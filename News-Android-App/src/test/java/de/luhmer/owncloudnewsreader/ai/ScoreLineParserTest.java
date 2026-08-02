package de.luhmer.owncloudnewsreader.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import de.luhmer.owncloudnewsreader.ai.prompt.ScoreLineParser;

/**
 * The whole guarantee table from {@code docs/ai/02_product_prompts.md} §5.1, plus the property that
 * matters most: <b>this parser never throws</b>, for any input at all.
 */
public class ScoreLineParserTest {

    private static final List<String> VOCAB =
            Arrays.asList("drt", "operators", "competitors", "electrification");

    private static Map<Integer, ScoreLineParser.Line> parse(String raw, int n) {
        return ScoreLineParser.parse(raw, n, VOCAB);
    }

    @Test
    public void happyPath() {
        Map<Integer, ScoreLineParser.Line> out = parse(
                "1|3|drt,operators|new dial-a-ride contract\n"
                        + "2|0|-|off topic\n", 2);
        assertEquals(2, out.size());
        assertEquals(3, out.get(1).score);
        assertEquals(Arrays.asList("drt", "operators"), out.get(1).themes);
        assertEquals("new dial-a-ride contract", out.get(1).why);
        assertEquals(0, out.get(2).score);
        assertTrue(out.get(2).themes.isEmpty());
        assertTrue(out.get(2).flags.isEmpty());
    }

    @Test
    public void bracketedIndexAndSurroundingSpaceAreAccepted() {
        Map<Integer, ScoreLineParser.Line> out = parse("  [1] | 2 | drt | why  \n", 1);
        assertEquals(1, out.size());
        assertEquals(2, out.get(1).score);
        assertEquals(Collections.singletonList("drt"), out.get(1).themes);
        assertEquals("why", out.get(1).why);
    }

    @Test
    public void nonMatchingLinesAreIgnoredNotFatal() {
        Map<Integer, ScoreLineParser.Line> out = parse(
                "Here are the scores:\n"
                        + "1|3|drt|good\n"
                        + "\n"
                        + "Let me know if you need anything else!\n", 1);
        assertEquals(1, out.size());
        assertEquals(3, out.get(1).score);
    }

    @Test
    public void duplicateIndexFirstWins() {
        Map<Integer, ScoreLineParser.Line> out = parse("1|3|drt|first\n1|0|-|second\n", 1);
        assertEquals(1, out.size());
        assertEquals(3, out.get(1).score);
        assertEquals("first", out.get(1).why);
    }

    @Test
    public void indexOutsideRangeIsDroppedNeverAppended() {
        Map<Integer, ScoreLineParser.Line> out = parse("5|3|drt|extra\n1|1|-|real\n", 4);
        assertEquals(1, out.size());
        assertNotNull(out.get(1));
        assertEquals(Arrays.asList(2, 3, 4), ScoreLineParser.missing(out, 4));
    }

    @Test
    public void scoreIsCoercedThenClamped() {
        // doc 02 §5.1: a float is rounded, not dropped, so "2.5" is 3 and not a lost article
        assertEquals(3, parse("1|2.5|-|x\n", 1).get(1).score);
        assertEquals(2, parse("1|2.4|-|x\n", 1).get(1).score);
        assertEquals(3, parse("1|7|-|x\n", 1).get(1).score);
        assertEquals(0, parse("1|-1|-|x\n", 1).get(1).score);
        assertEquals(0, parse("1|banana|-|x\n", 1).get(1).score);
        assertEquals(0, parse("1||-|x\n", 1).get(1).score);
    }

    @Test
    public void tagsAreNormalisedFilteredDedupedAndCapped() {
        ScoreLineParser.Line l = parse(
                "1|3| DRT , Operators , invented , Drt , competitor |x\n", 1).get(1);
        assertEquals(Arrays.asList("drt", "operators"), l.themes);
        assertEquals(Collections.singletonList("competitor"), l.flags);
        // the raw list keeps everything so the invented-tag rate stays measurable
        assertEquals(5, l.rawTags.size());
    }

