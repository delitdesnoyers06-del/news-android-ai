package de.luhmer.owncloudnewsreader.ai.prompt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns articles, the interests note and recent disagreements into the two scoring prompts.
 *
 * <p>Every cap here exists because a small model's compliance degrades with length far faster than
 * its judgement improves. The interests note is cut at <b>the last newline before 1200 chars</b> so a
 * topic is never truncated mid-sentence into a different claim; the excerpt is 600 chars, not
 * veille's 1200, because past roughly the lead paragraph a 1B int4 model's calibration stops
 * improving and its line-format compliance measurably gets worse.</p>
 *
 * <p>The {@code CORRECTIONS} heading is omitted <b>with its content</b> when there is nothing to say.
 * A heading followed by "(none yet)" is noise a small model may try to score.</p>
 *
 * <p>Titles and excerpts are sanitised before they reach the template: control characters collapse to
 * a space and <b>pipes are stripped</b>, because a pipe inside an article title would otherwise
 * appear in the payload of a pipe-delimited format the model is being asked to reproduce.</p>
 */
public final class AiPromptBuilder {

    public static final int INTERESTS_MAX_CHARS = 1200;
    public static final int TITLE_MAX_CHARS = 200;
    public static final int EXCERPT_MAX_CHARS = 600;
    public static final int SOURCE_MAX_CHARS = 40;
    public static final int CORRECTION_TITLE_MAX_CHARS = 120;
    /** veille uses 5; halved because each row is ~30 tokens of a ~550-token fixed prompt. */
    public static final int MAX_CORRECTIONS = 3;
    /** Beyond this a 1B model stops respecting the closed set at all. */
    public static final int MAX_TAG_ENTRIES = 24;

    public static final String NO_TEXT = "no text available";

    private AiPromptBuilder() {
        // no instances
    }

    /** One article, already reduced to what the model is allowed to see. */
    public static final class Article {
        public final String title;
        public final String source;
        public final String excerpt;

        public Article(String title, String source, String excerpt) {
            this.title = title;
            this.source = source;
            this.excerpt = excerpt;
        }
    }

    /** One past disagreement, rendered into the corrections block. */
    public static final class Correction {
        public final String title;
        public final int modelScore;
        public final boolean kept;

        public Correction(String title, int modelScore, boolean kept) {
            this.title = title;
            this.modelScore = modelScore;
            this.kept = kept;
        }
    }

    /**
     * {@code {{articles}}} — two lines per article then a blank line, indexed {@code 1..N}.
     *
     * <p>Local indices, not database ids: small models copy six-digit numbers wrong, {@code [1]} is
     * one token, and an index is a lookup into an array the caller owns — so the model physically
     * cannot name an article we did not send.</p>
     */
    public static String articles(List<Article> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            Article a = items.get(i);
            sb.append('[').append(i + 1).append("] ")
                    .append(oneLine(a.title, TITLE_MAX_CHARS)).append('\n');
            String src = oneLine(a.source, SOURCE_MAX_CHARS);
            String body = oneLine(a.excerpt, EXCERPT_MAX_CHARS);
            if (body.isEmpty()) {
                body = NO_TEXT;
            }
            sb.append('(').append(src).append(") ").append(body).append('\n');
            if (i < items.size() - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** {@code {{interests}}} — trimmed, cut at the last newline before the cap. */
    public static String interests(String note) {
        if (note == null) {
            return "";
        }
        String s = note.trim();
        if (s.length() <= INTERESTS_MAX_CHARS) {
            return s;
        }
        String cut = s.substring(0, INTERESTS_MAX_CHARS);
        int nl = cut.lastIndexOf('\n');
        return (nl > 0 ? cut.substring(0, nl) : cut).trim();
    }

    /** {@code {{tags}}} — one {@code "- slug"} per line, deduped, lowercased, capped. */
    public static String tags(Collection<String> themeSlugs) {
        Set<String> all = new LinkedHashSet<>();
        if (themeSlugs != null) {
            for (String s : themeSlugs) {
                String slug = ScoreLineParser.normaliseTag(s);
                if (!slug.isEmpty()) {
                    all.add(slug);
                }
            }
        }
        all.addAll(ScoreLineParser.FLAG_VOCAB);
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (String slug : all) {
            if (n++ >= MAX_TAG_ENTRIES) {
                break;
            }
            sb.append("- ").append(slug).append('\n');
        }
        return sb.toString();
    }

    /**
     * {@code {{corrections}}} — the empty string when there is nothing to say, <b>including the
     * heading</b>; otherwise a blank line, the heading, up to three rows, and a blank line, so it
     * drops into the template between {@code TAGS} and {@code ARTICLES} without disturbing either.
     */
    public static String corrections(List<Correction> rows) {
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\nRECENT CORRECTIONS\n");
        sb.append("These are past mistakes to learn from, not rules.\n");
        int n = 0;
        for (Correction c : rows) {
            if (n++ >= MAX_CORRECTIONS) {
                break;
            }
            sb.append("- ").append(oneLine(c.title, CORRECTION_TITLE_MAX_CHARS))
                    .append(" | you said ").append(c.modelScore)
                    .append(" | the reader ").append(c.kept ? "kept" : "dismissed")
                    .append(" it\n");
        }
        sb.append('\n');
        return sb.toString();
    }

    /**
     * {@code {{lang}}} — the <b>English name</b> of the device language ("French", "German"), never a
     * BCP-47 tag: a 1B model handles "French" far better than "fr-FR", and the tag occasionally
     * makes it answer <i>about</i> locales.
     */
    public static String languageName(Locale locale) {
        Locale l = locale == null ? Locale.getDefault() : locale;
        String name = l.getDisplayLanguage(Locale.ENGLISH);
        return name == null || name.isEmpty() ? "English" : name;
    }

    /** Assembles the variable map for {@code prompt_score_user.txt}. */
    public static Map<String, String> scoreVars(String interestsNote, Collection<String> themeSlugs,
                                                List<Correction> corrections, List<Article> items,
                                                Locale locale) {
        return PromptTemplate.vars(
                "interests", interests(interestsNote),
                "tags", tags(themeSlugs),
                "corrections", corrections(corrections),
                "articles", articles(items),
                "n", String.valueOf(items.size()),
                "lang", languageName(locale));
    }

    /** Assembles the variable map for {@code prompt_score_repair.txt}. */
    public static Map<String, String> repairVars(int n) {
        return PromptTemplate.vars("n", String.valueOf(n));
    }

    /**
     * Control characters collapse to a space, pipes are removed, runs of whitespace collapse, and
     * the result is capped. The pipe strip is not cosmetic: the wire format is pipe-delimited and
     * article text is scraped from arbitrary sites.
     */
    public static String oneLine(String in, int max) {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(in.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < in.length(); i++) {
            char c = in.charAt(i);
            if (c == '|' || c == '｜') {
                continue;
            }
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
        String s = sb.toString().trim();
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max).trim();
    }

    /** Convenience for callers holding raw candidates. */
    public static List<Article> toArticles(List<String[]> titleSourceExcerpt) {
        List<Article> out = new ArrayList<>(titleSourceExcerpt.size());
        for (String[] a : titleSourceExcerpt) {
            out.add(new Article(a[0], a.length > 1 ? a[1] : "", a.length > 2 ? a[2] : ""));
        }
        return out;
    }
}
