package de.luhmer.owncloudnewsreader.database.ai;

import android.content.ContentValues;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;

/**
 * CRUD on {@code AI_MODEL} — the <b>local</b> registry of what is on disk. The catalogue of what
 * <i>could</i> be downloaded is {@code res/raw/ai_model_catalog.json} and arrives in Phase 5.
 *
 * <p>States: {@code absent -> partial -> verifying -> installed | broken}. The transition rules and
 * the download state machine live in {@code AiModelRepository} (Phase 5); this class only stores.</p>
 *
 * <p>{@code PATH} must never be left pointing at a file that is gone — a dangling path is a silent,
 * permanent AI outage (PLAN R8). {@link #setState} nulls it whenever the state leaves
 * {@code installed}.</p>
 */
public class AiModelRegistry {

    public static final String KIND_LLM = "llm";
    public static final String KIND_EMBEDDER = "embedder";

    public static final String STATE_ABSENT = "absent";
    public static final String STATE_PARTIAL = "partial";
    public static final String STATE_VERIFYING = "verifying";
    public static final String STATE_INSTALLED = "installed";
    public static final String STATE_BROKEN = "broken";

    private static final String COLUMNS =
            "MODEL_ID, KIND, PATH, STATE, SIZE_BYTES, RECEIVED_BYTES, ETAG, SHA256, CAPS,"
                    + " LAST_ERROR, UPDATED_AT";

    private final AiDb db;

    public AiModelRegistry(AiDb db) {
        this.db = db;
    }

    /** One row of {@code AI_MODEL}. */
    public static class Row {
        public String modelId;
        public String kind;
        public String path;
        public String state;
        public Long sizeBytes;
        public Long receivedBytes;
        public String etag;
        public String sha256;
        public String caps;
        public String lastError;
        public long updatedAt;
    }

    /** Creates the row if absent; never downgrades an existing one. */
    public void register(String modelId, String kind, Long sizeBytes, String sha256) {
        db.exec("INSERT OR IGNORE INTO AI_MODEL"
                        + " (MODEL_ID, KIND, STATE, SIZE_BYTES, SHA256, UPDATED_AT)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                new Object[]{modelId, kind, STATE_ABSENT, sizeBytes, sha256,
                        System.currentTimeMillis()});
    }

    public Row get(String modelId) {
        try (Cursor c = db.query("SELECT " + COLUMNS + " FROM AI_MODEL WHERE MODEL_ID = ?",
                new String[]{modelId})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    public List<Row> all() {
        try (Cursor c = db.query("SELECT " + COLUMNS + " FROM AI_MODEL ORDER BY MODEL_ID", null)) {
            List<Row> out = new ArrayList<>(c.getCount());
            while (c.moveToNext()) {
                out.add(read(c));
            }
            return out;
        }
    }

    public List<Row> byState(String state) {
        try (Cursor c = db.query("SELECT " + COLUMNS + " FROM AI_MODEL WHERE STATE = ?"
                + " ORDER BY MODEL_ID", new String[]{state})) {
            List<Row> out = new ArrayList<>(c.getCount());
            while (c.moveToNext()) {
                out.add(read(c));
            }
            return out;
        }
    }

    public String pathOf(String modelId) {
        return db.queryString("SELECT PATH FROM AI_MODEL WHERE MODEL_ID = ? AND STATE = ?",
                new String[]{modelId, STATE_INSTALLED});
    }

    /**
     * Moves a model to {@code state}. Leaving {@code installed} clears {@code PATH} so no caller can
     * ever hand a dangling path to the engine.
     */
    public void setState(String modelId, String state, String path, String lastError) {
        ContentValues v = new ContentValues();
        v.put("STATE", state);
        if (STATE_INSTALLED.equals(state) && path != null) {
            v.put("PATH", path);
        } else {
            v.putNull("PATH");
        }
        if (lastError == null) {
            v.putNull("LAST_ERROR");
        } else {
            v.put("LAST_ERROR", lastError);
        }
        v.put("UPDATED_AT", System.currentTimeMillis());
        db.update(AiSchema.T_MODEL, v, "MODEL_ID = ?", new String[]{modelId});
    }

    public void setProgress(String modelId, long receivedBytes, Long sizeBytes, String etag) {
        ContentValues v = new ContentValues();
        v.put("RECEIVED_BYTES", receivedBytes);
        if (sizeBytes != null) {
            v.put("SIZE_BYTES", sizeBytes);
        }
        if (etag != null) {
            v.put("ETAG", etag);
        }
        v.put("UPDATED_AT", System.currentTimeMillis());
        db.update(AiSchema.T_MODEL, v, "MODEL_ID = ?", new String[]{modelId});
    }

    /** The probe result, e.g. {@code {"responseFormat":true,"tokenizer":"sp","loadMs":91000}}. */
    public void setCaps(String modelId, String capsJson) {
        ContentValues v = new ContentValues();
        if (capsJson == null) {
            v.putNull("CAPS");
        } else {
            v.put("CAPS", capsJson);
        }
        v.put("UPDATED_AT", System.currentTimeMillis());
        db.update(AiSchema.T_MODEL, v, "MODEL_ID = ?", new String[]{modelId});
    }

    public void delete(String modelId) {
        db.delete(AiSchema.T_MODEL, "MODEL_ID = ?", new String[]{modelId});
    }

    private static Row read(Cursor c) {
        Row r = new Row();
        r.modelId = c.getString(0);
        r.kind = c.getString(1);
        r.path = c.isNull(2) ? null : c.getString(2);
        r.state = c.getString(3);
        r.sizeBytes = c.isNull(4) ? null : c.getLong(4);
        r.receivedBytes = c.isNull(5) ? null : c.getLong(5);
        r.etag = c.isNull(6) ? null : c.getString(6);
        r.sha256 = c.isNull(7) ? null : c.getString(7);
        r.caps = c.isNull(8) ? null : c.getString(8);
        r.lastError = c.isNull(9) ? null : c.getString(9);
        r.updatedAt = c.getLong(10);
        return r;
    }
}
