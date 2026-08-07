package de.luhmer.owncloudnewsreader.database.ai;

import android.content.ContentValues;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;

/**
 * CRUD on {@code AI_FULLTEXT} — the extracted full body of articles whose RSS feed only shipped a
 * teaser. One row per {@code RSS_ITEM_ID}; the row dies with its article
 * ({@link AiDb#garbageCollect()}).
 *
 * <p>{@code STATE} is a small state machine:</p>
 * <ul>
 *   <li>{@code pending} — enqueued, not yet fetched.</li>
 *   <li>{@code ok} — {@code CONTENT_HTML} holds the extracted article. This is the only state the
 *       reader renders.</li>
 *   <li>{@code skipped} — deliberately not extracted (no link, already full, extraction empty).</li>
 *   <li>{@code failed} — a fetch/parse error; {@code ERROR} says why. Retried on a later sync.</li>
 * </ul>
 */
public class FullTextStore {

    public static final String STATE_PENDING = "pending";
    public static final String STATE_OK = "ok";
    public static final String STATE_SKIPPED = "skipped";
    public static final String STATE_FAILED = "failed";

    private final AiDb db;

    public FullTextStore(AiDb db) {
        this.db = db;
    }

    /** Marks an article as awaiting extraction, remembering the URL we intend to fetch. */
    public void upsertPending(long rssItemId, String url) {
        // No UPSERT: minSdk 24 ships SQLite 3.9, `ON CONFLICT ... DO UPDATE` needs 3.24.
        db.exec("INSERT OR IGNORE INTO AI_FULLTEXT (RSS_ITEM_ID, URL, STATE) VALUES (?, ?, ?)",
                new Object[]{rssItemId, url, STATE_PENDING});
    }

    /** Stores a successful extraction. This is the only path that sets {@code STATE = 'ok'}. */
    public void saveOk(long rssItemId, String url, String contentHtml, String excerpt, long fetchedAt) {
        ContentValues v = new ContentValues();
        v.put("RSS_ITEM_ID", rssItemId);
        v.put("URL", url);
        v.put("STATE", STATE_OK);
        v.put("CONTENT_HTML", contentHtml);
        if (excerpt == null) {
            v.putNull("EXCERPT");
        } else {
            v.put("EXCERPT", excerpt);
        }
        v.putNull("ERROR");
        v.put("FETCHED_AT", fetchedAt);
        db.insertOrReplace(AiSchema.T_FULLTEXT, v);
    }

    /** Records a terminal-for-now outcome ({@code skipped} or {@code failed}). */
    public void mark(long rssItemId, String url, String state, String error, long fetchedAt) {
        ContentValues v = new ContentValues();
        v.put("RSS_ITEM_ID", rssItemId);
        v.put("URL", url);
        v.put("STATE", state);
        v.putNull("CONTENT_HTML");
        v.putNull("EXCERPT");
        if (error == null) {
            v.putNull("ERROR");
        } else {
            v.put("ERROR", error);
        }
        v.put("FETCHED_AT", fetchedAt);
        db.insertOrReplace(AiSchema.T_FULLTEXT, v);
    }

    /** The extracted article HTML, or {@code null} when there is no {@code ok} row. */
    public String contentHtml(long rssItemId) {
        return db.queryString(
                "SELECT CONTENT_HTML FROM AI_FULLTEXT WHERE RSS_ITEM_ID = ? AND STATE = ?",
                new String[]{String.valueOf(rssItemId), STATE_OK});
    }

    /** True when an extracted body is available to render. */
    public boolean hasOk(long rssItemId) {
        return contentHtml(rssItemId) != null;
    }

    /** The recorded state for an article, or {@code null} when it was never queued. */
    public String stateOf(long rssItemId) {
        return db.queryString("SELECT STATE FROM AI_FULLTEXT WHERE RSS_ITEM_ID = ?",
                new String[]{String.valueOf(rssItemId)});
    }

    /** The epoch-ms of the last attempt, or {@code 0} when there is no row. */
    public long fetchedAt(long rssItemId) {
        return db.queryLong("SELECT FETCHED_AT FROM AI_FULLTEXT WHERE RSS_ITEM_ID = ?",
                new String[]{String.valueOf(rssItemId)}, 0L);
    }

    public int countByState(String state) {
        return (int) db.queryLong("SELECT COUNT(*) FROM AI_FULLTEXT WHERE STATE = ?",
                new String[]{state}, 0L);
    }

    /** Article ids still awaiting a first extraction attempt, oldest queued first. */
    public List<Long> pending(int limit) {
        try (Cursor c = db.query(
                "SELECT RSS_ITEM_ID FROM AI_FULLTEXT WHERE STATE = ? ORDER BY RSS_ITEM_ID LIMIT ?",
                new String[]{STATE_PENDING, String.valueOf(limit)})) {
            List<Long> out = new ArrayList<>(c.getCount());
            while (c.moveToNext()) {
                out.add(c.getLong(0));
            }
            return out;
        }
    }
}
