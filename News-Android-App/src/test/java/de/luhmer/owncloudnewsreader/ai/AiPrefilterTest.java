package de.luhmer.owncloudnewsreader.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.luhmer.owncloudnewsreader.database.ai.AiScoreStore;

/**
 * The cold / warm / unscored-room matrix. Pure JVM — the prefilter is deliberately free of Android.
 */
public class AiPrefilterTest {

    private static AiPrefilter.Item item(String key, Double sim, Long pubDate) {
        return new AiPrefilter.Item(key, key.hashCode(), sim, pubDate, pubDate);
    }

    private static List<String> keys(List<AiPrefilter.Item> items) {
        List<String> out = new ArrayList<>();
        for (AiPrefilter.Item i : items) {
            out.add(i.aiKey);
        }
        return out;
    }

    private static Map<String, String> reasons(List<AiPrefilter.Dropped> dropped) {
        Map<String, String> out = new LinkedHashMap<>();
        for (AiPrefilter.Dropped d : dropped) {
            out.put(d.item.aiKey, d.reason);
        }
        return out;
    }

    // ------------------------------------------------------------------ the warm-up gate

    @Test
    public void eitherCentroidMissingIsCold() {
        float[] v = {1f, 0f};
        assertTrue(AiPrefilter.isCold(null, v, 0, 99, 99));
        assertTrue(AiPrefilter.isCold(v, null, 99, 0, 99));
        assertFalse(AiPrefilter.isCold(v, v, 3, 3, 10));
    }

    @Test
    public void theWarmUpGateIsThreeEachAndTenTotal() {
        float[] v = {1f, 0f};
        assertTrue("2 liked is still cold", AiPrefilter.isCold(v, v, 2, 8, 10));
        assertTrue("2 rejected is still cold", AiPrefilter.isCold(v, v, 8, 2, 10));
        assertTrue("9 decisions is still cold", AiPrefilter.isCold(v, v, 5, 4, 9));
        assertFalse("3/3/10 is exactly warm", AiPrefilter.isCold(v, v, 3, 3, 10));
    }

    // ------------------------------------------------------------------ cold

    @Test
    public void coldSelectsTheMostRecentAndIgnoresSimilarityEntirely() {
        List<AiPrefilter.Item> in = Arrays.asList(
                item("old-but-loved", 0.99d, 1_000L),
                item("recent-but-hated", -0.99d, 9_000L),
                item("middle", null, 5_000L));

        AiPrefilter.Outcome out = AiPrefilter.run(in, true, 2);

        assertTrue(out.cold);
        assertEquals(Arrays.asList("recent-but-hated", "middle"), keys(out.selected));
        assertEquals(AiScoreStore.REASON_BELOW_TOP_K, reasons(out.dropped).get("old-but-loved"));
    }

    @Test
    public void coldPutsUndatedArticlesLast() {
        List<AiPrefilter.Item> in = Arrays.asList(
                item("undated", null, null),
                item("dated", null, 1L));

        AiPrefilter.Outcome out = AiPrefilter.run(in, true, 1);

        assertEquals("SQLite and Java both sort NULL first by default - undated must be LAST",
                Arrays.asList("dated"), keys(out.selected));
    }

    @Test
    public void coldNeverUsesTheLowSimilarityReason() {
        List<AiPrefilter.Item> in = Arrays.asList(
                item("a", -5d, 3L), item("b", -5d, 2L), item("c", -5d, 1L));

        AiPrefilter.Outcome out = AiPrefilter.run(in, true, 1);

        for (AiPrefilter.Dropped d : out.dropped) {
            assertEquals(AiScoreStore.REASON_BELOW_TOP_K, d.reason);
        }
    }

    // ------------------------------------------------------------------ warm

    @Test
    public void warmKeepsAboveTheFloorSortedBySimilarity() {
        List<AiPrefilter.Item> in = Arrays.asList(
                item("low", 0.06d, 9_000L),
                item("high", 0.90d, 1_000L),
                item("mid", 0.50d, 5_000L));

        AiPrefilter.Outcome out = AiPrefilter.run(in, false, 3);

        assertFalse(out.cold);
        assertEquals(Arrays.asList("high", "mid", "low"), keys(out.selected));
        assertTrue(out.dropped.isEmpty());
    }

    @Test
    public void warmDropsBelowTheFloorAsLowSimilarity() {
        List<AiPrefilter.Item> in = Arrays.asList(
                item("keep", AiSimilarity.SIM_FLOOR, 1L),
                item("drop", AiSimilarity.SIM_FLOOR - 1e-6, 2L));

        AiPrefilter.Outcome out = AiPrefilter.run(in, false, 10);

        assertEquals(Arrays.asList("keep"), keys(out.selected));
        assertEquals(AiScoreStore.REASON_LOW_SIMILARITY, reasons(out.dropped).get("drop"));
    }

