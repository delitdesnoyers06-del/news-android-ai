package de.luhmer.owncloudnewsreader.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.HashSet;
import java.util.Set;

import de.luhmer.owncloudnewsreader.ai.engine.AiEngineManager;
import de.luhmer.owncloudnewsreader.ai.model.AiCatalog;
import de.luhmer.owncloudnewsreader.ai.model.AiCatalogEntry;

/**
 * The real {@code res/raw/ai_model_catalog.json}. Every number in it was read from the live Hugging
 * Face tree API or from the CDN's own headers on 2026-08-01; these assertions are what stops a
 * later edit from quietly turning a verified size into a guess.
 */
@RunWith(RobolectricTestRunner.class)
public class AiCatalogTest {

    private AiCatalog catalog() {
        return AiCatalog.of(RuntimeEnvironment.getApplication());
    }

    @Test
    public void theShippedCatalogueParses() {
        assertFalse(catalog().all().isEmpty());
    }

    @Test
    public void idsAreUniqueAndEveryEntryIsUsable() {
        Set<String> ids = new HashSet<>();
        for (AiCatalogEntry e : catalog().all()) {
            assertTrue("duplicate id " + e.id, ids.add(e.id));
            assertNotNull(e.displayName);
            assertNotNull(e.fileName);
            assertTrue("size must be pinned for " + e.id, e.sizeBytes > 0);
            assertTrue("minTotalRamBytes is a data field, not a formula: " + e.id,
                    e.minTotalRamBytes > 0);
            assertTrue(e.isLlm() || e.isEmbedder());
            if (AiCatalogEntry.SOURCE_HF.equals(e.source)) {
                assertNotNull(e.repo);
                assertTrue(e.downloadUrl().startsWith("https://huggingface.co/" + e.repo
                        + "/resolve/"));
            } else {
                assertEquals(AiCatalogEntry.SOURCE_URL, e.source);
                assertNotNull(e.url);
                assertEquals(e.url, e.downloadUrl());
            }
        }
    }

    /**
     * D23: the persisted URL is always the origin {@code resolve/<rev>/} form. The CDN redirect is
     * signed with a ~15 minute expiry, so persisting it would 403 every resume after a lunch break.
     */
    @Test
    public void hfUrlsAreTheResolveFormNeverTheCdn() {
        for (AiCatalogEntry e : catalog().all()) {
            assertFalse(e.downloadUrl().contains("cdn-lfs"));
        }
    }

    /** The registry key the engine manager looks up must be a real catalogue id, or nothing loads. */
    @Test
    public void theEmbedderIdMatchesTheEngineManagersConstant() {
        AiCatalogEntry embedder = catalog().embedder();
        assertNotNull(embedder);
        assertEquals(AiEngineManager.EMBEDDING_MODEL_ID, embedder.id);
        assertTrue(embedder.isEmbedder());
        assertFalse("the embedder must be ungated: it is the mandatory floor", embedder.gated);
    }

    @Test
    public void verifiedFactsAreStillTheOnesInTheCatalogue() {
        AiCatalogEntry embedder = catalog().byId("embedding_gemma_int4int8");
        assertNotNull(embedder);
        assertEquals(183_816_181L, embedder.sizeBytes);
        assertEquals("dabc0e55b47b898a38472d5d99f37892", embedder.md5);
        assertNull("the MediaPipe CDN exposes an MD5 etag only", embedder.sha256);

        AiCatalogEntry e2b = catalog().byId("gemma-4-E2B");
        assertNotNull(e2b);
        assertEquals(2_588_147_712L, e2b.sizeBytes);
        assertEquals("181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", e2b.sha256);
        assertFalse(e2b.gated);
        assertTrue("SentencePiece is what makes constrained decoding available", e2b.sentencePiece);

        AiCatalogEntry qwen = catalog().byId("qwen3-0.6b-int4");
        assertNotNull(qwen);
        assertEquals(497_664_000L, qwen.sizeBytes);
        assertFalse(qwen.gated);
        assertFalse("Qwen is BPE: no grammar, batch 1, repair ladder does the work",
                qwen.sentencePiece);
    }

    /** D22: every Gemma-3 repo answers 401 GatedRepo, and none of them may be a tier default. */
    @Test
    public void gatedEntriesExistButAreNeverADefault() {
        AiCatalogEntry gemma3 = catalog().byId("gemma3-1b-it-int4");
        assertNotNull(gemma3);
        assertTrue(gemma3.gated);
        assertNull("Hugging Face masks the LFS oid of a gated repo", gemma3.sha256);

        AiCatalogEntry full = catalog().defaultLlmFor(AiCapability.Tier.FULL);
        AiCatalogEntry light = catalog().defaultLlmFor(AiCapability.Tier.LIGHT);
        assertNotNull(full);
        assertNotNull(light);
        assertFalse("a gated default is a wall, not a default", full.gated);
        assertFalse(light.gated);
        assertEquals("gemma-4-E2B", full.id);
        assertEquals("qwen3-0.6b-int4", light.id);
        assertNull(catalog().defaultLlmFor(AiCapability.Tier.UNSUPPORTED));
    }

    @Test
    public void everyUngatedEntryCarriesAChecksum() {
        for (AiCatalogEntry e : catalog().all()) {
            if (!e.gated) {
                assertTrue(e.id + " must be verifiable", e.hasChecksum());
            }
        }
    }

    @Test
    public void amalformedCatalogueYieldsAnEmptyOneRatherThanAnException() {
        assertTrue(AiCatalog.parse("{not json").all().isEmpty());
        assertTrue(AiCatalog.parse("").all().isEmpty());
        assertTrue(AiCatalog.parse("{\"entries\":[{\"id\":\"\",\"sizeBytes\":0}]}").all().isEmpty());
    }
}
