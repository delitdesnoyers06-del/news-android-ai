package de.luhmer.owncloudnewsreader.ai.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import de.luhmer.owncloudnewsreader.NewsDetailActivity;
import de.luhmer.owncloudnewsreader.R;
import de.luhmer.owncloudnewsreader.ai.AiDigests;
import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.AiDigestStore;
import de.luhmer.owncloudnewsreader.helper.ThemeChooser;
import de.luhmer.owncloudnewsreader.widget.WidgetProvider;

/**
 * The digest, full screen: abstract, theme sections, mark-all-read and share.
 *
 * <p>An Activity rather than a Fragment because it is reached from a card, owns a toolbar and a
 * footer, and must survive process death independently of the list fragment's folder state.</p>
 *
 * <h3>The one thing that must be right</h3>
 * {@code NewsDetailActivity} pages over whatever {@code CURRENT_RSS_ITEM_VIEW} holds. Launching it
 * from here without rebuilding that table first opens <i>the article at the same index in the
 * previous list</i> — a different article, with no error anywhere (PLAN R17). So
 * {@link #openArticle} rebuilds the view from {@link AiDigests#currentViewSql} before it starts
 * anything, and passes the article's <b>id</b> rather than its index so that even a reordered view
 * lands on the right article.
 */
public class AiDigestActivity extends AppCompatActivity implements AiDigestAdapter.Listener {

    /** Optional: which digest to show. Absent means the most recent one. */
    public static final String EXTRA_DIGEST_ID = "ai_digest_id";

    private AiDigestAdapter adapter;
    private RecyclerView list;
    private TextView empty;
    private AiDigestStore.Digest digest;
    private List<AiDigests.Entry> entries = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeChooser.chooseTheme(this);
        super.onCreate(savedInstanceState);
        ThemeChooser.afterOnCreate(this);

        setContentView(R.layout.activity_ai_digest);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.ai_digest_title);
        }

        adapter = new AiDigestAdapter(this, getString(R.string.ai_digest_theme_other));
        list = findViewById(R.id.digest_list);
        empty = findViewById(R.id.digest_empty);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.btn_mark_all_read).setOnClickListener(v -> markAllRead());
        findViewById(R.id.btn_share_digest).setOnClickListener(v -> share());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-read on every resume: coming back from an article must grey out the row that was read.
        load();
    }

    private void load() {
        entries = new ArrayList<>();
        digest = null;
        try {
            AiDb db = new DatabaseConnectionOrm(this).aiDb();
            if (db != null) {
                AiDigestStore store = new AiDigestStore(db);
                long id = getIntent().getLongExtra(EXTRA_DIGEST_ID, -1L);
                digest = id > 0 ? store.byId(id) : store.latest();
                if (digest != null) {
                    entries = AiDigests.entries(db, digest.id);
                    if (getSupportActionBar() != null) {
                        getSupportActionBar().setSubtitle(digest.dayKey);
                    }
                }
            }
        } catch (Throwable t) {
            // An unreadable digest renders as an empty one. It is a view over data that still exists.
            entries = new ArrayList<>();
        }

        boolean isEmpty = entries.isEmpty();
        list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        adapter.submit(digest == null ? null : digest.abstractText, entries);
    }

    @Override
    public void onDigestItemClicked(AiDigests.Entry entry, int articleIndex) {
        openArticle(entry);
    }

    private void openArticle(AiDigests.Entry entry) {
        if (entry == null || entry.rssItemId <= 0 || digest == null) {
            return;
        }
        try {
            // MANDATORY, and the single highest-risk line on this screen.
            new DatabaseConnectionOrm(this)
                    .insertIntoRssCurrentViewTable(AiDigests.currentViewSql(digest.id));
            AiDigests.markCurrentViewDirty();
        } catch (Throwable t) {
            Toast.makeText(this, R.string.ai_digest_open_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, NewsDetailActivity.class);
        intent.putExtra(WidgetProvider.RSS_ITEM_ID, entry.rssItemId);
        startActivity(intent);
    }

    /**
     * Reuses the app's own mark-all-read, over a {@code CURRENT_RSS_ITEM_VIEW} rebuilt from this
     * digest. Hand-rolling the loop here would set {@code READ_TEMP} without going through the path
     * the delayed sync watches, and the articles would come back unread on the next server round.
     */
    private void markAllRead() {
        int marked;
        try {
            DatabaseConnectionOrm dbConn = new DatabaseConnectionOrm(this);
            if (digest == null) {
                return;
            }
            dbConn.insertIntoRssCurrentViewTable(AiDigests.currentViewSql(digest.id));
            AiDigests.markCurrentViewDirty();
            marked = dbConn.markAllItemsAsReadForCurrentView();
        } catch (Throwable t) {
            Toast.makeText(this, R.string.ai_digest_open_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this,
                getResources().getQuantityString(R.plurals.ai_digest_marked_read, marked, marked),
                Toast.LENGTH_SHORT).show();
        load();
    }

    private void share() {
        String body = AiDigests.shareMarkdown(digest, entries,
                getString(R.string.ai_digest_theme_other));
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.ai_digest_title)
                + (digest == null ? "" : " · " + digest.dayKey));
        share.putExtra(Intent.EXTRA_TEXT, body);
        startActivity(Intent.createChooser(share, getString(R.string.action_Share)));
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
