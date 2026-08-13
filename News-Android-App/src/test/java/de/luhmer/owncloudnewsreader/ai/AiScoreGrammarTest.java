package de.luhmer.owncloudnewsreader.ai;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.regex.Pattern;

import de.luhmer.owncloudnewsreader.ai.prompt.AiScoreGrammar;

/**
 * The grammar is the decoder-side half of the contract. These tests do not prove LLGuidance accepts
 * it on device (that needs hardware — PLAN S2); they prove the pattern we would hand it is a valid
 * regex that admits exactly the shape the parser expects and rejects the shapes it must.
 */
public class AiScoreGrammarTest {

    private static Pattern p(int n) {
        Pattern pattern = AiScoreGrammar.compileAsJavaRegexOrNull(AiScoreGrammar.forBatch(n));
        assertNotNull("grammar for " + n + " must compile", pattern);
        return pattern;
    }

    @Test
    public void compilesForEveryPlausibleBatchSize() {
        for (int n = 1; n <= 16; n++) {
            assertNotNull(AiScoreGrammar.compileAsJavaRegexOrNull(AiScoreGrammar.forBatch(n)));
        }
        assertNotNull(AiScoreGrammar.compileAsJavaRegexOrNull(AiScoreGrammar.forAnyLength()));
    }

    @Test
    public void matchesValidOutput() {
        String out = "1|3|drt,operators|new dial-a-ride contract\n"
                + "2|0|-|off topic\n"
                + "3|1|drt|passing mention\n"
                + "4|2|regulation|tender rules change\n";
        assertTrue(p(4).matcher(out).matches());
    }

    @Test
    public void rejectsAMissingLine() {
        String out = "1|3|drt|a\n2|0|-|b\n3|1|drt|c\n";
        assertFalse(p(4).matcher(out).matches());
    }

    @Test
    public void rejectsAScoreOutsideZeroToThree() {
        assertFalse(p(1).matcher("1|4|drt|a\n").matches());
        assertFalse(p(1).matcher("1|-1|drt|a\n").matches());
    }

    @Test
    public void rejectsAPipeInsideWhy() {
        assertFalse(p(1).matcher("1|3|drt|a | b\n").matches());
    }

    @Test
    public void rejectsAnUppercaseOrSpacedTagField() {
        assertFalse(p(1).matcher("1|3|DRT|a\n").matches());
        assertFalse(p(1).matcher("1|3|drt operators|a\n").matches());
    }

    @Test
    public void rejectsAMissingTrailingNewline() {
        assertFalse(p(1).matcher("1|3|drt|a").matches());
    }

    @Test
    public void anEmptyTagFieldAndAnEmptyWhyAreLegal() {
        assertTrue(p(1).matcher("1|0||\n").matches());
    }

    @Test
    public void anyLengthFormAcceptsOneOrMoreLines() {
        Pattern any = AiScoreGrammar.compileAsJavaRegexOrNull(AiScoreGrammar.forAnyLength());
        assertNotNull(any);
        assertTrue(any.matcher("1|3|drt|a\n").matches());
        assertTrue(any.matcher("1|3|drt|a\n2|0|-|b\n").matches());
        assertFalse(any.matcher("").matches());
    }

    @Test
    public void anInvalidPatternDegradesToNullRatherThanThrowing() {
        assertNull(AiScoreGrammar.compileAsJavaRegexOrNull("(unclosed"));
    }
}
