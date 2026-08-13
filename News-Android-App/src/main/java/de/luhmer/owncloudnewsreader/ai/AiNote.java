package de.luhmer.owncloudnewsreader.ai;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.luhmer.owncloudnewsreader.SettingsActivity;
import de.luhmer.owncloudnewsreader.ai.prompt.TasteDraftGuard;
import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm;
import de.luhmer.owncloudnewsreader.database.ai.AiCentroidStore;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.AiDecisionStore;
import de.luhmer.owncloudnewsreader.database.ai.AiRubricStore;
import de.luhmer.owncloudnewsreader.database.ai.AiScoreStore;

/**
 * The interests note: one free-text paragraph the reader owns, and the rubric that goes into the
 * scoring prompt.
 *
 * <h3>The invariant this class exists for</h3>
 * <b>No code path writes the note from a model response.</b> {@code AiTasteDraftWorker} produces a
 * <i>draft</i> and parks it in {@code AI_META}; the note itself only ever changes through
 * {@link #save}, which is called from the settings field the reader typed into and from the Save
 * button in {@code AiRubricDiffActivity}. There is no third caller and there must not be one.
 *
 * <h3>Two stores, one truth</h3>
 * The live value is the preference {@code edt_ai_interests} — that is what the {@code
 * EditTextPreference} reads and writes, and what {@code AiTriageWorker} passes to the scorer.
 * {@code AI_RUBRIC} holds the append-only version history behind it, which is what makes revert a
 * single tap. On a mismatch the preference wins: it is the thing the reader last typed into, and it
 * survives a database wipe (Clear cache) that the rubric table does not care about.
 *
 * <h3>Saving has consequences</h3>
 * The note is part of the taste identity, so a new note invalidates the cached centroid units and
 * marks every stored {@code LLM_SCORE} stale. Without that, editing the note appears to do nothing
 * for a week — which teaches the reader the field is decorative.
 */
public final class AiNote {

    private static final String TAG = "AiNote";

    /** {@code AI_META}: a hash of the note that produced the current scores. Diagnostics. */
    public static final String KEY_INTERESTS_HASH = "interests_hash";

    /** How many versions the revert list offers. */
    public static final int HISTORY_LIMIT = 5;

    private AiNote() {
        // no instances
    }

    /** The live note. Never null; the empty string is a valid, working configuration. */
    public static String current(Context context) {
        if (context == null) {
            return "";
        }
        return current(AiFeature.prefsOf(context), null);
    }

    public static String current(SharedPreferences prefs, AiDb db) {
        String fromPrefs = prefs == null ? null : prefs.getString(SettingsActivity.EDT_AI_INTERESTS, "");
        if (fromPrefs != null && !fromPrefs.trim().isEmpty()) {
            return fromPrefs.trim();
        }
        if (db != null) {
            try {
                String body = new AiRubricStore(db).activeBody();
                if (body != null && !body.trim().isEmpty()) {
                    return body.trim();
                }
            } catch (Throwable t) {
                Log.w(TAG, "rubric unreadable", t);
            }
        }
        return "";
    }

