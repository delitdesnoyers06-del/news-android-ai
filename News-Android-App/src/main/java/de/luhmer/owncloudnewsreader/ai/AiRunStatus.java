package de.luhmer.owncloudnewsreader.ai;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.DateFormat;
import java.util.Date;

import de.luhmer.owncloudnewsreader.R;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;

/** Persistence and formatting for the last triage run diagnostics. */
public final class AiRunStatus {

    public static final String KEY_AT = "last_run_at";

    private static final String KEY_CANDIDATES = "last_run_candidates";
    private static final String KEY_ENQUEUED = "last_run_enqueued";
    private static final String KEY_EMBEDDED = "last_run_embedded";
    private static final String KEY_BACKFILLED = "last_run_backfilled";
    private static final String KEY_SELECTED = "last_run_selected";
    private static final String KEY_DISCARDED = "last_run_discarded";
    private static final String KEY_SCORED = "last_run_scored";
    private static final String KEY_UNJUDGED = "last_run_unjudged";
    private static final String KEY_INVENTED_TAGS = "last_run_invented_tags";
    private static final String KEY_COLD = "last_run_cold";
    private static final String KEY_DEGRADED = "last_run_degraded";

    private AiRunStatus() {
        // no instances
    }

    public static void save(AiDb db, long at, AiTriagePipeline.Report report) {
        if (db == null || report == null) {
            return;
        }
        db.putMetaLong(KEY_AT, at);
        db.putMetaLong(KEY_CANDIDATES, report.candidates);
        db.putMetaLong(KEY_ENQUEUED, report.enqueued);
        db.putMetaLong(KEY_EMBEDDED, report.embedded);
        db.putMetaLong(KEY_BACKFILLED, report.backfilled);
        db.putMetaLong(KEY_SELECTED, report.selected);
        db.putMetaLong(KEY_DISCARDED, report.discarded);
        db.putMetaLong(KEY_SCORED, report.scored);
        db.putMetaLong(KEY_UNJUDGED, report.unjudged);
        db.putMetaLong(KEY_INVENTED_TAGS, report.inventedTags);
        db.putMetaLong(KEY_COLD, report.cold ? 1L : 0L);
        db.putMeta(KEY_DEGRADED, report.degraded == null ? "" : report.degraded);
    }

    public static String summary(Context context, SharedPreferences prefs, AiDb db) {
        long at = lastRunAt(prefs, db);
        if (at <= 0L) {
            return context.getString(R.string.pref_summary_ai_last_run_never);
        }
        String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(at));
        long candidates = meta(db, KEY_CANDIDATES);
        long selected = meta(db, KEY_SELECTED);
        long scored = meta(db, KEY_SCORED);
        long unjudged = meta(db, KEY_UNJUDGED);
        String degraded = text(db, KEY_DEGRADED);
        String health = degraded.isEmpty() ? context.getString(R.string.ai_diagnostics_ok)
                : degraded;
        return context.getString(R.string.ai_diagnostics_summary, when, candidates, selected,
                scored, unjudged, health);
    }

    public static String details(Context context, SharedPreferences prefs, AiDb db) {
        long at = lastRunAt(prefs, db);
        if (at <= 0L) {
            return context.getString(R.string.pref_summary_ai_last_run_never);
        }
        String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(new Date(at));
        String degraded = text(db, KEY_DEGRADED);
        if (degraded.isEmpty()) {
            degraded = context.getString(R.string.ai_diagnostics_ok);
        }
        return context.getString(R.string.ai_diagnostics_details,
                when,
                meta(db, KEY_CANDIDATES),
                meta(db, KEY_ENQUEUED),
                meta(db, KEY_EMBEDDED),
                meta(db, KEY_BACKFILLED),
                meta(db, KEY_SELECTED),
                meta(db, KEY_DISCARDED),
                meta(db, KEY_SCORED),
                meta(db, KEY_UNJUDGED),
                meta(db, KEY_INVENTED_TAGS),
                meta(db, AiTriagePipeline.KEY_EMBED_BACKLOG),
                meta(db, KEY_COLD) == 1L
                        ? context.getString(android.R.string.yes)
                        : context.getString(android.R.string.no),
                degraded);
    }

    private static long lastRunAt(SharedPreferences prefs, AiDb db) {
        long fromDb = meta(db, KEY_AT);
        if (fromDb > 0L || prefs == null) {
            return fromDb;
        }
        return prefs.getLong(de.luhmer.owncloudnewsreader.SettingsActivity.PREF_AI_LAST_RUN
                + "_at", 0L);
    }

    private static long meta(AiDb db, String key) {
        return db == null ? 0L : db.getMetaLong(key, 0L);
    }

    private static String text(AiDb db, String key) {
        String v = db == null ? null : db.getMeta(key);
        return v == null ? "" : v;
    }
}
