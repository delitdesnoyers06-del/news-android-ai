package de.luhmer.owncloudnewsreader.database.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import de.luhmer.owncloudnewsreader.database.model.DaoMaster;

/**
 * The regression test for PLAN D1 — the decision to put the AI tables in the greenDAO database file.
 *
 * <p>The whole design rests on one property: {@code DaoMaster.dropAllTables()} drops exactly the
 * four tables greenDAO generated (Folder / Feed / RssItem / CurrentRssItemView), and
 * {@code DevOpenHelper.onUpgrade} calls only that. Tables greenDAO does not know about therefore
 * survive a schema bump, {@code DatabaseConnectionOrm.resetDatabase()} and Clear-cache. If that ever
 * stops being true, the user's taste model is silently wiped on an app update and this test is the
 * only thing that will say so.</p>
 *
 * <p>It must exist before the first AI row is ever written.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AiSchemaTest extends AiDbTestBase {

    @Test
    public void createOrMigrateCreatesEveryTable() {
        for (String table : AiSchema.ALL_TABLES) {
            assertTrue(table + " is missing", tableExists(table));
        }
    }

    @Test
    public void createOrMigrateRecordsItsVersion() {
        assertEquals(AiSchema.AI_SCHEMA_VERSION, AiSchema.readVersion(sqlite));
        assertTrue(AiSchema.isReady());
    }

    @Test
    public void createOrMigrateIsIdempotent() {
        seedOneRowPerTable();
        long[] before = countAll();

        AiSchema.createOrMigrate(sqlite);
        AiSchema.createOrMigrate(sqlite);
        AiSchema.createOrMigrate(sqlite);

        assertArrayCounts(before, countAll());
        assertEquals(AiSchema.AI_SCHEMA_VERSION, AiSchema.readVersion(sqlite));
    }

    /** THE test: a greenDAO schema bump must not cost the user a single AI row. */
    @Test
    public void everyAiRowSurvivesAGreenDaoSchemaWipe() {
        DaoMaster.createAllTables(sqlite, true);
        seedOneRowPerTable();
        long[] before = countAll();
        for (long c : before) {
            assertTrue("fixture did not insert a row", c > 0);
        }

        // Exactly what DevOpenHelper.onUpgrade() does on a SCHEMA_VERSION bump.
        DaoMaster.dropAllTables(sqlite, true);
        DaoMaster.createAllTables(sqlite, true);

        assertArrayCounts(before, countAll());

        // And the values, not just the counts.
        AiEmbeddingStore embeddings = new AiEmbeddingStore(db);
        float[] vec = embeddings.get("fp-1");
        assertNotNull("the embedding vector is gone", vec);
        assertEquals(3, vec.length);
        assertEquals(0.25f, vec[0], 0f);

        AiDecisionStore decisions = new AiDecisionStore(db);
        assertEquals(AiDecisionStore.STATE_KEPT, decisions.tasteState("fp-1"));
        assertEquals(1, decisions.history("fp-1").size());

        assertEquals("42", db.getMeta("interests_hash"));
    }

    /** {@code resetDatabase()} deletes rows through the DAOs; ours are not DAOs. */
    @Test
    public void aiRowsSurviveADaoLevelDeleteAll() {
        DaoMaster.createAllTables(sqlite, true);
        seedOneRowPerTable();
        long[] before = countAll();

        sqlite.execSQL("DELETE FROM RSS_ITEM");
        sqlite.execSQL("DELETE FROM FEED");
        sqlite.execSQL("DELETE FROM FOLDER");
        sqlite.execSQL("DELETE FROM CURRENT_RSS_ITEM_VIEW");

        assertArrayCounts(before, countAll());
    }

    // ------------------------------------------------------------------ helpers

    private boolean tableExists(String name) {
        try (Cursor c = sqlite.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?", new String[]{name})) {
            return c.moveToFirst();
        }
    }

    private long[] countAll() {
        long[] out = new long[AiSchema.ALL_TABLES.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = countOf(AiSchema.ALL_TABLES[i]);
        }
        return out;
    }

    private void assertArrayCounts(long[] expected, long[] actual) {
        for (int i = 0; i < expected.length; i++) {
            assertEquals(AiSchema.ALL_TABLES[i] + " row count changed",
                    expected[i], actual[i]);
        }
    }

    /** One row in every AI table, written through the real stores wherever one exists. */
    private void seedOneRowPerTable() {
        db.putMeta("interests_hash", "42");

        new AiScoreStore(db).enqueue(1001L, "fp-1", 7L);

        new AiEmbeddingStore(db).put("fp-1", "embedding_gemma@int4int8", "CLUSTERING",
                new float[]{0.25f, -0.5f, 1.0f}, 111L);

        // keep -> writes AI_DECISION + AI_TASTE
        new AiDecisionStore(db).decide("fp-1", AiDecisionStore.ACTION_KEEP,
                AiDecisionStore.SOURCE_SWIPE, 3, "a title", 222L);

        new AiCentroidStore(db).put(AiCentroidStore.CLASS_KEPT, "embedding_gemma@int4int8",
                "CLUSTERING", new float[]{0.25f, -0.5f, 1.0f}, 1);

        new AiRubricStore(db).append("DRT, operators, competitors", null,
                AiRubricStore.SOURCE_USER, true, 333L);

        AiDigestStore digests = new AiDigestStore(db);
        AiDigestStore.Digest d = digests.findOrCreate("2026-08-01", 0L, 1L, 1, 444L);
        digests.putItem(d.id, "fp-1", 1001L, "drt", 2.5d, 0);

        AiModelRegistry models = new AiModelRegistry(db);
        models.register("gemma-4-E2B", AiModelRegistry.KIND_LLM, 2588147712L, "1819381");
        models.setState("gemma-4-E2B", AiModelRegistry.STATE_INSTALLED, "/data/x.litertlm", null);
    }
}
