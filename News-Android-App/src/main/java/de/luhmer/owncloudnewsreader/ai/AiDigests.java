package de.luhmer.owncloudnewsreader.ai;

import android.content.Context;
import android.database.Cursor;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.AiDigestStore;
import de.luhmer.owncloudnewsreader.database.ai.AiScoreStore;

/**
 * The digest, end to end, minus the abstract: reads {@code AI_SCORE}, groups with
 * {@link AiDigestBuilder}, writes {@code AI_DIGEST} / {@code AI_DIGEST_ITEM}.
 *
 * <p><b>No inference anywhere in this class.</b> That is the point: the card renders immediately
 * with its item count and theme chips the first time the reader opens "For you" on a new day, and
 * the abstract — the only part that needs a model — arrives later or never. A digest that blocks on
 * a 2.6 GB model load is a digest nobody sees.</p>
 *
 * <p>Cadence is enforced by {@code AI_DIGEST.DAY_KEY UNIQUE}, not by a timer: two callers racing on
 * the first open of the day converge on one row.</p>
 */
public final class AiDigests {

    private static final String TAG = "AiDigests";

    /** How many digests to keep. Older ones are pruned when a new one is created. */
    public static final int KEEP_DAYS = 14;

    /** {@code AI_META}: the day-key whose card the reader dismissed. */
    public static final String KEY_DISMISSED_DAY = "digest_dismissed_day";

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    /** Fallback range when there has never been a digest: the last 24 hours of selections. */
    private static final long DEFAULT_RANGE_MS = DAY_MS;

    private AiDigests() {
        // no instances
    }

