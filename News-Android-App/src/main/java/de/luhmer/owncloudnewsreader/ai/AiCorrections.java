package de.luhmer.owncloudnewsreader.ai;

import android.database.Cursor;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

import de.luhmer.owncloudnewsreader.ai.prompt.AiPromptBuilder;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;

/**
 * The {@code RECENT CORRECTIONS} block: the last few times the reader disagreed with the model.
 *
 * <p>The predicate is veille's ({@code learn.py:62}), ported without "simplification": a
 * disagreement is a <b>kept</b> article the model scored below the threshold, or a <b>rejected</b>
 * one it scored at or above it — and {@code restore} is a standalone OR term, deliberately, because
 * its {@code LLM_SCORE_AT} is NULL and folding it into the score comparison would silently drop
 * every one of those rows.</p>
 *
 * <p>Three rows, not veille's five: each costs roughly 30 tokens out of a ~550-token fixed prompt,
 * and three is enough to shift a small model's calibration without becoming the dominant signal in
 * the context.</p>
 *
 * <p>These titles are <b>scraped text</b>. They are sanitised on the way into the prompt exactly
 * like article titles, for exactly the same reason.</p>
 */
public final class AiCorrections {

    private static final String TAG = "AiCorrections";

    private AiCorrections() {
        // no instances
    }

    public static List<AiPromptBuilder.Correction> recent(AiDb db, int limit) {
        List<AiPromptBuilder.Correction> out = new ArrayList<>();
        if (db == null || limit <= 0) {
            return out;
        }
        String sql = "SELECT TITLE_SNAP, LLM_SCORE_AT, TO_STATE FROM AI_DECISION"
                + " WHERE ("
                + "   LLM_SCORE_AT IS NOT NULL AND ("
                + "     (TO_STATE = 'kept' AND LLM_SCORE_AT < " + AiRank.SCORE_THRESHOLD + ")"
                + "     OR (TO_STATE = 'rejected' AND LLM_SCORE_AT >= " + AiRank.SCORE_THRESHOLD
                + "   ))"
                + " ) OR ACTION = 'restore'"
                + " ORDER BY _id DESC LIMIT ?";
        try (Cursor c = db.query(sql, new String[]{String.valueOf(limit * 3)})) {
            while (c.moveToNext() && out.size() < limit) {
                if (c.isNull(1)) {
                    // A restore that never reached the LLM has nothing to say about calibration:
                    // the row is selected (the OR term above) but there is no "you said N" to write.
                    continue;
                }
                String title = c.isNull(0) ? "" : c.getString(0);
                int score = c.getInt(1);
                boolean kept = "kept".equals(c.isNull(2) ? "" : c.getString(2));
                out.add(new AiPromptBuilder.Correction(AiText.sanitise(title), score, kept));
            }
        } catch (Throwable t) {
            // Corrections are a calibration nicety. Losing them must never cost a triage run.
            Log.w(TAG, "corrections unavailable", t);
        }
        return out;
    }
}
