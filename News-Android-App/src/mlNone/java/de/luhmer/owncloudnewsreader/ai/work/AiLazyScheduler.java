package de.luhmer.owncloudnewsreader.ai.work;

import android.content.Context;

import de.luhmer.owncloudnewsreader.database.ai.AiDb;

/**
 * The {@code mlNone} copy: no AI runtime and no {@code androidx.work} dependency in this flavor, so
 * there is nothing to schedule.
 *
 * <p>{@link #available()} returns false here, which is what lets the UI hide the "Suggest" button
 * rather than offer a control that silently does nothing.</p>
 *
 * @see de.luhmer.owncloudnewsreader.ai.work.AiLazyScheduler the mlGemma implementation
 */
public final class AiLazyScheduler {

    private AiLazyScheduler() {
        // no instances
    }

    public static void enqueueDigest(Context context) {
        // no AI in this flavor
    }

    public static void enqueueDigestForce(Context context) {
        // no AI in this flavor
    }

    public static void enqueueTasteDraft(Context context, AiDb db) {
        // no AI in this flavor
    }

    public static boolean available() {
        return false;
    }
}
