package de.luhmer.owncloudnewsreader.ai;

/**
 * The six lines of vector arithmetic the taste model needs. Port of veille {@code embed.py}.
 *
 * <p><b>Why not {@code TextEmbedder.cosineSimilarity}.</b> It exists and is {@code public static},
 * and it is the wrong tool twice over: it throws {@code IllegalArgumentException} on a zero L2 norm
 * (a legal, information-bearing state here), and it takes {@code Embedding} objects, which would
 * force us to wrap a centroid {@code float[]} in a synthetic MediaPipe type — dragging a runtime
 * dependency into {@code src/main} and breaking the {@code mlNone} flavor (PLAN D12).</p>
 *
 * <p>{@link #unit(float[])} returns {@code null} on a zero-norm vector, exactly like veille's
 * {@code _unit()}. That null is not an error: it means "this vector carries no direction", and the
 * callers treat it as a missing contribution rather than as a zero contribution.</p>
 */
public final class AiVec {

    private AiVec() {
        // no instances
    }

    /** @return a new unit-length copy, or {@code null} for a null / empty / zero-norm input */
    public static float[] unit(float[] v) {
        if (v == null || v.length == 0) {
            return null;
        }
        double sumSq = 0d;
        for (float f : v) {
            sumSq += (double) f * (double) f;
        }
        double norm = Math.sqrt(sumSq);
        if (!(norm > 0d) || Double.isNaN(norm) || Double.isInfinite(norm)) {
            return null;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / norm);
        }
        return out;
    }

    /**
     * Dot product. {@code 0.0} when either side is null or the dimensions disagree — a dimension
     * mismatch means the two vectors came from different models, and
     * {@code AiEmbeddingStore.assertCompatible()} is what is supposed to have prevented it; here we
     * refuse to guess rather than throw inside a sync.
     */
    public static double dot(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0d;
        }
        double s = 0d;
        for (int i = 0; i < a.length; i++) {
            s += (double) a[i] * (double) b[i];
        }
        return s;
    }

    /** {@code target += v * sign}, in place. The centroid accumulator's only mutation. */
    public static void addInPlace(float[] target, float[] v, int sign) {
        if (target == null || v == null || target.length != v.length) {
            return;
        }
        for (int i = 0; i < target.length; i++) {
            target[i] = target[i] + sign * v[i];
        }
    }

    /** @return a new array holding {@code v / n}, or {@code null} when {@code n <= 0} */
    public static float[] scale(float[] v, double divisor) {
        if (v == null || divisor == 0d) {
            return null;
        }
        float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] / divisor);
        }
        return out;
    }

    public static float[] zeros(int dim) {
        return new float[dim];
    }

    public static float[] copy(float[] v) {
        if (v == null) {
            return null;
        }
        float[] out = new float[v.length];
        System.arraycopy(v, 0, out, 0, v.length);
        return out;
    }
}
