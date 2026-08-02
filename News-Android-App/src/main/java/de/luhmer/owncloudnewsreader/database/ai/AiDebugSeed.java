package de.luhmer.owncloudnewsreader.database.ai;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Debug-only seeding of {@code AI_SCORE}, so the "For you" folder can be exercised end to end long
 * before a model exists. Deterministic: the same article always gets the same score, so a reviewer
 * can re-seed and compare the ordering.
 *
 * <p>It writes through {@link AiScoreStore#writeResult(AiScoreStore.Row)}, which fans the verdict
 * out over every row sharing an {@code AI_KEY} — the exact shape the real pipeline must use
 * (PLAN D3), so seeding also exercises the duplicate-fingerprint path.</p>
 *
 * <p>Never called from a release code path; the menu entry that reaches it is {@code BuildConfig
 * .DEBUG}-only.</p>
 */
public final class AiDebugSeed {

    /** Synthetic sync id, so seeded rows are distinguishable from anything the pipeline wrote. */
    public static final long DEBUG_SYNC_ID = -1L;

    private static final String[] THEMES = {
            "drt", "operators", "competitors", "electrification", "regulation", "customer",
    };

    private AiDebugSeed() {
        // no instances
    }

    /**
     * Scores the {@code limit} most recent articles.
     *
     * @return the number of articles that ended up {@code selected}.
     */
    public static int seed(SQLiteDatabase database, int limit) {
        AiDb db = AiDb.of(database);
        AiScoreStore scores = new AiScoreStore(db);

        // AI_KEY -> the articles carrying it. LinkedHashMap so the newest key stays first.
        Map<String, List<Long>> byKey = new LinkedHashMap<>();
        try (Cursor c = db.query("SELECT _id, FINGERPRINT FROM RSS_ITEM"
                + " ORDER BY PUB_DATE DESC, _id DESC LIMIT " + limit, null)) {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String key = AiKeys.of(c.isNull(1) ? null : c.getString(1), id);
                List<Long> ids = byKey.get(key);
                if (ids == null) {
                    ids = new ArrayList<>(1);
                    byKey.put(key, ids);
                }
                ids.add(id);
            }
        }

        int selected = 0;
        long now = System.currentTimeMillis();
        for (Map.Entry<String, List<Long>> e : byKey.entrySet()) {
            String aiKey = e.getKey();
            long representative = e.getValue().get(0);

            for (Long id : e.getValue()) {
                scores.enqueue(id, aiKey, DEBUG_SYNC_ID);
            }

            int llm = scoreOf(aiKey);
            double sim = simOf(aiKey);

            AiScoreStore.Row row = new AiScoreStore.Row();
            row.rssItemId = representative;
            row.aiKey = aiKey;
            row.status = llm >= 2 ? AiScoreStore.STATUS_SELECTED : AiScoreStore.STATUS_DISCARDED;
            row.discardReason = llm >= 2 ? null : AiScoreStore.REASON_LOW_SCORE;
            row.simScore = sim;
            row.llmScore = llm;
            row.effScore = llm;
            row.rankScore = round3(llm + 0.5 * sim);
            row.themes = THEMES[(int) (Math.abs((long) aiKey.hashCode()) % THEMES.length)];
            row.flags = "";
            row.why = "Seeded by the debug menu (score " + llm + ").";
            row.scoredAt = now;
            row.syncId = DEBUG_SYNC_ID;
            scores.writeResult(row);

            if (llm >= 2) {
                selected++;
            }
        }
        return selected;
    }

    /** Removes everything this seeder wrote, leaving pipeline-written rows alone. */
    public static int clear(SQLiteDatabase database) {
        return AiDb.of(database).delete(AiSchema.T_SCORE, "SYNC_ID = ?",
                new String[]{String.valueOf(DEBUG_SYNC_ID)});
    }

    /** Deterministic pseudo-score in [0,3], skewed so roughly half the corpus is selected. */
    private static int scoreOf(String aiKey) {
        return (int) (Math.abs((long) aiKey.hashCode()) % 4L);
    }

    /** Deterministic pseudo-similarity in [-0.5, 0.5). */
    private static double simOf(String aiKey) {
        return (Math.abs((long) aiKey.hashCode()) % 1000L) / 1000.0 - 0.5;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
