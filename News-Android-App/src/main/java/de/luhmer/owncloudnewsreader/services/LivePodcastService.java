package de.luhmer.owncloudnewsreader.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import de.luhmer.owncloudnewsreader.NewsReaderListActivity;
import de.luhmer.owncloudnewsreader.R;
import de.luhmer.owncloudnewsreader.SettingsActivity;
import de.luhmer.owncloudnewsreader.ai.AiCapability;
import de.luhmer.owncloudnewsreader.ai.AiDigests;
import de.luhmer.owncloudnewsreader.ai.AiFeature;
import de.luhmer.owncloudnewsreader.ai.download.AiModelRepository;
import de.luhmer.owncloudnewsreader.ai.engine.AiConversation;
import de.luhmer.owncloudnewsreader.ai.engine.AiEngineManager;
import de.luhmer.owncloudnewsreader.ai.engine.AiModelInfo;
import de.luhmer.owncloudnewsreader.ai.engine.AiPromptSpec;
import de.luhmer.owncloudnewsreader.ai.engine.AiTtsSpec;
import de.luhmer.owncloudnewsreader.ai.engine.CancelToken;
import de.luhmer.owncloudnewsreader.ai.engine.impl.AiEngines;
import de.luhmer.owncloudnewsreader.ai.model.AiCatalogEntry;
import de.luhmer.owncloudnewsreader.ai.prompt.AbstractGuard;
import de.luhmer.owncloudnewsreader.ai.prompt.AiPromptBuilder;
import de.luhmer.owncloudnewsreader.ai.prompt.AiPrompts;
import de.luhmer.owncloudnewsreader.ai.prompt.PromptTemplate;
import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.services.podcast.SentenceBuffer;
import de.luhmer.owncloudnewsreader.services.podcast.StreamingTtsPlayer;

/**
 * The "live podcast": generates a long-form spoken narration of today's "For you" digest with the
 * on-device LLM and reads it aloud as it streams — a foreground media service so audio survives the
 * screen going away.
 *
 * <p>Pipeline: read the digest items → prompt the triage LLM → stream the answer token by token →
 * {@link SentenceBuffer} cuts it into whole sentences → {@link StreamingTtsPlayer} synthesises and
 * plays each one. The LLM (CPU by default) and the neural voice (CPU, ONNX) run at the same time
 * with no GPU contention; the model finishes and releases the engine gate while the voice is still
 * speaking from the buffered tail.</p>
 *
 * <p>Reuses the triage model ({@link SettingsActivity#SP_AI_MODEL_TRIAGE}) so no second multi-GB
 * model is loaded, and is gated on {@link AiCapability#isSupported(Context)} so it is not offered on
 * devices that cannot run it.</p>
 */
public class LivePodcastService extends Service {

    private static final String TAG = "LivePodcastService";

    public static final String EXTRA_DIGEST_ID = "digest_id";
    public static final String ACTION_STOP = "de.luhmer.owncloudnewsreader.LIVE_PODCAST_STOP";

    private static final int NOTIFICATION_ID = 4713;
    private static final String CHANNEL_ID = "ai_live_podcast";

    /** Long-form: much larger than the digest abstract's 400, but bounded so the KV cache stays sane. */
    private static final int PODCAST_MAX_TOKENS = 1500;
    private static final long TIMEOUT_MS = 300_000L;

    public enum State { PREPARING, PLAYING, PAUSED, DONE, ERROR }

    /** Observes transcript/state; the bound {@code LivePodcastActivity} is the only listener. */
    public interface Listener {
        void onDelta(String delta);
        void onState(State state);
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final StringBuilder transcript = new StringBuilder();

    private volatile Listener listener;
    private volatile State state = State.PREPARING;
    private volatile String error;

    private CancelToken token = new CancelToken();
    private volatile AiConversation activeConv;
    private volatile StreamingTtsPlayer player;

    public class LocalBinder extends Binder {
        public LivePodcastService getService() {
            return LivePodcastService.this;
        }
    }

