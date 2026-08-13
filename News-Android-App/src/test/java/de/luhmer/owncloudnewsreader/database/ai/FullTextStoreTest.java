package de.luhmer.owncloudnewsreader.database.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

/**
 * {@code AI_FULLTEXT} CRUD against a real in-memory SQLite database.
 */
@RunWith(RobolectricTestRunner.class)
public class FullTextStoreTest extends AiDbTestBase {

    private FullTextStore store;

    @Before
    public void setUpStore() {
        store = new FullTextStore(db);
    }

    @Test
    public void pendingRowHasNoContentYet() {
        store.upsertPending(1L, "https://example.com/a");

        assertEquals(FullTextStore.STATE_PENDING, store.stateOf(1L));
        assertNull(store.contentHtml(1L));
        assertFalse(store.hasOk(1L));
        assertEquals(List.of(1L), store.pending(10));
    }

    @Test
    public void saveOkStoresRenderableContent() {
        store.saveOk(1L, "https://example.com/a", "<p>Full body</p>", "excerpt", 1000L);

        assertEquals(FullTextStore.STATE_OK, store.stateOf(1L));
        assertEquals("<p>Full body</p>", store.contentHtml(1L));
        assertTrue(store.hasOk(1L));
        assertEquals("ok rows are no longer pending", 0, store.pending(10).size());
    }

    @Test
    public void saveOkOverwritesAnEarlierAttempt() {
        store.upsertPending(1L, "https://example.com/a");
        store.mark(1L, "https://example.com/a", FullTextStore.STATE_FAILED, "boom", 900L);
        store.saveOk(1L, "https://example.com/a", "<p>Recovered</p>", null, 1000L);

        assertEquals(FullTextStore.STATE_OK, store.stateOf(1L));
        assertEquals("<p>Recovered</p>", store.contentHtml(1L));
        assertEquals(1, store.countByState(FullTextStore.STATE_OK));
        assertEquals(0, store.countByState(FullTextStore.STATE_FAILED));
    }

    @Test
    public void failedAndSkippedHaveNoRenderableContent() {
        store.mark(1L, "https://example.com/a", FullTextStore.STATE_FAILED, "HTTP 500", 1L);
        store.mark(2L, "https://example.com/b", FullTextStore.STATE_SKIPPED, "no_readable_content", 1L);

        assertNull(store.contentHtml(1L));
        assertNull(store.contentHtml(2L));
        assertFalse(store.hasOk(1L));
        assertFalse(store.hasOk(2L));
        assertEquals(1, store.countByState(FullTextStore.STATE_FAILED));
        assertEquals(1, store.countByState(FullTextStore.STATE_SKIPPED));
    }

    @Test
    public void stateOfUnknownItemIsNull() {
        assertNull(store.stateOf(999L));
        assertNull(store.contentHtml(999L));
        assertFalse(store.hasOk(999L));
    }

    @Test
    public void pendingRespectsTheLimitAndOrdersById() {
        store.upsertPending(3L, "c");
        store.upsertPending(1L, "a");
        store.upsertPending(2L, "b");

        assertEquals(List.of(1L, 2L), store.pending(2));
    }
}
