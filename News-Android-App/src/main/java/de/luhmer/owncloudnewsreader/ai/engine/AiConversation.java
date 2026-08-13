package de.luhmer.owncloudnewsreader.ai.engine;

/**
 * One multi-turn exchange. Not thread-safe except for {@link #cancel()}.
 *
 * <p>One conversation <b>per batch</b>, closed after its repair turn (PLAN D17). The repair turn
 * needs the previous turn in context — that is the whole point of it — but batch N+1 must not
 * inherit batch N's KV cache: that is unbounded KV growth and a decode that drifts as the run goes
 * on.</p>
 *
 * <p><b>Implementations must make {@link #cancel()} and {@link #close()} mutually exclusive.</b>
 * The watchdog fires {@code cancel()} from a scheduler thread with no ordering against the worker
 * thread that closes the conversation, so an implementation over a native handle must not let a
 * cancel land on a handle that {@code close()} has already freed. {@code cancel()} after
 * {@code close()} must be a no-op, not a crash.</p>
 */
public interface AiConversation extends java.io.Closeable {

    /**
     * BLOCKING. Runs on the calling worker thread. Returns the model's raw text, never null.
     *
     * <p>Uses whatever output constraint the conversation was created with.</p>
     */
    String send(String userText) throws AiException;

    /**
     * BLOCKING, with a per-turn output constraint.
     *
     * @param format {@code null} to use the conversation's own constraint (identical to
     *               {@link #send(String)}), {@link AiResponseFormat#NONE} to drop it for this turn,
     *               or {@link AiResponseFormat#regex(String)} to replace it for this turn. See
     *               {@link AiResponseFormat} for why a repair turn wants a different one.
     */
    String send(String userText, AiResponseFormat format) throws AiException;

    /**
     * Receives generated text incrementally. Plain Java, so no coroutine types leak into
     * {@code src/main}: streaming implementations bridge the underlying (coroutine) stream and call
     * {@link #onToken(String)} on the calling worker thread for each delta.
     */
    interface TokenSink {
        /** One chunk of freshly generated text ({@code delta}), in generation order. */
        void onToken(String delta);
    }

    /**
     * BLOCKING, streaming variant: like {@link #send(String, AiResponseFormat)} but reports the
     * output incrementally through {@code sink} as it is generated, and still returns the full text.
     *
     * <p>The default is non-streaming: it runs the blocking turn and emits the whole result as a
     * single delta. That is exactly the right behaviour for flavors/engines without a streaming API
     * (e.g. the {@code FakeLlm} test double). Engines with a token stream override this.</p>
     *
     * @param sink may be {@code null}, in which case this is identical to
     *             {@link #send(String, AiResponseFormat)}.
     */
    default String send(String userText, AiResponseFormat format, TokenSink sink) throws AiException {
        String full = send(userText, format);
        if (sink != null && full != null && !full.isEmpty()) {
            sink.onToken(full);
        }
        return full;
    }

    /**
     * Safe to call from any thread while {@link #send} is blocked; lands within roughly one token.
     * The conversation stays usable afterwards, with its history cleared. A no-op once
     * {@link #close()} has run.
     */
    void cancel();

    @Override
    void close();
}
