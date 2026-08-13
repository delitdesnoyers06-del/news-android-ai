package de.luhmer.owncloudnewsreader.ai.ui;

import android.graphics.Color;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.util.List;

import de.luhmer.owncloudnewsreader.R;
import de.luhmer.owncloudnewsreader.ai.AiFeature;
import de.luhmer.owncloudnewsreader.ai.AiNote;
import de.luhmer.owncloudnewsreader.ai.AiTasteDrafts;
import de.luhmer.owncloudnewsreader.ai.LineDiff;
import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.AiRubricStore;
import de.luhmer.owncloudnewsreader.helper.ThemeChooser;

/**
 * The approval step for a model-drafted interests note.
 *
 * <p>This screen is the <b>only</b> path by which a model's words can become the reader's note, and
 * it requires a deliberate tap on Save. That is the entire mitigation for rubric collapse: a model
 * that narrows the note a little on every pass can only do so with a human agreeing each time, and
 * the strikethrough makes what it proposes to delete impossible to miss.</p>
 *
 * <p>When {@code TasteDraftGuard} flagged the draft — it removes a lot, or it removes more than one
 * topic — the banner is shown and <b>Keep current is the primary action</b>. The guard deliberately
 * does not reject those drafts itself: a reader who has genuinely narrowed their interests is
 * entitled to a shorter note, and only they can tell that from a collapse.</p>
 */
public class AiRubricDiffActivity extends AppCompatActivity {

    private TextView banner;
    private TextView body;
    private Button save;
    private Button keep;

    private AiTasteDrafts.Draft draft;
    private String currentNote = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeChooser.chooseTheme(this);
        super.onCreate(savedInstanceState);
        ThemeChooser.afterOnCreate(this);

        setContentView(R.layout.activity_ai_rubric_diff);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.ai_diff_title);
        }

        banner = findViewById(R.id.diff_banner);
        body = findViewById(R.id.diff_body);
        save = findViewById(R.id.btn_save_note);
        keep = findViewById(R.id.btn_keep_current);

        save.setOnClickListener(v -> saveDraft());
        keep.setOnClickListener(v -> keepCurrent());

        load();
    }

    private void load() {
        AiDb db = db();
        draft = AiTasteDrafts.get(db);
        currentNote = AiNote.current(AiFeature.prefsOf(this), db);

        if (draft == null || !draft.isReady()) {
            body.setText(AiTasteDrafts.STATE_NO_CHANGE.equals(draft == null ? "" : draft.state)
                    ? R.string.ai_diff_no_change : R.string.ai_diff_none);
            save.setEnabled(false);
            findViewById(R.id.diff_legend).setVisibility(View.GONE);
            return;
        }

        body.setText(render(LineDiff.diff(currentNote, draft.body)));

        if (draft.warnTopicsRemoved) {
            banner.setVisibility(View.VISIBLE);
            banner.setText(getString(R.string.ai_diff_warn_topics, draft.removedLines));
        } else if (draft.warnShrink) {
            banner.setVisibility(View.VISIBLE);
            banner.setText(R.string.ai_diff_warn_shrink);
        }
        if (draft.preselectKeepCurrent) {
            // "Pre-select keep current": the safe action becomes the emphasised one.
            keep.requestFocus();
        }
    }

    /** Removed lines struck through and dimmed, added lines prefixed and coloured. */
    private CharSequence render(List<LineDiff.Line> diff) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (LineDiff.Line line : diff) {
            int start = sb.length();
            switch (line.op) {
                case REMOVE:
                    sb.append(line.text).append('\n');
                    sb.setSpan(new StrikethroughSpan(), start, sb.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(Color.parseColor("#B3261E")), start,
                            sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case ADD:
                    sb.append("+ ").append(line.text).append('\n');
                    sb.setSpan(new ForegroundColorSpan(Color.parseColor("#1B5E20")), start,
                            sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                default:
                    sb.append(line.text).append('\n');
                    break;
            }
        }
        return sb;
    }

    private void saveDraft() {
        if (draft == null || !draft.isReady()) {
            return;
        }
        AiNote.save(this, draft.body, AiRubricStore.SOURCE_MODEL_APPROVED);
        AiTasteDrafts.clear(db());
        Toast.makeText(this, R.string.ai_diff_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private void keepCurrent() {
        AiTasteDrafts.clear(db());
        finish();
    }

    private AiDb db() {
        try {
            return new DatabaseConnectionOrm(this).aiDb();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
