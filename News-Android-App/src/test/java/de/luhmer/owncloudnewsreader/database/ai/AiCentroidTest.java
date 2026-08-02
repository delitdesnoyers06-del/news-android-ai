package de.luhmer.owncloudnewsreader.database.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Random;

import de.luhmer.owncloudnewsreader.ai.AiVec;

/**
 * The centroid arithmetic, and the one property that makes the incremental design safe:
 * <b>however you get there, the incremental state must equal a full rebuild.</b>
 */
@RunWith(RobolectricTestRunner.class)
public class AiCentroidTest extends AiDbTestBase {

    private static final String MODEL = "embedding_gemma_int4int8";
    private static final String TASK = "CLUSTERING";
    private static final int DIM = 16;

    private AiCentroidStore centroids;
    private AiEmbeddingStore embeddings;
    private AiDecisionStore decisions;

    @Before
    public void openStores() {
        centroids = new AiCentroidStore(db);
        embeddings = new AiEmbeddingStore(db);
        decisions = new AiDecisionStore(db);
    }

    // ------------------------------------------------------------------ basics

    @Test
    public void aClassWithNoMembersIsMissingNotZero() {
        assertNull("a missing centroid must be null, never an all-zero vector",
                centroids.unit(AiCentroidStore.CLASS_KEPT));
        assertEquals(0, centroids.memberCount(AiCentroidStore.CLASS_KEPT));
    }

    @Test
    public void oneDecisionMakesTheCentroidThatArticlesDirection() {
        put("a", unitAlong(0));
        decide("a", AiDecisionStore.ACTION_KEEP);

        float[] u = centroids.unit(AiCentroidStore.CLASS_KEPT);
        assertNotNull(u);
        assertEquals(1.0f, u[0], 1e-5f);
        assertEquals(1, centroids.memberCount(AiCentroidStore.CLASS_KEPT));
    }

    @Test
    public void theLastMemberLeavingDeletesTheCentroidRatherThanZeroingIt() {
        put("a", unitAlong(0));
        decide("a", AiDecisionStore.ACTION_KEEP);
        decide("a", AiDecisionStore.ACTION_UNDO);

        assertNull("N reaching 0 must be a MISSING centroid, not a zero vector",
                centroids.get(AiCentroidStore.CLASS_KEPT));
        assertNull(centroids.unit(AiCentroidStore.CLASS_KEPT));
    }

    @Test
    public void flippingKeepToRejectMovesTheVectorBetweenClasses() {
        put("a", unitAlong(0));
        put("b", unitAlong(1));
        decide("a", AiDecisionStore.ACTION_KEEP);
        decide("b", AiDecisionStore.ACTION_KEEP);
        decide("a", AiDecisionStore.ACTION_REJECT);

        assertEquals(1, centroids.memberCount(AiCentroidStore.CLASS_KEPT));
        assertEquals(1, centroids.memberCount(AiCentroidStore.CLASS_REJECTED));
        float[] kept = centroids.unit(AiCentroidStore.CLASS_KEPT);
        assertEquals("only b is left in kept", 1.0f, kept[1], 1e-5f);
    }

    @Test
    public void aDecisionOnANeverEmbeddedArticleStillCountsButDoesNotMoveTheCentroid() {
        decide("no-vector", AiDecisionStore.ACTION_KEEP);

        assertEquals("the warm-up gate counts every decision", 1, decisions.decisionCount());
        assertNull(centroids.get(AiCentroidStore.CLASS_KEPT));
        assertEquals("the taste row must stay pending so backfill picks it up later",
                1, decisions.pendingCentroidKeys().size());
    }

    @Test
    public void backfillFoldsInAVectorThatArrivedAfterTheDecision() {
        decide("late", AiDecisionStore.ACTION_KEEP);
        assertNull(centroids.get(AiCentroidStore.CLASS_KEPT));

        put("late", unitAlong(3));
        assertEquals(1, centroids.backfill());

        assertEquals(1, centroids.memberCount(AiCentroidStore.CLASS_KEPT));
        assertTrue(decisions.pendingCentroidKeys().isEmpty());
    }

    @Test
    public void backfillIsIdempotent() {
        put("a", unitAlong(0));
        decide("a", AiDecisionStore.ACTION_KEEP);

        assertEquals("onDecision already folded it", 0, centroids.backfill());
        assertEquals(0, centroids.backfill());
        assertEquals(1, centroids.memberCount(AiCentroidStore.CLASS_KEPT));
    }

    @Test
    public void aDecisionInvalidatesTheCachedUnit() {
        put("a", unitAlong(0));
        decide("a", AiDecisionStore.ACTION_KEEP);
        centroids.unit(AiCentroidStore.CLASS_KEPT);          // caches it
        assertNotNull(centroids.get(AiCentroidStore.CLASS_KEPT).unit);

        put("b", unitAlong(1));
        decide("b", AiDecisionStore.ACTION_KEEP);
        assertNull("a new decision must drop every cached UNIT",
                centroids.get(AiCentroidStore.CLASS_KEPT).unit);
    }

    @Test
    public void theCacheKeyCoversDecisionCountModelAndTask() {
        assertTrue(centroids.refreshCacheKey(MODEL, TASK, 0));
        assertFalse("same key, no invalidation", centroids.refreshCacheKey(MODEL, TASK, 0));
        assertTrue("one more decision is a new key", centroids.refreshCacheKey(MODEL, TASK, 1));
        assertTrue("another model is a new key", centroids.refreshCacheKey("other", TASK, 1));
        assertTrue("another task is a new key", centroids.refreshCacheKey("other", "STS", 1));
    }

