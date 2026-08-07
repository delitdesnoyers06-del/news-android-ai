package de.luhmer.owncloudnewsreader.ai.work;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.ForegroundInfo;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.List;
import java.util.Locale;

import de.luhmer.owncloudnewsreader.SettingsActivity;
import de.luhmer.owncloudnewsreader.articlefulltext.ArticleFullTextExtraction;
import de.luhmer.owncloudnewsreader.ai.AiCorrections;
import de.luhmer.owncloudnewsreader.ai.AiDecisions;
import de.luhmer.owncloudnewsreader.ai.AiFeature;
import de.luhmer.owncloudnewsreader.ai.AiNote;
import de.luhmer.owncloudnewsreader.ai.AiRunStatus;
import de.luhmer.owncloudnewsreader.ai.AiTriagePipeline;
import de.luhmer.owncloudnewsreader.ai.download.AiModelRepository;
import de.luhmer.owncloudnewsreader.ai.engine.AiEngineManager;
import de.luhmer.owncloudnewsreader.ai.engine.AiModelInfo;
import de.luhmer.owncloudnewsreader.ai.engine.CancelToken;
import de.luhmer.owncloudnewsreader.ai.prompt.AiInterests;
import de.luhmer.owncloudnewsreader.ai.prompt.AiPrompts;
import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.notification.NextcloudNotificationManager;

/**
 * The WorkManager wrapper. All it does is set up a foreground notification, resolve which model to
 * use, and call {@link AiTriagePipeline}; the pipeline holds the logic and is testable without any
 * of this.
 *
 * <p><b>{@code doWork()} has exactly one {@code return Result.success()} and never returns
 * {@code Result.failure()} or {@code Result.retry()}.</b> A failed AI run is not a failed unit of
 * work — the next sync enqueues another one anyway, and returning failure/retry from a job that runs
 * after every sync produces a retry storm on exactly the devices that are already struggling. The
 * outcome lives in {@link AiTriagePipeline.Report}, not in the {@code Result}.
 *
 * <p>Lives in {@code src/mlGemma} because {@code androidx.work} is an {@code mlGemmaImplementation}
 * dependency: the {@code mlNone} flavor's dependency graph stays byte-identical to today's, which is
 * the F-Droid build.
 */
public class AiTriageWorker extends Worker {

    private static final String TAG = "AiTriageWorker";

    /** Also the notification channel name. */
    public static final String UNIQUE_WORK_NAME = "ai-triage";

    private static final int NOTIFICATION_ID = 4711;

    /** Shared with {@link #onStopped()}, which runs on a different thread. */
    private final CancelToken token = new CancelToken();

