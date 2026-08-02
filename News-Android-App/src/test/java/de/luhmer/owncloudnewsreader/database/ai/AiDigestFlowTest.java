package de.luhmer.owncloudnewsreader.database.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;
import java.util.List;

import de.luhmer.owncloudnewsreader.ai.AiDigestBuilder;
import de.luhmer.owncloudnewsreader.ai.AiDigests;
import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm;
import de.luhmer.owncloudnewsreader.database.model.DaoMaster;
import de.luhmer.owncloudnewsreader.database.model.DaoSession;

/**
 * The digest against real SQLite: the selection window, the persisted item rows, and — the one that
 * silently opens the wrong article if it is wrong — the {@code CURRENT_RSS_ITEM_VIEW} rebuild
 * (PLAN R17).
 */
@RunWith(RobolectricTestRunner.class)
public class AiDigestFlowTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long HOUR = 3_600_000L;

    private SQLiteDatabase sqlite;
    private AiDb db;
    private DaoSession daoSession;
    private DatabaseConnectionOrm dbConn;
    private AiScoreStore scores;

    @Before
    public void openDatabase() {
        sqlite = SQLiteDatabase.create(null);
        DaoMaster.createAllTables(sqlite, true);
        AiSchema.createOrMigrate(sqlite);
        daoSession = new DaoMaster(sqlite).newSession();
        dbConn = new DatabaseConnectionOrm(RuntimeEnvironment.getApplication(), daoSession);
        db = AiDb.of(sqlite);
        scores = new AiScoreStore(db);
    }

    @After
    public void closeDatabase() {
        if (sqlite != null && sqlite.isOpen()) {
            sqlite.close();
        }
    }

    @Test
    public void fewerThanFiveNewlySelectedArticlesProduceNoDigest() {
        seed(4, NOW - HOUR, "regulation");
        assertNull(AiDigests.ensureToday(db, NOW));
        assertEquals(0, new AiDigestStore(db).count());
    }

    @Test
    public void fiveNewlySelectedArticlesProduceOne() {
        seed(5, NOW - HOUR, "regulation");
        AiDigestStore.Digest d = AiDigests.ensureToday(db, NOW);
        assertNotNull(d);
        assertEquals(5, d.itemCount);
        assertEquals(AiDigestStore.ABSTRACT_PENDING, d.abstractState);
        assertEquals(5, new AiDigestStore(db).items(d.id).size());
    }

    @Test
    public void readForYouArticlesAreNotIncludedInTheDigestOrAbstractInput() {
        seed(5, NOW - HOUR, "unread");
        seedOne(99L, "read-picked", "read", 3.0d, NOW - HOUR, NOW - HOUR, true);

        AiDigestStore.Digest d = AiDigests.ensureToday(db, NOW);

        assertNotNull(d);
        assertEquals(5, d.itemCount);
        List<AiDigestStore.Item> items = new AiDigestStore(db).items(d.id);
        assertEquals(5, items.size());
        for (AiDigestStore.Item i : items) {
            assertFalse("read-picked".equals(i.aiKey));
        }
        for (AiDigests.Entry e : AiDigests.entries(db, d.id)) {
            assertFalse(e.read);
        }
    }

    @Test
    public void theSecondCallOnTheSameDayReturnsTheSameDigest() {
        seed(6, NOW - HOUR, "regulation");
        AiDigestStore.Digest first = AiDigests.ensureToday(db, NOW);
        AiDigestStore.Digest second = AiDigests.ensureToday(db, NOW + HOUR);
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.id, second.id);
        assertEquals(1, new AiDigestStore(db).count());
    }

    @Test
    public void articlesSelectedBeforeTheWindowAreNotIncluded() {
        // Selected two days ago: outside the default 24 h window of the very first digest.
        seed(3, NOW - 48L * HOUR, "old");
        seed(5, NOW - HOUR, "new");

        AiDigestStore.Digest d = AiDigests.ensureToday(db, NOW);
        assertNotNull(d);
        assertEquals("the range is on selection time, and it starts 24 h back", 5, d.itemCount);
        for (AiDigestStore.Item i : new AiDigestStore(db).items(d.id)) {
            assertEquals("new", i.theme);
        }
    }

    @Test
    public void aPublicationDateInTheWindowIsNotEnoughOnItsOwn() {
        // Published a minute ago, selected two days ago. It belongs to the previous digest.
        seedOne(100L, "fp-old-pick", "t", 2.5d, NOW - 48L * HOUR, NOW - 60_000L);
        seed(5, NOW - HOUR, "t");

        AiDigestStore.Digest d = AiDigests.ensureToday(db, NOW);
        assertNotNull(d);
        assertEquals(5, d.itemCount);
        for (AiDigestStore.Item i : new AiDigestStore(db).items(d.id)) {
            assertFalse("fp-old-pick".equals(i.aiKey));
        }
    }

    @Test
    public void itemsArePersistedInBlockOrder() {
        // "heavy" sums to 5.6, "light" to 3.0 — heavy first, whatever the item counts.
        seedOne(1L, "h1", "heavy", 2.8d, NOW - HOUR, NOW - HOUR);
        seedOne(2L, "h2", "heavy", 2.8d, NOW - HOUR, NOW - HOUR);
        seedOne(3L, "l1", "light", 1.0d, NOW - HOUR, NOW - HOUR);
        seedOne(4L, "l2", "light", 1.0d, NOW - HOUR, NOW - HOUR);
        seedOne(5L, "l3", "light", 1.0d, NOW - HOUR, NOW - HOUR);

        AiDigestStore.Digest d = AiDigests.ensureToday(db, NOW);
        assertNotNull(d);
        List<AiDigestStore.Item> items = new AiDigestStore(db).items(d.id);
        assertEquals("heavy", items.get(0).theme);
        assertEquals("heavy", items.get(1).theme);
        assertEquals("light", items.get(2).theme);
        assertEquals(0, items.get(0).pos);
        assertEquals(4, items.get(4).pos);
    }

    @Test
    public void theCurrentViewRebuildMaterialisesTheDigestInPosOrder() {
        seedOne(1L, "h1", "heavy", 2.8d, NOW - HOUR, NOW - HOUR);
        seedOne(2L, "h2", "heavy", 2.7d, NOW - HOUR, NOW - HOUR);
        seedOne(3L, "l1", "light", 1.0d, NOW - HOUR, NOW - HOUR);
        seedOne(4L, "l2", "light", 0.9d, NOW - HOUR, NOW - HOUR);
        seedOne(5L, "l3", "light", 0.8d, NOW - HOUR, NOW - HOUR);

        AiDigestStore.Digest d = AiDigests.ensureToday(db, NOW);
        assertNotNull(d);

        // The exact call AiDigestActivity makes before launching NewsDetailActivity.
        dbConn.insertIntoRssCurrentViewTable(AiDigests.currentViewSql(d.id));

        assertEquals("[1, 2, 3, 4, 5]", longColumn(
                "SELECT RSS_ITEM_ID FROM CURRENT_RSS_ITEM_VIEW ORDER BY _id").toString());
        assertEquals("the pager indexes this table, so the ids must be contiguous", "[1, 2, 3, 4, 5]",
                longColumn("SELECT _id FROM CURRENT_RSS_ITEM_VIEW ORDER BY _id").toString());
    }

    @Test
    public void entriesJoinBackToTheArticleAndItsWhyLine() {
        seed(5, NOW - HOUR, "regulation");
        AiDigestStore.Digest d = AiDigests.ensureToday(db, NOW);
        assertNotNull(d);

        List<AiDigests.Entry> entries = AiDigests.entries(db, d.id);
        assertEquals(5, entries.size());
        assertEquals("t1", entries.get(0).title);
        assertEquals("because", entries.get(0).why);
        assertEquals(Integer.valueOf(3), entries.get(0).llmScore);
        assertFalse(entries.get(0).read);
    }

    @Test
    public void chipsAreTheHeaviestThemesFirst() {
        seedOne(1L, "h1", "heavy", 2.8d, NOW - HOUR, NOW - HOUR);
        seedOne(2L, "h2", "heavy", 2.8d, NOW - HOUR, NOW - HOUR);
        seedOne(3L, "l1", "light", 1.0d, NOW - HOUR, NOW - HOUR);
        seedOne(4L, "l2", "light", 1.0d, NOW - HOUR, NOW - HOUR);
        seedOne(5L, "l3", "light", 1.0d, NOW - HOUR, NOW - HOUR);

        AiDigestStore.Digest d = AiDigests.ensureToday(db, NOW);
        assertNotNull(d);
        List<AiDigests.Chip> chips = AiDigests.chips(db, d.id, 3);
        assertEquals(2, chips.size());
        assertEquals("heavy", chips.get(0).theme);
        assertEquals(2, chips.get(0).count);
        assertEquals("light", chips.get(1).theme);
    }

    @Test
    public void theThemeFilterClauseIsAppendableAndParses() {
        seed(5, NOW - HOUR, "regulation");
        String base = "SELECT RSS_ITEM._id FROM RSS_ITEM"
                + " JOIN AI_SCORE ON AI_SCORE.RSS_ITEM_ID = RSS_ITEM._id"
                + " WHERE AI_SCORE.STATUS = 'selected'";
        String filtered = base + AiDigests.themeFilterClause("regulation")
                + " ORDER BY AI_SCORE.RANK_SCORE DESC";
        assertEquals(5, longColumn(filtered).size());

        assertEquals(0, longColumn(base + AiDigests.themeFilterClause("nothing-like-this")
                + " ORDER BY AI_SCORE.RANK_SCORE DESC").size());
        assertEquals("a bare LIKE would match 'deregulation' too", 0,
                longColumn(base + AiDigests.themeFilterClause("regul")
                        + " ORDER BY AI_SCORE.RANK_SCORE DESC").size());
        assertEquals("an empty filter must be a no-op, not a broken clause",
                "", AiDigests.themeFilterClause("  "));
    }

    @Test
    public void aDismissedCardIsRememberedForThatDayOnly() {
        seed(5, NOW - HOUR, "regulation");
        AiDigestStore.Digest d = AiDigests.ensureToday(db, NOW);
        assertNotNull(d);
        assertFalse(AiDigests.isDismissed(db, d.dayKey));

        AiDigests.dismiss(db, d, NOW);
        assertTrue(AiDigests.isDismissed(db, d.dayKey));
        assertFalse("tomorrow's card is a different card",
                AiDigests.isDismissed(db, AiDigests.dayKey(NOW + 24L * HOUR)));
    }

    @Test
    public void aFingerprintDuplicateIsExportedOnce() {
        // The fan-out writes one AI_SCORE row per RSS_ITEM_ID sharing an AI_KEY.
        insertRssItem(1L, "dup", NOW - HOUR);
        insertRssItem(2L, "dup", NOW - HOUR);
        for (int i = 3; i <= 7; i++) {
            insertRssItem(i, "fp-" + i, NOW - HOUR);
        }
        scores.enqueue(1L, "dup", 1L);
        scores.enqueue(2L, "dup", 1L);
        for (int i = 3; i <= 7; i++) {
            scores.enqueue(i, "fp-" + i, 1L);
        }
        writeScore("dup", 2.9d, NOW - HOUR, "t");
        for (int i = 3; i <= 7; i++) {
            writeScore("fp-" + i, 1.0d, NOW - HOUR, "t");
        }

        AiDigestStore.Digest d = AiDigests.ensureToday(db, NOW);
        assertNotNull(d);
        assertEquals(6, d.itemCount);
    }

    @Test
    public void theShareBodyIsMarkdownWithOneSectionPerTheme() {
        seedOne(1L, "h1", "heavy", 2.8d, NOW - HOUR, NOW - HOUR);
        seedOne(2L, "l1", "light", 1.0d, NOW - HOUR, NOW - HOUR);
        seedOne(3L, "l2", "light", 1.0d, NOW - HOUR, NOW - HOUR);
        seedOne(4L, "l3", "light", 1.0d, NOW - HOUR, NOW - HOUR);
        seedOne(5L, "l4", "light", 1.0d, NOW - HOUR, NOW - HOUR);

        AiDigestStore.Digest d = AiDigests.ensureToday(db, NOW);
        assertNotNull(d);
        String md = AiDigests.shareMarkdown(d, AiDigests.entries(db, d.id), "Everything else");
        assertTrue(md.contains("## heavy"));
        assertTrue(md.contains("## light"));
        assertEquals("one heading per theme, not per item", 2, count(md, "## "));
    }

    @Test
    public void theBuilderAndTheStoreAgreeOnTheMinimum() {
        assertEquals(AiDigestBuilder.MIN_ITEMS, 5);
    }

    // ------------------------------------------------------------------ fixture

    private void seed(int n, long selectedAt, String theme) {
        long base = db.queryLong("SELECT COALESCE(MAX(_id), 0) FROM RSS_ITEM", null, 0L);
        for (int i = 1; i <= n; i++) {
            seedOne(base + i, "fp-" + (base + i), theme, 2.5d - (i * 0.01d), selectedAt, selectedAt);
        }
    }

    private void seedOne(long id, String aiKey, String theme, double rank, long selectedAt,
                         long pubDate) {
        seedOne(id, aiKey, theme, rank, selectedAt, pubDate, false);
    }

    private void seedOne(long id, String aiKey, String theme, double rank, long selectedAt,
                         long pubDate, boolean read) {
        insertRssItem(id, aiKey, pubDate, read);
        scores.enqueue(id, aiKey, 1L);
        writeScore(aiKey, rank, selectedAt, theme);
    }

    private void writeScore(String aiKey, double rank, long selectedAt, String theme) {
        AiScoreStore.Row row = new AiScoreStore.Row();
        row.aiKey = aiKey;
        row.status = AiScoreStore.STATUS_SELECTED;
        row.simScore = 0.1d;
        row.llmScore = 3;
        row.effScore = 3;
        row.rankScore = rank;
        row.themes = theme;
        row.flags = "";
        row.why = "because";
        row.scoredAt = selectedAt;
        row.syncId = 1L;
        scores.writeResult(row);
    }

    private void insertRssItem(long id, String fingerprint, long pubDate) {
        insertRssItem(id, fingerprint, pubDate, false);
    }

    private void insertRssItem(long id, String fingerprint, long pubDate, boolean read) {
        sqlite.execSQL("INSERT INTO RSS_ITEM (_id, FEED_ID, TITLE, AUTHOR, GUID, GUID_HASH,"
                        + " FINGERPRINT, READ_TEMP, PUB_DATE) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new Object[]{id, 1L, "t" + id, "", "g" + id, "gh" + id, fingerprint,
                        read ? 1L : 0L, pubDate});
    }

    private List<Long> longColumn(String query) {
        List<Long> out = new ArrayList<>();
        try (Cursor c = sqlite.rawQuery(query, null)) {
            while (c.moveToNext()) {
                out.add(c.getLong(0));
            }
        }
        return out;
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            n++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return n;
    }
}
