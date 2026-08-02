package de.luhmer.owncloudnewsreader.ai.work;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.luhmer.owncloudnewsreader.SettingsActivity;
import de.luhmer.owncloudnewsreader.ai.AiAbstract;
import de.luhmer.owncloudnewsreader.ai.AiDigests;
import de.luhmer.owncloudnewsreader.ai.AiFeature;
import de.luhmer.owncloudnewsreader.ai.AiNote;
import de.luhmer.owncloudnewsreader.ai.download.AiModelRepository;
import de.luhmer.owncloudnewsreader.ai.engine.AiEngineManager;
import de.luhmer.owncloudnewsreader.ai.engine.AiModelInfo;
import de.luhmer.owncloudnewsreader.ai.engine.CancelToken;
import de.luhmer.owncloudnewsreader.ai.prompt.AiPrompts;
import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.AiDigestStore;
import de.luhmer.owncloudnewsreader.notification.NextcloudNotificationManager;

/**
 * Fills in the digest abstract, lazily, at most once a day.
 *
 * <p>The digest itself — its items, its theme blocks, its count — is written by {@link AiDigests}
 * from pure SQL before this worker is ever enqueued, and the card renders from that immediately.
 * This worker only ever turns {@code ABSTRACT_STATE} from {@code pending} into {@code ok},
 * {@code skipped} or {@code failed}. If it never runs, the digest is a digest without a paragraph.
 *
 * <p>It is enqueued on the reader <b>opening</b> "For you" (PLAN §5 Phase 7 — lazy on open) rather
 * than on every sync: sync fires up to eight times a day and eight abstracts is battery spent on a
 * screen nobody looked at. Running right after a triage pass also means the engine is usually still
 * warm ({@link AiEngineManager#IDLE_KEEPALIVE_MS}), so the paragraph costs a decode, not a load.
 *
 * <p>Like {@code AiTriageWorker}: exactly one {@code return Result.success()}.
 */
public class AiDigestWorker extends Worker {

    private static final String TAG = "AiDigestWorker";

    public static final String UNIQUE_WORK_NAME = "ai-digest";

    private static final int NOTIFICATION_ID = 4712;

    private final CancelToken token = new CancelToken();

    public AiDigestWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            Context ctx = getApplicationContext();
            SharedPreferences prefs = AiFeature.prefsOf(ctx);
            if (!AiFeature.isEnabled(ctx, prefs)) {
                Log.d(TAG, "AI disabled - no digest");
            } else {
                AiDb db = new DatabaseConnectionOrm(ctx).aiDb();
                if (db == null) {
                    Log.w(TAG, "AI schema unavailable");
                } else {
                    run(ctx, db, prefs);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "digest run failed", t);
        }
        return Result.success();
    }

    private void run(Context ctx, AiDb db, SharedPreferences prefs) {
        AiDigestStore store = new AiDigestStore(db);
        AiDigestStore.Digest digest = AiDigests.ensureToday(db, System.currentTimeMillis());
        if (digest == null) {
            Log.d(TAG, "not enough newly selected articles for a digest");
            return;
        }
        if (!AiDigestStore.ABSTRACT_PENDING.equals(digest.abstractState)) {
            Log.d(TAG, "abstract already " + digest.abstractState);
            return;
        }

        AiModelInfo model = resolveModel(ctx, db, prefs);
        if (model == null) {
            // Not a failure: "no model installed" is a supported configuration and the digest is
            // complete without a paragraph. Marking it skipped stops us retrying every open.
            store.setAbstract(digest.id, null, AiDigestStore.ABSTRACT_SKIPPED);
            return;
        }

        List<String[]> items = new ArrayList<>();
        for (AiDigests.Entry e : AiDigests.entries(db, digest.id)) {
            items.add(new String[]{e.title == null ? "" : e.title, e.why == null ? "" : e.why});
        }
        if (items.isEmpty()) {
            store.setAbstract(digest.id, null, AiDigestStore.ABSTRACT_SKIPPED);
            return;
        }

        final String note = AiNote.current(prefs, db);
        final boolean gpu = prefs.getBoolean(SettingsActivity.CB_AI_GPU_BACKEND, false);
        String text = null;
        try {
            text = new AiEngineManager(ctx, db).withLlm(model, gpu, token,
                    llm -> AiAbstract.generate(llm, AiPrompts.abstractSystem(ctx),
                            AiPrompts.abstractUser(ctx), items, note, Locale.getDefault(), token));
        } catch (Throwable t) {
            Log.w(TAG, "abstract generation failed", t);
        }

        if (text == null) {
            // The guard dropped it, or the engine could not produce one. Either way the digest
            // renders without a paragraph rather than with a bad one.
            store.setAbstract(digest.id, null, AiDigestStore.ABSTRACT_FAILED);
            return;
        }
        store.setAbstract(digest.id, text, AiDigestStore.ABSTRACT_OK);
        maybeNotify(ctx, prefs, digest, text);
    }

    private AiModelInfo resolveModel(Context ctx, AiDb db, SharedPreferences prefs) {
        try {
            // __same_as_triage__ (PLAN D15): the digest never loads a second model, so it asks for
            // the triage one. A different model here would mean evicting a warm engine to load
            // another multi-GB file for one paragraph.
            return new AiModelRepository(ctx, db).resolveLlm(prefs, SettingsActivity.SP_AI_MODEL_TRIAGE);
        } catch (Throwable t) {
            Log.w(TAG, "no digest model", t);
            return null;
        }
    }

    /** Opt-in, off by default. First sentence of the abstract plus the item count. */
    private void maybeNotify(Context ctx, SharedPreferences prefs, AiDigestStore.Digest digest,
                             String text) {
        if (!prefs.getBoolean(SettingsActivity.PREF_AI_DIGEST_NOTIFY, false)) {
            return;
        }
        try {
            NextcloudNotificationManager.showNotificationAiDigest(ctx, UNIQUE_WORK_NAME,
                    NOTIFICATION_ID, digest.id, firstSentence(text), digest.itemCount);
        } catch (Throwable t) {
            Log.w(TAG, "could not post the digest notification", t);
        }
    }

    static String firstSentence(String text) {
        if (text == null) {
            return "";
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?')
                    && (i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1)))) {
                return text.substring(0, i + 1);
            }
        }
        return text;
    }

    /**
     * Same contract as {@code AiTriageWorker} and {@code AiTasteDraftWorker}: cancelling the token
     * ends the in-flight decode, and {@code shutdownNow} releases the engine instead of leaving
     * multi-GB of mmapped weights resident in a process the OS is about to reclaim. The digest is
     * the shortest of the three runs, but it borrows the same warm engine, so it owes the same
     * release.
     */
    @Override
    public void onStopped() {
        token.cancel("worker stopped");
        AiEngineManager.shutdownNow("digest stopped");
        super.onStopped();
    }
}
