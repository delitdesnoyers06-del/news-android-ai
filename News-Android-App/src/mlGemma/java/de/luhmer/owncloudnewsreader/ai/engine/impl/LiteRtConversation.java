package de.luhmer.owncloudnewsreader.ai.engine.impl;

import android.util.Log;

import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.MessageCallback;
import com.google.ai.edge.litertlm.ResponseFormat;

import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import de.luhmer.owncloudnewsreader.ai.engine.AiConversation;
import de.luhmer.owncloudnewsreader.ai.engine.AiException;
import de.luhmer.owncloudnewsreader.ai.engine.AiResponseFormat;

/**
 * One conversation on a loaded engine.
 *
 * <p>Uses the <b>blocking</b> {@code sendMessage} overload rather than the {@code Flow} one: the
 * Flow variant is the only member of the API that would drag coroutines into a Java call site, and
 * the caller ({@code LlmCall}) is already on a worker thread with its own watchdog.</p>
 *
 * <p>The streaming variant ({@link #send(String, AiResponseFormat, TokenSink)}) uses the
 * <b>callback</b> overload {@code sendMessageAsync(String, MessageCallback, ...)} — which is plain
 * Java, no coroutines — and blocks the worker thread on a latch until {@code onDone}/{@code onError}
 * so it keeps the same blocking contract as {@code send}. Each {@code onMessage} chunk is a delta
 * (the callback layer does no accumulation), so we forward it straight to the sink and also collect
 * it into the returned full text.</p>
 *
 * <p>{@code cancelProcess()} is safe from another thread while {@code sendMessage} blocks and lands
 * within roughly one token — that is what makes the 120 s watchdog and "disable AI mid-run" real
 * rather than aspirational.</p>
 *
 * <h3>The cancel/close race (why {@code nativeLock} exists)</h3>
 * The watchdog runs {@code cancel()} on a scheduler thread. The worker thread runs {@code close()}
 * in a {@code finally} as soon as {@code send} returns. Nothing orders those two, so without a lock
 * the sequence <i>watchdog checks alive &rarr; worker closes &rarr; watchdog calls
 * cancelProcess()</i> reaches the JNI layer with a freed handle: a native use-after-free, which is
 * a SIGSEGV rather than an exception we could catch. So {@code cancel()} and {@code close()} hold
 * one monitor and {@code close()} latches a flag that makes every later {@code cancel()} a no-op.
 *
 * <p><b>{@code send} deliberately does not take that monitor.</b> Cancelling a blocked send is the
 * entire point of {@code cancel()}; if send held the lock, the watchdog would block on it until the
 * call it is supposed to abort had finished. The cost is that {@code close()} may wait for an
 * in-flight {@code cancelProcess()}, which is bounded by roughly one token, and that closing while
 * a send is still running remains the caller's contract to avoid — as it already was.</p>
 */
final class LiteRtConversation implements AiConversation {

    private static final String TAG = "LiteRtConversation";

    private final Conversation conv;
    /** The grammar, or null. Non-null only when {@code enableResponseFormat} was set at creation. */
    private final ResponseFormat responseFormat;
    /** True when {@code enableResponseFormat} was set, i.e. when a per-call format is legal at all. */
    private final boolean formatEnabled;

    /** Serialises native teardown against native cancellation. See the class comment. */
    private final Object nativeLock = new Object();
    /** Guarded by {@link #nativeLock}. */
    private boolean closed;

    LiteRtConversation(Conversation conv, String grammar) {
        this.conv = conv;
        this.responseFormat = grammar == null ? null : ResponseFormat.regex(grammar);
        this.formatEnabled = grammar != null;
    }

    @Override
    public String send(String userText) throws AiException {
        return send(userText, null);
    }

    @Override
    public String send(String userText, AiResponseFormat format) throws AiException {
        try {
            Message out = conv.sendMessage(
                    userText,
                    Collections.<String, Object>emptyMap(),  // extraContext
                    null,                                    // RepetitionPenaltyConfig
                    null,                                    // NoRepeatNgramConfig
                    null,                                    // SuppressTokensConfig
                    null,                                    // maxOutputToken (from the config)
                    null,                                    // ThinkingConfig
                    formatFor(format));
            return textOf(out);
        } catch (OutOfMemoryError t) {
            throw new AiException(AiException.Kind.OUT_OF_MEMORY, "decode OOM", t);
        } catch (Throwable t) {
            throw new AiException(AiException.Kind.RUNTIME, "sendMessage failed", t);
        }
    }

