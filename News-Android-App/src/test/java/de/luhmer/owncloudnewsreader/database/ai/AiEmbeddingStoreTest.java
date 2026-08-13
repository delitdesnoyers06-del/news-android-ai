package de.luhmer.owncloudnewsreader.database.ai;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * The {@code AI_EMBEDDING} blob layout is a cross-language contract with veille's {@code embed.py}
 * ({@code struct.pack('<%df')}), so the byte layout is asserted explicitly rather than only
 * round-tripped. {@link java.nio.ByteBuffer} defaults to BIG_ENDIAN; if someone ever drops the
 * {@code order(LITTLE_ENDIAN)} call every round-trip test still passes and only these byte
 * assertions fail.
 */
@RunWith(RobolectricTestRunner.class)
public class AiEmbeddingStoreTest extends AiDbTestBase {

    private AiEmbeddingStore store;

    @Before
    public void setUpStore() {
        store = new AiEmbeddingStore(db);
    }

    // ------------------------------------------------------------------ byte layout

    @Test
    public void oneIsPackedLittleEndian() {
        // IEEE-754 1.0f == 0x3F800000; little-endian on the wire == 00 00 80 3F
        assertArrayEquals(new byte[]{0x00, 0x00, (byte) 0x80, 0x3F},
                AiEmbeddingStore.pack(new float[]{1.0f}));
    }

    @Test
    public void aKnownVectorHasAKnownByteString() {
        // 1.0f, -2.0f, 0.5f  ->  0x3F800000, 0xC0000000, 0x3F000000, each reversed
        byte[] expected = {
                0x00, 0x00, (byte) 0x80, 0x3F,
                0x00, 0x00, 0x00, (byte) 0xC0,
                0x00, 0x00, 0x00, 0x3F,
        };
        assertArrayEquals(expected, AiEmbeddingStore.pack(new float[]{1.0f, -2.0f, 0.5f}));
    }

    @Test
    public void zeroPacksToFourZeroBytes() {
        assertArrayEquals(new byte[]{0, 0, 0, 0}, AiEmbeddingStore.pack(new float[]{0.0f}));
    }

    @Test
    public void packedLengthIsFourBytesPerDimension() {
        assertEquals(768 * 4, AiEmbeddingStore.pack(new float[768]).length);
    }

    @Test
    public void unpackReadsLittleEndian() {
        float[] out = AiEmbeddingStore.unpack(new byte[]{0x00, 0x00, (byte) 0x80, 0x3F});
        assertArrayEquals(new float[]{1.0f}, out, 0f);
    }

