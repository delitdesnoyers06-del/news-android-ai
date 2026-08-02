package de.luhmer.owncloudnewsreader.ai.download;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.greenrobot.eventbus.EventBus;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import de.luhmer.owncloudnewsreader.NewsReaderListActivity;
import de.luhmer.owncloudnewsreader.R;
import de.luhmer.owncloudnewsreader.SettingsActivity;
import de.luhmer.owncloudnewsreader.ai.AiCapability;
import de.luhmer.owncloudnewsreader.ai.AiFeature;
import de.luhmer.owncloudnewsreader.ai.engine.AiModelInfo;
import de.luhmer.owncloudnewsreader.ai.engine.CancelToken;
import de.luhmer.owncloudnewsreader.ai.model.AiCatalogEntry;
import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.AiModelRegistry;

/**
 * A plain foreground {@link Service} that downloads one model at a time, verifies it, and opens it
 * once to prove it works.
 *
 * <p>Deliberately <b>not</b> WorkManager and not {@code JobIntentService}: a 2.6 GB transfer exceeds
 * JobScheduler's ~10 minute window, and this is user-initiated and unbounded. It copies
 * {@code DownloadWebPageService}'s notification and executor shape but not its class — that one has
 * (had) no {@code foregroundServiceType} and therefore crashed on targetSdk 34+.
 *
 * <p>Declared in {@code src/mlGemma/AndroidManifest.xml} only, so the {@code mlNone} artifact never
 * even declares a service it could not use.
 *
 * <p>The smoke load runs <b>inside this service</b>, with its own "Preparing model…" progress line,
 * so the 180 s it may legitimately take happens while the user still has a foreground notification
 * explaining it rather than in silence after the progress bar hits 100 %.
 */
public class AiModelDownloadService extends Service {

    private static final String TAG = "AiModelDownload";

    public static final String ACTION_DOWNLOAD = "de.luhmer.owncloudnewsreader.ai.DOWNLOAD";
    public static final String ACTION_CANCEL = "de.luhmer.owncloudnewsreader.ai.CANCEL_DOWNLOAD";
    public static final String EXTRA_MODEL_ID = "model_id";

    private static final int NOTIFICATION_ID = 4712;
    private static final String CHANNEL_ID = "ai_model_download";

    /** Visible to the UI so a row can render "downloading" without subscribing first. */
    private static volatile String activeModelId;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ai-model-download");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private CancelToken token = new CancelToken();

    public static void start(Context context, String modelId) {
        Intent i = new Intent(context, AiModelDownloadService.class);
        i.setAction(ACTION_DOWNLOAD);
        i.putExtra(EXTRA_MODEL_ID, modelId);
        context.startService(i);
    }

    public static void cancel(Context context) {
        Intent i = new Intent(context, AiModelDownloadService.class);
        i.setAction(ACTION_CANCEL);
        context.startService(i);
    }

    public static String activeModelId() {
        return activeModelId;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        if (ACTION_CANCEL.equals(intent.getAction())) {
            token.cancel("user cancelled");
            return START_NOT_STICKY;
        }
        final String modelId = intent.getStringExtra(EXTRA_MODEL_ID);
        if (modelId == null || !running.compareAndSet(false, true)) {
            // One transfer at a time: two concurrent multi-gigabyte writes is not a feature.
            return START_NOT_STICKY;
        }
        activeModelId = modelId;
        token = new CancelToken();
        goForeground(modelId, 0, 100, getString(R.string.ai_model_download));
        worker.execute(() -> {
            try {
                run(modelId);
            } catch (Throwable t) {
                Log.e(TAG, "download crashed", t);
                post(new AiModelDownloadEvent(modelId, AiModelDownloadEvent.Phase.FAILED, 0, 0,
                        AiModelDownloader.CODE_IO, String.valueOf(t.getMessage())));
            } finally {
                activeModelId = null;
                running.set(false);
                stopForegroundCompat();
                stopSelf();
            }
        });
        return START_NOT_STICKY;
    }

