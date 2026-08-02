package de.luhmer.owncloudnewsreader.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

import de.luhmer.owncloudnewsreader.database.ai.AiCentroidStore;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.AiDecisionStore;
import de.luhmer.owncloudnewsreader.database.ai.AiEmbeddingStore;
import de.luhmer.owncloudnewsreader.database.ai.AiSchema;
import de.luhmer.owncloudnewsreader.database.ai.AiScoreStore;
import de.luhmer.owncloudnewsreader.database.model.RssItem;

/**
 * The swipe / fast-action / undo path end to end: {@link AiDecisions} to {@code AI_DECISION},
 * {@code AI_TASTE} and the centroids.
 */
@RunWith(RobolectricTestRunner.class)
public class AiDecisionFlowTest {

    private SQLiteDatabase sqlite;
    private AiDb db;
    private AiDecisionStore decisions;
    private AiCentroidStore centroids;
    private AiEmbeddingStore embeddings;
    private AiScoreStore scores;

    @Before
    public void openDatabase() {
        sqlite = SQLiteDatabase.create(null);
        AiSchema.createOrMigrate(sqlite);
        db = AiDb.of(sqlite);
        decisions = new AiDecisionStore(db);
        centroids = new AiCentroidStore(db);
        embeddings = new AiEmbeddingStore(db);
        scores = new AiScoreStore(db);
    }

    @After
    public void closeDatabase() {
        if (sqlite != null && sqlite.isOpen()) {
            sqlite.close();
        }
    }

