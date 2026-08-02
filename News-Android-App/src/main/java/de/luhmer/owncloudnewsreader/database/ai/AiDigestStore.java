package de.luhmer.owncloudnewsreader.database.ai;

import android.content.ContentValues;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;

/**
 * CRUD on {@code AI_DIGEST} / {@code AI_DIGEST_ITEM}.
 *
 * <p>{@code DAY_KEY} is {@code UNIQUE}: that column, not a timer and not a preference, is what
 * enforces one digest per day. {@link #findOrCreate} therefore never produces a second row for the
 * same day even if two workers race.</p>
 */
public class AiDigestStore {

    public static final String ABSTRACT_PENDING = "pending";
    public static final String ABSTRACT_OK = "ok";
    public static final String ABSTRACT_SKIPPED = "skipped";
    public static final String ABSTRACT_FAILED = "failed";

    private static final String DIGEST_COLUMNS =
            "_id, DAY_KEY, RANGE_FROM, RANGE_TO, ITEM_COUNT, ABSTRACT, ABSTRACT_STATE,"
                    + " DISMISSED_AT, CREATED_AT";

    private final AiDb db;

    public AiDigestStore(AiDb db) {
        this.db = db;
    }

    /** One row of {@code AI_DIGEST}. */
    public static class Digest {
        public long id;
        public String dayKey;
        public long rangeFrom;
        public long rangeTo;
        public int itemCount;
        public String abstractText;
        public String abstractState;
        public Long dismissedAt;
        public long createdAt;
    }

    /** One row of {@code AI_DIGEST_ITEM}. */
    public static class Item {
        public long digestId;
        public String aiKey;
        public Long rssItemId;
        public String theme;
        public Double rankScore;
        public int pos;
    }

    /**
     * Returns the digest for {@code dayKey}, creating it if absent. Concurrent callers converge on
     * one row because {@code DAY_KEY} is {@code UNIQUE}.
     */
    public Digest findOrCreate(String dayKey, long rangeFrom, long rangeTo, int itemCount, long now) {
        db.exec("INSERT OR IGNORE INTO AI_DIGEST"
                        + " (DAY_KEY, RANGE_FROM, RANGE_TO, ITEM_COUNT, ABSTRACT_STATE, CREATED_AT)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                new Object[]{dayKey, rangeFrom, rangeTo, itemCount, ABSTRACT_PENDING, now});
        return byDay(dayKey);
    }

    public Digest byDay(String dayKey) {
        try (Cursor c = db.query("SELECT " + DIGEST_COLUMNS + " FROM AI_DIGEST WHERE DAY_KEY = ?",
                new String[]{dayKey})) {
            return c.moveToFirst() ? readDigest(c) : null;
        }
    }

    public Digest byId(long id) {
        try (Cursor c = db.query("SELECT " + DIGEST_COLUMNS + " FROM AI_DIGEST WHERE _id = ?",
                new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? readDigest(c) : null;
        }
    }

    public Digest latest() {
        try (Cursor c = db.query("SELECT " + DIGEST_COLUMNS
                + " FROM AI_DIGEST ORDER BY DAY_KEY DESC LIMIT 1", null)) {
            return c.moveToFirst() ? readDigest(c) : null;
        }
    }

    public void setAbstract(long digestId, String text, String state) {
        ContentValues v = new ContentValues();
        if (text == null) {
            v.putNull("ABSTRACT");
        } else {
            v.put("ABSTRACT", text);
        }
        v.put("ABSTRACT_STATE", state);
        db.update(AiSchema.T_DIGEST, v, "_id = ?", new String[]{String.valueOf(digestId)});
    }

    public void setItemCount(long digestId, int itemCount) {
        ContentValues v = new ContentValues();
        v.put("ITEM_COUNT", itemCount);
        db.update(AiSchema.T_DIGEST, v, "_id = ?", new String[]{String.valueOf(digestId)});
    }

    public void dismiss(long digestId, long now) {
        ContentValues v = new ContentValues();
        v.put("DISMISSED_AT", now);
        db.update(AiSchema.T_DIGEST, v, "_id = ?", new String[]{String.valueOf(digestId)});
    }

    // ------------------------------------------------------------------ items

    public void putItem(long digestId, String aiKey, Long rssItemId, String theme,
                        Double rankScore, int pos) {
        ContentValues v = new ContentValues();
        v.put("DIGEST_ID", digestId);
        v.put("AI_KEY", aiKey);
        if (rssItemId == null) {
            v.putNull("RSS_ITEM_ID");
        } else {
            v.put("RSS_ITEM_ID", rssItemId);
        }
        if (theme == null) {
            v.putNull("THEME");
        } else {
            v.put("THEME", theme);
        }
        if (rankScore == null) {
            v.putNull("RANK_SCORE");
        } else {
            v.put("RANK_SCORE", rankScore);
        }
        v.put("POS", pos);
        db.insertOrReplace(AiSchema.T_DIGEST_ITEM, v);
    }

    public List<Item> items(long digestId) {
        try (Cursor c = db.query("SELECT DIGEST_ID, AI_KEY, RSS_ITEM_ID, THEME, RANK_SCORE, POS"
                        + " FROM AI_DIGEST_ITEM WHERE DIGEST_ID = ? ORDER BY POS",
                new String[]{String.valueOf(digestId)})) {
            List<Item> out = new ArrayList<>(c.getCount());
            while (c.moveToNext()) {
                Item i = new Item();
                i.digestId = c.getLong(0);
                i.aiKey = c.getString(1);
                i.rssItemId = c.isNull(2) ? null : c.getLong(2);
                i.theme = c.isNull(3) ? null : c.getString(3);
                i.rankScore = c.isNull(4) ? null : c.getDouble(4);
                i.pos = c.getInt(5);
                out.add(i);
            }
            return out;
        }
    }

    public void clearItems(long digestId) {
        db.delete(AiSchema.T_DIGEST_ITEM, "DIGEST_ID = ?", new String[]{String.valueOf(digestId)});
    }

    /** Drops digests older than {@code keepDays} day-keys, and their items. */
    public void pruneOlderThan(String oldestDayKeyToKeep) {
        db.exec("DELETE FROM AI_DIGEST_ITEM WHERE DIGEST_ID IN"
                        + " (SELECT _id FROM AI_DIGEST WHERE DAY_KEY < ?)",
                new Object[]{oldestDayKeyToKeep});
        db.exec("DELETE FROM AI_DIGEST WHERE DAY_KEY < ?", new Object[]{oldestDayKeyToKeep});
    }

    public int count() {
        return (int) db.queryLong("SELECT COUNT(*) FROM AI_DIGEST", null, 0L);
    }

    private static Digest readDigest(Cursor c) {
        Digest d = new Digest();
        d.id = c.getLong(0);
        d.dayKey = c.getString(1);
        d.rangeFrom = c.getLong(2);
        d.rangeTo = c.getLong(3);
        d.itemCount = c.getInt(4);
        d.abstractText = c.isNull(5) ? null : c.getString(5);
        d.abstractState = c.getString(6);
        d.dismissedAt = c.isNull(7) ? null : c.getLong(7);
        d.createdAt = c.getLong(8);
        return d;
    }
}
