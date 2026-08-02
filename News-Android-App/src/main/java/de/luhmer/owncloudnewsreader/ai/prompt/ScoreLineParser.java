package de.luhmer.owncloudnewsreader.ai.prompt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "The prompt is a request; the code is the guarantee."
 *
 * <p>Every constraint the scoring prompt states — a score in 0..3, a closed tag vocabulary, one line
 * per article, no pipe in {@code why} — is <b>also</b> enforced here, on both the constrained and the
 * unconstrained path. A grammar guarantees the <i>shape</i> of the output; it does not guarantee the
 * model numbered its lines correctly, did not repeat one, and did not invent a plausible tag. On the
 * constrained path this class's failure rate should be about zero, and that delta is exactly how you
 * find out whether constrained decoding is actually switched on.</p>
 *
 * <p><b>This class never throws.</b> Not for null input, not for a whole-prompt echo, not for
 * binary noise. Unparseable output is an empty (or partial) map, which the repair ladder turns into
 * {@code LLM_SCORE = NULL} — never a lost article and never an exception on the sync path.</p>
 *
 * <p>Alignment is <b>by index only</b>. There is deliberately no positional fallback: with local
 * {@code 1..N} indices a model that renumbers has produced garbage, and silently pairing garbage
 * with articles by position is how a triage list becomes confidently wrong.</p>
 */
public final class ScoreLineParser {

    /** The three flags, orthogonal to the user's own theme slugs. */
    public static final Set<String> FLAG_VOCAB = Collections.unmodifiableSet(
            new LinkedHashSet<>(Arrays.asList("competitor", "regulation", "customer")));

    public static final int MAX_TAGS = 6;
    public static final int MAX_WHY_CHARS = 160;
    public static final int MIN_SCORE = 0;
    public static final int MAX_SCORE = 3;

    /**
     * Lax on purpose. The strict shape is the grammar's job; this regex only has to find the four
     * fields so the <i>code</i> can clean them. A tight score group would make {@code "2.5"} fail to
     * match at all, and the guarantee table says a float is rounded, not dropped.
     */
    private static final Pattern LINE = Pattern.compile(
            "^\\s*\\[?\\s*(\\d{1,3})\\s*\\]?\\s*\\|\\s*([^|]*?)\\s*\\|([^|]*)\\|(.*)$");

    private ScoreLineParser() {
        // no instances
    }

    /** One accepted line. Immutable once built; {@code themes} order is load-bearing. */
    public static final class Line {
        public final int index;
        public final int score;
        /** Tags minus the flag vocabulary, order preserved: {@code themes[0]} groups the digest. */
        public final List<String> themes;
        public final List<String> flags;
        public final String why;
        /** Everything the model actually emitted, before filtering — the invented-tag metric. */
        public final List<String> rawTags;

        Line(int index, int score, List<String> themes, List<String> flags, String why,
             List<String> rawTags) {
            this.index = index;
            this.score = score;
            this.themes = Collections.unmodifiableList(themes);
            this.flags = Collections.unmodifiableList(flags);
            this.why = why;
            this.rawTags = Collections.unmodifiableList(rawTags);
        }

        /** Comma-joined, the {@code AI_SCORE.THEMES} storage form. */
        public String themesCsv() {
            return join(themes);
        }

        public String flagsCsv() {
            return join(flags);
        }

        private static String join(List<String> in) {
            if (in.isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (String s : in) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(s);
            }
            return sb.toString();
        }
    }

    /**
     * @param raw        the model's output, in any state whatsoever
     * @param n          the batch size; indices outside {@code 1..n} are dropped, never appended
     * @param themeSlugs the user's theme vocabulary; anything outside it &cup; {@link #FLAG_VOCAB}
     *                   is dropped silently
     * @return index &rarr; line, iteration order = first-seen. Missing indices are the repair
     *         ladder's input, not an error.
     */
    public static Map<Integer, Line> parse(String raw, int n, Collection<String> themeSlugs) {
        Map<Integer, Line> out = new LinkedHashMap<>();
        try {
            String text = preprocess(raw);
            if (text.isEmpty()) {
                return out;
            }
            Set<String> vocab = vocabulary(themeSlugs);
            for (String rawLine : text.split("\n", -1)) {
                Matcher m = LINE.matcher(rawLine);
                if (!m.matches()) {
                    continue;                       // preamble, "Here are the scores:", trailing chat
                }
                int index = toInt(m.group(1), -1);
                if (index < 1 || index > n) {
                    continue;                       // out of range: dropped, never appended
                }
                if (out.containsKey(index)) {
                    continue;                       // duplicate: the first occurrence is the considered one
                }
                int score = clamp(scoreOf(m.group(2)));
                List<String> rawTags = splitTags(m.group(3));
                List<String> kept = filterTags(rawTags, vocab);
                List<String> flags = new ArrayList<>();
                List<String> themes = new ArrayList<>();
                for (String t : kept) {
                    if (FLAG_VOCAB.contains(t)) {
                        flags.add(t);
                    } else {
                        themes.add(t);
                    }
                }
                out.put(index, new Line(index, score, themes, flags, cleanWhy(m.group(4)), rawTags));
            }
        } catch (Throwable t) {
            // Whatever went wrong, a partial map is a better answer than an exception on the
            // sync path. The repair ladder handles "some lines missing" already.
            return out;
        }
        return out;
    }

