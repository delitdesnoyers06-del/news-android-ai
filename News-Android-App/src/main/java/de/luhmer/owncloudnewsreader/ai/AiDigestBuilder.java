package de.luhmer.owncloudnewsreader.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups the articles selected since the last digest into theme blocks (veille
 * {@code domain/digests.py::create}).
 *
 * <h3>Three rules, all of them load-bearing</h3>
 * <ol>
 *   <li><b>The range filter is on selection time, not publication time.</b> You export what you
 *       <i>picked</i> since last time. An article published three weeks ago that the triage pass
 *       selected this morning belongs in this morning's digest; an article published an hour ago
 *       that was selected yesterday does not.</li>
 *   <li><b>Each article appears exactly once</b>, under {@code themes[0]}. The scoring prompt lists
 *       themes in relevance order, so the first one is the one the model thought mattered. An
 *       article in two sections makes a 12-item digest look like a 20-item one and makes
 *       "mark all as read" ambiguous.</li>
 *   <li><b>Blocks are ordered by {@code -sum(rank_score)}</b>, not by item count: three strong
 *       articles beat six weak ones, which is the whole point of having a ranking.</li>
 * </ol>
 *
 * <p>veille groups {@code country -> theme -> items}. The country axis exists because a team watches
 * several markets; one reader has one market, so that level is dropped (PLAN §3, product §3).</p>
 *
 * <p>Pure Java, no Android, no SQL: {@code AiDigests} does the reading and the writing.</p>
 */
public final class AiDigestBuilder {

    /**
     * Below this many newly-selected articles a digest is not worth a card, let alone an inference
     * (PLAN Q3 — a guess, to be re-measured in dogfood).
     */
    public static final int MIN_ITEMS = 5;

    /** Hard cap on what one digest may contain. Beyond this it is a feed, not a digest. */
    public static final int MAX_ITEMS = 60;

    private AiDigestBuilder() {
        // no instances
    }

    /** One selected article, as the builder sees it. */
    public static final class Item {
        public String aiKey;
        public long rssItemId;
        /** In relevance order, as the model emitted them. May be empty — that is normal. */
        public List<String> themes = new ArrayList<>();
        /** {@code null} when the article was never ranked. Sorts last. */
        public Double rankScore;
        /** When the pipeline selected it — {@code AI_SCORE.SCORED_AT}. The range key. */
        public long selectedAt;
        /** May be {@code null}: plenty of feeds omit a publication date. */
        public Long pubDate;
        public String title;
        public String why;
        public String source;

        public Item() {
            // fields are filled by the caller
        }

        public Item(String aiKey, long rssItemId, Double rankScore, long selectedAt,
                    String... themes) {
            this.aiKey = aiKey;
            this.rssItemId = rssItemId;
            this.rankScore = rankScore;
            this.selectedAt = selectedAt;
            for (String t : themes) {
                this.themes.add(t);
            }
        }
    }

    /** One theme section. {@code theme == null} is the untagged block. */
    public static final class Block {
        public final String theme;
        public final List<Item> items = new ArrayList<>();
        public double weight;

        Block(String theme) {
            this.theme = theme;
        }
    }

    /** What one digest looks like before it is written to {@code AI_DIGEST_ITEM}. */
    public static final class Result {
        public final List<Block> blocks = new ArrayList<>();
        public final long rangeFrom;
        public final long rangeTo;
        public int itemCount;

        Result(long rangeFrom, long rangeTo) {
            this.rangeFrom = rangeFrom;
            this.rangeTo = rangeTo;
        }

        /** Every item, in the order they are rendered — the {@code POS} order. */
        public List<Item> flattened() {
            List<Item> out = new ArrayList<>(itemCount);
            for (Block b : blocks) {
                out.addAll(b.items);
            }
            return out;
        }

        public boolean isEmpty() {
            return itemCount == 0;
        }
    }

