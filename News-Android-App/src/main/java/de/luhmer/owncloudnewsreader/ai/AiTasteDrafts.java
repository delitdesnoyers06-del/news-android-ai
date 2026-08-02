package de.luhmer.owncloudnewsreader.ai;

import android.util.Log;

import de.luhmer.owncloudnewsreader.ai.prompt.TasteDraftGuard;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;

/**
 * Where a proposed interests note waits for a human.
 *
 * <p>{@code AiTasteDraftWorker} runs in the background and finishes whenever it finishes; the reader
 * may have left the settings screen, rotated the device or killed the app. So the draft is parked in
 * {@code AI_META} rather than delivered as an event, and the diff screen reads it whenever it opens.
 * A draft is data, not a notification.</p>
 *
 * <p>Nothing here writes {@code edt_ai_interests}. That is {@link AiNote#save}, and it is only ever
 * reached from a tap.</p>
 */
public final class AiTasteDrafts {

    private static final String TAG = "AiTasteDrafts";

    public static final String KEY_STATE = "taste_draft_state";
    public static final String KEY_BODY = "taste_draft_body";
    public static final String KEY_META = "taste_draft_meta";

    public static final String STATE_IDLE = "idle";
    public static final String STATE_RUNNING = "running";
    public static final String STATE_READY = "ready";
    public static final String STATE_NO_CHANGE = "no_change";
    public static final String STATE_FAILED = "failed";

    private AiTasteDrafts() {
        // no instances
    }

    /** A parked draft. {@link #body} is only meaningful in state {@link #STATE_READY}. */
    public static final class Draft {
        public String state = STATE_IDLE;
        public String body = "";
        /** Machine reason for {@link #STATE_FAILED}; rendered as a generic message. */
        public String reason = "";
        public boolean warnShrink;
        public boolean warnTopicsRemoved;
        public boolean preselectKeepCurrent;
        public int removedLines;
        public int addedLines;
        public long createdAt;

        public boolean isReady() {
            return STATE_READY.equals(state) && body != null && !body.trim().isEmpty();
        }
    }

    public static void running(AiDb db) {
        if (db == null) {
            return;
        }
        try {
            db.putMeta(KEY_STATE, STATE_RUNNING);
            db.putMeta(KEY_BODY, "");
            db.putMeta(KEY_META, String.valueOf(System.currentTimeMillis()));
        } catch (Throwable t) {
            Log.w(TAG, "could not mark the draft running", t);
        }
    }

    /** Persists a guard verdict. A rejected or repair-needing verdict is stored as a failure. */
    public static void store(AiDb db, TasteDraftGuard.Verdict verdict) {
        if (db == null) {
            return;
        }
        if (verdict == null) {
            fail(db, "error");
            return;
        }
        try {
            switch (verdict.outcome) {
                case OK:
                    db.putMeta(KEY_STATE, STATE_READY);
                    db.putMeta(KEY_BODY, verdict.draft);
                    db.putMeta(KEY_META, encode(verdict));
                    break;
                case NO_CHANGE:
                    db.putMeta(KEY_STATE, STATE_NO_CHANGE);
                    db.putMeta(KEY_BODY, "");
                    db.putMeta(KEY_META, encode(verdict));
                    break;
                default:
                    fail(db, verdict.reason);
                    break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not store the draft", t);
        }
    }

    public static void fail(AiDb db, String reason) {
        if (db == null) {
            return;
        }
        try {
            db.putMeta(KEY_STATE, STATE_FAILED);
            db.putMeta(KEY_BODY, "");
            db.putMeta(KEY_META, System.currentTimeMillis() + "|0|0|0|0|"
                    + (reason == null ? "error" : reason));
        } catch (Throwable t) {
            Log.w(TAG, "could not store the failure", t);
        }
    }

    public static Draft get(AiDb db) {
        Draft d = new Draft();
        if (db == null) {
            return d;
        }
        try {
            String state = db.getMeta(KEY_STATE);
            if (state != null) {
                d.state = state;
            }
            String body = db.getMeta(KEY_BODY);
            d.body = body == null ? "" : body;
            decode(d, db.getMeta(KEY_META));
        } catch (Throwable t) {
            Log.w(TAG, "could not read the draft", t);
        }
        return d;
    }

    public static void clear(AiDb db) {
        if (db == null) {
            return;
        }
        try {
            db.putMeta(KEY_STATE, STATE_IDLE);
            db.putMeta(KEY_BODY, "");
            db.putMeta(KEY_META, "");
        } catch (Throwable t) {
            Log.w(TAG, "could not clear the draft", t);
        }
    }

    /** {@code createdAt|shrink|topics|removed|added|reason} — five ints and a word, no JSON. */
    static String encode(TasteDraftGuard.Verdict v) {
        return System.currentTimeMillis() + "|" + (v.warnShrink ? 1 : 0) + "|"
                + (v.warnTopicsRemoved ? 1 : 0) + "|" + v.removedTopicLines + "|"
                + v.addedTopicLines + "|" + (v.reason == null ? "" : v.reason);
    }

    static void decode(Draft d, String meta) {
        if (meta == null || meta.isEmpty()) {
            return;
        }
        String[] parts = meta.split("\\|", -1);
        d.createdAt = parseLong(parts, 0);
        d.warnShrink = parseLong(parts, 1) == 1L;
        d.warnTopicsRemoved = parseLong(parts, 2) == 1L;
        d.removedLines = (int) parseLong(parts, 3);
        d.addedLines = (int) parseLong(parts, 4);
        d.reason = parts.length > 5 ? parts[5] : "";
        d.preselectKeepCurrent = d.warnShrink || d.warnTopicsRemoved;
    }

    private static long parseLong(String[] parts, int index) {
        if (parts.length <= index) {
            return 0L;
        }
        try {
            return Long.parseLong(parts[index].trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
