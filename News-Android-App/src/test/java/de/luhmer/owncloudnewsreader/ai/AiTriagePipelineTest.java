package de.luhmer.owncloudnewsreader.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

import de.luhmer.owncloudnewsreader.SettingsActivity;
import de.luhmer.owncloudnewsreader.ai.engine.AiEngineManager;
import de.luhmer.owncloudnewsreader.ai.engine.AiException;
import de.luhmer.owncloudnewsreader.database.ai.AiCentroidStore;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.AiDecisionStore;
import de.luhmer.owncloudnewsreader.database.ai.AiEmbeddingStore;
import de.luhmer.owncloudnewsreader.database.ai.AiSchema;
import de.luhmer.owncloudnewsreader.database.ai.AiScoreStore;
import de.luhmer.owncloudnewsreader.database.model.DaoMaster;

/**
 * End to end over stages 1-4 with a {@link FakeEmbedder}: no model, no device, no WorkManager.
 *
 * <p>The invariant under test throughout is PLAN's third: <b>never lose an article</b>. Every
 * candidate that enters must leave with an {@code AI_SCORE} row — selected or discarded with a
 * reason — whatever the embedder does.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AiTriagePipelineTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long DAY = 24L * 60L * 60L * 1000L;

    private SQLiteDatabase sqlite;
    private AiDb db;
    private AiScoreStore scores;
    private AiEmbeddingStore embeddings;
    private AiDecisionStore decisions;
    private AiCentroidStore centroids;

    @Before
    public void openDatabase() {
        sqlite = SQLiteDatabase.create(null);
        AiSchema.createOrMigrate(sqlite);
        DaoMaster.createAllTables(sqlite, true);
        db = AiDb.of(sqlite);
        scores = new AiScoreStore(db);
        embeddings = new AiEmbeddingStore(db);
        decisions = new AiDecisionStore(db);
        centroids = new AiCentroidStore(db);
    }

    @After
    public void closeDatabase() {
        if (sqlite != null && sqlite.isOpen()) {
            sqlite.close();
        }
    }

    // ------------------------------------------------------------------ stage 1

    @Test
    public void onlyUnreadArticlesInsideTheWindowAreCandidates() {
        article(1, "fp1", "fresh", NOW - DAY, false);
        article(2, "fp2", "read", NOW - DAY, true);
        article(3, "fp3", "ancient", NOW - 30 * DAY, false);
        article(4, "fp4", "undated", null, false);

        List<AiCandidates.Candidate> c = AiCandidates.select(db, NOW, 100);

        assertEquals(2, c.size());
        assertEquals("undated articles are KEPT, and sort last", "fresh", c.get(0).title);
        assertEquals("undated", c.get(1).title);
    }

    @Test
    public void chargingAllUnreadCandidatesIgnoreTheAgeWindowButNotReadItems() {
        article(1, "fp1", "fresh", NOW - DAY, false);
        article(2, "fp2", "read", NOW - 30 * DAY, true);
        article(3, "fp3", "ancient", NOW - 30 * DAY, false);

        List<AiCandidates.Candidate> c = AiCandidates.select(db, NOW, 100, false);

        assertEquals(2, c.size());
        assertEquals("fresh", c.get(0).title);
        assertEquals("ancient", c.get(1).title);
    }

    // ------------------------------------------------------------------ cold

    @Test
    public void aColdRunSelectsTheMostRecentAndLosesNobody() {
        for (int i = 1; i <= 5; i++) {
            article(i, "fp" + i, "a" + i, NOW - i * 1000L, false);
        }

        AiTriagePipeline.Report r = run(new FakeEmbedder(), 3);

        assertTrue(r.cold);
        assertEquals(5, r.candidates);
        assertEquals(3, r.selected);
        assertEquals(2, r.discarded);
        assertEquals("every candidate must end up with a row", 5, scores.count());
        assertEquals(3, scores.countByStatus(AiScoreStore.STATUS_SELECTED));
        // newest first
        assertEquals(AiScoreStore.STATUS_SELECTED, scores.get(1L).status);
        assertEquals(AiScoreStore.STATUS_DISCARDED, scores.get(5L).status);
        assertEquals(AiScoreStore.REASON_BELOW_TOP_K, scores.get(5L).discardReason);
    }

    @Test
    public void aColdRunStillEmbedsSoTheNextOneCanBeWarm() {
        article(1, "fp1", "a1", NOW, false);
        FakeEmbedder embedder = new FakeEmbedder();

        AiTriagePipeline.Report r = run(embedder, 10);

        assertEquals(1, r.embedded);
        assertNotNull(embeddings.get("fp1"));
        assertEquals("an embedded article with no centroids is 0.0, never NULL",
                Double.valueOf(0d), scores.get(1L).simScore);
    }

    @Test
    public void anArticleThatWasNeverEmbeddedKeepsANullSimilarity() {
        article(1, "fp1", "a1", NOW, false);

        run(null, 10);   // no embedder installed at all

        assertNull("NULL means never embedded; 0.0 would claim we judged it",
                scores.get(1L).simScore);
        assertEquals(AiScoreStore.STATUS_SELECTED, scores.get(1L).status);
    }

    @Test
    public void aMissingEmbedderIsRecordedAsDegradedAndNotAsAFailure() {
        article(1, "fp1", "a1", NOW, false);

        AiTriagePipeline.Report r = run(null, 10);

        assertEquals(AiException.Kind.NOT_INSTALLED.name(), r.degraded);
        assertEquals("the run still produced a usable folder", 1, r.selected);
    }

    // ------------------------------------------------------------------ warm

    @Test
    public void aWarmRunRanksByTasteRatherThanRecency() {
        // Two directions in the space; the user has taught the model to like direction A.
        float[] a = {1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};
        float[] b = {0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f};
        FakeEmbedder embedder = new FakeEmbedder();

        warmUp(embedder, a, b);

        // The B-flavoured article is much more recent; taste must win.
        article(100, "cand-a", "likeme", NOW - 10_000L, false);
        article(101, "cand-b", "hateme", NOW, false);
        embedder.pin("likeme", a).pin("hateme", b);

        AiTriagePipeline.Report r = run(embedder, 1);

        assertFalse("3 keeps + 3 rejects + 10 decisions is warm", r.cold);
        assertEquals(AiScoreStore.STATUS_SELECTED, scores.get(100L).status);
        assertEquals(AiScoreStore.STATUS_DISCARDED, scores.get(101L).status);
        assertEquals(AiScoreStore.REASON_LOW_SIMILARITY, scores.get(101L).discardReason);
    }

    @Test
    public void warmRunsLetUnembeddedArticlesFillTheRemainingRoom() {
        float[] a = {1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};
        float[] b = {0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f};
        FakeEmbedder embedder = new FakeEmbedder();
        warmUp(embedder, a, b);

        article(100, "cand-a", "likeme", NOW - 10_000L, false);
        article(101, "cand-x", "broken", NOW, false);
        embedder.pin("likeme", a);
        embedder.fail("broken", AiException.Kind.RUNTIME);

        AiTriagePipeline.Report r = run(embedder, 5);

        assertNull("the failed article stays unembedded", embeddings.get("cand-x"));
        assertEquals("it must fill leftover room, not be discarded",
                AiScoreStore.STATUS_SELECTED, scores.get(101L).status);
        assertNull(scores.get(101L).simScore);
        assertEquals(2, r.selected);
    }

    @Test
    public void anEmbedderFailureNeverLosesTheArticle() {
        article(1, "fp1", "boom", NOW, false);
        FakeEmbedder embedder = new FakeEmbedder();
        embedder.fail("boom", AiException.Kind.OUT_OF_MEMORY);

        AiTriagePipeline.Report r = run(embedder, 10);

        assertEquals(0, r.embedded);
        assertEquals(1, scores.count());
        assertNotNull(scores.get(1L));
    }

    // ------------------------------------------------------------------ fan-out and budgets

    @Test
    public void duplicateFingerprintsAreScoredOnceAndFannedOut() {
        article(1, "same-fp", "dup one", NOW, false);
        article(2, "same-fp", "dup two", NOW - 1000L, false);
        FakeEmbedder embedder = new FakeEmbedder();

        AiTriagePipeline.Report r = run(embedder, 10);

        assertEquals("the duplicate must not be embedded twice", 1, embedder.embedCalls);
        assertEquals(2, scores.count());
        assertEquals(scores.get(1L).rankScore, scores.get(2L).rankScore);
        assertEquals(scores.get(1L).status, scores.get(2L).status);
    }

    @Test
    public void theEmbeddingBudgetIsRespectedAndTheBacklogCarriesForward() {
        int n = AiTriagePipeline.EMBED_MAX_PER_SYNC + 5;
        for (int i = 1; i <= n; i++) {
            article(i, "fp" + i, "a" + i, NOW - i * 1000L, false);
        }
        FakeEmbedder embedder = new FakeEmbedder();

        AiTriagePipeline.Report r = run(embedder, 10);

        assertEquals(AiTriagePipeline.EMBED_MAX_PER_SYNC, r.embedded);
        assertEquals(5L, db.getMetaLong(AiTriagePipeline.KEY_EMBED_BACKLOG, -1L));
        assertEquals("still no article lost", n, scores.count());

        // The second pass picks up exactly the leftovers.
        AiTriagePipeline.Report second = run(new FakeEmbedder(), 10);
        assertEquals(5, second.embedded);
    }

    @Test
    public void theChargingAllUnreadPreferenceOnlyAppliesWhileCharging() {
        SharedPreferences prefs = RuntimeEnvironment.getApplication()
                .getSharedPreferences("ai-test", 0);
        prefs.edit()
                .putBoolean(SettingsActivity.CB_AI_ANALYZE_ALL_UNREAD_WHILE_CHARGING, true)
                .apply();

        assertTrue(AiTriagePipeline.allUnreadWhileCharging(prefs, true));
        assertFalse(AiTriagePipeline.allUnreadWhileCharging(prefs, false));
    }

    @Test
    public void aChangedEmbeddingModelWipesTheVectorsButNotTheDecisions() {
        article(1, "fp1", "a1", NOW, false);
        embeddings.put("fp1", "some-old-model", "SEMANTIC_SIMILARITY", new float[]{1f, 0f});
        decisions.decide("fp1", AiDecisionStore.ACTION_KEEP, AiDecisionStore.SOURCE_SWIPE, null, "t");

        run(new FakeEmbedder(), 10);

        assertEquals("the vector was re-made with the new model",
                FakeEmbedder.MODEL, embeddings.getRow("fp1").model);
        assertEquals("the user's judgement survives a model change",
                AiDecisionStore.STATE_KEPT, decisions.tasteState("fp1"));
        assertEquals(1, decisions.decisionRowCount());
    }

    @Test
    public void theRankOfAnUnjudgedArticleIsItsSimilarity() {
        float[] a = {1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};
        float[] b = {0f, 1f, 0f, 0f, 0f, 0f, 0f, 0f};
        FakeEmbedder embedder = new FakeEmbedder();
        warmUp(embedder, a, b);

        article(100, "cand-a", "likeme", NOW, false);
        embedder.pin("likeme", a);
        run(embedder, 5);

        AiScoreStore.Row row = scores.get(100L);
        assertNull("Phase 4 has no LLM: NULL means retry, not 0", row.llmScore);
        assertNull(row.effScore);
        assertEquals(AiRank.round3(row.simScore), row.rankScore, 1e-9d);
    }

    // ------------------------------------------------------------------ helpers

    private AiTriagePipeline.Report run(FakeEmbedder embedder, int topK) {
        AiEngineManager engines = AiEngineManager.forTesting(embedder);
        return new AiTriagePipeline(RuntimeEnvironment.getApplication(), db, engines, topK)
                .run(NOW, 7L);
    }

    /** Teaches the taste model 3 keeps along {@code liked} and 3 rejects along {@code rejected}. */
    private void warmUp(FakeEmbedder embedder, float[] liked, float[] rejected) {
        for (int i = 0; i < 5; i++) {
            embeddings.put("liked" + i, FakeEmbedder.MODEL, FakeEmbedder.TASK, liked);
            fold("liked" + i, AiDecisionStore.ACTION_KEEP);
            embeddings.put("rejected" + i, FakeEmbedder.MODEL, FakeEmbedder.TASK, rejected);
            fold("rejected" + i, AiDecisionStore.ACTION_REJECT);
        }
    }

    private void fold(String aiKey, String action) {
        AiDecisionStore.Result r = decisions.decide(aiKey, action, AiDecisionStore.SOURCE_SWIPE,
                null, aiKey);
        centroids.onDecision(aiKey, r.from, r.state);
    }

    private void article(long id, String fingerprint, String title, Long pubDate, boolean read) {
        sqlite.execSQL("INSERT INTO RSS_ITEM (_id, FEED_ID, AUTHOR, GUID, GUID_HASH, FINGERPRINT,"
                        + " TITLE, BODY, READ_TEMP, PUB_DATE, LAST_MODIFIED)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new Object[]{id, 1L, "", "g" + id, "gh" + id, fingerprint, title,
                        "body of " + title, read ? 1L : 0L, pubDate, pubDate});
    }
}
