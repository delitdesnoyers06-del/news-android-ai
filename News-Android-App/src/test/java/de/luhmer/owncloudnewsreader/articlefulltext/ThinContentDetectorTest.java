package de.luhmer.owncloudnewsreader.articlefulltext;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link ThinContentDetector}: the teaser-vs-full-article decision. Pure jsoup, no Android, so it
 * runs as a plain JUnit test.
 */
public class ThinContentDetectorTest {

    private static final String LONG_PARAGRAPH =
            "This is a sufficiently long paragraph of article prose that comfortably clears the "
                    + "minimum character threshold used to distinguish real content from chrome.";

    @Test
    public void nullBodyIsNotThin() {
        // Null has its own handling downstream; do not trigger a fetch for it.
        assertFalse(ThinContentDetector.isThin(null));
    }

    @Test
    public void emptyBodyIsNotThin() {
        // Empty body falls back to the media description in RssItemToHtmlTask; leave it alone.
        assertFalse(ThinContentDetector.isThin("   <p></p>  "));
    }

    @Test
    public void singleShortTeaserIsThin() {
        assertTrue(ThinContentDetector.isThin("<p>Read the full story on our website.</p>"));
    }

    @Test
    public void shortTextWithManyTagsIsStillThin() {
        assertTrue(ThinContentDetector.isThin(
                "<p>Breaking:</p><p>more</p><p>soon</p><a href='x'>read more</a>"));
    }

    @Test
    public void fullArticleWithSeveralParagraphsIsNotThin() {
        String body = "<div>"
                + "<p>" + LONG_PARAGRAPH + "</p>"
                + "<p>" + LONG_PARAGRAPH + "</p>"
                + "<p>" + LONG_PARAGRAPH + "</p>"
                + "</div>";
        assertFalse(ThinContentDetector.isThin(body));
    }

    @Test
    public void oneParagraphIsThinEvenWhenLong() {
        // A body with a single paragraph is below MIN_PARAGRAPHS, so it is treated as a teaser
        // regardless of its length: a genuine full article virtually always has several paragraphs.
        String body = "<p>" + LONG_PARAGRAPH + " " + LONG_PARAGRAPH + " " + LONG_PARAGRAPH
                + " " + LONG_PARAGRAPH + "</p>";
        assertTrue(ThinContentDetector.isThin(body));
    }
}
