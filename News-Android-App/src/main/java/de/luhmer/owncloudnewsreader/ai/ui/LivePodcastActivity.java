package de.luhmer.owncloudnewsreader.ai.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.MenuItem;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;

import de.luhmer.owncloudnewsreader.R;
import de.luhmer.owncloudnewsreader.helper.ThemeChooser;
import de.luhmer.owncloudnewsreader.services.LivePodcastService;

/**
 * The live-podcast screen: shows the narration as the LLM streams it and drives the
 * {@link LivePodcastService} (play/pause/stop).
 *
 * <p>The service owns the pipeline and the audio; this Activity is a thin bound view, so it survives
 * rotation and being backgrounded — on (re)bind it seeds itself from the service's transcript and
 * state snapshot.</p>
 */
public class LivePodcastActivity extends AppCompatActivity implements LivePodcastService.Listener {

    public static final String EXTRA_DIGEST_ID = LivePodcastService.EXTRA_DIGEST_ID;

    private TextView status;
    private TextView transcript;
    private ScrollView scroll;
    private MaterialButton playPause;

    private LivePodcastService service;
    private boolean bound;

    /** Starts the foreground service and opens the screen. */
    public static void launch(Context context, long digestId) {
        Intent svc = new Intent(context, LivePodcastService.class)
                .putExtra(LivePodcastService.EXTRA_DIGEST_ID, digestId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svc);
        } else {
            context.startService(svc);
        }
        context.startActivity(new Intent(context, LivePodcastActivity.class)
                .putExtra(EXTRA_DIGEST_ID, digestId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((LivePodcastService.LocalBinder) binder).getService();
            bound = true;
            service.setListener(LivePodcastActivity.this);
            // Seed from the current snapshot (handles a rebind after rotation).
            transcript.setText(service.getTranscript());
            onState(service.getState());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            service = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeChooser.chooseTheme(this);
        super.onCreate(savedInstanceState);
        ThemeChooser.afterOnCreate(this);

        setContentView(R.layout.activity_live_podcast);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.live_podcast_title);
        }

        status = findViewById(R.id.podcast_status);
        transcript = findViewById(R.id.podcast_transcript);
        scroll = findViewById(R.id.podcast_scroll);
        playPause = findViewById(R.id.btn_play_pause);

        playPause.setOnClickListener(v -> {
            if (bound && service != null) {
                service.togglePlayPause();
            }
        });
        findViewById(R.id.btn_stop_podcast).setOnClickListener(v -> {
            if (bound && service != null) {
                service.stopFromUser();
            }
            finish();
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        bindService(new Intent(this, LivePodcastService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (bound) {
            service.setListener(null);
            unbindService(connection);
            bound = false;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---- LivePodcastService.Listener -------------------------------------------------------

    @Override
    public void onDelta(String delta) {
        transcript.append(delta);
        scroll.post(() -> scroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    @Override
    public void onState(LivePodcastService.State state) {
        switch (state) {
            case PREPARING:
                status.setText(R.string.live_podcast_preparing);
                break;
            case PLAYING:
                status.setText(R.string.live_podcast_playing);
                playPause.setText(R.string.live_podcast_pause);
                break;
            case PAUSED:
                status.setText(R.string.live_podcast_paused);
                playPause.setText(R.string.live_podcast_play);
                break;
            case DONE:
                status.setText(R.string.live_podcast_done);
                playPause.setEnabled(false);
                break;
            case ERROR:
                String err = service != null ? service.getError() : null;
                status.setText(err != null ? err : getString(R.string.live_podcast_error));
                playPause.setEnabled(false);
                break;
            default:
                break;
        }
    }
}
