package de.luhmer.owncloudnewsreader.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.luhmer.owncloudnewsreader.SettingsActivity;
import de.luhmer.owncloudnewsreader.ai.engine.AiEmbedder;
import de.luhmer.owncloudnewsreader.ai.engine.AiEngineManager;
import de.luhmer.owncloudnewsreader.ai.engine.AiException;
import de.luhmer.owncloudnewsreader.ai.engine.AiModelInfo;
import de.luhmer.owncloudnewsreader.ai.engine.CancelToken;
import de.luhmer.owncloudnewsreader.ai.prompt.AiPromptBuilder;
import de.luhmer.owncloudnewsreader.ai.prompt.AiPrompts;
import de.luhmer.owncloudnewsreader.database.ai.AiCentroidStore;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.AiDecisionStore;
import de.luhmer.owncloudnewsreader.database.ai.AiEmbeddingStore;
import de.luhmer.owncloudnewsreader.database.ai.AiScoreStore;

/**
 * Stages 1-4 of the triage run, with no LLM anywhere: candidates, embeddings, centroids, prefilter,
 * rank. <b>After this the feature is useful on its own</b> — the For You folder fills with the most
 * recent articles while cold, and with the ones nearest the user's liked pile once warm.
 *
 * <p>Deliberately free of WorkManager and of every Android type except {@link Context} and
 * {@link SharedPreferences}, so the whole thing runs under plain JUnit against an in-memory database
 * and a fake embedder. {@code AiTriageWorker} is a thin wrapper around {@link #run(long, long)}.</p>
 *
 * <h3>Degradation</h3>
 * Every engine failure is caught and named in {@link Report#degraded}; none of them aborts the run.
 * No embedder means no new vectors, which means {@code SIM_SCORE} stays NULL, which means the
 * prefilter takes its cold branch and selects by recency. That is a working product, not an error
 * state, and it is why the model download can be optional.
 *
 * <h3>The Phase 6 seam</h3>
 * The prefilter's survivors are written as {@code STATUS='selected'} with
 * {@code RANK_SCORE = round(sim, 3)} and {@code LLM_SCORE IS NULL}. When scoring lands it slots in
 * between the prefilter and the write, replaces the rank with
 * {@code round(eff + 0.5*sim, 3)} and re-derives the status from {@code eff >= 2}. The write path
 * ({@link AiScoreStore#writeResult}) and the {@code AI_KEY} fan-out do not change.
 */
public final class AiTriagePipeline {

    private static final String TAG = "AiTriagePipeline";

    /** Ceiling on the candidate scan. Not a correctness bound - the prefilter is. */
    public static final int CANDIDATE_LIMIT = 400;
    /** Opt-in charging mode: scan every unread candidate instead of a bounded batch. */
    public static final int ALL_UNREAD_BUDGET = Integer.MAX_VALUE;

    /**
     * Embeddings per sync. Separate from {@code topK} and necessarily {@code >=} it, because every
     * candidate must be embedded <i>before</i> the prefilter can rank it. 60 articles is 10-42 s of
     * CPU depending on the device; 200 would be a thermal event. The backlog carries forward.
     */
    public static final int EMBED_MAX_PER_SYNC = 60;

    public static final int TOP_K_FULL_TIER = 30;
    public static final int TOP_K_LIGHT_TIER = 15;

    /** {@code AI_META}: how far the embedding backlog has got. */
    public static final String KEY_EMBED_BACKLOG = "embed_backlog_cursor";
    /** {@code AI_META}: the sync id of the most recent run. */
    public static final String KEY_ACTIVE_SYNC = "active_sync_id";

    /** The embedding task type. Half of the vector-space compatibility key. */
    public static final String DEFAULT_TASK = "CLUSTERING";

    /** What a run did. Never an exception; the caller logs it and moves on. */
    public static final class Report {
        public int candidates;
        public int enqueued;
        public int embedded;
        public int backfilled;
        public int selected;
        public int discarded;
        /** Articles the LLM actually judged this run. */
        public int scored;
        /** Articles that reached the scorer and came back {@code LLM_SCORE IS NULL}. */
        public int unjudged;
        /** Tags the model emitted that were not in the closed vocabulary. A per-model metric. */
        public int inventedTags;
        public boolean cold = true;
        /** null when nothing degraded, else the {@link AiException.Kind} that fired. */
        public String degraded;