    /**
     * Writes a new version of the note.
     *
     * @param source one of {@link AiRubricStore#SOURCE_USER} (typed),
     *               {@link AiRubricStore#SOURCE_MODEL_APPROVED} (drafted by the model, approved by a
     *               tap) or {@link AiRubricStore#SOURCE_SEED}
     * @return true when something was written
     */
    public static boolean save(Context context, String body, String source) {
        if (context == null) {
            return false;
        }
        String clean = body == null ? "" : body.trim();
        try {
            SharedPreferences prefs = AiFeature.prefsOf(context);
            String previous = current(prefs, null);
            prefs.edit().putString(SettingsActivity.EDT_AI_INTERESTS, clean).apply();
            if (clean.equals(previous)) {
                return false;
            }

            AiDb db = new DatabaseConnectionOrm(context).aiDb();
            if (db == null) {
                // The preference is written either way: the note is the reader's, not the
                // database's, and losing it because the AI schema is unavailable is not acceptable.
                return true;
            }
            new AiRubricStore(db).append(clean, null, source, true);
            invalidate(db, clean);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "could not save the interests note", t);
            return false;
        }
    }

    /**
     * The three side effects of a new note, in one place so they cannot drift apart:
     * the cached centroid units go, the cache key goes, and every verdict becomes retryable.
     */
    private static void invalidate(AiDb db, String note) {
        try {
            AiCentroidStore centroids = new AiCentroidStore(db);
            centroids.invalidateUnits();
            centroids.setUnitCacheKey(null);
            int stale = new AiScoreStore(db).markScoresStale();
            db.putMeta(KEY_INTERESTS_HASH, hashOf(note));
            Log.i(TAG, "note changed: " + stale + " scores marked stale");
        } catch (Throwable t) {
            Log.e(TAG, "could not invalidate after a note change", t);
        }
    }

    /** Stable, cheap, and only ever compared to itself. */
    public static String hashOf(String note) {
        String s = note == null ? "" : note.trim();
        return Integer.toHexString(s.hashCode()) + ":" + s.length();
    }

    /** Newest first, active row included. */
    public static List<AiRubricStore.Row> history(Context context, int limit) {
        try {
            AiDb db = new DatabaseConnectionOrm(context).aiDb();
            if (db == null) {
                return new ArrayList<>();
            }
            return new AiRubricStore(db).history(limit);
        } catch (Throwable t) {
            Log.e(TAG, "could not read the note history", t);
            return new ArrayList<>();
        }
    }

    /** One-tap revert: activates an older version and mirrors it back into the preference. */
    public static boolean revert(Context context, long rubricId) {
        try {
            AiDb db = new DatabaseConnectionOrm(context).aiDb();
            if (db == null) {
                return false;
            }
            AiRubricStore store = new AiRubricStore(db);
            AiRubricStore.Row row = store.get(rubricId);
            if (row == null) {
                return false;
            }
            store.activate(rubricId);
            AiFeature.prefsOf(context).edit()
                    .putString(SettingsActivity.EDT_AI_INTERESTS, row.body).apply();
            invalidate(db, row.body);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "could not revert the note", t);
            return false;
        }
    }

    /**
     * The gate on "suggest a note from my decisions": veille's
     * {@link TasteDraftGuard#LEARN_MIN_DECISIONS}. It is a precondition, not a schedule — nothing
     * ever drafts a note on a timer.
     */
    public static boolean canSuggest(AiDb db) {
        if (db == null) {
            return false;
        }
        try {
            return new AiDecisionStore(db).decisionCount() >= TasteDraftGuard.LEARN_MIN_DECISIONS;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int decisionCount(AiDb db) {
        if (db == null) {
            return 0;
        }
        try {
            return new AiDecisionStore(db).decisionCount();
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Forgets everything the taste model learned: decisions, folded state, centroids, and the note's
     * effect on stored scores. The note itself and its history are <b>kept</b> — the reader wrote
     * those and a "reset" button is not licence to delete a person's prose.
     */
    public static boolean resetTaste(Context context) {
        try {
            AiDb db = new DatabaseConnectionOrm(context).aiDb();
            if (db == null) {
                return false;
            }
            db.inTransaction(() -> {
                db.exec("DELETE FROM AI_DECISION");
                db.exec("DELETE FROM AI_TASTE");
                db.exec("DELETE FROM AI_CENTROID");
                db.putMeta(AiDecisionStore.KEY_DECISION_COUNT, "0");
                db.putMeta(AiCentroidStore.KEY_UNIT_CACHE, null);
            });
            new AiScoreStore(db).markScoresStale();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "could not reset the taste model", t);
            return false;
        }
    }

    /** A one-line summary for the settings row: "12 decisions · 4 topics". */
    public static String summary(Context context, AiDb db) {
        int decisions = decisionCount(db);
        int topics = LineDiff.topicLines(current(context)).size();
        return String.format(Locale.getDefault(), "%d / %d", decisions,
                TasteDraftGuard.LEARN_MIN_DECISIONS) + " · " + topics;
    }
}
