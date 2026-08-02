package de.luhmer.owncloudnewsreader.ai;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import de.luhmer.owncloudnewsreader.ai.engine.AiConversation;
import de.luhmer.owncloudnewsreader.ai.engine.AiException;
import de.luhmer.owncloudnewsreader.ai.engine.AiLlm;
import de.luhmer.owncloudnewsreader.ai.engine.AiPromptSpec;
import de.luhmer.owncloudnewsreader.ai.engine.AiResponseFormat;
import de.luhmer.owncloudnewsreader.ai.engine.CancelToken;
import de.luhmer.owncloudnewsreader.ai.engine.LlmCall;
import de.luhmer.owncloudnewsreader.ai.prompt.AiPromptBuilder;
import de.luhmer.owncloudnewsreader.ai.prompt.AiPrompts;
import de.luhmer.owncloudnewsreader.ai.prompt.AiScoreGrammar;
import de.luhmer.owncloudnewsreader.ai.prompt.ScoreLineParser;

/**
 * Stage 5 — the scoring pass, with the repair ladder and the terminal guarantee.
 *
 * <h3>The one thing this class must never do</h3>
 * Lose an article. Every item handed to {@link #score} is handed back to the {@link Sink} exactly
 * once, with a verdict that may be {@code llmScore == null}. Null is <b>not</b> zero:
 * <ul>
 *   <li>{@code null} = never judged &rarr; rank on similarity alone, retry on the next sync;</li>
 *   <li>{@code 0} = judged and off topic &rarr; final.</li>
 * </ul>
 * Confusing the two makes every failed batch look like a permanent verdict, and the folder quietly
 * stops improving.
 *
 * <h3>The ladder (PLAN D17)</h3>
 * <pre>
 *   parse(raw)                               -&gt; some indices missing?
 *   one repair turn on the SAME Conversation -&gt; still missing?      (merge: existing lines win)
 *   re-run only the missing items as batch-of-1, FRESH Conversation each
 *   still missing                            -&gt; llmScore = null
 * </pre>
 * Splitting beats re-asking because the failure being repaired is almost always length or alignment,
 * and batch-of-1 eliminates alignment by construction. One {@code Conversation} <b>per batch</b>,
 * closed after its repair turn: the repair needs the previous turn in context, but batch N+1 must
 * not inherit batch N's KV cache.
 *
 * <p>The sink is called <b>per article as its batch completes</b>, not once at the end: the table is
 * the checkpoint, so a run killed by the OS mid-pass resumes at the first article with no verdict.
 */
public final class AiScorer {

    private static final String TAG = "AiScorer";

    /** Below this size a model gets batch 1 regardless of tokenizer (PLAN D17). */
    public static final long TINY_MODEL_BYTES = 500_000_000L;
    public static final int DEFAULT_BATCH = 4;

    public static final long TIMEOUT_BATCH_MS = 120_000L;
    public static final long TIMEOUT_SINGLE_MS = 45_000L;
    /** Whole-run cap. A triage pass that has taken twelve minutes has already lost. */
    public static final long RUN_BUDGET_MS = 12L * 60L * 1000L;

    /** ~70 output tokens per article covers a 160-char why plus tags with room to spare. */
    private static final int TOKENS_PER_ARTICLE = 70;

    /** One article as the scorer sees it. */
    public static final class Item {
        public final String aiKey;
        public final long rssItemId;
        public final String title;
        public final String source;
        public final String excerpt;

        public Item(String aiKey, long rssItemId, String title, String source, String excerpt) {
            this.aiKey = aiKey;
            this.rssItemId = rssItemId;
            this.title = title;
            this.source = source;
            this.excerpt = excerpt;
        }
    }

    /** What the model said about one article, already cleaned by {@link ScoreLineParser}. */
    public static final class Verdict {
        /** {@code null} = never judged. Load-bearing; see the class comment. */
        public final Integer llmScore;
        public final String themes;
        public final String flags;
        public final String why;

