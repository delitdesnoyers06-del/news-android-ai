package de.luhmer.owncloudnewsreader.ai.ui;

import android.content.Intent;
import android.os.Bundle;
import android.text.format.Formatter;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Named;

import de.luhmer.owncloudnewsreader.NewsReaderApplication;
import de.luhmer.owncloudnewsreader.R;
import de.luhmer.owncloudnewsreader.SettingsActivity;
import de.luhmer.owncloudnewsreader.ai.AiCapability;
import de.luhmer.owncloudnewsreader.ai.AiNote;
import de.luhmer.owncloudnewsreader.ai.AiTasteDrafts;
import de.luhmer.owncloudnewsreader.ai.download.AiModelRepository;
import de.luhmer.owncloudnewsreader.ai.model.AiCatalogEntry;
import de.luhmer.owncloudnewsreader.ai.prompt.TasteDraftGuard;
import de.luhmer.owncloudnewsreader.ai.work.AiLazyScheduler;
import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.AiRubricStore;

/**
 * The AI detail screen.
 *
 * <p>Two lines here are not optional. The Dagger injection gives us
 * {@code sharedPreferencesFileName}, and {@code setSharedPreferencesName} makes this screen write to
 * the same file every other preference in the app uses. Without it the AI preferences land in
 * {@code <pkg>_preferences} vs the framework default and <b>nothing else reads them</b> — the switch
 * would look on here and off everywhere else, with no error.</p>
 */
public class AiSettingsFragment extends PreferenceFragmentCompat {

    protected @Inject @Named("sharedPreferencesFileName") String sharedPreferencesFileName;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        ((NewsReaderApplication) requireActivity().getApplication()).getAppComponent()
                .injectFragment(this);

        // Mandatory: the app uses a custom preferences file (ApiModule provides it by name).
        getPreferenceManager().setSharedPreferencesName(sharedPreferencesFileName);

        addPreferencesFromResource(R.xml.pref_ai_detail);

