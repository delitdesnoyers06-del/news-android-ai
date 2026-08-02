/*
* Android ownCloud News
*
* @author David Luhmer
* @copyright 2013 David Luhmer david-dev@live.de
*
* This library is free software; you can redistribute it and/or
* modify it under the terms of the GNU AFFERO GENERAL PUBLIC LICENSE
* License as published by the Free Software Foundation; either
* version 3 of the License, or any later version.
*
* This library is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU AFFERO GENERAL PUBLIC LICENSE for more details.
*
* You should have received a copy of the GNU Affero General Public
* License along with this library.  If not, see <http://www.gnu.org/licenses/>.
*
*/

package de.luhmer.owncloudnewsreader;

import static java.util.Objects.requireNonNull;
import static de.luhmer.owncloudnewsreader.ListView.SubscriptionExpandableListAdapter.SPECIAL_FOLDERS.AI_FOR_YOU;
import static de.luhmer.owncloudnewsreader.ListView.SubscriptionExpandableListAdapter.SPECIAL_FOLDERS.ALL_DOWNLOADED_PODCASTS;
import static de.luhmer.owncloudnewsreader.ListView.SubscriptionExpandableListAdapter.SPECIAL_FOLDERS.ALL_STARRED_ITEMS;
import static de.luhmer.owncloudnewsreader.ListView.SubscriptionExpandableListAdapter.SPECIAL_FOLDERS.ALL_UNREAD_ITEMS;
import static de.luhmer.owncloudnewsreader.SettingsActivity.SP_SWIPE_LEFT_ACTION;
import static de.luhmer.owncloudnewsreader.SettingsActivity.SP_SWIPE_LEFT_ACTION_DEFAULT;
import static de.luhmer.owncloudnewsreader.SettingsActivity.SP_SWIPE_RIGHT_ACTION;
import static de.luhmer.owncloudnewsreader.SettingsActivity.SP_SWIPE_RIGHT_ACTION_DEFAULT;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;

import java.util.List;
import java.util.stream.Collectors;

import javax.inject.Inject;

import de.luhmer.owncloudnewsreader.adapter.NewsListRecyclerAdapter;
import de.luhmer.owncloudnewsreader.adapter.RssItemViewHolder;
import de.luhmer.owncloudnewsreader.ai.AiDecisions;
import de.luhmer.owncloudnewsreader.ai.AiDigests;
import de.luhmer.owncloudnewsreader.ai.AiFeature;
import de.luhmer.owncloudnewsreader.ai.ui.AiDigestActivity;
import de.luhmer.owncloudnewsreader.ai.ui.AiHeaderAdapter;
import de.luhmer.owncloudnewsreader.ai.work.AiLazyScheduler;
import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm;
import de.luhmer.owncloudnewsreader.database.ai.AiDb;
import de.luhmer.owncloudnewsreader.database.ai.AiDigestStore;
import de.luhmer.owncloudnewsreader.database.DatabaseConnectionOrm.SORT_DIRECTION;
import de.luhmer.owncloudnewsreader.database.model.RssItem;
import de.luhmer.owncloudnewsreader.database.model.RssItemDao;
import de.luhmer.owncloudnewsreader.databinding.FragmentNewsreaderDetailBinding;
import de.luhmer.owncloudnewsreader.helper.AsyncTaskHelper;
import de.luhmer.owncloudnewsreader.helper.DatabaseUtilsKt;
import de.luhmer.owncloudnewsreader.helper.PostDelayHandler;
import de.luhmer.owncloudnewsreader.helper.Search;
import de.luhmer.owncloudnewsreader.helper.StopWatch;
import io.reactivex.rxjava3.observers.DisposableObserver;
import io.reactivex.rxjava3.subjects.PublishSubject;

/**
 * A fragment representing a single NewsReader detail screen. This fragment is
 * either contained in a {@link NewsReaderListActivity} in two-pane mode (on
 * tablets) or a {@link NewsReaderListActivity} on handsets.
 */
public class NewsReaderDetailFragment extends Fragment {

    private static final String LAYOUT_MANAGER_STATE = "LAYOUT_MANAGER_STATE";

    /**
     * The two new values of {@code sp_swipe_*_action}. They are opt-in globally and <b>forced</b>
     * inside the For-You folder, which exists to produce decisions (PLAN D5).
     */
    private static final String SWIPE_AI_MORE = "4";
    private static final String SWIPE_AI_LESS = "5";

    protected final String TAG = getClass().getCanonicalName();

    FragmentNewsreaderDetailBinding binding;

    private Long idFeed;
    private Drawable leftSwipeDrawable;
    private Drawable rightSwipeDrawable;
    private String prevLeftAction = "";
    private String prevRightAction = "";
    private Parcelable layoutManagerSavedState;

    // Variables related to mark as read when scrolling
    private boolean mMarkAsReadWhileScrollingEnabled;
    private boolean mSyncWhenScrolledToBottomEnabled;
    private int previousFirstVisibleItem = -1;

    private Long idFolder;

    /**
     * The article adapter, held as a field rather than fished back out of
     * {@code binding.list.getAdapter()}: once a ConcatAdapter wraps it, that cast is a
     * ClassCastException (PLAN D31). Cleared in {@link #onDestroyView()} with the binding.
     */
    private NewsListRecyclerAdapter newsAdapter;
    private AiHeaderAdapter aiHeaderAdapter;
    /** The theme chip currently selected on the digest card. {@code null} = no filter. */
    private String aiThemeFilter;

