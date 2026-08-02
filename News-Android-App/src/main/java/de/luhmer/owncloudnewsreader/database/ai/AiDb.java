package de.luhmer.owncloudnewsreader.database.ai;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

/**
 * The single choke point for AI persistence.
 *
 * <p>This is the <b>only</b> class in the app that holds an {@link SQLiteDatabase} for AI purposes;
 * no AI statement is executed anywhere except through one of these methods. The stores in this
 * package build their table-specific statements and hand them here — they never see a
 * {@code SQLiteDatabase}, never open a cursor they do not close, and are therefore trivially
 * testable against an in-memory database.</p>
 *
 * <p>It also owns the two pieces of SQL that span tables: the {@code AI_META} key/value accessors
 * and {@link #garbageCollect()}.</p>
 */
public final class AiDb {

    private static final String TAG = "AiDb";

    private final SQLiteDatabase db;

    private AiDb(SQLiteDatabase db) {
        this.db = db;
    }

    public static AiDb of(SQLiteDatabase db) {
        return new AiDb(db);
    }

    // ------------------------------------------------------------------ plumbing

    /** Package-private escape hatch for the stores' {@code insert}/{@code update} conveniences. */
    SQLiteDatabase raw() {
        return db;
    }

    public void exec(String sql) {
        db.execSQL(sql);
    }

    public void exec(String sql, Object[] bindArgs) {
        db.execSQL(sql, bindArgs);
    }

    /** The caller owns the cursor and must close it. */
    public Cursor query(String sql, String[] args) {
        return db.rawQuery(sql, args);
    }

    public long queryLong(String sql, String[] args, long fallback) {
        try (Cursor c = db.rawQuery(sql, args)) {
            if (c.moveToFirst() && !c.isNull(0)) {
                return c.getLong(0);
            }
        }
        return fallback;
    }

    public String queryString(String sql, String[] args) {
        try (Cursor c = db.rawQuery(sql, args)) {
            if (c.moveToFirst() && !c.isNull(0)) {
                return c.getString(0);
            }
        }
        return null;
    }

    public byte[] queryBlob(String sql, String[] args) {
        try (Cursor c = db.rawQuery(sql, args)) {
            if (c.moveToFirst() && !c.isNull(0)) {
                return c.getBlob(0);
            }
        }
        return null;
    }

    public long insertOrReplace(String table, ContentValues values) {
        return db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public long insert(String table, ContentValues values) {
        return db.insertOrThrow(table, null, values);
    }

    public int update(String table, ContentValues values, String where, String[] args) {
        return db.update(table, values, where, args);
    }

    public int delete(String table, String where, String[] args) {
        return db.delete(table, where, args);
    }

    /** Runs {@code body} in a transaction. Any throw rolls back and is rethrown. */
    public void inTransaction(Runnable body) {
        db.beginTransaction();
        try {
            body.run();
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ------------------------------------------------------------------ AI_META

    public String getMeta(String key) {
        return queryString("SELECT V FROM AI_META WHERE K = ?", new String[]{key});
    }

    public void putMeta(String key, String value) {
        exec("INSERT OR REPLACE INTO AI_META (K, V, B) VALUES (?, ?, (SELECT B FROM AI_META WHERE K = ?))",
                new Object[]{key, value, key});
    }

    public long getMetaLong(String key, long fallback) {
        String v = getMeta(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            Log.w(TAG, "AI_META['" + key + "'] is not a number: " + v);
            return fallback;
        }
    }

    public void putMetaLong(String key, long value) {
        putMeta(key, String.valueOf(value));
    }

    /** @return the new value. */
    public long incrementMeta(String key, long delta) {
        long next = getMetaLong(key, 0L) + delta;
        putMetaLong(key, next);
        return next;
    }

    public byte[] getMetaBlob(String key) {
        return queryBlob("SELECT B FROM AI_META WHERE K = ?", new String[]{key});
    }

    public void putMetaBlob(String key, byte[] value) {
        exec("INSERT OR REPLACE INTO AI_META (K, V, B) VALUES (?, (SELECT V FROM AI_META WHERE K = ?), ?)",
                new Object[]{key, key, value});
    }

    public void deleteMeta(String key) {
        delete(AiSchema.T_META, "K = ?", new String[]{key});
    }

    // ------------------------------------------------------------------ GC

    /**
     * Prunes AI rows whose article is gone. Called from
     * {@code DatabaseConnectionOrm.aiGarbageCollect()}, itself called from
     * {@code RssItemObservable.sync()} immediately after {@code clearDatabaseOverSize()}.
     *
     * <p>{@code AI_DECISION}, {@code AI_TASTE}, {@code AI_CENTROID} and {@code AI_RUBRIC} are
     * <b>never</b> GC-ed. They are not a cache, they are the feature.</p>
     */
    public void garbageCollect() {
        // 1. AI_SCORE is transient: it dies with its article, unconditionally.
        exec("DELETE FROM AI_SCORE WHERE RSS_ITEM_ID NOT IN (SELECT _id FROM RSS_ITEM)");
        // 2. The embedding of an article the user ruled on IS the taste model. Keep it forever.
        //    Everything else follows the article cache.
        exec("DELETE FROM AI_EMBEDDING"
                + " WHERE AI_KEY NOT IN (SELECT AI_KEY FROM AI_TASTE)"
                + "   AND AI_KEY NOT IN (SELECT AI_KEY FROM AI_SCORE)");
        // 3. Digest items whose digest is gone (the digest itself is pruned by AiDigestStore).
        exec("DELETE FROM AI_DIGEST_ITEM WHERE DIGEST_ID NOT IN (SELECT _id FROM AI_DIGEST)");
    }
}
