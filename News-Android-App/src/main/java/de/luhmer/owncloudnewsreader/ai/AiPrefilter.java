package de.luhmer.owncloudnewsreader.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import de.luhmer.owncloudnewsreader.database.ai.AiScoreStore;

/**
 * Stage 2 — the taste model's selector. Verbatim port of veille
 * {@code pipeline.py::_stage_prefilter} (770-870), <b>including the last third</b>.
 *
 * <p>This is the single lever that keeps cost flat as feeds grow: the expensive stage downstream
 * only ever sees {@code topK} items regardless of how many articles arrived.</p>
 *
 * <h3>Cold</h3>
 * Either centroid missing, or the warm-up gate (D29) not met. Similarity is <b>skipped entirely</b>:
 * the selection is the most recent {@code topK} by {@code (pubDate, fetchedAt) DESC}, undated last.
 * Note that this concerns the <i>selection</i> only — {@code AI_SCORE.SIM_SCORE} still records the
 * sentinel the article earned ({@code NULL} if never embedded, {@code 0.0} if embedded with no
 * centroid to compare against), because those two states are not the same thing and a later run
 * must be able to tell them apart.
 *
 * <h3>Warm</h3>
 * <ol>
 *   <li>{@code above} = embedded articles with {@code sim >= SIM_FLOOR}, sorted by
 *       {@code (sim, recency) DESC}. Selected up to {@code topK}; the overflow is
 *       {@code below_top_k}.</li>
 *   <li>{@code below} = embedded articles under the floor: {@code low_similarity}.</li>
 *   <li><b>The clause every summary drops:</b> articles that were <b>never embedded</b> are not
 *       dissimilar, they are <b>unjudgeable</b>. They fill whatever capacity the scored ones left,
 *       by recency, and only the overflow of <i>that</i> is dropped — as {@code below_top_k}, never
 *       as {@code low_similarity}, because that would be a lie about them.</li>
 * </ol>
 * Without step 3 the first warm sync after an embedding backlog scores three articles instead of
 * filling its budget.
 *
 * <p>Discard reasons are <b>recorded, never deleted</b>. Rows stay in {@code AI_SCORE} with
 * {@code STATUS='discarded'} and a reason, which is what makes "why is this not in my For You?"
 * answerable.</p>
 */
public final class AiPrefilter {

    /** D29 warm-up gate: below any of these, behave exactly as if a centroid were missing. */
    public static final int MIN_PER_CLASS = 3;
    public static final int MIN_TOTAL_DECISIONS = 10;

    private AiPrefilter() {
        // no instances
    }

    /** One article as the prefilter sees it. */
    public static final class Item {
        public final String aiKey;
        public final long rssItemId;
        /** {@code null} = never embedded. Not "dissimilar". */
        public final Double sim;
        public final Long pubDate;
        public final Long fetchedAt;

        public Item(String aiKey, long rssItemId, Double sim, Long pubDate, Long fetchedAt) {
            this.aiKey = aiKey;
            this.rssItemId = rssItemId;
            this.sim = sim;
            this.pubDate = pubDate;
            this.fetchedAt = fetchedAt;
        }
    }

    /** A rejected article and why. The reason is persisted, not logged and forgotten. */
    public static final class Dropped {
        public final Item item;
        public final String reason;

        Dropped(Item item, String reason) {
            this.item = item;
            this.reason = reason;
        }
    }

    public static final class Outcome {
        public final List<Item> selected;
        public final List<Dropped> dropped;
        public final boolean cold;

        Outcome(List<Item> selected, List<Dropped> dropped, boolean cold) {
            this.selected = selected;
            this.dropped = dropped;
            this.cold = cold;
        }
    }

