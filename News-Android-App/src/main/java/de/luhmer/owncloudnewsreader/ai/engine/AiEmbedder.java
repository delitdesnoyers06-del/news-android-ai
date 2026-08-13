package de.luhmer.owncloudnewsreader.ai.engine;

import java.io.Closeable;

/**
 * A loaded sentence embedder. Obtained only from {@code AiEngineManager.withEmbedder()}.
 *
 * <p>This interface lives in {@code src/main} and mentions <b>no MediaPipe type</b>, which is what
 * lets the whole taste model — centroids, similarity, prefilter, rank — be unit-tested on the JVM
 * against a fake, and what lets the {@code mlNone} flavor compile with the same call sites.</p>
 *
 * <p><b>The single-argument {@code embed(String)} does not exist here, deliberately</b> (PLAN D12).
 * MediaPipe's one-argument overload applies <i>no task prefix</i>. Prefixed and unprefixed vectors
 * live in different regions of the space, cosine against the centroid becomes meaningless, and there
 * is <b>no error and no symptom</b> — only a taste model that never converges. The interface not
 * exposing the shape is the enforcement; a code-review rule alone would not be.</p>
 */
public interface AiEmbedder extends Closeable {

    /** 768 for EmbeddingGemma. Persisted as {@code AI_EMBEDDING.DIM}. */
    int dim();

    /** Persisted as {@code AI_EMBEDDING.MODEL}; half of the compatibility key. */
    String modelId();

    /** Persisted as {@code AI_EMBEDDING.TASK}; the other half. {@code "CLUSTERING"}. */
    String task();

    /**
     * Embeds one article.
     *
     * <p>Implementations join and clip the two halves themselves ({@code AiText.embedInput}), so
     * callers must not pre-concatenate — the 1400-character budget is per article, not per field.</p>
     *
     * @return a <b>unit-normalised</b> {@code float[dim()]}, or {@code null} when the vector had a
     *         zero L2 norm. Null is a legal, information-bearing result (veille {@code embed.py::_unit}),
     *         not an error: the caller stores no row and the article stays {@code SIM_SCORE IS NULL}.
     */
    float[] embed(String title, String body) throws AiException;

    /** Releases native resources. Idempotent. Never throws. */
    @Override
    void close();
}