        public Verdict(Integer llmScore, String themes, String flags, String why) {
            this.llmScore = llmScore;
            this.themes = themes;
            this.flags = flags;
            this.why = why;
        }

        static Verdict unjudged() {
            return new Verdict(null, null, null, null);
        }
    }

    /** Called once per article, as its batch completes. Must not throw. */
    public interface Sink {
        void onScored(Item item, Verdict verdict);
    }

    /** Counters for the status strip and {@code pref_ai_last_run}. */
    public static final class Report {
        public int scored;
        public int unjudged;
        public int batches;
        public int repairTurns;
        public int splitCalls;
        public int inventedTags;
        public String degraded;

        @Override
        public String toString() {
            return "AiScore[scored=" + scored + " unjudged=" + unjudged + " batches=" + batches
                    + " repair=" + repairTurns + " split=" + splitCalls
                    + " inventedTags=" + inventedTags + " degraded=" + degraded + "]";
        }
    }

    private final AiPrompts prompts;
    private final String interestsNote;
    private final Collection<String> themeSlugs;
    private final List<AiPromptBuilder.Correction> corrections;
    private final Locale locale;
    private final int batchSize;

    public AiScorer(AiPrompts prompts, String interestsNote, Collection<String> themeSlugs,
                    List<AiPromptBuilder.Correction> corrections, Locale locale, int batchSize) {
        this.prompts = prompts;
        this.interestsNote = interestsNote;
        this.themeSlugs = themeSlugs;
        this.corrections = corrections;
        this.locale = locale;
        this.batchSize = Math.max(1, batchSize);
    }

    /**
     * Batch size for a model. 4 buys about 25 % of the wall time back by amortising prefill; 8 buys
     * nothing more and adds drift, dropped lines and renumbering. Unconstrained decoding gets 1,
     * which removes alignment risk by construction at a cost the repair ladder would otherwise pay.
     *
     * @param preferred {@code sp_ai_score_batch}, or &lt;= 0 to let the model decide
     */
    public static int batchSizeFor(AiLlm llm, int preferred) {
        if (llm == null) {
            return 1;
        }
        if (llm.model() != null && llm.model().sizeBytes > 0
                && llm.model().sizeBytes < TINY_MODEL_BYTES) {
            return 1;
        }
        if (!llm.supportsConstrainedDecoding()) {
            return 1;
        }
        return preferred > 0 ? preferred : DEFAULT_BATCH;
    }

    /**
     * Scores every item. <b>Never throws.</b> Everything that can go wrong — a load failure, a
     * watchdog, an OOM, the user disabling AI mid-run — ends as {@code llmScore = null} for the
     * articles it touched, and the loop continues.
     *
     * @param deadlineMs absolute wall-clock deadline; after it every remaining item is emitted
     *                   unjudged rather than left out
     */
    public Report score(AiLlm llm, List<Item> items, Sink sink, CancelToken token,
                        long deadlineMs) {
        Report report = new Report();
        if (items == null || items.isEmpty()) {
            return report;
        }
        if (llm == null) {
            emitAllUnjudged(items, sink, report);
            report.degraded = AiException.Kind.NOT_INSTALLED.name();
            return report;
        }
        final boolean constrained = llm.supportsConstrainedDecoding();
        for (int from = 0; from < items.size(); from += batchSize) {
            List<Item> batch = items.subList(from, Math.min(items.size(), from + batchSize));
            if (token != null && token.isCancelled()) {
                emitAllUnjudged(batch, sink, report);
                report.degraded = AiException.Kind.CANCELLED.name();
                continue;
            }
            if (System.currentTimeMillis() > deadlineMs) {
                emitAllUnjudged(batch, sink, report);
                report.degraded = AiException.Kind.TIMEOUT.name();
                continue;
            }
            report.batches++;
            Map<Integer, ScoreLineParser.Line> lines = runBatch(llm, batch, constrained, token,
                    report);
            List<Integer> missing = ScoreLineParser.missing(lines, batch.size());
            if (!missing.isEmpty() && batch.size() > 1) {
                lines = splitToSingles(llm, batch, missing, lines, constrained, token, report,
                        deadlineMs);
            }
            commit(batch, lines, sink, report);
        }
        Log.i(TAG, report.toString());
        return report;
    }

