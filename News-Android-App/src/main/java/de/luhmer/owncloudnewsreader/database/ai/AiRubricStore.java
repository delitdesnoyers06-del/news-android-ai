package de.luhmer.owncloudnewsreader.database.ai;

import android.content.ContentValues;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;

/**
 * CRUD on {@code AI_RUBRIC} — the interests note, append-only with exactly one {@code ACTIVE} row.
 *
 * <p>Append-only means a version history and a one-tap revert come for free, which is the mitigation
 * for rubric-learn collapse (PLAN R18). Nothing here ever writes a row from a model response: that
 * is a UI decision in Phase 8 and requires an explicit tap.</p>
 */
public class AiRubricStore {

    public static final String SOURCE_USER = "user";
    public static final String SOURCE_MODEL_APPROVED = "model_approved";
    public static final String SOURCE_SEED = "seed";

    private static final String COLUMNS = "_id, BODY, RATIONALE, ACTIVE, SOURCE, CREATED_AT";

    private final AiDb db;

    public AiRubricStore(AiDb db) {
        this.db = db;
    }

    /** One row of {@code AI_RUBRIC}. */
    public static class Row {
        public long id;
        public String body;
        public String rationale;
        public boolean active;
        public String source;
        public long createdAt;
    }

    /**
     * Appends a version. When {@code makeActive} is set, every other row is deactivated in the same
     * transaction so the "exactly one ACTIVE" invariant is never observable as violated.
     *
     * @return the new row id
     */
    public long append(String body, String rationale, String source, boolean makeActive) {
        return append(body, rationale, source, makeActive, System.currentTimeMillis());
    }

    public long append(String body, String rationale, String source, boolean makeActive, long now) {
        final long[] id = new long[1];
        db.inTransaction(() -> {
            if (makeActive) {
                ContentValues off = new ContentValues();
                off.put("ACTIVE", 0);
                db.update(AiSchema.T_RUBRIC, off, "ACTIVE = 1", null);
            }
            ContentValues v = new ContentValues();
            v.put("BODY", body);
            if (rationale == null) {
                v.putNull("RATIONALE");
            } else {
                v.put("RATIONALE", rationale);
            }
            v.put("ACTIVE", makeActive ? 1 : 0);
            v.put("SOURCE", source);
            v.put("CREATED_AT", now);
            id[0] = db.insert(AiSchema.T_RUBRIC, v);
        });
        return id[0];
    }

    /** @return the active rubric, or {@code null} when the user has never written one. */
    public Row active() {
        try (Cursor c = db.query("SELECT " + COLUMNS + " FROM AI_RUBRIC WHERE ACTIVE = 1"
                + " ORDER BY _id DESC LIMIT 1", null)) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    public String activeBody() {
        Row r = active();
        return r == null ? null : r.body;
    }

    /** Makes an existing version active again — the revert path. */
    public void activate(long id) {
        db.inTransaction(() -> {
            ContentValues off = new ContentValues();
            off.put("ACTIVE", 0);
            db.update(AiSchema.T_RUBRIC, off, "ACTIVE = 1", null);
            ContentValues on = new ContentValues();
            on.put("ACTIVE", 1);
            db.update(AiSchema.T_RUBRIC, on, "_id = ?", new String[]{String.valueOf(id)});
        });
    }

    /** Newest first. */
    public List<Row> history(int limit) {
        try (Cursor c = db.query("SELECT " + COLUMNS + " FROM AI_RUBRIC ORDER BY _id DESC LIMIT ?",
                new String[]{String.valueOf(limit)})) {
            List<Row> out = new ArrayList<>(c.getCount());
            while (c.moveToNext()) {
                out.add(read(c));
            }
            return out;
        }
    }

    public Row get(long id) {
        try (Cursor c = db.query("SELECT " + COLUMNS + " FROM AI_RUBRIC WHERE _id = ?",
                new String[]{String.valueOf(id)})) {
            return c.moveToFirst() ? read(c) : null;
        }
    }

    public int count() {
        return (int) db.queryLong("SELECT COUNT(*) FROM AI_RUBRIC", null, 0L);
    }

    private static Row read(Cursor c) {
        Row r = new Row();
        r.id = c.getLong(0);
        r.body = c.getString(1);
        r.rationale = c.isNull(2) ? null : c.getString(2);
        r.active = c.getInt(3) == 1;
        r.source = c.getString(4);
        r.createdAt = c.getLong(5);
        return r;
    }
}