    private String title;
    private int onResumeCount = 0;
    private RecyclerView.OnItemTouchListener itemTouchListener;

    protected @Inject SharedPreferences mPrefs;
    protected @Inject PostDelayHandler mPostDelayHandler;

    public PublishSubject<Boolean> syncTrigger = PublishSubject.create();

    private PodcastFragmentActivity mActivity;

    /**
     * Mandatory empty constructor for the fragment manager to instantiate the
     * fragment (e.g. upon screen orientation changes).
     */
    public NewsReaderDetailFragment() {
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        this.mActivity = (PodcastFragmentActivity) context;
    }

    @Override
    public void onDetach() {
        this.mActivity = null;
        super.onDetach();
    }

    protected DisposableObserver<List<RssItem>> searchResultObserver = new DisposableObserver<List<RssItem>>() {
        @Override
        public void onNext(@NonNull List<RssItem> rssItems) {
            loadRssItemsIntoView(rssItems);
        }

        @Override
        public void onError(Throwable e) {
            binding.pbLoading.setVisibility(View.GONE);
            Toast.makeText(mActivity, e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
        }

        @Override
        public void onComplete() {
            Log.v(TAG, "Search Completed!");
        }
    };


    public static SORT_DIRECTION getSortDirection(SharedPreferences prefs) {
        return DatabaseUtilsKt.getSortDirectionFromSettings(prefs);
    }

    /**
     * @return the idFeed
     */
    public Long getIdFeed() {
        return idFeed;
    }

    /**
     * @return the idFolder
     */
    public Long getIdFolder() {
        return idFolder;
    }

    /**
     * @return the titel
     */
    public String getTitle() {
        return title;
    }

    protected void setTitle(String title) {
        this.title = title;
        requireNonNull(mActivity.getSupportActionBar()).setTitle(title);
    }

    protected void setData(Long idFeed, Long idFolder, String title, boolean updateListView) {
        Log.v(TAG, "Creating new instance");

        this.idFeed = idFeed;
        this.idFolder = idFolder;
        // A theme chip belongs to one digest in one folder. Carrying it across would filter an
        // unrelated list by a slug that means nothing there.
        this.aiThemeFilter = null;
        setTitle(title);

        // The For-You folder overrides the global swipe prefs, so the drawables have to follow the
        // folder and not only onResume(). Without this the user sees a star while they are telling
        // the taste model "less like this".
        updateSwipeDrawables(true);

        if (updateListView) {
            updateCurrentRssView();
        } else {
            refreshCurrentRssView();
        }
    }

    @Override
    public void onResume() {
        Log.v(TAG, "onResume called!");

        mMarkAsReadWhileScrollingEnabled = mPrefs.getBoolean(SettingsActivity.CB_MARK_AS_READ_WHILE_SCROLLING_STRING, false);
        mSyncWhenScrolledToBottomEnabled = mPrefs.getBoolean(SettingsActivity.CB_SYNC_WHEN_SCROLLED_TO_BOTTOM_STRING, false);
        this.initFastDoneAll(this.requireView());

        // The digest screen overwrites CURRENT_RSS_ITEM_VIEW with its own article list so the pager
        // opens the right article. Coming back here without rebuilding would page the digest's
        // articles into this list.
        if (AiDigests.consumeCurrentViewDirty()) {
            updateCurrentRssView();
        } else if (onResumeCount >= 2) {
            //When the fragment is instantiated by the xml file, onResume will be called twice
            refreshCurrentRssView();
        }
        onResumeCount++;

        updateSwipeDrawables(false);

        super.onResume();
    }

    protected void updateMenuItemsState() {
        NewsReaderListActivity nla = (NewsReaderListActivity) mActivity;
        if(nla != null && nla.getMenuItemDownloadMoreItems() != null) {
            // "Download more items" is meaningless in the AI folder: it is a ranked view over
            // what is already cached, not a per-feed window into the server.
            nla.getMenuItemDownloadMoreItems().setEnabled(idFolder == null
                    || (idFolder != ALL_UNREAD_ITEMS.getValue() && idFolder != AI_FOR_YOU.getValue()));
        }
    }

    protected void notifyDataSetChangedOnAdapter() {
        // The list adapter may be a ConcatAdapter (AI header + articles), so notify whatever is
        // actually attached rather than casting to the article adapter.
        RecyclerView.Adapter<?> attached = binding.list.getAdapter();
        if (attached != null) {
            attached.notifyDataSetChanged();
        }
    }

    /**
     * Refreshes the current RSS-View
     */
    protected void refreshCurrentRssView() {
        Log.v(TAG, "refreshCurrentRssView");
        NewsListRecyclerAdapter nra = newsAdapter;

        if (nra != null) {
            nra.refreshAdapterDataAsync(() -> {
                binding.pbLoading.setVisibility(View.GONE);

                if (layoutManagerSavedState != null) {
                    requireNonNull(binding.list.getLayoutManager()).onRestoreInstanceState(layoutManagerSavedState);
                    layoutManagerSavedState = null;
                }
            });
        }
    }

    /**
     * Init fast action for mark all as read shown as floating action bar button (fab)
     *
     * @param rootView root view of fragment
     */
    protected void initFastDoneAll(View rootView) {
        FloatingActionButton fab_done_all = binding.fabDoneAll;
        if (mPrefs.getBoolean(SettingsActivity.CB_SHOW_FAST_ACTIONS, true)) {
            fab_done_all.setVisibility(View.VISIBLE);
            fab_done_all.setOnTouchListener(new FastMarkReadMotionListener(rootView));
        } else {
            fab_done_all.setVisibility(View.GONE);
        }
    }

    /**
     * Updates the current RSS-View
     */
    public void updateCurrentRssView() {
        Log.v(TAG, "updateCurrentRssView");
        AsyncTaskHelper.StartAsyncTask(new UpdateCurrentRssViewTask());
    }

    public RecyclerView getRecyclerView() {
        return binding.list;
    }

    /**
     * The article adapter. Callers used to cast {@code getRecyclerView().getAdapter()} to this type;
     * in the "For you" folder that object is a {@link ConcatAdapter} and the cast throws.
     *
     * @return {@code null} before the first load completes
     */
    public NewsListRecyclerAdapter getNewsAdapter() {
        return newsAdapter;
    }

    public LinearLayoutManager getLayoutManager() {
        return (LinearLayoutManager) binding.list.getLayoutManager();
    }

    protected List<RssItem> performSearch(String searchString) {
        Handler mainHandler = new Handler(mActivity.getMainLooper());

        Runnable myRunnable = () -> {
            binding.pbLoading.setVisibility(View.VISIBLE);
            binding.tvNoItemsAvailable.getRoot().setVisibility(View.GONE);
        };
        mainHandler.post(myRunnable);

        return Search.PerformSearch(mActivity, idFolder, idFeed, searchString, mPrefs);
    }

    void loadRssItemsIntoView(List<RssItem> rssItems) {
        previousFirstVisibleItem = -1;
        try {
            // Rebuild when the wiring has to change, not only when there is no adapter at all:
            // switching between "For you" and any other folder switches between a ConcatAdapter and
            // a bare one. A ConcatAdapter keeps an observer registered on every child it was given,
            // so the article adapter is rebuilt with it rather than being handed to a second parent.
            boolean wantsHeader = isAiFolder();
            if (newsAdapter == null || wantsHeader != (aiHeaderAdapter != null)) {
                newsAdapter = new NewsListRecyclerAdapter(mActivity, binding.list, mActivity, mPostDelayHandler, mPrefs);
                if (wantsHeader) {
                    // ConcatAdapter, not a view type at position 0 (PLAN D31): NewsListRecyclerAdapter
                    // indexes lazyList by adapter position in four places.
                    aiHeaderAdapter = new AiHeaderAdapter(new DigestCardListener());
                    binding.list.setAdapter(new ConcatAdapter(aiHeaderAdapter, newsAdapter));
                } else {
                    aiHeaderAdapter = null;
                    binding.list.setAdapter(newsAdapter);
                }
            }
            NewsListRecyclerAdapter nra = newsAdapter;
            nra.updateAdapterData(rssItems);

            binding.pbLoading.setVisibility(View.GONE);
            if (nra.getItemCount() <= 0) {
                binding.tvNoItemsAvailable.getRoot().setVisibility(View.VISIBLE);
            } else {
                binding.tvNoItemsAvailable.getRoot().setVisibility(View.GONE);
            }

            binding.list.scrollToPosition(0);

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        binding = FragmentNewsreaderDetailBinding.inflate(inflater, container, false);
        // The view is new, so the adapters attached to the previous one are gone with it. Holding a
        // stale field here would silently update a RecyclerView nobody is looking at.
        newsAdapter = null;
        aiHeaderAdapter = null;

        binding.list.setHasFixedSize(true);
        binding.list.setLayoutManager(new LazyLoadingLinearLayoutManager(mActivity, RecyclerView.VERTICAL, false));
        binding.list.setItemAnimator(new DefaultItemAnimator());

        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(new NewsReaderItemTouchHelperCallback());
        itemTouchHelper.attachToRecyclerView(binding.list);
        //recyclerView.addItemDecoration(new DividerItemDecoration(mActivity)); // Enable divider line

        /*
        recyclerView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                ((NewsReaderListActivity) mActivity).clearSearchViewFocus();
                return false;
            }
        });
        */

        binding.swipeRefresh.setOnRefreshListener((SwipeRefreshLayout.OnRefreshListener) mActivity);

        binding.list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (dy > 0) { // check for scroll down
                    Log.v(TAG, "Scroll Delta y: " + dy);

                    LinearLayoutManager linearLayoutManager = (LinearLayoutManager) binding.list.getLayoutManager();
                    NewsListRecyclerAdapter adapter = newsAdapter;
                    RecyclerView.Adapter<?> attached = binding.list.getAdapter();

                    if (linearLayoutManager != null && adapter != null && attached != null) {
                        int firstVisibleItem = linearLayoutManager.findFirstVisibleItemPosition();
                        int lastVisibleItem = linearLayoutManager.findLastVisibleItemPosition();
                        int visibleItemCount = lastVisibleItem - firstVisibleItem;
                        // Layout positions are ConcatAdapter positions, so the total must be too, or
                        // "reached the bottom" fires one row early for the whole AI folder.
                        int totalItemCount = attached.getItemCount();
                        boolean reachedBottom = (lastVisibleItem == (totalItemCount - 1));

                        if (mMarkAsReadWhileScrollingEnabled) {
                            handleMarkAsReadScrollEvent(firstVisibleItem, lastVisibleItem, visibleItemCount, reachedBottom, adapter);
                        }

                        // trigger sync (to automatically reload) once we reach the end/bottom
                        int lastCompletelyVisibleItem = linearLayoutManager.findLastCompletelyVisibleItemPosition();
                        boolean reachedBottomFully = (lastCompletelyVisibleItem == (totalItemCount - 1));
                        if (mSyncWhenScrolledToBottomEnabled && reachedBottomFully) {
                            Log.d(TAG, "Reached end of list - trigger sync");
                            syncTrigger.onNext(true);
                        }
                    }
                }
            }
        });

