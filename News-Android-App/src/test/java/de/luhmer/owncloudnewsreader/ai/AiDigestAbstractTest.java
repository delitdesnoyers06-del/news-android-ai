package de.luhmer.owncloudnewsreader.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.luhmer.owncloudnewsreader.ai.prompt.PromptTemplate;

/**
 * The abstract stage end to end against a scripted model: what reaches the digest, and what the
 * guard throws away before it gets there.
 */
@RunWith(RobolectricTestRunner.class)
public class AiDigestAbstractTest {

    private static final String SYSTEM = "You write in {{lang}}.";
    private static final String USER = "ITEMS KEPT\n{{items}}\n{{brief}}\nTASK\nWrite in {{lang}}.";

    private static final String GOOD_ANSWER =
            "Regulators moved on two fronts this week while three competitors shipped in the same "
                    + "fortnight.";

    private static List<String[]> items(int n) {
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new String[]{"Article " + i, "because " + i});
        }
        return out;
    }

    private static String generate(FakeLlm llm, List<String[]> items, String note) {
        return AiAbstract.generate(llm, PromptTemplate.of(SYSTEM), PromptTemplate.of(USER),
                items, note, Locale.ENGLISH, null);
    }

    @Test
    public void aGoodAnswerBecomesTheAbstract() {
        FakeLlm llm = new FakeLlm((text, turn) -> GOOD_ANSWER);
        assertEquals(GOOD_ANSWER, generate(llm, items(6), null));
        assertEquals("one conversation, opened and closed", 1, llm.conversationsOpened);
        assertEquals(1, llm.conversationsClosed);
    }

    @Test
    public void garbageProducesNoAbstractAndNoRepairTurn() {
        FakeLlm llm = FakeLlm.garbage();
        assertNull(generate(llm, items(6), null));
        assertEquals("the abstract never gets a second turn", 1, llm.prompts.size());
    }

    @Test
    public void anExplodingModelProducesNoAbstractAndNoException() {
        assertNull(generate(FakeLlm.exploding(), items(6), null));
    }

    @Test
    public void anEmptyDigestNeverReachesTheModel() {
        FakeLlm llm = new FakeLlm((text, turn) -> GOOD_ANSWER);
        assertNull(generate(llm, new ArrayList<>(), null));
        assertEquals(0, llm.conversationsOpened);
    }

    @Test
    public void theItemsBlockReachesThePrompt() {
        FakeLlm llm = new FakeLlm((text, turn) -> GOOD_ANSWER);
        generate(llm, items(3), null);
        String prompt = llm.prompts.get(0);
        assertTrue(prompt.contains("- **Article 0** — because 0"));
        assertTrue(prompt.contains("- **Article 2** — because 2"));
    }

    @Test
    public void theInterestsNoteIsInjectedAsTheBriefOnlyWhenThereIsOne() {
        FakeLlm withNote = new FakeLlm((text, turn) -> GOOD_ANSWER);
        generate(withNote, items(3), "- public transport regulation");
        assertTrue(withNote.prompts.get(0).contains("EXTRA INSTRUCTION FROM THE READER"));

        FakeLlm without = new FakeLlm((text, turn) -> GOOD_ANSWER);
        generate(without, items(3), "   ");
        assertTrue("the heading is omitted with its content",
                !without.prompts.get(0).contains("EXTRA INSTRUCTION"));
    }

    @Test
    public void aWrappedAnswerIsUnwrappedRatherThanDropped() {
        FakeLlm llm = new FakeLlm((text, turn) ->
                "Here is the summary:\n\n```\n" + GOOD_ANSWER + "\n```\n\nHope this helps!");
        assertEquals(GOOD_ANSWER, generate(llm, items(6), null));
    }

    @Test
    public void aStubAnswerIsDroppedEntirely() {
        FakeLlm llm = new FakeLlm((text, turn) -> "Here is the summary of your articles:");
        assertNull("a bad abstract is worse than none", generate(llm, items(6), null));
    }

    @Test
    public void theFirstSentenceIsWhatTheNotificationWouldQuote() {
        // Mirrors AiDigestWorker.firstSentence without pulling in the mlGemma source set.
        String text = GOOD_ANSWER + " A second sentence follows.";
        String cleaned = de.luhmer.owncloudnewsreader.ai.prompt.AbstractGuard.clean(text);
        assertNotNull(cleaned);
        assertTrue(cleaned.startsWith("Regulators"));
    }
}