    // ------------------------------------------------------------------ THE property

    /**
     * <b>Incremental == rebuild.</b> A random sequence of keeps, rejects and undos over a random
     * article set must leave the running sums in exactly the state a full recomputation from
     * {@code AI_TASTE ⋈ AI_EMBEDDING} would produce.
     *
     * <p>Compared with a tolerance rather than bit-for-bit: float32 addition is not associative, so
     * a value added-then-subtracted does not return to its exact original. The documented drift over
     * ~10^3 additions is ~1e-4 relative and irrelevant to a cosine — but a <b>logic</b> error (a
     * missed decrement, a double fold) moves the result by orders of magnitude more than that, which
     * is exactly what this catches.</p>
     */
    @Test
    public void incrementalStateEqualsAFullRebuild() {
        for (int seed = 0; seed < 12; seed++) {
            resetTables();
            Random rnd = new Random(seed);

            final int articles = 15;
            for (int i = 0; i < articles; i++) {
                put("k" + i, randomVector(rnd));
            }
            String[] actions = {AiDecisionStore.ACTION_KEEP, AiDecisionStore.ACTION_REJECT,
                    AiDecisionStore.ACTION_UNDO};
            for (int step = 0; step < 60; step++) {
                decide("k" + rnd.nextInt(articles), actions[rnd.nextInt(actions.length)]);
            }

            AiCentroidStore.Row keptBefore = centroids.get(AiCentroidStore.CLASS_KEPT);
            AiCentroidStore.Row rejBefore = centroids.get(AiCentroidStore.CLASS_REJECTED);
            assertTrue("seed " + seed + ": member counts drifted", centroids.consistent());

            centroids.rebuild();

            assertSameCentroid("seed " + seed + " kept",
                    keptBefore, centroids.get(AiCentroidStore.CLASS_KEPT));
            assertSameCentroid("seed " + seed + " rejected",
                    rejBefore, centroids.get(AiCentroidStore.CLASS_REJECTED));
        }
    }

    @Test
    public void rebuildRepairsAHalfAppliedFold() {
        put("a", unitAlong(0));
        put("b", unitAlong(1));
        decide("a", AiDecisionStore.ACTION_KEEP);
        decide("b", AiDecisionStore.ACTION_KEEP);

        // Corrupt the accumulator the way a crash between the two writes would.
        centroids.put(AiCentroidStore.CLASS_KEPT, MODEL, TASK, unitAlong(0), 1);
        assertFalse(centroids.consistent());

        assertEquals(2, centroids.rebuild());
        assertTrue(centroids.consistent());
        assertEquals(2, centroids.memberCount(AiCentroidStore.CLASS_KEPT));
    }

    @Test
    public void rebuildIgnoresDecidedArticlesWithNoVector() {
        put("has-vector", unitAlong(0));
        decide("has-vector", AiDecisionStore.ACTION_KEEP);
        decide("no-vector", AiDecisionStore.ACTION_KEEP);

        assertEquals(1, centroids.rebuild());
        assertEquals(1, centroids.memberCount(AiCentroidStore.CLASS_KEPT));
        assertEquals("the vector-less decision stays pending, forever and harmlessly",
                1, decisions.pendingCentroidKeys().size());
    }

    // ------------------------------------------------------------------ helpers

    private void assertSameCentroid(String what, AiCentroidStore.Row expected,
                                    AiCentroidStore.Row actual) {
        if (expected == null || expected.n == 0) {
            assertTrue(what + ": rebuild produced a centroid where there was none",
                    actual == null || actual.n == 0);
            return;
        }
        assertNotNull(what + ": rebuild lost the centroid", actual);
        assertEquals(what + ": member count", expected.n, actual.n);
        for (int i = 0; i < expected.sum.length; i++) {
            assertEquals(what + ": component " + i, expected.sum[i], actual.sum[i], 1e-3f);
        }
        float[] a = AiVec.unit(AiVec.scale(expected.sum, expected.n));
        float[] b = AiVec.unit(AiVec.scale(actual.sum, actual.n));
        if (a != null && b != null) {
            assertEquals(what + ": the two unit means must be the same direction",
                    1.0d, AiVec.dot(a, b), 1e-4d);
        }
    }

    private void put(String aiKey, float[] vec) {
        embeddings.put(aiKey, MODEL, TASK, vec);
    }

    /** Goes through the same path the UI does: decide, then fold. */
    private void decide(String aiKey, String action) {
        AiDecisionStore.Result r = decisions.decide(aiKey, action, AiDecisionStore.SOURCE_SWIPE,
                null, aiKey);
        if (r.changed) {
            centroids.onDecision(aiKey, r.from, r.state);
        }
    }

    private void resetTables() {
        for (String t : new String[]{AiSchema.T_CENTROID, AiSchema.T_TASTE, AiSchema.T_DECISION,
                AiSchema.T_EMBEDDING, AiSchema.T_SCORE, AiSchema.T_META}) {
            sqlite.execSQL("DELETE FROM " + t);
        }
        AiSchema.createOrMigrate(sqlite);
    }

    private static float[] unitAlong(int axis) {
        float[] v = new float[DIM];
        v[axis] = 1f;
        return v;
    }

    private static float[] randomVector(Random rnd) {
        float[] v = new float[DIM];
        for (int i = 0; i < DIM; i++) {
            v[i] = (float) rnd.nextGaussian();
        }
        return AiVec.unit(v);
    }
}
