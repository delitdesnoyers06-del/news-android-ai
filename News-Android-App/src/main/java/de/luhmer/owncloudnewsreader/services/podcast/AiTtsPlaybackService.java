package de.luhmer.owncloudnewsreader.services.podcast;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import de.luhmer.owncloudnewsreader.ai.engine.AiPcm;
import de.luhmer.owncloudnewsreader.ai.engine.AiTts;
import de.luhmer.owncloudnewsreader.ai.engine.AiTtsSpec;
import de.luhmer.owncloudnewsreader.ai.engine.impl.AiEngines;
import de.luhmer.owncloudnewsreader.model.MediaItem;
import de.luhmer.owncloudnewsreader.model.TTSItem;

/**
 * Reads an article aloud with an on-device neural voice (sherpa-onnx, {@code mlGemma} flavor).
 *
 * <p><b>Streaming.</b> The models are non-autoregressive, so a whole sentence is synthesised in one
 * blocking call. The article is streamed at sentence granularity, reusing {@link TtsTextSplitter}: a
 * producer thread synthesises chunk <i>N+1</i> into a small bounded queue while a consumer thread
 * writes chunk <i>N</i> to a streaming {@link AudioTrack}. The {@code AudioTrack.write} back-pressure
 * paces the producer, so memory stays bounded no matter how long the article is.</p>
 *
 * <p><b>Progress.</b> Neural TTS has no native millisecond timeline, so — exactly like the fixed
 * {@link TTSPlaybackService} — position and duration are expressed on a character scale
 * ({@link #MS_PER_CHAR}), with the within-chunk fraction taken from the {@code AudioTrack} playback
 * head so the bar advances smoothly, not in per-sentence steps.</p>
 */
public class AiTtsPlaybackService extends PlaybackService {

    private static final String TAG = "AiTtsPlaybackService";

    /** Rough speaking rate: ~16 chars/s at speed 1.0. Only the ratio matters for the progress bar. */
    static final int MS_PER_CHAR = 62;
    // Larger than the native splitter's 200: each neural synth call has fixed overhead, so longer
    // chunks mean fewer calls and more audio per call — the synthesiser stays ahead of playback and
    // the reading does not stutter between sentences. Still small enough that the first sentence
    // starts within a second or two.
    private static final int CHUNK_SIZE = 400;
    // Prefetch depth: how many synthesised chunks may wait ahead of the one playing. Deep enough to
    // ride out a slow synth (e.g. the heavier Kokoro model) without the AudioTrack starving.
    private static final int QUEUE_CAPACITY = 6;

    private final AiTtsSpec spec;
    private final int speakerId;
    private final List<String> chunks;
    private final int[] prefixChars;
    private final int totalChars;

    private volatile AiTts engine;
    private volatile AudioTrack audioTrack;
    private volatile float speed = 1.0f;
    private volatile boolean released;

    // Progress state, all written by the consumer thread and read by getCurrentPosition().
    private volatile int currentChunk;
    private volatile int chunkStartFrame;
    private volatile int chunkFrames;

    private Thread producer;
    private Thread consumer;
    private final BlockingQueue<Object> pcmQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private static final Object END = new Object();

    public AiTtsPlaybackService(Context context, PodcastStatusListener listener, MediaItem mediaItem,
                                AiTtsSpec spec, int speakerId) {
        super(listener, mediaItem);
        this.spec = spec;
        this.speakerId = Math.max(0, speakerId);
        String text = ((TTSItem) mediaItem).text;
        this.chunks = TtsTextSplitter.split(text, CHUNK_SIZE);
        this.prefixChars = new int[Math.max(1, chunks.size())];
        int sum = 0;
        for (int i = 0; i < chunks.size(); i++) {
            prefixChars[i] = sum;
            sum += chunks.get(i).length();
        }
        this.totalChars = Math.max(1, sum);
        setStatus(PlaybackStateCompat.STATE_CONNECTING);
        start();
    }

    private void start() {
        if (chunks.isEmpty()) {
            setStatus(PlaybackStateCompat.STATE_ERROR);
            return;
        }
        consumer = new Thread(this::runConsumer, "ai-tts-play");
        consumer.setDaemon(true);
        producer = new Thread(this::runProducer, "ai-tts-synth");
        producer.setDaemon(true);
        consumer.start();
        producer.start();
    }

    /**
     * Opens the engine, synthesises each chunk into the queue, and — crucially — <b>closes the engine
     * on this same thread</b> in the finally block. The native {@code generate()} is not
     * interruptible, so the engine must never be released from another thread while a generate is in
     * flight (that frees the ONNX Runtime session under it and aborts the process). {@code destroy()}
     * only signals {@link #released}; the loop exits after the current chunk and this finally frees it.
     */
    private void runProducer() {
        if (released) {
            putQuietly(END);
            return;
        }
        AiTts local;
        try {
            if (spec == null) {
                fail("no neural voice selected");
                putQuietly(END);
                return;
            }
            local = AiEngines.openTts(spec);
            engine = local;
        } catch (Throwable t) {
            fail("voice load failed: " + t.getMessage());
            putQuietly(END);
            return;
        }
        try {
            for (int i = 0; i < chunks.size() && !released; i++) {
                try {
                    AiPcm pcm = local.synthesize(chunks.get(i), speakerId, speed);
                    if (pcm == null || pcm.samples == null) {
                        continue;
                    }
                    pcmQueue.put(new Chunk(i, pcm));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    Log.e(TAG, "synthesis failed for chunk " + i, t);
                    // Skip the bad sentence rather than aborting the whole article.
                }
            }
            putQuietly(END);
        } finally {
            engine = null;
            try {
                local.close();
            } catch (Throwable ignored) {
                // closing a half-loaded engine is best-effort
            }
        }
    }

