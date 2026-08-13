package de.luhmer.owncloudnewsreader.database.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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

import de.luhmer.owncloudnewsreader.ListView.SubscriptionExpandableListAdapter.SPECIAL_FOLDERS;
import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm;
import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm.SORT_DIRECTION;
import de.luhmer.owncloudnewsreader.database.model.DaoMaster;
import de.luhmer.owncloudnewsreader.database.model.DaoSession;
import de.luhmer.owncloudnewsreader.database.model.RssItem;
import de.luhmer.owncloudnewsreader.database.model.RssItemDao;

/**
 * The regression test for PLAN D3/D4 — the "For you" list query.
 *
 * <p>Everything this file asserts is a silent-corruption risk: a wrong alias, a second literal
 * {@code ORDER BY}, a re-enabled sort preference or a missing exclusion-set entry all produce a
 * list that renders, scrolls and pages perfectly while being wrong. There is no exception anywhere
 * on those paths, so the string itself and the rows it actually materialises are the only things
 * that can be checked.</p>
 *
 * <p>Real SQLite via Robolectric, the real {@link DatabaseConnectionOrm} query builders, and the
 * {@code " GROUP BY FINGERPRINT "} string surgery copied character-for-character from
 * {@code NewsReaderDetailFragment.UpdateCurrentRssViewTask.doInBackground()}.</p>
 */
@RunWith(RobolectricTestRunner.class)
public class AiFolderSqlTest {

    private static final long AI_FOR_YOU = SPECIAL_FOLDERS.AI_FOR_YOU.getValue();

    private SQLiteDatabase sqlite;
    private DaoSession daoSession;
    private DatabaseConnectionOrm dbConn;
    private AiScoreStore scores;

    @Before
    public void openDatabase() {
        sqlite = SQLiteDatabase.create(null);       // in-memory, real SQLite
        DaoMaster.createAllTables(sqlite, true);
        AiSchema.createOrMigrate(sqlite);
        daoSession = new DaoMaster(sqlite).newSession();
        dbConn = new DatabaseConnectionOrm(RuntimeEnvironment.getApplication(), daoSession);
        scores = new AiScoreStore(AiDb.of(sqlite));
    }

    @After
    public void closeDatabase() {
        if (sqlite != null && sqlite.isOpen()) {
            sqlite.close();
        }
    }

    // ---------------------------------------------------------------- the string itself

    @Test
    public void generatedSqlIsExactlyTheAgreedString() {
        String expected = "SELECT RSS_ITEM._id"
                + " FROM RSS_ITEM"
                + " JOIN AI_SCORE ON AI_SCORE.RSS_ITEM_ID = RSS_ITEM._id"
                + " WHERE AI_SCORE.STATUS = 'selected'"
                + " AND NOT EXISTS (SELECT 1 FROM AI_TASTE WHERE AI_TASTE.AI_KEY = AI_SCORE.AI_KEY)"
                + " ORDER BY AI_SCORE.RANK_SCORE DESC, RSS_ITEM.PUB_DATE DESC, RSS_ITEM._id DESC";

        assertEquals(expected, sql(false, SORT_DIRECTION.desc));
    }

    @Test
    public void onlyUnreadAddsExactlyOnePredicate() {
        String expected = "SELECT RSS_ITEM._id"
                + " FROM RSS_ITEM"
                + " JOIN AI_SCORE ON AI_SCORE.RSS_ITEM_ID = RSS_ITEM._id"
                + " WHERE AI_SCORE.STATUS = 'selected'"
                + " AND NOT EXISTS (SELECT 1 FROM AI_TASTE WHERE AI_TASTE.AI_KEY = AI_SCORE.AI_KEY)"
                + " AND RSS_ITEM.READ_TEMP != 1"
                + " ORDER BY AI_SCORE.RANK_SCORE DESC, RSS_ITEM.PUB_DATE DESC, RSS_ITEM._id DESC";

        assertEquals(expected, sql(true, SORT_DIRECTION.desc));
    }

    @Test
    public void thereIsExactlyOneOrderByLiteral() {
        for (boolean onlyUnread : new boolean[]{false, true}) {
            String s = sql(onlyUnread, SORT_DIRECTION.desc);
            int first = s.indexOf("ORDER BY");
            assertTrue("no ORDER BY at all", first >= 0);
            assertEquals("a second ORDER BY would move the GROUP BY injection point",
                    -1, s.indexOf("ORDER BY", first + 1));
        }
    }

