package de.luhmer.owncloudnewsreader.ai.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

import de.luhmer.owncloudnewsreader.R;
import de.luhmer.owncloudnewsreader.SettingsActivity;
import de.luhmer.owncloudnewsreader.ai.AiCapability;
import de.luhmer.owncloudnewsreader.ai.AiFeature;
import de.luhmer.owncloudnewsreader.ai.download.AiModelDownloadEvent;
import de.luhmer.owncloudnewsreader.ai.download.AiModelDownloadService;
import de.luhmer.owncloudnewsreader.ai.download.AiModelDownloader;
import de.luhmer.owncloudnewsreader.ai.download.AiModelRepository;
import de.luhmer.owncloudnewsreader.ai.model.AiCatalogEntry;
import de.luhmer.owncloudnewsreader.helper.ThemeChooser;

/**
 * Download, resume, cancel and delete models.
 *
 * <p>Not a preference screen: determinate progress bars and per-row buttons are not expressible as
 * one. Progress arrives over EventBus — the same mechanism the podcast download already uses — so
 * there is no polling, no LiveData and no new dependency.</p>
 *
 * <p>Deletion is where the worst failure shape of this feature is prevented: removing a model also
 * resets every preference that named it, so no code path can be left holding a path to a file that
 * is gone.</p>
 */
public class AiModelManagerActivity extends AppCompatActivity implements AiModelAdapter.Listener {

    private AiModelRepository repo;
    private AiModelAdapter adapter;
    private TextView storageSummary;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeChooser.chooseTheme(this);
        super.onCreate(savedInstanceState);
        ThemeChooser.afterOnCreate(this);

        setContentView(R.layout.activity_ai_model_manager);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.ai_models_title);
        }

        repo = new AiModelRepository(this);
        adapter = new AiModelAdapter(this, this);
        storageSummary = findViewById(R.id.storage_summary);
        RecyclerView list = findViewById(R.id.model_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
    }

    @Override
    protected void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        refresh();
    }

    @Override
    protected void onStop() {
        EventBus.getDefault().unregister(this);
        super.onStop();
    }

    private void refresh() {
        List<AiModelRepository.Status> statuses = repo.scan();
        AiCatalogEntry recommended =
                repo.catalog().defaultLlmFor(AiCapability.tier(this));
        adapter.submit(statuses, recommended == null ? null : recommended.id);
        storageSummary.setText(getString(R.string.ai_models_storage_summary,
                repo.installedCount(),
                Formatter.formatShortFileSize(this, repo.installedBytes()),
                Formatter.formatShortFileSize(this, repo.freeExternalBytes())));
    }

    // ---- row actions --------------------------------------------------------------------------

    @Override
    public void onDownload(AiCatalogEntry entry) {
        if (entry.gated && !hasToken()) {
            // Regime B (PLAN D22): the anonymous request would come back 401 GatedRepo. Send the
            // user to the model page and to the token field rather than letting them watch a
            // download fail with a number.
            new AlertDialog.Builder(this)
                    .setMessage(R.string.ai_err_gated)
                    .setPositiveButton(R.string.ai_model_open_page, (d, w) -> openPage(entry))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        if (!AiCapability.downloadOk(this, entry.sizeBytes)) {
            Toast.makeText(this, R.string.ai_err_no_space, Toast.LENGTH_LONG).show();
            return;
        }
        AiModelDownloadService.start(this, entry.id);
    }

    @Override
    public void onCancel(AiCatalogEntry entry) {
        AiModelDownloadService.cancel(this);
    }

    @Override
    public void onDelete(AiCatalogEntry entry) {
        new AlertDialog.Builder(this)
                .setMessage(getString(R.string.ai_model_delete_confirm, entry.displayName))
                .setPositiveButton(R.string.ai_model_delete, (d, w) -> {
                    long freed = repo.delete(entry.id);
                    Toast.makeText(this,
                            getString(R.string.ai_model_deleted,
                                    Formatter.formatShortFileSize(this, freed)),
                            Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private boolean hasToken() {
        String token = AiFeature.prefsOf(this).getString(SettingsActivity.EDT_AI_HF_TOKEN, "");
        return token != null && !token.trim().isEmpty();
    }

    private void openPage(AiCatalogEntry entry) {
        String url = entry.licenseUrl != null ? entry.licenseUrl
                : "https://huggingface.co/" + entry.repo;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    // ---- progress -----------------------------------------------------------------------------

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDownloadEvent(AiModelDownloadEvent event) {
        switch (event.phase) {
            case PROGRESS:
            case STARTED:
                adapter.onProgress(event.modelId, event.received, event.total);
                break;
            case INSTALLED:
                adapter.onDownloadEnded(event.modelId);
                refresh();
                break;
            case CANCELLED:
                adapter.onDownloadEnded(event.modelId);
                refresh();
                break;
            case FAILED:
                adapter.onDownloadEnded(event.modelId);
                Toast.makeText(this, messageFor(event.code), Toast.LENGTH_LONG).show();
                refresh();
                break;
            default:
                break;
        }
    }

    private int messageFor(String code) {
        if (AiModelDownloader.CODE_NO_SPACE.equals(code)) {
            return R.string.ai_err_no_space;
        }
        if (AiModelDownloader.CODE_GATED.equals(code)) {
            return R.string.ai_err_gated;
        }
        if (AiModelDownloader.CODE_CHECKSUM.equals(code)
                || AiModelDownloader.CODE_SIZE_MISMATCH.equals(code)) {
            return R.string.ai_err_corrupt;
        }
        if ("smoke".equals(code)) {
            return R.string.ai_err_incompatible;
        }
        return R.string.ai_err_network;
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
