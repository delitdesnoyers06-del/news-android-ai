package de.luhmer.owncloudnewsreader.database.ai;

import android.content.ContentValues;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.luhmer.owncloudnewsreader.ai.AiVec;

/**
 * {@code AI_CENTROID} — the running, <b>unnormalised</b> sum and member count per class, plus the
 * arithmetic that keeps it correct.
 *
 * <p>Two classes only: {@code kept} and {@code rejected}. A class with no row is a <b>missing</b>
 * centroid and contributes {@code 0} to the similarity — it is not a zero vector, and the
 * distinction is what makes the cold-start path correct. {@code N} reaching zero therefore
 * <b>deletes</b> the row rather than storing an all-zero sum.</p>
 *
 * <p><b>Why a running sum and not a stored mean.</b> veille recomputes the full mean per
 * {@code (veille_id, decision_count)}. On a phone the incoming signal is one decision at a time and
 * {@code mean = sum / n}, so a decision costs one vector add instead of a full table scan. The price
 * is that incremental and rebuilt state must agree; {@code AiCentroidTest} asserts exactly that as a
 * property over random decision sequences, and {@link #rebuild()} is the escape hatch when
 * {@link #consistent()} says they have drifted.</p>
 *
 * <p>Float32 accumulation drift over ~10^3 additions is ~1e-4 relative, which is irrelevant to a
 * cosine — but the consistency check is free, so we do it.</p>
 */
public class AiCentroidStore {

    public static final String CLASS_KEPT = "kept";
    public static final String CLASS_REJECTED = "rejected";

    /** {@code AI_META} key holding the cache key {@code (decisionCount, model, task)}. */
    public static final String KEY_UNIT_CACHE = "centroid_unit_cache_key";

    private final AiDb db;
    private final AiEmbeddingStore embeddings;
    private final AiDecisionStore decisions;

    public AiCentroidStore(AiDb db) {
        this.db = db;
        this.embeddings = new AiEmbeddingStore(db);
        this.decisions = new AiDecisionStore(db);
    }

    /** True for the two words that name a centroid. {@code queued} / null are "no class". */
    public static boolean isClass(String state) {
        return CLASS_KEPT.equals(state) || CLASS_REJECTED.equals(state);
    }

    /** One row of {@code AI_CENTROID}. {@code unit} is null when it must be recomputed. */
    public static class Row {
        public String cls;
        public String model;
        public String task;
        public int dim;
        public int n;
        public float[] sum;
        public float[] unit;
    }

    public Row get(String cls) {
        try (Cursor c = db.query("SELECT CLASS, MODEL, TASK, DIM, N, SUM, UNIT"
                + " FROM AI_CENTROID WHERE CLASS = ?", new String[]{cls})) {
            if (!c.moveToFirst()) {
                return null;
            }
            Row r = new Row();
            r.cls = c.getString(0);
            r.model = c.getString(1);
            r.task = c.getString(2);
            r.dim = c.getInt(3);
            r.n = c.getInt(4);
            r.sum = AiEmbeddingStore.unpack(c.getBlob(5));
            r.unit = c.isNull(6) ? null : AiEmbeddingStore.unpack(c.getBlob(6));
            return r;
        }
    }

    /** Writes the accumulator. {@code UNIT} is cleared — the caller recomputes it lazily. */
    public void put(String cls, String model, String task, float[] sum, int n) {
        ContentValues v = new ContentValues();
        v.put("CLASS", cls);
        v.put("MODEL", model);
        v.put("TASK", task);
        v.put("DIM", sum.length);
        v.put("N", n);
        v.put("SUM", AiEmbeddingStore.pack(sum));
        v.putNull("UNIT");
        db.insertOrReplace(AiSchema.T_CENTROID, v);
    }

    /** Caches the unit-normalised mean. {@code null} clears the cache. */
    public void putUnit(String cls, float[] unit) {
        ContentValues v = new ContentValues();
        if (unit == null) {
            v.putNull("UNIT");
        } else {
            v.put("UNIT", AiEmbeddingStore.pack(unit));
        }
        db.update(AiSchema.T_CENTROID, v, "CLASS = ?", new String[]{cls});
    }

    /** Drops every cached unit vector. Called on any decision, model change or note edit. */
    public void invalidateUnits() {
        ContentValues v = new ContentValues();
        v.putNull("UNIT");
        db.update(AiSchema.T_CENTROID, v, null, null);
    }

