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
    public static final long TIMEOUT_MS = 60_000L;

    /** Small-model path: several short calls beat one giant prompt that times out. */
    public static final int CHUNK_STARRED = 8;
    public static final int CHUNK_DISMISSED = 6;

    public static final String REPAIR_INSTRUCTION = "Output only the note. No other text.";

    public static final class Debug {
        public int kept;
        public int rejected;
        public int chunks;
        public int completedChunks;
        public int calls;
        public int repairs;
        public int maxPromptChars;
        public long wallMs;
        public String failure = "";
    }

    public static final class Result {
        public final TasteDraftGuard.Verdict verdict;
        public final Debug debug;

        Result(TasteDraftGuard.Verdict verdict, Debug debug) {
            this.verdict = verdict;
            this.debug = debug;
        }
    }

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
        return draftWithDebug(llm, system, user, currentNote, starred, dismissed, locale,
                token).verdict;
    }

    public static Result draftWithDebug(AiLlm llm, PromptTemplate system, PromptTemplate user,
                                        String currentNote, List<String> starred,
                                        List<String> dismissed, Locale locale,
                                        CancelToken token) {
        Debug debug = new Debug();
        if (llm == null) {
            debug.failure = "no_llm";
            return new Result(TasteDraftGuard.check(null, currentNote), debug);
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
        debug.kept = starredTitles.size();
        debug.rejected = dismissedTitles.size();

        List<String> allTitles = new ArrayList<>(starredTitles);
        allTitles.addAll(dismissedTitles);

        if (starredTitles.size() <= CHUNK_STARRED && dismissedTitles.size() <= CHUNK_DISMISSED) {
            debug.chunks = 1;
            TasteDraftGuard.Verdict v = callOnce(llm, spec, user, note, currentNote,
                    starredTitles, dismissedTitles, allTitles, lang, token, debug);
            if (v.outcome != TasteDraftGuard.Outcome.REJECTED) {
                debug.completedChunks = 1;
            }
            return new Result(v, debug);
        }

        String workingNote = note;
        int chunks = Math.max(chunks(starredTitles.size(), CHUNK_STARRED),
                chunks(dismissedTitles.size(), CHUNK_DISMISSED));
        debug.chunks = chunks;
        for (int i = 0; i < chunks; i++) {
            List<String> keptChunk = slice(starredTitles, i * CHUNK_STARRED, CHUNK_STARRED);
            List<String> rejectedChunk =
                    slice(dismissedTitles, i * CHUNK_DISMISSED, CHUNK_DISMISSED);
            TasteDraftGuard.Verdict v = callOnce(llm, spec, user, workingNote, currentNote,
                    keptChunk, rejectedChunk, allTitles, lang, token, debug);
            if (v.outcome == TasteDraftGuard.Outcome.REJECTED) {
                if (debug.failure.isEmpty()) {
                    debug.failure = v.reason;
                }
                return new Result(v, debug);
            }
            debug.completedChunks++;
            if (v.outcome == TasteDraftGuard.Outcome.OK) {
                workingNote = v.draft;
            }
        }
        return new Result(TasteDraftGuard.check(workingNote, currentNote, allTitles), debug);
    }

    private static TasteDraftGuard.Verdict callOnce(AiLlm llm, AiPromptSpec spec,
                                                    PromptTemplate user, String note,
                                                    String originalNote,
                                                    List<String> starredTitles,
                                                    List<String> dismissedTitles,
                                                    List<String> allTitles, String lang,
                                                    CancelToken token, Debug debug) {
        String userText = user.render(PromptTemplate.vars(
                "current_note", note,
                "starred", bullets(starredTitles),
                // The placeholder earns its tokens here: an empty section under a heading is an
                // invitation for a small model to invent one.
                "dismissed", dismissedTitles.isEmpty() ? "(none)" : bullets(dismissedTitles),
                "lang", lang));
        debug.maxPromptChars = Math.max(debug.maxPromptChars, userText.length());

        AiConversation conv = null;
        try {
            conv = llm.start(spec);
            LlmCall.Result r = LlmCall.run(conv, userText, TIMEOUT_MS, token);
            debug.calls++;
            debug.wallMs += r.wallMs;
            if (!r.ok()) {
                Log.w(TAG, "taste draft call failed: " + r.failure.kind);
                debug.failure = r.failure.kind.name();
                return TasteDraftGuard.check(null, originalNote, allTitles);
            }
            TasteDraftGuard.Verdict v = TasteDraftGuard.check(r.raw, originalNote, allTitles);
            if (v.outcome != TasteDraftGuard.Outcome.NEEDS_REPAIR) {
                return v;
            }
            LlmCall.Result repair = LlmCall.run(conv, REPAIR_INSTRUCTION, TIMEOUT_MS, token);
            debug.calls++;
            debug.repairs++;
            debug.wallMs += repair.wallMs;
            if (!repair.ok()) {
                debug.failure = repair.failure.kind.name();
                return TasteDraftGuard.check(null, originalNote, allTitles);
            }
            TasteDraftGuard.Verdict second =
                    TasteDraftGuard.check(repair.raw, originalNote, allTitles);
            if (second.outcome == TasteDraftGuard.Outcome.NEEDS_REPAIR) {
                // One repair, then reject. A second one has never produced a different answer.
                debug.failure = "wrong_format_after_repair";
                return TasteDraftGuard.check(null, originalNote, allTitles);
            }
            return second;
        } catch (Throwable t) {
            Log.w(TAG, "taste draft stage failed", t);
            debug.failure = "runtime";
            return TasteDraftGuard.check(null, originalNote, allTitles);
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

    private static int chunks(int size, int chunkSize) {
        return size == 0 ? 0 : (int) Math.ceil(size / (double) chunkSize);
    }

    private static List<String> slice(List<String> in, int start, int count) {
        List<String> out = new ArrayList<>();
        if (in == null) {
            return out;
        }
        for (int i = start; i < in.size() && out.size() < count; i++) {
            out.add(in.get(i));
        }
        return out;
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
