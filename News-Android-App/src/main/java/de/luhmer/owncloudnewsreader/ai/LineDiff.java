package de.luhmer.owncloudnewsreader.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A line-level LCS diff — veille's {@code difflib} approval step, in about eighty lines.
 *
 * <p>No new dependency: {@code java-diff-utils} would be a library added to an APK that already
 * ships two multi-megabyte binary blobs, to diff two documents that are capped at twelve lines
 * each.</p>
 *
 * <p>The diff is used twice and both uses are safety-critical: {@code TasteDraftGuard} counts
 * removed topic lines with it (the collapse detector), and {@code AiRubricDiffActivity} renders it
 * so the human can see what a model proposes to delete before they approve it.</p>
 */
public final class LineDiff {

    /** Above this the DP table is not worth building; both documents are capped far below it. */
    public static final int MAX_LINES = 400;

    public enum Op {
        KEEP,
        ADD,
        REMOVE
    }

    /** One line of the rendered diff. */
    public static final class Line {
        public final Op op;
        public final String text;

        public Line(Op op, String text) {
            this.op = op;
            this.text = text;
        }

        @Override
        public String toString() {
            return (op == Op.ADD ? "+ " : op == Op.REMOVE ? "- " : "  ") + text;
        }
    }

    private LineDiff() {
        // no instances
    }

    /** Splits into lines, dropping blank ones — a blank line is not a topic. */
    public static List<String> topicLines(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String s = line.trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    public static List<Line> diff(List<String> before, List<String> after) {
        List<String> a = cap(before);
        List<String> b = cap(after);
        int n = a.size();
        int m = b.size();
        int[][] lcs = new int[n + 1][m + 1];
        for (int i = n - 1; i >= 0; i--) {
            for (int j = m - 1; j >= 0; j--) {
                if (a.get(i).equals(b.get(j))) {
                    lcs[i][j] = lcs[i + 1][j + 1] + 1;
                } else {
                    lcs[i][j] = Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
        }
        List<Line> out = new ArrayList<>(n + m);
        int i = 0;
        int j = 0;
        while (i < n && j < m) {
            if (a.get(i).equals(b.get(j))) {
                out.add(new Line(Op.KEEP, a.get(i)));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out.add(new Line(Op.REMOVE, a.get(i)));
                i++;
            } else {
                out.add(new Line(Op.ADD, b.get(j)));
                j++;
            }
        }
        while (i < n) {
            out.add(new Line(Op.REMOVE, a.get(i++)));
        }
        while (j < m) {
            out.add(new Line(Op.ADD, b.get(j++)));
        }
        return out;
    }

    /** Convenience over two whole documents. */
    public static List<Line> diff(String before, String after) {
        return diff(topicLines(before), topicLines(after));
    }

    public static int count(List<Line> diff, Op op) {
        int n = 0;
        for (Line l : diff) {
            if (l.op == op) {
                n++;
            }
        }
        return n;
    }

    private static List<String> cap(List<String> in) {
        if (in == null) {
            return Collections.emptyList();
        }
        return in.size() <= MAX_LINES ? in : new ArrayList<>(in.subList(0, MAX_LINES));
    }
}
