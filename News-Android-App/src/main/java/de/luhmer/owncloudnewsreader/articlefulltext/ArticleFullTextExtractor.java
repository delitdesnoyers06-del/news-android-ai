package de.luhmer.owncloudnewsreader.articlefulltext;

import androidx.annotation.Nullable;

import net.dankito.readability4j.Article;
import net.dankito.readability4j.Readability4J;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Fetches an article URL and runs the Mozilla-Readability JVM port (readability4j) over it to
 * recover the clean, full body that the RSS feed only teased.
 *
 * <p>Deliberately uses its own {@link OkHttpClient}, never the Nextcloud-authenticated one
 * ({@code OkHttpSSLClient}): these requests go to arbitrary third-party article hosts, so no
 * Nextcloud credentials must ever ride along.</p>
 */
public final class ArticleFullTextExtractor {

    /** A neutral desktop UA; some sites serve stripped markup to unknown agents. */
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/124.0 Safari/537.36";

    /**
     * Hard cap on how much of a page we read into memory. Article HTML is well under this; the cap
     * exists so a huge (or hostile) response cannot OOM the background process. Truncation is safe:
     * Readability works fine on a prefix, and the article body sits near the top of the document.
     */
    private static final long MAX_HTML_BYTES = 3L * 1024 * 1024;

    private ArticleFullTextExtractor() {
    }

    /** The clean article HTML plus a short excerpt, as produced by Readability. */
    public static final class Result {
        public final String contentHtml;
        @Nullable public final String excerpt;

        public Result(String contentHtml, @Nullable String excerpt) {
            this.contentHtml = contentHtml;
            this.excerpt = excerpt;
        }
    }

    /**
     * A shared client with sane timeouts; extraction must never hang a background run. The
     * {@code callTimeout} is the hard per-article ceiling the pass budgets against.
     */
    public static OkHttpClient defaultClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    /**
     * Downloads {@code url} and extracts its readable content.
     *
     * @return the extracted body, or {@code null} when the page could not be fetched or Readability
     *         found nothing usable (both are a "skip", not a crash)
     * @throws IOException on a network/HTTP failure the caller may want to retry later
     */
    @Nullable
    public static Result extract(String url, OkHttpClient client) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml")
                .build();

        String html;
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code() + " for " + url);
            }
            ResponseBody body = response.body();
            if (body == null) {
                return null;
            }
            // peekBody buffers at most MAX_HTML_BYTES: a hostile or accidentally huge page is
            // truncated rather than read whole into memory.
            html = response.peekBody(MAX_HTML_BYTES).string();
        }

        return parse(url, html);
    }

    /**
     * Runs Readability over already-fetched HTML. Split out from {@link #extract} so the extraction
     * itself is testable without a network round-trip.
     *
     * @return the extracted body, or {@code null} when Readability found nothing usable
     */
    @Nullable
    public static Result parse(String url, String html) {
        if (html == null || html.isEmpty()) {
            return null;
        }
        Article article = new Readability4J(url, html).parse();
        String content = article.getContent();
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        // This HTML comes from an arbitrary third-party host and is rendered in a JavaScript-enabled
        // WebView. Readability strips <script>, but not inline event handlers (onerror/onclick) or
        // other active markup. Run it through a jsoup Safelist so only inert formatting, links and
        // images survive - a tighter surface than the feed body we rendered before.
        String safe = Jsoup.clean(content, url, Safelist.relaxed());
        if (safe.trim().isEmpty()) {
            return null;
        }
        return new Result(safe, article.getExcerpt());
    }
}
