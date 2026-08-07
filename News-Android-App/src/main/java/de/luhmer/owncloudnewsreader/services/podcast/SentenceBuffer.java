package de.luhmer.owncloudnewsreader.services.podcast;

/**
 * Turns a stream of arbitrary text deltas (as produced by a streaming LLM) into whole sentences.
 *
 * <p>The live-podcast pipeline feeds every generated delta here; each complete sentence is handed to
 * a {@link Sink} so the TTS producer can synthesise it while the model keeps generating. This is the
 * bridge between token-at-a-time generation and the sentence-at-a-time neural TTS.</p>
 *
 * <p>Not thread-safe: {@link #feed(String)} and {@link #flush()} are called from the one thread that
 * owns the LLM stream (the {@code MessageCallback} worker).</p>
 */
public final class SentenceBuffer {

    /** Receives whole sentences, in order. */
    public interface Sink {
        void onSentence(String sentence);
    }

    /**
     * Hard cap: if the model produces this many characters with no sentence boundary, cut at the last
     * whitespace anyway so playback never stalls waiting for a period that isn't coming.
     */
    private static final int MAX_PENDING = 400;

    private final Sink sink;
    private final StringBuilder pending = new StringBuilder();

    public SentenceBuffer(Sink sink) {
        this.sink = sink;
    }

    /** Appends a delta and emits any sentences that have now completed. */
    public void feed(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        pending.append(delta);
        drain();
    }

    private void drain() {
        int boundary;
        while ((boundary = TtsTextSplitter.firstSentenceBoundary(pending.toString(), 0)) > 0) {
            emit(boundary);
        }
        // Safety valve for a very long run with no punctuation (e.g. a list without full stops).
        while (pending.length() > MAX_PENDING) {
            int cut = lastWhitespaceBefore(pending, MAX_PENDING);
            emit(cut > 0 ? cut : MAX_PENDING);
        }
    }

    private void emit(int upTo) {
        String sentence = pending.substring(0, upTo).trim();
        pending.delete(0, upTo);
        if (!sentence.isEmpty()) {
            sink.onSentence(sentence);
        }
    }

    /** Emits whatever text is left as a final sentence. Call once, when the stream ends. */
    public void flush() {
        String tail = pending.toString().trim();
        pending.setLength(0);
        if (!tail.isEmpty()) {
            sink.onSentence(tail);
        }
    }

    private static int lastWhitespaceBefore(CharSequence s, int limit) {
        for (int i = Math.min(limit, s.length()) - 1; i > 0; i--) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i + 1;
            }
        }
        return -1;
    }
}