    @Test
    public void warmCapsAtTopKWithBelowTopK() {
        List<AiPrefilter.Item> in = Arrays.asList(
                item("a", 0.9d, 1L), item("b", 0.8d, 1L), item("c", 0.7d, 1L));

        AiPrefilter.Outcome out = AiPrefilter.run(in, false, 2);

        assertEquals(Arrays.asList("a", "b"), keys(out.selected));
        assertEquals(AiScoreStore.REASON_BELOW_TOP_K, reasons(out.dropped).get("c"));
    }

    @Test
    public void similarityTiesAreBrokenByRecency() {
        List<AiPrefilter.Item> in = Arrays.asList(
                item("older", 0.5d, 1_000L), item("newer", 0.5d, 9_000L));

        AiPrefilter.Outcome out = AiPrefilter.run(in, false, 2);

        assertEquals(Arrays.asList("newer", "older"), keys(out.selected));
    }

    // ------------------------------------------------------------------ THE clause

    @Test
    public void unembeddedArticlesFillTheRoomTheScoredOnesLeft() {
        List<AiPrefilter.Item> in = Arrays.asList(
                item("scored", 0.9d, 1_000L),
                item("unscored-new", null, 9_000L),
                item("unscored-old", null, 2_000L));

        AiPrefilter.Outcome out = AiPrefilter.run(in, false, 3);

        assertEquals("the room left by the scored article must be filled by recency",
                Arrays.asList("scored", "unscored-new", "unscored-old"), keys(out.selected));
        assertTrue(out.dropped.isEmpty());
    }

    @Test
    public void unembeddedArticlesAreNeverCalledLowSimilarity() {
        List<AiPrefilter.Item> in = Arrays.asList(
                item("scored-a", 0.9d, 1L), item("scored-b", 0.8d, 1L),
                item("unscored", null, 9_000L));

        AiPrefilter.Outcome out = AiPrefilter.run(in, false, 2);

        assertEquals(Arrays.asList("scored-a", "scored-b"), keys(out.selected));
        assertEquals("an unembedded article is unjudgeable, not dissimilar - saying "
                        + "low_similarity would be a lie about it",
                AiScoreStore.REASON_BELOW_TOP_K, reasons(out.dropped).get("unscored"));
    }

    @Test
    public void unembeddedArticlesGetNoRoomWhenTheScoredOnesFillTheBudget() {
        List<AiPrefilter.Item> in = Arrays.asList(
                item("s1", 0.9d, 1L), item("s2", 0.8d, 1L), item("unscored", null, 9_000L));

        AiPrefilter.Outcome out = AiPrefilter.run(in, false, 2);

        assertEquals(2, out.selected.size());
        assertEquals(1, out.dropped.size());
    }

    @Test
    public void unembeddedArticlesDoNotDisplaceScoredOnes() {
        // The recency of the unembedded article is far higher; it must still queue behind every
        // above-floor scored article, because it fills REMAINING room and does not compete for it.
        List<AiPrefilter.Item> in = Arrays.asList(
                item("unscored-newest", null, 9_999L),
                item("scored-ancient", 0.06d, 1L));

        AiPrefilter.Outcome out = AiPrefilter.run(in, false, 2);

        assertEquals(Arrays.asList("scored-ancient", "unscored-newest"), keys(out.selected));
    }

    // ------------------------------------------------------------------ edges

    @Test
    public void anEmptyCandidateSetIsNotAnError() {
        AiPrefilter.Outcome out = AiPrefilter.run(new ArrayList<>(), false, 30);
        assertTrue(out.selected.isEmpty());
        assertTrue(out.dropped.isEmpty());

        AiPrefilter.Outcome nullOut = AiPrefilter.run(null, true, 30);
        assertTrue(nullOut.selected.isEmpty());
    }

    @Test
    public void aTopKOfZeroStillSelectsOne() {
        List<AiPrefilter.Item> in = Arrays.asList(item("a", 0.9d, 1L), item("b", 0.8d, 1L));
        AiPrefilter.Outcome out = AiPrefilter.run(in, false, 0);
        assertEquals("a budget of zero would empty the folder forever", 1, out.selected.size());
    }

    @Test
    public void everyCandidateEndsUpInExactlyOneBucket() {
        List<AiPrefilter.Item> in = Arrays.asList(
                item("a", 0.9d, 5L), item("b", -0.9d, 4L), item("c", null, 3L),
                item("d", 0.05d, 2L), item("e", null, null));

        for (boolean cold : new boolean[]{true, false}) {
            for (int k = 1; k <= 6; k++) {
                AiPrefilter.Outcome out = AiPrefilter.run(in, cold, k);
                assertEquals("no article may be lost or duplicated (cold=" + cold + " k=" + k + ")",
                        in.size(), out.selected.size() + out.dropped.size());
            }
        }
    }
}
