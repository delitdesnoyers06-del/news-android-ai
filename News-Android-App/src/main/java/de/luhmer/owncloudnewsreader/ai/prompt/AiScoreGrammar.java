package de.luhmer.owncloudnewsreader.ai.prompt;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The decoder-side half of the output contract: a regex handed to {@code ResponseFormat.regex(...)}
 * so a SentencePiece model <b>cannot</b> emit a malformed batch in the first place.
 *
 * <p>Regex over one line format rather than a JSON schema, for three reasons. One parser, one prompt
 * and one eval fixture serve both tokenizer families, instead of two of each for constrained-vs-not.
 * The structural guarantee (exactly N lines, indices, score 0-3, no pipe inside {@code why}) becomes
 * mechanical while the semantic one (is 2 the right score?) stays with the model, which is the
 * correct split. And LLGuidance rejects {@code oneOf} in JSON Schema — a constraint we simply never
 * meet by not using JSON.</p>
 *
 * <p>This is an <b>optimisation, never a gate</b>: a BPE model gets the unconstrained path plus
 * the repair ladder, and the feature ships either way.</p>
 */
public final class AiScoreGrammar {

    /** Longest {@code tags} field the grammar will admit. */
    public static final int TAGS_MAX = 80;
    /** Longest {@code why} field the grammar will admit; matches the parser's own cap. */
    public static final int WHY_MAX = 160;

    private AiScoreGrammar() {
        // no instances
    }

    /**
     * <b>The default</b> (PLAN S2, revised): one or more answer lines, with no cap on the count.
     *
     * <p>The count is deliberately <i>not</i> in the grammar. A grammar is a mask over the next
     * token, not a plan: with {@code {N}} the decoder masks EOS until the Nth line is complete, so
     * a model that has said everything it has to say is forced to invent the remaining lines
     * instead of stopping — and if it has painted itself into a state where every continuation is
     * illegal, the mask is empty and the whole generation errors out rather than degrading.
     * "Exactly N lines, correctly indexed" is a property {@link ScoreLineParser} can check after
     * the fact and the repair ladder can fix; it is not a property worth risking the decode for.
     *
     * @return a pattern matching one or more newline-terminated answer lines
     */
    public static String forAnyLength() {
        return "(?:" + LINE + "\\n)+";
    }

    /**
     * The fixed-count form, {@code (?:LINE\n){N}}.
     *
     * <p><b>Not used on the generation path</b> — see {@link #forAnyLength()} for why. It is kept
     * because it is the exact shape a <i>well-formed</i> answer for a batch of {@code n} has, which
     * makes it the natural oracle for the eval fixture and for tests that need to assert "this
     * output is complete", and because if the on-device spike ever shows a model that only obeys
     * the line format under a hard count, this is the one-line revert.
     *
     * @param n batch size
     * @return a pattern matching exactly {@code n} newline-terminated answer lines
     */
    public static String forBatch(int n) {
        int k = Math.max(1, n);
        return "(?:" + LINE + "\\n){" + k + "}";
    }

    private static final String LINE =
            "[1-9][0-9]?\\|[0-3]\\|[a-z0-9,\\-]{0," + TAGS_MAX + "}\\|[^\\n|]{0," + WHY_MAX + "}";

    /**
     * A <b>smoke check in the wrong dialect</b>, and named to say so.
     *
     * <p>Compiles {@code pattern} with {@link java.util.regex.Pattern} — Java's own backtracking
     * engine — and returns {@code null} when even that refuses it. That catches a typo (an unclosed
     * group, a bad quantifier) before a background service hands the string to the decoder, and it
     * catches <b>nothing else</b>.
     *
     * <p>It is <b>not</b> evidence that LLGuidance will accept the grammar. LLGuidance compiles
     * regexes with the Rust {@code regex} crate, which is a different language: it has no
     * backreferences and no lookaround (both of which {@code java.util.regex} compiles happily),
     * it treats some escapes and character-class syntax differently, and on top of that LLGuidance
     * adds its own restrictions and must be able to build a finite automaton over the model's
     * token vocabulary. A pattern can pass here and be rejected on device; the only thing that
     * settles acceptance is {@code AiModelProbe} running against a real model (PLAN S2). Treat a
     * non-null return as "not obviously broken", never as "validated".
     */
    public static Pattern compileAsJavaRegexOrNull(String pattern) {
        try {
            return Pattern.compile(pattern);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }
}
