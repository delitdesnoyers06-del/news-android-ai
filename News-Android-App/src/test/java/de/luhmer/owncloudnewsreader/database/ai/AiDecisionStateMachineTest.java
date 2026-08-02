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
 * The four-row transition table of PLAN D28, and the two properties around it that matter more than
 * the table itself: an invalid transition writes <b>no</b> {@code AI_DECISION} row, and undo
 * <b>appends</b> a row rather than deleting one.
 */
@RunWith(RobolectricTestRunner.class)
public class AiDecisionStateMachineTest extends AiDbTestBase {

    private AiDecisionStore store;

    @Before
    public void setUpStore() {
        store = new AiDecisionStore(db);
    }

    // ------------------------------------------------------------------ the table, pure

    @Test
    public void keepIsValidFromQueuedRejectedDiscarded() {
        assertEquals("kept", AiDecisionStore.targetOf("keep", "queued"));
        assertEquals("kept", AiDecisionStore.targetOf("keep", "rejected"));
        assertEquals("kept", AiDecisionStore.targetOf("keep", "discarded"));
        assertNull(AiDecisionStore.targetOf("keep", "kept"));
    }

    @Test
    public void rejectIsValidFromQueuedKeptDiscarded() {
        assertEquals("rejected", AiDecisionStore.targetOf("reject", "queued"));
        assertEquals("rejected", AiDecisionStore.targetOf("reject", "kept"));
        assertEquals("rejected", AiDecisionStore.targetOf("reject", "discarded"));
        assertNull(AiDecisionStore.targetOf("reject", "rejected"));
    }

    @Test
    public void restoreIsValidOnlyFromDiscarded() {
        assertEquals("queued", AiDecisionStore.targetOf("restore", "discarded"));
        assertNull(AiDecisionStore.targetOf("restore", "queued"));
        assertNull(AiDecisionStore.targetOf("restore", "kept"));
        assertNull(AiDecisionStore.targetOf("restore", "rejected"));
    }

    @Test
    public void undoIsValidOnlyFromKeptOrRejected() {
        assertEquals("queued", AiDecisionStore.targetOf("undo", "kept"));
        assertEquals("queued", AiDecisionStore.targetOf("undo", "rejected"));
        assertNull(AiDecisionStore.targetOf("undo", "queued"));
        assertNull(AiDecisionStore.targetOf("undo", "discarded"));
    }

    @Test
    public void unknownActionIsNeverValid() {
        assertNull(AiDecisionStore.targetOf("star", "queued"));
        assertNull(AiDecisionStore.targetOf(null, "queued"));
    }

    /**
     * {@code selected} is a machine word with no row in the table; it normalises to {@code queued}.
     * Without this, swiping an article in the For You folder — where every row is
     * {@code STATUS='selected'} — would be a silent no-op and the entire signal would be dead.
     */
    @Test
    public void selectedNormalisesToQueued() {
        assertEquals("kept", AiDecisionStore.targetOf("keep", "selected"));
        assertEquals("rejected", AiDecisionStore.targetOf("reject", "selected"));
        assertNull(AiDecisionStore.targetOf("undo", "selected"));
        assertNull(AiDecisionStore.targetOf("restore", "selected"));
    }

    // ------------------------------------------------------------------ the table, persisted

    @Test
    public void keepWritesOneDecisionAndOneTasteRow() {
        AiDecisionStore.Result r = store.decide("k1", AiDecisionStore.ACTION_KEEP,
                AiDecisionStore.SOURCE_SWIPE, 3, "title", 10L);

        assertTrue(r.changed);
        assertEquals(AiDecisionStore.STATE_KEPT, r.state);
        assertEquals(1, store.decisionRowCount());
        assertEquals(AiDecisionStore.STATE_KEPT, store.tasteState("k1"));

        AiDecisionStore.Row row = store.history("k1").get(0);
        assertEquals("queued", row.fromState);
        assertEquals("kept", row.toState);
        assertEquals(Integer.valueOf(3), row.llmScoreAt);
        assertEquals("title", row.titleSnap);
    }

    @Test
    public void keepThenRejectFlipsTheTasteRowAndAppends() {
        store.decide("k1", AiDecisionStore.ACTION_KEEP, AiDecisionStore.SOURCE_SWIPE, 3, "t", 10L);
        AiDecisionStore.Result r = store.decide("k1", AiDecisionStore.ACTION_REJECT,
                AiDecisionStore.SOURCE_FASTACTION, 3, "t", 20L);

        assertTrue(r.changed);
        assertEquals(2, store.decisionRowCount());
        assertEquals(AiDecisionStore.STATE_REJECTED, store.tasteState("k1"));
        assertEquals("kept", store.history("k1").get(0).fromState);
    }

