package de.luhmer.owncloudnewsreader.database.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.luhmer.owncloudnewsreader.database.model.RssItem;

/**
 * PLAN D2. {@code InsertRssItemIntoDatabase.java:75} defaults the fingerprint to {@code ""}, not
 * {@code null}, so the null guard a few lines below it never fires. A naive fingerprint key would
 * collapse the entire corpus of such a server into ONE ai row.
 *
 * <p>Pure JUnit — no Android types are involved.</p>
 */
public class AiKeysTest {

    @Test
    public void aRealFingerprintIsTheKey() {
        assertEquals("abc123", AiKeys.of(item(7L, "abc123")));
        assertFalse(AiKeys.isSynthetic("abc123"));
    }

    /** The case that actually happens: the server omits the field, greenDAO stores "". */
    @Test
    public void anEmptyFingerprintFallsBackToTheId() {
        assertEquals("id:7", AiKeys.of(item(7L, "")));
        assertTrue(AiKeys.isSynthetic(AiKeys.of(item(7L, ""))));
    }

    @Test
    public void aNullFingerprintFallsBackToTheId() {
        assertEquals("id:7", AiKeys.of(item(7L, null)));
    }

    /** The whole point: two empty-fingerprint articles must NOT share a key. */
    @Test
    public void emptyFingerprintsDoNotCollapseTheCorpus() {
        assertNotEquals(AiKeys.of(item(1L, "")), AiKeys.of(item(2L, "")));
        assertNotEquals(AiKeys.of(item(1L, "")), AiKeys.of(item(2L, null)));
    }

    /** Duplicates of the same article DO share a key — that is what the fan-out relies on. */
    @Test
    public void duplicatesShareOneKey() {
        assertEquals(AiKeys.of(item(1L, "same")), AiKeys.of(item(2L, "same")));
    }

    @Test
    public void whitespaceIsNotEmpty() {
        // A single space is a legal, if useless, fingerprint. We do not second-guess the server:
        // trimming here would silently merge articles the server considers distinct.
        assertEquals(" ", AiKeys.of(item(7L, " ")));
    }

    @Test
    public void theColumnOverloadAgreesWithTheEntityOverload() {
        assertEquals(AiKeys.of(item(9L, "")), AiKeys.of("", 9L));
        assertEquals(AiKeys.of(item(9L, "fp")), AiKeys.of("fp", 9L));
    }

    private static RssItem item(long id, String fingerprint) {
        RssItem it = new RssItem(id);
        it.setFingerprint(fingerprint);
        return it;
    }
}