        @Override
        public String toString() {
            return "AiTriage[candidates=" + candidates + " enqueued=" + enqueued
                    + " embedded=" + embedded + " backfilled=" + backfilled
                    + " selected=" + selected + " discarded=" + discarded
                    + " scored=" + scored + " unjudged=" + unjudged
                    + " inventedTags=" + inventedTags
                    + " cold=" + cold + " degraded=" + degraded + "]";
        }
    }

    /**
     * Everything stage 5 needs. Absent ({@code null}) means "no scoring this run", which is a
     * first-class, fully working configuration: the folder ranks on similarity alone.
     */
    public static final class Scoring {
        public final AiModelInfo model;
        public final AiPrompts prompts;
        public final String interestsNote;
        public final Collection<String> themeSlugs;
        public final List<AiPromptBuilder.Correction> corrections;
        public final Locale locale;
        public final boolean gpu;
        public final int preferredBatch;
        public final CancelToken token;

        public Scoring(AiModelInfo model, AiPrompts prompts, String interestsNote,
                       Collection<String> themeSlugs, List<AiPromptBuilder.Correction> corrections,
                       Locale locale, boolean gpu, int preferredBatch, CancelToken token) {
            this.model = model;
            this.prompts = prompts;
            this.interestsNote = interestsNote;
            this.themeSlugs = themeSlugs;
            this.corrections = corrections;
            this.locale = locale;
            this.gpu = gpu;
            this.preferredBatch = preferredBatch;
            this.token = token == null ? CancelToken.none() : token;
        }
    }

    private final Context app;
    private final AiDb db;
    private final AiEngineManager engines;
    private final int topK;
    private final int candidateLimit;
    private final boolean recentOnly;
    private Scoring scoring;

    public AiTriagePipeline(Context context, AiDb db, SharedPreferences prefs) {
        this(context, db, new AiEngineManager(context, db), topKFor(context, prefs),
                CANDIDATE_LIMIT, true);
    }

    /** Test seam: inject the engine manager and the budget directly. */
    public AiTriagePipeline(Context context, AiDb db, AiEngineManager engines, int topK) {
        this(context, db, engines, topK, CANDIDATE_LIMIT, true);
    }

    /** Test seam: inject the engine manager plus both budgets directly. */
    public AiTriagePipeline(Context context, AiDb db, AiEngineManager engines, int topK,
                            int candidateLimit) {
        this(context, db, engines, topK, candidateLimit, true);
    }

    /** Test seam: inject all candidate selection knobs directly. */
    public AiTriagePipeline(Context context, AiDb db, AiEngineManager engines, int topK,
                            int candidateLimit, boolean recentOnly) {
        this.app = context == null ? null : context.getApplicationContext();
        this.db = db;
        this.engines = engines;
        this.topK = Math.max(1, topK);
        this.candidateLimit = Math.max(1, candidateLimit);
        this.recentOnly = recentOnly;
    }

    /**
     * Enables stage 5. Left unset the pipeline is exactly the Phase 4 taste model, which is a
     * shippable product on its own — that is why this is a builder call and not a constructor
     * argument.
     */
    public AiTriagePipeline withScoring(Scoring scoring) {
        this.scoring = scoring;
        return this;
    }

