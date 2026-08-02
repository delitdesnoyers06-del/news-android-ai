package de.luhmer.owncloudnewsreader.ai.prompt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Derives the closed theme vocabulary from the free-text interests note.
 *
 * <p>veille has explicit theme slugs because a human curated them in a config file. On a phone the
 * note is the only artefact the user maintains, so the slugs are <b>derived</b> from it: one per
 * line, from the words before the first parenthesis, comma or colon. Deterministic, so the same note
 * always produces the same vocabulary, and the closed-set filter in {@link ScoreLineParser} stays a
 * code guarantee rather than a prompt request.</p>
 *
 * <p>An empty note yields an empty theme vocabulary — the three flags still apply, so the model can
 * still say {@code regulation} and the article still gets a score. That is the intended cold-start
 * behaviour: no interests note is a valid, working configuration.</p>
 */
public final class AiInterests {

    /** Leaves room for the three flags inside {@link AiPromptBuilder#MAX_TAG_ENTRIES}. */
    public static final int MAX_THEMES = 21;
    private static final int MAX_WORDS_PER_SLUG = 3;

    private AiInterests() {
        // no instances
    }

    public static List<String> themeSlugs(String note) {
        Set<String> out = new LinkedHashSet<>();
        if (note == null) {
            return new ArrayList<>(out);
        }
        for (String line : note.split("\n")) {
            String s = line.trim();
            if (s.startsWith("-") || s.startsWith("*") || s.startsWith("•")) {
                s = s.substring(1).trim();
            }
            s = cutAt(s, '(');
            s = cutAt(s, ',');
            s = cutAt(s, ':');
            s = cutAt(s, ';');
            String slug = slugify(s);
            if (!slug.isEmpty()) {
                out.add(slug);
            }
            if (out.size() >= MAX_THEMES) {
                break;
            }
        }
        return new ArrayList<>(out);
    }

    private static String cutAt(String s, char c) {
        int at = s.indexOf(c);
        return at < 0 ? s : s.substring(0, at);
    }

    static String slugify(String s) {
        String[] words = s.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (String w : words) {
            String clean = ScoreLineParser.normaliseTag(w);
            if (clean.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('-');
            }
            sb.append(clean);
            if (++used >= MAX_WORDS_PER_SLUG) {
                break;
            }
        }
        return sb.toString();
    }
}