    /** The indices of {@code 1..n} that {@code parsed} does not cover, in order. */
    public static List<Integer> missing(Map<Integer, Line> parsed, int n) {
        List<Integer> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            if (!parsed.containsKey(i)) {
                out.add(i);
            }
        }
        return out;
    }

    /**
     * Merges a repair turn's output into the first attempt. <b>Existing lines win</b>: the repair
     * prompt asks for the whole batch again, and a line we already accepted was produced with the
     * article still in context, whereas the repair turn is a model recovering from its own mistake.
     */
    public static Map<Integer, Line> merge(Map<Integer, Line> first, Map<Integer, Line> repair) {
        Map<Integer, Line> out = new LinkedHashMap<>(first);
        for (Map.Entry<Integer, Line> e : repair.entrySet()) {
            if (!out.containsKey(e.getKey())) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    // ---- shared preprocessing ---------------------------------------------------------------

    /**
     * Normalisation applied to every response, in this order: null/blank guard, code-fence
     * stripping, BOM and leading blank lines, then the character substitutions that a small model
     * makes on CJK-adjacent context ({@code ｜} fullwidth pipe) or when it copies from a web
     * page (NBSP, CRLF).
     */
    static String preprocess(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("\uFEFF", "")           // BOM
                .replace("\r\n", "\n")
                .replace("\r", "\n")
                .replace('\u00A0', ' ')                 // NBSP
                .replace('\uFF5C', '|');                // fullwidth pipe, emitted on CJK context
        s = stripFences(s);
        return s.trim();
    }

    /**
     * An even number of fences means the model wrapped its answer: drop them all. An odd number
     * means it opened one and never closed it: drop the opener only, because the content after it is
     * still the answer.
     */
    private static String stripFences(String s) {
        int fences = 0;
        int at = s.indexOf("```");
        while (at >= 0) {
            fences++;
            at = s.indexOf("```", at + 3);
        }
        if (fences == 0) {
            return s;
        }
        final boolean dropAll = fences % 2 == 0;
        boolean droppedOne = false;
        StringBuilder out = new StringBuilder(s.length());
        for (String line : s.split("\n", -1)) {
            if (line.trim().startsWith("```")) {
                if (dropAll) {
                    continue;
                }
                if (!droppedOne) {
                    droppedOne = true;
                    continue;
                }
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
        }
        return out.toString();
    }

    private static Set<String> vocabulary(Collection<String> themeSlugs) {
        Set<String> v = new HashSet<>(FLAG_VOCAB);
        if (themeSlugs != null) {
            for (String s : themeSlugs) {
                String slug = normaliseTag(s);
                if (!slug.isEmpty()) {
                    v.add(slug);
                }
            }
        }
        return v;
    }

    private static List<String> splitTags(String field) {
        List<String> out = new ArrayList<>();
        if (field == null) {
            return out;
        }
        String f = field.trim();
        if (f.isEmpty() || "-".equals(f)) {
            return out;
        }
        for (String part : f.split(",")) {
            String tag = normaliseTag(part);
            if (!tag.isEmpty() && !"-".equals(tag)) {
                out.add(tag);
            }
        }
        return out;
    }

    private static List<String> filterTags(List<String> rawTags, Set<String> vocab) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String t : rawTags) {
            if (out.size() >= MAX_TAGS) {
                break;
            }
            if (vocab.contains(t) && seen.add(t)) {
                out.add(t);
            }
        }
        return out;
    }

    static String normaliseTag(String in) {
        if (in == null) {
            return "";
        }
        String s = in.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '-').replace('_', '-');
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-') {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** {@code toIntOrNull() ?: 0}, with a float round attempted first so {@code "2.5"} becomes 2. */
    static int scoreOf(String field) {
        if (field == null) {
            return 0;
        }
        String s = field.trim();
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            // fall through
        }
        try {
            return (int) Math.round(Double.parseDouble(s));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    static int clamp(int v) {
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, v));
    }

    private static int toInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Empty is legal — the UI falls back to the excerpt's first sentence. */
    static String cleanWhy(String field) {
        if (field == null) {
            return "";
        }
        String s = field.trim();
        if (s.length() <= MAX_WHY_CHARS) {
            return s;
        }
        String cut = s.substring(0, MAX_WHY_CHARS - 1);
        int lastSpace = cut.lastIndexOf(' ');
        if (lastSpace > MAX_WHY_CHARS / 3) {
            cut = cut.substring(0, lastSpace);
        }
        return cut.trim() + "…";
    }
}
