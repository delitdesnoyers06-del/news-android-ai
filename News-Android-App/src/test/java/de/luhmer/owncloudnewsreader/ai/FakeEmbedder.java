package de.luhmer.owncloudnewsreader.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import de.luhmer.owncloudnewsreader.ai.engine.AiEmbedder;
import de.luhmer.owncloudnewsreader.ai.engine.AiException;

/**
 * Deterministic stand-in for {@link AiEmbedder}.
 *
 * <p>Its existence is the point of the design: {@code AiEmbedder} lives in {@code src/main} and
 * mentions no MediaPipe type, so the centroids, the similarity, the prefilter and the rank formula
 * are all testable on a plain JVM with no device and no model.</p>
 *
 * <p>Vectors are seeded from the title's hash, so the same article always gets the same vector, and
 * {@link #pin(String, float[])} lets a test place an article exactly where it wants it.</p>
 */
public class FakeEmbedder implements AiEmbedder {

    public static final String MODEL = "fake-embedder";
    public static final String TASK = "CLUSTERING";

    private final int dim;
    private final Map<String, float[]> pinned = new HashMap<>();
    /** Titles that must fail, to exercise the never-lose-an-article path. */
    private final Map<String, AiException.Kind> failures = new HashMap<>();

    public int embedCalls;
    public boolean closed;
    /** Last text the embedder actually saw, after clipping. */
    public String lastInput;

    public FakeEmbedder() {
        this(8);
    }

    public FakeEmbedder(int dim) {
        this.dim = dim;
    }

    /** Forces the vector for one title. */
    public FakeEmbedder pin(String title, float[] vec) {
        pinned.put(title, vec);
        return this;
    }

    /** Makes one title throw. */
    public FakeEmbedder fail(String title, AiException.Kind kind) {
        failures.put(title, kind);
        return this;
    }

    @Override
    public float[] embed(String title, String body) throws AiException {
        embedCalls++;
        lastInput = AiText.embedInput(title, body);
        AiException.Kind kind = failures.get(title);
        if (kind != null) {
            throw new AiException(kind, "fake failure for " + title);
        }
        if (pinned.containsKey(title)) {
            return AiVec.unit(pinned.get(title));
        }
        if (lastInput.isEmpty()) {
            return null;
        }
        Random rnd = new Random(lastInput.hashCode());
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) {
            v[i] = (float) rnd.nextGaussian();
        }
        return AiVec.unit(v);
    }

    @Override
    public int dim() {
        return dim;
    }

    @Override
    public String modelId() {
        return MODEL;
    }

    @Override
    public String task() {
        return TASK;
    }

    @Override
    public void close() {
        closed = true;
    }
}
