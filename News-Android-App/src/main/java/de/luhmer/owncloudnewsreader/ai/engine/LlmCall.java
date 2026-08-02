package de.luhmer.owncloudnewsreader.ai.engine;

import android.util.Log;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One model turn, with a watchdog, <b>and it never throws</b>.
 *
 * <p>{@code sendMessage} is blocking and takes no timeout, so the watchdog is a scheduled
 * {@link AiConversation#cancel()} — which is safe from another thread and lands within about a
 * token. Everything else (OOM, a native crash surfaced as an exception, the user disabling AI
 * mid-run) is converted to {@link Result#failure}.</p>
 *
 * <p>This is the single place PLAN invariant 3 and 7 are made mechanical: no exception from the
 * inference half can reach {@code AiTriageWorker.doWork()}, and therefore none can reach
 * {@code onPerformSync}.</p>
 */
public final class LlmCall {

    private static final String TAG = "LlmCall";

    private static final ScheduledExecutorService WATCHDOG =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "ai-call-watchdog");
                t.setDaemon(true);
                return t;
            });

    private LlmCall() {
        // no instances
    }

    /** The outcome of one turn. {@code raw} is never null; {@code failure} is null on success. */
    public static final class Result {
        public final String raw;
        public final AiException failure;
        public final long wallMs;

        Result(String raw, AiException failure, long wallMs) {
            this.raw = raw == null ? "" : raw;
            this.failure = failure;
            this.wallMs = wallMs;
        }

        public boolean ok() {
            return failure == null;
        }
    }

    /**
     * Runs one turn on the caller's worker thread.
     *
     * @param token checked before the call and used by the caller to abort the run; a cancelled
     *              token short-circuits to {@link AiException.Kind#CANCELLED} without touching the
     *              model, which is what makes "disable AI" land in under five seconds
     */
    public static Result run(AiConversation conv, String userText, long timeoutMs,
                             CancelToken token) {
        return run(conv, userText, timeoutMs, token, null);
    }

    /**
     * Runs one turn with a per-turn output constraint.
     *
     * @param format {@code null} to use the conversation's own constraint,
     *               {@link AiResponseFormat#NONE} to drop it for this turn, or
     *               {@link AiResponseFormat#regex(String)} to replace it
     */
    public static Result run(AiConversation conv, String userText, long timeoutMs,
                             CancelToken token, AiResponseFormat format) {
        final long t0 = System.currentTimeMillis();
        if (token != null && token.isCancelled()) {
            return new Result("", new AiException(AiException.Kind.CANCELLED,
                    "cancelled before send: " + token.reason()), 0L);
        }
        // AtomicBoolean, not boolean[]: this is written on the watchdog thread and read on this
        // one, and a plain array element gives no happens-before edge between them. Under the Java
        // memory model the worker is entitled to keep reading the stale `false` forever, which
        // reports a call the watchdog aborted as a clean success and feeds a truncated answer into
        // the parser as if the model had meant it.
        final AtomicBoolean firedByWatchdog = new AtomicBoolean(false);
        ScheduledFuture<?> wd = null;
        try {
            wd = WATCHDOG.schedule(() -> {
                firedByWatchdog.set(true);
                try {
                    // Safe against a concurrent close(): AiConversation requires cancel() and
                    // close() to be mutually exclusive and cancel-after-close to be a no-op.
                    conv.cancel();
                } catch (Throwable t) {
                    Log.w(TAG, "watchdog cancel failed", t);
                }
            }, timeoutMs, TimeUnit.MILLISECONDS);

            String raw = conv.send(userText, format);
            long wall = System.currentTimeMillis() - t0;
            if (firedByWatchdog.get()) {
                return new Result(raw, new AiException(AiException.Kind.TIMEOUT,
                        "call exceeded " + timeoutMs + " ms"), wall);
            }
            if (token != null && token.isCancelled()) {
                return new Result(raw, new AiException(AiException.Kind.CANCELLED,
                        String.valueOf(token.reason())), wall);
            }
            return new Result(raw, null, wall);
        } catch (AiException e) {
            return new Result("", e, System.currentTimeMillis() - t0);
        } catch (OutOfMemoryError e) {
            return new Result("", new AiException(AiException.Kind.OUT_OF_MEMORY, "call OOM", e),
                    System.currentTimeMillis() - t0);
        } catch (Throwable t) {
            AiException.Kind kind = firedByWatchdog.get()
                    ? AiException.Kind.TIMEOUT : AiException.Kind.RUNTIME;
            return new Result("", new AiException(kind, "call failed", t),
                    System.currentTimeMillis() - t0);
        } finally {
            if (wd != null) {
                // cancel(true): if the watchdog task is already running, interrupt it so it is not
                // left half-executed while the caller goes on to close the conversation. The
                // interrupt does not abort a native cancelProcess() — nothing can — which is why
                // the actual safety comes from the conversation serialising cancel against close.
                wd.cancel(true);
            }
        }
    }
}
