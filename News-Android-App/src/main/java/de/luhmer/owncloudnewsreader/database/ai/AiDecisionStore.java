package de.luhmer.owncloudnewsreader.database.ai;

import android.content.ContentValues;
import android.database.Cursor;

import java.util.ArrayList;
import java.util.List;

/**
 * The human signal: append-only {@code AI_DECISION} plus its fold into {@code AI_TASTE}.
 *
 * <p><b>{@code AI_DECISION} is append-only</b> (PLAN invariant 6). Nothing in the app may
 * {@code UPDATE} or {@code DELETE} a row. "Undo" <i>appends</i> an {@code undo} row and deletes the
 * {@code AI_TASTE} row; the disagreement history — the entire input to the learn loop — is never
 * erased.</p>
 *
 * <p><b>The transition table</b> (PLAN D28, ported from veille {@code domain/decisions.py:29-38}):</p>
 * <pre>
 *   keep    valid from queued, rejected, discarded  -> kept
 *   reject  valid from queued, kept, discarded      -> rejected
 *   restore valid from discarded                    -> queued
 *   undo    valid from kept, rejected               -> queued
 * </pre>
 * Anything else is a <b>silent idempotent no-op that writes no {@code AI_DECISION} row</b>. It must
 * not toast, must not vibrate, must not log an error: on a phone the UI fires from a list that moved
 * under the user's thumb.
 *
 * <p><b>Two vocabularies, one state.</b> {@code AI_TASTE.STATE} holds the human words
 * ({@code kept|rejected}); when there is no taste row the state falls back to the machine word in
 * {@code AI_SCORE.STATUS} ({@code queued|selected|discarded}), and to {@code queued} when the
 * article has no score row either. {@code selected} is <b>normalised to {@code queued}</b> for the
 * purposes of the table above — both mean "the pipeline is done, the human has not ruled yet", and
 * the table only distinguishes {@code discarded} because {@code restore} needs it. The <i>observed</i>
 * state (which may literally be {@code selected}) is what gets recorded in {@code FROM_STATE}.</p>
 */
public class AiDecisionStore {

    public static final String ACTION_KEEP = "keep";
    public static final String ACTION_REJECT = "reject";
    public static final String ACTION_RESTORE = "restore";
    public static final String ACTION_UNDO = "undo";

    public static final String STATE_QUEUED = "queued";
    public static final String STATE_SELECTED = "selected";
    public static final String STATE_DISCARDED = "discarded";
    public static final String STATE_KEPT = "kept";
    public static final String STATE_REJECTED = "rejected";

    public static final String SOURCE_SWIPE = "swipe";
    public static final String SOURCE_FASTACTION = "fastaction";
    public static final String SOURCE_STAR_SEED = "star_seed";
    public static final String SOURCE_SETTINGS = "settings";

    /** {@code AI_META} counter feeding the warm-up gate (PLAN D29). */
    public static final String KEY_DECISION_COUNT = "decision_count";

    private final AiDb db;

    public AiDecisionStore(AiDb db) {
        this.db = db;
    }

    /** Outcome of {@link #decide}. {@code changed == false} means nothing was written. */
    public static final class Result {
        /** The state observed before the call. Recorded verbatim in {@code FROM_STATE}. */
        public final String from;
        public final String state;
        public final boolean changed;

        Result(String from, String state, boolean changed) {
            this.from = from;
            this.state = state;
            this.changed = changed;
        }
    }

    /** One row of {@code AI_DECISION}. */
    public static class Row {
        public long id;
        public String aiKey;
        public String action;
        public String fromState;
        public String toState;
        public Integer llmScoreAt;
        public String source;
        public String titleSnap;
        public long createdAt;
    }

    /**
     * Applies {@code action} to {@code aiKey}. Valid transitions append exactly one
     * {@code AI_DECISION} row and update the {@code AI_TASTE} fold; invalid ones write nothing.
     *
     * @param llmScoreAt the article's {@code LLM_SCORE} at decision time, or {@code null} when it
     *                   never reached the LLM — that null is INFORMATION for the learn loop, not a
     *                   missing value
     * @param titleSnap  sanitised title (caller clips to 120 chars); the few-shot corpus
     */
    public Result decide(String aiKey, String action, String source,
                         Integer llmScoreAt, String titleSnap) {
        return decide(aiKey, action, source, llmScoreAt, titleSnap, System.currentTimeMillis());
    }

    public Result decide(String aiKey, String action, String source,
                         Integer llmScoreAt, String titleSnap, long now) {
        final String from = currentState(aiKey);
        final String to = targetOf(action, from);
        if (to == null) {
            return new Result(from, from, false);   // idempotent no-op: no row, no noise
        }
        db.inTransaction(() -> {
            ContentValues d = new ContentValues();
            d.put("AI_KEY", aiKey);
            d.put("ACTION", action);
            d.put("FROM_STATE", from);
            d.put("TO_STATE", to);
            if (llmScoreAt == null) {
                d.putNull("LLM_SCORE_AT");
            } else {
                d.put("LLM_SCORE_AT", llmScoreAt);
            }
            d.put("SOURCE", source);
            if (titleSnap == null) {
                d.putNull("TITLE_SNAP");
            } else {
                d.put("TITLE_SNAP", titleSnap);
            }
            d.put("CREATED_AT", now);
            db.insert(AiSchema.T_DECISION, d);

            if (STATE_KEPT.equals(to) || STATE_REJECTED.equals(to)) {
                ContentValues t = new ContentValues();
                t.put("AI_KEY", aiKey);
                t.put("STATE", to);
                t.put("DECIDED_AT", now);
                // The vector must be (re)folded into the centroid of its new class, so the row is
                // never carried over as already-counted. AiCentroidStore.backfill() picks it up.
                t.put("IN_CENTROID", 0);
                db.insertOrReplace(AiSchema.T_TASTE, t);
            } else {
                // to == queued: undo/restore. The taste row means "is a member"; drop it.
                db.delete(AiSchema.T_TASTE, "AI_KEY = ?", new String[]{aiKey});
            }
            db.incrementMeta(KEY_DECISION_COUNT, 1);
        });
        return new Result(from, to, true);
    }

