package de.luhmer.owncloudnewsreader.database.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;

/**
 * {@code AI_SCORE} CRUD, the {@code AI_KEY} fan-out of PLAN D3, and the two sentinel pairs.
 *
 * <p>The fan-out is what makes the bare {@code " GROUP BY FINGERPRINT "} that
 * {@code NewsReaderDetailFragment} injects into the list SQL harmless: SQLite picks an arbitrary
 * group member for {@code RANK_SCORE}, and every member has the same one. Without it the list
 * renders, scrolls and pages perfectly while being mis-ranked, with no error anywhere.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AiScoreStoreTest extends AiDbTestBase {

    private AiScoreStore store;

    @Before
    public void setUpStore() {
        store = new AiScoreStore(db);
    }

    @Test
    public void enqueueCreatesAQueuedRow() {
        store.enqueue(1L, "fp-a", 7L);

        AiScoreStore.Row r = store.get(1L);
        assertNotNull(r);
        assertEquals(AiScoreStore.STATUS_QUEUED, r.status);
        assertEquals("fp-a", r.aiKey);
        assertEquals(Long.valueOf(7L), r.syncId);
        assertNull("SIM_SCORE must start NULL = never embedded", r.simScore);
        assertNull("LLM_SCORE must start NULL = never judged", r.llmScore);
    }

    @Test
    public void enqueueIsIdempotentAndDoesNotClobberAScore() {
        store.enqueue(1L, "fp-a", 7L);
        AiScoreStore.Row res = new AiScoreStore.Row();
        res.aiKey = "fp-a";
        res.status = AiScoreStore.STATUS_SELECTED;
        res.llmScore = 3;
        res.rankScore = 3.4d;
        store.writeResult(res);

        store.enqueue(1L, "fp-a", 8L);

        assertEquals(1, store.count());
        AiScoreStore.Row r = store.get(1L);
        assertEquals(AiScoreStore.STATUS_SELECTED, r.status);
        assertEquals(Integer.valueOf(3), r.llmScore);
        assertEquals(Long.valueOf(8L), r.syncId);
    }

    /** The D3 invariant. */
    @Test
    public void writeResultFansOutOverEveryRowSharingTheAiKey() {
        store.enqueue(10L, "fp-dup", 1L);
        store.enqueue(11L, "fp-dup", 1L);   // a duplicate of the same article
        store.enqueue(12L, "fp-other", 1L);

        AiScoreStore.Row res = new AiScoreStore.Row();
        res.aiKey = "fp-dup";
        res.status = AiScoreStore.STATUS_SELECTED;
        res.simScore = 0.4d;
        res.llmScore = 3;
        res.effScore = 3;
        res.rankScore = 3.2d;
        res.themes = "drt,operators";
        res.flags = "competitor";
        res.why = "why line";
        res.scoredAt = 999L;
        res.syncId = 1L;
        store.writeResult(res);

        List<AiScoreStore.Row> group = store.byAiKey("fp-dup");
        assertEquals(2, group.size());
        for (AiScoreStore.Row r : group) {
            assertEquals(AiScoreStore.STATUS_SELECTED, r.status);
            assertEquals(Double.valueOf(3.2d), r.rankScore);
            assertEquals(Integer.valueOf(3), r.llmScore);
            assertEquals("drt,operators", r.themes);
        }
        // and nothing leaked to the neighbour
        assertEquals(AiScoreStore.STATUS_QUEUED, store.get(12L).status);
        assertNull(store.get(12L).rankScore);
    }

    @Test
    public void themeOrderIsPreservedVerbatim() {
        store.enqueue(1L, "fp-a", 1L);
        AiScoreStore.Row res = new AiScoreStore.Row();
        res.aiKey = "fp-a";
        res.status = AiScoreStore.STATUS_SELECTED;
        res.themes = "electrification,drt,regulation";
        store.writeResult(res);

        assertEquals("electrification,drt,regulation", store.get(1L).themes);
    }

    /** NULL and 0 are different in both columns, and the difference is load-bearing. */
    @Test
    public void nullAndZeroAreDistinctSentinels() {
        store.enqueue(1L, "never-judged", 1L);
        store.enqueue(2L, "judged-zero", 1L);

        AiScoreStore.Row unjudged = new AiScoreStore.Row();
        unjudged.aiKey = "never-judged";
        unjudged.status = AiScoreStore.STATUS_DISCARDED;
        unjudged.llmScore = null;
        unjudged.simScore = null;
        store.writeResult(unjudged);

        AiScoreStore.Row offTopic = new AiScoreStore.Row();
        offTopic.aiKey = "judged-zero";
        offTopic.status = AiScoreStore.STATUS_DISCARDED;
        offTopic.llmScore = 0;
        offTopic.simScore = 0.0d;
        store.writeResult(offTopic);

        assertNull(store.get(1L).llmScore);
        assertNull(store.get(1L).simScore);
        assertEquals(Integer.valueOf(0), store.get(2L).llmScore);
        assertEquals(Double.valueOf(0.0d), store.get(2L).simScore);
    }

    @Test
    public void unjudgedIsTheResumeSelector() {
        store.enqueue(1L, "a", 5L);
        store.enqueue(2L, "b", 5L);
        store.enqueue(3L, "c", 6L);   // a different run

        AiScoreStore.Row done = new AiScoreStore.Row();
        done.aiKey = "a";
        done.status = AiScoreStore.STATUS_SELECTED;
        done.llmScore = 2;
        done.syncId = 5L;
        store.writeResult(done);

        List<AiScoreStore.Row> pending = store.unjudged(5L, 10);
        assertEquals(1, pending.size());
        assertEquals("b", pending.get(0).aiKey);
    }

    @Test
    public void setSimScoreAcceptsNullBack() {
        store.enqueue(1L, "a", 1L);
        store.setSimScore(1L, 0.5d);
        assertEquals(Double.valueOf(0.5d), store.get(1L).simScore);

        store.setSimScore(1L, null);
        assertNull(store.get(1L).simScore);
    }

    @Test
    public void statusCountsAndDiscardReasons() {
        store.enqueue(1L, "a", 1L);
        store.enqueue(2L, "b", 1L);
        store.setStatus(1L, AiScoreStore.STATUS_SELECTED, null);
        store.setStatus(2L, AiScoreStore.STATUS_DISCARDED, AiScoreStore.REASON_LOW_SIMILARITY);

        assertEquals(1, store.countByStatus(AiScoreStore.STATUS_SELECTED));
        assertEquals(1, store.countByStatus(AiScoreStore.STATUS_DISCARDED));
        assertEquals(0, store.countByStatus(AiScoreStore.STATUS_QUEUED));
        assertEquals(AiScoreStore.REASON_LOW_SIMILARITY, store.get(2L).discardReason);
        assertNull(store.get(1L).discardReason);
        assertEquals("a", store.aiKeyOf(1L));
    }

    /**
     * The interests note changed, so every verdict is stale. NULL means "never judged, retry" —
     * which is precisely what a re-score needs — while the list keeps its current contents and
     * order until better numbers arrive, and the digest keeps its range key.
     */
    @Test
    public void markScoresStaleClearsTheVerdictButNotTheListOrTheDigestWindow() {
        store.enqueue(1L, "a", 1L);
        AiScoreStore.Row row = new AiScoreStore.Row();
        row.aiKey = "a";
        row.status = AiScoreStore.STATUS_SELECTED;
        row.simScore = 0.4d;
        row.llmScore = 3;
        row.effScore = 3;
        row.rankScore = 3.2d;
        row.themes = "regulation";
        row.why = "because";
        row.scoredAt = 1234L;
        row.syncId = 1L;
        store.writeResult(row);

        assertEquals(1, store.markScoresStale());

        AiScoreStore.Row after = store.get(1L);
        assertNull("null = never judged, retry", after.llmScore);
        assertNull(after.effScore);
        assertEquals(AiScoreStore.STATUS_SELECTED, after.status);
        assertEquals("the folder must not empty itself on a note edit",
                Double.valueOf(3.2d), after.rankScore);
        assertEquals("SCORED_AT is the digest's range key", Long.valueOf(1234L), after.scoredAt);
        assertEquals("regulation", after.themes);

        assertEquals("nothing left to invalidate", 0, store.markScoresStale());
    }
}