    @Test
    public void noTableIsAliased() {
        // An alias would still parse - " GROUP BY FINGERPRINT " is unqualified and only RSS_ITEM
        // owns that column - and would then silently mis-rank. Assert the two full table names are
        // what the statement actually references.
        String s = sql(false, SORT_DIRECTION.desc);
        assertTrue(s.contains(" FROM RSS_ITEM JOIN AI_SCORE ON "));
        assertEquals("RSS_ITEM was given an alias", "JOIN", tokenAfter(s, "FROM RSS_ITEM"));
        assertEquals("AI_SCORE was given an alias", "ON", tokenAfter(s, "JOIN AI_SCORE"));
        // Every column reference is table-qualified or unambiguous; no single-letter alias anywhere.
        for (String token : s.split("\\s+")) {
            assertFalse("suspicious alias token: " + token, token.length() == 1
                    && Character.isLetter(token.charAt(0)));
        }
    }

    @Test
    public void sortDirectionPreferenceHasNoEffect() {
        // PLAN D4: "For you" is a relevance list, not a timeline.
        assertEquals(sql(false, SORT_DIRECTION.asc), sql(false, SORT_DIRECTION.desc));
        assertEquals(sql(true, SORT_DIRECTION.asc), sql(true, SORT_DIRECTION.desc));
        assertFalse(sql(false, SORT_DIRECTION.asc).contains(" asc"));
    }

    @Test
    public void aRealFolderIsUnaffectedAndTheAiIdNeverReachesIt() {
        String real = dbConn.getAllItemsIdsForFolderSQL(7L, false, SORT_DIRECTION.desc,
                RuntimeEnvironment.getApplication());
        assertTrue(real.contains("WHERE f._id = 7"));

        // The bug this guards: -14 falling into the real-folder branch produces "f._id = -14",
        // which returns zero rows with no error at all.
        String ai = sql(false, SORT_DIRECTION.desc);
        assertFalse(ai.contains("f._id"));
        assertFalse(ai.contains("FOLDER"));
        assertNotEquals(real, ai);
    }

    // ---------------------------------------------------------------- the injection

    @Test
    public void theGroupByInjectionParsesAndFingerprintResolvesUnqualified() {
        seedThreeRankedArticles();

        String injected = injectGroupByFingerprint(sql(false, SORT_DIRECTION.desc));
        assertTrue(injected.contains(" GROUP BY FINGERPRINT ORDER BY "));

        // If FINGERPRINT were ambiguous or unresolvable this throws SQLiteException.
        List<Long> ids = new ArrayList<>();
        try (Cursor c = sqlite.rawQuery(injected, null)) {
            while (c.moveToNext()) {
                ids.add(c.getLong(0));
            }
        }
        assertEquals(3, ids.size());
    }

    // ---------------------------------------------------------------- the materialised list

    @Test
    public void currentViewIsInRankOrderWithContiguousIds() {
        seedThreeRankedArticles();

        dbConn.insertIntoRssCurrentViewTable(injectGroupByFingerprint(sql(false, SORT_DIRECTION.desc)));

        assertEquals("[3, 1, 2]", currentViewRssItemIds().toString());
        assertEquals("CURRENT_RSS_ITEM_VIEW._id must be contiguous from 1 - paging is _id-ranged",
                "[1, 2, 3]", currentViewOwnIds().toString());

        List<RssItem> page = dbConn.getCurrentRssItemView(0);
        assertEquals(3, page.size());
        assertEquals(Long.valueOf(3L), page.get(0).getId());
        assertEquals(Long.valueOf(1L), page.get(1).getId());
        assertEquals(Long.valueOf(2L), page.get(2).getId());
    }

    @Test
    public void aDuplicateFingerprintYieldsExactlyOneRowAtTheCorrectRank() {
        seedThreeRankedArticles();
        // A fourth article that is a fingerprint-duplicate of the top-ranked one. The pipeline fans
        // the score out over the AI_KEY, so both rows carry rank 2.9 and the arbitrary group-member
        // pick SQLite makes is a pick among identical values.
        insertRssItem(4L, "fp-top", 4000L, false);
        scores.enqueue(4L, "fp-top", 1L);
        writeScore("fp-top", 2.9);

        dbConn.insertIntoRssCurrentViewTable(injectGroupByFingerprint(sql(false, SORT_DIRECTION.desc)));

        List<Long> ids = currentViewRssItemIds();
        assertEquals("the duplicate produced a second row", 3, ids.size());
        assertTrue("the surviving duplicate is not at the top rank: " + ids,
                ids.get(0) == 3L || ids.get(0) == 4L);
        assertEquals("only one of the two duplicates survived", 1,
                (ids.contains(3L) ? 1 : 0) + (ids.contains(4L) ? 1 : 0));
        assertEquals("[1, 2, 3]", currentViewOwnIds().toString());
    }

