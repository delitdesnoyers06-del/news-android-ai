package de.luhmer.owncloudnewsreader.database.ai;

import android.content.ContentValues;
import android.database.Cursor;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * CRUD on {@code AI_EMBEDDING}, plus the wire format for a vector.
 *
 * <p><b>The blob layout is a contract, not an implementation detail.</b> {@code VEC} is packed
 * float32 <b>little-endian</b>, byte-identical to veille's {@code embed.py} {@code pack()}
 * ({@code struct.pack('<%df')}). A parity test dumps one vector from here and one from veille for
 * the same text and diffs the bytes; that only works if the endianness never drifts.
 * {@link java.nio.ByteBuffer} defaults to BIG_ENDIAN, so the explicit
 * {@link ByteOrder#LITTLE_ENDIAN} below is the whole point of these two methods existing.</p>
 *
 * <p>{@code MODEL} and {@code TASK} are stored on every row. Changing either invalidates every
 * vector and every centroid — see {@link #assertCompatible(String, String)}. That migration is
 * explicit and destructive by design: silent drift produces a taste model that never converges,
 * with no error and no symptom.</p>
 */
public class AiEmbeddingStore {

    private static final String TAG = "AiEmbeddingStore";

    private final AiDb db;

    public AiEmbeddingStore(AiDb db) {
        this.db = db;
    }

    /** One row of {@code AI_EMBEDDING}. */
    public static class Row {
        public String aiKey;
        public String model;
        public String task;
        public int dim;
        public float[] vec;
        public long createdAt;
    }

    // ------------------------------------------------------------------ pack / unpack

    /**
     * float32, little-endian, no header. {@code null} in, {@code null} out.
     *
     * @return {@code vec.length * 4} bytes
     */
    public static byte[] pack(float[] vec) {
        if (vec == null) {
            return null;
        }
        ByteBuffer buf = ByteBuffer.allocate(vec.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : vec) {
            buf.putFloat(f);
        }
        return buf.array();
    }

    /**
     * Inverse of {@link #pack(float[])}. A blob whose length is not a multiple of 4 is truncated
     * to the last whole float rather than throwing — a corrupt row must not take a sync down.
     */
    public static float[] unpack(byte[] blob) {
        if (blob == null) {
            return null;
        }
        int n = blob.length / 4;
        ByteBuffer buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = buf.getFloat();
        }
        return out;
    }

    // ------------------------------------------------------------------ CRUD

    public void put(String aiKey, String model, String task, float[] vec, long createdAt) {
        ContentValues v = new ContentValues();
        v.put("AI_KEY", aiKey);
        v.put("MODEL", model);
        v.put("TASK", task);
        v.put("DIM", vec.length);
        v.put("VEC", pack(vec));
        v.put("CREATED_AT", createdAt);
        db.insertOrReplace(AiSchema.T_EMBEDDING, v);
    }

    public void put(String aiKey, String model, String task, float[] vec) {
        put(aiKey, model, task, vec, System.currentTimeMillis());
    }

    /** @return the vector, or {@code null} when this key was never embedded. */
    public float[] get(String aiKey) {
        return unpack(db.queryBlob("SELECT VEC FROM AI_EMBEDDING WHERE AI_KEY = ?",
                new String[]{aiKey}));
    }

    public Row getRow(String aiKey) {
        try (Cursor c = db.query("SELECT AI_KEY, MODEL, TASK, DIM, VEC, CREATED_AT"
                + " FROM AI_EMBEDDING WHERE AI_KEY = ?", new String[]{aiKey})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    public boolean has(String aiKey) {
        return db.queryLong("SELECT COUNT(*) FROM AI_EMBEDDING WHERE AI_KEY = ?",
                new String[]{aiKey}, 0L) > 0;
    }

    public int count() {
        return (int) db.queryLong("SELECT COUNT(*) FROM AI_EMBEDDING", null, 0L);
    }

    /** Every embedding of a decided article, i.e. the taste corpus. */
    public List<Row> decided() {
        try (Cursor c = db.query("SELECT E.AI_KEY, E.MODEL, E.TASK, E.DIM, E.VEC, E.CREATED_AT"
                + " FROM AI_EMBEDDING E JOIN AI_TASTE T ON T.AI_KEY = E.AI_KEY"
                + " ORDER BY E.AI_KEY", null)) {
            List<Row> out = new ArrayList<>(c.getCount());
            while (c.moveToNext()) {
                out.add(read(c));
            }
            return out;
        }
    }

    public void delete(String aiKey) {
        db.delete(AiSchema.T_EMBEDDING, "AI_KEY = ?", new String[]{aiKey});
    }

    public void deleteAll() {
        db.delete(AiSchema.T_EMBEDDING, null, null);
    }

    // ------------------------------------------------------------------ migration

    /**
     * Explicit model/task migration. If any stored vector — or either centroid — was produced by a
     * different {@code (MODEL, TASK)} pair than the one we are about to use, the whole vector space
     * is incomparable and must go. {@code AI_TASTE.IN_CENTROID} is reset to 0 so the next
     * {@code AiCentroidStore.backfill()} re-folds the decided set once it has been re-embedded.
     *
     * <p>{@code AI_DECISION} and {@code AI_TASTE} are untouched: the user's judgements survive a
     * model change, only the arithmetic does not.</p>
     *
     * @return true when a wipe happened
     */
    /**
     * True when any stored vector or centroid was produced by a different {@code (MODEL, TASK)}
     * pair. Read-only counterpart of {@link #assertCompatible(String, String)}, so a caller can
     * decide whether it is worth loading an engine at all.
     */
    public boolean hasForeign(String model, String task) {
        return db.queryLong("SELECT COUNT(*) FROM AI_EMBEDDING WHERE MODEL <> ? OR TASK <> ?",
                new String[]{model, task}, 0L) > 0
                || db.queryLong("SELECT COUNT(*) FROM AI_CENTROID WHERE MODEL <> ? OR TASK <> ?",
                new String[]{model, task}, 0L) > 0;
    }

    public boolean assertCompatible(String model, String task) {
        long stale = db.queryLong(
                "SELECT COUNT(*) FROM AI_EMBEDDING WHERE MODEL <> ? OR TASK <> ?",
                new String[]{model, task}, 0L);
        long staleCentroids = db.queryLong(
                "SELECT COUNT(*) FROM AI_CENTROID WHERE MODEL <> ? OR TASK <> ?",
                new String[]{model, task}, 0L);
        if (stale == 0 && staleCentroids == 0) {
            return false;
        }
        Log.w(TAG, "embedding model/task changed to " + model + "/" + task
                + " - dropping " + stale + " vectors and " + staleCentroids + " centroids");
        db.inTransaction(() -> {
            db.delete(AiSchema.T_EMBEDDING, null, null);
            db.delete(AiSchema.T_CENTROID, null, null);
            ContentValues v = new ContentValues();
            v.put("IN_CENTROID", 0);
            db.update(AiSchema.T_TASTE, v, null, null);
        });
        return true;
    }

    private static Row read(Cursor c) {
        Row r = new Row();
        r.aiKey = c.getString(0);
        r.model = c.getString(1);
        r.task = c.getString(2);
        r.dim = c.getInt(3);
        r.vec = unpack(c.getBlob(4));
        r.createdAt = c.getLong(5);
        return r;
    }
}