    public AiTriageWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Context ctx = getApplicationContext();
            SharedPreferences prefs = AiFeature.prefsOf(ctx);
            if (!AiFeature.isEnabled(ctx, prefs)) {
                Log.d(TAG, "AI disabled or unsupported - nothing to do");
            } else {
                goForeground(ctx);
                AiDb db = new DatabaseConnectionOrm(ctx).aiDb();
                if (db == null) {
                    Log.w(TAG, "AI schema unavailable - skipping run");
                } else {
                    // One-time, opt-in: import the user's stars as keep decisions so the taste
                    // model has something to work with before they have swiped anything.
                    if (prefs.getBoolean(SettingsActivity.CB_AI_STAR_IS_LIKE, false)) {
                        AiDecisions.seedFromStarred(ctx);
                    }
                    long now = System.currentTimeMillis();
                    boolean allUnread = AiTriagePipeline.allUnreadWhileCharging(prefs,
                            isCharging(ctx));
                    int budget = allUnread ? AiTriagePipeline.ALL_UNREAD_BUDGET
                            : AiTriagePipeline.topKFor(ctx, prefs);
                    // Fetch full article bodies for teaser feeds BEFORE embedding, so the whole AI
                    // pipeline (embeddings, scoring, digest) reads the real article rather than the
                    // RSS excerpt. Synchronous on purpose: it must finish before candidate selection.
                    // Same opt-in toggle as the display feature - off means no third-party fetches.
                    if (prefs.getBoolean(SettingsActivity.CB_FULLTEXT_EXTRACTION, false)) {
                        int scan = allUnread ? 400 : ArticleFullTextExtraction.DEFAULT_SCAN_LIMIT;
                        int max = allUnread ? 120 : ArticleFullTextExtraction.DEFAULT_MAX_FETCHES;
                        ArticleFullTextExtraction.run(ctx, new DatabaseConnectionOrm(ctx), scan, max);
                    }
                    AiTriagePipeline pipeline = new AiTriagePipeline(ctx, db,
                            new AiEngineManager(ctx, db), budget, budget, !allUnread);
                    AiTriagePipeline.Scoring scoring = scoringFor(ctx, db, prefs);
                    if (scoring != null) {
                        pipeline.withScoring(scoring);
                    }
                    AiTriagePipeline.Report report = pipeline.run(now, now);
                    AiRunStatus.save(db, now, report);
                    // A separate key from PREF_AI_LAST_RUN: that one is a display-only Preference,
                    // and storing a long under a Preference's own key invites a ClassCastException
                    // the first time something asks the preference framework to read it.
                    prefs.edit().putLong(SettingsActivity.PREF_AI_LAST_RUN + "_at", now).apply();
                    Log.i(TAG, String.valueOf(report));
                }
            }
        } catch (Throwable t) {
            // Nothing an AI pass can do justifies failing a background job. Log and succeed.
            Log.e(TAG, "triage run failed", t);
        }
        return Result.success();
    }

    /**
     * Resolves the scoring half.
     *
     * <p>{@code null} is a completely normal answer: no model downloaded, the stage switched off, or
     * a device that cannot select the one that is installed. The pipeline then runs its Phase 4
     * shape — embeddings, centroids, prefilter, rank on similarity — which is a working product on
     * its own. That is why this returns null instead of throwing.
     */
    private AiTriagePipeline.Scoring scoringFor(Context ctx, AiDb db, SharedPreferences prefs) {
        try {
            AiModelRepository repo = new AiModelRepository(ctx, db);
            AiModelInfo model = repo.resolveLlm(prefs, SettingsActivity.SP_AI_MODEL_TRIAGE);
            if (model == null) {
                Log.i(TAG, "no scoring model resolved; similarity-only run");
                return null;
            }
            // AiNote, not the raw preference: it falls back to the active AI_RUBRIC row, so a
            // note that survived a preferences reset still reaches the scoring prompt.
            String note = AiNote.current(prefs, db);
            List<String> slugs = AiInterests.themeSlugs(note);
            boolean gpu = prefs.getBoolean(SettingsActivity.CB_AI_GPU_BACKEND, false);
            return new AiTriagePipeline.Scoring(model, AiPrompts.of(ctx), note, slugs,
                    AiCorrections.recent(db, 3), Locale.getDefault(), gpu,
                    preferredBatch(prefs), token);
        } catch (Throwable t) {
            Log.w(TAG, "could not configure scoring; similarity-only run", t);
            return null;
        }
    }

    /** {@code sp_ai_score_batch}; 0 means "let the model decide" (4 constrained, 1 otherwise). */
    private static int preferredBatch(SharedPreferences prefs) {
        String v = prefs.getString(SettingsActivity.SP_AI_SCORE_BATCH, null);
        if (v == null) {
            return 0;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isCharging(Context ctx) {
        try {
            Intent i = ctx.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) {
                return false;
            }
            int plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            if (plugged == BatteryManager.BATTERY_PLUGGED_AC
                    || plugged == BatteryManager.BATTERY_PLUGGED_USB
                    || plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) {
                return true;
            }
            int status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            return status == BatteryManager.BATTERY_STATUS_CHARGING
                    || status == BatteryManager.BATTERY_STATUS_FULL;
        } catch (Throwable t) {
            Log.w(TAG, "could not read battery state", t);
            return false;
        }
    }

    /**
     * WorkManager stopping us is the single most common way a run ends early. Cancelling the token
     * makes the in-flight batch degrade to {@code LLM_SCORE = NULL} — retryable next sync — and
     * {@code shutdownNow} releases the engine rather than leaving multi-GB resident in a process
     * that is about to be killed anyway.
     */
    @Override
    public void onStopped() {
        token.cancel("worker stopped");
        AiEngineManager.shutdownNow("worker stopped");
        super.onStopped();
    }

    /**
     * Best-effort promotion to a foreground service. If the OS refuses (background start
     * restrictions, missing notification permission), the run continues as ordinary background work
     * rather than dying — the pass is short and idempotent.
     */
    private void goForeground(Context ctx) {
        try {
            Notification n = NextcloudNotificationManager
                    .buildNotificationAiTriage(ctx, UNIQUE_WORK_NAME);
            ForegroundInfo info;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // The 3-argument form is mandatory on 34+.
                info = new ForegroundInfo(NOTIFICATION_ID, n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                info = new ForegroundInfo(NOTIFICATION_ID, n);
            }
            setForegroundAsync(info);
        } catch (Throwable t) {
            Log.w(TAG, "could not go foreground; continuing in the background", t);
        }
    }
}
