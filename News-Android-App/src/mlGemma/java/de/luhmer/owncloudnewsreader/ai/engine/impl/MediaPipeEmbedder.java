package de.luhmer.owncloudnewsreader.ai.engine.impl;

import android.content.Context;
import android.util.Log;

import com.google.mediapipe.tasks.components.containers.Embedding;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder;

import java.io.File;
import java.util.List;

import de.luhmer.owncloudnewsreader.ai.AiText;
import de.luhmer.owncloudnewsreader.ai.AiVec;
import de.luhmer.owncloudnewsreader.ai.engine.AiEmbedder;
import de.luhmer.owncloudnewsreader.ai.engine.AiException;

/**
 * EmbeddingGemma through MediaPipe Tasks Text. The only class in the app that names
 * {@code com.google.mediapipe.*} for embedding purposes.
 *
 * <p><b>Four non-negotiables (PLAN D12), each of which fails silently if broken:</b></p>
 * <ol>
 *   <li><b>The two-argument {@code embed(text, CTX)} only.</b> The one-argument overload applies no
 *       task prefix; its vectors land in a different region of the space and cosine against a
 *       prefixed centroid is noise, with no exception and no log line. The {@link AiEmbedder}
 *       interface does not expose that shape and neither does this class.</li>
 *   <li><b>One {@code TextFormatContext}, built once, reused</b> for every article and every
 *       centroid member. Two contexts with different settings would silently split the space in the
 *       same way.</li>
 *   <li><b>{@code CLUSTERING} is the load-bearing half; {@code TextRole.QUERY} is inert here.</b>
 *       The operation is "is this article in the same neighbourhood as my liked pile" — group
 *       membership, not sentence-pair similarity, and not an asymmetric query/document retrieval
 *       split, so {@code CLUSTERING} is the right task type. The role is <b>not</b> load-bearing:
 *       {@code getGeckoEmbeddingText} only consults it for the QA / fact-check / code-retrieval
 *       task types, so for {@code CLUSTERING} the emitted prefix is always
 *       {@code "task: clustering | query: <text>"} whatever the role says. We still set it because
 *       {@code TextFormatContext} is an AutoValue and {@code build()} throws
 *       {@code IllegalStateException} on a missing required property — not because it changes the
 *       vector. Do not "improve" the embedding by changing the role; change the task type.</li>
 *   <li><b>Open question (spike S4, needs a device).</b> Whether the shipped
 *       {@code embedding_gemma.task} graph actually wants this Gecko-style
 *       {@code "task: … | query: …"} prefix at all is unverified. If the graph was exported with a
 *       different (or no) prompt template, the prefix is dead weight at best and a systematic
 *       offset in the embedding space at worst — and it degrades <i>silently</i>: no exception, no
 *       log line, just worse ranking. Nothing on the JVM can settle this; it needs the real model
 *       on real hardware.</li>
 *   <li><b>Text clipped to 1400 chars.</b> The shipped {@code embedding_gemma.task} graph has a
 *       static {@code [1,512]} INT32 input; longer text overflows the tokenizer and is truncated at
 *       an undefined boundary.</li>
 * </ol>
 *
 * <p>{@code setL2Normalize(true)} so article vectors arrive unit-length, {@code setQuantize(false)}
 * because a centroid is an average of {@code float[]} and cannot be built from bytes.</p>
 */
final class MediaPipeEmbedder implements AiEmbedder {

    private static final String TAG = "MediaPipeEmbedder";

    static final String MODEL_ID = "embedding_gemma_int4int8";
    static final String TASK = "CLUSTERING";
    static final int DIM = 768;

    /**
     * Built ONCE and reused. See non-negotiable 2 on the class comment before touching this field.
     *
     * <p>{@code setRole(QUERY)} is required by the AutoValue builder but has <b>no effect</b> for
     * {@code CLUSTERING} — see non-negotiable 3. It is set so {@code build()} does not throw, and
     * for no other reason.
     */
    private static final TextEmbedder.TextFormatContext CTX =
            TextEmbedder.TextFormatContext.builder()
                    .setTaskType(TextEmbedder.EmbeddingType.CLUSTERING)
                    .setRole(TextEmbedder.TextRole.QUERY)
                    .build();

    private final TextEmbedder embedder;
    private boolean closed;

    private MediaPipeEmbedder(TextEmbedder embedder) {
        this.embedder = embedder;
    }

    static MediaPipeEmbedder open(Context context, File taskFile) throws AiException {
        try {
            BaseOptions base = BaseOptions.builder()
                    .setModelAssetPath(taskFile.getAbsolutePath())   // absolute path, not an asset
                    .setDelegate(Delegate.CPU)
                    .build();
            TextEmbedder.TextEmbedderOptions opts = TextEmbedder.TextEmbedderOptions.builder()
                    .setBaseOptions(base)
                    .setL2Normalize(true)
                    .setQuantize(false)
                    .build();
            return new MediaPipeEmbedder(TextEmbedder.createFromOptions(context, opts));
        } catch (OutOfMemoryError e) {
            throw new AiException(AiException.Kind.OUT_OF_MEMORY, "embedder load OOM", e);
        } catch (Throwable t) {
            throw new AiException(AiException.Kind.LOAD_FAILED,
                    "could not open " + taskFile.getAbsolutePath(), t);
        }
    }

    @Override
    public float[] embed(String title, String body) throws AiException {
        if (closed) {
            throw new AiException(AiException.Kind.CANCELLED, "embedder already closed");
        }
        String text = AiText.embedInput(title, body);
        if (text.isEmpty()) {
            return null;    // nothing to embed is not an error; the article stays SIM_SCORE NULL
        }
        try {
            List<Embedding> embeddings =
                    embedder.embed(text, CTX).embeddingResult().embeddings();
            if (embeddings.isEmpty()) {
                return null;
            }
            // Unit-normalise again even though setL2Normalize(true) already did: it is six
            // multiplications, and AiVec.unit() is the single definition of "zero norm means null"
            // that the centroid maths relies on.
            return AiVec.unit(embeddings.get(0).floatEmbedding());
        } catch (OutOfMemoryError e) {
            throw new AiException(AiException.Kind.OUT_OF_MEMORY, "embed OOM", e);
        } catch (Throwable t) {
            throw new AiException(AiException.Kind.RUNTIME, "embed failed", t);
        }
    }

    @Override
    public int dim() {
        return DIM;
    }

    @Override
    public String modelId() {
        return MODEL_ID;
    }

    @Override
    public String task() {
        return TASK;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            embedder.close();
        } catch (Throwable t) {
            Log.w(TAG, "embedder close failed", t);
        }
    }
}