    /**
     * The composite state of an article: the human word if there is one, else the machine word,
     * else {@code queued}.
     */
    public String currentState(String aiKey) {
        String taste = db.queryString("SELECT STATE FROM AI_TASTE WHERE AI_KEY = ?",
                new String[]{aiKey});
        if (taste != null) {
            return taste;
        }
        String status = db.queryString(
                "SELECT STATUS FROM AI_SCORE WHERE AI_KEY = ? ORDER BY RSS_ITEM_ID LIMIT 1",
                new String[]{aiKey});
        return status != null ? status : STATE_QUEUED;
    }

    /**
     * The transition table. {@code null} = not a legal transition.
     *
     * @param from the <i>observed</i> state; {@code selected} is normalised to {@code queued}
     */
    public static String targetOf(String action, String from) {
        final String s = STATE_SELECTED.equals(from) ? STATE_QUEUED : from;
        if (action == null || s == null) {
            return null;
        }
        switch (action) {
            case ACTION_KEEP:
                return STATE_QUEUED.equals(s) || STATE_REJECTED.equals(s) || STATE_DISCARDED.equals(s)
                        ? STATE_KEPT : null;
            case ACTION_REJECT:
                return STATE_QUEUED.equals(s) || STATE_KEPT.equals(s) || STATE_DISCARDED.equals(s)
                        ? STATE_REJECTED : null;
            case ACTION_RESTORE:
                return STATE_DISCARDED.equals(s) ? STATE_QUEUED : null;
            case ACTION_UNDO:
                return STATE_KEPT.equals(s) || STATE_REJECTED.equals(s) ? STATE_QUEUED : null;
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------ reads

    /** Full history for one article, newest first. */
    public List<Row> history(String aiKey) {
        try (Cursor c = db.query("SELECT _id, AI_KEY, ACTION, FROM_STATE, TO_STATE, LLM_SCORE_AT,"
                + " SOURCE, TITLE_SNAP, CREATED_AT FROM AI_DECISION WHERE AI_KEY = ?"
                + " ORDER BY _id DESC", new String[]{aiKey})) {
            return readAll(c);
        }
    }

    public List<Row> recent(int limit) {
        try (Cursor c = db.query("SELECT _id, AI_KEY, ACTION, FROM_STATE, TO_STATE, LLM_SCORE_AT,"
                        + " SOURCE, TITLE_SNAP, CREATED_AT FROM AI_DECISION ORDER BY _id DESC LIMIT ?",
                new String[]{String.valueOf(limit)})) {
            return readAll(c);
        }
    }

    public int decisionCount() {
        return (int) db.getMetaLong(KEY_DECISION_COUNT, 0L);
    }

    public int decisionRowCount() {
        return (int) db.queryLong("SELECT COUNT(*) FROM AI_DECISION", null, 0L);
    }

    public int tasteCount(String state) {
        return (int) db.queryLong("SELECT COUNT(*) FROM AI_TASTE WHERE STATE = ?",
                new String[]{state}, 0L);
    }

    /** @return {@code kept}, {@code rejected}, or {@code null} when the article is undecided. */
    public String tasteState(String aiKey) {
        return db.queryString("SELECT STATE FROM AI_TASTE WHERE AI_KEY = ?", new String[]{aiKey});
    }

    public List<String> tasteKeys(String state) {
        try (Cursor c = db.query("SELECT AI_KEY FROM AI_TASTE WHERE STATE = ? ORDER BY DECIDED_AT",
                new String[]{state})) {
            List<String> out = new ArrayList<>(c.getCount());
            while (c.moveToNext()) {
                out.add(c.getString(0));
            }
            return out;
        }
    }

    /** Marks a taste row as already folded into its centroid (or not). */
    public void markInCentroid(String aiKey, boolean folded) {
        ContentValues v = new ContentValues();
        v.put("IN_CENTROID", folded ? 1 : 0);
        db.update(AiSchema.T_TASTE, v, "AI_KEY = ?", new String[]{aiKey});
    }

    public List<String> pendingCentroidKeys() {
        try (Cursor c = db.query("SELECT AI_KEY FROM AI_TASTE WHERE IN_CENTROID = 0", null)) {
            List<String> out = new ArrayList<>(c.getCount());
            while (c.moveToNext()) {
                out.add(c.getString(0));
            }
            return out;
        }
    }

    private static List<Row> readAll(Cursor c) {
        List<Row> out = new ArrayList<>(c.getCount());
        while (c.moveToNext()) {
            Row r = new Row();
            r.id = c.getLong(0);
            r.aiKey = c.getString(1);
            r.action = c.getString(2);
            r.fromState = c.getString(3);
            r.toState = c.getString(4);
            r.llmScoreAt = c.isNull(5) ? null : c.getInt(5);
            r.source = c.getString(6);
            r.titleSnap = c.isNull(7) ? null : c.getString(7);
            r.createdAt = c.getLong(8);
            out.add(r);
        }
        return out;
    }
}
