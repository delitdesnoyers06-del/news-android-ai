package de.luhmer.owncloudnewsreader.ai.engine;

/**
 * Every failure the AI half can produce, as a <b>checked</b> exception.
 *
 * <p>Checked on purpose: it forces every caller to write the degradation branch instead of letting
 * an unhandled runtime exception escape into a sync or a gesture handler. PLAN invariant 7 — degrade,
 * never crash — is enforced by the compiler here rather than by review.</p>
 */
public class AiException extends Exception {

    private static final long serialVersionUID = 1L;

    /** The taxonomy the degradation ladder switches on. */
    public enum Kind {
        /** {@code cb_ai_enabled == false}. */
        DISABLED,
        /** No arm64-v8a, or device tier T0. */
        UNSUPPORTED_DEVICE,
        /** The selected model is not on disk. */
        NOT_INSTALLED,
        /** {@code initialize()} threw or blew the load watchdog. */
        LOAD_FAILED,
        OUT_OF_MEMORY,
        /** A per-call watchdog fired. */
        TIMEOUT,
        /** cancel(), worker stopped, or the master switch flipped off mid-run. */
        CANCELLED,
        /** Anything else the runtime threw. */
        RUNTIME
    }

    public final Kind kind;

    public AiException(Kind kind, String msg) {
        this(kind, msg, null);
    }

    public AiException(Kind kind, String msg, Throwable cause) {
        super(msg, cause);
        this.kind = kind;
    }

    /**
     * True when the same work is worth attempting on the next sync. A missing model or an
     * unsupported device will not have changed by then; a timeout or a memory squeeze might have.
     */
    public boolean retryableNextSync() {
        return kind == Kind.TIMEOUT || kind == Kind.CANCELLED || kind == Kind.OUT_OF_MEMORY;
    }
}
