package de.luhmer.owncloudnewsreader.ai.work;

import android.content.Context;

/**
 * The {@code mlNone} copy: this build has no AI runtime and no {@code androidx.work} dependency, so
 * there is nothing to schedule. Both methods are no-ops.
 *
 * <p>Keeping the seam here rather than behind a flag at the call site is what lets
 * {@code OwnCloudSyncAdapter} carry the same single line in both flavors.</p>
 *
 * @see de.luhmer.owncloudnewsreader.ai.work.AiTriageScheduler the mlGemma implementation
 */
public final class AiTriageScheduler {

    private AiTriageScheduler() {
        // no instances
    }

    public static void enqueueAfterSync(Context context) {
        // no AI in this flavor
    }

    public static void cancel(Context context) {
        // no AI in this flavor
    }
}
