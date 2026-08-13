package de.luhmer.owncloudnewsreader.ai.engine.impl;

import android.content.Context;

import java.io.File;
import java.io.IOException;

import de.luhmer.owncloudnewsreader.ai.engine.AiEmbedder;
import de.luhmer.owncloudnewsreader.ai.engine.AiException;
import de.luhmer.owncloudnewsreader.ai.engine.AiLlm;
import de.luhmer.owncloudnewsreader.ai.engine.AiModelInfo;
import de.luhmer.owncloudnewsreader.ai.engine.AiTts;
import de.luhmer.owncloudnewsreader.ai.engine.AiTtsSpec;

/**
 * The {@code mlNone} half of the flavor seam. No AI runtime is on this classpath, so every engine
 * request degrades to {@code NOT_INSTALLED} — which is a state the pipeline already has to handle
 * (the model has not been downloaded yet) and therefore costs no extra branch.
 *
 * @see de.luhmer.owncloudnewsreader.ai.engine.AiEngineManager
 */
public final class AiEngines {

    private AiEngines() {
        // no instances
    }

    /** False in this flavor. There is nothing to load, ever. */
    public static boolean embedderSupported() {
        return false;
    }

    public static AiEmbedder openEmbedder(Context context, File taskFile) throws AiException {
        throw new AiException(AiException.Kind.NOT_INSTALLED,
                "this build has no on-device AI runtime");
    }

    /** False in this flavor. There is nothing to load, ever. */
    public static boolean llmSupported() {
        return false;
    }

    public static AiLlm openLlm(AiModelInfo info, File cacheDir, boolean gpu) throws AiException {
        throw new AiException(AiException.Kind.NOT_INSTALLED,
                "this build has no on-device AI runtime");
    }

    /** False in this flavor: no sherpa-onnx on the classpath. */
    public static boolean ttsSupported() {
        return false;
    }

    public static AiTts openTts(AiTtsSpec spec) throws AiException {
        throw new AiException(AiException.Kind.NOT_INSTALLED,
                "this build has no on-device AI runtime");
    }

    public static void extractTarBz2(File archive, File destDir) throws IOException {
        throw new IOException("no archive support in this build");
    }
}
