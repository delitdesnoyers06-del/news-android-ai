package de.luhmer.owncloudnewsreader.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import de.luhmer.owncloudnewsreader.ai.engine.AiResponseFormat;
import de.luhmer.owncloudnewsreader.ai.engine.CancelToken;
import de.luhmer.owncloudnewsreader.ai.prompt.AiPrompts;
import de.luhmer.owncloudnewsreader.ai.prompt.AiScoreGrammar;

/**
 * DEEPREVIEW F4 — the {@code {N}} quantifier is gone from the generation path, and the repair turn
 * is no longer forced to re-emit exactly N lines.
 *
 * <p>Two separate defects, both about a grammar being a token mask rather than a plan:</p>
 * <ul>
 *   <li>{@code (?:LINE\n){N}} masks EOS until the Nth line is complete, so a model with nothing
 *       left to say must invent lines, and a decoder with no legal continuation gets an empty mask,
 *       which is a generation error rather than a short answer.</li>
 *   <li>Re-imposing the same grammar on the repair turn means the repair cannot emit only the
 *       missing lines, cannot say less than the turn that just failed, and cannot stop.</li>
 * </ul>
 *
 * <p>The parser stays authoritative in both cases, which is what makes relaxing the grammar safe:
 * {@code ScoreLineParser} drops anything malformed and {@code merge()} keeps the lines already in
 * hand, so a looser turn can only add information.</p>
 */
public class AiScorerResponseFormatTest {

    private static final String SYSTEM = "You rate news articles.";
    private static final String USER = "ARTICLES\n{{articles}}\n\nANSWER\n{{n}} lines in {{lang}}.\n";
    private static final String REPAIR = "That was not the required format. {{n}} lines.";

    private static AiScorer scorer(int batch) {
        return new AiScorer(AiPrompts.of(SYSTEM, USER, REPAIR), "- drt\n",
                Collections.singletonList("drt"), Collections.emptyList(), Locale.ENGLISH, batch);
    }

    private static List<AiScorer.Item> items(int n) {
        List<AiScorer.Item> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            out.add(new AiScorer.Item("k" + i, i, "Title " + i, "Transit Weekly", "excerpt " + i));
        }
        return out;
    }

    private static long deadline() {
        return System.currentTimeMillis() + 60_000L;
    }

    // ---------------------------------------------------------------- the conversation's grammar

    @Test
    public void theBatchGrammarHasNoFixedCountQuantifier() {
        FakeLlm llm = FakeLlm.perfect();
        scorer(4).score(llm, items(4), (item, verdict) -> { }, CancelToken.none(), deadline());

        assertEquals("one conversation for the batch", 1, llm.specGrammars.size());
        String grammar = llm.specGrammars.get(0);
        assertNotNull("a SentencePiece model must still get a grammar", grammar);
        assertEquals("the any-length form, not forBatch(n)",
                AiScoreGrammar.forAnyLength(), grammar);
        assertFalse("a {N} quantifier would mask EOS until the Nth line: " + grammar,
                grammar.contains("{4}"));
    }

    @Test
    public void theSameGrammarIsUsedWhateverTheBatchSize() {
        FakeLlm one = FakeLlm.perfect();
        scorer(1).score(one, items(1), (item, verdict) -> { }, CancelToken.none(), deadline());
        FakeLlm eight = FakeLlm.perfect();
        scorer(8).score(eight, items(8), (item, verdict) -> { }, CancelToken.none(), deadline());
        assertEquals(one.specGrammars.get(0), eight.specGrammars.get(0));
    }

    @Test
    public void anUnconstrainedModelStillGetsNoGrammarAtAll() {
        FakeLlm llm = new FakeLlm((userText, turn) -> "1|2|drt|fine\n", false, 2_588_147_712L);
        scorer(1).score(llm, items(1), (item, verdict) -> { }, CancelToken.none(), deadline());
        assertNull("a BPE model must take the unconstrained path", llm.specGrammars.get(0));
    }

    // ---------------------------------------------------------------------- the per-turn override

    @Test
    public void theFirstTurnUsesTheConversationFormatAndTheRepairTurnDropsIt() {
        // Answers only line 1 of 3 on every turn, so the repair turn always fires.
        FakeLlm llm = new FakeLlm((userText, turn) -> "1|2|drt|only the first line\n");
        scorer(3).score(llm, items(3), (item, verdict) -> { }, CancelToken.none(), deadline());

        assertTrue("expected a first turn and a repair turn, got " + llm.formats.size(),
                llm.formats.size() >= 2);
        assertNull("the first turn takes the conversation's own format", llm.formats.get(0));
        assertSame("the repair turn must be unconstrained",
                AiResponseFormat.NONE, llm.formats.get(1));
    }

    @Test
    public void everyArticleStillGetsAVerdictWithTheRelaxedGrammar() {
        FakeLlm llm = FakeLlm.perfect();
        final List<String> seen = new ArrayList<>();
        AiScorer.Report report = scorer(4).score(llm, items(4),
                (item, verdict) -> seen.add(item.aiKey), CancelToken.none(), deadline());
        assertEquals("the terminal guarantee is unaffected", 4, seen.size());
        assertEquals(4, report.scored);
        assertEquals(0, report.unjudged);
    }

    // ------------------------------------------------------------------------- AiResponseFormat

    @Test
    public void theThreeStatesOfAResponseFormatAreDistinct() {
        assertNull("NONE means 'no constraint'", AiResponseFormat.NONE.regex());
        assertSame("a null pattern collapses to NONE", AiResponseFormat.NONE,
                AiResponseFormat.regex(null));
        assertSame("an empty pattern collapses to NONE", AiResponseFormat.NONE,
                AiResponseFormat.regex(""));
        assertEquals("[0-3]", AiResponseFormat.regex("[0-3]").regex());
    }
}
