package de.luhmer.owncloudnewsreader.database.ai;

import android.content.ContentValues;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;

/**
 * CRUD on {@code AI_SCORE} — the transient, per-article triage state.
 *
 * <p>Two invariants this class exists to protect:</p>
 * <ul>
 *   <li>{@code STATUS} only ever holds a <b>machine</b> word: {@code queued|selected|discarded}.
 *       The pipeline never writes {@code kept} — that is a human's word and it lives in
 *       {@code AI_TASTE.STATE} (PLAN D28).</li>
 *   <li>Scores are <b>fanned out over {@code AI_KEY}</b> on write, so every fingerprint-duplicate of
 *       an article carries an identical rank. That is what makes the bare
 *       {@code " GROUP BY FINGERPRINT "} injected by {@code NewsReaderDetailFragment} harmless:
 *       SQLite picks an arbitrary group member and every member has the same
 *       {@code RANK_SCORE} (PLAN D3).</li>
 * </ul>
 *
 * <p>{@code NULL} and {@code 0} are different and both load-bearing:
 * {@code LLM_SCORE IS NULL} = never judged, retry; {@code LLM_SCORE = 0} = judged off-topic, final.
 * {@code SIM_SCORE IS NULL} = never embedded; {@code SIM_SCORE = 0.0} = embedded, cold start.</p>
 */
public class AiScoreStore {

    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_SELECTED = "selected";
    public static final String STATUS_DISCARDED = "discarded";

    public static final String REASON_LOW_SIMILARITY = "low_similarity";
    public static final String REASON_BELOW_TOP_K = "below_top_k";
    public static final String REASON_LOW_SCORE = "low_score";
    public static final String REASON_OUT_OF_WINDOW = "out_of_window";

    private static final String COLUMNS =
            "RSS_ITEM_ID, AI_KEY, STATUS, DISCARD_REASON, SIM_SCORE, LLM_SCORE, EFF_SCORE,"
                    + " RANK_SCORE, THEMES, FLAGS, WHY, SCORED_AT, SYNC_ID";

    private final AiDb db;

    public AiScoreStore(AiDb db) {
        this.db = db;
    }

    /** One row of {@code AI_SCORE}. Nullable boxes are the sentinels described on the class. */
    public static class Row {
        public long rssItemId;
        public String aiKey;
        public String status;
        public String discardReason;
        public Double simScore;
        public Integer llmScore;
        public Integer effScore;
        public Double rankScore;
        public String themes;
        public String flags;
        public String why;
        public Long scoredAt;
        public Long syncId;
    }

    /**
     * Inserts a {@code queued} row for an article, or refreshes the {@code AI_KEY} / {@code SYNC_ID}
     * of the existing one. Never clobbers a score that is already there.
     */
    public void enqueue(long rssItemId, String aiKey, long syncId) {
        // No UPSERT: minSdk 24 ships SQLite 3.9, and `ON CONFLICT ... DO UPDATE` needs 3.24.
        db.exec("INSERT OR IGNORE INTO AI_SCORE (RSS_ITEM_ID, AI_KEY, STATUS, SYNC_ID)"
                        + " VALUES (?, ?, ?, ?)",
                new Object[]{rssItemId, aiKey, STATUS_QUEUED, syncId});
        db.exec("UPDATE AI_SCORE SET AI_KEY = ?, SYNC_ID = ? WHERE RSS_ITEM_ID = ?",
                new Object[]{aiKey, syncId, rssItemId});
    }

