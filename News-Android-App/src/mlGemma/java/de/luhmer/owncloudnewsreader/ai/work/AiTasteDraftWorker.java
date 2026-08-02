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
import de.luhmer.owncloudnewsreader.ai.AiFeature;
import de.luhmer.owncloudnewsreader.ai.AiNote;
import de.luhmer.owncloudnewsreader.ai.AiTasteDraft;
import de.luhmer.owncloudnewsreader.ai.AiTasteDrafts;
import de.luhmer.owncloudnewsreader.ai.download.AiModelRepository;
import de.luhmer.owncloudnewsreader.ai.engine.AiEngineManager;
import de.luhmer.owncloudnewsreader.ai.engine.AiModelInfo;
import de.luhmer.owncloudnewsreader.ai.engine.CancelToken;
import de.luhmer.owncloudnewsreader.ai.prompt.AiPrompts;
import de.luhmer.owncloudnewsreader.ai.prompt.TasteDraftGuard;
import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.AiDecisionStore;

/**
 * Drafts a revised interests note from recent decisions. <b>Manual trigger only, never a timer.</b>
 *
 * <p>The result is parked in {@code AI_META} as a proposal and the reader is shown a line diff. This
 * worker has no write path to the note — {@link AiNote#save} is only ever called from the settings
 * field and from the Save button in {@code AiRubricDiffActivity}. That separation is the entire
 * mitigation for rubric collapse: a model that narrows the note over successive passes can only do
 * so if a human taps Save each time, and the diff makes what it removed impossible to miss.</p>
 */
public class AiTasteDraftWorker extends Worker {

    private static final String TAG = "AiTasteDraftWorker";

    public static final String UNIQUE_WORK_NAME = "ai-taste-draft";

    private final CancelToken token = new CancelToken();

    public AiTasteDraftWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        AiDb db = null;
        try {
            Context ctx = getApplicationContext();
            SharedPreferences prefs = AiFeature.prefsOf(ctx);
            db = new DatabaseConnectionOrm(ctx).aiDb();
            if (!AiFeature.isEnabled(ctx, prefs) || db == null) {
                AiTasteDrafts.fail(db, "unavailable");
            } else {
                run(ctx, db, prefs);
            }
        } catch (Throwable t) {
            Log.e(TAG, "taste draft failed", t);
            AiTasteDrafts.fail(db, "error");
        }
        return Result.success();
    }

    private void run(Context ctx, AiDb db, SharedPreferences prefs) {
        AiDecisionStore decisions = new AiDecisionStore(db);
        if (decisions.decisionCount() < TasteDraftGuard.LEARN_MIN_DECISIONS) {
            AiTasteDrafts.fail(db, "not_enough_decisions");
            return;
        }
        AiModelInfo model;
        try {
            model = new AiModelRepository(ctx, db)
                    .resolveLlm(prefs, SettingsActivity.SP_AI_MODEL_TRIAGE);
        } catch (Throwable t) {
            model = null;
        }
        if (model == null) {
            AiTasteDrafts.fail(db, "no_model");
            return;
        }

        final String note = AiNote.current(prefs, db);
        final List<String> kept = titlesOf(decisions, AiDecisionStore.STATE_KEPT);
        final List<String> rejected = titlesOf(decisions, AiDecisionStore.STATE_REJECTED);
        final boolean gpu = prefs.getBoolean(SettingsActivity.CB_AI_GPU_BACKEND, false);

        AiTasteDraft.Result result;
        try {
            result = new AiEngineManager(ctx, db).withLlm(model, gpu, token,
                    llm -> AiTasteDraft.draftWithDebug(llm, AiPrompts.tasteSystem(ctx),
                            AiPrompts.tasteUser(ctx), note, kept, rejected,
                            Locale.getDefault(), token));
        } catch (Throwable t) {
            Log.w(TAG, "draft call failed", t);
            AiTasteDrafts.fail(db, "engine");
            return;
        }
        AiTasteDrafts.store(db, result.verdict, result.debug);
    }

    /**
     * The most recent decision titles for one polarity. {@code TITLE_SNAP} is used rather than a
     * join on {@code RSS_ITEM}: the article may have been evicted by the cache trim months ago and
     * the decision is the durable half.
     */
    private List<String> titlesOf(AiDecisionStore decisions, String state) {
        List<String> out = new ArrayList<>();
        String action = AiDecisionStore.STATE_KEPT.equals(state)
                ? AiDecisionStore.ACTION_KEEP : AiDecisionStore.ACTION_REJECT;
        for (AiDecisionStore.Row row : decisions.recent(200)) {
            if (action.equals(row.action) && row.titleSnap != null
                    && !row.titleSnap.trim().isEmpty()) {
                out.add(row.titleSnap);
            }
        }
        return out;
    }

    @Override
    public void onStopped() {
        token.cancel("worker stopped");
        AiEngineManager.shutdownNow("taste draft stopped");
        super.onStopped();
    }
}