    public int memberCount(String cls) {
        return (int) db.queryLong("SELECT N FROM AI_CENTROID WHERE CLASS = ?",
                new String[]{cls}, 0L);
    }

    public String unitCacheKey() {
        return db.getMeta(KEY_UNIT_CACHE);
    }

    public void setUnitCacheKey(String key) {
        db.putMeta(KEY_UNIT_CACHE, key);
    }

    public void delete(String cls) {
        db.delete(AiSchema.T_CENTROID, "CLASS = ?", new String[]{cls});
    }

    /** Wipes both centroids. The decisions that produced them are untouched. */
    public void deleteAll() {
        db.delete(AiSchema.T_CENTROID, null, null);
    }

    // ------------------------------------------------------------------ the arithmetic

    /**
     * Folds one decision into the centroids, incrementally.
     *
     * <p>The article may never have been embedded. In that case the decision still <b>counts</b>
     * (it moves {@code decision_count}, which is what the warm-up gate reads) but contributes no
     * vector, and {@code AI_TASTE.IN_CENTROID} stays 0 so that {@link #backfill()} folds it in the
     * moment the vector exists. That is the single most common transient state on a phone: the user
     * swipes faster than the embedder runs.</p>
     *
     * <p>{@code decision_count} is incremented by {@code AiDecisionStore.decide()}, inside the same
     * transaction that appends the {@code AI_DECISION} row — deliberately not here, so a centroid
     * failure can never desynchronise the counter from the history.</p>
     *
     * @param from the state the article left ({@code kept}/{@code rejected}/anything else = none)
     * @param to   the state it entered (same convention)
     */
    public void onDecision(String aiKey, String from, String to) {
        AiEmbeddingStore.Row row = embeddings.getRow(aiKey);
        if (row != null && row.vec != null) {
            if (isClass(from)) {
                add(from, row, -1);
            }
            if (isClass(to)) {
                add(to, row, +1);
            }
            decisions.markInCentroid(aiKey, isClass(to));
        }
        invalidateUnits();
    }

    /**
     * The cached unit mean of a class, recomputed and re-cached on demand.
     *
     * @return {@code null} for a <b>missing</b> centroid ({@code N == 0}, no row, or a zero-norm
     *         sum). Null contributes 0 to the similarity; it is never treated as a zero vector.
     */
    public float[] unit(String cls) {
        Row r = get(cls);
        if (r == null || r.n <= 0 || r.sum == null) {
            return null;
        }
        if (r.unit != null) {
            return r.unit;
        }
        float[] u = AiVec.unit(AiVec.scale(r.sum, r.n));
        if (u != null) {
            putUnit(cls, u);
        }
        return u;
    }

    /**
     * veille's {@code (veille_id, decision_count)} cache key becomes
     * {@code (decisionCount, embeddingModel, embeddingTask)}: there is one veille, but there are
     * several models. Any mismatch nulls every cached {@code UNIT}.
     *
     * @return true when the key changed and the cache was dropped
     */
    public boolean refreshCacheKey(String model, String task, int decisionCount) {
        String key = decisionCount + "|" + model + "|" + task;
        if (key.equals(unitCacheKey())) {
            return false;
        }
        invalidateUnits();
        setUnitCacheKey(key);
        return true;
    }

    /**
     * Folds every decided article that has a vector but is not yet counted.
     *
     * <p>Runs at the top of each worker pass. An article that is gone from {@code RSS_ITEM} forever
     * and was never embedded simply stays {@code IN_CENTROID = 0}: a permanent, harmless no-op.</p>
     *
     * @return how many rows were folded in
     */
    public int backfill() {
        List<String> pending = decisions.pendingCentroidKeys();
        int folded = 0;
        for (String aiKey : pending) {
            String state = decisions.tasteState(aiKey);
            if (!isClass(state)) {
                continue;
            }
            AiEmbeddingStore.Row row = embeddings.getRow(aiKey);
            if (row == null || row.vec == null) {
                continue;
            }
            add(state, row, +1);
            decisions.markInCentroid(aiKey, true);
            folded++;
        }
        if (folded > 0) {
            invalidateUnits();
        }
        return folded;
    }

