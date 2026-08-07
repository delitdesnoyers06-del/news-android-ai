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

import de.luhmer.owncloudnewsreader.SettingsActivity;
import de.luhmer.owncloudnewsreader.articlefulltext.ArticleFullTextExtraction;
import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm;

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
        ArticleFullTextExtraction.run(this, new DatabaseConnectionOrm(this),
                ArticleFullTextExtraction.DEFAULT_SCAN_LIMIT,
                ArticleFullTextExtraction.DEFAULT_MAX_FETCHES,
                ArticleFullTextExtraction.DEFAULT_BUDGET_MS);
    }
}
