package de.luhmer.owncloudnewsreader.ai.engine;

/**
 * How the decoder is constrained.
 *
 * <p>{@link #FREE_TEXT} is the universal floor and the only mode guaranteed to exist: a BPE model
 * (Qwen, SmolLM) rejects a grammar outright. Every other mode is an <b>optimisation</b> — the
 * code-side parser and the repair ladder run identically on all three, because a grammar guarantees
 * shape, not that the model numbered its lines correctly or invented no tag.</p>
 */
public enum AiOutputMode {
    FREE_TEXT,
    REGEX,
    JSON_SCHEMA
}
