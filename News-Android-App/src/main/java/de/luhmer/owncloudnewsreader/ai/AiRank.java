package de.luhmer.owncloudnewsreader.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Stage 4 — the final ordering (veille {@code pipeline.py::_stage_rank}).
 *
 * <pre>
 *   eff  = clamp(llm + feedBump, 0, 3)
 *   rank = round(eff + 0.5 * sim, 3)
 *   llm IS NULL  =>  rank = round(sim, 3)
 * </pre>
 *
 * <h3>Why the coefficient is 0.5, and why it is enforced here</h3>
 * <b>Similarity must never cross a tier boundary; the per-feed weight bump deliberately can.</b>
 * A score-2 article must outrank every score-1 article no matter how much the taste model likes the
 * latter — otherwise a single enthusiastic centroid quietly reorders the user's entire feed and the
 * LLM's judgement stops meaning anything.
 *
 * <p>The guarantee is arithmetic: {@code sim} is a difference of two cosines and therefore lies in
 * {@code [-2, 2]} in the general case, so {@code 0.5 * sim} could reach {@code ±1} and <i>would</i>
 * cross a boundary. veille never clamps because its centroids are rarely anti-aligned; we do
 * ({@link #clampSim}), because <b>a constraint stated in a design is only real when the code
 * enforces it</b>. With {@code sim} clamped to {@code [-1, 1]} the contribution is bounded by
 * {@code ±0.5} and {@code effA > effB  =>  rank(A) >= rank(B)} holds for every input — asserted as a
 * property in {@code AiRankTest}.
 *
 * <p>The clamp applies to the <b>ranking arithmetic only</b>. The stored {@code AI_SCORE.SIM_SCORE}
 * and the {@code SIM_FLOOR} comparison use the raw value: clipping what we persist would destroy
 * information, and the floor is a threshold, not an ordering.</p>
 *
 * <p>{@code AI_SCORE.LLM_SCORE} stores the model's <b>unmodified</b> judgement — the feed bump lives
 * in {@code EFF_SCORE}. Disagreement detection has to see what the model actually said.</p>
 */
public final class AiRank {

    /** {@code selected iff EFF_SCORE >= 2}. No preference; the rubric is calibrated to it. */
    public static final int SCORE_THRESHOLD = 2;

    public static final int MIN_SCORE = 0;
    public static final int MAX_SCORE = 3;

    /** The coefficient. See the class comment before changing it. */
    public static final double SIM_WEIGHT = 0.5d;

    /** veille {@code WEIGHT_SCORE_BUMP}. Per-feed, default {@code normal}. */
    public static final String WEIGHT_LOW = "low";
    public static final String WEIGHT_NORMAL = "normal";
    public static final String WEIGHT_HIGH = "high";
    public static final String WEIGHT_BONUS = "bonus";

    private AiRank() {
        // no instances
    }

    /** {@code {low:-1, normal:0, high:+1, bonus:+2}}; anything unknown is {@code normal}. */
    public static int bumpOf(String weight) {
        if (WEIGHT_LOW.equals(weight)) {
            return -1;
        }
        if (WEIGHT_HIGH.equals(weight)) {
            return 1;
        }
        if (WEIGHT_BONUS.equals(weight)) {
            return 2;
        }
        return 0;
    }

    /**
     * @return {@code clamp(llm + feedBump, 0, 3)}, or {@code null} when the article was never judged
     */
    public static Integer eff(Integer llmScore, int feedBump) {
        if (llmScore == null) {
            return null;
        }
        int v = llmScore + feedBump;
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, v));
    }

    /** {@code [-1, 1]} — the ranking-only clamp that makes the tier guarantee real. */
    public static double clampSim(Double sim) {
        if (sim == null) {
            return 0d;
        }
        return Math.max(-1d, Math.min(1d, sim));
    }

    /**
     * @param llmScore the model's raw score, or {@code null} when it never reached the LLM
     * @param sim      the raw similarity, or {@code null} when the article was never embedded
     */
    public static double rank(Integer llmScore, int feedBump, Double sim) {
        double s = clampSim(sim);
        if (llmScore == null) {
            return round3(s);
        }
        return round3(eff(llmScore, feedBump) + SIM_WEIGHT * s);
    }

    /**
     * The machine status of a scored article. {@code null} llm means the pipeline has no verdict
     * yet — that is the prefilter's business, not this method's.
     */
    public static boolean selectable(Integer effScore) {
        return effScore != null && effScore >= SCORE_THRESHOLD;
    }

    /** Half-up to 3 decimals, matching Python's {@code round(x, 3)} closely enough for an ordering. */
    public static double round3(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return 0d;
        }
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }
}
