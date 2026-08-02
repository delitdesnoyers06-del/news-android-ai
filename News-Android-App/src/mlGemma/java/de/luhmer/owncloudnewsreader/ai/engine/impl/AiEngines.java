package de.luhmer.owncloudnewsreader.ai.engine.impl;

import android.content.Context;

import java.io.File;

import de.luhmer.owncloudnewsreader.ai.engine.AiEmbedder;
import de.luhmer.owncloudnewsreader.ai.engine.AiException;
import de.luhmer.owncloudnewsreader.ai.engine.AiLlm;
import de.luhmer.owncloudnewsreader.ai.engine.AiModelInfo;

/**
 * The {@code mlGemma} half of the flavor seam: this class exists twice at the same fully-qualified
 * name, once per {@code ml} source set, so that {@code AiEngineManager} in {@code src/main} can name
 * it without dragging MediaPipe or LiteRT-LM into the {@code mlNone} classpath.
 *
 * @see de.luhmer.owncloudnewsreader.ai.engine.AiEngineManager
 */
public final class AiEngines {

    private AiEngines() {
        // no instances
    }

    /** True in this flavor: the MediaPipe text runtime is on the classpath. */
    public static boolean embedderSupported() {
        return true;
    }

    public static AiEmbedder openEmbedder(Context context, File taskFile) throws AiException {
        return MediaPipeEmbedder.open(context, taskFile);
    }

    /** True in this flavor: LiteRT-LM is on the classpath. */
    public static boolean llmSupported() {
        return true;
    }

    /**
     * Loads a generative model. Blocking and slow; the load watchdog belongs to the caller.
     *
     * @param cacheDir {@code EngineConfig.cacheDir} — internal storage, never the weights' volume
     */
    public static AiLlm openLlm(AiModelInfo info, File cacheDir, boolean gpu) throws AiException {
        return LiteRtLlm.open(info, cacheDir, gpu);
    }
}
