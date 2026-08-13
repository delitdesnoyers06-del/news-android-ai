package de.luhmer.owncloudnewsreader.services.podcast;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits article text into pieces small enough for a single TextToSpeech.speak() call.
 * Anything longer than getMaxSpeechInputLength() gets rejected by the engine, so longer
 * articles need to be cut up and queued (see issue #839). We try to cut at sentence ends,
 * fall back to word boundaries and only cut hard when there is no other option.
 */
public final class TtsTextSplitter {

    /**
     * Sentence-ish chunk size used when there is no engine input-length limit to respect (the neural
     * TTS path). Kept small so the first sentence starts playing quickly and so it stays clear of
     * multi-byte scripts. The native {@code TextToSpeech} path caps this against
     * {@code getMaxSpeechInputLength()} as well.
     */
    public static final int DEFAULT_CHUNK_SIZE = 200;

    private TtsTextSplitter() { }

    public static List<String> split(String text, int maxLen) {
        if (maxLen <= 0) {
            throw new IllegalArgumentException("maxLen must be > 0, was " + maxLen);
        }

        List<String> chunks = new ArrayList<>();
        if (text == null) {
            return chunks;
        }

        String remaining = text.trim();
        while (!remaining.isEmpty()) {
            if (remaining.length() <= maxLen) {
                chunks.add(remaining);
                break;
            }

            int splitAt = findSplitIndex(remaining, maxLen);
            String chunk = remaining.substring(0, splitAt).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            remaining = remaining.substring(splitAt).trim();
        }
        return chunks;
    }

    private static int findSplitIndex(String text, int maxLen) {
        int window = Math.min(maxLen, text.length());

        int sentenceEnd = lastSentenceBoundary(text, window);
        if (sentenceEnd > 0) {
            return sentenceEnd;
        }

        int lastSpace = lastWhitespace(text, window);
        if (lastSpace > 0) {
            return lastSpace;
        }

        return maxLen;
    }

    /**
     * Index just past the first sentence terminator ({@code . ! ? \n}) at or after {@code from} that
     * is <b>followed by whitespace still present in {@code text}</b>; {@code -1} if none.
     *
     * <p>Used by the streaming path to emit whole sentences as soon as they complete. A terminator
     * sitting at the very end of the buffer returns {@code -1} on purpose: we cannot yet tell an
     * end-of-sentence period from the dot in "U.S." until the next character arrives, so we wait for
     * more text (the tail is flushed explicitly at stream end).</p>
     */
    public static int firstSentenceBoundary(String text, int from) {
        if (text == null) {
            return -1;
        }
        int n = text.length();
        for (int i = Math.max(0, from); i < n; i++) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?' || c == '\n')
                    && i + 1 < n && Character.isWhitespace(text.charAt(i + 1))) {
                return i + 1;
            }
        }
        return -1;
    }

    private static int lastSentenceBoundary(String text, int window) {
        for (int i = window - 1; i > 0; i--) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?' || c == '\n')
                    && (i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1)))) {
                return i + 1;
            }
        }
        return -1;
    }

    private static int lastWhitespace(String text, int window) {
        for (int i = window - 1; i > 0; i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return i + 1;
            }
        }
        return -1;
    }
}
