package de.luhmer.owncloudnewsreader.ai.engine;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One cancellation signal, shared by every source that can stop a run: {@code Worker.onStopped()},
 * {@code cb_ai_enabled} flipping off, a critical {@code onTrimMemory}, {@code shutdownNow()} and the
 * notification's Stop action.
 *
 * <p>Cancellation is not failure. An in-flight batch degrades to {@code LLM_SCORE = NULL}, which is
 * explicitly retryable on the next sync — as opposed to {@code 0}, which is a final judgement.</p>
 */
public final class CancelToken {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile String reason;

    public void cancel(String why) {
        this.reason = why;
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public String reason() {
        return reason;
    }

    /** A token that is never cancelled. Convenience for call sites with no lifecycle. */
    public static CancelToken none() {
        return new CancelToken();
    }
}