        itemTouchListener = new RecyclerView.OnItemTouchListener() {
            final GestureDetector detector = new GestureDetector(mActivity, new RecyclerViewOnGestureListener());

            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                detector.onTouchEvent(e);
                return false;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
            }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallowIntercept) {
            }
        };

        return binding.getRoot();
    }

    private void handleMarkAsReadScrollEvent(int firstVisibleItem, int lastVisibleItem, int visibleItemCount, boolean reachedBottom, NewsListRecyclerAdapter adapter) {
        // Exit if the position didn't change.
        if (firstVisibleItem == previousFirstVisibleItem && !reachedBottom) {
            return;
        }
        previousFirstVisibleItem = firstVisibleItem;

        //Log.v(TAG, "First visible: " + firstVisibleItem + " - Last visible: " + lastVisibleItem + " - visible count: " + visibleItemCount + " - total count: " + totalItemCount);

        //Set the item at top to read
        //ViewHolder vh = (ViewHolder) recyclerView.findViewHolderForLayoutPosition(firstVisibleItem);

        // Mark the first two items as read
        final int numberItemsAhead = 1;
        for (int i = firstVisibleItem; i < firstVisibleItem + numberItemsAhead; i++) {
            //Log.v(TAG, "Mark item as read: " + i);

            // instanceof, NOT a cast: the position may hold a ProgressViewHolder (or, once the AI
            // header lands, a non-article holder). The unchecked cast that used to be here was a
            // ClassCastException waiting for the first list that is not all RssItemViewHolders.
            // Compare the correct shape a few lines below.
            RecyclerView.ViewHolder vhTop = binding.list.findViewHolderForLayoutPosition(i);
            if (vhTop instanceof RssItemViewHolder vh && !vh.shouldStayUnread()) {
                adapter.changeReadStateOfItem(vh, true);
            }
        }

        //Check if Listview is scrolled to bottom
        if (reachedBottom && visibleItemCount != 0 && //Check if list is empty
                binding.list.getChildAt(visibleItemCount).getBottom() <= binding.list.getHeight()) {

            for (int i = firstVisibleItem; i <= lastVisibleItem; i++) {
                RecyclerView.ViewHolder vhTemp = binding.list.findViewHolderForLayoutPosition(i);

                if (vhTemp instanceof RssItemViewHolder vh) { //Check for ViewHolder instance because of ProgressViewHolder

                    if (!vh.shouldStayUnread()) {
                        adapter.changeReadStateOfItem(vh, true);
                    } else {
                        Log.v(TAG, "shouldStayUnread");
                    }
                }
            }
        }
    }

    @Override
    public void onInflate(@NonNull Context context, @NonNull AttributeSet attrs, Bundle savedInstanceState) {
        super.onInflate(context, attrs, savedInstanceState);

        ((NewsReaderApplication) requireActivity().getApplication()).getAppComponent().injectFragment(this);

        updateSwipeDrawables(true);
    }

    /**
     *
     * @param forceUpdate force swipe drawables to be reloaded
     */
    private void updateSwipeDrawables(boolean forceUpdate) {
        boolean ai = isAiFolder();
        String leftAction  = ai ? SWIPE_AI_LESS
                : mPrefs.getString(SP_SWIPE_LEFT_ACTION, SP_SWIPE_LEFT_ACTION_DEFAULT);
        String rightAction = ai ? SWIPE_AI_MORE
                : mPrefs.getString(SP_SWIPE_RIGHT_ACTION, SP_SWIPE_RIGHT_ACTION_DEFAULT);

        if (!forceUpdate && leftAction.equals(prevLeftAction) && rightAction.equals(prevRightAction)) {
            return;
        }

        prevLeftAction  = leftAction;
        prevRightAction = rightAction;
        int leftId  = getLayoutId(leftAction);
        int rightId = getLayoutId(rightAction);

        TypedArray styledAttributes = requireContext().obtainStyledAttributes(new int[]{leftId, rightId});
        leftSwipeDrawable = styledAttributes.getDrawable(0);
        rightSwipeDrawable = styledAttributes.getDrawable(1);
        styledAttributes.recycle();
    }

    /** True while the "For you" virtual folder is on screen. */
    private boolean isAiFolder() {
        return idFolder != null && idFolder == AI_FOR_YOU.getValue();
    }

    /**
     * Records a taste decision from a swipe and offers the mandatory undo (PLAN D5).
     *
     * <p>The row leaves the list immediately - the user just said they do not want to see it - and
     * the Snackbar gives them 5 s to take it back. <b>Undo appends an {@code undo} decision row, it
     * never deletes the {@code keep}/{@code reject} one</b>: the disagreement history is the input
     * to the learn loop and is never erased (PLAN invariant 6).</p>
     *
     * <p>{@code record()} returning false (illegal transition, AI storage unavailable) is silent by
     * design; the row still leaves the list, because the list is a view and the decision store is
     * the truth, and they are allowed to disagree for one refresh.</p>
     */
    private void applyAiDecision(RssItemViewHolder vh, boolean positive) {
        final RssItem item = vh.getRssItem();
        final int pos = vh.getBindingAdapterPosition();
        // getBindingAdapterPosition(), not getAbsoluteAdapterPosition(): with the digest card at
        // the top these differ by one, and removeItemAt() indexes the article adapter.
        final NewsListRecyclerAdapter adapter = newsAdapter;
        if (item == null || adapter == null || pos == RecyclerView.NO_POSITION) {
            return;
        }

        AiDecisions.record(requireContext(), item,
                positive ? AiDecisions.KEEP : AiDecisions.REJECT, AiDecisions.SOURCE_SWIPE);
        adapter.removeItemAt(pos);

        Snackbar.make(binding.getRoot(),
                        getString(positive ? R.string.ai_snack_more_like_this
                                : R.string.ai_snack_less_like_this),
                        BaseTransientBottomBar.LENGTH_LONG)
                .setAnchorView(binding.fabDoneAll.getVisibility() == View.VISIBLE
                        ? binding.fabDoneAll : null)
                .setAction(R.string.ai_snack_undo, v -> {
                    AiDecisions.record(requireContext(), item, AiDecisions.UNDO,
                            AiDecisions.SOURCE_SWIPE);
                    adapter.restoreItemAt(pos, item);
                    binding.list.scrollToPosition(pos);
                })
                .show();
    }

    // ------------------------------------------------------------------ the digest card

    /** Filled on the background thread by {@link UpdateCurrentRssViewTask}, read on the UI thread. */
    private AiHeaderData pendingAiHeader;

    private static final class AiHeaderData {
        AiDigestStore.Digest digest;
        List<AiDigests.Chip> chips;
        boolean abstractPending;
    }

    /**
     * Builds today's digest if a new day has started and enough articles have been selected since
     * the last one, then reads back what the card needs. All SQL, no inference — the card must never
     * wait on a model (product §3).
     */
    private AiHeaderData loadAiHeader(DatabaseConnectionOrm dbConn) {
        try {
            if (!AiFeature.isEnabled(requireContext(), mPrefs)) {
                return null;
            }
            AiDb db = dbConn.aiDb();
            if (db == null) {
                return null;
            }
            AiDigestStore.Digest digest = AiDigests.ensureToday(db, System.currentTimeMillis());
            if (digest == null || AiDigests.isDismissed(db, digest.dayKey)) {
                return null;
            }
            AiHeaderData data = new AiHeaderData();
            data.digest = digest;
            data.chips = AiDigests.chips(db, digest.id, 3);
            data.abstractPending =
                    AiDigestStore.ABSTRACT_PENDING.equals(digest.abstractState);
            return data;
        } catch (Throwable t) {
            // No card is a perfectly good outcome. It is never worth an empty folder.
            Log.w(TAG, "could not prepare the digest card", t);
            return null;
        }
    }

    private void applyAiHeader() {
        if (aiHeaderAdapter == null) {
            return;
        }
        AiHeaderData data = pendingAiHeader;
        if (data == null) {
            aiHeaderAdapter.clear();
            return;
        }
        aiHeaderAdapter.setDigest(data.digest, data.chips);
        if (data.abstractPending) {
            // Lazy on open, exactly once: ExistingWorkPolicy.KEEP makes four opens one pass.
            AiLazyScheduler.enqueueDigest(requireContext().getApplicationContext());
        }
    }

    /** The card's three actions. */
    private class DigestCardListener implements AiHeaderAdapter.Listener {
        @Override
        public void onDigestOpen(long digestId) {
            Intent intent = new Intent(requireContext(), AiDigestActivity.class);
            intent.putExtra(AiDigestActivity.EXTRA_DIGEST_ID, digestId);
            startActivity(intent);
        }

        @Override
        public void onDigestDismiss(long digestId) {
            try {
                DatabaseConnectionOrm dbConn = new DatabaseConnectionOrm(requireContext());
                AiDb db = dbConn.aiDb();
                if (db != null && pendingAiHeader != null) {
                    AiDigests.dismiss(db, pendingAiHeader.digest, System.currentTimeMillis());
                }
            } catch (Throwable t) {
                Log.w(TAG, "could not dismiss the digest card", t);
            }
            pendingAiHeader = null;
            aiThemeFilter = null;
            aiHeaderAdapter.clear();
            updateCurrentRssView();
        }

        @Override
        public void onDigestThemeSelected(String theme) {
            aiThemeFilter = theme;
            updateCurrentRssView();
        }
    }

    private int getLayoutId(String action) {
        switch (action) {
            case "0": return R.attr.openinbrowserDrawable;
            case "1": return R.attr.starredDrawable;
            case "2": return R.attr.markasreadDrawable;
            case "3": return R.attr.shareDrawable;
            case SWIPE_AI_MORE: return R.attr.aiMoreDrawable;
            case SWIPE_AI_LESS: return R.attr.aiLessDrawable;
            default:
                Log.e(TAG, "Invalid option saved to prefs. This should not happen");
                return Integer.MAX_VALUE;
        }
    }

    @Override
    public void onViewStateRestored(Bundle savedInstanceState) {
        if (savedInstanceState != null)
            layoutManagerSavedState = savedInstanceState.getParcelable(LAYOUT_MANAGER_STATE);
        super.onViewStateRestored(savedInstanceState);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putParcelable(LAYOUT_MANAGER_STATE, getLayoutManager().onSaveInstanceState());
    }

    public int getFirstVisibleScrollPosition() {
        LinearLayoutManager layoutManager = ((LinearLayoutManager) binding.list.getLayoutManager());
        return layoutManager.findFirstVisibleItemPosition();
    }

    private class UpdateCurrentRssViewTask extends AsyncTask<Void, Void, List<RssItem>> {

        @Override
        protected void onPreExecute() {
            binding.pbLoading.setVisibility(View.VISIBLE);
            binding.tvNoItemsAvailable.getRoot().setVisibility(View.GONE);
            super.onPreExecute();
        }

        @Override
        protected List<RssItem> doInBackground(Void... voids) {
            DatabaseConnectionOrm dbConn = new DatabaseConnectionOrm(NewsReaderDetailFragment.this.getContext());
            SORT_DIRECTION sortDirection = getSortDirection(mPrefs);
            boolean onlyUnreadItems = mPrefs.getBoolean(SettingsActivity.CB_SHOWONLYUNREAD_STRING, false);
            boolean onlyStarredItems = idFolder != null && idFolder == ALL_STARRED_ITEMS.getValue();

            String sqlSelectStatement = null;
            if (idFeed != null) {
                if (idFolder != null && idFolder == ALL_UNREAD_ITEMS.getValue()) {
                    onlyUnreadItems = true;
                }
                sqlSelectStatement = dbConn.getAllItemsIdsForFeedSQL(idFeed, onlyUnreadItems, onlyStarredItems, sortDirection);
            } else if (idFolder != null) {
                if (idFolder == ALL_STARRED_ITEMS.getValue() || idFolder == ALL_DOWNLOADED_PODCASTS.getValue())
                    onlyUnreadItems = false;
                // No AI_FOR_YOU branch on purpose: onlyUnreadItems is honoured there like anywhere
                // else, and sortDirection is dropped inside getAllItemsIdsForFolderSQL() because
                // "For you" is a relevance list, not a timeline (PLAN D4). Do not "wire the sort
                // pref for consistency" here.
                sqlSelectStatement = dbConn.getAllItemsIdsForFolderSQL(idFolder, onlyUnreadItems, sortDirection, mActivity);
                if (isAiFolder() && aiThemeFilter != null) {
                    // A list rebuild, not an in-memory filter: paging, mark-as-read-while-scrolling
                    // and the swipe positions all read CURRENT_RSS_ITEM_VIEW, so filtering anywhere
                    // else would leave them describing a list that is no longer on screen.
                    int orderBy = sqlSelectStatement.indexOf("ORDER BY");
                    String clause = AiDigests.themeFilterClause(aiThemeFilter);
                    sqlSelectStatement = orderBy < 0
                            ? sqlSelectStatement + clause
                            : new StringBuilder(sqlSelectStatement).insert(orderBy, clause).toString();
                }
            }
            if (isAiFolder()) {
                pendingAiHeader = loadAiHeader(dbConn);
            } else {
                pendingAiHeader = null;
            }
            if (sqlSelectStatement != null) {
                int index = sqlSelectStatement.indexOf("ORDER BY");
                if (index == -1) {
                    index = sqlSelectStatement.length();
                }
                sqlSelectStatement = new StringBuilder(sqlSelectStatement).insert(index, " GROUP BY " + RssItemDao.Properties.Fingerprint.columnName + " ").toString();
                dbConn.insertIntoRssCurrentViewTable(sqlSelectStatement);
            }

            StopWatch sw = new StopWatch();
            sw.start();

            List<RssItem> items = dbConn.getCurrentRssItemView(0);

            if (idFolder == ALL_DOWNLOADED_PODCASTS.getValue()) {
                items = items.stream().filter((rss) -> {
                    var podcast = DatabaseConnectionOrm.ParsePodcastItemFromRssItem(mActivity, rss);
                    return podcast.offlineCached;
                }).collect(Collectors.toList());
            }

            sw.stop();
            Log.v(TAG, "Time needed (init loading): " + sw);

            return items;
        }

        @Override
        protected void onPostExecute(List<RssItem> rssItem) {
            loadRssItemsIntoView(rssItem);
            applyAiHeader();

            if (rssItem.size() < 10) { // Less than 10 items in the list (usually 3-5 items fit on one screen)
                // There is no API to check, if this listener has already been added. We don't want to
                // add it multiple times, so we take the safe route here by removing it before adding it.
                binding.list.removeOnItemTouchListener(itemTouchListener);
                binding.list.addOnItemTouchListener(itemTouchListener);
            } else {
                binding.list.removeOnItemTouchListener(itemTouchListener);
            }
        }
    }

    // This Gesture listener is only attached when there are few articles on the screen (e.g. less than 10)
    // because the list onScroll callback won't be triggered when all items fit on the screen. Therefore
    // we use this gesture listener to detect swipes on the screen
    private class RecyclerViewOnGestureListener extends GestureDetector.SimpleOnGestureListener {

        private int minLeftEdgeDistance = -1;

        private void initEdgeDistance() {
            if (getResources().getBoolean(R.bool.isTablet)) {
                // if tablet mode enabled, the navigation drawer will always be visible.
                // Therefore we don't need no offset here
                minLeftEdgeDistance = 0;
            } else {
                // otherwise, have left-edge offset to avoid mark-read gesture when user is pulling to open drawer
                minLeftEdgeDistance = ((NewsReaderListActivity) mActivity).getEdgeSizeOfDrawer();
            }
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            if (minLeftEdgeDistance == -1) { // if not initialized
                initEdgeDistance();
            }

            if (e1 == null) {
                Log.e(TAG, "motion event 1 is null");
                return false;
            }
            if (e2 == null) {
                Log.e(TAG, "motion event 2 is null");
                return false;
            }


            LinearLayoutManager linearLayoutManager = (LinearLayoutManager) binding.list.getLayoutManager();
            NewsListRecyclerAdapter adapter = newsAdapter;
            RecyclerView.Adapter<?> attached = binding.list.getAdapter();

            if (linearLayoutManager == null || adapter == null || attached == null) {
                return false;
            }

            int firstVisibleItem = linearLayoutManager.findFirstVisibleItemPosition();
            int lastVisibleItem = linearLayoutManager.findLastVisibleItemPosition();
            int visibleItemCount = lastVisibleItem - firstVisibleItem;
            int totalItemCount = attached.getItemCount();
            boolean reachedBottom = (lastVisibleItem == (totalItemCount - 1));

            if (mMarkAsReadWhileScrollingEnabled &&
                    e1.getX() > minLeftEdgeDistance &&   // only if gesture starts a bit away from left window edge
                    (e2.getY() - e1.getY()) < 0) {       // and if swipe direction is upwards
                handleMarkAsReadScrollEvent(firstVisibleItem, lastVisibleItem, visibleItemCount, reachedBottom, adapter);
                return true;
            }
            return false;
        }
    }

    // TODO: somehow always cancel item out animation
    private class NewsReaderItemTouchHelperCallback extends ItemTouchHelper.SimpleCallback {
        public NewsReaderItemTouchHelperCallback() {
            super(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT);
        }

        @Override
        public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder viewHolder) {
            return 0.25f;
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
            return false;
        }

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView,
                                    @NonNull RecyclerView.ViewHolder viewHolder) {
            // Header rows (status strip, digest card) are not articles: they must not be swipeable
            // at all, or onSwiped() below reaches a cast it cannot satisfy.
            return (viewHolder instanceof RssItemViewHolder)
                    ? super.getMovementFlags(recyclerView, viewHolder) : 0;
        }

        @Override
        public void onSwiped(@NonNull final RecyclerView.ViewHolder viewHolder, final int direction) {
            if (!(viewHolder instanceof RssItemViewHolder)) {
                return;   // belt and braces: getMovementFlags() already blocked this
            }
            final NewsListRecyclerAdapter adapter = newsAdapter;

            String swipeAction;
            if (isAiFolder()) {
                // The For-You list exists to produce decisions; the global swipe prefs do not apply.
                swipeAction = (direction == ItemTouchHelper.LEFT) ? SWIPE_AI_LESS : SWIPE_AI_MORE;
            } else if (direction == ItemTouchHelper.LEFT) {
                swipeAction = mPrefs.getString(SP_SWIPE_LEFT_ACTION, SP_SWIPE_LEFT_ACTION_DEFAULT);
            } else {
                swipeAction = mPrefs.getString(SP_SWIPE_RIGHT_ACTION, SP_SWIPE_RIGHT_ACTION_DEFAULT);
            }
            switch (swipeAction) {
                case "0": // Open link in browser and mark as read
                    String currentUrl = ((RssItemViewHolder) viewHolder).getRssItem().getLink();
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl));
                    startActivity(browserIntent);
                    adapter.changeReadStateOfItem((RssItemViewHolder) viewHolder, true);
                    break;
                case "1": // Star
                    adapter.toggleStarredStateOfItem((RssItemViewHolder) viewHolder);
                    break;
                case "2": // Read
                    adapter.toggleReadStateOfItem((RssItemViewHolder) viewHolder);
                    break;
                case "3": // Share
                    RssItem rssItem = ((RssItemViewHolder) viewHolder).getRssItem();
                    String title = rssItem.getTitle();
                    String content = rssItem.getLink();

                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("text/plain");
                    share.putExtra(Intent.EXTRA_SUBJECT, title);
                    share.putExtra(Intent.EXTRA_TEXT, content);
                    startActivity(Intent.createChooser(share, "Share Item"));
                    break;
                case SWIPE_AI_MORE:
                case SWIPE_AI_LESS:
                    // NOT break: applyAiDecision owns the row removal and the undo path, and the
                    // removeView() hack below would fight the notifyItemRemoved() animation.
                    applyAiDecision((RssItemViewHolder) viewHolder, SWIPE_AI_MORE.equals(swipeAction));
                    return;
                default:
                    Log.e(TAG, "Swipe preferences has an invalid value");
                    break;
            }
            // Hack to reset view, see https://code.google.com/p/android/issues/detail?id=175798
            binding.list.removeView(viewHolder.itemView);
        }

        @Override
        public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            // binding.swipeRefresh cancels swiping left/right when accidentally moving in the y direction;
            binding.swipeRefresh.setEnabled(!isCurrentlyActive);
            if (isCurrentlyActive) {
                Rect viewRect = new Rect();
                viewHolder.itemView.getDrawingRect(viewRect);
                float fractionMoved = Math.abs(dX / viewHolder.itemView.getMeasuredWidth());
                Drawable drawable;
                if (dX < 0) {
                    drawable = leftSwipeDrawable;
                    viewRect.left = (int) dX + viewRect.right;
                } else {
                    drawable = rightSwipeDrawable;
                    viewRect.right = (int) dX - viewRect.left;
                }

                if (fractionMoved > getSwipeThreshold(viewHolder))
                    drawable.setState(new int[]{android.R.attr.state_above_anchor});
                else
                    drawable.setState(new int[]{-android.R.attr.state_above_anchor});

                viewRect.offset(0, viewHolder.itemView.getTop());
                drawable.setBounds(viewRect);
                drawable.draw(c);
            }
        }
    }

    /**
     * MotionListener for Floating Action Bar Button to mark all articles in current
     * news feed as marked without using the menu.
     *
     * A movement up is required to prevent accidentally marking articles as read.
     */
    private class FastMarkReadMotionListener implements View.OnTouchListener {
        private final View fabMarkAllAsRead;
        private final ImageView targetView;

        private boolean markAsRead = false;
        private float originX,
                      originY;
        private float dx,
                      dy;

        public FastMarkReadMotionListener(View fabMarkAllAsRead) {
            this.fabMarkAllAsRead = fabMarkAllAsRead;
            this.targetView = fabMarkAllAsRead.findViewById(R.id.target_done_all);
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    this.startUserInteractionProcess(v, event);
                    break;
                case MotionEvent.ACTION_MOVE:
                    this.moveFAB(v, event);
                    break;
                case MotionEvent.ACTION_UP:
                    this.stopUserInteractionProcess(v);
                    break;
                default:
                    // Do nothing
                    break;
            }
            return true;
        }

        /**
         * Start Animation for user to drag all read button to target.
         * Once the button is moved to the target, a success animation is loaded and shown.
         *
         * @param v FAB moved by the user
         * @param event motion event for v
         */

        private void startUserInteractionProcess(View v, MotionEvent event) {
            // Save start location of movement and button
            this.originX = v.getX();
            this.originY = v.getY();
            this.dx = v.getX() - event.getRawX();
            this.dy = v.getY() - event.getRawY();
            this.markAsRead = false;

            // Start animation of target
            this.targetView.setImageResource(R.drawable.fa_all_read_target);
            this.targetView.setVisibility(View.VISIBLE);
            ((Animatable)this.targetView.getDrawable()).start();
        }

        /**
         * Handle move event of FAB to mark all articles as read
         * Two things are done here:
         *  - button location is changed
         *  - it is checked iv button is moved into target area
         *
         * @param v FAB moved by the user
         * @param event motion event for v
         */
        private void moveFAB(View v, MotionEvent event) {
            v.setX(event.getRawX() + this.dx);
            v.setY(event.getRawY() + this.dy);
            this.checkLocation(event);
        }

        /**
         * Checks if FAB to mark all as read was moved within the shown target area.
         * For location calculation, the actual location of the target view is read
         * and calculated if current move position is within the view area of the target view.
         *
         * @param evt MotionEvent of all read FAB
         */
        private void checkLocation(MotionEvent evt) {
            // Location on screen for target is required as motion event returns location on screen
            int[] location = new int[2];
            this.targetView.getLocationOnScreen(location);

            Rect r = new Rect(location[0], location[1],
                    (location[0] + targetView.getWidth()),
                    (location[1] + targetView.getHeight()));

            if (r.contains((int)evt.getRawX(), (int)evt.getRawY())) {
                if (!this.markAsRead) {
                    this.markAsRead = true;
                    this.targetView.setImageResource(R.drawable.fa_all_read_target_success);
                    ((Animatable) this.targetView.getDrawable()).start();
                }
            } else {
                if (this.markAsRead) {
                    this.markAsRead = false;
                    this.targetView.setImageResource(R.drawable.fa_all_read_target);
                    ((Animatable) this.targetView.getDrawable()).start();

                }
            }
        }

        /**
         * Stops the user interaction
         *  - FAB is animated back to original position
         *  - A success animation is shown of all articles will be marked as read
         *  - Target view is hidden again
         *
         * @param v view of fab
         */
        private void stopUserInteractionProcess(View v) {
            if (this.markAsRead) {
                Animation anim_success = AnimationUtils.loadAnimation(NewsReaderDetailFragment.this.getContext(),
                        R.anim.all_read_success);
                anim_success.setAnimationListener(new Animation.AnimationListener() {
                    @Override
                    public void onAnimationStart(Animation animation) {
                        v.animate().x(originX).y(originY).setDuration(100).setStartDelay(0).start();
                    }

                    @Override
                    public void onAnimationEnd(Animation animation) {
                        ((Animatable)targetView.getDrawable()).stop();
                        targetView.setVisibility(View.INVISIBLE);
                    }

                    @Override
                    public void onAnimationRepeat(Animation animation) {
                        //Nothing to do here for now
                    }
                });
                this.targetView.startAnimation(anim_success);
                this.markAllAsReadForCurrentView();
            } else {
                this.targetView.setVisibility(View.INVISIBLE);
                v.animate().x(this.originX).y(this.originY).setDuration(100).setStartDelay(0).start();
                ((Animatable)this.targetView.getDrawable()).stop();
            }
        }

        /**
         * Mark all articles in current view as read.
         */
        private void markAllAsReadForCurrentView() {
            DatabaseConnectionOrm dbConn2 = new DatabaseConnectionOrm(this.fabMarkAllAsRead.getContext());
            var deletedCount = dbConn2.markAllItemsAsReadForCurrentView();
            NewsReaderDetailFragment.this.refreshCurrentRssView();
            Snackbar.make(
                    fabMarkAllAsRead,
                    getResources().getQuantityString(
                            R.plurals.marked_as_read_message,
                            deletedCount,
                            deletedCount
                    ),
                    BaseTransientBottomBar.LENGTH_SHORT
            ).setAnchorView(fabMarkAllAsRead).show();
        }
    }
}
