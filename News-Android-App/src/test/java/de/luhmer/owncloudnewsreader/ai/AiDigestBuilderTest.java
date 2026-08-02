package de.luhmer.owncloudnewsreader.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The three rules of {@link AiDigestBuilder}: each article once, blocks by {@code -sum(rank)}, and
 * the range filter on <b>selection</b> time.
 */
public class AiDigestBuilderTest {

    private static AiDigestBuilder.Item item(String key, long id, double rank, long selectedAt,
                                             String... themes) {
        AiDigestBuilder.Item i =
                new AiDigestBuilder.Item(key, id, Double.valueOf(rank), selectedAt, themes);
        i.title = "title " + id;
        return i;
    }

    private static List<AiDigestBuilder.Item> list(AiDigestBuilder.Item... items) {
        List<AiDigestBuilder.Item> out = new ArrayList<>();
        for (AiDigestBuilder.Item i : items) {
            out.add(i);
        }
        return out;
    }

    @Test
    public void noArticleAppearsInTwoSections() {
        AiDigestBuilder.Result r = AiDigestBuilder.build(list(
                item("a", 1, 3.0d, 100L, "regulation", "competitors", "on-device"),
                item("b", 2, 2.0d, 100L, "competitors", "regulation")), 0L, 200L);

        Set<String> seen = new HashSet<>();
        int total = 0;
        for (AiDigestBuilder.Block b : r.blocks) {
            for (AiDigestBuilder.Item i : b.items) {
                assertTrue("article " + i.aiKey + " is in two sections", seen.add(i.aiKey));
                total++;
            }
        }
        assertEquals(2, total);
        assertEquals(2, r.itemCount);
        assertEquals("each item sits under themes[0] only", 2, r.blocks.size());
    }

    @Test
    public void itemsSitUnderTheirFirstTheme() {
        AiDigestBuilder.Result r = AiDigestBuilder.build(list(
                item("a", 1, 3.0d, 100L, "regulation", "competitors")), 0L, 200L);
        assertEquals(1, r.blocks.size());
        assertEquals("regulation", r.blocks.get(0).theme);
    }

    @Test
    public void blocksAreOrderedByTheSumOfTheirRankScores() {
        // "few" has 2 items summing to 5.4; "many" has 3 items summing to 3.0.
        AiDigestBuilder.Result r = AiDigestBuilder.build(list(
                item("a", 1, 2.7d, 100L, "few"),
                item("b", 2, 2.7d, 100L, "few"),
                item("c", 3, 1.0d, 100L, "many"),
                item("d", 4, 1.0d, 100L, "many"),
                item("e", 5, 1.0d, 100L, "many")), 0L, 200L);

        assertEquals(2, r.blocks.size());
        assertEquals("three weak articles must not outrank two strong ones",
                "few", r.blocks.get(0).theme);
        assertEquals("many", r.blocks.get(1).theme);
        assertEquals(5.4d, r.blocks.get(0).weight, 1e-9d);
    }

    @Test
    public void theRangeIsOnSelectionTimeNotPublicationDate() {
        AiDigestBuilder.Item oldNewsPickedToday = item("old", 1, 2.0d, 150L, "t");
        oldNewsPickedToday.pubDate = Long.valueOf(1L);            // published three weeks ago
        AiDigestBuilder.Item freshNewsPickedYesterday = item("fresh", 2, 3.0d, 50L, "t");
        freshNewsPickedYesterday.pubDate = Long.valueOf(999L);    // published an hour ago

        AiDigestBuilder.Result r =
                AiDigestBuilder.build(list(oldNewsPickedToday, freshNewsPickedYesterday), 100L, 200L);

        assertEquals(1, r.itemCount);
        assertEquals("old", r.flattened().get(0).aiKey);
    }

    @Test
    public void theLowerBoundIsExclusiveAndTheUpperBoundInclusive() {
        AiDigestBuilder.Result r = AiDigestBuilder.build(list(
                item("onFrom", 1, 1.0d, 100L, "t"),
                item("onTo", 2, 1.0d, 200L, "t")), 100L, 200L);
        assertEquals(1, r.itemCount);
        assertEquals("an item must never land in two consecutive digests",
                "onTo", r.flattened().get(0).aiKey);
    }

