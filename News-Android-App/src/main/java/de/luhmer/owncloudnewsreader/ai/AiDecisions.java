package de.luhmer.owncloudnewsreader.ai;

import android.content.Context;
import android.util.Log;

import java.util.List;

import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm;
import de.luhmer.owncloudnewsreader.database.ai.AiCentroidStore;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.AiDecisionStore;
import de.luhmer.owncloudnewsreader.database.ai.AiKeys;
import de.luhmer.owncloudnewsreader.database.ai.AiScoreStore;
import de.luhmer.owncloudnewsreader.database.model.RssItem;

/**
 * The one entry point the UI uses to record a taste decision (PLAN D5).
 *
 * <p>Swipe in the For-You folder, the two reader fast-action buttons, the Snackbar undo and the
 * one-time star seed all land here. Everything downstream is a consequence:</p>
 * <pre>
 *   AiDecisions.record()
 *     -> AiDecisionStore.decide()      append AI_DECISION + fold AI_TASTE + bump decision_count
 *     -> AiCentroidStore.onDecision()  incremental +/- on the running sums, UNIT = NULL
 * </pre>
 *
 * <p><b>Undo appends, it never deletes</b> (PLAN invariant 6). {@link #UNDO} writes an {@code undo}
 * row whose {@code TO_STATE} is {@code queued} and drops the {@code AI_TASTE} membership; the
 * disagreement history — the entire input to the learn loop — survives.</p>
 *
 * <p><b>Nothing here throws.</b> A decision is a gesture on a list that is moving under the user's
 * thumb; an invalid transition is a silent idempotent no-op (no toast, no vibration, no error log),
 * and a broken AI schema degrades to {@code false}. The caller may still remove the row from the
 * list — the list is a view, the decision store is the truth, and they are allowed to disagree for
 * one frame.</p>
 *
 * <p>Runs synchronously on the calling (main) thread on purpose: the writes are a handful of small
 * statements against an already-open database, and the undo must observe the keep. The codebase
 * already does DB work on the main thread from the mark-all-as-read FAB.</p>
 */
public final class AiDecisions {

    private static final String TAG = "AiDecisions";

    public static final String KEEP = AiDecisionStore.ACTION_KEEP;
    public static final String REJECT = AiDecisionStore.ACTION_REJECT;
    public static final String RESTORE = AiDecisionStore.ACTION_RESTORE;
    public static final String UNDO = AiDecisionStore.ACTION_UNDO;

    public static final String SOURCE_SWIPE = AiDecisionStore.SOURCE_SWIPE;
    public static final String SOURCE_FASTACTION = AiDecisionStore.SOURCE_FASTACTION;
    public static final String SOURCE_STAR_SEED = AiDecisionStore.SOURCE_STAR_SEED;
    public static final String SOURCE_SETTINGS = AiDecisionStore.SOURCE_SETTINGS;

    /** Product cap on the one-time star import (PLAN D5). */
    public static final int STAR_SEED_MAX = 50;

    /** {@code AI_META} flag: the star seed is one-time, whatever the preference does afterwards. */
    public static final String KEY_STAR_SEED_DONE = "star_seed_done";

    private AiDecisions() {
        // no instances
    }

    /**
     * Records a decision about an article.
     *
     * @return true iff a row was appended — false means "not a legal transition" or "AI storage
     *         unavailable", and in both cases the caller must stay quiet
     */
    public static boolean record(Context context, RssItem item, String action, String source) {
        if (context == null || item == null) {
            return false;
        }
        try {
            DatabaseConnectionOrm conn = new DatabaseConnectionOrm(context.getApplicationContext());
            return record(conn.aiDb(), item, action, source);
        } catch (Throwable t) {
            Log.e(TAG, "record(" + action + ") failed", t);
            return false;
        }
    }

    /** The testable core: everything above this is a database handle and a try/catch. */
    public static boolean record(AiDb db, RssItem item, String action, String source) {
        if (db == null || item == null) {
            return false;
        }
        try {
            final String aiKey = AiKeys.of(item);
            final AiDecisionStore decisions = new AiDecisionStore(db);
            final Integer llmScoreAt = llmScoreOf(new AiScoreStore(db), aiKey);
            final String titleSnap = AiText.titleSnap(item.getTitle());

            AiDecisionStore.Result r = decisions.decide(aiKey, action, source, llmScoreAt, titleSnap);
            if (!r.changed) {
                return false;
            }
            new AiCentroidStore(db).onDecision(aiKey, r.from, r.state);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "record(" + action + ") failed", t);
            return false;
        }
    }

    /**
     * The article's LLM verdict at decision time, or {@code null} when it never reached the LLM.
     *
     * <p>That null is <b>information</b>, not a missing value: the learn loop's disagreement
     * predicate treats "the user rejected something the model scored 3" and "the user rejected
     * something the model never saw" as different events.</p>
     */
    private static Integer llmScoreOf(AiScoreStore scores, String aiKey) {
        List<AiScoreStore.Row> rows = scores.byAiKey(aiKey);
        for (AiScoreStore.Row row : rows) {
            if (row.llmScore != null) {
                return row.llmScore;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ star seed

    /**
     * One-time opt-in import: the {@code <= 50} most recently starred articles become {@code keep}
     * decisions, source {@code star_seed}.
     *
     * <p>This is a <b>seed</b>, not a signal. Star is server-synced, has no negative twin, and its
     * real-world distribution is "I did not have time to read this" — which biases the liked
     * centroid toward long articles. Importing it once with the user's explicit consent buys a warm
     * start; wiring it as an ongoing positive would poison the model (PLAN D5).</p>
     *
     * <p>Idempotent: the {@code AI_META} flag is set even when zero articles were imported, so a
     * user with no stars does not get re-prompted forever. Individual decisions are idempotent
     * anyway — an already-{@code kept} article is a silent no-op.</p>
     *
     * @return the number of decisions actually appended
     */
    public static int seedFromStarred(Context context) {
        if (context == null) {
            return 0;
        }
        try {
            DatabaseConnectionOrm conn = new DatabaseConnectionOrm(context.getApplicationContext());
            AiDb db = conn.aiDb();
            if (db == null || db.getMeta(KEY_STAR_SEED_DONE) != null) {
                return 0;
            }
            List<RssItem> starred = conn.getStarredRssItemsNewestFirst(STAR_SEED_MAX);
            int seeded = 0;
            for (RssItem item : starred) {
                if (record(db, item, KEEP, SOURCE_STAR_SEED)) {
                    seeded++;
                }
            }
            db.putMeta(KEY_STAR_SEED_DONE, String.valueOf(System.currentTimeMillis()));
            Log.i(TAG, "star seed imported " + seeded + " of " + starred.size() + " starred articles");
            return seeded;
        } catch (Throwable t) {
            Log.e(TAG, "seedFromStarred failed", t);
            return 0;
        }
    }

    /** True once {@link #seedFromStarred(Context)} has run, whatever it found. */
    public static boolean starSeedDone(AiDb db) {
        return db != null && db.getMeta(KEY_STAR_SEED_DONE) != null;
    }
}
