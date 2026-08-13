package de.luhmer.owncloudnewsreader.ai.engine;

/**
 * Immutable per-stage generation parameters. Built once per stage, reused across batches.
 *
 * <p>Defaults are greedy ({@code topK=1, topP=1.0, temperature=0.0, seed=0}) because a flaky eval is
 * worse than no eval: the §6 fixture only means something if the same input gives the same output.
 * The taste draft is the single stage that raises the temperature.</p>
 */
public final class AiPromptSpec {

    public final String systemInstruction;
    public final int maxOutputTokens;
    public final int topK;
    public final double topP;
    public final double temperature;
    public final int seed;
    public final AiOutputMode mode;
    /** Regex pattern or JSON schema; {@code null} for {@link AiOutputMode#FREE_TEXT}. */
    public final String grammar;
    public final long perCallTimeoutMs;

    private AiPromptSpec(Builder b) {
        this.systemInstruction = b.systemInstruction;
        this.maxOutputTokens = b.maxOutputTokens;
        this.topK = b.topK;
        this.topP = b.topP;
        this.temperature = b.temperature;
        this.seed = b.seed;
        this.mode = b.grammar == null ? AiOutputMode.FREE_TEXT : b.mode;
        this.grammar = b.grammar;
        this.perCallTimeoutMs = b.perCallTimeoutMs;
    }

    /** A copy with the grammar removed — what a BPE model gets. */
    public AiPromptSpec unconstrained() {
        if (mode == AiOutputMode.FREE_TEXT) {
            return this;
        }
        return builder()
                .systemInstruction(systemInstruction)
                .maxOutputTokens(maxOutputTokens)
                .sampler(topK, topP, temperature, seed)
                .perCallTimeoutMs(perCallTimeoutMs)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** No public constructor: every field has a defined default and none of them is optional. */
    public static final class Builder {
        private String systemInstruction;
        private int maxOutputTokens = 256;
        private int topK = 1;
        private double topP = 1.0d;
        private double temperature = 0.0d;
        private int seed;
        private AiOutputMode mode = AiOutputMode.FREE_TEXT;
        private String grammar;
        private long perCallTimeoutMs = 120_000L;

        public Builder systemInstruction(String v) {
            this.systemInstruction = v;
            return this;
        }

        public Builder maxOutputTokens(int v) {
            this.maxOutputTokens = Math.max(1, v);
            return this;
        }

        public Builder sampler(int topK, double topP, double temperature, int seed) {
            this.topK = topK;
            this.topP = topP;
            this.temperature = temperature;
            this.seed = seed;
            return this;
        }

        public Builder regex(String pattern) {
            this.mode = AiOutputMode.REGEX;
            this.grammar = pattern;
            return this;
        }

        public Builder perCallTimeoutMs(long v) {
            this.perCallTimeoutMs = Math.max(1000L, v);
            return this;
        }

        public AiPromptSpec build() {
            return new AiPromptSpec(this);
        }
    }
}
