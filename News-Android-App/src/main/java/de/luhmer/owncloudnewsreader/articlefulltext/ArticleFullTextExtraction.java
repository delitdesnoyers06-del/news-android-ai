/*
 * Android ownCloud News
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU AFFERO GENERAL PUBLIC LICENSE
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU AFFERO GENERAL PUBLIC LICENSE for more details.
 */

package de.luhmer.owncloudnewsreader.articlefulltext;

import android.content.Context;
import android.util.Log;

import java.util.List;

import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.FullTextStore;
import de.luhmer.owncloudnewsreader.database.model.RssItem;
import okhttp3.OkHttpClient;

/**
 * The synchronous full-article extraction pass, shared by two callers:
 *
 * <ul>
 *   <li>{@code ArticleFullTextService} — the post-sync background job that fills content in for
 *       display, in every flavor.</li>
 *   <li>the AI triage worker (mlGemma) — which runs it <b>before</b> embedding so the AI pipeline
 *       (embeddings, scoring, digest) reads the full article, not the teaser. This is why the pass
 *       is synchronous and lives here rather than only inside the service.</li>
 * </ul>
 *
 * <p>Idempotent: an article already {@code ok} or {@code skipped} is left alone, so running it from
 * both callers around the same sync at most fetches a brand-new article once. Degrades, never
 * crashes: one page's failure is recorded and skipped.</p>
 */
public final class ArticleFullTextExtraction {

    private static final String TAG = "ArticleFullText";

    /** How many recent unread items to inspect for a teaser body in one pass. */
    public static final int DEFAULT_SCAN_LIMIT = 100;

    /** How many articles to actually fetch per pass, so a big first sync does not stampede. */
    public static final int DEFAULT_MAX_FETCHES = 30;

    private ArticleFullTextExtraction() {
    }

    /**
     * Fetches and extracts the full body of recent unread articles whose RSS body is only a teaser.
     *
     * @return the number of articles a fetch was attempted for (0 when the AI side-store is
     *         unavailable or nothing qualified)
     */
    public static int run(Context context, DatabaseConnectionOrm dbConn, int scanLimit, int maxFetches) {
        AiDb aiDb = dbConn.aiDb();
        if (aiDb == null) {
            Log.w(TAG, "AI side-store unavailable; skipping full-text extraction");
            return 0;
        }

        FullTextStore store = new FullTextStore(aiDb);
        OkHttpClient client = ArticleFullTextExtractor.defaultClient();

        List<RssItem> candidates = dbConn.getUnreadRssItemsForFullTextExtraction(scanLimit);
        int fetched = 0;

        for (RssItem item : candidates) {
            if (fetched >= maxFetches) {
                Log.d(TAG, "Reached per-run fetch cap (" + maxFetches + ")");
                break;
            }

            long id = item.getId();
            String state = store.stateOf(id);
            // Already extracted, or deliberately skipped before: leave it. A previous `failed` is
            // retried on a later pass (the site may have been down).
            if (FullTextStore.STATE_OK.equals(state) || FullTextStore.STATE_SKIPPED.equals(state)) {
                continue;
            }

            String url = item.getLink();
            if (url == null || url.trim().isEmpty()) {
                continue; // no page to fetch; not worth a persistent row
            }
            if (!ThinContentDetector.isThin(item.getBody())) {
                continue; // the RSS body is already a full article
            }

            long now = System.currentTimeMillis();
            try {
                ArticleFullTextExtractor.Result result = ArticleFullTextExtractor.extract(url, client);
                if (result == null) {
                    store.mark(id, url, FullTextStore.STATE_SKIPPED, "no_readable_content", now);
                } else {
                    store.saveOk(id, url, result.contentHtml, result.excerpt, now);
                }
            } catch (Exception ex) {
                Log.w(TAG, "Full-text extraction failed for " + url + ": " + ex.getMessage());
                store.mark(id, url, FullTextStore.STATE_FAILED, ex.getMessage(), now);
            } catch (Throwable t) {
                // Never let one bad page take the whole pass (or the app) down.
                Log.e(TAG, "Unexpected error extracting " + url, t);
            }
            fetched++;
        }

        Log.d(TAG, "Full-text extraction pass done; attempted " + fetched + " article(s)");
        return fetched;
    }
}
