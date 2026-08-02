package de.luhmer.owncloudnewsreader.ai;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import de.luhmer.owncloudnewsreader.ai.engine.AiConversation;
import de.luhmer.owncloudnewsreader.ai.engine.AiLlm;
import de.luhmer.owncloudnewsreader.ai.engine.AiPromptSpec;
import de.luhmer.owncloudnewsreader.ai.engine.CancelToken;
import de.luhmer.owncloudnewsreader.ai.engine.LlmCall;
import de.luhmer.owncloudnewsreader.ai.prompt.AiPromptBuilder;
import de.luhmer.owncloudnewsreader.ai.prompt.PromptTemplate;
import de.luhmer.owncloudnewsreader.ai.prompt.TasteDraftGuard;

/**
 * Asks the model to redraft the interests note from recent decisions, and hands the result to
 * {@link TasteDraftGuard}. <b>It does not save anything</b> — the return value is a proposal.
 *
 * <p>This is the one stage where a repair turn is worth the tokens: the artefact is long-lived, the
 * reader asked for it explicitly by tapping a button, and there will not be another attempt for days.
 * The repair is a single instruction on the same conversation ("Output only the note"), and after it
 * the guard's verdict is final.</p>
 *
 * <p>The placeholder for an empty note is a literal sentence rather than a second prompt file: the
 * rest of the prompt reads correctly unchanged, and two prompt files for one task is two things to
 * keep in sync.</p>
 */
public final class AiTasteDraft {

    private static final String TAG = "AiTasteDraft";

    public static final String EMPTY_NOTE_PLACEHOLDER =
            "(empty — write a first note from the saved articles below)";

    /** More recent titles than this and a small model's attention over the list is already thin. */
    public static final int MAX_STARRED = 30;
    public static final int MAX_DISMISSED = 20;

    public static final int TITLE_MAX_CHARS = 120;
    public static final int MAX_TOKENS = 600;
    public static final long TIMEOUT_MS = 120_000L;

    public static final String REPAIR_INSTRUCTION = "Output only the note. No other text.";

    private AiTasteDraft() {
        // no instances
    }

    /**
     * @return a verdict that is never null. {@link TasteDraftGuard.Outcome#REJECTED} covers a failed
     *         call, a cancelled run and a model that would not produce a note.
     */
    public static TasteDraftGuard.Verdict draft(AiLlm llm, PromptTemplate system,
                                                PromptTemplate user, String currentNote,
                                                List<String> starred, List<String> dismissed,
                                                Locale locale, CancelToken token) {
        if (llm == null) {
            return TasteDraftGuard.check(null, currentNote);
        }
        String lang = AiPromptBuilder.languageName(locale);
        AiPromptSpec spec = AiPromptSpec.builder()
                .systemInstruction(system.render(PromptTemplate.vars("lang", lang)))
                .maxOutputTokens(MAX_TOKENS)
                // The single stage that is not greedy: a note redrafted at temperature 0 tends to
                // reproduce its input verbatim, which is a wasted inference the reader waited for.
                .sampler(40, 0.95d, 0.4d, 0)
                .perCallTimeoutMs(TIMEOUT_MS)
                .build();

        String note = currentNote == null || currentNote.trim().isEmpty()
                ? EMPTY_NOTE_PLACEHOLDER : currentNote.trim();
        List<String> starredTitles = titles(starred, MAX_STARRED);
        List<String> dismissedTitles = titles(dismissed, MAX_DISMISSED);

        String userText = user.render(PromptTemplate.vars(
                "current_note", note,
                "starred", bullets(starredTitles),
                // The placeholder earns its tokens here: an empty section under a heading is an
                // invitation for a small model to invent one.
                "dismissed", dismissedTitles.isEmpty() ? "(none)" : bullets(dismissedTitles),
                "lang", lang));

        List<String> allTitles = new ArrayList<>(starredTitles);
        allTitles.addAll(dismissedTitles);

        AiConversation conv = null;
        try {
            conv = llm.start(spec);
            LlmCall.Result r = LlmCall.run(conv, userText, TIMEOUT_MS, token);
            if (!r.ok()) {
                Log.w(TAG, "taste draft call failed: " + r.failure.kind);
                return TasteDraftGuard.check(null, currentNote, allTitles);
            }
            TasteDraftGuard.Verdict v = TasteDraftGuard.check(r.raw, currentNote, allTitles);
            if (v.outcome != TasteDraftGuard.Outcome.NEEDS_REPAIR) {
                return v;
            }
            LlmCall.Result repair = LlmCall.run(conv, REPAIR_INSTRUCTION, TIMEOUT_MS, token);
            if (!repair.ok()) {
                return TasteDraftGuard.check(null, currentNote, allTitles);
            }
            TasteDraftGuard.Verdict second =
                    TasteDraftGuard.check(repair.raw, currentNote, allTitles);
            if (second.outcome == TasteDraftGuard.Outcome.NEEDS_REPAIR) {
                // One repair, then reject. A second one has never produced a different answer.
                return TasteDraftGuard.check(null, currentNote, allTitles);
            }
            return second;
        } catch (Throwable t) {
            Log.w(TAG, "taste draft stage failed", t);
            return TasteDraftGuard.check(null, currentNote, allTitles);
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

    /** Sanitised, capped, deduped, order preserved (most recent first). */
    static List<String> titles(Collection<String> raw, int max) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String t : raw) {
            String clean = AiPromptBuilder.oneLine(t, TITLE_MAX_CHARS);
            if (clean.isEmpty() || out.contains(clean)) {
                continue;
            }
            out.add(clean);
            if (out.size() >= max) {
                break;
            }
        }
        return out;
    }

    static String bullets(List<String> titles) {
        StringBuilder sb = new StringBuilder();
        for (String t : titles) {
            sb.append("- ").append(t).append('\n');
        }
        return sb.toString();
    }
}
