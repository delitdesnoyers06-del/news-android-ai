package de.luhmer.owncloudnewsreader.ai.work;

import android.content.Context;
import android.util.Log;

import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import de.luhmer.owncloudnewsreader.ai.AiFeature;
import de.luhmer.owncloudnewsreader.ai.AiTasteDrafts;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;

/**
 * The two on-demand passes: the digest abstract and the taste draft. Exists twice at the same
 * fully-qualified name, once per {@code ml} source set; the {@code mlNone} copy is a no-op.
 *
 * <p>Both are {@link ExistingWorkPolicy#KEEP}. Opening "For you" four times in a minute must not
 * queue four abstracts, and double-tapping "Suggest" must not run two drafts against one engine.</p>
 */
public final class AiLazyScheduler {

    private static final String TAG = "AiLazyScheduler";

    private AiLazyScheduler() {
        // no instances
    }

    /**
     * Called when the reader opens "For you" and today's digest still has no abstract. Cheap enough
     * to call unconditionally: the worker re-checks everything and exits in milliseconds when there
     * is nothing to do.
     */
    public static void enqueueDigest(Context context) {
        if (context == null || !AiFeature.isEnabled(context)) {
            return;
        }
        try {
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AiDigestWorker.class)
                    .addTag(AiDigestWorker.UNIQUE_WORK_NAME)
                    .build();
            WorkManager.getInstance(context.getApplicationContext())
                    .enqueueUniqueWork(AiDigestWorker.UNIQUE_WORK_NAME,
                            ExistingWorkPolicy.KEEP, request);
        } catch (Throwable t) {
            Log.e(TAG, "could not enqueue the digest", t);
        }
    }

    /**
     * Debug-only force path behind "Force create digest": unlike {@link #enqueueDigest(Context)} this
     * uses {@link ExistingWorkPolicy#REPLACE} so a fresh run supersedes any pending/KEPT one and the
     * abstract is regenerated for today's (just rebuilt) digest row.
     */
    public static void enqueueDigestForce(Context context) {
        if (context == null || !AiFeature.isEnabled(context)) {
            return;
        }
        try {
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AiDigestWorker.class)
                    .addTag(AiDigestWorker.UNIQUE_WORK_NAME)
                    .build();
            WorkManager.getInstance(context.getApplicationContext())
                    .enqueueUniqueWork(AiDigestWorker.UNIQUE_WORK_NAME,
                            ExistingWorkPolicy.REPLACE, request);
        } catch (Throwable t) {
            Log.e(TAG, "could not enqueue the forced digest", t);
        }
    }

    /** Called from the "Suggest from my decisions" button, and from nowhere else. */
    public static void enqueueTasteDraft(Context context, AiDb db) {
        if (context == null || !AiFeature.isEnabled(context)) {
            return;
        }
        try {
            AiTasteDrafts.running(db);
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(AiTasteDraftWorker.class)
                    .addTag(AiTasteDraftWorker.UNIQUE_WORK_NAME)
                    .build();
            WorkManager.getInstance(context.getApplicationContext())
                    .enqueueUniqueWork(AiTasteDraftWorker.UNIQUE_WORK_NAME,
                            ExistingWorkPolicy.KEEP, request);
        } catch (Throwable t) {
            Log.e(TAG, "could not enqueue the taste draft", t);
            AiTasteDrafts.fail(db, "enqueue");
        }
    }

    /** True when this build can run either pass at all. Drives the visibility of the button. */
    public static boolean available() {
        return true;
    }
}
