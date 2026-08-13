package de.luhmer.owncloudnewsreader.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.luhmer.owncloudnewsreader.ai.engine.AiException;

/** The ladder, as a table. Every rung leaves a usable product. */
public class AiDegradeTest {

    @Test
    public void nothingFailedIsFullOnlyWhenTheEmbedderIsThere() {
        assertEquals(AiDegrade.Level.FULL, AiDegrade.levelOf(null, true));
        assertEquals(AiDegrade.Level.NO_EMBEDDER, AiDegrade.levelOf(null, false));
    }

    /**
     * The row that is easy to get wrong: not having downloaded a 2.6 GB scoring model is not a
     * failure. It is the taste model running on its own, which is a shippable product.
     */
    @Test
    public void aMissingScoringModelIsSimilarityOnlyNotAFailure() {
        assertEquals(AiDegrade.Level.SIMILARITY_ONLY,
                AiDegrade.levelOf(AiException.Kind.NOT_INSTALLED, true));
        assertEquals(AiDegrade.Level.NO_EMBEDDER,
                AiDegrade.levelOf(AiException.Kind.NOT_INSTALLED, false));
    }

    @Test
    public void everyOtherFailureIsScoringFailed() {
        for (AiException.Kind k : new AiException.Kind[]{
                AiException.Kind.LOAD_FAILED, AiException.Kind.OUT_OF_MEMORY,
                AiException.Kind.TIMEOUT, AiException.Kind.CANCELLED,
                AiException.Kind.RUNTIME}) {
            assertEquals(k.name(), AiDegrade.Level.SCORING_FAILED, AiDegrade.levelOf(k, true));
        }
        assertEquals(AiDegrade.Level.OFF, AiDegrade.levelOf(AiException.Kind.DISABLED, true));
        assertEquals(AiDegrade.Level.UNSUPPORTED,
                AiDegrade.levelOf(AiException.Kind.UNSUPPORTED_DEVICE, true));
    }

    @Test
    public void whatIsWorthRetryingNextSync() {
        assertTrue(AiDegrade.retryableNextSync(AiException.Kind.TIMEOUT));
        assertTrue(AiDegrade.retryableNextSync(AiException.Kind.CANCELLED));
        assertTrue(AiDegrade.retryableNextSync(AiException.Kind.OUT_OF_MEMORY));
        assertFalse("a missing model will not appear by itself",
                AiDegrade.retryableNextSync(AiException.Kind.NOT_INSTALLED));
        assertFalse(AiDegrade.retryableNextSync(AiException.Kind.UNSUPPORTED_DEVICE));
        assertFalse(AiDegrade.retryableNextSync(null));
    }

    @Test
    public void theReportsDegradedStringRoundTrips() {
        assertNull(AiDegrade.kindOf(null));
        assertEquals(AiException.Kind.TIMEOUT, AiDegrade.kindOf("TIMEOUT"));
        assertEquals("an unknown name is a runtime failure, never a crash",
                AiException.Kind.RUNTIME, AiDegrade.kindOf("something-else"));
    }
}
