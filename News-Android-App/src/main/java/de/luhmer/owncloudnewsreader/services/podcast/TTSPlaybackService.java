package de.luhmer.owncloudnewsreader.services.podcast;

import android.content.Context;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import java.util.List;
import java.util.Locale;

import de.luhmer.owncloudnewsreader.model.MediaItem;
import de.luhmer.owncloudnewsreader.model.TTSItem;

/**
 * Created by david on 31.01.17.
 */

public class TTSPlaybackService extends PlaybackService implements TextToSpeech.OnInitListener {

    private static final String TAG = "TTSPlaybackService";

    // A single speak() call drops text longer than getMaxSpeechInputLength(), so longer
    // articles have to be split and queued. The size is kept small on purpose: it stays clear
    // of multi byte scripts (e.g. Greek) and it is also the granularity we can resume at after
    // a pause, since TextToSpeech itself cannot pause and continue. The splitter cuts at
    // sentence ends, so the pieces still sound natural.
    private static final int CHUNK_SIZE = Math.min(TextToSpeech.getMaxSpeechInputLength(), 200);

    private static final String UTTERANCE_PREFIX = "tts_";

    // TextToSpeech has no millisecond timeline, so the progress bar is driven on a character scale:
    // a fixed ms-per-character, interpolated within the current chunk by wall clock. Only the ratio
    // position/duration matters to the bar; the constant keeps the time display plausible too.
    private static final int MS_PER_CHAR = 62;

    private TextToSpeech ttsController;
    private List<String> chunks;
    private int currentChunk;
    private String lastUtteranceId;

    // Progress timeline, computed once up front so getTotalDuration() is non-zero from the start
    // (updateMetadata reads it before the async engine init has produced a single sound).
    private int[] prefixChars;
    private int totalChars = 1;
    private volatile float speechRate = 1.0f;
    private volatile long chunkStartUptimeMs;

