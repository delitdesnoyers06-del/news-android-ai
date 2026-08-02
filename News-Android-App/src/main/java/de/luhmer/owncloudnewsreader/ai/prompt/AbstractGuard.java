package de.luhmer.owncloudnewsreader.ai.prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Cleans the digest abstract, and decides whether there is one at all.
 *
 * <p>There is no structured parse here and there is deliberately <b>no repair turn</b>: the abstract
 * is decoration on a digest that is already complete and correct without it. A second inference to
 * rescue a bad paragraph costs more than the paragraph is worth.</p>
 *
 * <p>The rule that matters: <b>if the cleaned text is shorter than {@value #MIN_CHARS} characters,
 * or contains no letter at all, the abstract is dropped entirely</b> and the digest renders without
 * it. A stub abstract ("Here is the summary:", "…", "N/A") is worse than none — it is the one part
 * of the screen the reader trusts to be prose, so garbage there discredits the item list below it,
 * which was produced by completely different machinery.</p>
 */
public final class AbstractGuard {

    /** Below this, drop the abstract rather than render it. */
    public static final int MIN_CHARS = 80;

    /** The prompt asks for 3-5; 6 is the tolerance before we start cutting. */
    public static final int MAX_SENTENCES = 6;

    /** Belt and braces against a model that never stops. */
    public static final int MAX_CHARS = 1200;

    // No punctuation inside the alternatives: "sure," followed by a space has no word boundary
    // after the comma, so "sure,\\b" never matches the one phrasing it was written for.
    private static final Pattern LEAD_IN = Pattern.compile(
            "^(here is|here's|here are|voici|of course|certainly|sure|okay|ok)\\b.*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TRAILER = Pattern.compile(
            "^(let me know|hope this helps|i hope this|feel free|n'hésitez|do let me know)\\b.*",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HEADING = Pattern.compile("^#+\\s+.*");

    private AbstractGuard() {
        // no instances
    }

    /**
     * @return the abstract to display, or {@code null} meaning <b>render the digest without one</b>.
     *         Never throws, never returns a blank string.
     */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        List<String> lines = stripFences(raw);
        lines = stripLeadIns(lines);
        lines = stripTrailers(lines);

        StringBuilder joined = new StringBuilder();
        for (String line : lines) {
            String s = line.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(s);
        }

        String text = collapseSpaces(joined.toString());
        text = firstSentences(text, MAX_SENTENCES);
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS).trim();
        }
        text = text.trim();

        if (text.length() < MIN_CHARS || !hasLetter(text)) {
            return null;
        }
        return text;
    }

    /**
     * Drops every line that is a code-fence marker. Keeping the <i>content</i> of the block rather
     * than discarding it is deliberate: a model that wraps a perfectly good paragraph in triple
     * backticks has produced a formatting mistake, not a different answer.
     */
    static List<String> stripFences(String raw) {
        List<String> out = new ArrayList<>();
        for (String line : raw.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("```") || t.startsWith("~~~")) {
                continue;
            }
            out.add(line);
        }
        return out;
    }

    /** Removes leading blank lines, Markdown headings and "Here is…" preambles, repeatedly. */
    static List<String> stripLeadIns(List<String> lines) {
        int from = 0;
        while (from < lines.size()) {
            String t = lines.get(from).trim();
            if (t.isEmpty() || HEADING.matcher(t).matches() || LEAD_IN.matcher(t).matches()) {
                from++;
            } else {
                break;
            }
        }
        return new ArrayList<>(lines.subList(from, lines.size()));
    }

    /** Removes trailing blank lines and pleasantries. */
    static List<String> stripTrailers(List<String> lines) {
        int to = lines.size();
        while (to > 0) {
            String t = lines.get(to - 1).trim();
            if (t.isEmpty() || TRAILER.matcher(t).matches()) {
                to--;
            } else {
                break;
            }
        }
        return new ArrayList<>(lines.subList(0, to));
    }

    /**
     * Keeps the first {@code max} sentences. A terminator is {@code . ! ? …} followed by whitespace
     * or the end of the text; a terminator inside a number ("3.5") is therefore not a sentence end.
     */
    static String firstSentences(String text, int max) {
        int sentences = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '.' && c != '!' && c != '?' && c != '…') {
                continue;
            }
            int j = i;
            while (j + 1 < text.length() && isTerminator(text.charAt(j + 1))) {
                j++;
            }
            boolean end = j + 1 >= text.length();
            if (!end && !Character.isWhitespace(text.charAt(j + 1))) {
                continue;
            }
            sentences++;
            if (sentences >= max) {
                return text.substring(0, j + 1).trim();
            }
            i = j;
        }
        return text;
    }

    private static boolean isTerminator(char c) {
        return c == '.' || c == '!' || c == '?' || c == '…';
    }

    private static String collapseSpaces(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean space = Character.isWhitespace(c) || Character.isISOControl(c);
            if (space) {
                if (!lastWasSpace && sb.length() > 0) {
                    sb.append(' ');
                }
                lastWasSpace = true;
            } else {
                sb.append(c);
                lastWasSpace = false;
            }
        }
        return sb.toString().trim();
    }

    private static boolean hasLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetter(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code {{items}}} for {@code prompt_abstract_user.txt}: {@code - **title** — why} per item,
     * highest rank first, capped at {@value #MAX_ITEM_LINES} items / {@value #MAX_ITEMS_CHARS}
     * characters with an explicit "(and k more items)" tail so the model knows it saw a subset.
     */
    public static final int MAX_ITEM_LINES = 25;
    public static final int MAX_ITEMS_CHARS = 3000;

    public static String itemsBlock(List<String[]> titleAndWhy) {
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int total = titleAndWhy == null ? 0 : titleAndWhy.size();
        for (int i = 0; i < total; i++) {
            String[] row = titleAndWhy.get(i);
            String title = AiPromptBuilder.oneLine(row.length > 0 ? row[0] : "",
                    AiPromptBuilder.TITLE_MAX_CHARS);
            String why = AiPromptBuilder.oneLine(row.length > 1 ? row[1] : "", 200);
            if (title.isEmpty()) {
                continue;
            }
            String line = why.isEmpty()
                    ? "- **" + title + "**\n"
                    : "- **" + title + "** — " + why + "\n";
            if (used >= MAX_ITEM_LINES || sb.length() + line.length() > MAX_ITEMS_CHARS) {
                break;
            }
            sb.append(line);
            used++;
        }
        int remaining = total - used;
        if (remaining > 0) {
            sb.append(String.format(Locale.ROOT, "- (and %d more items)%n", remaining));
        }
        return sb.toString();
    }

    /**
     * {@code {{brief}}} — the empty string when the reader wrote no interests note. The heading is
     * omitted with its content; a heading over "(none)" is text a small model tries to summarise.
     */
    public static String briefBlock(String interestsNote) {
        String s = interestsNote == null ? "" : interestsNote.trim();
        if (s.isEmpty()) {
            return "";
        }
        if (s.length() > 300) {
            s = s.substring(0, 300).trim();
        }
        return "\nEXTRA INSTRUCTION FROM THE READER\n" + s + "\n";
    }
}
