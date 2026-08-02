package de.luhmer.owncloudnewsreader.database.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import de.luhmer.owncloudnewsreader.database.model.DaoMaster;

/**
 * {@code AiDb.garbageCollect()} — the prune that runs right after
 * {@code DatabaseConnectionOrm.clearDatabaseOverSize()} on every sync.
 *
 * <p>The one thing it must never do is drop an embedding the user paid for with a decision.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AiGcTest extends AiDbTestBase {

    private AiScoreStore scores;
    private AiEmbeddingStore embeddings;
    private AiDecisionStore decisions;

    @Before
    public void seed() {
        DaoMaster.createAllTables(sqlite, true);
        scores = new AiScoreStore(db);
        embeddings = new AiEmbeddingStore(db);
        decisions = new AiDecisionStore(db);

        // Three articles exist on disk, one has already been evicted by clearDatabaseOverSize().
        insertRssItem(1L, "fp-live");
        insertRssItem(2L, "fp-decided");
        // id 3 / fp-gone is deliberately absent from RSS_ITEM.

        scores.enqueue(1L, "fp-live", 1L);
        scores.enqueue(2L, "fp-decided", 1L);
        scores.enqueue(3L, "fp-gone", 1L);

        embeddings.put("fp-live", "m", "CLUSTERING", new float[]{1f, 0f});
        embeddings.put("fp-decided", "m", "CLUSTERING", new float[]{0f, 1f});
        embeddings.put("fp-gone", "m", "CLUSTERING", new float[]{1f, 1f});
        embeddings.put("fp-orphan", "m", "CLUSTERING", new float[]{2f, 2f});

        // The user ruled on fp-decided; its article is about to disappear from the cache.
        decisions.decide("fp-decided", AiDecisionStore.ACTION_KEEP,
                AiDecisionStore.SOURCE_SWIPE, 3, "kept one", 100L);
        sqlite.execSQL("DELETE FROM RSS_ITEM WHERE _id = 2");
    }

    @Test
    public void orphanedScoresArePruned() {
        db.garbageCollect();

        assertNotNull(scores.get(1L));
        assertNull("score of an evicted article survived", scores.get(2L));
        assertNull("score of a never-present article survived", scores.get(3L));
        assertEquals(1, scores.count());
    }

    @Test
    public void embeddingsOfDecidedArticlesAreKeptForever() {
        db.garbageCollect();

        assertNotNull("the taste model was garbage collected", embeddings.get("fp-decided"));
        assertNotNull("a live article lost its vector", embeddings.get("fp-live"));
        assertNull("an undecided orphan kept its vector", embeddings.get("fp-gone"));
        assertNull("a vector with no score and no decision survived", embeddings.get("fp-orphan"));
    }

    @Test
    public void decisionsAndTasteAreNeverTouched() {
        int decisionRows = decisions.decisionRowCount();
        db.garbageCollect();
        db.garbageCollect();

        assertEquals(decisionRows, decisions.decisionRowCount());
        assertEquals(AiDecisionStore.STATE_KEPT, decisions.tasteState("fp-decided"));
        assertEquals(1, decisions.tasteCount(AiDecisionStore.STATE_KEPT));
    }

    @Test
    public void gcIsIdempotent() {
        db.garbageCollect();
        long scoresAfterFirst = countOf(AiSchema.T_SCORE);
        long embeddingsAfterFirst = countOf(AiSchema.T_EMBEDDING);

        db.garbageCollect();

        assertEquals(scoresAfterFirst, countOf(AiSchema.T_SCORE));
        assertEquals(embeddingsAfterFirst, countOf(AiSchema.T_EMBEDDING));
    }

    private void insertRssItem(long id, String fingerprint) {
        sqlite.execSQL("INSERT INTO RSS_ITEM (_id, FEED_ID, AUTHOR, GUID, GUID_HASH, FINGERPRINT,"
                        + " READ_TEMP) VALUES (?, ?, ?, ?, ?, ?, ?)",
                new Object[]{id, 1L, "", "g" + id, "gh" + id, fingerprint, 0L});
    }
}
