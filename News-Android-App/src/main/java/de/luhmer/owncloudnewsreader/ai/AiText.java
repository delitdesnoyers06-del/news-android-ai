package de.luhmer.owncloudnewsreader.ai;

/**
 * Text hygiene for everything that is about to be fed to a model or snapshotted into a decision.
 *
 * <p>Pure Java, no Android types: unit-testable on the JVM with no Robolectric.</p>
 *
 * <p><b>Why 1400 and not veille's 2000.</b> The shipped {@code embedding_gemma.task} graph has a
 * <b>static {@code [1,512]} INT32 input</b>. ~2000 characters overflow 512 tokens and the tokenizer
 * truncates at an undefined boundary with no error — the vector is silently produced from a prefix
 * nobody chose. 1400 characters is roughly 350-400 tokens, comfortably inside (PLAN D12).</p>
 */
public final class AiText {

    /** The embedding input budget. See the class comment before changing this. */
    public static final int EMBED_CLIP_CHARS = 1400;

    /** {@code AI_DECISION.TITLE_SNAP} budget — it becomes few-shot prompt text later. */
    public static final int TITLE_SNAP_CHARS = 120;

    private AiText() {
        // no instances
    }

    /**
     * Clips to at most {@code maxChars} <b>chars</b>, never splitting a surrogate pair.
     *
     * @return {@code ""} for a null input — an empty string is a legal thing to embed and callers
     *         must not have to null-check
     */
    public static String clip(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        if (maxChars <= 0) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        int end = maxChars;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    /**
     * Strips tags and collapses every run of whitespace to a single space. Article bodies are HTML
     * and article titles routinely contain newlines and {@code &nbsp;}.
     */
    public static String sanitise(String text) {
        if (text == null) {
            return "";
        }
        String noTags = text.replaceAll("<[^>]*>", " ");
        String noEntities = noTags.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'");
        return noEntities.replaceAll("\\s+", " ").trim();
    }

    /** {@code sanitise} then {@code clip} — the shape every model input takes. */
    public static String prepare(String text, int maxChars) {
        return clip(sanitise(text), maxChars);
    }

    /**
     * The embedding input, veille {@code _stage_embed}: {@code title + "\n" + summary}, clipped.
     * Both halves are sanitised independently so a null body cannot swallow the title.
     */
    public static String embedInput(String title, String body) {
        String t = sanitise(title);
        String b = sanitise(body);
        String joined = b.isEmpty() ? t : (t.isEmpty() ? b : t + "\n" + b);
        return clip(joined, EMBED_CLIP_CHARS);
    }

    /** The decision title snapshot. Never null, never longer than {@link #TITLE_SNAP_CHARS}. */
    public static String titleSnap(String title) {
        return clip(sanitise(title), TITLE_SNAP_CHARS);
    }
}
