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
import android.text.Html;
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
import de.luhmer.owncloudnewsreader.ai.engine.AiLlm;
import de.luhmer.owncloudnewsreader.ai.engine.AiModelInfo;
import de.luhmer.owncloudnewsreader.ai.engine.AiPromptSpec;
import de.luhmer.owncloudnewsreader.ai.engine.AiTtsSpec;
import de.luhmer.owncloudnewsreader.ai.engine.CancelToken;
import de.luhmer.owncloudnewsreader.ai.engine.impl.AiEngines;
import de.luhmer.owncloudnewsreader.ai.model.AiCatalogEntry;
import de.luhmer.owncloudnewsreader.ai.prompt.AiPromptBuilder;
import de.luhmer.owncloudnewsreader.ai.prompt.AiPrompts;
import de.luhmer.owncloudnewsreader.ai.prompt.PromptTemplate;
import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.FullTextStore;
import de.luhmer.owncloudnewsreader.database.model.RssItem;
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

    // The podcast is generated as several short segments — an intro, one paragraph per article, and
    // a conclusion — rather than one long call. Segmenting gives a small on-device model a much
    // better shot at length and structure, and each segment streams and starts speaking as soon as
    // it is generated. Per-segment token caps keep every call's KV cache small.
    private static final int INTRO_MAX_TOKENS = 220;
    private static final int BRIEF_MAX_TOKENS = 400;
    private static final int CONCLUSION_MAX_TOKENS = 220;
    /** How many articles get their own paragraph. Highest-ranked first; the rest are omitted. */
    private static final int MAX_BRIEFS = 10;
    /** Plain-text article context fed to a brief, trimmed so the prompt stays small. */
    private static final int MAX_CONTEXT_CHARS = 800;
    private static final long TIMEOUT_MS = 120_000L;

    public enum State { PREPARING, PLAYING, PAUSED, DONE, ERROR }

    /** Observes transcript/state; the bound {@code LivePodcastActivity} is the only listener. */
    public interface Listener {
        /** The full transcript so far. Idempotent: the view just renders it, so re-delivery is safe. */
        void onTranscript(String fullText);
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
    private volatile boolean uiPushScheduled;

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
        // startForegroundService (the caller) obliges us to call startForeground promptly on every
        // start, including the early-return paths below — otherwise Android 8+ crashes the service
        // with ForegroundServiceDidNotStartInTimeException.
        goForeground(getString(R.string.live_podcast_preparing));
        final long digestId = intent == null ? -1 : intent.getLongExtra(EXTRA_DIGEST_ID, -1);
        if (digestId < 0 || !running.compareAndSet(false, true)) {
            // Malformed request, or an episode is already running (one at a time). Release only when
            // nothing is actually running, so we never kill an in-progress episode.
            if (!running.get()) {
                stopForegroundCompat();
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        token = new CancelToken();
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
        DatabaseConnectionOrm dbConn = new DatabaseConnectionOrm(ctx);
        AiDb db = dbConn.aiDb();
        if (db == null) {
            fail(getString(R.string.live_podcast_unavailable));
            return;
        }

        List<Brief> briefs = buildBriefs(dbConn, db, digestId);
        if (briefs.isEmpty()) {
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
        // A plain headline list for the intro/conclusion (not the full briefs).
        final StringBuilder overview = new StringBuilder();
        for (Brief b : briefs) {
            overview.append("- ").append(b.title).append('\n');
        }
        final String overviewBlock = overview.toString();

        StreamingTtsPlayer p = buildPlayer(ctx, db, prefs, locale);
        this.player = p;
        final SentenceBuffer buffer = new SentenceBuffer(p::offer);

        setState(State.PLAYING);
        goForeground(getString(R.string.live_podcast_playing));

        final boolean gpu = prefs.getBoolean(SettingsActivity.CB_AI_GPU_BACKEND, false);
        try {
            new AiEngineManager(ctx, db).withLlm(model, gpu, token, llm -> {
                // Intro.
                streamSegment(llm, AiPrompts.podcastIntroSystem(ctx), lang, INTRO_MAX_TOKENS, buffer,
                        AiPrompts.podcastIntroUser(ctx).render(PromptTemplate.vars(
                                "items", overviewBlock, "lang", lang)));
                // One paragraph per article.
                for (Brief b : briefs) {
                    if (token.isCancelled()) {
                        break;
                    }
                    streamSegment(llm, AiPrompts.podcastBriefSystem(ctx), lang, BRIEF_MAX_TOKENS, buffer,
                            AiPrompts.podcastBriefUser(ctx).render(PromptTemplate.vars(
                                    "title", b.title, "context", b.context, "lang", lang)));
                }
                // Conclusion.
                if (!token.isCancelled()) {
                    streamSegment(llm, AiPrompts.podcastConclusionSystem(ctx), lang,
                            CONCLUSION_MAX_TOKENS, buffer,
                            AiPrompts.podcastConclusionUser(ctx).render(PromptTemplate.vars(
                                    "items", overviewBlock, "lang", lang)));
                }
                return null;
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

    /**
     * Runs one podcast segment (intro, a per-article brief, or the conclusion) as its own short
     * conversation, streaming the text to the transcript and the TTS buffer. A fresh conversation
     * per segment resets the KV cache so context does not grow across the whole episode. Each
     * segment ends with a flushed sentence and a paragraph break so the voice pauses between them.
     * A failing segment is logged and skipped rather than aborting the episode.
     */
    private void streamSegment(AiLlm llm, PromptTemplate systemTemplate, String lang, int maxTokens,
                               SentenceBuffer buffer, String userText) {
        AiConversation conv = null;
        try {
            AiPromptSpec spec = AiPromptSpec.builder()
                    .systemInstruction(systemTemplate.render(PromptTemplate.vars("lang", lang)))
                    .maxOutputTokens(maxTokens)
                    // Greedy: faithful narration, not a creative riff.
                    .sampler(1, 1.0d, 0.0d, 0)
                    .perCallTimeoutMs(TIMEOUT_MS)
                    .build();
            conv = llm.start(spec);
            activeConv = conv;
            conv.send(userText, null, delta -> {
                appendTranscript(delta);
                buffer.feed(delta);
            });
        } catch (Throwable t) {
            Log.w(TAG, "podcast segment failed; skipping", t);
        } finally {
            activeConv = null;
            if (conv != null) {
                try {
                    conv.close();
                } catch (Throwable ignored) {
                    // best-effort close
                }
            }
            // Close out the segment: emit its trailing sentence and separate it from the next.
            buffer.flush();
            appendTranscript("\n\n");
        }
    }

    /** Builds the per-article briefs (headline + faithful context) from the digest, capped. */
    private List<Brief> buildBriefs(DatabaseConnectionOrm dbConn, AiDb db, long digestId) {
        List<Brief> out = new ArrayList<>();
        FullTextStore fullText = new FullTextStore(db);
        for (AiDigests.Entry e : AiDigests.entries(db, digestId)) {
            if (out.size() >= MAX_BRIEFS) {
                break;
            }
            String title = e.title == null ? "" : e.title.trim();
            if (title.isEmpty()) {
                continue;
            }
            out.add(new Brief(title, articleContext(dbConn, fullText, e)));
        }
        return out;
    }

    /** Assembles the faithful context for one article: why it was selected plus a body snippet. */
    private String articleContext(DatabaseConnectionOrm dbConn, FullTextStore fullText,
                                  AiDigests.Entry e) {
        StringBuilder sb = new StringBuilder();
        if (e.why != null && !e.why.trim().isEmpty()) {
            sb.append("Why it was selected: ").append(e.why.trim()).append('\n');
        }
        String body = "";
        try {
            if (fullText.hasOk(e.rssItemId)) {
                body = htmlToText(fullText.contentHtml(e.rssItemId));
            }
        } catch (Throwable ignored) {
            // fall through to the RSS body
        }
        if (body.isEmpty()) {
            try {
                RssItem item = dbConn.getRssItemById(e.rssItemId);
                if (item != null) {
                    body = htmlToText(item.getBody());
                }
            } catch (Throwable ignored) {
                // no body available; the headline + why still make a short brief
            }
        }
        if (!body.isEmpty()) {
            if (body.length() > MAX_CONTEXT_CHARS) {
                body = body.substring(0, MAX_CONTEXT_CHARS);
            }
            sb.append("Article: ").append(body);
        }
        return sb.toString();
    }

    private static String htmlToText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString();
        return text.replaceAll("\\s+", " ").trim();
    }

    /** One article's material for its paragraph. */
    private static final class Brief {
        final String title;
        final String context;

        Brief(String title, String context) {
            this.title = title;
            this.context = context;
        }
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
        pushTranscript();
    }

    /**
     * Schedules a single coalesced push of the full transcript to the listener. Coalescing bounds
     * main-thread work (one render per frame regardless of token rate), and delivering the whole
     * transcript — rather than a delta — makes the render idempotent, so a rebind (rotation) that
     * re-pushes cannot duplicate or drop text.
     */
    private void pushTranscript() {
        if (uiPushScheduled) {
            return;
        }
        uiPushScheduled = true;
        main.post(() -> {
            uiPushScheduled = false;
            Listener l = listener;
            if (l != null) {
                l.onTranscript(getTranscript());
            }
        });
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
        if (l != null) {
            pushTranscript();      // seed the (re)bound view with the full transcript
        }
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
        worker.shutdownNow();
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
