package de.luhmer.owncloudnewsreader.ai.engine;

import java.io.File;

/**
 * Everything the flavor-specific TTS runtime needs to load a voice, with no reference to any
 * sherpa-onnx type — so {@code src/main} (and therefore the {@code mlNone} build) can name it.
 *
 * <p>The concrete on-disk file names (the {@code .onnx}, {@code tokens.txt}, {@code espeak-ng-data},
 * a Matcha vocoder) are derived by the runtime from {@link #unpackedRoot} and {@link #engine}, which
 * is exactly the sherpa-onnx archive layout — see {@code SherpaTts} in {@code src/mlGemma}.</p>
 */
public final class AiTtsSpec {

    public final String modelId;
    /** One of {@code AiCatalogEntry.TTS_ENGINE_*}. */
    public final String engine;
    /** The directory the model archive unpacked into. */
    public final File unpackedRoot;

    public AiTtsSpec(String modelId, String engine, File unpackedRoot) {
        this.modelId = modelId;
        this.engine = engine;
        this.unpackedRoot = unpackedRoot;
    }
}
