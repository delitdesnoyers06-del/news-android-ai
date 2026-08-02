package de.luhmer.owncloudnewsreader.ai.engine;

/**
 * A loaded generative model. Obtained only from {@code AiEngineManager.withLlm()}.
 *
 * <p>Like {@link AiEmbedder} this lives in {@code src/main} and names <b>no LiteRT-LM type</b>, which
 * is what lets the whole scoring half — prompt building, the parser, the repair ladder, the terminal
 * "never lose an article" guarantee — be tested on a plain JVM against {@code FakeLlm}, and what lets
 * {@code mlNone} compile the same call sites.</p>
 */
public interface AiLlm extends java.io.Closeable {

    AiModelInfo model();

    /**
     * True iff this model's tokenizer is SentencePiece, i.e. iff LLGuidance will accept a grammar.
     * <b>Never a feature gate</b> — false only means the repair ladder does more work.
     */
    boolean supportsConstrainedDecoding();

    /** Opens one conversation. The caller MUST close it. */
    AiConversation start(AiPromptSpec spec) throws AiException;

    @Override
    void close();
}
