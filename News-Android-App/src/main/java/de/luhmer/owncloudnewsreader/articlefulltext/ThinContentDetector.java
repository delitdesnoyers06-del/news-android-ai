package de.luhmer.owncloudnewsreader.articlefulltext;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Decides whether an RSS body is a full article or just a teaser.
 *
 * <p>Many feeds ship only the lede — one or two paragraphs and a "read more" link. This detector
 * flags those so {@link ArticleFullTextExtractor} can fetch the real article. It is deliberately
 * conservative: when in doubt it treats the body as full, because a wrongful extraction replaces
 * good content with a network round-trip, whereas a missed teaser just leaves things as they are.</p>
 */
public final class ThinContentDetector {

    /** A body with fewer than this many non-trivial paragraphs is a teaser. */
    static final int MIN_PARAGRAPHS = 2;

    /**
     * ...or one whose visible text is shorter than this, regardless of paragraph count — this
     * catches teasers that fake structure with a couple of one-line paragraphs. Kept low so that a
     * genuinely short but complete multi-paragraph article is not misread as a teaser.
     */
    static final int MIN_TEXT_CHARS = 200;

    /** A {@code <p>} shorter than this is treated as chrome (a byline, a "read more"), not content. */
    private static final int MIN_PARAGRAPH_CHARS = 40;

    private ThinContentDetector() {
    }

    /**
     * @param bodyHtml the RSS-provided body ({@code RssItem.getBody()})
     * @return true when {@code bodyHtml} looks like a truncated teaser worth expanding
     */
    public static boolean isThin(String bodyHtml) {
        if (bodyHtml == null) {
            return false; // nothing to render either way; leave the empty-body fallbacks alone
        }
        Document doc = Jsoup.parse(bodyHtml);
        String text = doc.text().trim();
        if (text.isEmpty()) {
            return false; // empty body has its own media-description fallback in RssItemToHtmlTask
        }
        if (text.length() < MIN_TEXT_CHARS) {
            return true;
        }
        int paragraphs = 0;
        for (Element p : doc.select("p")) {
            if (p.text().trim().length() >= MIN_PARAGRAPH_CHARS) {
                paragraphs++;
            }
        }
        return paragraphs < MIN_PARAGRAPHS;
    }
}
