package de.luhmer.owncloudnewsreader.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.luhmer.owncloudnewsreader.ai.prompt.AiInterests;
import de.luhmer.owncloudnewsreader.ai.prompt.AiPromptBuilder;
import de.luhmer.owncloudnewsreader.ai.prompt.PromptTemplate;

/** Substitution semantics and every cap the scoring prompt depends on. */
public class PromptBuilderTest {

    // ---- single-pass substitution ------------------------------------------------------------

    /**
     * The whole reason {@code PromptTemplate} is not three chained {@code String.replace} calls.
     * {@code {{articles}}} is scraped text; if a value were re-scanned, an article title containing
     * {@code {{interests}}} would have the reader's private note spliced into it.
     */
    @Test
    public void aValueContainingAPlaceholderIsNotReSubstituted() {
        PromptTemplate t = PromptTemplate.of("A={{a}} B={{b}}");
        Map<String, String> vars = PromptTemplate.vars("a", "{{b}}", "b", "SECRET");
        assertEquals("A={{b}} B=SECRET", t.render(vars));
    }

    @Test
    public void substitutionIsOrderIndependent() {
        PromptTemplate t = PromptTemplate.of("{{x}}{{y}}");
        assertEquals("{{y}}{{x}}",
                t.render(PromptTemplate.vars("x", "{{y}}", "y", "{{x}}")));
    }

    @Test
    public void anUnknownPlaceholderIsLeftVisibleRatherThanBlanked() {
        assertEquals("hi {{nobody}}",
                PromptTemplate.of("hi {{nobody}}").render(PromptTemplate.vars("other", "x")));
        assertTrue(PromptTemplate.hasUnsubstituted("hi {{nobody}}"));
        assertFalse(PromptTemplate.hasUnsubstituted("hi there"));
    }

    @Test
    public void anUnterminatedPlaceholderIsCopiedVerbatim() {
        assertEquals("a {{b", PromptTemplate.of("a {{b").render(PromptTemplate.vars("b", "X")));
    }

    // ---- caps ---------------------------------------------------------------------------------