    /** One batch: the call, then at most one repair turn on the same conversation. */
    private Map<Integer, ScoreLineParser.Line> runBatch(AiLlm llm, List<Item> batch,
                                                        boolean constrained, CancelToken token,
                                                        Report report) {
        final int n = batch.size();
        Map<Integer, ScoreLineParser.Line> parsed = new LinkedHashMap<>();
        AiConversation conv = null;
        try {
            conv = llm.start(specFor(n, constrained));
            String userText = prompts.user.render(AiPromptBuilder.scoreVars(
                    interestsNote, themeSlugs, corrections, articlesOf(batch), locale));
            long timeout = n > 1 ? TIMEOUT_BATCH_MS : TIMEOUT_SINGLE_MS;
            LlmCall.Result first = LlmCall.run(conv, userText, timeout, token);
            parsed = ScoreLineParser.parse(first.raw, n, themeSlugs);
            countInventedTags(parsed, report);

            List<Integer> missing = ScoreLineParser.missing(parsed, n);
            boolean cancelled = token != null && token.isCancelled();
            if (!missing.isEmpty() && !cancelled) {
                report.repairTurns++;
                String repairText = prompts.repair.render(AiPromptBuilder.repairVars(n));
                // The repair turn runs UNCONSTRAINED, on purpose. The grammar is what the first
                // turn failed under; re-imposing it means the model must produce the same shape
                // that just went wrong, cannot emit only the lines that are missing, and cannot
                // say less than it did. The parser is authoritative either way: anything that is
                // not a well-formed line is dropped by ScoreLineParser and merge() keeps the
                // lines we already have, so a looser repair can only add information.
                LlmCall.Result second = LlmCall.run(conv, repairText, timeout, token,
                        AiResponseFormat.NONE);
                Map<Integer, ScoreLineParser.Line> repaired =
                        ScoreLineParser.parse(second.raw, n, themeSlugs);
                countInventedTags(repaired, report);
                parsed = ScoreLineParser.merge(parsed, repaired);
            }
            if (first.failure != null) {
                report.degraded = first.failure.kind.name();
            }
        } catch (AiException e) {
            report.degraded = e.kind.name();
            Log.w(TAG, "batch failed (" + e.kind + "): " + e.getMessage());
        } catch (Throwable t) {
            report.degraded = AiException.Kind.RUNTIME.name();
            Log.w(TAG, "batch failed", t);
        } finally {
            closeQuietly(conv);
        }
        return parsed;
    }

    /**
     * Re-runs only the still-missing articles, one per call, each with a <b>fresh</b> conversation:
     * carrying over the conversation that just failed would carry over the tokens that caused it.
     */
    private Map<Integer, ScoreLineParser.Line> splitToSingles(
            AiLlm llm, List<Item> batch, List<Integer> missing,
            Map<Integer, ScoreLineParser.Line> soFar, boolean constrained, CancelToken token,
            Report report, long deadlineMs) {
        Map<Integer, ScoreLineParser.Line> merged = new LinkedHashMap<>(soFar);
        for (Integer index : missing) {
            if ((token != null && token.isCancelled()) || System.currentTimeMillis() > deadlineMs) {
                break;
            }
            Item item = batch.get(index - 1);
            report.splitCalls++;
            AiConversation conv = null;
            try {
                conv = llm.start(specFor(1, constrained));
                String text = prompts.user.render(AiPromptBuilder.scoreVars(
                        interestsNote, themeSlugs, corrections,
                        articlesOf(java.util.Collections.singletonList(item)), locale));
                LlmCall.Result r = LlmCall.run(conv, text, TIMEOUT_SINGLE_MS, token);
                Map<Integer, ScoreLineParser.Line> one =
                        ScoreLineParser.parse(r.raw, 1, themeSlugs);
                countInventedTags(one, report);
                ScoreLineParser.Line line = one.get(1);
                if (line != null) {
                    // Re-key from "line 1 of a batch of 1" to its position in the original batch.
                    merged.put(index, line);
                }
            } catch (AiException e) {
                report.degraded = e.kind.name();
            } catch (Throwable t) {
                report.degraded = AiException.Kind.RUNTIME.name();
            } finally {
                closeQuietly(conv);
            }
        }
        return merged;
    }