    @Override
    public String send(String userText, AiResponseFormat format, TokenSink sink) throws AiException {
        if (sink == null) {
            return send(userText, format);
        }
        final StringBuilder full = new StringBuilder();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        try {
            conv.sendMessageAsync(
                    userText,
                    new MessageCallback() {
                        @Override
                        public void onMessage(Message message) {
                            String delta = textOf(message);
                            if (!delta.isEmpty()) {
                                full.append(delta);
                                try {
                                    sink.onToken(delta);
                                } catch (Throwable t) {
                                    Log.w(TAG, "token sink threw", t);
                                }
                            }
                        }

                        @Override
                        public void onDone() {
                            done.countDown();
                        }

                        @Override
                        public void onError(Throwable t) {
                            error.set(t);
                            done.countDown();
                        }
                    },
                    Collections.<String, Object>emptyMap(),   // extraContext
                    null,                                     // RepetitionPenaltyConfig
                    null,                                     // NoRepeatNgramConfig
                    null,                                     // SuppressTokensConfig
                    null,                                     // maxOutputToken (from the config)
                    null,                                     // ThinkingConfig
                    formatFor(format));
            done.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiException(AiException.Kind.RUNTIME, "streaming interrupted", e);
        } catch (OutOfMemoryError t) {
            throw new AiException(AiException.Kind.OUT_OF_MEMORY, "decode OOM", t);
        } catch (Throwable t) {
            throw new AiException(AiException.Kind.RUNTIME, "sendMessageAsync failed", t);
        }
        Throwable t = error.get();
        if (t != null) {
            if (t instanceof OutOfMemoryError) {
                throw new AiException(AiException.Kind.OUT_OF_MEMORY, "decode OOM", t);
            }
            throw new AiException(AiException.Kind.RUNTIME, "streaming failed", t);
        }
        return full.toString();
    }

    /** Concatenates the text parts of one message, ignoring any non-text content. */
    private static String textOf(Message message) {
        if (message == null || message.getContents() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Content c : message.getContents().getContents()) {
            if (c instanceof Content.Text) {
                sb.append(((Content.Text) c).getText());
            }
        }
        return sb.toString();
    }

    /**
     * Resolves the per-call override against the conversation's own format.
     *
     * <p>A {@code null} response format is always legal: {@code sendMessage} only throws when a
     * <i>non-null</i> format meets {@code enableResponseFormat=false} (bytecode offsets 12-33), and
     * {@code resolveResponseFormat} returns immediately on null (offsets 0-5). So dropping the
     * grammar for one turn needs no cooperation from the config.
     *
     * <p>Going the other way is not possible: a conversation created unconstrained cannot be given
     * a grammar later, so a {@code regex(...)} override on one is ignored with a log line rather
     * than turned into an {@code IllegalArgumentException} in a background service.</p>
     */
    private ResponseFormat formatFor(AiResponseFormat override) {
        if (override == null) {
            return responseFormat;                  // the conversation's own
        }
        String pattern = override.regex();
        if (pattern == null) {
            return null;                            // AiResponseFormat.NONE - drop it
        }
        if (!formatEnabled) {
            Log.d(TAG, "ignoring a per-call grammar on an unconstrained conversation");
            return null;
        }
        return ResponseFormat.regex(pattern);
    }

    @Override
    public void cancel() {
        synchronized (nativeLock) {
            if (closed) {
                // The watchdog lost the race with close(). Nothing to cancel, and touching the
                // handle now would be a use-after-free.
                return;
            }
            try {
                conv.cancelProcess();
            } catch (Throwable t) {
                Log.w(TAG, "cancelProcess failed", t);
            }
        }
    }

    @Override
    public void close() {
        synchronized (nativeLock) {
            if (closed) {
                return;
            }
            closed = true;
            try {
                conv.close();
            } catch (Throwable t) {
                Log.w(TAG, "conversation close failed", t);
            }
        }
    }
}
