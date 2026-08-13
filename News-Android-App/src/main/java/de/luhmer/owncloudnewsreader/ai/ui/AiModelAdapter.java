package de.luhmer.owncloudnewsreader.ai.ui;

import android.content.Context;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.luhmer.owncloudnewsreader.R;
import de.luhmer.owncloudnewsreader.ai.AiCapability;
import de.luhmer.owncloudnewsreader.ai.download.AiModelRepository;
import de.luhmer.owncloudnewsreader.ai.model.AiCatalogEntry;
import de.luhmer.owncloudnewsreader.database.ai.AiModelRegistry;

/**
 * One row per catalogue entry. Java rather than Kotlin: the "view holders may be Kotlin" carve-out
 * exists for the article list holders that sit next to nine existing {@code .kt} files; this one has
 * no such neighbours and Java keeps it out of detekt's and ktlint's way entirely.
 *
 * <p>A row's <b>state chip is the whole vocabulary</b>: Recommended / Installed / Paused at N% /
 * Needs ~4.5 GB RAM / Needs 1.2 GB more space / Broken / Needs a Hugging Face token. A row that
 * fails the RAM gate is not hidden — it says what it needs and offers the action anyway, because OEM
 * {@code totalMem} reporting is inconsistent enough that a hard lock generates bug reports. A row
 * that fails the <i>ABI</i> gate never appears at all: that one is a physical impossibility.</p>
 */
public class AiModelAdapter extends RecyclerView.Adapter<AiModelAdapter.Holder> {

    /** What the Activity does when a row's button is tapped. */
    public interface Listener {
        void onDownload(AiCatalogEntry entry);

        void onCancel(AiCatalogEntry entry);

        void onDelete(AiCatalogEntry entry);
    }

    private final Context context;
    private final Listener listener;
    private final List<AiModelRepository.Status> rows = new ArrayList<>();
    /** modelId -> live progress, superimposed on the scanned state while a download runs. */
    private final Map<String, long[]> live = new HashMap<>();
    private String activeModelId;
    private String recommendedId;

    public AiModelAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
        setHasStableIds(true);
    }

    public void submit(List<AiModelRepository.Status> statuses, String recommendedId) {
        this.rows.clear();
        this.rows.addAll(statuses);
        this.recommendedId = recommendedId;
        notifyDataSetChanged();
    }

    /** Live progress from the download service's EventBus messages. */
    public void onProgress(String modelId, long received, long total) {
        live.put(modelId, new long[]{received, total});
        activeModelId = modelId;
        int at = indexOf(modelId);
        if (at >= 0) {
            notifyItemChanged(at);
        }
    }

    public void onDownloadEnded(String modelId) {
        live.remove(modelId);
        if (modelId != null && modelId.equals(activeModelId)) {
            activeModelId = null;
        }
    }

    private int indexOf(String modelId) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).entry.id.equals(modelId)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public long getItemId(int position) {
        return rows.get(position).entry.id.hashCode();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.ai_model_row, parent, false);
        return new Holder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        final AiModelRepository.Status s = rows.get(position);
        final AiCatalogEntry e = s.entry;
        h.name.setText(e.displayName);
        String size = Formatter.formatShortFileSize(context, e.sizeBytes);
        final int purposeRes;
        if (e.isLlm()) {
            purposeRes = R.string.ai_model_purpose_triage;
        } else if (e.isTts()) {
            purposeRes = R.string.ai_model_purpose_voice;
        } else {
            purposeRes = R.string.ai_model_purpose_similarity;
        }
        h.purpose.setText(context.getString(purposeRes, size));

        long[] progress = live.get(e.id);
        boolean downloading = progress != null;
        boolean selectOk = AiCapability.selectOk(context, e.minTotalRamBytes);
        boolean spaceOk = AiCapability.downloadOk(context, e.sizeBytes);

        if (downloading) {
            h.progress.setVisibility(View.VISIBLE);
            int pct = progress[1] <= 0 ? 0
                    : (int) Math.min(100L, progress[0] * 100L / progress[1]);
            h.progress.setProgress(pct);
            h.state.setText(context.getString(R.string.ai_model_chip_paused, pct));
            h.action.setText(R.string.ai_model_cancel);
            h.action.setOnClickListener(v -> listener.onCancel(e));
            h.delete.setVisibility(View.GONE);
        } else if (s.installed()) {
            h.progress.setVisibility(View.GONE);
            h.state.setText(R.string.ai_model_chip_installed);
            h.action.setVisibility(View.GONE);
            h.delete.setVisibility(View.VISIBLE);
            h.delete.setOnClickListener(v -> listener.onDelete(e));
        } else {
            h.progress.setVisibility(s.partial() ? View.VISIBLE : View.GONE);
            h.progress.setProgress(s.percent());
            h.state.setText(stateText(s, selectOk, spaceOk));
            h.action.setVisibility(View.VISIBLE);
            h.action.setText(s.partial() ? R.string.ai_model_resume : R.string.ai_model_download);
            h.action.setEnabled(spaceOk);
            h.action.setOnClickListener(v -> listener.onDownload(e));
            h.delete.setVisibility(s.partial() ? View.VISIBLE : View.GONE);
            h.delete.setOnClickListener(v -> listener.onDelete(e));
        }
        if (!downloading && !s.installed()) {
            h.action.setVisibility(View.VISIBLE);
        }
    }

    private String stateText(AiModelRepository.Status s, boolean selectOk, boolean spaceOk) {
        if (AiModelRegistry.STATE_BROKEN.equals(s.state)) {
            return context.getString(R.string.ai_model_chip_broken);
        }
        if (!spaceOk) {
            long missing = Math.max(0L, s.entry.sizeBytes + AiCapability.DOWNLOAD_HEADROOM_BYTES
                    - AiCapability.freeBytes(context.getExternalFilesDir(null)));
            return context.getString(R.string.ai_model_chip_needs_space,
                    Formatter.formatShortFileSize(context, missing));
        }
        if (!selectOk) {
            return context.getString(R.string.ai_model_chip_needs_ram,
                    Formatter.formatShortFileSize(context, s.entry.minTotalRamBytes));
        }
        if (s.entry.gated) {
            return context.getString(R.string.ai_model_chip_gated);
        }
        if (s.entry.id.equals(recommendedId)) {
            return context.getString(R.string.ai_model_chip_recommended);
        }
        return "";
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView purpose;
        final TextView state;
        final ProgressBar progress;
        final Button action;
        final Button delete;

        Holder(View v) {
            super(v);
            name = v.findViewById(R.id.model_name);
            purpose = v.findViewById(R.id.model_purpose);
            state = v.findViewById(R.id.model_state);
            progress = v.findViewById(R.id.model_progress);
            action = v.findViewById(R.id.model_action);
            delete = v.findViewById(R.id.model_delete);
        }
    }
}
