package de.luhmer.owncloudnewsreader.ai.prompt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import de.luhmer.owncloudnewsreader.ai.LineDiff;

/**
 * The code half of the anti-collapse guarantee for the interests note.
 *
 * <h3>The failure this exists to stop</h3>
 * A model asked to rewrite its own instructions from a noisy signal narrows them a little on every
 * pass: the reader dismissed one article about transport regulation, so the line about transport
 * regulation goes; fewer articles are selected; the reader stars less; the note shrinks again. After
 * five passes the note says nothing and the folder is empty. The system prompt asks the model not to
 * do this. <b>This class is what makes it true</b> — and where it cannot decide, it puts the choice
 * in front of the one entity that can tell a legitimate cleanup from a collapse.
 *
 * <h3>Warn, do not auto-reject</h3>
 * A shrinking draft is <i>flagged</i>, never silently discarded, because a reader who has genuinely
 * narrowed their interests is entitled to a shorter note. Only structurally broken output (too
 * short, or not a note at all) is rejected outright.
 *
 * <p>Nothing in this class writes anything. The note is written by exactly one code path: the user
 * tapping Save in {@code AiRubricDiffActivity}.</p>
 */
public final class TasteDraftGuard {

    /** Below this the draft is not a note, whatever it is. */
    public static final int MIN_CHARS = 40;

    public static final int MAX_LINES = 12;
    public static final int MAX_CHARS = 1200;

    /** Shorter than this fraction of the current note ⇒ the collapse banner. */
    public static final double SHRINK_RATIO = 0.60d;

    /** More removed topic lines than this ⇒ the banner, and "keep current" pre-selected. */
    public static final int MAX_TOPIC_REMOVALS = 1;

    /** veille {@code LEARN_MIN_DECISIONS}. */
    public static final int LEARN_MIN_DECISIONS = 25;

    /** Leftovers from the scoring format. Their presence means the model answered the wrong task. */
    private static final Pattern WRONG_TASK = Pattern.compile(
            "(?m)^\\s*(WHY:|SUM:)|^\\s*\\d+\\s*\\|.*\\|", Pattern.CASE_INSENSITIVE);

    public enum Outcome {
        /** Structurally unusable. Keep the current note, tell the reader there is not enough yet. */
        REJECTED,
        /** One repair turn ("Output only the note. No other text."), then reject. */
        NEEDS_REPAIR,
        /** Byte-identical to the current note. A valid answer the system prompt licenses. */
        NO_CHANGE,
        /** Showable. May still carry warnings. */
        OK
    }

    /** What the guard decided, and what the diff screen must show. */
    public static final class Verdict {
        public final Outcome outcome;
        /** The cleaned draft. Never null; empty only when {@link Outcome#REJECTED}. */
        public final String draft;
        /** A short machine reason, for logs and for the rejection message. */
        public final String reason;
        public final boolean warnShrink;
        public final boolean warnTopicsRemoved;
        /** True ⇒ the dialog opens with "keep current" selected, not "save". */
        public final boolean preselectKeepCurrent;
        public final int removedTopicLines;
        public final int addedTopicLines;
        /** Lines dropped because they were copied verbatim from an article title. */
        public final int strippedEchoLines;

        Verdict(Outcome outcome, String draft, String reason, boolean warnShrink,
                boolean warnTopicsRemoved, int removed, int added, int strippedEchoLines) {
            this.outcome = outcome;
            this.draft = draft == null ? "" : draft;
            this.reason = reason;
            this.warnShrink = warnShrink;
            this.warnTopicsRemoved = warnTopicsRemoved;
            this.preselectKeepCurrent = warnShrink || warnTopicsRemoved;
            this.removedTopicLines = removed;
            this.addedTopicLines = added;
            this.strippedEchoLines = strippedEchoLines;
        }

        /** True when the draft may be offered to the user at all. */
        public boolean savable() {
            return outcome == Outcome.OK;
        }
    }

    private TasteDraftGuard() {
        // no instances
    }

    public static Verdict check(String raw, String currentNote) {
        return check(raw, currentNote, null);
    }

    /**
     * @param scrapedTitles the titles that were injected into the prompt. Any draft line copied
     *                      verbatim from one of them is stripped: it is either the model echoing its
     *                      input, or an article title that contained an instruction and got obeyed.
     *                      Both produce the same artefact and both must not reach the note.
     */
    public static Verdict check(String raw, String currentNote, Collection<String> scrapedTitles) {
        String current = currentNote == null ? "" : currentNote.trim();
        if (raw == null || raw.trim().isEmpty()) {
            return new Verdict(Outcome.REJECTED, "", "empty", false, false, 0, 0, 0);
        }
        if (raw.contains("```") || raw.contains("~~~") || WRONG_TASK.matcher(raw).find()) {
            // Not cleaned on purpose: the caller gets one repair turn with the raw text still in the
            // conversation, which is the only context that makes the repair instruction meaningful.
            return new Verdict(Outcome.NEEDS_REPAIR, raw.trim(), "wrong_format",
                    false, false, 0, 0, 0);
        }

        Set<String> echoes = normalisedTitles(scrapedTitles);
        List<String> kept = new ArrayList<>();
        int stripped = 0;
        for (String line : LineDiff.topicLines(raw)) {
            if (echoes.contains(normalise(line))) {
                stripped++;
                continue;
            }
            kept.add(line);
        }

        String draft = truncate(kept);
        if (draft.length() < MIN_CHARS) {
            return new Verdict(Outcome.REJECTED, draft, "too_short", false, false, 0, 0, stripped);
        }
        if (draft.equals(current)) {
            return new Verdict(Outcome.NO_CHANGE, draft, "identical", false, false, 0, 0, stripped);
        }

        List<LineDiff.Line> diff = LineDiff.diff(current, draft);
        int removed = LineDiff.count(diff, LineDiff.Op.REMOVE);
        int added = LineDiff.count(diff, LineDiff.Op.ADD);

        boolean shrink = !current.isEmpty()
                && draft.length() < (int) Math.floor(current.length() * SHRINK_RATIO);
        boolean topicsRemoved = removed > MAX_TOPIC_REMOVALS;

        return new Verdict(Outcome.OK, draft, "ok", shrink, topicsRemoved, removed, added,
                stripped);
    }

    /** Truncates at the last complete line inside the caps. Never mid-sentence. */
    static String truncate(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (String line : lines) {
            if (used >= MAX_LINES) {
                break;
            }
            int next = sb.length() + line.length() + 1;
            if (next > MAX_CHARS) {
                break;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
            used++;
        }
        return sb.toString().trim();
    }

    private static Set<String> normalisedTitles(Collection<String> titles) {
        Set<String> out = new HashSet<>();
        if (titles == null) {
            return out;
        }
        for (String t : titles) {
            String n = normalise(t);
            // A one-word "title" would strip a legitimate one-word topic line.
            if (n.length() >= 12) {
                out.add(n);
            }
        }
        return out;
    }

    /** Bullet, case and whitespace insensitive: those are exactly the edits a model makes. */
    static String normalise(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        while (!t.isEmpty() && (t.charAt(0) == '-' || t.charAt(0) == '*' || t.charAt(0) == '•')) {
            t = t.substring(1).trim();
        }
        StringBuilder sb = new StringBuilder(t.length());
        boolean lastWasSpace = false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!lastWasSpace && sb.length() > 0) {
                    sb.append(' ');
                }
                lastWasSpace = true;
            } else {
                sb.append(Character.toLowerCase(c));
                lastWasSpace = false;
            }
        }
        return sb.toString().trim().toLowerCase(Locale.ROOT);
    }
}