    /** {@code sp_ai_batch_budget}, defaulting to the device tier (30 on T2, 15 on T1). */
    public static int topKFor(Context context, SharedPreferences prefs) {
        int fallback = AiCapability.tier(context) == AiCapability.Tier.FULL
                ? TOP_K_FULL_TIER : TOP_K_LIGHT_TIER;
        if (prefs == null) {
            return fallback;
        }
        String v = prefs.getString(SettingsActivity.SP_AI_BATCH_BUDGET, null);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static boolean allUnreadWhileCharging(SharedPreferences prefs, boolean charging) {
        return charging && prefs != null && prefs.getBoolean(
                SettingsActivity.CB_AI_ANALYZE_ALL_UNREAD_WHILE_CHARGING, false);
    }

    /**
     * Runs one triage pass.
     *
     * @param nowMs  the clock, injectable so the 7-day window is testable
     * @param syncId identifies this run's rows; the resume selector reads it
     */
    public Report run(long nowMs, long syncId) {
        final Report report = new Report();
        if (db == null) {
            report.degraded = "no_database";
            return report;
        }
        final AiScoreStore scores = new AiScoreStore(db);
        final AiEmbeddingStore embeddings = new AiEmbeddingStore(db);
        final AiCentroidStore centroids = new AiCentroidStore(db);
        final AiDecisionStore decisions = new AiDecisionStore(db);

        db.putMetaLong(KEY_ACTIVE_SYNC, syncId);

        // ---- stage 1: candidates ------------------------------------------------------------
        List<AiCandidates.Candidate> all = AiCandidates.select(db, nowMs, candidateLimit,
                recentOnly);
        report.candidates = all.size();
        for (AiCandidates.Candidate c : all) {
            scores.enqueue(c.rssItemId, c.aiKey, syncId);
            report.enqueued++;
        }
        // One representative per AI_KEY: the write path fans the result back out over the group,
        // so scoring a duplicate twice would be pure waste (PLAN D3).
        List<AiCandidates.Candidate> unique = dedupeByAiKey(all);

        // ---- stage 2: embeddings ------------------------------------------------------------
        String model = AiEngineManager.EMBEDDING_MODEL_ID;
        String task = DEFAULT_TASK;
        try {
            String[] used = embedMissing(unique, embeddings, report);
            model = used[0];
            task = used[1];
        } catch (AiException e) {
            report.degraded = e.kind.name();
            Log.i(TAG, "no embeddings this run (" + e.kind + "): " + e.getMessage());
        }

        // ---- stage 3: centroids -------------------------------------------------------------
        report.backfilled = centroids.backfill();
        if (!centroids.consistent()) {
            Log.w(TAG, "centroid member counts disagree with AI_TASTE - rebuilding");
            centroids.rebuild();
        }
        centroids.refreshCacheKey(model, task, decisions.decisionCount());

        float[] likedUnit = centroids.unit(AiCentroidStore.CLASS_KEPT);
        float[] rejectedUnit = centroids.unit(AiCentroidStore.CLASS_REJECTED);
        boolean cold = AiPrefilter.isCold(likedUnit, rejectedUnit,
                centroids.memberCount(AiCentroidStore.CLASS_KEPT),
                centroids.memberCount(AiCentroidStore.CLASS_REJECTED),
                decisions.decisionCount());
        report.cold = cold;

        // ---- similarity ---------------------------------------------------------------------
        // While cold the centroids are passed as null, which yields exactly the sentinels we want:
        // NULL for an article that was never embedded, 0.0 for one that was. The prefilter ignores
        // both, but AI_SCORE must still be able to tell "unjudgeable" from "judged neutral".
        final float[] liked = cold ? null : likedUnit;
        final float[] rejected = cold ? null : rejectedUnit;
        List<AiPrefilter.Item> items = new ArrayList<>(unique.size());
        for (AiCandidates.Candidate c : unique) {
            c.sim = AiSimilarity.sim(embeddings.get(c.aiKey), liked, rejected);
            items.add(new AiPrefilter.Item(c.aiKey, c.rssItemId, c.sim, c.pubDate, c.fetchedAt));
        }

        // ---- stage 4: prefilter -------------------------------------------------------------
        AiPrefilter.Outcome outcome = AiPrefilter.run(items, cold, topK);

        // ---- stage 5: scoring ---------------------------------------------------------------
        // Every survivor of the prefilter leaves this call with a row in AI_SCORE, judged or not.
        scoreAndCommit(outcome.selected, byAiKey(unique), scores, nowMs, syncId, report);

        for (AiPrefilter.Dropped d : outcome.dropped) {
            AiScoreStore.Row row = new AiScoreStore.Row();
            row.aiKey = d.item.aiKey;
            row.rssItemId = d.item.rssItemId;
            row.status = AiScoreStore.STATUS_DISCARDED;
            row.discardReason = d.reason;               // recorded, never deleted
            row.simScore = d.item.sim;
            row.llmScore = null;
            row.effScore = null;
            row.rankScore = AiRank.rank(null, 0, d.item.sim);
            row.scoredAt = nowMs;
            row.syncId = syncId;
            scores.writeResult(row);
            report.discarded++;
        }

        Log.i(TAG, report.toString());
        return report;
    }

    /**
     * Stage 5 — score the prefilter's survivors, then rank and commit each one <b>as it completes</b>.
     *
     * <p>Three properties this method exists to hold:</p>
     * <ul>
     *   <li><b>Every selected article gets a row.</b> If the engine never opens, if it OOMs, if the
     *       user disables AI halfway through — the articles the scorer never reached are written
     *       here with {@code LLM_SCORE = NULL}, which is "not judged yet, try again", not
     *       "judged irrelevant".</li>
     *   <li><b>The write path does not change.</b> {@link AiScoreStore#writeResult} with
     *       {@code aiKey} set is still the fan-out over duplicates.</li>
     *   <li><b>An unjudged article stays selected.</b> It ranks on similarity alone rather than
     *       vanishing from the folder because an inference failed.</li>
     * </ul>
     */
    private void scoreAndCommit(List<AiPrefilter.Item> selected,
                                Map<String, AiCandidates.Candidate> byKey,
                                final AiScoreStore scores, final long nowMs, final long syncId,
                                final Report report) {
        final Set<String> committed = new HashSet<>();
        final Map<String, AiPrefilter.Item> itemByKey = new LinkedHashMap<>();
        for (AiPrefilter.Item it : selected) {
            itemByKey.put(it.aiKey, it);
        }
        if (scoring == null || selected.isEmpty()) {
            for (AiPrefilter.Item it : selected) {
                commitScored(scores, it, null, nowMs, syncId, report);
                committed.add(it.aiKey);
            }
            return;
        }

        final List<AiScorer.Item> work = new ArrayList<>(selected.size());
        Map<Long, String> feedTitles = feedTitles();
        for (AiPrefilter.Item it : selected) {
            AiCandidates.Candidate c = byKey.get(it.aiKey);
            String title = c == null ? "" : AiText.sanitise(c.title);
            String body = c == null ? "" : AiText.sanitise(c.body);
            String source = c == null ? "" : feedTitles.get(Long.valueOf(c.feedId));
            work.add(new AiScorer.Item(it.aiKey, it.rssItemId, title,
                    source == null ? "" : source, body));
        }

        final AiScorer.Sink sink = (item, verdict) -> {
            AiPrefilter.Item it = itemByKey.get(item.aiKey);
            if (it == null) {
                return;
            }
            commitScored(scores, it, verdict, nowMs, syncId, report);
            committed.add(item.aiKey);
        };

        try {
            final long deadline = System.currentTimeMillis() + AiScorer.RUN_BUDGET_MS;
            engines.withLlm(scoring.model, scoring.gpu, scoring.token, llm -> {
                AiScorer scorer = new AiScorer(scoring.prompts, scoring.interestsNote,
                        scoring.themeSlugs, scoring.corrections, scoring.locale,
                        AiScorer.batchSizeFor(llm, scoring.preferredBatch));
                AiScorer.Report r = scorer.score(llm, work, sink, scoring.token, deadline);
                report.scored = r.scored;
                report.unjudged = r.unjudged;
                report.inventedTags = r.inventedTags;
                if (r.degraded != null) {
                    report.degraded = r.degraded;
                }
                return null;
            });
        } catch (AiException e) {
            report.degraded = e.kind.name();
            Log.i(TAG, "no scoring this run (" + e.kind + "): " + e.getMessage());
        } catch (Throwable t) {
            report.degraded = AiException.Kind.RUNTIME.name();
            Log.w(TAG, "scoring failed", t);
        }

        // The terminal guarantee: anything the scorer never reached is still written, unjudged.
        for (AiPrefilter.Item it : selected) {
            if (!committed.contains(it.aiKey)) {
                commitScored(scores, it, null, nowMs, syncId, report);
                report.unjudged++;
            }
        }
    }

    /** One article's final row. {@code verdict == null} means the LLM never reached it. */
    private static void commitScored(AiScoreStore scores, AiPrefilter.Item it,
                                     AiScorer.Verdict verdict, long nowMs, long syncId,
                                     Report report) {
        Integer llm = verdict == null ? null : verdict.llmScore;
        Integer eff = AiRank.eff(llm, 0);
        AiScoreStore.Row row = new AiScoreStore.Row();
        row.aiKey = it.aiKey;
        row.rssItemId = it.rssItemId;
        row.simScore = it.sim;
        row.llmScore = llm;
        row.effScore = eff;
        row.rankScore = AiRank.rank(llm, 0, it.sim);
        row.themes = verdict == null ? null : verdict.themes;
        row.flags = verdict == null ? null : verdict.flags;
        row.why = verdict == null ? null : verdict.why;
        row.scoredAt = nowMs;
        row.syncId = syncId;
        if (llm == null || AiRank.selectable(eff)) {
            // Never judged => still shown, ranked on similarity, retried next sync.
            row.status = AiScoreStore.STATUS_SELECTED;
            row.discardReason = null;
            report.selected++;
        } else {
            row.status = AiScoreStore.STATUS_DISCARDED;
            row.discardReason = AiScoreStore.REASON_LOW_SCORE;
            report.discarded++;
        }
        scores.writeResult(row);
    }

    private static Map<String, AiCandidates.Candidate> byAiKey(List<AiCandidates.Candidate> in) {
        Map<String, AiCandidates.Candidate> out = new LinkedHashMap<>();
        for (AiCandidates.Candidate c : in) {
            out.put(c.aiKey, c);
        }
        return out;
    }

    /**
     * {@code FEED_ID -> FEED_TITLE}, for the {@code (source)} half of the article payload. One
     * query rather than a join on the candidate SQL, whose exact shape other tests depend on.
     */
    private Map<Long, String> feedTitles() {
        Map<Long, String> out = new LinkedHashMap<>();
        try (android.database.Cursor c =
                     db.query("SELECT _id, FEED_TITLE FROM FEED", null)) {
            while (c.moveToNext()) {
                out.put(Long.valueOf(c.getLong(0)), c.isNull(1) ? "" : c.getString(1));
            }
        } catch (Throwable t) {
            Log.w(TAG, "feed titles unavailable", t);
        }
        return out;
    }

    /**
     * Embeds up to {@link #EMBED_MAX_PER_SYNC} not-yet-embedded articles, newest first.
     *
     * <p><b>Order matters here.</b> {@code assertCompatible()} may wipe every stored vector, so the
     * "which articles still need embedding" list has to be built <i>after</i> it, inside the open
     * engine. Building it first meant that the sync in which the model changed embedded nothing at
     * all — every candidate looked already-embedded, then its vector was deleted underneath.</p>
     *
     * @return {@code {model, task}} actually used, so the centroid cache key names the right space
     */
    private String[] embedMissing(List<AiCandidates.Candidate> candidates,
                                  AiEmbeddingStore embeddings, Report report) throws AiException {
        if (!anythingToEmbed(candidates, embeddings) && !embeddings.hasForeign(
                AiEngineManager.EMBEDDING_MODEL_ID, DEFAULT_TASK)) {
            // Every candidate already has a vector in the right space: nothing to load.
            db.putMetaLong(KEY_EMBED_BACKLOG, 0L);
            return new String[]{AiEngineManager.EMBEDDING_MODEL_ID, DEFAULT_TASK};
        }
        return engines.withEmbedder(embedder -> {
            final String model = embedder.modelId();
            final String task = embedder.task();
            // Explicit migration: a different model or task makes every stored vector and both
            // centroids incomparable. Never silent drift.
            embeddings.assertCompatible(model, task);

            final List<AiCandidates.Candidate> todo = new ArrayList<>();
            for (AiCandidates.Candidate c : candidates) {
                if (todo.size() >= EMBED_MAX_PER_SYNC) {
                    break;  // candidates arrive newest-first; the rest is next run's backlog
                }
                if (!embeddings.has(c.aiKey)) {
                    todo.add(c);
                }
            }
            db.putMetaLong(KEY_EMBED_BACKLOG, backlogAfter(candidates, embeddings, todo));

            for (AiCandidates.Candidate c : todo) {
                float[] v = embedSafely(embedder, c);
                if (v != null) {
                    embeddings.put(c.aiKey, model, task, v);
                    report.embedded++;
                }
            }
            return new String[]{model, task};
        });
    }

    private static boolean anythingToEmbed(List<AiCandidates.Candidate> candidates,
                                           AiEmbeddingStore embeddings) {
        for (AiCandidates.Candidate c : candidates) {
            if (!embeddings.has(c.aiKey)) {
                return true;
            }
        }
        return false;
    }

    /** How many candidates this run leaves unembedded. Purely informational. */
    private static long backlogAfter(List<AiCandidates.Candidate> candidates,
                                     AiEmbeddingStore embeddings,
                                     List<AiCandidates.Candidate> todo) {
        int missing = 0;
        for (AiCandidates.Candidate c : candidates) {
            if (!embeddings.has(c.aiKey)) {
                missing++;
            }
        }
        return Math.max(0, missing - todo.size());
    }

    /**
     * One article's vector, or {@code null}. A single failed article must not cost the whole batch:
     * it simply stays unembedded and the prefilter treats it as unjudgeable.
     */
    private static float[] embedSafely(AiEmbedder embedder, AiCandidates.Candidate c) {
        try {
            return embedder.embed(c.title, c.body);
        } catch (AiException e) {
            Log.w(TAG, "embed failed for " + c.aiKey + " (" + e.kind + ")");
            return null;
        }
    }

    /** Keeps the first occurrence of each {@code AI_KEY}; the input is already newest-first. */
    static List<AiCandidates.Candidate> dedupeByAiKey(List<AiCandidates.Candidate> in) {
        Map<String, AiCandidates.Candidate> seen = new LinkedHashMap<>();
        for (AiCandidates.Candidate c : in) {
            if (!seen.containsKey(c.aiKey)) {
                seen.put(c.aiKey, c);
            }
        }
        return new ArrayList<>(seen.values());
    }
}
