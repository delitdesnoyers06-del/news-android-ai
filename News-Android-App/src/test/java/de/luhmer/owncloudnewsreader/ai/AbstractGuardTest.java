package de.luhmer.owncloudnewsreader.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import de.luhmer.owncloudnewsreader.ai.prompt.AbstractGuard;

/**
 * The abstract is decoration on a digest that is already correct without it, so the interesting
 * assertions are the ones where the guard throws the model's answer away.
 */
public class AbstractGuardTest {

    /** 96 characters of plausible prose — long enough to survive the length floor. */
    private static final String GOOD =
            "Regulators moved on two fronts this week while three competitors shipped in the same "
                    + "fortnight.";

    @Test
    public void aCleanParagraphSurvivesUnchanged() {
        assertEquals(GOOD, AbstractGuard.clean(GOOD));
    }

    @Test
    public void aShortResultIsDroppedEntirely() {
        assertNull(AbstractGuard.clean("Not much happened."));
        assertNull(AbstractGuard.clean(""));
        assertNull(AbstractGuard.clean(null));
    }

    @Test
    public void aLetterlessResultIsDroppedEntirely() {
        // Long enough to pass the length floor, and still not prose.
        assertNull(AbstractGuard.clean("... --- ... --- ... --- ... --- ... --- ... --- ... --- "
                + "... --- ... --- ... --- ... ---"));
    }

    @Test
    public void aPreambleIsStrippedAndWhatIsLeftIsJudgedOnItsOwn() {
        assertEquals(GOOD, AbstractGuard.clean("Here is a summary of the items:\n\n" + GOOD));
        assertEquals(GOOD, AbstractGuard.clean("Sure, here you go.\n" + GOOD));
        assertEquals(GOOD, AbstractGuard.clean("Voici le résumé :\n" + GOOD));
    }

    @Test
    public void aPreambleWithNothingBehindItIsDropped() {
        assertNull("stripping the preamble must not leave a stub abstract",
                AbstractGuard.clean("Here is a summary of the items you kept today:"));
    }

    @Test
    public void markdownHeadingsAreStripped() {
        assertEquals(GOOD, AbstractGuard.clean("## Digest\n\n" + GOOD));
        assertEquals(GOOD, AbstractGuard.clean("# Today\n### Summary\n" + GOOD));
    }

    @Test
    public void codeFencesAreStrippedButTheirContentIsKept() {
        assertEquals(GOOD, AbstractGuard.clean("```\n" + GOOD + "\n```"));
        assertEquals(GOOD, AbstractGuard.clean("```markdown\n" + GOOD + "\n```\n"));
    }

    @Test
    public void trailingPleasantriesAreStripped() {
        assertEquals(GOOD, AbstractGuard.clean(GOOD + "\n\nLet me know if you want more detail."));
        assertEquals(GOOD, AbstractGuard.clean(GOOD + "\nHope this helps!"));
    }

    @Test
    public void atMostSixSentencesSurvive() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            sb.append("Sentence number ").append(i).append(" says something here. ");
        }
        String cleaned = AbstractGuard.clean(sb.toString());
        assertNotNull(cleaned);
        assertTrue(cleaned.contains("number 6"));
        assertFalse(cleaned.contains("number 7"));
    }

    @Test
    public void aDecimalPointIsNotASentenceEnd() {
        String text = "Funding rose 3.5 percent across the sector this quarter, which is the first "
                + "increase in two years and the largest since 2021.";
        assertEquals(text, AbstractGuard.clean(text));
    }

    @Test
    public void lineBreaksInsideTheParagraphCollapseToSpaces() {
        String cleaned = AbstractGuard.clean("Regulators moved on two fronts this week\n"
                + "while three competitors shipped in the same fortnight.");
        assertEquals("Regulators moved on two fronts this week while three competitors shipped in "
                + "the same fortnight.", cleaned);
    }

    @Test
    public void theItemsBlockIsCappedAndSaysHowManyItDropped() {
        List<String[]> rows = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            rows.add(new String[]{"Article number " + i, "because of reason " + i});
        }
        String block = AbstractGuard.itemsBlock(rows);
        assertTrue(block.contains("- **Article number 0** — because of reason 0"));
        assertTrue("the model must be told it saw a subset", block.contains("more items)"));
        int lines = block.split("\n").length;
        assertTrue(lines <= AbstractGuard.MAX_ITEM_LINES + 1);
    }

    @Test
    public void anItemWithNoWhyLineStillRenders() {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"A title", ""});
        assertEquals("- **A title**\n", AbstractGuard.itemsBlock(rows));
    }

    @Test
    public void theBriefBlockIsOmittedWithItsHeadingWhenThereIsNoNote() {
        assertEquals("", AbstractGuard.briefBlock(null));
        assertEquals("", AbstractGuard.briefBlock("   "));
        assertTrue(AbstractGuard.briefBlock("- transport").contains("EXTRA INSTRUCTION"));
    }
}
