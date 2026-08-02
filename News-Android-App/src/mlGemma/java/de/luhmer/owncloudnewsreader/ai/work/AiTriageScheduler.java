package de.luhmer.owncloudnewsreader.ai.work;

import android.content.Context;
import android.util.Log;

import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import de.luhmer.owncloudnewsreader.ai.AiFeature;

/**
 * The only place triage is started. Exists twice at the same fully-qualified name, once per
 * {@code ml} source set; the {@code mlNone} copy is a no-op.
 *
 * <p><b>Enqueue, never inline.</b> {@code OwnCloudSyncAdapter.onPerformSync()} is synchronous and
 * its {@code syncRunning} flag drives the UI spinner — running a minute of embedding inside it would
 * leave the user staring at a spinner and would block the sync framework's thread. The AI pass is
 * strictly downstream of the sync and owns its own lifecycle.</p>
 *
 * <p>{@link ExistingWorkPolicy#KEEP}: several syncs in a row must not queue several passes. The pass
 * always reads the current state of the database, so the one already running (or queued) will see
 * everything the later sync brought in.</p>
 */
public final class AiTriageScheduler {

    private static final String TAG = "AiTriageScheduler";

    private AiTriageScheduler() {
        // no instances
    }

    /** Called from {@code onPerformSync()} after {@code startFaviconDownload()}. */
    public static void enqueueAfterSync(Context context) {
        enqueue(context);
    }

    /** Called by the diagnostics row. Bypasses trigger preferences, but not the master switch. */
    public static void enqueueNow(Context context) {
        enqueue(context);
    }

    private static void enqueue(Context context) {
        if (context == null || !AiFeature.isEnabled(context)) {
            return;
        }
        try {
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AiTriageWorker.class)
                    .addTag(AiTriageWorker.UNIQUE_WORK_NAME)
                    .build();
            WorkManager.getInstance(context.getApplicationContext())
                    .enqueueUniqueWork(AiTriageWorker.UNIQUE_WORK_NAME,
                            ExistingWorkPolicy.KEEP, request);
        } catch (Throwable t) {
            // A scheduling failure must never fail a sync.
            Log.e(TAG, "could not enqueue AI triage", t);
        }
    }

    /** Cancels any pending or running pass. Used when the master switch is turned off. */
    public static void cancel(Context context) {
        if (context == null) {
            return;
        }
        try {
            WorkManager.getInstance(context.getApplicationContext())
                    .cancelUniqueWork(AiTriageWorker.UNIQUE_WORK_NAME);
        } catch (Throwable t) {
            Log.e(TAG, "could not cancel AI triage", t);
        }
    }
}