    public TTSPlaybackService(Context context, PodcastStatusListener podcastStatusListener, MediaItem mediaItem) {
        super(podcastStatusListener, mediaItem);

        // Split up front (pure, no engine needed) so the timeline exists before onInit runs.
        chunks = TtsTextSplitter.split(((TTSItem) mediaItem).text, CHUNK_SIZE);
        computeTimeline();

        try {
            ttsController = new TextToSpeech(context, this);
            setStatus(PlaybackStateCompat.STATE_CONNECTING);

            if(ttsController != null) {
                ttsController.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override
                    public void onStart(String utteranceId) {
                        // Remember the piece we are on so play() can resume here after a pause
                        currentChunk = indexOf(utteranceId);
                        // Anchor the within-chunk interpolation used by getCurrentPosition().
                        chunkStartUptimeMs = SystemClock.uptimeMillis();
                    }

                    @Override
                    public void onDone(String utteranceId) {
                        // Only finish once the last chunk was spoken
                        if (utteranceId != null && utteranceId.equals(lastUtteranceId)) {
                            podcastCompleted();
                        }
                    }

                    @Override
                    public void onError(String utteranceId) {
                        Log.e(TAG, "TTS error while speaking " + utteranceId);
                        setStatus(PlaybackStateCompat.STATE_ERROR);
                    }
                });
            } else {
                onInit(TextToSpeech.SUCCESS);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void destroy() {
        pause();
        if (ttsController != null) {
            ttsController.shutdown();
            ttsController = null;
        }
    }

    @Override
    public void play() {
        // Resume at the piece that was interrupted. Start fresh if nothing was prepared yet.
        if (ttsController != null && chunks != null && !chunks.isEmpty()) {
            speakFrom(currentChunk);
        } else {
            onInit(TextToSpeech.SUCCESS);
        }
    }

    @Override
    public void pause() {
        if (ttsController != null && ttsController.isSpeaking()) {
            ttsController.stop();
            setStatus(PlaybackStateCompat.STATE_PAUSED);
        }
    }

    @Override
    public void playbackSpeedChanged(float currentPlaybackSpeed) {
        ttsController.setSpeechRate(currentPlaybackSpeed);
        this.speechRate = currentPlaybackSpeed <= 0f ? 1.0f : currentPlaybackSpeed;
    }

    @Override
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            // Use the language the user chose for this article, else the device language so the
            // engine picks a fitting voice. The user can change engine and voice through the system
            // TTS settings shortcut in the app settings.
            ttsController.setLanguage(readingLocale());

            if (chunks == null || chunks.isEmpty()) {
                setStatus(PlaybackStateCompat.STATE_ERROR);
                return;
            }

            speakFrom(0);
        } else {
            Log.e("TTS", "Initialization Failed!");
            ttsController = null;
        }
    }

    /**
     * The locale to read this article in: the user's per-article override when set, else the
     * device default. An unsupported override falls back to the default rather than staying silent.
     */
    private Locale readingLocale() {
        String lang = ((TTSItem) getMediaItem()).ttsLanguage;
        if (lang == null || lang.isEmpty()) {
            return Locale.getDefault();
        }
        Locale locale = Locale.forLanguageTag(lang);
        int result = ttsController.isLanguageAvailable(locale);
        if (result == TextToSpeech.LANG_NOT_SUPPORTED || result == TextToSpeech.LANG_MISSING_DATA) {
            Log.w(TAG, "requested TTS language " + lang + " unavailable; using device default");
            return Locale.getDefault();
        }
        return locale;
    }

    private void speakFrom(int startIndex) {
        lastUtteranceId = UTTERANCE_PREFIX + (chunks.size() - 1);

        for (int i = startIndex; i < chunks.size(); i++) {
            int queueMode = (i == startIndex) ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
            int result = ttsController.speak(chunks.get(i), queueMode, null, UTTERANCE_PREFIX + i);
            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "Failed to queue chunk " + i);
                setStatus(PlaybackStateCompat.STATE_ERROR);
                return;
            }
        }
        setStatus(PlaybackStateCompat.STATE_PLAYING);
    }

    private void computeTimeline() {
        prefixChars = new int[Math.max(1, chunks.size())];
        int sum = 0;
        for (int i = 0; i < chunks.size(); i++) {
            prefixChars[i] = sum;
            sum += chunks.get(i).length();
        }
        totalChars = Math.max(1, sum);
    }

    /**
     * Non-zero from construction (see {@link #totalChars}); read by {@code updateMetadata} as
     * {@code METADATA_KEY_DURATION} to set the bar's maximum.
     */
    @Override
    public int getTotalDuration() {
        return totalChars * MS_PER_CHAR;
    }

    /**
     * Characters spoken so far on the same scale as {@link #getTotalDuration()}: the offset of the
     * current chunk, plus a wall-clock estimate of how far into that chunk the engine has read. Steps
     * per chunk would look frozen for long sentences, so the within-chunk fraction is interpolated.
     */
    @Override
    public int getCurrentPosition() {
        if (prefixChars == null || chunks == null || chunks.isEmpty()) {
            return 0;
        }
        int idx = Math.min(currentChunk, prefixChars.length - 1);
        int base = prefixChars[idx];
        int chunkLen = idx < chunks.size() ? chunks.get(idx).length() : 0;
        float fraction = 0f;
        if (getStatus() == PlaybackStateCompat.STATE_PLAYING && chunkLen > 0) {
            long estMs = (long) (chunkLen * MS_PER_CHAR / Math.max(0.25f, speechRate));
            if (estMs > 0) {
                long elapsed = SystemClock.uptimeMillis() - chunkStartUptimeMs;
                fraction = Math.max(0f, Math.min(1f, elapsed / (float) estMs));
            }
        }
        return (int) ((base + fraction * chunkLen) * (long) MS_PER_CHAR);
    }

    private int indexOf(String utteranceId) {
        if (utteranceId != null && utteranceId.startsWith(UTTERANCE_PREFIX)) {
            try {
                return Integer.parseInt(utteranceId.substring(UTTERANCE_PREFIX.length()));
            } catch (NumberFormatException e) {
                Log.e(TAG, "Unexpected utterance id " + utteranceId);
            }
        }
        return 0;
    }

}