    /**
     * Exact recomputation of both classes from {@code AI_TASTE ⋈ AI_EMBEDDING}.
     *
     * <p>Run when {@link #consistent()} is false, when the embedding model or task changed, or when
     * the user taps "reset taste". Rebuilding also re-derives {@code IN_CENTROID}, so it is the only
     * operation that can repair a half-applied fold.</p>
     *
     * @return the total number of vectors folded in across both classes
     */
    public int rebuild() {
        final int[] total = {0};
        db.inTransaction(() -> {
            db.delete(AiSchema.T_CENTROID, null, null);
            ContentValues zero = new ContentValues();
            zero.put("IN_CENTROID", 0);
            db.update(AiSchema.T_TASTE, zero, null, null);

            Map<String, Acc> byClass = new LinkedHashMap<>();
            List<String> folded = new ArrayList<>();
            try (Cursor c = db.query("SELECT T.AI_KEY, T.STATE, E.MODEL, E.TASK, E.VEC"
                    + " FROM AI_TASTE T JOIN AI_EMBEDDING E ON E.AI_KEY = T.AI_KEY"
                    + " ORDER BY T.AI_KEY", null)) {
                while (c.moveToNext()) {
                    String aiKey = c.getString(0);
                    String state = c.getString(1);
                    if (!isClass(state)) {
                        continue;
                    }
                    float[] vec = AiEmbeddingStore.unpack(c.getBlob(4));
                    if (vec == null || vec.length == 0) {
                        continue;
                    }
                    Acc acc = byClass.get(state);
                    if (acc == null) {
                        acc = new Acc(c.getString(2), c.getString(3), vec.length);
                        byClass.put(state, acc);
                    }
                    if (acc.sum.length != vec.length) {
                        continue;   // mixed dimensions: assertCompatible() should have prevented it
                    }
                    AiVec.addInPlace(acc.sum, vec, +1);
                    acc.n++;
                    folded.add(aiKey);
                }
            }
            for (Map.Entry<String, Acc> e : byClass.entrySet()) {
                Acc acc = e.getValue();
                if (acc.n > 0) {
                    put(e.getKey(), acc.model, acc.task, acc.sum, acc.n);
                }
            }
            ContentValues one = new ContentValues();
            one.put("IN_CENTROID", 1);
            for (String aiKey : folded) {
                db.update(AiSchema.T_TASTE, one, "AI_KEY = ?", new String[]{aiKey});
            }
            total[0] = folded.size();
        });
        return total[0];
    }

    /**
     * Cheap agreement check: does each stored {@code N} match the number of taste rows marked as
     * folded into it?
     */
    public boolean consistent() {
        return consistent(CLASS_KEPT) && consistent(CLASS_REJECTED);
    }

    private boolean consistent(String cls) {
        int stored = memberCount(cls);
        int counted = (int) db.queryLong(
                "SELECT COUNT(*) FROM AI_TASTE T JOIN AI_EMBEDDING E ON E.AI_KEY = T.AI_KEY"
                        + " WHERE T.STATE = ? AND T.IN_CENTROID = 1", new String[]{cls}, 0L);
        return stored == counted;
    }

    /**
     * {@code SUM += sign * vec ; N += sign}. Dropping to {@code N <= 0} deletes the row: a class
     * with no members is <b>missing</b>, not zero.
     */
    private void add(String cls, AiEmbeddingStore.Row row, int sign) {
        Row cur = get(cls);
        float[] sum;
        int n;
        boolean sameSpace = cur != null && cur.sum != null
                && cur.sum.length == row.vec.length
                && equal(cur.model, row.model) && equal(cur.task, row.task);
        if (sameSpace) {
            sum = cur.sum;
            n = cur.n;
        } else {
            sum = AiVec.zeros(row.vec.length);
            n = 0;
        }
        AiVec.addInPlace(sum, row.vec, sign);
        n += sign;
        if (n <= 0) {
            delete(cls);
        } else {
            put(cls, row.model, row.task, sum, n);
        }
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** In-memory accumulator used only by {@link #rebuild()}. */
    private static final class Acc {
        private final String model;
        private final String task;
        private final float[] sum;
        private int n;

        private Acc(String model, String task, int dim) {
            this.model = model;
            this.task = task;
            this.sum = AiVec.zeros(dim);
        }
    }
}
