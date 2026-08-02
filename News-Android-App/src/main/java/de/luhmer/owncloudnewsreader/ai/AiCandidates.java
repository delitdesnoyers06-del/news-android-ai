package de.luhmer.owncloudnewsreader.ai;

import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;

import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.AiKeys;

/**
 * Stage 1 — the hard filter (veille {@code pipeline.py::_stage_candidates}).
 *
 * <p>Unread, and either undated or published inside the window. <b>A NULL publication date is
 * KEPT</b>, not dropped: plenty of feeds omit it, and treating "no date" as "too old" would make the
 * AI folder permanently empty for those users. That is a direct port, not a judgement call.</p>
 *
 * <p>Ordering is the recency key used everywhere downstream: {@code (pubDate, fetchedAt) DESC} with
 * <b>undated last</b>. SQLite sorts NULL first under {@code DESC}, so the {@code PUB_DATE IS NULL}
 * discriminator column is required — without it every undated article would jump to the front of
 * the cold-start selection.</p>
 */
public final class AiCandidates {

    /** veille {@code window_days}. Older-than-this unread articles are not triaged. */
    public static final int WINDOW_DAYS = 7;

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private AiCandidates() {
        // no instances
    }

    /** One triage candidate. Mutable on purpose: the pipeline fills {@code sim} in place. */
    public static final class Candidate {
        public long rssItemId;
        public String aiKey;
        public String title;
        public String body;
        public long feedId;
        /** epoch millis, or {@code null} when the feed gave no date */
        public Long pubDate;
        /** epoch millis of last local modification — the {@code fetched_at} half of the key */
        public Long fetchedAt;
        /** filled by the pipeline: {@code null} = never embedded, {@code 0.0} = neutral */
        public Double sim;
        /** filled by the scorer (Phase 6): {@code null} = never judged */
        public Integer llmScore;
    }

    /**
     * @param limit hard cap on rows returned; the caller's budget, not a correctness bound
     */
    public static List<Candidate> select(AiDb db, long nowMs, int limit) {
        long cutoff = nowMs - (long) WINDOW_DAYS * DAY_MS;
        String sql = "SELECT RSS_ITEM._id, RSS_ITEM.FINGERPRINT, RSS_ITEM.TITLE, RSS_ITEM.BODY,"
                + " RSS_ITEM.FEED_ID, RSS_ITEM.PUB_DATE, RSS_ITEM.LAST_MODIFIED"
                + " FROM RSS_ITEM"
                + " WHERE RSS_ITEM.READ_TEMP != 1"
                + "   AND (RSS_ITEM.PUB_DATE IS NULL OR RSS_ITEM.PUB_DATE >= ?)"
                // NULL sorts first under DESC in SQLite; the discriminator pushes undated last.
                + " ORDER BY (RSS_ITEM.PUB_DATE IS NULL) ASC, RSS_ITEM.PUB_DATE DESC,"
                + " RSS_ITEM.LAST_MODIFIED DESC, RSS_ITEM._id DESC"
                + " LIMIT ?";
        List<Candidate> out = new ArrayList<>();
        try (Cursor c = db.query(sql, new String[]{String.valueOf(cutoff), String.valueOf(limit)})) {
            while (c.moveToNext()) {
                Candidate cand = new Candidate();
                cand.rssItemId = c.getLong(0);
                cand.aiKey = AiKeys.of(c.isNull(1) ? null : c.getString(1), cand.rssItemId);
                cand.title = c.isNull(2) ? null : c.getString(2);
                cand.body = c.isNull(3) ? null : c.getString(3);
                cand.feedId = c.getLong(4);
                cand.pubDate = c.isNull(5) ? null : c.getLong(5);
                cand.fetchedAt = c.isNull(6) ? null : c.getLong(6);
                out.add(cand);
            }
        }
        return out;
    }
}