        Preference manage = findPreference(SettingsActivity.PREF_AI_MANAGE_MODELS);
        if (manage != null) {
            manage.setOnPreferenceClickListener(p -> {
                startActivity(new Intent(requireContext(), AiModelManagerActivity.class));
                return true;
            });
        }
        bindInterests();
    }

    // ------------------------------------------------------------------ the interests note

    /**
     * The note is the reader's, and it is the source of truth for scoring. Everything here routes
     * writes through {@link AiNote#save} so that one edit always has its three consequences: a new
     * version in the append-only history, a dropped centroid cache, and every stored verdict marked
     * retryable. An edit that quietly does nothing for a week teaches the reader the field is
     * decorative.
     */
    private void bindInterests() {
        EditTextPreference note = findPreference(SettingsActivity.EDT_AI_INTERESTS);
        if (note != null) {
            note.setDialogMessage(getString(R.string.ai_interests_hint));
            note.setOnPreferenceChangeListener((preference, newValue) -> {
                AiNote.save(requireContext(), String.valueOf(newValue),
                        AiRubricStore.SOURCE_USER);
                refreshNoteSummary();
                return true;
            });
        }

        Preference suggest = findPreference(SettingsActivity.PREF_AI_SUGGEST_INTERESTS);
        if (suggest != null) {
            // Visible only where it can work. A button that is permanently disabled reads as broken;
            // a button that is absent reads as "this build does not do that".
            suggest.setVisible(AiLazyScheduler.available());
            suggest.setOnPreferenceClickListener(p -> {
                onSuggestClicked();
                return true;
            });
        }

        Preference history = findPreference(SettingsActivity.PREF_AI_NOTE_HISTORY);
        if (history != null) {
            history.setOnPreferenceClickListener(p -> {
                showHistory();
                return true;
            });
        }

        Preference reset = findPreference(SettingsActivity.PREF_AI_RESET_TASTE);
        if (reset != null) {
            reset.setOnPreferenceClickListener(p -> {
                new AlertDialog.Builder(requireContext())
                        .setMessage(R.string.ai_reset_taste_confirm)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.pref_title_ai_reset_taste, (d, w) -> {
                            AiNote.resetTaste(requireContext());
                            Toast.makeText(requireContext(), R.string.ai_reset_taste_done,
                                    Toast.LENGTH_SHORT).show();
                            refresh();
                        })
                        .show();
                return true;
            });
        }
    }

    /**
     * Either opens the draft that is waiting, or asks for one. The 25-decision floor is a
     * precondition on the tap, not a schedule: nothing here ever runs on a timer.
     */
    private void onSuggestClicked() {
        AiDb db = aiDb();
        AiTasteDrafts.Draft draft = AiTasteDrafts.get(db);
        if (draft.isReady()) {
            startActivity(new Intent(requireContext(), AiRubricDiffActivity.class));
            return;
        }
        if (!AiNote.canSuggest(db)) {
            Toast.makeText(requireContext(),
                    getString(R.string.pref_summary_ai_suggest_locked,
                            TasteDraftGuard.LEARN_MIN_DECISIONS, AiNote.decisionCount(db)),
                    Toast.LENGTH_LONG).show();
            return;
        }
        AiLazyScheduler.enqueueTasteDraft(requireContext().getApplicationContext(), db);
        refreshNoteSummary();
    }

    /** The revert path: five versions, one tap. Append-only, so nothing is ever lost by reverting. */
    private void showHistory() {
        List<AiRubricStore.Row> rows = AiNote.history(requireContext(), AiNote.HISTORY_LIMIT);
        if (rows.isEmpty()) {
            return;
        }
        DateFormat fmt = android.text.format.DateFormat.getDateFormat(requireContext());
        CharSequence[] labels = new CharSequence[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            AiRubricStore.Row r = rows.get(i);
            String first = r.body == null ? "" : r.body.trim().split("\\n")[0];
            labels[i] = fmt.format(new Date(r.createdAt)) + (r.active ? " ✓" : "") + "\n" + first;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.pref_title_ai_note_history)
                .setItems(labels, (d, which) -> {
                    if (AiNote.revert(requireContext(), rows.get(which).id)) {
                        Toast.makeText(requireContext(), R.string.ai_note_reverted,
                                Toast.LENGTH_SHORT).show();
                    }
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void refreshNoteSummary() {
        AiDb db = aiDb();
        EditTextPreference note = findPreference(SettingsActivity.EDT_AI_INTERESTS);
        if (note != null) {
            String body = AiNote.current(requireContext());
            note.setSummary(body.isEmpty()
                    ? getString(R.string.pref_summary_ai_interests_empty) : body);
        }

        Preference suggest = findPreference(SettingsActivity.PREF_AI_SUGGEST_INTERESTS);
        if (suggest != null) {
            AiTasteDrafts.Draft draft = AiTasteDrafts.get(db);
            int decisions = AiNote.decisionCount(db);
            if (AiTasteDrafts.STATE_RUNNING.equals(draft.state)) {
                suggest.setSummary(R.string.pref_summary_ai_suggest_running);
            } else if (draft.isReady()) {
                suggest.setSummary(R.string.pref_summary_ai_suggest_ready);
            } else if (AiTasteDrafts.STATE_NO_CHANGE.equals(draft.state)) {
                suggest.setSummary(R.string.pref_summary_ai_suggest_no_change);
            } else if (AiTasteDrafts.STATE_FAILED.equals(draft.state)) {
                suggest.setSummary(R.string.pref_summary_ai_suggest_failed);
            } else if (decisions < TasteDraftGuard.LEARN_MIN_DECISIONS) {
                suggest.setSummary(getString(R.string.pref_summary_ai_suggest_locked,
                        TasteDraftGuard.LEARN_MIN_DECISIONS, decisions));
            } else {
                suggest.setSummary(R.string.pref_summary_ai_suggest_interests);
            }
        }

        Preference history = findPreference(SettingsActivity.PREF_AI_NOTE_HISTORY);
        if (history != null) {
            int n = AiNote.history(requireContext(), AiNote.HISTORY_LIMIT).size();
            history.setVisible(n > 0);
            history.setSummary(getString(R.string.pref_summary_ai_note_history, n));
        }
    }

    private AiDb aiDb() {
        try {
            return new DatabaseConnectionOrm(requireContext()).aiDb();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    /**
     * Rebuilds everything that depends on what is installed. Done in {@code onResume} rather than
     * once at creation because the user leaves this screen to download a model and comes straight
     * back — a summary that still says "None installed" would read as a failed download.
     */
    private void refresh() {
        AiModelRepository repo;
        try {
            repo = new AiModelRepository(requireContext());
        } catch (Throwable t) {
            return;
        }
        bindTriagePicker(repo);
        bindEmbedderStatus(repo);
        bindManageSummary(repo);
        bindTierOnlyRows();
        refreshNoteSummary();
    }

    /**
     * The triage picker lists <b>installed</b> models only. Offering a model that is not on disk
     * would let the user select a dangling path, which is the worst failure shape in this feature:
     * a silent, permanent AI outage.
     */
    private void bindTriagePicker(AiModelRepository repo) {
        ListPreference triage = findPreference(SettingsActivity.SP_AI_MODEL_TRIAGE);
        if (triage == null) {
            return;
        }
        List<CharSequence> labels = new ArrayList<>();
        List<CharSequence> values = new ArrayList<>();
        for (AiCatalogEntry e : repo.catalog().llms()) {
            if (repo.isInstalled(e)) {
                labels.add(e.displayName);
                values.add(e.id);
            }
        }
        if (labels.isEmpty()) {
            triage.setEnabled(false);
            triage.setSummary(R.string.pref_summary_ai_no_model);
            triage.setEntries(new CharSequence[0]);
            triage.setEntryValues(new CharSequence[0]);
            return;
        }
        triage.setEnabled(true);
        triage.setEntries(labels.toArray(new CharSequence[0]));
        triage.setEntryValues(values.toArray(new CharSequence[0]));
        CharSequence current = triage.getEntry();
        triage.setSummary(current != null ? current : labels.get(0));
        triage.setOnPreferenceChangeListener((preference, newValue) -> {
            ((ListPreference) preference).setValue(String.valueOf(newValue));
            preference.setSummary(((ListPreference) preference).getEntry());
            return true;
        });
    }

    /** A status row, not a picker (PLAN D21): there is exactly one embedder and never a second. */
    private void bindEmbedderStatus(AiModelRepository repo) {
        Preference embedding = findPreference(SettingsActivity.SP_AI_MODEL_EMBEDDING);
        if (embedding == null) {
            return;
        }
        AiCatalogEntry e = repo.catalog().embedder();
        if (e == null) {
            embedding.setSummary(R.string.pref_summary_ai_no_model);
            return;
        }
        String size = Formatter.formatShortFileSize(requireContext(), e.sizeBytes);
        embedding.setSummary(repo.isInstalled(e)
                ? e.displayName + " · " + size + " · " + getString(R.string.ai_model_chip_installed)
                : e.displayName + " · " + size + " · " + getString(R.string.pref_summary_ai_no_model));
    }

    private void bindManageSummary(AiModelRepository repo) {
        Preference manage = findPreference(SettingsActivity.PREF_AI_MANAGE_MODELS);
        if (manage == null) {
            return;
        }
        manage.setSummary(getString(R.string.ai_models_storage_summary,
                repo.installedCount(),
                Formatter.formatShortFileSize(requireContext(), repo.installedBytes()),
                Formatter.formatShortFileSize(requireContext(), repo.freeExternalBytes())));
    }

    /** Enrich and learn are T2-only capabilities; on a LIGHT device the rows would be a lie. */
    private void bindTierOnlyRows() {
        boolean full = AiCapability.tier(requireContext()) == AiCapability.Tier.FULL;
        Preference enrich = findPreference(SettingsActivity.CB_AI_ENRICH_ENABLED);
        Preference learn = findPreference(SettingsActivity.CB_AI_LEARN_ENABLED);
        if (enrich != null) {
            enrich.setVisible(full);
        }
        if (learn != null) {
            learn.setVisible(full);
        }
    }
}
