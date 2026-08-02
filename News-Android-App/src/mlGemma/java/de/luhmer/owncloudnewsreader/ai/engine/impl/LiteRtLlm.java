package de.luhmer.owncloudnewsreader.ai.engine.impl;

import android.util.Log;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.SamplerConfig;

import java.io.File;
import java.util.Collections;

import de.luhmer.owncloudnewsreader.ai.engine.AiConversation;
import de.luhmer.owncloudnewsreader.ai.engine.AiException;
import de.luhmer.owncloudnewsreader.ai.engine.AiLlm;
import de.luhmer.owncloudnewsreader.ai.engine.AiModelInfo;
import de.luhmer.owncloudnewsreader.ai.engine.AiOutputMode;
import de.luhmer.owncloudnewsreader.ai.engine.AiPromptSpec;

/**
 * The LiteRT-LM 0.15.0 binding. <b>Only this class and {@link LiteRtConversation} name a
 * {@code com.google.ai.edge.litertlm} type</b>, and both live in {@code src/mlGemma} so the
 * {@code mlNone} artifact never resolves the dependency.
 *
 * <p>Signatures verified by {@code javap} against the real AAR:</p>
 * <ul>
 *   <li>{@code EngineConfig} has <b>seven</b> constructor parameters in Java — Kotlin default
 *       arguments do not telescope for Java callers, so all seven must be passed.</li>
 *   <li>{@code ConversationConfig} does telescope, but only the <b>12-argument</b> form reaches
 *       {@code enableResponseFormat}, which {@code sendMessage} requires before it will accept a
 *       {@code ResponseFormat} at all (it throws {@code IllegalArgumentException} otherwise).</li>
 *   <li>{@code tools} is passed <b>empty</b> on purpose: {@code Conversation.resolveResponseFormat}
 *       applies the schema unchanged only when no tool is registered.</li>
 *   <li>{@code Message.Companion.user(...)} and {@code Contents.Companion.of(...)} are not
 *       {@code @JvmStatic}; they go through {@code .Companion}.</li>
 * </ul>
 */
final class LiteRtLlm implements AiLlm {

    private static final String TAG = "LiteRtLlm";

    private final Engine engine;
    private final AiModelInfo info;

    private LiteRtLlm(Engine engine, AiModelInfo info) {
        this.engine = engine;
        this.info = info;
    }

    /**
     * Loads the weights. <b>Blocking, and slow</b> — the first load of a 2.6 GB model writes the
     * rearranged-weight cache from scratch. The 180 s watchdog lives in the caller
     * ({@code AiEngineManager}), because it needs a thread this method cannot see.
     */
    static LiteRtLlm open(AiModelInfo info, File cacheDir, boolean gpu) throws AiException {
        try {
            Backend backend = gpu ? new Backend.GPU() : new Backend.CPU();
            // DO NOT "tidy" the nulls below into zeros or into `backend`.
            //
            // maxNumImages: the EngineConfig constructor validates
            //     maxNumImages == null || maxNumImages.intValue() > 0
            // and otherwise throws IllegalArgumentException("maxNumImages must be positive or
            // null (use the default from model or engine)."). Verified in the 0.15.0 bytecode:
            //     101: getfield maxNumImages / 105: ifnull 118 / 112: intValue / 115: ifle 122
            //     123: ifne 146 / 133: new IllegalArgumentException / 145: athrow
            // Zero takes the throwing branch, and open() maps every Throwable to LOAD_FAILED, so
            // a 0 here means "every model in the catalogue is permanently broken", silently.
            // The same rule guards maxNumTokens (offsets 56-100), which is why info.maxNumTokens
            // must stay positive.
            //
            // visionBackend / audioBackend: these two parameters are NOT null-checked (the
            // constructor emits Intrinsics.checkNotNullParameter for modelPath and backend only),
            // so they are genuinely nullable. This is a text-only model: handing it a vision and
            // an audio backend asks the runtime to stand up modality paths that have no weights.
            EngineConfig cfg = new EngineConfig(
                    info.file.getAbsolutePath(),
                    backend,
                    null,                                   // visionBackend - text-only model
                    null,                                   // audioBackend  - text-only model
                    Integer.valueOf(info.maxNumTokens),
                    null,                                   // maxNumImages - MUST be null, not 0
                    cacheDir.getAbsolutePath());
            Engine e = new Engine(cfg);
            e.initialize();
            return new LiteRtLlm(e, info);
        } catch (OutOfMemoryError t) {
            throw new AiException(AiException.Kind.OUT_OF_MEMORY, "engine load OOM", t);
        } catch (Throwable t) {
            throw new AiException(AiException.Kind.LOAD_FAILED,
                    "could not load " + info.catalogId, t);
        }
    }

    @Override
    public AiModelInfo model() {
        return info;
    }

    @Override
    public boolean supportsConstrainedDecoding() {
        return info.sentencePiece;
    }

    @Override
    public AiConversation start(AiPromptSpec spec) throws AiException {
        try {
            SamplerConfig sampler = sampler(spec);
            Contents sys = spec.systemInstruction == null
                    ? null : Contents.Companion.of(spec.systemInstruction);
            // A grammar on a BPE tokenizer throws inside the JNI layer with
            // "Constrained decoding is only supported for SentencePiece tokenizer" — so the
            // downgrade happens here, once, rather than as a caught exception per batch.
            boolean constrained = spec.mode != AiOutputMode.FREE_TEXT
                    && spec.grammar != null && info.sentencePiece;
            ConversationConfig cc = new ConversationConfig(
                    sys,
                    Collections.<Message>emptyList(),       // initialMessages
                    Collections.emptyList(),                // tools - MUST stay empty, see class doc
                    sampler,
                    false,                                  // automaticToolCalling
                    Collections.emptyList(),                // channels
                    Collections.emptyMap(),                 // extraContext
                    null,                                   // loraConfig
                    false,                                  // prefillPrefaceOnInit
                    Integer.valueOf(spec.maxOutputTokens),
                    null,                                   // thinkingConfig
                    constrained);                           // enableResponseFormat
            Conversation conv = engine.createConversation(cc);
            return new LiteRtConversation(conv, constrained ? spec.grammar : null);
        } catch (OutOfMemoryError t) {
            throw new AiException(AiException.Kind.OUT_OF_MEMORY, "conversation OOM", t);
        } catch (Throwable t) {
            throw new AiException(AiException.Kind.RUNTIME, "createConversation failed", t);
        }
    }

    /**
     * veille invariant 7: a sampler parameter the library rejects must not kill the AI half. If the
     * greedy configuration is refused we fall back to the library defaults and carry on with a
     * noisier decode, which the parser and repair ladder already tolerate.
     */
    private static SamplerConfig sampler(AiPromptSpec spec) {
        try {
            return new SamplerConfig(spec.topK, spec.topP, spec.temperature, spec.seed);
        } catch (RuntimeException e) {
            Log.w(TAG, "sampler rejected; using library defaults", e);
            return new SamplerConfig(40, 0.95d, 0.8d, 0);
        }
    }

    @Override
    public void close() {
        try {
            engine.close();
        } catch (Throwable t) {
            Log.w(TAG, "engine close failed", t);
        }
    }
}