    /** {@code yyyy-MM-dd} in the device's zone — the same calendar the reader is living in. */
    public static String dayKey(long ms) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms));
    }

    /**
     * Creates today's digest if it does not exist yet and there is enough new material.
     *
     * @return the digest for today, or {@code null} when there is nothing worth one. {@code null} is
     *         the common case and is not an error: most days produce fewer than
     *         {@link AiDigestBuilder#MIN_ITEMS} new selections.
     */
    public static AiDigestStore.Digest ensureToday(AiDb db, long now) {
        if (db == null) {
            return null;
        }
        try {
            AiDigestStore store = new AiDigestStore(db);
            String today = dayKey(now);
            AiDigestStore.Digest existing = store.byDay(today);
            if (existing != null) {
                return existing;
            }

            AiDigestStore.Digest previous = store.latest();
            long from = previous == null ? now - DEFAULT_RANGE_MS : previous.rangeTo;
            List<AiDigestBuilder.Item> selected = selectedInRange(db, from, now);
            AiDigestBuilder.Result built = AiDigestBuilder.build(selected, from, now);
            if (built.itemCount < AiDigestBuilder.MIN_ITEMS) {
                return null;
            }

            AiDigestStore.Digest digest =
                    store.findOrCreate(today, from, now, built.itemCount, now);
            if (digest == null) {
                return null;
            }
            store.clearItems(digest.id);
            int pos = 0;
            for (AiDigestBuilder.Block b : built.blocks) {
                for (AiDigestBuilder.Item i : b.items) {
                    store.putItem(digest.id, i.aiKey, Long.valueOf(i.rssItemId), b.theme,
                            i.rankScore, pos++);
                }
            }
            store.setItemCount(digest.id, built.itemCount);
            store.pruneOlderThan(dayKey(now - (long) KEEP_DAYS * DAY_MS));
            return store.byDay(today);
        } catch (Throwable t) {
            // A digest is a convenience view. It never takes the folder down with it.
            Log.e(TAG, "could not build today's digest", t);
            return null;
        }
    }

    /** Convenience for UI callers holding a {@link Context} rather than a database. */
    public static AiDigestStore.Digest ensureToday(Context context, long now) {
        if (context == null) {
            return null;
        }
        try {
            AiDb db = new DatabaseConnectionOrm(context).aiDb();
            return ensureToday(db, now);
        } catch (Throwable t) {
            Log.e(TAG, "could not build today's digest", t);
            return null;
        }
    }

    /**
     * Everything still unread and selected in {@code (from, to]}, joined to the article for its
     * title and date.
     *
     * <p>{@code SCORED_AT} is the selection instant. Using {@code PUB_DATE} here instead would be the
     * single most likely way to get this wrong, and it would look right for weeks: on a healthy feed
     * the two mostly agree.</p>
     */
    public static List<AiDigestBuilder.Item> selectedInRange(AiDb db, long from, long to) {
        List<AiDigestBuilder.Item> out = new ArrayList<>();
        if (db == null) {
            return out;
        }
        String sql = "SELECT AI_SCORE.AI_KEY, AI_SCORE.RSS_ITEM_ID, AI_SCORE.THEMES,"
                + " AI_SCORE.RANK_SCORE, AI_SCORE.SCORED_AT, AI_SCORE.WHY,"
                + " RSS_ITEM.TITLE, RSS_ITEM.PUB_DATE, FEED.FEED_TITLE"
                + " FROM AI_SCORE"
                + " JOIN RSS_ITEM ON RSS_ITEM._id = AI_SCORE.RSS_ITEM_ID"
                + " LEFT JOIN FEED ON FEED._id = RSS_ITEM.FEED_ID"
                + " WHERE AI_SCORE.STATUS = ?"
                + "   AND RSS_ITEM.READ_TEMP != 1"
                + "   AND AI_SCORE.SCORED_AT > ? AND AI_SCORE.SCORED_AT <= ?"
                + " ORDER BY AI_SCORE.RANK_SCORE DESC, RSS_ITEM.PUB_DATE DESC, RSS_ITEM._id DESC";
        try (Cursor c = db.query(sql, new String[]{AiScoreStore.STATUS_SELECTED,
                String.valueOf(from), String.valueOf(to)})) {
            while (c.moveToNext()) {
                AiDigestBuilder.Item i = new AiDigestBuilder.Item();
                i.aiKey = c.getString(0);
                i.rssItemId = c.getLong(1);
                i.themes = AiDigestBuilder.splitThemes(c.isNull(2) ? null : c.getString(2));
                i.rankScore = c.isNull(3) ? null : Double.valueOf(c.getDouble(3));
                i.selectedAt = c.isNull(4) ? 0L : c.getLong(4);
                i.why = c.isNull(5) ? null : c.getString(5);
                i.title = c.isNull(6) ? null : c.getString(6);
                i.pubDate = c.isNull(7) ? null : Long.valueOf(c.getLong(7));
                i.source = c.isNull(8) ? null : c.getString(8);
                out.add(i);
            }
        } catch (Throwable t) {
            Log.e(TAG, "could not read the selection window", t);
        }
        return out;
    }

    /** One rendered digest row, joined back to the article. */
    public static final class Entry {
        public String aiKey;
        public long rssItemId;
        public String theme;
        public Double rankScore;
        public Integer llmScore;
        public String title;
        public String why;
        public String source;
        public boolean read;
        public int pos;
    }

    /** The persisted digest, in {@code POS} order — what {@code AiDigestActivity} renders. */
    public static List<Entry> entries(AiDb db, long digestId) {
        List<Entry> out = new ArrayList<>();
        if (db == null) {
            return out;
        }
        String sql = "SELECT AI_DIGEST_ITEM.AI_KEY, AI_DIGEST_ITEM.RSS_ITEM_ID,"
                + " AI_DIGEST_ITEM.THEME, AI_DIGEST_ITEM.RANK_SCORE, AI_DIGEST_ITEM.POS,"
                + " RSS_ITEM.TITLE, RSS_ITEM.READ_TEMP, FEED.FEED_TITLE,"
                + " AI_SCORE.WHY, AI_SCORE.LLM_SCORE"
                + " FROM AI_DIGEST_ITEM"
                + " JOIN RSS_ITEM ON RSS_ITEM._id = AI_DIGEST_ITEM.RSS_ITEM_ID"
                + " LEFT JOIN FEED ON FEED._id = RSS_ITEM.FEED_ID"
                + " LEFT JOIN AI_SCORE ON AI_SCORE.RSS_ITEM_ID = AI_DIGEST_ITEM.RSS_ITEM_ID"
                + " WHERE AI_DIGEST_ITEM.DIGEST_ID = ?"
                + " ORDER BY AI_DIGEST_ITEM.POS ASC";
        try (Cursor c = db.query(sql, new String[]{String.valueOf(digestId)})) {
            while (c.moveToNext()) {
                Entry e = new Entry();
                e.aiKey = c.getString(0);
                e.rssItemId = c.isNull(1) ? 0L : c.getLong(1);
                e.theme = c.isNull(2) ? null : c.getString(2);
                e.rankScore = c.isNull(3) ? null : Double.valueOf(c.getDouble(3));
                e.pos = c.getInt(4);
                e.title = c.isNull(5) ? null : c.getString(5);
                e.read = !c.isNull(6) && c.getInt(6) == 1;
                e.source = c.isNull(7) ? null : c.getString(7);
                e.why = c.isNull(8) ? null : c.getString(8);
                e.llmScore = c.isNull(9) ? null : Integer.valueOf(c.getInt(9));
                out.add(e);
            }
        } catch (Throwable t) {
            Log.e(TAG, "could not read digest " + digestId, t);
        }
        return out;
    }

    /** Theme, count and weight, heaviest first — the card's chips. */
    public static final class Chip {
        public final String theme;
        public final int count;

        Chip(String theme, int count) {
            this.theme = theme;
            this.count = count;
        }
    }

    public static List<Chip> chips(AiDb db, long digestId, int limit) {
        List<Chip> out = new ArrayList<>();
        if (db == null) {
            return out;
        }
        String sql = "SELECT THEME, COUNT(*), SUM(COALESCE(RANK_SCORE, 0))"
                + " FROM AI_DIGEST_ITEM WHERE DIGEST_ID = ? AND THEME IS NOT NULL"
                + " GROUP BY THEME ORDER BY SUM(COALESCE(RANK_SCORE, 0)) DESC LIMIT ?";
        try (Cursor c = db.query(sql,
                new String[]{String.valueOf(digestId), String.valueOf(limit)})) {
            while (c.moveToNext()) {
                out.add(new Chip(c.getString(0), c.getInt(1)));
            }
        } catch (Throwable t) {
            Log.e(TAG, "could not read digest chips", t);
        }
        return out;
    }

    /**
     * The {@code CURRENT_RSS_ITEM_VIEW} contract for opening an article <b>from the digest</b>.
     *
     * <p>{@code NewsDetailActivity} pages over whatever that table holds. Launching it from the
     * digest without rebuilding the table first opens the article at the same <i>index</i> in the
     * previous list — a different article, silently (PLAN R17). This is the single highest-risk
     * detail on the digest screen.</p>
     */
    public static String currentViewSql(long digestId) {
        return "SELECT RSS_ITEM._id FROM RSS_ITEM"
                + " JOIN AI_DIGEST_ITEM ON AI_DIGEST_ITEM.RSS_ITEM_ID = RSS_ITEM._id"
                + " WHERE AI_DIGEST_ITEM.DIGEST_ID = " + digestId
                + " ORDER BY AI_DIGEST_ITEM.POS ASC";
    }

    /**
     * Set whenever the digest screen has overwritten {@code CURRENT_RSS_ITEM_VIEW} with its own
     * article list.
     *
     * <p>That table is global and singular: the list fragment's lazy paging reads it too. Without
     * this flag, coming back from the digest and scrolling to the bottom of "For you" would page in
     * the <i>digest's</i> articles. {@code NewsReaderDetailFragment.onResume()} consumes the flag and
     * rebuilds. A static is the right shape here precisely because the table it guards is a static
     * too — one row set, one process.</p>
     */
    private static volatile boolean currentViewDirty;

    public static void markCurrentViewDirty() {
        currentViewDirty = true;
    }

    /** @return true once per {@link #markCurrentViewDirty()} */
    public static boolean consumeCurrentViewDirty() {
        boolean was = currentViewDirty;
        currentViewDirty = false;
        return was;
    }

    /**
     * The {@code AI_SCORE} theme filter used by the card's chips. Appended to the For You SQL by the
     * fragment, before its {@code ORDER BY} — a list rebuild, not an in-memory filter, so paging,
     * mark-as-read-while-scrolling and the swipe positions all stay consistent.
     */
    public static String themeFilterClause(String theme) {
        if (theme == null || theme.trim().isEmpty()) {
            return "";
        }
        String safe = theme.trim().toLowerCase(Locale.ROOT).replace("'", "");
        // Delimiter-anchored, not a bare LIKE '%theme%': THEMES is a comma-joined list with no
        // spaces (ScoreLineParser.join), so "regulation" would otherwise also select every article
        // tagged "deregulation" and the chip count would not match the list length.
        return " AND (',' || AI_SCORE.THEMES || ',') LIKE '%," + safe + ",%'";
    }

    /** {@code ACTION_SEND} body: the abstract, then one Markdown section per theme. */
    public static String shareMarkdown(AiDigestStore.Digest digest, List<Entry> entries,
                                       String untaggedLabel) {
        StringBuilder sb = new StringBuilder();
        if (digest != null) {
            sb.append("# ").append(digest.dayKey).append('\n').append('\n');
            if (digest.abstractText != null && !digest.abstractText.trim().isEmpty()) {
                sb.append(digest.abstractText.trim()).append('\n').append('\n');
            }
        }
        String currentTheme = " ";   // a value no theme can take
        for (Entry e : entries) {
            String theme = e.theme == null ? untaggedLabel : e.theme;
            if (!theme.equals(currentTheme)) {
                sb.append("## ").append(theme).append('\n').append('\n');
                currentTheme = theme;
            }
            sb.append("- **").append(e.title == null ? "" : e.title.trim()).append("**");
            if (e.source != null && !e.source.trim().isEmpty()) {
                sb.append(" (").append(e.source.trim()).append(')');
            }
            sb.append('\n');
            if (e.why != null && !e.why.trim().isEmpty()) {
                sb.append("  ").append(e.why.trim()).append('\n');
            }
        }
        return sb.toString();
    }

    /** Midnight of the day {@code ms} falls in, device zone. Used by the notification schedule. */
    public static long startOfDay(long ms) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ms);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /** True when the reader dismissed this exact day's card. Dismissal lasts one day, by design. */
    public static boolean isDismissed(AiDb db, String dayKey) {
        if (db == null || dayKey == null) {
            return false;
        }
        return dayKey.equals(db.getMeta(KEY_DISMISSED_DAY));
    }

    public static void dismiss(AiDb db, AiDigestStore.Digest digest, long now) {
        if (db == null || digest == null) {
            return;
        }
        try {
            db.putMeta(KEY_DISMISSED_DAY, digest.dayKey);
            new AiDigestStore(db).dismiss(digest.id, now);
        } catch (Throwable t) {
            Log.e(TAG, "could not dismiss the digest card", t);
        }
    }
}