    @Test
    public void aFingerprintDuplicateIsCountedOnce() {
        AiDigestBuilder.Result r = AiDigestBuilder.build(list(
                item("dup", 1, 2.9d, 100L, "t"),
                item("dup", 2, 2.9d, 100L, "t")), 0L, 200L);
        assertEquals(1, r.itemCount);
    }

    @Test
    public void untaggedArticlesGetTheirOwnBlockAndItIsLast() {
        AiDigestBuilder.Result r = AiDigestBuilder.build(list(
                item("a", 1, 0.1d, 100L, "regulation"),
                item("b", 2, 3.0d, 100L)), 0L, 200L);

        assertEquals(2, r.blocks.size());
        assertEquals("regulation", r.blocks.get(0).theme);
        assertNull("the untagged block is last however heavy it is", r.blocks.get(1).theme);
        assertEquals(1, r.blocks.get(1).items.size());
    }

    @Test
    public void aSimilarityOnlyRunStillProducesADigest() {
        // No LLM => no themes at all. One untagged block, ranked by similarity.
        AiDigestBuilder.Result r = AiDigestBuilder.build(list(
                item("a", 1, 0.4d, 100L),
                item("b", 2, 0.9d, 100L),
                item("c", 3, 0.2d, 100L)), 0L, 200L);
        assertEquals(1, r.blocks.size());
        assertEquals(3, r.itemCount);
        assertEquals("b", r.flattened().get(0).aiKey);
        assertEquals("c", r.flattened().get(2).aiKey);
    }

    @Test
    public void itemsInsideABlockAreOrderedLikeTheList() {
        AiDigestBuilder.Item a = item("a", 1, 2.0d, 100L, "t");
        a.pubDate = Long.valueOf(10L);
        AiDigestBuilder.Item b = item("b", 2, 2.0d, 100L, "t");
        b.pubDate = Long.valueOf(20L);
        AiDigestBuilder.Item c = item("c", 3, 3.0d, 100L, "t");
        c.pubDate = Long.valueOf(1L);

        AiDigestBuilder.Result r = AiDigestBuilder.build(list(a, b, c), 0L, 200L);
        List<AiDigestBuilder.Item> flat = r.flattened();
        assertEquals("c", flat.get(0).aiKey);   // rank wins
        assertEquals("b", flat.get(1).aiKey);   // then pubDate
        assertEquals("a", flat.get(2).aiKey);
    }

    @Test
    public void anUnrankedArticleSortsLast() {
        AiDigestBuilder.Item nullRank = new AiDigestBuilder.Item("n", 9, null, 100L, "t");
        AiDigestBuilder.Result r =
                AiDigestBuilder.build(list(nullRank, item("a", 1, 0.0d, 100L, "t")), 0L, 200L);
        assertEquals("a", r.flattened().get(0).aiKey);
        assertEquals("n", r.flattened().get(1).aiKey);
    }

    @Test
    public void anEmptyInputIsAnEmptyDigestNotAnException() {
        AiDigestBuilder.Result r = AiDigestBuilder.build(null, 0L, 1L);
        assertTrue(r.isEmpty());
        assertEquals(0, r.blocks.size());
    }

    @Test
    public void themesAreSplitAndNormalisedFromTheStoredColumn() {
        assertEquals(0, AiDigestBuilder.splitThemes(null).size());
        assertEquals(2, AiDigestBuilder.splitThemes("regulation, competitors").size());
        assertEquals("regulation", AiDigestBuilder.splitThemes(" regulation , ,x").get(0));

        AiDigestBuilder.Item i = item("a", 1, 1.0d, 5L, "  ", "Regulation");
        assertEquals("a blank leading theme must not create a blank block",
                "regulation", AiDigestBuilder.primaryTheme(i));
    }
}