    /**
     * @param selected  candidates with {@code STATUS='selected'}; may contain duplicates sharing an
     *                  {@code AI_KEY} (the fan-out puts one row per {@code RSS_ITEM_ID})
     * @param rangeFrom exclusive lower bound on {@code selectedAt}
     * @param rangeTo   inclusive upper bound on {@code selectedAt}
     */
    public static Result build(List<Item> selected, long rangeFrom, long rangeTo) {
        Result result = new Result(rangeFrom, rangeTo);
        if (selected == null || selected.isEmpty()) {
            return result;
        }

        List<Item> ranked = new ArrayList<>(selected.size());
        for (Item i : selected) {
            if (i == null || i.aiKey == null) {
                continue;
            }
            // Exclusive on the low end so consecutive digests can never claim the same article,
            // inclusive on the high end so the article selected at "now" is in today's digest.
            if (i.selectedAt > rangeFrom && i.selectedAt <= rangeTo) {
                ranked.add(i);
            }
        }
        Collections.sort(ranked, RANK_ORDER);

        // Dedupe by AI_KEY: the score fan-out means a fingerprint-duplicate carries an identical
        // rank, so "first after sorting" is a stable, arbitrary-but-correct pick.
        Set<String> seenKeys = new HashSet<>();
        List<Item> unique = new ArrayList<>(ranked.size());
        for (Item i : ranked) {
            if (seenKeys.add(i.aiKey) && unique.size() < MAX_ITEMS) {
                unique.add(i);
            }
        }

        Map<String, Block> byTheme = new LinkedHashMap<>();
        Block untagged = null;
        for (Item i : unique) {
            String theme = primaryTheme(i);
            Block b;
            if (theme == null) {
                if (untagged == null) {
                    untagged = new Block(null);
                }
                b = untagged;
            } else {
                b = byTheme.get(theme);
                if (b == null) {
                    b = new Block(theme);
                    byTheme.put(theme, b);
                }
            }
            b.items.add(i);
            b.weight += i.rankScore == null ? 0d : i.rankScore.doubleValue();
        }

        List<Block> blocks = new ArrayList<>(byTheme.values());
        Collections.sort(blocks, BLOCK_ORDER);
        result.blocks.addAll(blocks);
        if (untagged != null) {
            // Always last. It is the "everything else" bucket; leading a digest with it would read
            // as the model having nothing to say, even when the tagged blocks below are strong.
            result.blocks.add(untagged);
        }
        result.itemCount = unique.size();
        return result;
    }

    /**
     * {@code themes[0]}, normalised. {@code null} when the article carries no theme at all — which
     * is the entire similarity-only configuration, so it must render, not disappear.
     */
    public static String primaryTheme(Item i) {
        if (i == null || i.themes == null) {
            return null;
        }
        for (String t : i.themes) {
            if (t == null) {
                continue;
            }
            String s = t.trim().toLowerCase(java.util.Locale.ROOT);
            if (!s.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    /** Splits a comma-joined {@code AI_SCORE.THEMES} value, order preserved. */
    public static List<String> splitThemes(String joined) {
        List<String> out = new ArrayList<>();
        if (joined == null) {
            return out;
        }
        for (String part : joined.split(",")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** The list order, mirroring the For You SQL: rank DESC, pubDate DESC, id DESC. */
    private static final Comparator<Item> RANK_ORDER = new Comparator<Item>() {
        @Override
        public int compare(Item a, Item b) {
            int c = Double.compare(score(b), score(a));
            if (c != 0) {
                return c;
            }
            c = Long.compare(pub(b), pub(a));
            if (c != 0) {
                return c;
            }
            return Long.compare(b.rssItemId, a.rssItemId);
        }

        private double score(Item i) {
            // NULL ranks last under DESC, exactly as SQLite orders it.
            return i.rankScore == null ? Double.NEGATIVE_INFINITY : i.rankScore.doubleValue();
        }

        private long pub(Item i) {
            return i.pubDate == null ? Long.MIN_VALUE : i.pubDate.longValue();
        }
    };

    /** {@code -sum(rank_score)}, then the theme name so the order is deterministic on a tie. */
    private static final Comparator<Block> BLOCK_ORDER = new Comparator<Block>() {
        @Override
        public int compare(Block a, Block b) {
            int c = Double.compare(b.weight, a.weight);
            if (c != 0) {
                return c;
            }
            return a.theme.compareTo(b.theme);
        }
    };
}