    public Row get(long rssItemId) {
        try (Cursor c = db.query("SELECT " + COLUMNS + " FROM AI_SCORE WHERE RSS_ITEM_ID = ?",
                new String[]{String.valueOf(rssItemId)})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    public List<Row> byStatus(String status) {
        try (Cursor c = db.query("SELECT " + COLUMNS + " FROM AI_SCORE WHERE STATUS = ?"
                + " ORDER BY RANK_SCORE DESC", new String[]{status})) {
            List<Row> out = new ArrayList<>(c.getCount());
            while (c.moveToNext()) {
                out.add(read(c));
            }
            return out;
        }
    }

    public List<Row> byAiKey(String aiKey) {
        try (Cursor c = db.query("SELECT " + COLUMNS + " FROM AI_SCORE WHERE AI_KEY = ?"
                + " ORDER BY RSS_ITEM_ID", new String[]{aiKey})) {
            List<Row> out = new ArrayList<>(c.getCount());
            while (c.moveToNext()) {
                out.add(read(c));
            }
            return out;
        }
    }

    public int count() {
        return (int) db.queryLong("SELECT COUNT(*) FROM AI_SCORE", null, 0L);
    }

    public int countByStatus(String status) {
        return (int) db.queryLong("SELECT COUNT(*) FROM AI_SCORE WHERE STATUS = ?",
                new String[]{status}, 0L);
    }

    /** The AI_KEY of an article, or null when it has no score row. */
    public String aiKeyOf(long rssItemId) {
        return db.queryString("SELECT AI_KEY FROM AI_SCORE WHERE RSS_ITEM_ID = ?",
                new String[]{String.valueOf(rssItemId)});
    }

    /** Persists the similarity of a single article. {@code null} means "never embedded". */
    public void setSimScore(long rssItemId, Double sim) {
        ContentValues v = new ContentValues();
        if (sim == null) {
            v.putNull("SIM_SCORE");
        } else {
            v.put("SIM_SCORE", sim);
        }
        db.update(AiSchema.T_SCORE, v, "RSS_ITEM_ID = ?", new String[]{String.valueOf(rssItemId)});
    }

    public void setStatus(long rssItemId, String status, String discardReason) {
        ContentValues v = new ContentValues();
        v.put("STATUS", status);
        if (discardReason == null) {
            v.putNull("DISCARD_REASON");
        } else {
            v.put("DISCARD_REASON", discardReason);
        }
        db.update(AiSchema.T_SCORE, v, "RSS_ITEM_ID = ?", new String[]{String.valueOf(rssItemId)});
    }

    /**
     * Writes the outcome of a scoring run for one article and <b>fans it out over every row sharing
     * its {@code AI_KEY}</b> (PLAN D3). This is the only write path the pipeline may use for a
     * result; writing by {@code RSS_ITEM_ID} alone would leave duplicates at a different rank and
     * the {@code GROUP BY FINGERPRINT} pick would become observable.
     *
     * <p>Every value is nullable on purpose: an article that never reached the LLM is committed with
     * {@code llmScore == null} rather than dropped.</p>
     */
    public void writeResult(Row row) {
        ContentValues v = new ContentValues();
        v.put("STATUS", row.status);
        putOrNull(v, "DISCARD_REASON", row.discardReason);
        putOrNull(v, "SIM_SCORE", row.simScore);
        putOrNull(v, "LLM_SCORE", row.llmScore);
        putOrNull(v, "EFF_SCORE", row.effScore);
        putOrNull(v, "RANK_SCORE", row.rankScore);
        putOrNull(v, "THEMES", row.themes);
        putOrNull(v, "FLAGS", row.flags);
        putOrNull(v, "WHY", row.why);
        putOrNull(v, "SCORED_AT", row.scoredAt);
        putOrNull(v, "SYNC_ID", row.syncId);
        // The fan-out IS the update: AI_KEY covers the representative row too.
        db.update(AiSchema.T_SCORE, v, "AI_KEY = ?", new String[]{row.aiKey});
    }

    /** Rows of the current run that still have no verdict — the resume selector. */
    public List<Row> unjudged(long syncId, int limit) {
        try (Cursor c = db.query("SELECT " + COLUMNS + " FROM AI_SCORE"
                        + " WHERE SYNC_ID = ? AND LLM_SCORE IS NULL"
                        + " ORDER BY RANK_SCORE DESC, RSS_ITEM_ID DESC LIMIT ?",
                new String[]{String.valueOf(syncId), String.valueOf(limit)})) {
            List<Row> out = new ArrayList<>(c.getCount());
            while (c.moveToNext()) {
                out.add(read(c));
            }
            return out;
        }
    }

    /**
     * Marks every verdict stale after the interests note changed: {@code LLM_SCORE} goes back to
     * {@code NULL}, which is exactly "never judged, retry", so the next run re-judges the article
     * against the new note.
     *
     * <p>{@code STATUS} and {@code RANK_SCORE} are deliberately <b>left alone</b>. Clearing them
     * would empty the For You folder the instant the reader edits their note and refill it only
     * after the next sync — the edit would look like it broke the feature. This way the list keeps
     * its current contents, ranked as before, until better numbers arrive.</p>
     *
     * <p>{@code SCORED_AT} is left alone too, and that one is not cosmetic: it is the digest's range
     * key. Nulling it would make every already-exported article eligible for the next digest again.
     * </p>
     *
     * @return the number of rows invalidated
     */
    public int markScoresStale() {
        int before = (int) db.queryLong("SELECT COUNT(*) FROM AI_SCORE WHERE LLM_SCORE IS NOT NULL",
                null, 0L);
        db.exec("UPDATE AI_SCORE SET LLM_SCORE = NULL, EFF_SCORE = NULL"
                + " WHERE LLM_SCORE IS NOT NULL");
        return before;
    }

    public void delete(long rssItemId) {
        db.delete(AiSchema.T_SCORE, "RSS_ITEM_ID = ?", new String[]{String.valueOf(rssItemId)});
    }

    public void deleteAll() {
        db.delete(AiSchema.T_SCORE, null, null);
    }

    private static void putOrNull(ContentValues v, String column, Object value) {
        if (value == null) {
            v.putNull(column);
        } else if (value instanceof String) {
            v.put(column, (String) value);
        } else if (value instanceof Integer) {
            v.put(column, (Integer) value);
        } else if (value instanceof Long) {
            v.put(column, (Long) value);
        } else if (value instanceof Double) {
            v.put(column, (Double) value);
        } else {
            v.put(column, String.valueOf(value));
        }
    }

    private static Row read(Cursor c) {
        Row r = new Row();
        r.rssItemId = c.getLong(0);
        r.aiKey = c.getString(1);
        r.status = c.getString(2);
        r.discardReason = c.isNull(3) ? null : c.getString(3);
        r.simScore = c.isNull(4) ? null : c.getDouble(4);
        r.llmScore = c.isNull(5) ? null : c.getInt(5);
        r.effScore = c.isNull(6) ? null : c.getInt(6);
        r.rankScore = c.isNull(7) ? null : c.getDouble(7);
        r.themes = c.isNull(8) ? null : c.getString(8);
        r.flags = c.isNull(9) ? null : c.getString(9);
        r.why = c.isNull(10) ? null : c.getString(10);
        r.scoredAt = c.isNull(11) ? null : c.getLong(11);
        r.syncId = c.isNull(12) ? null : c.getLong(12);
        return r;
    }
}