    private static RssItem item(long id, String fingerprint, String title) {
        RssItem r = new RssItem();
        r.setId(id);
        r.setFingerprint(fingerprint);
        r.setTitle(title);
        return r;
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    public void aSwipeRightAppendsAKeepAndFoldsTheCentroid() {
        embeddings.put("fp", "m", "CLUSTERING", new float[]{1f, 0f});

        assertTrue(AiDecisions.record(db, item(1, "fp", "hello"), AiDecisions.KEEP,
                AiDecisions.SOURCE_SWIPE));

        assertEquals(AiDecisionStore.STATE_KEPT, decisions.tasteState("fp"));
        assertEquals(1, decisions.decisionRowCount());
        assertEquals(1, centroids.memberCount(AiCentroidStore.CLASS_KEPT));
        AiDecisionStore.Row row = decisions.history("fp").get(0);
        assertEquals(AiDecisions.SOURCE_SWIPE, row.source);
        assertEquals("hello", row.titleSnap);
    }

    @Test
    public void undoAppendsARowAndNeverDeletesOne() {
        embeddings.put("fp", "m", "CLUSTERING", new float[]{1f, 0f});
        AiDecisions.record(db, item(1, "fp", "hello"), AiDecisions.KEEP, AiDecisions.SOURCE_SWIPE);
        AiDecisions.record(db, item(1, "fp", "hello"), AiDecisions.UNDO, AiDecisions.SOURCE_SWIPE);

        assertEquals("the history is append-only: 2 rows, not 0", 2, decisions.decisionRowCount());
        assertNull("the taste membership is dropped", decisions.tasteState("fp"));
        assertNull("and so is the centroid it was the only member of",
                centroids.get(AiCentroidStore.CLASS_KEPT));

        List<AiDecisionStore.Row> history = decisions.history("fp");
        assertEquals(AiDecisionStore.ACTION_UNDO, history.get(0).action);
        assertEquals(AiDecisionStore.STATE_QUEUED, history.get(0).toState);
        assertEquals(AiDecisionStore.ACTION_KEEP, history.get(1).action);
    }

    @Test
    public void anIllegalTransitionIsASilentNoOp() {
        // undo from queued is not in the table
        assertFalse(AiDecisions.record(db, item(1, "fp", "t"), AiDecisions.UNDO,
                AiDecisions.SOURCE_SWIPE));
        assertEquals(0, decisions.decisionRowCount());
        assertNull(decisions.tasteState("fp"));
    }

    @Test
    public void aSecondIdenticalKeepIsASilentNoOp() {
        AiDecisions.record(db, item(1, "fp", "t"), AiDecisions.KEEP, AiDecisions.SOURCE_SWIPE);
        assertFalse("kept -> keep is not a transition",
                AiDecisions.record(db, item(1, "fp", "t"), AiDecisions.KEEP,
                        AiDecisions.SOURCE_FASTACTION));
        assertEquals(1, decisions.decisionRowCount());
    }

    @Test
    public void anArticleSelectedByThePipelineCanStillBeSwiped() {
        // Every row in the For You folder is STATUS='selected'. If that did not map onto the
        // transition table, swiping there would be a silent no-op and the whole signal would die.
        scores.enqueue(1L, "fp", 1L);
        AiScoreStore.Row r = new AiScoreStore.Row();
        r.aiKey = "fp";
        r.status = AiScoreStore.STATUS_SELECTED;
        scores.writeResult(r);

        assertTrue(AiDecisions.record(db, item(1, "fp", "t"), AiDecisions.REJECT,
                AiDecisions.SOURCE_SWIPE));
        assertEquals(AiDecisionStore.STATE_REJECTED, decisions.tasteState("fp"));
        assertEquals("FROM_STATE records what was observed, not the normalised word",
                AiScoreStore.STATUS_SELECTED, decisions.history("fp").get(0).fromState);
    }

    @Test
    public void theLlmScoreAtIsSnapshottedAndItsAbsenceIsInformation() {
        scores.enqueue(1L, "scored", 1L);
        AiScoreStore.Row r = new AiScoreStore.Row();
        r.aiKey = "scored";
        r.status = AiScoreStore.STATUS_SELECTED;
        r.llmScore = 3;
        scores.writeResult(r);

        AiDecisions.record(db, item(1, "scored", "t"), AiDecisions.REJECT, AiDecisions.SOURCE_SWIPE);
        assertEquals(Integer.valueOf(3), decisions.history("scored").get(0).llmScoreAt);

        AiDecisions.record(db, item(2, "unscored", "t"), AiDecisions.REJECT,
                AiDecisions.SOURCE_SWIPE);
        assertNull("NULL means the article never reached the LLM - that is information",
                decisions.history("unscored").get(0).llmScoreAt);
    }

    @Test
    public void anArticleWithNoFingerprintUsesTheSyntheticKey() {
        assertTrue(AiDecisions.record(db, item(42, "", "t"), AiDecisions.KEEP,
                AiDecisions.SOURCE_SWIPE));
        assertNotNull("an empty fingerprint must not collapse the whole corpus into one row",
                decisions.tasteState("id:42"));
    }

    @Test
    public void aLongTitleIsClippedBeforeItBecomesPromptText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("abcdefghij");
        }
        AiDecisions.record(db, item(1, "fp", sb.toString()), AiDecisions.KEEP,
                AiDecisions.SOURCE_SWIPE);
        assertEquals(AiText.TITLE_SNAP_CHARS, decisions.history("fp").get(0).titleSnap.length());
    }

    @Test
    public void aNullDatabaseDegradesRatherThanThrowing() {
        assertFalse(AiDecisions.record((AiDb) null, item(1, "fp", "t"), AiDecisions.KEEP,
                AiDecisions.SOURCE_SWIPE));
    }

    @Test
    public void aDecisionOnANeverEmbeddedArticleLeavesTheCentroidPending() {
        AiDecisions.record(db, item(1, "fp", "t"), AiDecisions.KEEP, AiDecisions.SOURCE_SWIPE);

        assertEquals(1, decisions.decisionCount());
        assertNull(centroids.get(AiCentroidStore.CLASS_KEPT));
        assertEquals(1, decisions.pendingCentroidKeys().size());
    }

    @Test
    public void theStarSeedFlagIsWrittenOnlyOnce() {
        assertFalse(AiDecisions.starSeedDone(db));
        db.putMeta(AiDecisions.KEY_STAR_SEED_DONE, "1");
        assertTrue(AiDecisions.starSeedDone(db));
    }
}