    /** The invariant: an invalid transition is an idempotent no-op that writes NOTHING. */
    @Test
    public void invalidTransitionWritesNoDecisionRow() {
        store.decide("k1", AiDecisionStore.ACTION_KEEP, AiDecisionStore.SOURCE_SWIPE, 3, "t", 10L);
        assertEquals(1, store.decisionRowCount());

        AiDecisionStore.Result again = store.decide("k1", AiDecisionStore.ACTION_KEEP,
                AiDecisionStore.SOURCE_SWIPE, 3, "t", 20L);

        assertFalse(again.changed);
        assertEquals(AiDecisionStore.STATE_KEPT, again.state);
        assertEquals("an invalid transition appended a row", 1, store.decisionRowCount());
        assertEquals(1, store.decisionCount());
    }

    @Test
    public void restoreOnANonDiscardedArticleWritesNothing() {
        AiDecisionStore.Result r = store.decide("k1", AiDecisionStore.ACTION_RESTORE,
                AiDecisionStore.SOURCE_SETTINGS, null, null, 10L);

        assertFalse(r.changed);
        assertEquals(0, store.decisionRowCount());
        assertEquals(0, countOf(AiSchema.T_TASTE));
    }

    @Test
    public void undoOnAnUndecidedArticleWritesNothing() {
        AiDecisionStore.Result r = store.decide("k1", AiDecisionStore.ACTION_UNDO,
                AiDecisionStore.SOURCE_SWIPE, null, null, 10L);

        assertFalse(r.changed);
        assertEquals(0, store.decisionRowCount());
    }

    /** Undo APPENDS. The disagreement history is the input to the learn loop; it is never erased. */
    @Test
    public void undoAppendsARowAndRemovesTheTasteRow() {
        store.decide("k1", AiDecisionStore.ACTION_KEEP, AiDecisionStore.SOURCE_SWIPE, 3, "t", 10L);

        AiDecisionStore.Result r = store.decide("k1", AiDecisionStore.ACTION_UNDO,
                AiDecisionStore.SOURCE_SWIPE, 3, "t", 20L);

        assertTrue(r.changed);
        assertEquals(AiDecisionStore.STATE_QUEUED, r.state);
        assertNull("the taste row survived an undo", store.tasteState("k1"));
        assertEquals(0, countOf(AiSchema.T_TASTE));

        List<AiDecisionStore.Row> history = store.history("k1");
        assertEquals("undo did not append", 2, history.size());
        assertEquals("undo", history.get(0).action);
        assertEquals("kept", history.get(0).fromState);
        assertEquals("queued", history.get(0).toState);
        assertEquals("keep", history.get(1).action);
    }

    /**
     * A restore has no {@code LLM_SCORE_AT} — and that null is INFORMATION, not a missing value.
     * {@code learn.py:62} treats {@code restore} as a standalone OR term precisely because of it.
     */
    @Test
    public void nullLlmScoreIsPersistedAsNull() {
        new AiScoreStore(db).enqueue(1L, "k1", 1L);
        new AiScoreStore(db).setStatus(1L, AiScoreStore.STATUS_DISCARDED,
                AiScoreStore.REASON_LOW_SCORE);

        AiDecisionStore.Result r = store.decide("k1", AiDecisionStore.ACTION_RESTORE,
                AiDecisionStore.SOURCE_SETTINGS, null, null, 10L);

        assertTrue(r.changed);
        assertEquals("discarded", store.history("k1").get(0).fromState);
        assertNull(store.history("k1").get(0).llmScoreAt);
        assertNull("restore must not create a taste row", store.tasteState("k1"));
    }

    /** With no taste row and no score row, an article is {@code queued}. */
    @Test
    public void currentStateFallsBackThroughTasteThenScoreThenQueued() {
        assertEquals("queued", store.currentState("unknown"));

        new AiScoreStore(db).enqueue(1L, "k1", 1L);
        new AiScoreStore(db).setStatus(1L, AiScoreStore.STATUS_SELECTED, null);
        assertEquals("selected", store.currentState("k1"));

        store.decide("k1", AiDecisionStore.ACTION_KEEP, AiDecisionStore.SOURCE_SWIPE, 2, "t", 10L);
        assertEquals("kept", store.currentState("k1"));
    }

    @Test
    public void decisionCountTracksAppendedRowsOnly() {
        store.decide("a", AiDecisionStore.ACTION_KEEP, AiDecisionStore.SOURCE_SWIPE, 3, "t", 10L);
        store.decide("b", AiDecisionStore.ACTION_REJECT, AiDecisionStore.SOURCE_SWIPE, 0, "t", 11L);
        store.decide("b", AiDecisionStore.ACTION_REJECT, AiDecisionStore.SOURCE_SWIPE, 0, "t", 12L);

        assertEquals(2, store.decisionCount());
        assertEquals(2, store.decisionRowCount());
        assertEquals(1, store.tasteCount(AiDecisionStore.STATE_KEPT));
        assertEquals(1, store.tasteCount(AiDecisionStore.STATE_REJECTED));
    }
}
