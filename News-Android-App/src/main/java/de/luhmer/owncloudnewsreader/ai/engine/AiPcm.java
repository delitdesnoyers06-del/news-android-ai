package de.luhmer.owncloudnewsreader.ai.engine;

/**
 * One synthesised utterance: mono PCM as 32-bit floats in {@code [-1, 1]}, plus the sample rate the
 * model produced them at. Flavor-neutral so {@code src/main} playback code can hold it without the
 * sherpa-onnx {@code GeneratedAudio} type on the classpath.
 */
public final class AiPcm {

    public final float[] samples;
    public final int sampleRate;

    public AiPcm(float[] samples, int sampleRate) {
        this.samples = samples;
        this.sampleRate = sampleRate;
    }
}