    @Test
    public void interestsAreCutAtTheLastNewlineBeforeTheCap() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("- topic number ").append(i).append('\n');
        }
        String out = AiPromptBuilder.interests(sb.toString());
        assertTrue(out.length() <= AiPromptBuilder.INTERESTS_MAX_CHARS);
        // Never cut mid-line: a half-sentence is a different claim.
        assertTrue(out.endsWith(Integer.toString(countLines(out) - 1)));
    }

    private static int countLines(String s) {
        return s.split("\n").length;
    }

    @Test
    public void theCorrectionsBlockDisappearsEntirelyWhenEmpty() {
        assertEquals("", AiPromptBuilder.corrections(null));
        assertEquals("", AiPromptBuilder.corrections(new ArrayList<>()));
    }

    @Test
    public void atMostThreeCorrectionsAreRendered() {
        List<AiPromptBuilder.Correction> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(new AiPromptBuilder.Correction("title " + i, 1, true));
        }
        String out = AiPromptBuilder.corrections(rows);
        assertTrue(out.contains("RECENT CORRECTIONS"));
        int n = 0;
        for (String line : out.split("\n")) {
            if (line.startsWith("- title")) {
                n++;
            }
        }
        assertEquals(AiPromptBuilder.MAX_CORRECTIONS, n);
    }

    @Test
    public void correctionTitlesAreSanitisedAndCapped() {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 400) {
            sb.append("x");
        }
        String out = AiPromptBuilder.corrections(Collections.singletonList(
                new AiPromptBuilder.Correction("a|b\nc" + sb, 3, false)));
        assertFalse(out.contains("|b"));
        assertTrue(out.contains("the reader dismissed it"));
    }

    @Test
    public void articlesAreTwoLinesEachIndexedFromOne() {
        String out = AiPromptBuilder.articles(Arrays.asList(
                new AiPromptBuilder.Article("First", "Feed A", "Body one"),
                new AiPromptBuilder.Article("Second", "Feed B", "")));
        String[] lines = out.split("\n");
        assertEquals("[1] First", lines[0]);
        assertEquals("(Feed A) Body one", lines[1]);
        assertEquals("", lines[2]);
        assertEquals("[2] Second", lines[3]);
        // An empty excerpt gets the literal the prompt tells the model to expect.
        assertEquals("(Feed B) " + AiPromptBuilder.NO_TEXT, lines[4]);
    }

    @Test
    public void pipesAndNewlinesAreStrippedFromTitles() {
        String out = AiPromptBuilder.articles(Collections.singletonList(
                new AiPromptBuilder.Article("a|b\nc\td", "s", "e")));
        assertEquals("[1] ab c d", out.split("\n")[0]);
    }

    @Test
    public void excerptsAreCappedAtSixHundredChars() {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < 2000) {
            sb.append("abcdefghij");
        }
        String out = AiPromptBuilder.articles(Collections.singletonList(
                new AiPromptBuilder.Article("t", "s", sb.toString())));
        String body = out.split("\n")[1];
        assertTrue(body.length() <= AiPromptBuilder.EXCERPT_MAX_CHARS + 4);
    }

    @Test
    public void theTagBlockAlwaysCarriesTheThreeFlags() {
        String tags = AiPromptBuilder.tags(Arrays.asList("DRT", "Public Operators"));
        assertTrue(tags.contains("- drt\n"));
        assertTrue(tags.contains("- public-operators\n"));
        assertTrue(tags.contains("- competitor\n"));
        assertTrue(tags.contains("- regulation\n"));
        assertTrue(tags.contains("- customer\n"));
    }

    /** "French", never "fr-FR": a 1B model handles the English language name far better. */
    @Test
    public void languageIsTheEnglishNameNotABcp47Tag() {
        assertEquals("French", AiPromptBuilder.languageName(Locale.FRANCE));
        assertEquals("German", AiPromptBuilder.languageName(Locale.GERMANY));
        assertEquals("English", AiPromptBuilder.languageName(Locale.UK));
    }

    // ---- derived vocabulary -------------------------------------------------------------------

    @Test
    public void themeSlugsAreDerivedDeterministicallyFromTheNote() {
        List<String> slugs = AiInterests.themeSlugs(
                "- demand-responsive transport (DRT), on-demand buses\n"
                        + "- public transport operators and authorities in France\n"
                        + "\n"
                        + "- fleet electrification for buses\n");
        assertEquals(Arrays.asList("demand-responsive-transport", "public-transport-operators",
                "fleet-electrification-for"), slugs);
        assertEquals(slugs, AiInterests.themeSlugs(
                "- demand-responsive transport (DRT), on-demand buses\n"
                        + "- public transport operators and authorities in France\n"
                        + "\n"
                        + "- fleet electrification for buses\n"));
    }

    @Test
    public void anEmptyNoteYieldsNoThemesAndThatIsAWorkingConfiguration() {
        assertTrue(AiInterests.themeSlugs(null).isEmpty());
        assertTrue(AiInterests.themeSlugs("").isEmpty());
    }

    @Test
    public void scoreVarsCoverEveryPlaceholderOfTheRealTemplate() {
        String template = "INTERESTS\n{{interests}}\n\nTAGS\n{{tags}}{{corrections}}ARTICLES\n"
                + "{{articles}}\n\nANSWER\n{{n}} lines in {{lang}}.\n";
        Map<String, String> vars = AiPromptBuilder.scoreVars("- drt\n",
                Collections.singletonList("drt"), null,
                Collections.singletonList(new AiPromptBuilder.Article("t", "s", "e")),
                Locale.UK);
        String rendered = PromptTemplate.of(template).render(vars);
        assertFalse(PromptTemplate.hasUnsubstituted(rendered));
        assertTrue(rendered.contains("[1] t"));
        assertTrue(rendered.contains("1 lines in English."));
    }
}
