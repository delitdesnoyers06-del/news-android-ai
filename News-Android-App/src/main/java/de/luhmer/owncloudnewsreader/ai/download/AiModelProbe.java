package de.luhmer.owncloudnewsreader.ai.download;

import android.content.Context;
import android.util.Log;

import de.luhmer.owncloudnewsreader.ai.engine.AiConversation;
import de.luhmer.owncloudnewsreader.ai.engine.AiEngineManager;
import de.luhmer.owncloudnewsreader.ai.engine.AiException;
import de.luhmer.owncloudnewsreader.ai.engine.AiModelInfo;
import de.luhmer.owncloudnewsreader.ai.engine.AiPromptSpec;
import de.luhmer.owncloudnewsreader.ai.engine.CancelToken;
import de.luhmer.owncloudnewsreader.ai.engine.LlmCall;
import de.luhmer.owncloudnewsreader.ai.prompt.AiScoreGrammar;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.AiModelRegistry;

/**
 * The post-download smoke load: open the model once, say one thing, and — where the catalogue claims
 * SentencePiece — prove that a grammar is actually accepted.
 *
 * <p>The load watchdog is 180 s, not 45 (PLAN D25): the first {@code initialize()} of a 2.588 GB
 * model writes the rearranged-weight cache from scratch, which is plausibly 60-150 s on a mid-range
 * phone. A 45 s watchdog marks a perfectly good model BROKEN <i>after</i> a 2.6 GB download and
 * offers the user a Delete button — the worst possible ending to a twenty-minute wait. The measured
 * load time is recorded so later runs can use {@code 3 x measured}.</p>
 *
 * <p>The second half is the on-device resolution of the constrained-decoding question for any model
 * added to the catalogue. A {@code LiteRtLmJniException} carrying <i>"Constrained decoding is only
 * supported for SentencePiece tokenizer"</i> is not a failure: it records
 * {@code sentencePiece = false} for this install, which forces batch 1 and makes the repair ladder
 * load-bearing. The model still works.</p>
 */
public final class AiModelProbe {

    private static final String TAG = "AiModelProbe";

    /** What the probe learned. Never an exception. */
    public static final class Result {
        public final boolean loaded;
        public final boolean constrainedDecoding;
        public final long loadMs;
        public final String error;

        Result(boolean loaded, boolean constrainedDecoding, long loadMs, String error) {
            this.loaded = loaded;
            this.constrainedDecoding = constrainedDecoding;
            this.loadMs = loadMs;
            this.error = error;
        }
    }

    private AiModelProbe() {
        // no instances
    }

    /**
     * Runs the smoke load on the calling background thread.
     *
     * @return whether the model loaded, and whether a grammar survived it
     */
    public static Result run(Context context, AiDb db, AiModelInfo info) {
        final long t0 = System.currentTimeMillis();
        AiEngineManager engines = new AiEngineManager(context, db);
        final boolean[] constrained = {false};
        try {
            // Smoke loads always run on CPU: the GPU delegate is the single most common LiteRT crash
            // class and a probe is the wrong place to discover it.
            engines.withLlm(info, false, CancelToken.none(), llm -> {
                AiPromptSpec plain = AiPromptSpec.builder()
                        .maxOutputTokens(8)
                        .sampler(1, 1.0d, 0.0d, 0)
                        .perCallTimeoutMs(60_000L)
                        .build();
                try (AiConversation c = llm.start(plain)) {
                    LlmCall.Result r = LlmCall.run(c, "Say OK.", 60_000L, CancelToken.none());
                    if (r.failure != null) {
                        throw r.failure;
                    }
                }
                if (info.sentencePiece) {
                    constrained[0] = probeGrammar(llm);
                }
                return null;
            });
            long ms = System.currentTimeMillis() - t0;
            record(db, info.catalogId, true, constrained[0], ms, null);
            return new Result(true, constrained[0], ms, null);
        } catch (AiException e) {
            long ms = System.currentTimeMillis() - t0;
            Log.w(TAG, "smoke load failed for " + info.catalogId + " (" + e.kind + ")", e);
            record(db, info.catalogId, false, false, ms, e.kind.name());
            return new Result(false, false, ms, e.kind.name());
        } catch (Throwable t) {
            long ms = System.currentTimeMillis() - t0;
            Log.w(TAG, "smoke load failed for " + info.catalogId, t);
            record(db, info.catalogId, false, false, ms, String.valueOf(t.getMessage()));
            return new Result(false, false, ms, String.valueOf(t.getMessage()));
        }
    }

    /** One constrained turn against the trivial grammar {@code [0-3]}. Failure is informative. */
    private static boolean probeGrammar(de.luhmer.owncloudnewsreader.ai.engine.AiLlm llm) {
        AiPromptSpec spec = AiPromptSpec.builder()
                .maxOutputTokens(4)
                .sampler(1, 1.0d, 0.0d, 0)
                .regex("[0-3]")
                .perCallTimeoutMs(60_000L)
                .build();
        // A typo check only — Java's regex dialect is not LLGuidance's. Whether the model accepts
        // the grammar is exactly what the turn below is here to find out.
        if (AiScoreGrammar.compileAsJavaRegexOrNull(spec.grammar) == null) {
            return false;
        }
        try (AiConversation c = llm.start(spec)) {
            LlmCall.Result r = LlmCall.run(c, "Answer with one digit between 0 and 3.", 60_000L,
                    CancelToken.none());
            return r.failure == null && r.raw != null && !r.raw.trim().isEmpty();
        } catch (Throwable t) {
            Log.i(TAG, "constrained decoding unavailable on this model: " + t.getMessage());
            return false;
        }
    }

    private static void record(AiDb db, String modelId, boolean loaded, boolean constrained,
                               long loadMs, String error) {
        if (db == null) {
            return;
        }
        try {
            db.putMeta(AiModelRepository.metaKeySentencePiece(modelId), constrained ? "1" : "0");
            db.putMetaLong(AiModelRepository.metaKeyLoadMs(modelId), loadMs);
            new AiModelRegistry(db).setCaps(modelId, "{\"responseFormat\":" + constrained
                    + ",\"loadMs\":" + loadMs + ",\"loaded\":" + loaded + "}");
        } catch (Throwable t) {
            Log.w(TAG, "could not record probe result", t);
        }
    }
}
