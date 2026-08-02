package de.luhmer.owncloudnewsreader.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.luhmer.owncloudnewsreader.ai.prompt.AiPromptBuilder;
import de.luhmer.owncloudnewsreader.ai.prompt.PromptTemplate;
import de.luhmer.owncloudnewsreader.ai.prompt.ScoreLineParser;

/**
 * The §6 eval fixture, checked <b>structurally</b> against a recorded response.
 *
 * <p>Two gates, and only one of them can run here:</p>
 * <ul>
 *   <li><b>Format gate (this test, every build):</b> 12 distinct indices 1..12, none missing, none
 *       extra; every score in 0..3 after clamping; every surviving tag inside the closed vocabulary;
 *       {@code why} present where the fixture demands it and never over 160 chars; items 5 and 6
 *       produce a line at all, because silence is worse than a zero.</li>
 *   <li><b>Calibration gate (on device, per model):</b> &ge; 9/12 in band plus the ordering
 *       assertions. It needs hardware and a 2.6 GB model, so it is out of scope here — but the band
 *       data lives in the fixture so the device test is a fixture read, not a rewrite.</li>
 * </ul>
 *
 * <p>The {@code recorded} response in the fixture is synthetic until a device transcript exists.
 * That is honest and still useful: it is the format contract that regresses silently, not the
 * calibration, and this test would catch a parser change that broke it.</p>
 */
public class AiScoreEvalTest {

    /** Gson DTOs for the fixture. */
    private static final class Fixture {
        String interests;
        List<String> vocabulary;
        List<Item> items;
        String recorded;
    }

    private static final class Item {
        int index;
        String title;
        String excerpt;
        String trap;
        int bandMin;
        int bandMax;
        int expect;
        List<String> expectTags;
        boolean whyRequired;
    }

    private static Fixture fixture;
    private static Map<Integer, ScoreLineParser.Line> parsed;

    @BeforeClass
    public static void loadFixture() throws IOException {
        try (InputStream in = AiScoreEvalTest.class.getResourceAsStream("/ai/score_eval.json")) {
            assertNotNull("src/test/resources/ai/score_eval.json must exist", in);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            fixture = new Gson().fromJson(new String(bos.toByteArray(), Charset.forName("UTF-8")),
                    Fixture.class);
        }
        parsed = ScoreLineParser.parse(fixture.recorded, fixture.items.size(),
                fixture.vocabulary);
    }

    @Test
    public void theFixtureItselfIsWellFormed() {
        assertEquals(12, fixture.items.size());
        for (int i = 0; i < fixture.items.size(); i++) {
            Item item = fixture.items.get(i);
            assertEquals("indices are 1..12 in order", i + 1, item.index);
            assertTrue(item.bandMin >= 0 && item.bandMax <= 3 && item.bandMin <= item.bandMax);
            assertTrue(item.expect >= item.bandMin && item.expect <= item.bandMax);
            assertNotNull(item.title);
            assertNotNull(item.excerpt);
            assertNotNull(item.trap);
            for (String tag : item.expectTags) {
                assertTrue(tag + " must be in the closed vocabulary",
                        fixture.vocabulary.contains(tag));
            }
        }
    }

    @Test
    public void twelveDistinctIndicesNoneMissingNoneExtra() {
        assertEquals(12, parsed.size());
        assertTrue(ScoreLineParser.missing(parsed, 12).isEmpty());
        for (int i = 1; i <= 12; i++) {
            assertNotNull("index " + i + " missing", parsed.get(i));
        }
    }

    @Test
    public void everyScoreIsInRangeAfterClamping() {
        for (ScoreLineParser.Line l : parsed.values()) {
            assertTrue(l.score >= 0 && l.score <= 3);
        }
    }

    @Test
    public void everySurvivingTagIsInTheClosedVocabulary() {
        int invented = 0;
        for (ScoreLineParser.Line l : parsed.values()) {
            for (String t : l.themes) {
                assertTrue(t + " escaped the filter", fixture.vocabulary.contains(t));
            }
            for (String t : l.flags) {
                assertTrue(t + " escaped the filter", fixture.vocabulary.contains(t));
            }
            invented += l.rawTags.size() - (l.themes.size() + l.flags.size());
        }
        // The raw invented-tag count is the cheapest per-model quality number there is; the format
        // gate only requires that none of them survive filtering.
        assertEquals("no invented tags in the recorded transcript", 0, invented);
    }

    @Test
    public void whyIsPresentWhereItMattersAndNeverTooLong() {
        for (Item item : fixture.items) {
            ScoreLineParser.Line l = parsed.get(item.index);
            assertNotNull(l);
            assertTrue("why over 160 chars on item " + item.index,
                    l.why.length() <= ScoreLineParser.MAX_WHY_CHARS);
            if (item.whyRequired) {
                assertFalse("item " + item.index + " needs a why", l.why.isEmpty());
            }
        }
    }

    /** veille: "silence is worse than a zero". An empty excerpt and mojibake still get a line. */
    @Test
    public void theUnjudgeableItemsStillProduceALine() {
        assertNotNull(parsed.get(5));
        assertNotNull(parsed.get(6));
    }

    @Test
    public void theRecordedTranscriptIsInBandAndPassesTheOrderingTraps() {
        int inBand = 0;
        for (Item item : fixture.items) {
            int score = parsed.get(item.index).score;
            if (score >= item.bandMin && score <= item.bandMax) {
                inBand++;
            }
        }
        assertTrue("ship gate is >= 9/12 in band; got " + inBand, inBand >= 9);

        // Ordering assertions: stricter than the bands and immune to a model that is globally hot
        // or cold by a point.
        assertTrue("short-and-on-topic must beat long-with-a-mention",
                parsed.get(3).score > parsed.get(2).score);
        assertTrue(parsed.get(3).score >= parsed.get(10).score);
        assertTrue("a regulation article must beat an adjacent one",
                parsed.get(7).score > parsed.get(9).score);
        assertTrue("a press release about a watched company must beat vendor spam",
                parsed.get(1).score > parsed.get(12).score);
    }

    /** The fixture doubles as a prompt-assembly test: 12 real articles through the real builder. */
    @Test
    public void theFixtureRendersIntoACompletePrompt() {
        List<AiPromptBuilder.Article> articles = new ArrayList<>();
        for (Item item : fixture.items) {
            articles.add(new AiPromptBuilder.Article(item.title, "Feed", item.excerpt));
        }
        String template = "INTERESTS\n{{interests}}\n\nTAGS\n{{tags}}{{corrections}}ARTICLES\n"
                + "{{articles}}\n\nANSWER\n{{n}} lines in {{lang}}.\n";
        String rendered = PromptTemplate.of(template).render(AiPromptBuilder.scoreVars(
                fixture.interests, fixture.vocabulary, null, articles, java.util.Locale.UK));
        assertFalse(PromptTemplate.hasUnsubstituted(rendered));
        assertTrue(rendered.contains("[12] Our new SaaS platform makes fleet management effortless"));
        assertTrue("an empty excerpt becomes the literal the prompt names",
                rendered.contains(AiPromptBuilder.NO_TEXT));
        assertFalse("no raw pipe may reach a pipe-delimited payload",
                rendered.contains("|"));
    }
}