    @Test
    public void onlyUnreadIsHonoured() {
        seedThreeRankedArticles();
        sqlite.execSQL("UPDATE RSS_ITEM SET READ_TEMP = 1 WHERE _id = 3");

        dbConn.insertIntoRssCurrentViewTable(injectGroupByFingerprint(sql(true, SORT_DIRECTION.desc)));
        assertEquals("[1, 2]", currentViewRssItemIds().toString());

        dbConn.insertIntoRssCurrentViewTable(injectGroupByFingerprint(sql(false, SORT_DIRECTION.desc)));
        assertEquals("[3, 1, 2]", currentViewRssItemIds().toString());
    }

    @Test
    public void discardedAndQueuedArticlesNeverAppear() {
        seedThreeRankedArticles();
        insertRssItem(9L, "fp-queued", 9000L, false);
        scores.enqueue(9L, "fp-queued", 1L);        // stays 'queued'
        insertRssItem(10L, "fp-discarded", 9500L, false);
        scores.enqueue(10L, "fp-discarded", 1L);
        scores.setStatus(10L, AiScoreStore.STATUS_DISCARDED, AiScoreStore.REASON_LOW_SCORE);

        dbConn.insertIntoRssCurrentViewTable(injectGroupByFingerprint(sql(false, SORT_DIRECTION.desc)));

        assertEquals("[3, 1, 2]", currentViewRssItemIds().toString());
    }

    @Test
    public void anArticleWithNoScoreRowIsNotInTheList() {
        seedThreeRankedArticles();
        insertRssItem(11L, "fp-unscored", 9900L, false);

        dbConn.insertIntoRssCurrentViewTable(injectGroupByFingerprint(sql(false, SORT_DIRECTION.desc)));

        assertFalse(currentViewRssItemIds().contains(11L));
    }

    @Test
    public void tiedRanksFallBackToPubDateThenId() {
        insertRssItem(1L, "fp-a", 1000L, false);
        insertRssItem(2L, "fp-b", 3000L, false);
        insertRssItem(3L, "fp-c", 3000L, false);
        for (long id = 1; id <= 3; id++) {
            scores.enqueue(id, "fp-" + (char) ('a' + id - 1), 1L);
            writeScore("fp-" + (char) ('a' + id - 1), 1.5);
        }

        dbConn.insertIntoRssCurrentViewTable(injectGroupByFingerprint(sql(false, SORT_DIRECTION.desc)));

        // equal rank -> newest pubDate first; equal pubDate -> highest _id first
        assertEquals("[3, 2, 1]", currentViewRssItemIds().toString());
    }

    @Test
    public void theDebugSeederProducesAListTheFolderCanRender() {
        for (long id = 1; id <= 20; id++) {
            insertRssItem(id, "fp-" + id, 1000L * id, false);
        }

        int selected = AiDebugSeed.seed(sqlite, 100);
        assertTrue("the seeder selected nothing at all", selected > 0);

        dbConn.insertIntoRssCurrentViewTable(injectGroupByFingerprint(sql(false, SORT_DIRECTION.desc)));
        assertEquals(selected, currentViewRssItemIds().size());

        // Ranks must be non-increasing down the materialised list.
        double previous = Double.MAX_VALUE;
        for (Long id : currentViewRssItemIds()) {
            double rank = scores.get(id).rankScore;
            assertTrue("list is not in rank order", rank <= previous);
            previous = rank;
        }

        assertTrue(AiDebugSeed.clear(sqlite) > 0);
        dbConn.insertIntoRssCurrentViewTable(injectGroupByFingerprint(sql(false, SORT_DIRECTION.desc)));
        assertTrue(currentViewRssItemIds().isEmpty());
    }

    // ---------------------------------------------------------------- helpers

    /** The first whitespace-delimited token following {@code prefix}. */
    private static String tokenAfter(String sql, String prefix) {
        int at = sql.indexOf(prefix);
        assertTrue(prefix + " not found in: " + sql, at >= 0);
        String rest = sql.substring(at + prefix.length()).trim();
        int end = rest.indexOf(' ');
        return end < 0 ? rest : rest.substring(0, end);
    }

    private String sql(boolean onlyUnread, SORT_DIRECTION direction) {
        return dbConn.getAllItemsIdsForFolderSQL(AI_FOR_YOU, onlyUnread, direction,
                RuntimeEnvironment.getApplication());
    }

    /**
     * Copied character-for-character from
     * {@code NewsReaderDetailFragment.UpdateCurrentRssViewTask.doInBackground()}. If that code ever
     * changes, this must change with it — that is the point of duplicating it here.
     */
    private static String injectGroupByFingerprint(String sqlSelectStatement) {
        int index = sqlSelectStatement.indexOf("ORDER BY");
        if (index == -1) {
            index = sqlSelectStatement.length();
        }
        return new StringBuilder(sqlSelectStatement)
                .insert(index, " GROUP BY " + RssItemDao.Properties.Fingerprint.columnName + " ")
                .toString();
    }

