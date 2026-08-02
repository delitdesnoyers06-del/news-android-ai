package de.luhmer.owncloudnewsreader.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Random;

/**
 * {@code eff = clamp(llm + bump, 0, 3)} and {@code rank = round(eff + 0.5*sim, 3)}, plus the one
 * property the coefficient exists for.
 */
public class AiRankTest {

    private static final double EPS = 1e-9d;

    @Test
    public void effIsClampedToTheScoreRange() {
        assertEquals(Integer.valueOf(0), AiRank.eff(0, -1));
        assertEquals(Integer.valueOf(0), AiRank.eff(1, -1));
        assertEquals(Integer.valueOf(3), AiRank.eff(3, 2));
        assertEquals(Integer.valueOf(3), AiRank.eff(2, 2));
        assertEquals(Integer.valueOf(2), AiRank.eff(2, 0));
    }

    @Test
    public void aNeverJudgedArticleHasNoEffectiveScore() {
        assertNull(AiRank.eff(null, 2));
    }

    @Test
    public void theFourWeightsAreTheVeilleOnes() {
        assertEquals(-1, AiRank.bumpOf(AiRank.WEIGHT_LOW));
        assertEquals(0, AiRank.bumpOf(AiRank.WEIGHT_NORMAL));
        assertEquals(1, AiRank.bumpOf(AiRank.WEIGHT_HIGH));
        assertEquals(2, AiRank.bumpOf(AiRank.WEIGHT_BONUS));
        assertEquals("an unknown weight must behave as normal", 0, AiRank.bumpOf("wat"));
        assertEquals(0, AiRank.bumpOf(null));
    }

    @Test
    public void withoutAnLlmScoreTheRankIsTheSimilarity() {
        assertEquals(0.424d, AiRank.rank(null, 0, 0.4239d), EPS);
        assertEquals(-0.5d, AiRank.rank(null, 2, -0.5d), EPS);
        assertEquals("a feed bump cannot rank an unjudged article",
                0.3d, AiRank.rank(null, 2, 0.3d), EPS);
    }

    @Test
    public void withoutASimilarityTheRankIsTheEffectiveScore() {
        assertEquals(2.0d, AiRank.rank(2, 0, null), EPS);
        assertEquals(3.0d, AiRank.rank(2, 1, null), EPS);
        assertEquals(0.0d, AiRank.rank(null, 0, null), EPS);
    }

    @Test
    public void theFormulaIsEffPlusHalfSimRoundedToThree() {
        assertEquals(2.25d, AiRank.rank(2, 0, 0.5d), EPS);
        assertEquals(1.75d, AiRank.rank(2, 0, -0.5d), EPS);
        assertEquals(3.0d, AiRank.rank(3, 0, 0.0d), EPS);
        // 2 + 0.5*0.1235 = 2.06175 -> 2.062
        assertEquals(2.062d, AiRank.rank(2, 0, 0.1235d), EPS);
    }

    // ------------------------------------------------------------------ the property

    /**
     * <b>Similarity must never cross a tier boundary.</b> For every pair of articles whose effective
     * scores differ, the higher-scored one ranks at least as high, whatever the taste model says.
     * This is the reason the coefficient is 0.5 and the reason
     * {@link AiRank#clampSim(Double)} exists — {@code sim} is a difference of two cosines and can
     * legally reach ±2, at which point an unclamped 0.5 would move an article a whole tier.
     */
    @Test
    public void similarityNeverCrossesATierBoundary() {
        Random rnd = new Random(20260801L);
        for (int i = 0; i < 20000; i++) {
            int llmHigh = 1 + rnd.nextInt(3);          // 1..3
            int llmLow = rnd.nextInt(llmHigh);         // strictly lower
            // deliberately sample outside [-1,1] so the clamp is what is under test
            double simHigh = -2.5d + rnd.nextDouble() * 5d;
            double simLow = -2.5d + rnd.nextDouble() * 5d;

            double high = AiRank.rank(llmHigh, 0, simHigh);
            double low = AiRank.rank(llmLow, 0, simLow);
            assertTrue("eff " + llmHigh + " (sim " + simHigh + ") ranked below eff " + llmLow
                            + " (sim " + simLow + "): " + high + " < " + low,
                    high >= low);
        }
    }

    /**
     * The other half of the same decision: the per-feed weight <b>is</b> allowed to move an article
     * across a tier. That is the point of the setting.
     */
    @Test
    public void theFeedWeightCanCrossATierBoundary() {
        double boosted = AiRank.rank(1, AiRank.bumpOf(AiRank.WEIGHT_BONUS), 0.0d);
        double plain = AiRank.rank(2, AiRank.bumpOf(AiRank.WEIGHT_NORMAL), 0.9d);
        assertTrue("a bonus feed must be able to outrank a higher raw score",
                boosted > plain);

        double demoted = AiRank.rank(2, AiRank.bumpOf(AiRank.WEIGHT_LOW), 0.9d);
        assertTrue("a low-weight feed must be able to fall below a lower raw score",
                demoted < AiRank.rank(2, 0, -0.9d));
    }

    @Test
    public void theSelectionThresholdIsTwo() {
        assertEquals(2, AiRank.SCORE_THRESHOLD);
        assertFalse(AiRank.selectable(null));
        assertFalse(AiRank.selectable(1));
        assertTrue(AiRank.selectable(2));
        assertTrue(AiRank.selectable(3));
    }

    @Test
    public void roundingIsHalfUpAndSurvivesNonFiniteInput() {
        assertEquals(0.125d, AiRank.round3(0.1245d), EPS);
        assertEquals(0.0d, AiRank.round3(Double.NaN), EPS);
        assertEquals(0.0d, AiRank.round3(Double.POSITIVE_INFINITY), EPS);
    }

    @Test
    public void theRankingClampDoesNotTouchTheStoredSimilarity() {
        // clampSim is the ranking-only guard; nothing here should be read as licence to clamp
        // AI_SCORE.SIM_SCORE, which must keep the raw value and its sentinels.
        assertEquals(1.0d, AiRank.clampSim(1.7d), EPS);
        assertEquals(-1.0d, AiRank.clampSim(-1.7d), EPS);
        assertEquals(0.3d, AiRank.clampSim(0.3d), EPS);
        assertEquals(0.0d, AiRank.clampSim(null), EPS);
    }
}