    @Test
    public void noMoreThanSixTagsSurvive() {
        List<String> wide = Arrays.asList("a", "b", "c", "d", "e", "f", "g", "h");
        Map<Integer, ScoreLineParser.Line> out =
                ScoreLineParser.parse("1|3|a,b,c,d,e,f,g,h|x\n", 1, wide);
        assertEquals(ScoreLineParser.MAX_TAGS,
                out.get(1).themes.size() + out.get(1).flags.size());
    }

    @Test
    public void themeOrderIsPreservedBecauseTheDigestGroupsOnTheFirstOne() {
        ScoreLineParser.Line l = parse("1|3|operators,drt|x\n", 1).get(1);
        assertEquals("operators", l.themes.get(0));
        assertEquals("operators,drt", l.themesCsv());
    }

    @Test
    public void whyMayBeEmptyAndAStrayPipeLandsInsideIt() {
        assertEquals("", parse("1|3|drt|\n", 1).get(1).why);
        assertEquals("a | b", parse("1|3|drt|a | b\n", 1).get(1).why);
    }

    @Test
    public void longWhyIsTruncatedAtAWordBoundary() {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 400) {
            sb.append("word ");
        }
        String why = parse("1|3|drt|" + sb + "\n", 1).get(1).why;
        assertTrue(why.length() <= ScoreLineParser.MAX_WHY_CHARS);
        assertTrue(why.endsWith("…"));
    }

    @Test
    public void fencesBomCrlfNbspAndFullwidthPipeAreNormalised() {
        String raw = "﻿```\r\n1｜3｜drt｜a b\r\n```";
        Map<Integer, ScoreLineParser.Line> out = parse(raw, 1);
        assertEquals(1, out.size());
        assertEquals(3, out.get(1).score);
        assertEquals("a b", out.get(1).why);
    }

    @Test
    public void anUnclosedFenceStripsOnlyTheOpener() {
        Map<Integer, ScoreLineParser.Line> out = parse("```text\n1|2|drt|x\n", 1);
        assertEquals(1, out.size());
        assertEquals(2, out.get(1).score);
    }

    /**
     * The doc says a whole-prompt echo is a parse failure. In practice the prompt contains its own
     * worked example line, which is byte-identical in shape to a real answer — so the honest
     * assertion is the one that matters operationally: an echo never yields a <b>complete</b> batch,
     * so the repair ladder always fires.
     */
    @Test
    public void wholePromptEchoNeverProducesACompleteBatch() {
        String prompt = "ANSWER\nWrite exactly 4 lines, one per article, in this format:\n\n"
                + "n|score|tags|why\n\nExample line:\n"
                + "2|3|regulation,on-demand-transport|new EU rule on demand-responsive services\n";
        Map<Integer, ScoreLineParser.Line> out = parse(prompt, 4);
        assertTrue(out.size() < 4);
        assertFalse(ScoreLineParser.missing(out, 4).isEmpty());
    }

    @Test
    public void nullBlankAndBinaryNoiseNeverThrow() {
        assertTrue(parse(null, 4).isEmpty());
        assertTrue(parse("", 4).isEmpty());
        assertTrue(parse("   \n\n  ", 4).isEmpty());
        Random rnd = new Random(1234);
        for (int i = 0; i < 500; i++) {
            StringBuilder sb = new StringBuilder();
            int len = rnd.nextInt(200);
            for (int j = 0; j < len; j++) {
                sb.append((char) rnd.nextInt(0xFFFF));
            }
            // The assertion is simply that this line returns.
            assertNotNull(parse(sb.toString(), 4));
        }
    }

    @Test
    public void mergeKeepsTheFirstAttemptsLines() {
        Map<Integer, ScoreLineParser.Line> first = parse("1|3|drt|first\n", 2);
        Map<Integer, ScoreLineParser.Line> repair = parse("1|0|-|repaired\n2|2|drt|new\n", 2);
        Map<Integer, ScoreLineParser.Line> merged = ScoreLineParser.merge(first, repair);
        assertEquals(2, merged.size());
        assertEquals("first", merged.get(1).why);
        assertEquals(2, merged.get(2).score);
        assertTrue(ScoreLineParser.missing(merged, 2).isEmpty());
    }
}
