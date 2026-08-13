package de.luhmer.owncloudnewsreader.ai.engine;

/**
 * A <b>per-turn</b> output constraint, as opposed to the per-conversation one carried by
 * {@link AiPromptSpec}.
 *
 * <p>A conversation is created once per batch and then used for more than one turn, and those turns
 * do not want the same constraint. The first turn wants the answer-line shape. The repair turn is
 * asking the model to fix what it just got wrong — forcing it back through the same grammar means
 * it cannot emit only the lines that are missing, cannot say less, and cannot stop early; the
 * constraint that was meant to help is what makes the repair impossible.
 *
 * <p>Three states, deliberately distinct:</p>
 * <ul>
 *   <li>{@code null} passed as the format — use whatever the conversation was created with. This is
 *       the default and what {@link AiConversation#send(String)} does.</li>
 *   <li>{@link #NONE} — <b>drop</b> the constraint for this turn only.</li>
 *   <li>{@link #regex(String)} — <b>replace</b> it for this turn only.</li>
 * </ul>
 *
 * <p>Relaxing or dropping the grammar is safe because the code-side parser is authoritative:
 * {@code ScoreLineParser} accepts only well-formed lines and the ladder in {@code AiScorer} decides
 * what to do with the rest. The grammar was never the thing guaranteeing correctness — it is an
 * optimisation that reduces how often the ladder has to run.
 *
 * <p>Note that a per-call format can only <i>narrow or drop</i>: a conversation created without
 * {@code enableResponseFormat} cannot be given one later (LiteRT-LM throws
 * {@code IllegalArgumentException} on that), so {@link #regex(String)} on an unconstrained
 * conversation is ignored by the implementation rather than fatal.
 */
public final class AiResponseFormat {

    /** Explicitly unconstrained for this turn. */
    public static final AiResponseFormat NONE = new AiResponseFormat(null);

    private final String regex;

    private AiResponseFormat(String regex) {
        this.regex = regex;
    }

    /** Constrain this turn to {@code pattern}. A null or empty pattern is {@link #NONE}. */
    public static AiResponseFormat regex(String pattern) {
        if (pattern == null || pattern.isEmpty()) {
            return NONE;
        }
        return new AiResponseFormat(pattern);
    }

    /** The pattern, or {@code null} when this format means "no constraint". */
    public String regex() {
        return regex;
    }

    @Override
    public String toString() {
        return regex == null ? "AiResponseFormat[none]" : "AiResponseFormat[" + regex + "]";
    }
}
