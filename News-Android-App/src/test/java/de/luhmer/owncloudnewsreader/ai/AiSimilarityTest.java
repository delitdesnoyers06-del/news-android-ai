package de.luhmer.owncloudnewsreader.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The two sentinels — {@code null} (never embedded) and {@code 0.0} (embedded, nothing to compare
 * against) — and the missing-centroid rule. Pure JVM, no Robolectric.
 */
public class AiSimilarityTest {

    private static final float[] E1 = {1f, 0f, 0f};
    private static final float[] E2 = {0f, 1f, 0f};
    private static final double EPS = 1e-6d;

    @Test
    public void aNeverEmbeddedArticleIsNullAndNotZero() {
        Double sim = AiSimilarity.sim(null, E1, E2);
        assertNull("an unembedded article must be NULL, not 0.0 - they mean opposite things", sim);
    }

    @Test
    public void anEmbeddedArticleWithNoCentroidsIsZeroAndNotNull() {
        Double sim = AiSimilarity.sim(E1, null, null);
        assertNotNull("cold start must produce 0.0, not NULL", sim);
        assertEquals(0.0d, sim, EPS);
    }

    @Test
    public void aMissingCentroidContributesZeroNotAZeroVector() {
        // With only a liked centroid the score is the plain cosine against it. If "missing" were
        // implemented as a zero vector the result would be identical here - but the rejected side
        // would then also silently subtract nothing forever after it appears. Assert the shape.
        Double onlyLiked = AiSimilarity.sim(E1, E1, null);
        assertEquals(1.0d, onlyLiked, EPS);

        Double onlyRejected = AiSimilarity.sim(E1, null, E1);
        assertEquals(-1.0d, onlyRejected, EPS);
    }

    @Test
    public void similarityIsTheDifferenceOfTheTwoCosines() {
        Double both = AiSimilarity.sim(E1, E1, E2);
        assertEquals(1.0d - 0.0d, both, EPS);

        Double opposed = AiSimilarity.sim(E1, E2, E1);
        assertEquals(0.0d - 1.0d, opposed, EPS);
    }

    @Test
    public void aZeroNormVectorIsEmbeddedButDirectionless() {
        Double sim = AiSimilarity.sim(new float[]{0f, 0f, 0f}, E1, E2);
        assertNotNull("a zero-norm vector was still embedded - it is 0.0, not NULL", sim);
        assertEquals(0.0d, sim, EPS);
    }

    @Test
    public void theInputVectorNeedNotArriveUnitLength() {
        Double scaled = AiSimilarity.sim(new float[]{5f, 0f, 0f}, E1, null);
        assertEquals(1.0d, scaled, EPS);
    }

    @Test
    public void nullIsNeitherAboveNorBelowTheFloor() {
        assertFalse(AiSimilarity.aboveFloor(null));
        assertFalse(AiSimilarity.aboveFloor(AiSimilarity.SIM_FLOOR - 1e-9));
        assertTrue(AiSimilarity.aboveFloor(AiSimilarity.SIM_FLOOR));
        assertTrue(AiSimilarity.aboveFloor(1.0d));
    }

    @Test
    public void theFloorIsStillVeillesValue() {
        assertEquals(0.05d, AiSimilarity.SIM_FLOOR, 0d);
    }

    @Test
    public void mismatchedDimensionsContributeZeroRatherThanThrowing() {
        Double sim = AiSimilarity.sim(E1, new float[]{1f, 0f}, null);
        assertEquals(0.0d, sim, EPS);
    }
}