    /** Drains the queue and owns the AudioTrack lifecycle: it is released here, never from destroy(). */
    private void runConsumer() {
        try {
            while (!released) {
                Object item = pcmQueue.take();
                if (item == END) {
                    break;
                }
                Chunk chunk = (Chunk) item;
                playChunk(chunk);
            }
            if (!released) {
                drainAndComplete();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Log.e(TAG, "playback failed", t);
            fail("playback failed");
        } finally {
            AudioTrack track = audioTrack;
            audioTrack = null;
            if (track != null) {
                try {
                    track.pause();
                    track.flush();
                    track.release();
                } catch (Throwable ignored) {
                    // releasing a half-built track is best-effort
                }
            }
        }
    }

    private void playChunk(Chunk chunk) {
        AudioTrack track = ensureTrack(chunk.pcm.sampleRate);
        if (track == null) {
            return;
        }
        currentChunk = chunk.index;
        chunkStartFrame = framesWritten;
        chunkFrames = chunk.pcm.samples.length;
        if (getStatus() != PlaybackStateCompat.STATE_PLAYING) {
            track.play();
            setStatus(PlaybackStateCompat.STATE_PLAYING);
        }
        int off = 0;
        float[] s = chunk.pcm.samples;
        while (off < s.length && !released) {
            int n = track.write(s, off, s.length - off, AudioTrack.WRITE_BLOCKING);
            if (n <= 0) {
                break;
            }
            off += n;
            framesWritten += n;
        }
    }

    private volatile int framesWritten;

    private AudioTrack ensureTrack(int sampleRate) {
        if (audioTrack != null) {
            return audioTrack;
        }
        int minBuf = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT);
        if (minBuf <= 0) {
            minBuf = sampleRate * 4;      // one second of float mono as a fallback
        }
        // A generous track buffer (~2 s of float mono) so a slow neural synth (Kokoro) cannot
        // starve the track between chunks — an underrun on some devices plays back as hiss, not
        // just a gap. Paired with the prefetch queue this keeps playback continuous and clean.
        int bufBytes = Math.max(minBuf * 4, sampleRate * 2 * 4);
        try {
            AudioTrack track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            audioTrack = track;
            return track;
        } catch (Throwable t) {
            Log.e(TAG, "AudioTrack init failed", t);
            fail("audio init failed");
            return null;
        }
    }

    private void drainAndComplete() {
        AudioTrack track = audioTrack;
        if (track != null) {
            try {
                // Let the buffered tail play out before reporting completion.
                while (!released && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING
                        && track.getPlaybackHeadPosition() < framesWritten) {
                    Thread.sleep(80);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (!released) {
            podcastCompleted();
        }
    }

    // ---- PlaybackService contract ----------------------------------------------------------

    @Override
    public void play() {
        AudioTrack track = audioTrack;
        if (track != null) {
            track.play();
            setStatus(PlaybackStateCompat.STATE_PLAYING);
        }
    }

    @Override
    public void pause() {
        AudioTrack track = audioTrack;
        if (track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            track.pause();
            setStatus(PlaybackStateCompat.STATE_PAUSED);
        }
    }

    @Override
    public void playbackSpeedChanged(float currentPlaybackSpeed) {
        // Applies to chunks synthesised from here on; already-generated audio keeps its rate.
        this.speed = currentPlaybackSpeed <= 0f ? 1.0f : currentPlaybackSpeed;
    }

    @Override
    public void destroy() {
        // Only signal + interrupt + halt audio. The engine and AudioTrack are freed by their owning
        // threads (runProducer / runConsumer finally blocks) so a blocking native generate() is never
        // torn out from under the synth thread — that was the Scudo double-free in libonnxruntime.
        released = true;
        if (producer != null) {
            producer.interrupt();
        }
        if (consumer != null) {
            consumer.interrupt();
        }
        AudioTrack track = audioTrack;
        if (track != null) {
            try {
                // Stop sound immediately and unblock a consumer stuck in a blocking write; the
                // consumer thread then releases the track in its finally block.
                track.pause();
                track.flush();
            } catch (Throwable ignored) {
                // best-effort halt
            }
        }
    }

    @Override
    public int getCurrentPosition() {
        int base = prefixChars[Math.min(currentChunk, prefixChars.length - 1)];
        float fraction = 0f;
        AudioTrack track = audioTrack;
        if (track != null && chunkFrames > 0) {
            int within = track.getPlaybackHeadPosition() - chunkStartFrame;
            fraction = Math.max(0f, Math.min(1f, within / (float) chunkFrames));
        }
        int chunkLen = currentChunk < chunks.size() ? chunks.get(currentChunk).length() : 0;
        return (int) ((base + fraction * chunkLen) * (long) MS_PER_CHAR);
    }

    @Override
    public int getTotalDuration() {
        return totalChars * MS_PER_CHAR;
    }

    // ---- helpers ---------------------------------------------------------------------------

    private void fail(String reason) {
        Log.e(TAG, reason);
        setStatus(PlaybackStateCompat.STATE_ERROR);
    }

    private void putQuietly(Object item) {
        try {
            pcmQueue.put(item);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class Chunk {
        final int index;
        final AiPcm pcm;

        Chunk(int index, AiPcm pcm) {
            this.index = index;
            this.pcm = pcm;
        }
    }
}