    /** Three selected articles whose intended order is 3, 1, 2. */
    private void seedThreeRankedArticles() {
        insertRssItem(1L, "fp-mid", 3000L, false);
        insertRssItem(2L, "fp-low", 5000L, false);
        insertRssItem(3L, "fp-top", 1000L, false);

        scores.enqueue(1L, "fp-mid", 1L);
        scores.enqueue(2L, "fp-low", 1L);
        scores.enqueue(3L, "fp-top", 1L);

        writeScore("fp-mid", 2.4);
        writeScore("fp-low", 2.05);
        writeScore("fp-top", 2.9);
    }

    private void writeScore(String aiKey, double rank) {
        AiScoreStore.Row row = new AiScoreStore.Row();
        row.aiKey = aiKey;
        row.status = AiScoreStore.STATUS_SELECTED;
        row.simScore = 0.1;
        row.llmScore = 3;
        row.effScore = 3;
        row.rankScore = rank;
        row.themes = "drt";
        row.flags = "";
        row.why = "because";
        row.scoredAt = 1L;
        row.syncId = 1L;
        scores.writeResult(row);
    }

    private void insertRssItem(long id, String fingerprint, long pubDate, boolean read) {
        sqlite.execSQL("INSERT INTO RSS_ITEM (_id, FEED_ID, TITLE, AUTHOR, GUID, GUID_HASH,"
                        + " FINGERPRINT, READ_TEMP, PUB_DATE) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                new Object[]{id, 1L, "t" + id, "", "g" + id, "gh" + id, fingerprint,
                        read ? 1L : 0L, pubDate});
    }

    // --- a decided article leaves the queue -------------------------------------------------
    //
    // The triage loop only works if swiping removes the article. The decision is written to
    // AI_TASTE, never to AI_SCORE.STATUS, because the pipeline must not write a human word like
    // 'kept' (PLAN D28) — so the list query expresses "already judged" as the absence of a taste
    // row. Without that clause every swiped article came straight back on the next refresh, which
    // is what all three implementation phases flagged as the outstanding bug.

    @Test
    public void aKeptArticleLeavesTheForYouList() {
        seedThreeRankedArticles();
        decisions().decide("fp-top", AiDecisionStore.ACTION_KEEP, "swipe", 3, "t3");

        rebuildCurrentView();

        assertEquals("the kept article must not come back on refresh",
                List.of(1L, 2L), currentViewRssItemIds());
    }

    @Test
    public void aRejectedArticleLeavesTheForYouList() {
        seedThreeRankedArticles();
        decisions().decide("fp-mid", AiDecisionStore.ACTION_REJECT, "swipe", 3, "t1");

        rebuildCurrentView();

        assertEquals(List.of(3L, 2L), currentViewRssItemIds());
    }

    @Test
    public void undoPutsTheArticleBackInRankOrder() {
        seedThreeRankedArticles();
        decisions().decide("fp-top", AiDecisionStore.ACTION_KEEP, "swipe", 3, "t3");
        rebuildCurrentView();
        assertEquals(List.of(1L, 2L), currentViewRssItemIds());

        decisions().decide("fp-top", AiDecisionStore.ACTION_UNDO, "swipe", 3, "t3");
        rebuildCurrentView();

        assertEquals("undo deletes the AI_TASTE row, so the article returns at its old rank",
                List.of(3L, 1L, 2L), currentViewRssItemIds());
    }

    @Test
    public void theBadgeCountMatchesTheListExactly() {
        seedThreeRankedArticles();
        assertEquals("3", dbConn.getAiSelectedUnreadCount());

        decisions().decide("fp-top", AiDecisionStore.ACTION_KEEP, "swipe", 3, "t3");

        rebuildCurrentView();
        assertEquals("a badge that counts rows the list does not show is a bug users can see",
                String.valueOf(currentViewRssItemIds().size()), dbConn.getAiSelectedUnreadCount());
        assertEquals("2", dbConn.getAiSelectedUnreadCount());
    }

    private AiDecisionStore decisions() {
        return new AiDecisionStore(AiDb.of(sqlite));
    }

    private void rebuildCurrentView() {
        dbConn.insertIntoRssCurrentViewTable(
                injectGroupByFingerprint(dbConn.getAllItemsIdsForFolderSQL(
                        AI_FOR_YOU, false, SORT_DIRECTION.desc, RuntimeEnvironment.getApplication())));
    }

    private List<Long> currentViewRssItemIds() {
        return longColumn("SELECT RSS_ITEM_ID FROM CURRENT_RSS_ITEM_VIEW ORDER BY _id");
    }

    private List<Long> currentViewOwnIds() {
        return longColumn("SELECT _id FROM CURRENT_RSS_ITEM_VIEW ORDER BY _id");
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
}
