package de.luhmer.owncloudnewsreader.ai.engine;

/**
 * A loaded on-device neural voice. Implemented once per {@code ml} flavor: {@code SherpaTts} in
 * {@code mlGemma}, and never instantiated in {@code mlNone} (there {@code AiEngines.openTts} throws
 * {@link AiException.Kind#NOT_INSTALLED}).
 *
 * <p>Synthesis is sentence-at-a-time on purpose. sherpa-onnx TTS models are non-autoregressive, so a
 * whole chunk is produced in one blocking call; the article is streamed by feeding
 * {@link #synthesize} the same sentence chunks the native engine already splits, playing chunk N
 * while chunk N+1 is being generated. Not thread-safe: one call at a time.</p>
 */
public interface AiTts {

    /**
     * Synthesises {@code text} to PCM. Blocking; call off the main thread.
     *
     * @param speakerId the voice index, clamped by the implementation to {@code [0, numSpeakers)}
     * @param speed     1.0 = the model's natural rate; &gt;1 faster
     */
    AiPcm synthesize(String text, int speakerId, float speed) throws AiException;

    /** How many voices this model ships with; speaker ids are {@code [0, numSpeakers)}. */
    int numSpeakers();

    /** Releases the native model. Idempotent. */
    void close();
}
