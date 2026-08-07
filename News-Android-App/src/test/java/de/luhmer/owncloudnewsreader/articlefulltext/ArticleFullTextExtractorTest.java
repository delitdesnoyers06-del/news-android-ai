package de.luhmer.owncloudnewsreader.articlefulltext;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link ArticleFullTextExtractor#parse}: the Readability step, exercised on raw HTML with no
 * network. Pure JVM (readability4j + jsoup), so it runs as a plain JUnit test.
 */
public class ArticleFullTextExtractorTest {

    private static final String ARTICLE_HTML =
            "<html><head><title>A Real Article</title></head><body>"
                    + "<header><nav>site nav we do not want</nav></header>"
                    + "<article>"
                    + "<h1>A Real Article</h1>"
                    + "<p>The first paragraph of a genuine article carries several sentences of "
                    + "substance so that Readability recognises it as the main content of the page "
                    + "and not as boilerplate around the edges.</p>"
                    + "<p>The second paragraph continues the story with yet more prose, again long "
                    + "enough that the scoring heuristics keep it as part of the extracted body.</p>"
                    + "<p>A third paragraph seals it, giving the article a clear, dominant block of "
                    + "text that any Readability implementation will lift out cleanly.</p>"
                    + "</article>"
                    + "<footer>copyright boilerplate</footer>"
                    + "</body></html>";

    @Test
    public void extractsMainArticleTextAndDropsChrome() {
        ArticleFullTextExtractor.Result result =
                ArticleFullTextExtractor.parse("https://example.com/story", ARTICLE_HTML);

        assertNotNull("Readability should find the article body", result);
        assertTrue("keeps the lede", result.contentHtml.contains("first paragraph"));
        assertTrue("keeps later paragraphs", result.contentHtml.contains("third paragraph"));
    }

    @Test
    public void emptyHtmlYieldsNull() {
        assertNull(ArticleFullTextExtractor.parse("https://example.com/x", ""));
        assertNull(ArticleFullTextExtractor.parse("https://example.com/x", null));
    }

    @Test
    public void stripsActiveMarkupFromExtractedContent() {
        // A hostile page: the extracted body must not carry <script> or inline event handlers into
        // the JavaScript-enabled WebView that renders it.
        String hostile = "<html><body><article>"
                + "<h1>Story</h1>"
                + "<p>The lede paragraph is long enough to be recognised as the article's main "
                + "content by the Readability scoring, so the body is not discarded as boilerplate.</p>"
                + "<p onclick=\"steal()\">The second paragraph adds more substance and an inline "
                + "handler that must be removed before this HTML ever reaches the WebView.</p>"
                + "<img src=\"https://h/x.png\" onerror=\"alert(1)\"/>"
                + "<script>alert('xss')</script>"
                + "</article></body></html>";

        ArticleFullTextExtractor.Result result =
                ArticleFullTextExtractor.parse("https://example.com/story", hostile);

        assertNotNull(result);
        assertFalse("no inline event handlers", result.contentHtml.contains("onclick"));
        assertFalse("no inline error handlers", result.contentHtml.contains("onerror"));
        assertFalse("no scripts", result.contentHtml.toLowerCase().contains("<script"));
        assertTrue("article text is kept", result.contentHtml.contains("lede paragraph"));
    }
}