    private AiPromptSpec specFor(int n, boolean constrained) {
        AiPromptSpec.Builder b = AiPromptSpec.builder()
                .systemInstruction(prompts.system.render())
                .maxOutputTokens(TOKENS_PER_ARTICLE * n)
                .sampler(1, 1.0d, 0.0d, 0)
                .perCallTimeoutMs(n > 1 ? TIMEOUT_BATCH_MS : TIMEOUT_SINGLE_MS);
        if (constrained) {
            // forAnyLength(), not forBatch(n). The {N} quantifier masks EOS until the Nth line is
            // complete, so a model that has nothing left to say is forced to invent lines instead
            // of stopping, and a decoder that reaches a state with no legal continuation errors
            // out on an empty token mask rather than degrading. "Exactly n lines" is checked by
            // ScoreLineParser.missing() and fixed by the ladder; it does not belong in the mask.
            b.regex(AiScoreGrammar.forAnyLength());
        }
        return b.build();
    }

    /** Emits every item of the batch exactly once — this is the terminal guarantee in code. */
    private void commit(List<Item> batch, Map<Integer, ScoreLineParser.Line> lines, Sink sink,
                        Report report) {
        for (int i = 0; i < batch.size(); i++) {
            ScoreLineParser.Line line = lines.get(i + 1);
            Verdict v;
            if (line == null) {
                v = Verdict.unjudged();
                report.unjudged++;
            } else {
                v = new Verdict(Integer.valueOf(line.score), line.themesCsv(), line.flagsCsv(),
                        line.why == null || line.why.isEmpty() ? null : line.why);
                report.scored++;
            }
            emit(sink, batch.get(i), v);
        }
    }

    private void emitAllUnjudged(List<Item> batch, Sink sink, Report report) {
        for (Item item : batch) {
            report.unjudged++;
            emit(sink, item, Verdict.unjudged());
        }
    }

    private static void emit(Sink sink, Item item, Verdict v) {
        if (sink == null) {
            return;
        }
        try {
            sink.onScored(item, v);
        } catch (Throwable t) {
            // A failing sink is a database problem, not a reason to abandon the remaining articles.
            Log.w(TAG, "sink failed for " + item.aiKey, t);
        }
    }

    private void countInventedTags(Map<Integer, ScoreLineParser.Line> lines, Report report) {
        for (ScoreLineParser.Line l : lines.values()) {
            int kept = l.themes.size() + l.flags.size();
            report.inventedTags += Math.max(0, l.rawTags.size() - kept);
        }
    }

    private static List<AiPromptBuilder.Article> articlesOf(List<Item> batch) {
        List<AiPromptBuilder.Article> out = new ArrayList<>(batch.size());
        for (Item i : batch) {
            out.add(new AiPromptBuilder.Article(i.title, i.source, i.excerpt));
        }
        return out;
    }

    private static void closeQuietly(AiConversation conv) {
        if (conv == null) {
            return;
        }
        try {
            conv.close();
        } catch (Throwable t) {
            Log.w(TAG, "conversation close failed", t);
        }
    }
}
