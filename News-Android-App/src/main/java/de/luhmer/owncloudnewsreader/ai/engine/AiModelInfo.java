package de.luhmer.owncloudnewsreader.ai.engine;

import java.io.File;

/** An installed model, resolved to a concrete file. Immutable; carries no runtime type. */
public final class AiModelInfo {

    public final String catalogId;
    public final String displayName;
    public final long sizeBytes;
    public final File file;
    /**
     * Whether the tokenizer is SentencePiece, i.e. whether LLGuidance will accept a grammar.
     * Seeded from the catalogue and overridden by the on-device probe result in {@code AI_MODEL.CAPS}
     * — the native library refuses constrained decoding on BPE with
     * {@code "Constrained decoding is only supported for SentencePiece tokenizer"}.
     */
    public final boolean sentencePiece;
    public final int maxNumTokens;

    public AiModelInfo(String catalogId, String displayName, long sizeBytes, File file,
                       boolean sentencePiece, int maxNumTokens) {
        this.catalogId = catalogId;
        this.displayName = displayName;
        this.sizeBytes = sizeBytes;
        this.file = file;
        this.sentencePiece = sentencePiece;
        this.maxNumTokens = maxNumTokens <= 0 ? 2048 : maxNumTokens;
    }

    /** A copy with {@link #sentencePiece} overridden — what the smoke probe produces. */
    public AiModelInfo withSentencePiece(boolean value) {
        return new AiModelInfo(catalogId, displayName, sizeBytes, file, value, maxNumTokens);
    }

    @Override
    public String toString() {
        return "AiModelInfo[" + catalogId + " sp=" + sentencePiece + " " + file + "]";
    }
}
