package de.luhmer.owncloudnewsreader.ai;

import android.util.Log;

import java.util.List;
import java.util.Locale;

import de.luhmer.owncloudnewsreader.ai.engine.AiConversation;
import de.luhmer.owncloudnewsreader.ai.engine.AiLlm;
import de.luhmer.owncloudnewsreader.ai.engine.AiPromptSpec;
import de.luhmer.owncloudnewsreader.ai.engine.CancelToken;
import de.luhmer.owncloudnewsreader.ai.engine.LlmCall;
import de.luhmer.owncloudnewsreader.ai.prompt.AbstractGuard;
import de.luhmer.owncloudnewsreader.ai.prompt.AiPromptBuilder;
import de.luhmer.owncloudnewsreader.ai.prompt.PromptTemplate;

/**
 * One inference, once a day, for the digest's opening paragraph.
 *
 * <p>Free text, no grammar: the artefact is prose. Everything the prompt asks for — no heading, no
 * preamble, three to five sentences — is re-checked by {@link AbstractGuard}, and the guard is
 * allowed to answer "no abstract at all", which is the whole reason this stage can be optional.</p>
 *
 * <p>No repair turn. A second inference to rescue a bad paragraph costs more than the paragraph is
 * worth, and the digest below it is already complete.</p>
 */
public final class AiAbstract {

    private static final String TAG = "AiAbstract";

    /** Prose, 3-5 sentences. 400 tokens is roughly double the ask. */
    public static final int MAX_TOKENS = 400;

    /** Longer than a scoring batch: this is one long generation, not many short ones. */
    public static final long TIMEOUT_MS = 90_000L;

    private AiAbstract() {
        // no instances
    }

    /**
     * @param items {@code {title, why}} pairs, highest rank first
     * @return the cleaned abstract, or {@code null} meaning "render the digest without one" — which
     *         covers a failed call, a cancelled run and a model that produced garbage, on purpose:
     *         the digest treats all three identically
     */
    public static String generate(AiLlm llm, PromptTemplate system, PromptTemplate user,
                                  List<String[]> items, String interestsNote, Locale locale,
                                  CancelToken token) {
        if (llm == null || items == null || items.isEmpty()) {
            return null;
        }
        String lang = AiPromptBuilder.languageName(locale);
        AiPromptSpec spec = AiPromptSpec.builder()
                .systemInstruction(system.render(PromptTemplate.vars("lang", lang)))
                .maxOutputTokens(MAX_TOKENS)
                // Greedy: two readers with the same digest should get the same paragraph, and a
                // "creative" abstract is exactly the one that invents a market-wide claim.
                .sampler(1, 1.0d, 0.0d, 0)
                .perCallTimeoutMs(TIMEOUT_MS)
                .build();

        String userText = user.render(PromptTemplate.vars(
                "items", AbstractGuard.itemsBlock(items),
                "brief", AbstractGuard.briefBlock(interestsNote),
                "lang", lang));

        AiConversation conv = null;
        try {
            conv = llm.start(spec);
            LlmCall.Result r = LlmCall.run(conv, userText, TIMEOUT_MS, token);
            if (!r.ok()) {
                Log.w(TAG, "abstract call failed: " + r.failure.kind);
                return null;
            }
            return AbstractGuard.clean(r.raw);
        } catch (Throwable t) {
            Log.w(TAG, "abstract stage failed", t);
            return null;
        } finally {
            if (conv != null) {
                try {
                    conv.close();
                } catch (Throwable t) {
                    Log.w(TAG, "conversation close failed", t);
                }
            }
        }
    }
}