    // ---- Service lifecycle -----------------------------------------------------------------

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopFromUser();
            return START_NOT_STICKY;
        }
        final long digestId = intent == null ? -1 : intent.getLongExtra(EXTRA_DIGEST_ID, -1);
        if (digestId < 0 || !running.compareAndSet(false, true)) {
            // One episode at a time.
            return START_NOT_STICKY;
        }
        token = new CancelToken();
        goForeground(getString(R.string.live_podcast_preparing));
        worker.execute(() -> {
            try {
                runPipeline(digestId);
            } catch (Throwable t) {
                Log.e(TAG, "live podcast crashed", t);
                fail(getString(R.string.live_podcast_error));
            }
        });
        return START_NOT_STICKY;
    }

    // ---- Pipeline --------------------------------------------------------------------------

    private void runPipeline(long digestId) {
        Context ctx = this;
        SharedPreferences prefs = AiFeature.prefsOf(ctx);
        if (!AiFeature.isEnabled(ctx, prefs) || !AiCapability.isSupported(ctx)
                || !AiEngines.llmSupported()) {
            fail(getString(R.string.live_podcast_unavailable));
            return;
        }
        AiDb db = new DatabaseConnectionOrm(ctx).aiDb();
        if (db == null) {
            fail(getString(R.string.live_podcast_unavailable));
            return;
        }

        List<String[]> items = new ArrayList<>();
        for (AiDigests.Entry e : AiDigests.entries(db, digestId)) {
            items.add(new String[]{e.title == null ? "" : e.title, e.why == null ? "" : e.why});
        }
        if (items.isEmpty()) {
            fail(getString(R.string.live_podcast_empty));
            return;
        }

        AiModelInfo model = new AiModelRepository(ctx, db)
                .resolveLlm(prefs, SettingsActivity.SP_AI_MODEL_TRIAGE);
        if (model == null) {
            fail(getString(R.string.live_podcast_no_model));
            return;
        }

        final Locale locale = Locale.getDefault();
        final String lang = AiPromptBuilder.languageName(locale);
        final AiPromptSpec spec = AiPromptSpec.builder()
                .systemInstruction(AiPrompts.podcastSystem(ctx).render(PromptTemplate.vars("lang", lang)))
                .maxOutputTokens(PODCAST_MAX_TOKENS)
                // Greedy: a podcast should be faithful narration, not a creative riff.
                .sampler(1, 1.0d, 0.0d, 0)
                .perCallTimeoutMs(TIMEOUT_MS)
                .build();
        final String userText = AiPrompts.podcastUser(ctx).render(PromptTemplate.vars(
                "items", AbstractGuard.itemsBlock(items),
                "lang", lang));

        StreamingTtsPlayer p = buildPlayer(ctx, db, prefs, locale);
        this.player = p;
        final SentenceBuffer buffer = new SentenceBuffer(p::offer);

        setState(State.PLAYING);
        goForeground(getString(R.string.live_podcast_playing));

        final boolean gpu = prefs.getBoolean(SettingsActivity.CB_AI_GPU_BACKEND, false);
        try {
            new AiEngineManager(ctx, db).withLlm(model, gpu, token, llm -> {
                AiConversation conv = llm.start(spec);
                activeConv = conv;
                try {
                    return conv.send(userText, null, delta -> {
                        appendTranscript(delta);
                        buffer.feed(delta);
                    });
                } finally {
                    activeConv = null;
                    try {
                        conv.close();
                    } catch (Throwable ignored) {
                        // best-effort close
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "generation failed", t);
            fail(getString(R.string.live_podcast_error));
            return;
        }

        // Generation done: flush the last partial sentence and tell the voice no more is coming.
        // The service stays foreground until the player reports completion.
        buffer.flush();
        p.endInput();
    }

    private StreamingTtsPlayer buildPlayer(Context ctx, AiDb db, SharedPreferences prefs,
                                           Locale locale) {
        StreamingTtsPlayer.Listener l = new StreamingTtsPlayer.Listener() {
            @Override
            public void onCompleted() {
                finishOk();
            }

            @Override
            public void onError(String reason) {
                fail(reason);
            }
        };
        try {
            if (prefs.getBoolean(SettingsActivity.CB_AI_TTS_ENGINE, false) && AiEngines.ttsSupported()) {
                AiModelRepository repo = new AiModelRepository(ctx, db);
                AiCatalogEntry voice = repo.ttsEntryForLang(prefs, locale.getLanguage());
                if (voice != null) {
                    AiTtsSpec ttsSpec = repo.ttsSpecFor(voice);
                    if (ttsSpec != null) {
                        return StreamingTtsPlayer.neural(ttsSpec, parseSpeaker(prefs), 1.0f, l);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "neural voice setup failed; using system TTS", t);
        }
        return StreamingTtsPlayer.nativeVoice(ctx, locale, 1.0f, l);
    }

    private static int parseSpeaker(SharedPreferences prefs) {
        try {
            return Integer.parseInt(prefs.getString(SettingsActivity.SP_AI_TTS_SPEAKER, "0"));
        } catch (Throwable t) {
            return 0;
        }
    }

    // ---- transcript / state ----------------------------------------------------------------

    private void appendTranscript(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        synchronized (transcript) {
            transcript.append(delta);
        }
        Listener l = listener;
        if (l != null) {
            main.post(() -> l.onDelta(delta));
        }
    }

    private void setState(State s) {
        state = s;
        Listener l = listener;
        if (l != null) {
            main.post(() -> l.onState(s));
        }
    }

    private void finishOk() {
        setState(State.DONE);
        goForeground(getString(R.string.live_podcast_done));
        main.postDelayed(this::shutdown, 1500);
    }

    private void fail(String reason) {
        error = reason;
        setState(State.ERROR);
        shutdown();
    }

    // ---- public control (called from the bound Activity) -----------------------------------

    /** Snapshot of the transcript so far, for a late-binding Activity (e.g. after rotation). */
    public String getTranscript() {
        synchronized (transcript) {
            return transcript.toString();
        }
    }

    public State getState() {
        return state;
    }

    public String getError() {
        return error;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void togglePlayPause() {
        StreamingTtsPlayer p = player;
        if (p == null) {
            return;
        }
        if (state == State.PLAYING) {
            p.pause();
            setState(State.PAUSED);
        } else if (state == State.PAUSED) {
            p.resume();
            setState(State.PLAYING);
        }
    }

    public void stopFromUser() {
        shutdown();
    }

    private void shutdown() {
        token.cancel("live podcast stopped");
        AiConversation conv = activeConv;
        if (conv != null) {
            conv.cancel();
        }
        StreamingTtsPlayer p = player;
        if (p != null) {
            p.stop();
        }
        AiEngineManager.shutdownNow("live podcast stopped");
        stopForegroundCompat();
        stopSelf();
    }

    @Override
    public void onDestroy() {
        token.cancel("service destroyed");
        AiConversation conv = activeConv;
        if (conv != null) {
            conv.cancel();
        }
        StreamingTtsPlayer p = player;
        if (p != null) {
            p.stop();
        }
        worker.shutdownNow();
        super.onDestroy();
    }

    // ---- notification ----------------------------------------------------------------------

    private void goForeground(String text) {
        try {
            Notification n = build(text);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not go foreground", t);
        }
    }

    private Notification build(String text) {
        createChannel();
        Intent open = new Intent(this, NewsReaderListActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Intent stopIntent = new Intent(this, LivePodcastService.class).setAction(ACTION_STOP);
        PendingIntent stop = PendingIntent.getService(this, 1, stopIntent, PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.live_podcast_title))
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(content)
                .addAction(0, getString(R.string.live_podcast_stop), stop)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.live_podcast_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        nm.createNotificationChannel(channel);
    }

    private void stopForegroundCompat() {
        try {
            stopForeground(Service.STOP_FOREGROUND_REMOVE);
        } catch (Throwable t) {
            Log.w(TAG, "stopForeground failed", t);
        }
    }
}
