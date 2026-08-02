package de.luhmer.owncloudnewsreader.ai;

/**
 * {@code sim = cos(v, likedUnit) - cos(v, rejectedUnit)} — port of veille {@code embed.py
 * sim_score()}, semantics identical.
 *
 * <p><b>The two sentinels are the whole class.</b></p>
 * <ul>
 *   <li>{@code null} — this article was <b>never embedded</b>. The prefilter could not judge it, so
 *       it is not "dissimilar", it is unjudgeable, and the warm branch lets it fill leftover room by
 *       recency instead of discarding it as {@code low_similarity}.</li>
 *   <li>{@code 0.0} — this article <b>was</b> embedded and the answer is genuinely neutral: either
 *       there are no decisions to compare against (cold start) or the two cosines cancelled.</li>
 * </ul>
 * They look identical in a list and mean opposite things. {@code AI_SCORE.SIM_SCORE} preserves the
 * distinction and every consumer must too.
 *
 * <p>A <b>missing</b> centroid contributes {@code 0}, not a zero vector. With only a liked pile the
 * score is a plain cosine against it; that is deliberate and is what makes the incremental warm-up
 * continuous rather than a step change.</p>
 */
public final class AiSimilarity {

    /**
     * Keep an article iff {@code sim >= SIM_FLOOR}. Unchanged from veille at 0.05.
     *
     * <p>{@code sim} is a <i>difference</i> of cosines, so the model-specific cosine baseline
     * (EmbeddingGemma's cosines sit compressed and high; other models' do not) cancels in the
     * subtraction and the floor's meaning — "closer to the liked pile than to the rejected pile, by
     * a hair" — is model-independent. Changing it without evidence would be cargo cult.</p>
     */
    public static final double SIM_FLOOR = 0.05d;

    private AiSimilarity() {
        // no instances
    }

    /**
     * @param vec         the article's raw embedding, or {@code null} when it was never embedded
     * @param likedUnit   the unit-normalised liked centroid, or {@code null} when missing
     * @param rejectedUnit the unit-normalised rejected centroid, or {@code null} when missing
     * @return the similarity, or {@code null} iff {@code vec == null}
     */
    public static Double sim(float[] vec, float[] likedUnit, float[] rejectedUnit) {
        if (vec == null) {
            return null;                       // NULL = never embedded
        }
        float[] u = AiVec.unit(vec);
        if (u == null) {
            return 0.0d;                       // embedded, but the vector has no direction
        }
        double s = 0d;
        if (likedUnit != null) {
            s += AiVec.dot(u, likedUnit);
        }
        if (rejectedUnit != null) {
            s -= AiVec.dot(u, rejectedUnit);
        }
        return s;
    }

    /** {@code sim >= SIM_FLOOR}. A null similarity is <b>not</b> above the floor and not below it. */
    public static boolean aboveFloor(Double sim) {
        return sim != null && sim >= SIM_FLOOR;
    }
}