    /**
     * The warm-up predicate (PLAN D29) — a one-line strengthening of veille's own
     * "either centroid missing" test, not a separate subsystem.
     *
     * @param likedUnit    the liked centroid, or null when missing
     * @param rejectedUnit the rejected centroid, or null when missing
     * @param likedN       members folded into the liked centroid
     * @param rejectedN    members folded into the rejected centroid
     * @param totalDecisions {@code AI_META.decision_count} — every decision counts toward the gate,
     *                       including ones whose article was never embedded
     */
    public static boolean isCold(float[] likedUnit, float[] rejectedUnit,
                                 int likedN, int rejectedN, int totalDecisions) {
        if (likedUnit == null || rejectedUnit == null) {
            return true;
        }
        return likedN < MIN_PER_CLASS || rejectedN < MIN_PER_CLASS
                || totalDecisions < MIN_TOTAL_DECISIONS;
    }

    /**
     * @param topK clamped to at least 1: a budget of zero would select nothing forever
     */
    public static Outcome run(List<Item> candidates, boolean cold, int topK) {
        final int k = Math.max(1, topK);
        final List<Item> selected = new ArrayList<>();
        final List<Dropped> dropped = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) {
            return new Outcome(selected, dropped, cold);
        }

        if (cold) {
            List<Item> ranked = new ArrayList<>(candidates);
            Collections.sort(ranked, RECENCY_DESC);
            take(ranked, k, selected, dropped, AiScoreStore.REASON_BELOW_TOP_K);
            return new Outcome(selected, dropped, true);
        }

        List<Item> above = new ArrayList<>();
        List<Item> below = new ArrayList<>();
        List<Item> unscored = new ArrayList<>();
        for (Item it : candidates) {
            if (it.sim == null) {
                unscored.add(it);                       // unjudgeable, NOT dissimilar
            } else if (it.sim >= AiSimilarity.SIM_FLOOR) {
                above.add(it);
            } else {
                below.add(it);
            }
        }

        Collections.sort(above, SIM_THEN_RECENCY_DESC);
        take(above, k, selected, dropped, AiScoreStore.REASON_BELOW_TOP_K);
        for (Item it : below) {
            dropped.add(new Dropped(it, AiScoreStore.REASON_LOW_SIMILARITY));
        }

        // >>> the clause everyone misses <<<
        int room = Math.max(0, k - selected.size());
        Collections.sort(unscored, RECENCY_DESC);
        take(unscored, room, selected, dropped, AiScoreStore.REASON_BELOW_TOP_K);

        return new Outcome(selected, dropped, false);
    }

    private static void take(List<Item> ranked, int room, List<Item> selected,
                             List<Dropped> dropped, String overflowReason) {
        for (int i = 0; i < ranked.size(); i++) {
            if (i < room) {
                selected.add(ranked.get(i));
            } else {
                dropped.add(new Dropped(ranked.get(i), overflowReason));
            }
        }
    }

    /** {@code (pubDate, fetchedAt) DESC}, undated LAST, {@code rssItemId} as the tiebreaker. */
    static final Comparator<Item> RECENCY_DESC = (a, b) -> {
        int byDate = compareNullsLast(a.pubDate, b.pubDate);
        if (byDate != 0) {
            return byDate;
        }
        int byFetch = compareNullsLast(a.fetchedAt, b.fetchedAt);
        if (byFetch != 0) {
            return byFetch;
        }
        return Long.compare(b.rssItemId, a.rssItemId);
    };

    /** {@code sim DESC} first, then the recency key. Only ever applied to embedded articles. */
    static final Comparator<Item> SIM_THEN_RECENCY_DESC = (a, b) -> {
        double sa = a.sim == null ? 0d : a.sim;
        double sb = b.sim == null ? 0d : b.sim;
        int bySim = Double.compare(sb, sa);
        return bySim != 0 ? bySim : RECENCY_DESC.compare(a, b);
    };

    /** Descending order in which a null value sorts <b>after</b> every present value. */
    private static int compareNullsLast(Long a, Long b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        return Long.compare(b, a);
    }
}