    private void run(String modelId) {
        AiDb db = aiDb();
        AiModelRepository repo = new AiModelRepository(this, db);
        final AiCatalogEntry entry = repo.catalog().byId(modelId);
        if (entry == null) {
            post(new AiModelDownloadEvent(modelId, AiModelDownloadEvent.Phase.FAILED, 0, 0,
                    AiModelDownloader.CODE_IO, "unknown model"));
            return;
        }
        if (!AiCapability.downloadOk(this, entry.sizeBytes)) {
            post(new AiModelDownloadEvent(modelId, AiModelDownloadEvent.Phase.FAILED, 0,
                    entry.sizeBytes, AiModelDownloader.CODE_NO_SPACE, null));
            return;
        }
        AiModelRegistry registry = db == null ? null : new AiModelRegistry(db);
        if (registry != null) {
            registry.register(entry.id, entry.isLlm()
                    ? AiModelRegistry.KIND_LLM : AiModelRegistry.KIND_EMBEDDER,
                    entry.sizeBytes, entry.sha256);
            registry.setState(entry.id, AiModelRegistry.STATE_PARTIAL, null, null);
        }
        post(new AiModelDownloadEvent(entry.id, AiModelDownloadEvent.Phase.STARTED, 0,
                entry.sizeBytes, null, null));

        final long[] lastNotified = {0L};
        AiModelDownloader.Outcome outcome = new AiModelDownloader().download(
                repo, entry, hfToken(), token,
                (id, received, total) -> {
                    post(AiModelDownloadEvent.progress(id, received, total));
                    // The notification is the expensive part; 1 % steps are plenty.
                    long step = Math.max(1L, total / 100L);
                    if (received - lastNotified[0] >= step) {
                        lastNotified[0] = received;
                        goForeground(id, received, total, getString(R.string.ai_model_download));
                    }
                });

        if (!outcome.ok()) {
            if (registry != null) {
                boolean broken = AiModelDownloader.CODE_CHECKSUM.equals(outcome.code)
                        || AiModelDownloader.CODE_SIZE_MISMATCH.equals(outcome.code);
                registry.setState(entry.id,
                        broken ? AiModelRegistry.STATE_BROKEN : AiModelRegistry.STATE_PARTIAL,
                        null, outcome.code);
                registry.setProgress(entry.id, outcome.received, entry.sizeBytes, null);
            }
            AiModelDownloadEvent.Phase phase =
                    AiModelDownloader.CODE_CANCELLED.equals(outcome.code)
                            ? AiModelDownloadEvent.Phase.CANCELLED
                            : AiModelDownloadEvent.Phase.FAILED;
            post(new AiModelDownloadEvent(entry.id, phase, outcome.received, entry.sizeBytes,
                    outcome.code, outcome.message));
            return;
        }

        if (registry != null) {
            registry.setState(entry.id, AiModelRegistry.STATE_INSTALLED,
                    repo.fileFor(entry).getAbsolutePath(), null);
            registry.setProgress(entry.id, entry.sizeBytes, entry.sizeBytes, null);
        }

        if (entry.isLlm()) {
            // The 180 s smoke load, with its own notification line so the wait is explained.
            post(new AiModelDownloadEvent(entry.id, AiModelDownloadEvent.Phase.PREPARING,
                    entry.sizeBytes, entry.sizeBytes, null, null));
            goForeground(entry.id, entry.sizeBytes, entry.sizeBytes,
                    getString(R.string.ai_model_preparing));
            AiModelInfo info = repo.infoFor(entry.id);
            if (info != null) {
                AiModelProbe.Result probe = AiModelProbe.run(this, db, info);
                if (!probe.loaded && registry != null) {
                    registry.setState(entry.id, AiModelRegistry.STATE_BROKEN, null, probe.error);
                    post(new AiModelDownloadEvent(entry.id, AiModelDownloadEvent.Phase.FAILED,
                            entry.sizeBytes, entry.sizeBytes, "smoke", probe.error));
                    return;
                }
            }
        }
        post(new AiModelDownloadEvent(entry.id, AiModelDownloadEvent.Phase.INSTALLED,
                entry.sizeBytes, entry.sizeBytes, null, null));
    }

    private String hfToken() {
        SharedPreferences prefs = AiFeature.prefsOf(this);
        return prefs.getString(SettingsActivity.EDT_AI_HF_TOKEN, "");
    }

    private AiDb aiDb() {
        try {
            return new DatabaseConnectionOrm(this).aiDb();
        } catch (Throwable t) {
            Log.w(TAG, "AI database unavailable", t);
            return null;
        }
    }

    private static void post(AiModelDownloadEvent event) {
        try {
            EventBus.getDefault().post(event);
        } catch (Throwable t) {
            Log.w(TAG, "event post failed", t);
        }
    }

    // ---- notification ----------------------------------------------------------------------

    private void goForeground(String modelId, long received, long total, String title) {
        try {
            Notification n = build(modelId, received, total, title);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // The typed form is mandatory on 34+ or startForeground throws.
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, n);
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not go foreground", t);
        }
    }

    private Notification build(String modelId, long received, long total, String title) {
        createChannel();
        Intent open = new Intent(this, NewsReaderListActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE);
        Intent cancelIntent = new Intent(this, AiModelDownloadService.class);
        cancelIntent.setAction(ACTION_CANCEL);
        PendingIntent cancel = PendingIntent.getService(this, 1, cancelIntent,
                PendingIntent.FLAG_IMMUTABLE);
        int percent = total <= 0 ? 0 : (int) Math.min(100L, received * 100L / total);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(modelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(content)
                .addAction(0, getString(R.string.ai_model_cancel), cancel)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, percent, total <= 0)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.ai_notification_channel_download),
                NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null);
        channel.enableVibration(false);
        nm.createNotificationChannel(channel);
    }

    private void stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(Service.STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Throwable t) {
            Log.w(TAG, "stopForeground failed", t);
        }
    }

    @Override
    public void onDestroy() {
        token.cancel("service destroyed");
        worker.shutdownNow();
        super.onDestroy();
    }
}
