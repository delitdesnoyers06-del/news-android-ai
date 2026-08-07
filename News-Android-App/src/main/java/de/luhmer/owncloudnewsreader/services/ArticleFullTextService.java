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

package de.luhmer.owncloudnewsreader.services;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.JobIntentService;

import java.util.List;

import de.luhmer.owncloudnewsreader.SettingsActivity;
import de.luhmer.owncloudnewsreader.articlefulltext.ArticleFullTextExtractor;
import de.luhmer.owncloudnewsreader.articlefulltext.ThinContentDetector;
import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.FullTextStore;
import de.luhmer.owncloudnewsreader.database.model.RssItem;
import okhttp3.OkHttpClient;

/**
 * Post-sync background pass that fills in the body of articles whose feed only shipped a teaser.
 *
 * <p>Enqueued from {@code OwnCloudSyncAdapter.onPerformSync()} once the new items are in the DB.
 * Runs off the sync thread ({@link JobIntentService}), so a minute of network never blocks the UI
 * spinner. Flavor-agnostic: unlike the AI triage worker it needs no ML model, only the network and
 * a Readability parse, so it lives in {@code src/main} and works in every flavor.</p>
 *
 * <p>Opt-in: does nothing unless {@link SettingsActivity#CB_FULLTEXT_EXTRACTION} is on, because it
 * contacts third-party article hosts.</p>
 *
 * <p>Degrade, never crash: one article's failure is recorded and skipped; it never aborts the run
 * or reaches a user who only wants to read the news.</p>
 */
public class ArticleFullTextService extends JobIntentService {

    private static final String TAG = ArticleFullTextService.class.getCanonicalName();

    private static final int JOB_ID = 1001;

    /** How many recent unread items to inspect for a teaser body in one run. */
    private static final int SCAN_LIMIT = 100;

    /** How many articles to actually fetch per run, so a big first sync does not stampede. */
    private static final int MAX_FETCHES_PER_RUN = 30;

    /**
     * Enqueues an extraction pass, but only when the feature is enabled. Cheap and safe to call
     * after every sync.
     */
    public static void enqueueAfterSync(Context context) {
        try {
            if (!isEnabled(context)) {
                return;
            }
            enqueueWork(context, ArticleFullTextService.class, JOB_ID, new Intent());
        } catch (Throwable t) {
            Log.e(TAG, "Failed to enqueue full-text extraction", t);
        }
    }

    private static boolean isEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
        return prefs.getBoolean(SettingsActivity.CB_FULLTEXT_EXTRACTION, false);
    }

    @Override
    protected void onHandleWork(@NonNull Intent intent) {
        if (!isEnabled(this)) {
            return;
        }

        DatabaseConnectionOrm dbConn = new DatabaseConnectionOrm(this);
        AiDb aiDb = dbConn.aiDb();
        if (aiDb == null) {
            Log.w(TAG, "AI side-store unavailable; skipping full-text extraction");
            return;
        }

        FullTextStore store = new FullTextStore(aiDb);
        OkHttpClient client = ArticleFullTextExtractor.defaultClient();

        List<RssItem> candidates = dbConn.getUnreadRssItemsForFullTextExtraction(SCAN_LIMIT);
        int fetched = 0;

        for (RssItem item : candidates) {
            if (fetched >= MAX_FETCHES_PER_RUN) {
                Log.d(TAG, "Reached per-run fetch cap (" + MAX_FETCHES_PER_RUN + ")");
                break;
            }

            long id = item.getId();
            String state = store.stateOf(id);
            // Already extracted, or deliberately skipped before: leave it. A previous `failed` is
            // retried on a later sync (the site may have been down).
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
                // Never let one bad page take the whole run (or the app) down.
                Log.e(TAG, "Unexpected error extracting " + url, t);
            }
            fetched++;
        }

        Log.d(TAG, "Full-text extraction pass done; attempted " + fetched + " article(s)");
    }
}