    @Test
    public void packUnpackRoundTrips() {
        float[] v = new float[768];
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) Math.sin(i) * (i % 7 == 0 ? -1f : 1f);
        }
        assertArrayEquals(v, AiEmbeddingStore.unpack(AiEmbeddingStore.pack(v)), 0f);
    }

    @Test
    public void extremesRoundTripExactly() {
        float[] v = {Float.MIN_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, 0f, -0f, 1e-30f};
        assertArrayEquals(v, AiEmbeddingStore.unpack(AiEmbeddingStore.pack(v)), 0f);
    }

    @Test
    public void nullsPassThrough() {
        assertNull(AiEmbeddingStore.pack(null));
        assertNull(AiEmbeddingStore.unpack(null));
    }

    /** A truncated blob must degrade to the whole floats it contains, never throw. */
    @Test
    public void aTruncatedBlobIsClippedNotFatal() {
        float[] out = AiEmbeddingStore.unpack(new byte[]{0x00, 0x00, (byte) 0x80, 0x3F, 0x01, 0x02});
        assertEquals(1, out.length);
        assertEquals(1.0f, out[0], 0f);
    }

    // ------------------------------------------------------------------ CRUD

    @Test
    public void storeAndReadBack() {
        float[] v = {0.1f, 0.2f, -0.3f};
        store.put("fp-1", "embedding_gemma@int4int8", "CLUSTERING", v, 1234L);

        assertArrayEquals(v, store.get("fp-1"), 0f);
        assertTrue(store.has("fp-1"));
        assertEquals(1, store.count());

        AiEmbeddingStore.Row row = store.getRow("fp-1");
        assertNotNull(row);
        assertEquals("embedding_gemma@int4int8", row.model);
        assertEquals("CLUSTERING", row.task);
        assertEquals(3, row.dim);
        assertEquals(1234L, row.createdAt);
        assertArrayEquals(v, row.vec, 0f);
    }

    @Test
    public void missingKeyReadsAsNullNotAsAZeroVector() {
        assertNull(store.get("never-embedded"));
        assertNull(store.getRow("never-embedded"));
        assertFalse(store.has("never-embedded"));
    }

    @Test
    public void putOverwritesInPlace() {
        store.put("fp-1", "m", "CLUSTERING", new float[]{1f});
        store.put("fp-1", "m", "CLUSTERING", new float[]{2f, 3f});

        assertEquals(1, store.count());
        assertArrayEquals(new float[]{2f, 3f}, store.get("fp-1"), 0f);
        assertEquals(2, store.getRow("fp-1").dim);
    }

    @Test
    public void decidedReturnsOnlyRuledOnArticles() {
        store.put("fp-1", "m", "CLUSTERING", new float[]{1f});
        store.put("fp-2", "m", "CLUSTERING", new float[]{2f});
        new AiDecisionStore(db).decide("fp-2", AiDecisionStore.ACTION_KEEP,
                AiDecisionStore.SOURCE_SWIPE, 3, "t", 1L);

        assertEquals(1, store.decided().size());
        assertEquals("fp-2", store.decided().get(0).aiKey);
    }

    // ------------------------------------------------------------------ assertCompatible

    @Test
    public void compatibleModelAndTaskLeaveEverythingAlone() {
        store.put("fp-1", "m", "CLUSTERING", new float[]{1f});
        new AiCentroidStore(db).put(AiCentroidStore.CLASS_KEPT, "m", "CLUSTERING",
                new float[]{1f}, 1);

        assertFalse(store.assertCompatible("m", "CLUSTERING"));
        assertEquals(1, store.count());
        assertEquals(1, countOf(AiSchema.T_CENTROID));
    }

    @Test
    public void aChangedModelWipesVectorsAndCentroidsButNotDecisions() {
        AiDecisionStore decisions = new AiDecisionStore(db);
        store.put("fp-1", "old-model", "CLUSTERING", new float[]{1f});
        decisions.decide("fp-1", AiDecisionStore.ACTION_KEEP, AiDecisionStore.SOURCE_SWIPE,
                3, "t", 1L);
        decisions.markInCentroid("fp-1", true);
        new AiCentroidStore(db).put(AiCentroidStore.CLASS_KEPT, "old-model", "CLUSTERING",
                new float[]{1f}, 1);

        assertTrue(store.assertCompatible("new-model", "CLUSTERING"));

        assertEquals(0, store.count());
        assertEquals(0, countOf(AiSchema.T_CENTROID));
        // The user's judgement survives a model swap; only the arithmetic does not.
        assertEquals(1, decisions.decisionRowCount());
        assertEquals(AiDecisionStore.STATE_KEPT, decisions.tasteState("fp-1"));
        assertEquals(1, decisions.pendingCentroidKeys().size());
    }

    @Test
    public void aChangedTaskAlsoWipes() {
        store.put("fp-1", "m", "CLUSTERING", new float[]{1f});
        assertTrue(store.assertCompatible("m", "SEMANTIC_SIMILARITY"));
        assertEquals(0, store.count());
    }

    @Test
    public void assertCompatibleOnAnEmptyDatabaseIsANoOp() {
        assertFalse(store.assertCompatible("m", "CLUSTERING"));
    }
}
