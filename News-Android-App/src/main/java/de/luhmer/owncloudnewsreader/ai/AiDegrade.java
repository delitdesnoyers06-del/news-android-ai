package de.luhmer.owncloudnewsreader.ai;

import de.luhmer.owncloudnewsreader.ai.engine.AiException;

/**
 * The degradation ladder as one table, so "what does the user see when X fails?" has exactly one
 * answer and it is not spread across five call sites.
 *
 * <p>PLAN invariant 7: AI off, model missing, unsupported device, inference failed and OOM each have
 * a defined fallback. <b>Nothing throws to the user</b>, and every level below still leaves a usable
 * product — at worst the For You folder is a recency list, which is what the app showed before this
 * feature existed.</p>
 */
public final class AiDegrade {

    /** Ordered worst-to-best; the strip renders the lowest level reached during a run. */
    public enum Level {
        /** {@code cb_ai_enabled == false}. The folder and the buttons are not there at all. */
        OFF,
        /** No arm64-v8a or under 3 GB. There is no override and no retry. */
        UNSUPPORTED,
        /** No embedder installed: no vectors, so no taste model. Recency only. */
        NO_EMBEDDER,
        /** Embedder present, no scoring model: rank on similarity, no scores and no why-lines. */
        SIMILARITY_ONLY,
        /** A scoring model exists but this run could not use it. Retryable next sync. */
        SCORING_FAILED,
        /** Everything worked. */
        FULL
    }

    private AiDegrade() {
        // no instances
    }

    /**
     * @param kind the failure, or {@code null} when nothing failed
     * @return where the run landed. {@code NOT_INSTALLED} deliberately maps to
     *         {@link Level#SIMILARITY_ONLY} rather than to a failure: a user who has not downloaded
     *         a 2.6 GB scoring model has not failed at anything, and the taste model works without
     *         one.
     */
    public static Level levelOf(AiException.Kind kind, boolean embedderInstalled) {
        if (kind == null) {
            return embedderInstalled ? Level.FULL : Level.NO_EMBEDDER;
        }
        switch (kind) {
            case DISABLED:
                return Level.OFF;
            case UNSUPPORTED_DEVICE:
                return Level.UNSUPPORTED;
            case NOT_INSTALLED:
                return embedderInstalled ? Level.SIMILARITY_ONLY : Level.NO_EMBEDDER;
            default:
                return Level.SCORING_FAILED;
        }
    }

    /** Parses the string {@code AiTriagePipeline.Report.degraded} carries, tolerating anything. */
    public static AiException.Kind kindOf(String name) {
        if (name == null) {
            return null;
        }
        for (AiException.Kind k : AiException.Kind.values()) {
            if (k.name().equals(name)) {
                return k;
            }
        }
        return AiException.Kind.RUNTIME;
    }

    /**
     * True when the same work is worth attempting on the next sync. A missing model or an
     * unsupported device will not have changed; a timeout, a cancellation or a memory squeeze might.
     */
    public static boolean retryableNextSync(AiException.Kind kind) {
        if (kind == null) {
            return false;
        }
        return kind == AiException.Kind.TIMEOUT || kind == AiException.Kind.CANCELLED
                || kind == AiException.Kind.OUT_OF_MEMORY || kind == AiException.Kind.RUNTIME
                || kind == AiException.Kind.LOAD_FAILED;
    }
}
