package de.luhmer.owncloudnewsreader.ai.ui;

import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.luhmer.owncloudnewsreader.R;
import de.luhmer.owncloudnewsreader.ai.AiDigests;
import de.luhmer.owncloudnewsreader.database.ai.AiDigestStore;

/**
 * The header half of the "For you" list: today's digest card, if there is one.
 *
 * <p>A second adapter behind a {@code ConcatAdapter} rather than a view type inside
 * {@code NewsListRecyclerAdapter} (PLAN D31). That adapter indexes {@code lazyList} by adapter
 * position in four places and removes {@code lazyList.get(prevSize - 1)} on lazy load; every one of
 * them would need an offset, and a missed one is an off-by-one that reads the wrong article. Here
 * the two adapters do not know about each other at all.</p>
 *
 * <p>Written in Java, not Kotlin. The plan permits a Kotlin view holder; it does not require one,
 * and Java keeps this file out of the {@code detekt}/{@code ktlint} gates entirely.</p>
 */
public class AiHeaderAdapter extends RecyclerView.Adapter<AiHeaderAdapter.DigestCardViewHolder> {

    /** Everything the card can do. All of it lands back in the fragment. */
    public interface Listener {
        void onDigestOpen(long digestId);

        void onDigestDismiss(long digestId);

        /** {@code null} clears the filter and rebuilds the full list. */
        void onDigestThemeSelected(String theme);
    }

    private final Listener listener;

    private AiDigestStore.Digest digest;
    private List<AiDigests.Chip> chips = new ArrayList<>();
    private String selectedTheme;

    public AiHeaderAdapter(Listener listener) {
        this.listener = listener;
        setHasStableIds(false);
    }

    /** {@code digest == null} removes the card; there is no empty-card state. */
    public void setDigest(AiDigestStore.Digest digest, List<AiDigests.Chip> chips) {
        this.digest = digest;
        this.chips = chips == null ? new ArrayList<>() : chips;
        notifyDataSetChanged();
    }

    public void clear() {
        setDigest(null, null);
    }

    public boolean hasCard() {
        return digest != null;
    }

    @Override
    public int getItemCount() {
        return digest == null ? 0 : 1;
    }

    @NonNull
    @Override
    public DigestCardViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.ai_digest_card, parent, false);
        return new DigestCardViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull DigestCardViewHolder holder, int position) {
        final AiDigestStore.Digest d = digest;
        if (d == null) {
            return;
        }
        String day = DateFormat.getMediumDateFormat(holder.itemView.getContext())
                .format(new Date(d.rangeTo));
        holder.overline.setText(String.format(Locale.getDefault(), "%s · %s · %s",
                holder.itemView.getContext().getString(R.string.ai_digest_overline), day,
                holder.itemView.getResources().getQuantityString(
                        R.plurals.ai_digest_selected_count, d.itemCount, d.itemCount)));

        boolean pending = AiDigestStore.ABSTRACT_PENDING.equals(d.abstractState);
        boolean hasAbstract = d.abstractText != null && !d.abstractText.trim().isEmpty();
        // Three states, not two: waiting (shimmer), present (text), and dropped-or-never (neither,
        // and the card is still a card — the count and the chips are the digest, the paragraph is
        // garnish).
        holder.abstractText.setVisibility(hasAbstract ? View.VISIBLE : View.GONE);
        holder.abstractText.setText(hasAbstract ? d.abstractText : "");
        holder.placeholder.setVisibility(pending && !hasAbstract ? View.VISIBLE : View.GONE);

        holder.chips.removeAllViews();
        for (AiDigests.Chip c : chips) {
            Chip chip = new Chip(holder.itemView.getContext());
            chip.setText(String.format(Locale.getDefault(), "%s %d", c.theme, c.count));
            chip.setCheckable(true);
            chip.setChecked(c.theme.equals(selectedTheme));
            chip.setOnClickListener(v -> {
                // Tapping the selected chip clears the filter. A chip group with no way back is a
                // list the reader cannot get out of without leaving the folder.
                selectedTheme = c.theme.equals(selectedTheme) ? null : c.theme;
                if (listener != null) {
                    listener.onDigestThemeSelected(selectedTheme);
                }
                notifyDataSetChanged();
            });
            holder.chips.addView(chip);
        }
        holder.chips.setVisibility(chips.isEmpty() ? View.GONE : View.VISIBLE);

        holder.open.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDigestOpen(d.id);
            }
        });
        holder.dismiss.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDigestDismiss(d.id);
            }
        });
    }

    /** The one header view holder. */
    public static class DigestCardViewHolder extends RecyclerView.ViewHolder {
        final TextView overline;
        final TextView abstractText;
        final View placeholder;
        final ChipGroup chips;
        final View open;
        final ImageButton dismiss;

        DigestCardViewHolder(View v) {
            super(v);
            overline = v.findViewById(R.id.digest_overline);
            abstractText = v.findViewById(R.id.digest_abstract);
            placeholder = v.findViewById(R.id.digest_abstract_placeholder);
            chips = v.findViewById(R.id.digest_chips);
            open = v.findViewById(R.id.digest_open);
            dismiss = v.findViewById(R.id.digest_dismiss);
        }
    }
}
