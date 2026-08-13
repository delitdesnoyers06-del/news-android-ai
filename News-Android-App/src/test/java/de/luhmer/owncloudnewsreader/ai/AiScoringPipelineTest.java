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
import org.robolectric.RuntimeEnvironment;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import de.luhmer.owncloudnewsreader.ai.engine.AiEngineManager;
import de.luhmer.owncloudnewsreader.ai.engine.AiModelInfo;
import de.luhmer.owncloudnewsreader.ai.engine.CancelToken;
import de.luhmer.owncloudnewsreader.ai.prompt.AiPrompts;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.AiSchema;
import de.luhmer.owncloudnewsreader.database.ai.AiScoreStore;
import de.luhmer.owncloudnewsreader.database.model.DaoMaster;

/**
 * Stage 5 end to end, against {@link FakeLlm}. This is the PLAN's {@code AiTriageWorkerTest}: the
 * Worker itself is a 60-line wrapper that lives in {@code src/mlGemma} and therefore cannot be named
 * from a shared test source set without breaking the {@code mlNone} unit-test compile, so the test
 * drives the pipeline the Worker calls.
 *
 * <p><b>The assertion that matters:</b> every article that enters scoring exits with a row in
 * {@code AI_SCORE}. With a model that emits pure garbage, that row carries
 * {@code LLM_SCORE IS NULL} — never a missing row, never a zero, never an exception.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AiScoringPipelineTest {

    private static final long NOW = 1_700_000_000_000L;

    /** Minimal stand-ins for the res/raw prompts; the real ones are covered by PromptBuilderTest. */
    private static final String SYSTEM = "You rate news articles.";
    private static final String USER = "INTERESTS\n{{interests}}\n\nTAGS\n{{tags}}{{corrections}}"
            + "ARTICLES\n{{articles}}\n\nANSWER\n{{n}} lines in {{lang}}.\n";
    private static final String REPAIR = "That was not the required format. {{n}} lines.";

    private SQLiteDatabase sqlite;
    private AiDb db;
    private AiScoreStore scores;

    @Before
    public void openDatabase() {
        sqlite = SQLiteDatabase.create(null);
        AiSchema.createOrMigrate(sqlite);
        DaoMaster.createAllTables(sqlite, true);
        db = AiDb.of(sqlite);
        scores = new AiScoreStore(db);
        sqlite.execSQL("INSERT INTO FEED (_id, FOLDER_ID, FEED_TITLE) "
                + "VALUES (1, NULL, 'Transit Weekly')");
    }

    @After
    public void closeDatabase() {
        if (sqlite != null && sqlite.isOpen()) {
            sqlite.close();
        }
    }

    // ------------------------------------------------------------------ the terminal guarantee

    @Test
    public void garbageOutputLeavesEveryArticleWithARowAndANullScore() {
        articles(6);
        AiTriagePipeline.Report report = run(FakeLlm.garbage(), 6);

        assertEquals(6, scores.count());
        assertEquals(6, report.unjudged);
        assertEquals(0, report.scored);
        for (int i = 1; i <= 6; i++) {
            AiScoreStore.Row row = scores.get(i);
            assertNotNull("article " + i + " must have a row", row);
            assertNull("NULL means never judged, and is retried; 0 would be final", row.llmScore);
            assertEquals("an unjudged article stays visible", AiScoreStore.STATUS_SELECTED,
                    row.status);
        }
    }

    @Test
    public void anExplodingModelLosesNobodyEither() {
        articles(4);
        AiTriagePipeline.Report report = run(FakeLlm.exploding(), 4);

        assertEquals(4, scores.count());
        assertEquals(4, report.unjudged);
        assertNotNull("the failure is named in the report, not thrown", report.degraded);
        for (int i = 1; i <= 4; i++) {
            assertNull(scores.get(i).llmScore);
        }
    }

    @Test
    public void aMissingModelDegradesToSimilarityOnly() {
        articles(3);
        AiEngineManager engines = AiEngineManager.forTesting(new FakeEmbedder(), null);
        AiTriagePipeline.Report report =
                new AiTriagePipeline(RuntimeEnvironment.getApplication(), db, engines, 10)
                        .withScoring(scoring(null))
                        .run(NOW, 7L);

        assertEquals(3, scores.count());
        assertEquals(3, report.unjudged);
        for (int i = 1; i <= 3; i++) {
            assertNull(scores.get(i).llmScore);
            assertEquals(AiScoreStore.STATUS_SELECTED, scores.get(i).status);
        }
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    public void goodOutputIsStoredWithScoresTagsAndWhy() {
        articles(4);
        FakeLlm llm = new FakeLlm((userText, turn) ->
                "1|3|drt|first line\n2|0|-|off topic\n3|2|operators|third\n4|1|drt|fourth\n");
        AiTriagePipeline.Report report = run(llm, 4);

        assertEquals(4, report.scored);
        assertEquals(0, report.unjudged);
        assertEquals(Integer.valueOf(3), scores.get(1).llmScore);
        assertEquals("drt", scores.get(1).themes);
        assertEquals("first line", scores.get(1).why);
        assertEquals(AiScoreStore.STATUS_SELECTED, scores.get(1).status);
        // eff < 2 => discarded with a reason, recorded rather than deleted
        assertEquals(AiScoreStore.STATUS_DISCARDED, scores.get(2).status);
        assertEquals(AiScoreStore.REASON_LOW_SCORE, scores.get(2).discardReason);
        assertEquals(AiScoreStore.STATUS_DISCARDED, scores.get(4).status);
        assertEquals(Integer.valueOf(1), scores.get(4).llmScore);
    }

    @Test
    public void rankIsEffPlusHalfSimAndTheLlmScoreIsStoredUnbumped() {
        articles(1);
        run(new FakeLlm((userText, turn) -> "1|3|drt|why\n"), 1);
        AiScoreStore.Row row = scores.get(1);
        assertEquals(Integer.valueOf(3), row.llmScore);
        assertEquals(Integer.valueOf(3), row.effScore);
        assertNotNull(row.rankScore);
        assertEquals(AiRank.rank(3, 0, row.simScore), row.rankScore, 1e-9);
    }

    // ------------------------------------------------------------------ the repair ladder

    @Test
    public void aMissingLineTriggersOneRepairTurnOnTheSameConversation() {
        articles(4);
        FakeLlm llm = new FakeLlm((userText, turn) -> turn == 0
                ? "1|3|drt|a\n2|2|drt|b\n3|1|drt|c\n"          // line 4 missing
                : "1|0|-|repaired\n2|0|-|repaired\n3|0|-|repaired\n4|2|drt|repaired four\n");
        AiTriagePipeline.Report report = run(llm, 4);

        assertEquals(4, report.scored);
        // existing lines win on merge: the repair turn does not overwrite what we already accepted
        assertEquals(Integer.valueOf(3), scores.get(1).llmScore);
        assertEquals("repaired four", scores.get(4).why);
        assertEquals("one conversation per batch, repair turn included", 1, llm.conversationsOpened);
        assertEquals(1, llm.conversationsClosed);
    }

    @Test
    public void aStillMissingLineIsRerunAsABatchOfOneWithAFreshConversation() {
        articles(4);
        // Turns 0 and 1 (batch + repair) never mention article 4; the split call does.
        FakeLlm llm = new FakeLlm((userText, turn) -> {
            if (FakeLlm.countArticles(userText) == 1) {
                return "1|2|operators|from the split call\n";
            }
            return "1|3|drt|a\n2|2|drt|b\n3|1|drt|c\n";
        });
        AiTriagePipeline.Report report = run(llm, 4);

        assertEquals(4, report.scored);
        assertEquals("from the split call", scores.get(4).why);
        assertEquals(Integer.valueOf(2), scores.get(4).llmScore);
        assertTrue("the split call gets a fresh conversation", llm.conversationsOpened >= 2);
        assertEquals(llm.conversationsOpened, llm.conversationsClosed);
    }

    @Test
    public void extraIndicesAreDroppedNeverAppended() {
        articles(2);
        AiTriagePipeline.Report report =
                run(new FakeLlm((userText, turn) -> "1|3|drt|a\n2|2|drt|b\n9|3|drt|ghost\n"), 2);
        assertEquals(2, scores.count());
        assertEquals(2, report.scored);
    }

    // ------------------------------------------------------------------ cancellation

    @Test
    public void aCancelledRunStillWritesEveryArticleAsUnjudged() {
        articles(5);
        CancelToken token = new CancelToken();
        token.cancel("test");
        AiEngineManager engines = AiEngineManager.forTesting(new FakeEmbedder(), FakeLlm.perfect());
        AiTriagePipeline.Report report =
                new AiTriagePipeline(RuntimeEnvironment.getApplication(), db, engines, 10)
                        .withScoring(new AiTriagePipeline.Scoring(fakeModel(),
                                AiPrompts.of(SYSTEM, USER, REPAIR), "- drt\n",
                                Arrays.asList("drt", "operators"), Collections.emptyList(),
                                Locale.UK, false, 4, token))
                        .run(NOW, 7L);

        assertEquals(5, scores.count());
        assertEquals(5, report.unjudged);
        for (int i = 1; i <= 5; i++) {
            assertNull(scores.get(i).llmScore);
        }
    }

    // ------------------------------------------------------------------ batching + fan-out

    @Test
    public void batchSizeIsFourWhenConstrainedAndOneOtherwise() {
        assertEquals(4, AiScorer.batchSizeFor(FakeLlm.perfect(), 0));
        assertEquals(1, AiScorer.batchSizeFor(
                new FakeLlm((u, t) -> "", false, 2_588_147_712L), 0));
        assertEquals("a sub-500 MB model always gets batch 1", 1,
                AiScorer.batchSizeFor(new FakeLlm((u, t) -> "", true, 373_719_040L), 0));
        assertEquals(1, AiScorer.batchSizeFor(null, 4));
    }

    @Test
    public void duplicateFingerprintsAreScoredOnceAndFannedOut() {
        article(1, "same", "first copy");
        article(2, "same", "second copy");
        AiTriagePipeline.Report report =
                run(new FakeLlm((userText, turn) -> "1|3|drt|shared\n"), 10);

        assertEquals("scored once", 1, report.scored);
        assertEquals(Integer.valueOf(3), scores.get(1).llmScore);
        assertEquals("the fan-out carries the verdict to the duplicate", Integer.valueOf(3),
                scores.get(2).llmScore);
        assertEquals(scores.get(1).rankScore, scores.get(2).rankScore, 1e-9);
        assertEquals("shared", scores.get(2).why);
    }

    @Test
    public void theFeedTitleReachesThePromptAsTheSourceLine() {
        articles(1);
        FakeLlm llm = new FakeLlm((userText, turn) -> "1|3|drt|why\n");
        run(llm, 1);
        assertFalse(llm.prompts.isEmpty());
        assertTrue(llm.prompts.get(0).contains("(Transit Weekly)"));
    }

    // ------------------------------------------------------------------ helpers

    private AiTriagePipeline.Report run(FakeLlm llm, int topK) {
        AiEngineManager engines = AiEngineManager.forTesting(new FakeEmbedder(), llm);
        return new AiTriagePipeline(RuntimeEnvironment.getApplication(), db, engines, topK)
                .withScoring(scoring(llm))
                .run(NOW, 7L);
    }

    private AiTriagePipeline.Scoring scoring(FakeLlm ignored) {
        return new AiTriagePipeline.Scoring(fakeModel(), AiPrompts.of(SYSTEM, USER, REPAIR),
                "- drt\n- operators\n", Arrays.asList("drt", "operators"),
                Collections.emptyList(), Locale.UK, false, 4, new CancelToken());
    }

    private static AiModelInfo fakeModel() {
        return new AiModelInfo("fake", "Fake", 2_588_147_712L, new java.io.File("/dev/null"), true,
                2048);
    }

    private void articles(int n) {
        for (int i = 1; i <= n; i++) {
            article(i, "fp" + i, "Article " + i);
        }
    }

    private void article(long id, String fingerprint, String title) {
        sqlite.execSQL("INSERT INTO RSS_ITEM (_id, FEED_ID, AUTHOR, GUID, GUID_HASH, FINGERPRINT,"
                        + " TITLE, BODY, READ_TEMP, PUB_DATE, LAST_MODIFIED)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new Object[]{id, 1L, "", "g" + id, "gh" + id, fingerprint, title,
                        "<p>body of " + title + "</p>", 0L, NOW - id * 1000L, NOW - id * 1000L});
    }
}
