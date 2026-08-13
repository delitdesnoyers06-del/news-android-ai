Enough context. Writing the integration spec.

---

# UI INTEGRATION SPEC — news-android-ai

Everything below is anchored to code I read. Line numbers are from the current working tree. Naming: I use **`AI_FOR_YOU(-14)`** (product's name) — `validate:android-risk` calls the same id `AI_DIGEST(-14)`; pick one, they are the same row. Files are absolute paths under `/home/yohann/dev/padam/news-android-ai/News-Android-App/src/main/`.

---

## 1. The new virtual folder — 8 extension points, actual diffs

### 1.1 Enum — `ListView/SubscriptionExpandableListAdapter.java:87-88`

```java
    public enum SPECIAL_FOLDERS  {
        ALL_UNREAD_ITEMS(-10), ALL_STARRED_ITEMS(-11), ALL_ITEMS(-12), ALL_DOWNLOADED_PODCASTS(-13), ITEMS_WITHOUT_FOLDER(-22);
```
→
```java
    public enum SPECIAL_FOLDERS  {
        ALL_UNREAD_ITEMS(-10), ALL_STARRED_ITEMS(-11), ALL_ITEMS(-12), ALL_DOWNLOADED_PODCASTS(-13),
        AI_FOR_YOU(-14), ITEMS_WITHOUT_FOLDER(-22);
```
Plus a static import at `:24-27`:
```java
import static de.luhmer.owncloudnewsreader.ListView.SubscriptionExpandableListAdapter.SPECIAL_FOLDERS.AI_FOR_YOU;
```

### 1.2 Drawer row — `:377-380`

```java
        ArrayList<AbstractItem> mCategories = new ArrayList<>();
        mCategories.add(new FolderSubscribtionItem(mContext.getString(R.string.allUnreadFeeds), null, ALL_UNREAD_ITEMS.getValue()));
        mCategories.add(new FolderSubscribtionItem(mContext.getString(R.string.starredFeeds), null, ALL_STARRED_ITEMS.getValue()));
        mCategories.add(new FolderSubscribtionItem(mContext.getString(R.string.downloadedPodcasts), null, ALL_DOWNLOADED_PODCASTS.getValue()));
```
→ insert **as element 0**, gated on availability so the row does not exist on a device that cannot run it:
```java
        ArrayList<AbstractItem> mCategories = new ArrayList<>();
        if (AiAvailability.isSupported(mContext)) {   // Build.SUPPORTED_64_BIT_ABIS + totalMem, see §5
            mCategories.add(new FolderSubscribtionItem(mContext.getString(R.string.ai_for_you), null, AI_FOR_YOU.getValue()));
        }
        mCategories.add(new FolderSubscribtionItem(mContext.getString(R.string.allUnreadFeeds), null, ALL_UNREAD_ITEMS.getValue()));
```
Note the constructor signature in use here is `FolderSubscribtionItem(header, idFolder, id_database)` — `idFolder` **must** stay `null`, that is what routes the row to the `else` branch at `getGroupView():302`.

### 1.3 Icon + no chevron — `getGroupView()`, `:302-312`

```java
        } else {
        	if(group.id_database == ALL_STARRED_ITEMS.getValue()) {
                viewHolder.binding.imgViewExpandableIndicator.setVisibility(View.GONE);
                viewHolder.binding.imgViewFavicon.setVisibility(View.VISIBLE);
                rotation = 0;
                viewHolder.binding.imgViewFavicon.setImageResource(R.drawable.ic_star_border_24dp_theme_aware);
            } else if(group.id_database == ALL_DOWNLOADED_PODCASTS.getValue()) {
```
→ insert a branch before the podcast one:
```java
        } else {
            if (group.id_database == AI_FOR_YOU.getValue()) {
                viewHolder.binding.imgViewExpandableIndicator.setVisibility(View.GONE);
                viewHolder.binding.imgViewFavicon.setVisibility(View.VISIBLE);
                rotation = 0;
                viewHolder.binding.imgViewFavicon.setImageResource(R.drawable.ic_ai_sparkle_24dp_theme_aware);
            } else if(group.id_database == ALL_STARRED_ITEMS.getValue()) {
```

### 1.4 Badge count — **one line, and it also solves extension point 7 for free**

The brief and the product spec both say to add a branch near `:286` and a purge exemption at `:523`. **Both are unnecessary.** Evidence:

`DatabaseConnectionOrm.java:740-741` already stuffs the two virtual folders into the folder-count array:
```java
        values[0].put(SPECIAL_FOLDERS.ALL_UNREAD_ITEMS.getValue(), String.valueOf(totalUnreadItemsCount));
        values[0].put(SPECIAL_FOLDERS.ALL_STARRED_ITEMS.getValue(), getUnreadItemsCountForSpecificFolder(SPECIAL_FOLDERS.ALL_STARRED_ITEMS));
```
`getGroupView():279-284` reads that array generically for any row whose `idFolder == null`:
```java
        if (!skipGetUnread) {
            String unreadCount = unreadCountFolders.get((int) group.id_database);
            if (unreadCount != null) {
                viewHolder.binding.tVFeedsCount.setText(unreadCount);
            }
        }
```
and the only-unread purge at `NotifyDataSetChangedAsyncTask.onPostExecute():518-519` tests exactly the same map:
```java
                    if(item instanceof FolderSubscribtionItem &&
                            unreadCountFoldersTemp.get(((Long) item.id_database).intValue()) == null) {
```
`getUnreadItemsCountForSpecificFolder()` returns `values.valueAt(0)` from `SELECT COUNT(1)` — a non-null `"0"` when empty. That is *why* the Starred row never disappears under "show only unread" and why it needs no exemption. So:

**`DatabaseConnectionOrm.java`, after `:741`:**
```java
        values[0].put(SPECIAL_FOLDERS.AI_FOR_YOU.getValue(), String.valueOf(getAiSelectedUnreadCount()));
```
**New method, next to `getUnreadItemsCountForSpecificFolder()` at `:681`:**
```java
    public int getAiSelectedUnreadCount() {
        return (int) getLongValueBySQL(
                "SELECT COUNT(1) FROM " + RssItemDao.TABLENAME +
                " JOIN AI_SCORE ON AI_SCORE.RSS_ITEM_ID = " + RssItemDao.TABLENAME + "." + RssItemDao.Properties.Id.columnName +
                " WHERE AI_SCORE.STATUS = 'selected'" +
                " AND " + RssItemDao.TABLENAME + "." + RssItemDao.Properties.Read_temp.columnName + " != 1");
    }
```
No change to `notifyCountDataSetChanged()`'s 4-arg signature, no change to `NotifyDataSetChangedAsyncTask`, no change to `onPostExecute()`. This works only because `AI_SCORE` lives in the **same DB file** (`validate:android-risk` recommendation (b), raw `execSQL` from `DatabaseHelperOrm`). If anyone re-litigates that into a separate DB file, this one-liner dies and you are back to a Java-side count plus an explicit purge exemption.

### 1.5 Children — **do nothing**

`loadCategoriesAndItemsFromDatabase():401-421`:
```java
            if (parent_id == ALL_UNREAD_ITEMS.getValue()) {
                feedItemList = dbConn.getAllFeedsWithUnreadRssItems();
            } else if (parent_id == ALL_STARRED_ITEMS.getValue()) {
            ...
            if(feedItemList != null) {
```
`feedItemList` stays `null` for `-14`, `mItems.get(-14)` is an empty list, `getChildrenCount()` returns 0, and `getGroupView()`'s AI branch (§1.3) already hides the chevron. "For you" is a cross-feed relevance list; grouping it by feed is meaningless. Skip the brief's checklist item 5.

### 1.6 The query — `DatabaseConnectionOrm.getAllItemsIdsForFolderSQL():607-635`

**The trap is line 611, not the missing branch.** Current condition:
```java
        if(!(ID_FOLDER == ALL_UNREAD_ITEMS.getValue() || ID_FOLDER == ALL_STARRED_ITEMS.getValue() || ID_FOLDER == ALL_DOWNLOADED_PODCASTS.getValue()) || ID_FOLDER == ALL_ITEMS.getValue())//Wenn nicht Alle Artikel ausgewaehlt wurde (-10) oder (-11) fuer Starred Feeds
        {
            buildSQL += " WHERE " + RssItemDao.Properties.FeedId.columnName + " IN " +
                    "(SELECT sc." + FeedDao.Properties.Id.columnName + ...
                    " WHERE f." + FolderDao.Properties.Id.columnName + " = " + ID_FOLDER + ")";
```
`-14` is none of `-10/-11/-13`, so the negation is **true** and the AI folder silently takes the real-folder branch, producing `WHERE f._id = -14` → zero rows, no error. Adding an `else if` further down is not enough; `-14` must be added to the exclusion set.

Full replacement of `:607-635`:
```java
    public String getAllItemsIdsForFolderSQL(long ID_FOLDER, boolean onlyUnread, SORT_DIRECTION sortDirection, Context context) {
        // AI folder: early return. Ordering is by AI rank, never by PUB_DATE/sortDirection,
        // and the tables MUST NOT be aliased (UpdateCurrentRssViewTask injects a bare
        // " GROUP BY FINGERPRINT " before the first "ORDER BY" - see NewsReaderDetailFragment:539).
        if (ID_FOLDER == AI_FOR_YOU.getValue()) {
            String ai = "SELECT " + RssItemDao.TABLENAME + "." + RssItemDao.Properties.Id.columnName +
                    " FROM " + RssItemDao.TABLENAME +
                    " JOIN AI_SCORE ON AI_SCORE.RSS_ITEM_ID = " + RssItemDao.TABLENAME + "." + RssItemDao.Properties.Id.columnName +
                    " WHERE AI_SCORE.STATUS = 'selected'";
            if (onlyUnread) {
                ai += " AND " + RssItemDao.TABLENAME + "." + RssItemDao.Properties.Read_temp.columnName + " != 1";
            }
            ai += " ORDER BY AI_SCORE.RANK_SCORE DESC, " +
                    RssItemDao.TABLENAME + "." + RssItemDao.Properties.PubDate.columnName + " DESC, " +
                    RssItemDao.TABLENAME + "." + RssItemDao.Properties.Id.columnName + " DESC";
            return ai;
        }

        String buildSQL = "SELECT " + RssItemDao.Properties.Id.columnName +
                " FROM " + RssItemDao.TABLENAME;

        if(!(ID_FOLDER == ALL_UNREAD_ITEMS.getValue() || ID_FOLDER == ALL_STARRED_ITEMS.getValue() || ID_FOLDER == ALL_DOWNLOADED_PODCASTS.getValue()) || ID_FOLDER == ALL_ITEMS.getValue())
        { ... unchanged ... }
```
Three hard constraints on that string, all load-bearing:

* **No table aliases.** `NewsReaderDetailFragment:543` injects `" GROUP BY FINGERPRINT "` unqualified. With aliases it still parses (only `RSS_ITEM` has that column) and then silently mis-ranks.
* **`GROUP BY FINGERPRINT` + `ORDER BY AI_SCORE.RANK_SCORE` picks an arbitrary group member.** The only safe fix is at the write side: **AI_SCORE must be fingerprint-homogeneous** — when the triage job writes a score, write the same `RANK_SCORE`/`STATUS`/`WHY` to every `RSS_ITEM` row sharing that `FINGERPRINT`. Then the arbitrary pick is a pick among identical values and the existing dedupe behaves exactly as it does for every other folder. Do **not** try to fix this by editing `UpdateCurrentRssViewTask`.
* **No `ORDER BY` substring anywhere but at the end** — `indexOf("ORDER BY")` at `:539` finds the first occurrence.

**`getAllItemsIdsForFolderSQLSearch():637-658` — do NOT add an AI branch.** Its exclusion set at `:641` is `(-10, -11)` only, so `-13` already falls into the folder branch (pre-existing bug, podcast search returns nothing). Search over a relevance-ordered list is incoherent anyway. Instead hide the search affordance for this folder: `NewsReaderListActivity.onCreateOptionsMenu()` at `:912` grabs `menu.findItem(R.id.menu_search)`; add to `syncMenuItemUnreadOnly()`-adjacent code a `menuItemSearch.setVisible(currentFolderId == null || currentFolderId != -14)`.

### 1.7 Only-unread purge exemption

Not needed — see §1.4.

### 1.8 `NewsReaderDetailFragment` + `NewsReaderListActivity`

**a) `NewsReaderDetailFragment.UpdateCurrentRssViewTask.doInBackground():524-537`:**
```java
            boolean onlyUnreadItems = mPrefs.getBoolean(SettingsActivity.CB_SHOWONLYUNREAD_STRING, false);
            boolean onlyStarredItems = idFolder != null && idFolder == ALL_STARRED_ITEMS.getValue();
            ...
            } else if (idFolder != null) {
                if (idFolder == ALL_STARRED_ITEMS.getValue() || idFolder == ALL_DOWNLOADED_PODCASTS.getValue())
                    onlyUnreadItems = false;
                sqlSelectStatement = dbConn.getAllItemsIdsForFolderSQL(idFolder, onlyUnreadItems, sortDirection, mActivity);
            }
```
No edit required — `getAllItemsIdsForFolderSQL` handles `-14` and ignores `sortDirection` by construction. That is deliberate: **product requires `SP_SORT_ORDER` to have no effect on this folder**, and dropping the parameter on the floor inside the builder is the only place where a future "let's wire the pref for consistency" refactor cannot silently break it. Add a comment saying so.

**b) `updateMenuItemsState():228-233`:**
```java
            nla.getMenuItemDownloadMoreItems().setEnabled(idFolder == null || idFolder != ALL_UNREAD_ITEMS.getValue());
```
→
```java
            nla.getMenuItemDownloadMoreItems().setEnabled(idFolder == null
                    || (idFolder != ALL_UNREAD_ITEMS.getValue() && idFolder != AI_FOR_YOU.getValue()));
```

**c) `NewsReaderListActivity.DownloadMoreItems()` — this one crashes without the edit.** Current:
```java
			List<Integer> specialFolders = Arrays.asList(
					SubscriptionExpandableListAdapter.SPECIAL_FOLDERS.ALL_UNREAD_ITEMS.getValue(),
					SubscriptionExpandableListAdapter.SPECIAL_FOLDERS.ALL_STARRED_ITEMS.getValue(),
					SubscriptionExpandableListAdapter.SPECIAL_FOLDERS.ALL_ITEMS.getValue()
			);
			if (specialFolders.contains(idFolder.intValue())) {
				startSync();
			} else {
				DatabaseConnectionOrm dbConn = new DatabaseConnectionOrm(this);
				for (Feed feed : dbConn.getFolderById(idFolder).getFeedList()) {
```
`getFolderById(-14)` returns null → NPE. Add `SPECIAL_FOLDERS.AI_FOR_YOU.getValue(),` to the list. (`-13` has the same latent NPE today; the menu item is enabled for it.)

**d) `NewsReaderListActivity.updateDetailFragment():878-887`:**
```java
			} else if (idFolder == -11) {
				title = getString(R.string.starredFeeds);
			} else if (idFolder == -13) {
				title = getString(R.string.downloadedPodcasts);
			}
```
→ add
```java
			} else if (idFolder == -14) {
				title = getString(R.string.ai_for_you);
			}
```

**e) Landing folder — leave alone.** `NewsReaderListActivity:446` / `:584` call `switchToAllUnreadItemsFolder()`. Product wants For You as the default destination; I recommend **not** changing it in v1. It contradicts acceptance criterion 30 ("with no AI model installed… every screen behaves identically to the upstream build") and it makes a fresh install land on a list that is only recency-ordered with an onboarding card. Position 0 in the drawer is sufficient discovery.

---

## 2. The feedback UI

Product chose swipe (For-You only, plus opt-in global values) + fast actions. Both are implementable; here is the exact shape.

### 2.1 Swipe values `"4"`/`"5"`

**`res/values/strings.xml:167-178`** (arrays are `translatable="false"`, items point at translatable strings):
```xml
    <string-array name="pref_general_swipe_action" translatable="false">
        <item>@string/action_openInBrowser</item>
        <item>@string/action_starred</item>
        <item>@string/action_read</item>
        <item>@string/action_Share</item>
        <item>@string/action_ai_more_like_this</item>
        <item>@string/action_ai_less_like_this</item>
    </string-array>
    <string-array name="pref_general_swipe_action_values" translatable="false">
        <item>0</item><item>1</item><item>2</item><item>3</item><item>4</item><item>5</item>
    </string-array>
```
The picker at `res/xml/pref_general.xml:104-118` already binds these arrays; no XML change there.

**`NewsReaderDetailFragment.getLayoutId():480-490`** — this is a landmine: the `default:` returns `Integer.MAX_VALUE`, which is then fed straight into `obtainStyledAttributes(new int[]{leftId, rightId})` at `:474`. Any unhandled value throws. So the theme attrs are mandatory:
```java
    private int getLayoutId(String action) {
        switch (action) {
            case "0": return R.attr.openinbrowserDrawable;
            case "1": return R.attr.starredDrawable;
            case "2": return R.attr.markasreadDrawable;
            case "3": return R.attr.shareDrawable;
            case "4": return R.attr.aiMoreDrawable;
            case "5": return R.attr.aiLessDrawable;
            default: ...
```
`res/values/attrs.xml` (alongside `:8-12`):
```xml
    <attr name="aiMoreDrawable" format="reference" />
    <attr name="aiLessDrawable" format="reference" />
```
`res/values/themes.xml` (alongside `:42-46`, in **every** theme that defines `starredDrawable` — grep says at least the block at `:42`):
```xml
        <item name="aiMoreDrawable">@drawable/swipe_ai_more</item>
        <item name="aiLessDrawable">@drawable/swipe_ai_less</item>
```
Model the two drawables on `@drawable/swipe_setstarred` — they are state-list drawables keyed on `android.R.attr.state_above_anchor` (see `onChildDraw():712-715`), not plain vectors. Copy that file's structure with `ic_thumb_up_24` / `ic_thumb_down_24`.

### 2.2 For-You override + undo — `onSwiped():653-692`

```java
        @Override
        public void onSwiped(@NonNull final RecyclerView.ViewHolder viewHolder, final int direction) {
            final NewsListRecyclerAdapter adapter = (NewsListRecyclerAdapter) binding.list.getAdapter();

            String swipeAction;
            if (direction == ItemTouchHelper.LEFT)
                swipeAction = mPrefs.getString(SP_SWIPE_LEFT_ACTION, SP_SWIPE_LEFT_ACTION_DEFAULT);
            else
                swipeAction = mPrefs.getString(SP_SWIPE_RIGHT_ACTION, SP_SWIPE_RIGHT_ACTION_DEFAULT);
            switch (swipeAction) {
```
→
```java
        @Override
        public void onSwiped(@NonNull final RecyclerView.ViewHolder viewHolder, final int direction) {
            if (!(viewHolder instanceof RssItemViewHolder)) { return; }   // digest card / status strip
            final NewsListRecyclerAdapter adapter = newsAdapter;

            String swipeAction;
            if (idFolder != null && idFolder == AI_FOR_YOU.getValue()) {
                // The For-You list exists to produce decisions; the global swipe prefs do not apply here.
                swipeAction = (direction == ItemTouchHelper.LEFT) ? "5" : "4";
            } else if (direction == ItemTouchHelper.LEFT) {
                swipeAction = mPrefs.getString(SP_SWIPE_LEFT_ACTION, SP_SWIPE_LEFT_ACTION_DEFAULT);
            } else {
                swipeAction = mPrefs.getString(SP_SWIPE_RIGHT_ACTION, SP_SWIPE_RIGHT_ACTION_DEFAULT);
            }
            switch (swipeAction) {
                ...
                case "4":
                case "5":
                    applyAiDecision((RssItemViewHolder) viewHolder, "4".equals(swipeAction));
                    return;   // NOT break: applyAiDecision owns the row removal
```
and the new method on the fragment:
```java
    private void applyAiDecision(RssItemViewHolder vh, boolean positive) {
        final RssItem item = vh.getRssItem();
        final int pos = vh.getBindingAdapterPosition();
        AiDecisions.record(requireContext(), item, positive ? AiDecisions.KEEP : AiDecisions.REJECT);
        newsAdapter.removeItemAt(pos);

        Snackbar.make(binding.getRoot(),
                getString(positive ? R.string.ai_snack_more_like_this : R.string.ai_snack_less_like_this),
                BaseTransientBottomBar.LENGTH_LONG)   // 5s, mandated by product
            .setAnchorView(binding.fabDoneAll.getVisibility() == View.VISIBLE ? binding.fabDoneAll : null)
            .setAction(R.string.ai_snack_undo, v -> {
                AiDecisions.record(requireContext(), item, AiDecisions.UNDO);  // append-only, does NOT delete
                newsAdapter.restoreItemAt(pos, item);
                binding.list.scrollToPosition(pos);
            })
            .show();
    }
```
Precedent for the Snackbar shape: `NewsReaderDetailFragment:878-886` and `NewsReaderListActivity.makeFABAwareSnackbar()`.

**Two adapter methods must be added to `NewsListRecyclerAdapter`** (its `lazyList` is already mutated at `:112`/`:470-471`, and `LoadMoreItemsAsyncTask` shows the `UnsupportedOperationException` guard is needed):
```java
    public void removeItemAt(int position) {
        if (lazyList == null || position < 0 || position >= lazyList.size()) return;
        try { lazyList.remove(position); notifyItemRemoved(position); }
        catch (UnsupportedOperationException ignored) { notifyDataSetChanged(); }
    }

    public void restoreItemAt(int position, RssItem item) {
        if (lazyList == null) return;
        int p = Math.min(position, lazyList.size());
        try { lazyList.add(p, item); notifyItemInserted(p); }
        catch (UnsupportedOperationException ignored) { notifyDataSetChanged(); }
    }
```

**`updateSwipeDrawables()` must become folder-aware** — today it is driven only by prefs (`:461-478`) and is called from `onInflate():454` and `onResume():223`. Add the same override and call it from `setData()`:
```java
    private void updateSwipeDrawables(boolean forceUpdate) {
        boolean ai = idFolder != null && idFolder == AI_FOR_YOU.getValue();
        String leftAction  = ai ? "5" : mPrefs.getString(SP_SWIPE_LEFT_ACTION, SP_SWIPE_LEFT_ACTION_DEFAULT);
        String rightAction = ai ? "4" : mPrefs.getString(SP_SWIPE_RIGHT_ACTION, SP_SWIPE_RIGHT_ACTION_DEFAULT);
```
and in `setData():195-207`, before `updateCurrentRssView()`, add `updateSwipeDrawables(false);`. Without this the user sees a star/checkmark drawable while performing an AI decision.

**Also required: block swipe on non-article rows.** `NewsReaderItemTouchHelperCallback` extends `SimpleCallback(0, LEFT|RIGHT)` (`:638-641`); once the digest card and status strip exist (§4) they are swipeable and `onSwiped`'s `(RssItemViewHolder)` cast crashes. Override:
```java
        @Override
        public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh) {
            return (vh instanceof RssItemViewHolder) ? super.getMovementFlags(rv, vh) : 0;
        }
```

### 2.3 Fast actions — which view holders change: **none**

`widget_fastactions_detailview.xml` is included exactly once, from `activity_news_detail.xml:56-61`, and wired in `NewsDetailActivity.initFastActionBar():250-267`. It is not part of the list at all, so **zero of the 7 `RssItemViewHolder` subclasses are touched and `RssItemViewHolder.java` is unaffected** by the fast-action work. Product's claim to the contrary is wrong.

Add two buttons inside `fa_collapse_layout`, after `fa_mark_as_read`:
```xml
        <androidx.appcompat.widget.AppCompatImageButton
            android:id="@+id/fa_ai_up"
            android:layout_width="56dp"
            android:layout_height="56dp"
            android:visibility="gone"
            android:background="@android:color/transparent"
            android:contentDescription="@string/action_ai_more_like_this"
            android:src="@drawable/ic_thumb_up_24_theme_aware"
            android:tint="?attr/colorControlActivated"/>

        <androidx.appcompat.widget.AppCompatImageButton
            android:id="@+id/fa_ai_down"
            android:layout_width="56dp"
            android:layout_height="56dp"
            android:visibility="gone"
            android:background="@android:color/transparent"
            android:contentDescription="@string/action_ai_less_like_this"
            android:src="@drawable/ic_thumb_down_24_theme_aware"
            android:tint="?attr/colorControlActivated"/>
```
`NewsDetailActivity.initFastActionBar()` at `:254-256`:
```java
			binding.faDetailBar.faStar.setOnClickListener(v -> NewsDetailActivity.this.toggleRssItemStarredState());
			binding.faDetailBar.faMarkAsRead.setOnClickListener(v -> NewsDetailActivity.this.markRead(currentPosition));
```
→ append
```java
			boolean aiOn = mPrefs.getBoolean(SettingsActivity.CB_AI_ENABLED, false);
			binding.faDetailBar.faAiUp.setVisibility(aiOn ? View.VISIBLE : View.GONE);
			binding.faDetailBar.faAiDown.setVisibility(aiOn ? View.VISIBLE : View.GONE);
			binding.faDetailBar.faAiUp.setOnClickListener(v -> recordAiDecision(true));
			binding.faDetailBar.faAiDown.setOnClickListener(v -> recordAiDecision(false));
```
**Default them to GONE and gate on `cb_ai_enabled`.** The bar is `wrap_content`, bottom-end, `layout_marginEnd="16dp"` in a `RelativeLayout` (`activity_news_detail.xml:56-61`). Three buttons is 168dp; five is 280dp + 32dp margins = 312dp, which overflows a 320dp-wide device and is 87% of a 360dp one. Gating on the master switch keeps the existing bar byte-identical for everyone who never turns AI on, which is acceptance criterion 30.

---

## 3. The "why this matters" line

### 3.1 Which layouts/holders

**Do not touch any of the 7 existing layouts.** `subscription_detail_list_item_headline_thumbnail.xml` is a hard `android:layout_height="124dp"` ConstraintLayout with `summary` at `maxLines="3"`; `subscription_detail_list_item_thumbnail.xml` (the default, `sp_feed_list_layout="0"`) has a fixed `android:layout_height="96dp"` body. Both are shared with Unread and every folder.

New: **`res/layout/ai_list_item.xml`** + **`adapter/RssItemAiViewHolder.kt`**.

**Non-obvious hard requirement on the new holder:** `RssItemViewHolder` declares 9 abstract getters, and three of them are dereferenced unconditionally:
- `NewsListRecyclerAdapter.onCreateViewHolder():253` — `viewHolder.getPlayPausePodcastWrapper().setOnClickListener(...)`
- `onBindViewHolder():293/297` — `holder.getPlayPausePodcastWrapper().setVisibility(...)`
- `setPlaying():350` — `getPlayPausePodcastButton().getContext()`
- `setDownloadPodcastProgressbar():363` — `getPodcastDownloadProgress().setProgress(...)`

So `ai_list_item.xml` **must** `<include layout="@layout/subscription_detail_list_item_podcast_wrapper"/>`. `getStar()`, `getColorFeed()`, `getTextViewTitle/Summary/Body/ItemDate` are all null-checked (`:157`, `:182`, `:196`, `:213`, `:230`, `:320`, `:327`) and may return null.

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/list_item_header"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:minHeight="124dp"
    android:paddingTop="@dimen/listview_row_margin_top"
    android:paddingBottom="@dimen/listview_row_margin_bottom"
    android:paddingStart="@dimen/listview_row_margin_left"
    android:paddingEnd="@dimen/listview_row_margin_right"
    android:descendantFocusability="blocksDescendants"
    android:background="?attr/selectableItemBackground">

    <ImageView
        android:id="@+id/imgViewThumbnail"
        android:layout_width="88dp" android:layout_height="88dp"
        android:scaleType="centerCrop"
        android:contentDescription="@string/img_view_thumbnail"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        tools:src="@drawable/feed_icon" />

    <include android:id="@+id/podcast_wrapper"
        layout="@layout/subscription_detail_list_item_podcast_wrapper"
        android:layout_width="88dp" android:layout_height="88dp"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintTop_toTopOf="parent" />

    <TextView
        android:id="@+id/summary"
        android:layout_width="0dp" android:layout_height="wrap_content"
        android:maxLines="2" android:ellipsize="end"
        android:lineSpacingMultiplier="1.2"
        android:textSize="17sp" android:textStyle="bold"
        android:textColor="?attr/primaryTextColor"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toStartOf="@id/imgViewThumbnail"
        app:layout_constraintTop_toTopOf="parent"
        android:layout_marginEnd="16dp"
        tools:text="New EU regulation tightens rules on public transport tender awards" />

    <!-- the why-line. GONE (not empty) when score is null. -->
    <TextView
        android:id="@+id/ai_why"
        android:layout_width="0dp" android:layout_height="wrap_content"
        android:maxLines="2" android:ellipsize="end"
        android:layout_marginTop="6dp" android:layout_marginEnd="16dp"
        android:textSize="13sp" android:textStyle="italic"
        android:textColor="?attr/colorOnSurfaceVariant"
        android:visibility="gone"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toStartOf="@id/imgViewThumbnail"
        app:layout_constraintTop_toBottomOf="@id/summary"
        tools:visibility="visible"
        tools:text="new EU rule on demand-responsive services" />

    <!-- shimmer placeholder shown while this row is queued for scoring -->
    <View
        android:id="@+id/ai_why_placeholder"
        android:layout_width="0dp" android:layout_height="12dp"
        android:layout_marginTop="8dp" android:layout_marginEnd="88dp"
        android:background="@drawable/ai_shimmer_bar"
        android:visibility="gone"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toStartOf="@id/imgViewThumbnail"
        app:layout_constraintTop_toBottomOf="@id/summary" />

    <TextView
        android:id="@+id/ai_score_pill"
        android:layout_width="18dp" android:layout_height="18dp"
        android:layout_marginTop="8dp"
        android:gravity="center" android:textSize="10sp" android:textStyle="bold"
        android:background="@drawable/ai_score_pill_bg"
        android:visibility="gone"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/ai_why"
        tools:visibility="visible" tools:text="3" />

    <ImageView android:id="@+id/imgViewFavIcon"
        android:layout_width="18dp" android:layout_height="18dp"
        android:layout_marginStart="8dp" android:layout_marginTop="8dp"
        android:contentDescription="@string/content_desc_none"
        app:layout_constraintStart_toEndOf="@id/ai_score_pill"
        app:layout_constraintTop_toBottomOf="@id/ai_why"
        tools:src="@drawable/default_feed_icon_light" />

    <ImageView android:id="@+id/star_imageview"
        android:layout_width="18dp" android:layout_height="18dp"
        android:layout_marginStart="8dp" android:layout_marginTop="8dp"
        android:contentDescription="@string/content_desc_add_to_favorites"
        android:src="@drawable/ic_star_white_24"
        app:tint="?attr/starredColor"
        app:layout_constraintStart_toEndOf="@id/imgViewFavIcon"
        app:layout_constraintTop_toBottomOf="@id/ai_why" />

    <TextView android:id="@+id/tv_subscription"
        android:layout_width="0dp" android:layout_height="wrap_content"
        android:layout_marginStart="8dp" android:layout_marginTop="8dp"
        android:singleLine="true" android:ellipsize="middle"
        android:textSize="13sp" android:textColor="@color/material_grey_500"
        app:layout_constraintStart_toEndOf="@id/star_imageview"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintTop_toBottomOf="@id/ai_why"
        tools:text="Le Monde" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

Holder (Kotlin, matching the 6 existing `.kt` holders; note `getTextViewBody()` returns `null` so `RssItemViewHolder.bind()` skips the expensive `getBodyText()` HTML-stripping path at `:230-255` entirely — a measurable win on this list):
```kotlin
class RssItemAiViewHolder internal constructor(
    binding: AiListItemBinding, faviconHandler: FavIconHandler,
    glide: RequestManager, prefs: SharedPreferences,
) : RssItemViewHolder<AiListItemBinding>(binding, faviconHandler, glide, prefs) {
    override fun getImageViewFavIcon(): ImageView = binding.imgViewFavIcon
    override fun getStar(): ImageView = binding.starImageview
    override fun getPlayPausePodcastButton(): ImageView = binding.podcastWrapper.btnPlayPausePodcast
    override fun getColorFeed(): View? = null
    override fun getTextViewTitle(): TextView = binding.tvSubscription
    override fun getTextViewSummary(): TextView = binding.summary
    override fun getTextViewBody(): TextView? = null
    override fun getTextViewItemDate(): TextView? = null
    override fun getPlayPausePodcastWrapper(): FrameLayout = binding.podcastWrapper.flPlayPausePodcastWrapper
    override fun getPodcastDownloadProgress(): ProgressBar = binding.podcastWrapper.podcastDownloadProgress

    fun bindAi(row: AiRow?) {
        when {
            row == null || row.status == AiRow.PENDING -> {
                binding.aiWhy.visibility = View.GONE
                binding.aiWhyPlaceholder.visibility = View.VISIBLE
                binding.aiScorePill.visibility = View.GONE
            }
            else -> {
                binding.aiWhyPlaceholder.visibility = View.GONE
                binding.aiWhy.visibility = if (row.why.isNullOrBlank()) View.GONE else View.VISIBLE
                binding.aiWhy.text = row.why            // plain text. NEVER Html.fromHtml - model output.
                // Absent means absent: no pill for a row the model failed to parse.
                binding.aiScorePill.visibility = if (row.score == null) View.GONE else View.VISIBLE
                row.score?.let {
                    binding.aiScorePill.text = it.toString()
                    binding.aiScorePill.backgroundTintList = ColorStateList.valueOf(tierColor(it))
                }
            }
        }
    }
}
```

### 3.2 Selection — the adapter does not know `idFolder`

`NewsListRecyclerAdapter.onCreateViewHolder():187` switches on the pref only:
```java
            switch (Integer.parseInt(mPrefs.getString(SettingsActivity.SP_FEED_LIST_LAYOUT, "0"))) {
```
Constructor call site, `NewsReaderDetailFragment:309`:
```java
                nra = new NewsListRecyclerAdapter(mActivity, binding.list, mActivity, mPostDelayHandler, mPrefs);
```
Add a 6th ctor param `Long idFolder`, store it, and prepend to the switch:
```java
            RssItemViewHolder viewHolder;
            if (idFolder != null && idFolder == AI_FOR_YOU.getValue()) {
                viewHolder = new RssItemAiViewHolder(
                        AiListItemBinding.inflate(LayoutInflater.from(context), parent, false),
                        faviconHandler, glide, mPrefs);
            } else {
                switch (Integer.parseInt(mPrefs.getString(SettingsActivity.SP_FEED_LIST_LAYOUT, "0"))) { ... }
            }
```
And in `onBindViewHolder():284-287`:
```java
            final RssItemViewHolder holder = (RssItemViewHolder) viewHolder;
            RssItem item = lazyList.get(position);
            holder.bind(item);
            holder.setStayUnread(NewsReaderListActivity.stayUnreadItems.contains(item.getId()));
```
→ append
```java
            if (holder instanceof RssItemAiViewHolder aiHolder) {
                aiHolder.bindAi(aiRowCache.get(item.getId()));   // preloaded per page, not per bind
            }
```
`aiRowCache` is a `LongSparseArray<AiRow>` filled in `LoadMoreItemsAsyncTask`/`RefreshDataAsyncTask` with one `WHERE RSS_ITEM_ID IN (…25 ids…)` query, on the background thread. **Never** hit SQLite from `onBindViewHolder`.

### 3.3 Row-height cost, measured

Against `subscription_detail_list_item_headline_thumbnail.xml`'s fixed **124dp** (8dp top pad + 3×17sp@1.2 ≈ 73dp title + 8dp + 18dp meta + 8dp bottom pad):

| variant | computed height | delta | rows per 960dp viewport |
|---|---|---|---|
| headline_thumbnail (baseline) | 124dp | — | 7.7 |
| ai_list_item, why `maxLines=1` | 118dp | −6dp | 8.1 |
| **ai_list_item, why `maxLines=2`** | **137dp** | **+13dp (+10%)** | **7.0** |
| ai_list_item, no why-line at all (GONE) | 99dp → clamped by `minHeight` + 88dp thumb → **112dp** | −12dp | 8.5 |

### 3.4 Recommendation: **ship it, at `maxLines=2`, exactly as specced.**

+13dp/row is a 9% scan-density loss on a list that is at most 5–20 items long — you never scroll far enough for it to matter. The why-line is the only mechanism by which the user can catch a 1B-parameter model being wrong, and it is the trigger for the negative signal, which is half the taste model. Two structural notes that matter more than the height:
- The score pill sits on the **existing** meta row, not a new one. That is where the height budget was saved.
- `ai_why` is `visibility="gone"` by default and the row is `wrap_content` with `minHeight`, so rows without a why (score parse failure, embedder-only mode) do not reserve empty space. That is what makes "absent means absent" render correctly rather than as a ragged gap.

---

## 4. The digest — and the list header problem

### 4.1 Do NOT add view types to `NewsListRecyclerAdapter`

It has hard positional coupling everywhere: `getItemViewType():354` = `lazyList.get(position) != null ? VIEW_ITEM : VIEW_PROG`, `onBindViewHolder():285` = `lazyList.get(position)`, `getItemId():365` = `lazyList.get(position)`, and `LoadMoreItemsAsyncTask.onPostExecute():468-473` does `lazyList.remove(prevSize - 1)`. Every one of those needs an offset. Worse, `NewsReaderDetailFragment.handleMarkAsReadScrollEvent():423` does an **unchecked** cast:
```java
            RssItemViewHolder vh = (RssItemViewHolder) binding.list.findViewHolderForLayoutPosition(i);
```
(compare `:436`, which correctly uses `instanceof`). With a card at position 0 and `cb_MarkAsReadWhileScrolling` on, this is a guaranteed `ClassCastException` on first scroll.

### 4.2 Use `ConcatAdapter` (recyclerview 1.4.0, `build.gradle:144` — available)

```java
// NewsReaderDetailFragment.loadRssItemsIntoView(), replacing :307-311
            if (newsAdapter == null) {
                newsAdapter = new NewsListRecyclerAdapter(mActivity, binding.list, mActivity, mPostDelayHandler, mPrefs, idFolder);
                if (idFolder != null && idFolder == AI_FOR_YOU.getValue()) {
                    aiHeaderAdapter = new AiHeaderAdapter(this);   // status strip + onboarding card + digest card
                    binding.list.setAdapter(new ConcatAdapter(aiHeaderAdapter, newsAdapter));
                } else {
                    binding.list.setAdapter(newsAdapter);
                }
            }
            newsAdapter.updateAdapterData(rssItems);
```
`AiHeaderAdapter` is a tiny `RecyclerView.Adapter` with 0–3 items and 3 view types (`STRIP`, `ONBOARDING`, `DIGEST_CARD`), each inflating its own layout, `setHasStableIds(false)`.

**Required companion change: 10 cast sites become a field/accessor.** Grep result:
```
NewsReaderListActivity.java:284      (NewsListRecyclerAdapter) ndf.getRecyclerView().getAdapter()
NewsReaderListActivity.java:1285     ((NewsListRecyclerAdapter) getNewsReaderDetailFragment().getRecyclerView().getAdapter())
NewsReaderDetailFragment.java:236, 247, 307, 360, 615, 655
androidTest/.../ScreenshotTest.java:108
androidTest/.../NewsReaderListActivityUiTests.java:151
```
Add to `NewsReaderDetailFragment`:
```java
    private NewsListRecyclerAdapter newsAdapter;
    private AiHeaderAdapter aiHeaderAdapter;
    public NewsListRecyclerAdapter getNewsAdapter() { return newsAdapter; }
```
and replace all 10 casts with `newsAdapter` / `ndf.getNewsAdapter()`. Mechanical, no behaviour change, but it must land in the same commit or the app crashes the first time an AI folder is opened and the user goes back to Unread.

Also fix `:423` to `if (binding.list.findViewHolderForLayoutPosition(i) instanceof RssItemViewHolder vh && !vh.shouldStayUnread())` — a one-line fix, and a latent bug even without AI (the `ProgressViewHolder` at position N can hit it during lazy-load).

### 4.3 Digest full screen: **Activity**, not Fragment

Reason: it is reached from a card, has its own toolbar/share/mark-all footer, and must survive process death independently of the list fragment's `idFolder`/`idFeed` state. Fragment would mean touching `activity_newsreader.xml`'s two-pane layout.

**`AiDigestActivity extends AppCompatActivity`**, mirroring `SettingsActivity:112-145` (theme → super → `ThemeChooser.afterOnCreate` → `setContentView` → toolbar). ViewBinding only, no Compose.

`res/layout/activity_ai_digest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:fitsSystemWindows="true" android:orientation="vertical">

    <include android:id="@+id/toolbar_layout" layout="@layout/toolbar_layout" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/digest_list"
        android:layout_width="match_parent" android:layout_height="0dp"
        android:layout_weight="1" android:scrollbars="vertical" />

    <com.google.android.material.divider.MaterialDivider
        android:layout_width="match_parent" android:layout_height="wrap_content"/>

    <LinearLayout android:layout_width="match_parent" android:layout_height="wrap_content"
        android:orientation="horizontal" android:padding="@dimen/spacer_1x">
        <com.google.android.material.button.MaterialButton
            android:id="@+id/btn_mark_all_read" style="@style/Widget.Material3.Button.TextButton"
            android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content"
            android:text="@string/ai_digest_mark_all_read"/>
        <com.google.android.material.button.MaterialButton
            android:id="@+id/btn_share_digest" style="@style/Widget.Material3.Button.TextButton"
            android:layout_width="0dp" android:layout_weight="1" android:layout_height="wrap_content"
            android:text="@string/action_Share"/>
    </LinearLayout>
</LinearLayout>
```
`AiDigestAdapter` — 3 view types over a flattened `List<Row>` (`AbstractRow`, `ThemeHeaderRow`, `ItemRow`), layouts `ai_digest_abstract.xml`, `ai_digest_theme_header.xml`, `ai_digest_item.xml`. Item tap → the existing detail path:
```java
Intent i = new Intent(this, NewsDetailActivity.class);
```
(`NewsDetailActivity` is `android:exported="false"` at `AndroidManifest.xml:55-56`; check how `NewsReaderListActivity` passes the item index — the digest must build the same `CURRENT_RSS_ITEM_VIEW` contract or it will open the wrong article. Simplest correct answer: from the digest, call `dbConn.insertIntoRssCurrentViewTable(<digest ids SQL>)` before launching, so the pager pages over the digest, not over the previous list. **Flag as the single highest-risk detail in the digest screen.**)

Share footer reuses the shape at `NewsReaderDetailFragment:680-684`:
```java
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, digestTitle);
        share.putExtra(Intent.EXTRA_TEXT, markdownBody);
        startActivity(Intent.createChooser(share, getString(R.string.action_Share)));
```

Manifest, next to `AndroidManifest.xml:55`:
```xml
        <activity android:name=".AiDigestActivity" android:exported="false"
            android:label="@string/ai_digest_title" />
        <activity android:name=".AiSettingsActivity" android:exported="false"
            android:label="@string/pref_header_ai" />
        <activity android:name=".AiModelManagerActivity" android:exported="false"
            android:label="@string/ai_models_title" />
```

### 4.4 Digest card layout (`ai_digest_card.xml`, rendered by `AiHeaderAdapter`)

`MaterialCardView` (material 1.13.0) → vertical `LinearLayout`: overline `TextView` (11sp, allCaps), abstract `TextView` (`maxLines=3`, `ellipsize=end`) **plus** a sibling shimmer `View` shown while the abstract is null, a `com.google.android.material.chip.ChipGroup` with `singleSelection="true"` for the theme chips, and an end-aligned "Read digest →" `MaterialButton.TextButton`. Chip selection calls back into the fragment → re-runs `insertIntoRssCurrentViewTable` with `AND AI_SCORE.THEMES LIKE '%<slug>%'` appended before `ORDER BY`, then `updateCurrentRssView()`. `X` dismiss writes a date into `AI_META`.

### 4.5 Empty states

`fragment_newsreader_detail.xml` already includes `empty_content_view.xml` as `@id/tv_no_items_available`, and `loadRssItemsIntoView():315-319` only toggles its visibility:
```java
            if (nra.getItemCount() <= 0) {
                binding.tvNoItemsAvailable.getRoot().setVisibility(View.VISIBLE);
            } else {
                binding.tvNoItemsAvailable.getRoot().setVisibility(View.GONE);
            }
```
→
```java
            if (newsAdapter.getItemCount() <= 0) {
                if (idFolder != null && idFolder == AI_FOR_YOU.getValue()) {
                    AiEmptyState s = AiEmptyState.resolve(requireContext(), mPrefs);
                    binding.tvNoItemsAvailable.title.setText(s.titleRes);
                    binding.tvNoItemsAvailable.description.setText(s.descriptionRes);
                } else {
                    binding.tvNoItemsAvailable.title.setText(R.string.empty_view_content);
                    binding.tvNoItemsAvailable.description.setText(R.string.empty_view_content_action);
                }
                binding.tvNoItemsAvailable.getRoot().setVisibility(View.VISIBLE);
```
The `else` branch is mandatory — the include is shared and the ViewBinding fields persist across folder switches, so without the reset the "AI triage is off" text leaks into the Unread empty state. The 4th empty state's `[Set up]` / `[Why?]` action button does not exist in `empty_content_view.xml`; add a `<com.google.android.material.button.MaterialButton android:id="@+id/action" android:visibility="gone" .../>` below `@id/description` — default-GONE so no existing screen changes.

---

## 5. Settings

### 5.1 `SettingsActivity.java` constants — insert after `:106`

```java
    // ---- AI triage ----
    public static final String CB_AI_ENABLED            = "cb_ai_enabled";
    public static final String PREF_AI_SETTINGS         = "pref_ai_settings";

    public static final String SP_AI_MODEL_TRIAGE       = "sp_ai_model_triage";
    public static final String SP_AI_MODEL_EMBEDDING    = "sp_ai_model_embedding";
    public static final String SP_AI_MODEL_DIGEST       = "sp_ai_model_digest";
    public static final String SP_AI_MODEL_ENRICH       = "sp_ai_model_enrich";
    public static final String SP_AI_MODEL_LEARN        = "sp_ai_model_learn";
    public static final String AI_MODEL_SAME_AS_TRIAGE  = "__same_as_triage__";
    public static final String AI_MODEL_OFF             = "__off__";

    public static final String PREF_AI_MANAGE_MODELS    = "pref_ai_manage_models";
    public static final String PREF_AI_DELETE_MODELS    = "pref_ai_delete_models";
    public static final String EDT_AI_HF_TOKEN          = "edt_ai_hf_token";

    public static final String SP_AI_RUN_TRIGGER        = "sp_ai_run_trigger";
    public static final String SP_AI_BATCH_BUDGET       = "sp_ai_batch_budget";
    public static final String SP_AI_MIN_BATTERY        = "sp_ai_min_battery";
    public static final String CB_AI_GPU_BACKEND        = "cb_ai_gpu_backend";
    public static final String CB_AI_DOWNLOADS_WIFI_ONLY = "cb_ai_downloads_wifi_only";
    public static final String CB_AI_THERMAL_PAUSE      = "cb_ai_thermal_pause";

    public static final String EDT_AI_INTERESTS         = "edt_ai_interests";
    public static final String PREF_AI_SUGGEST_INTERESTS = "pref_ai_suggest_interests";
    public static final String CB_AI_STAR_IS_LIKE       = "cb_ai_star_is_like";
    public static final String PREF_AI_RESET_TASTE      = "pref_ai_reset_taste";

    public static final String PREF_AI_LAST_RUN         = "pref_ai_last_run";
    public static final String CB_AI_DEBUG_LOG          = "cb_ai_debug_log";
    public static final String PREF_AI_DIGEST_NOTIFY    = "cb_ai_digest_notify";
    public static final String SP_AI_DIGEST_NOTIFY_TIME = "sp_ai_digest_notify_time";
```

### 5.2 `res/xml/pref_ai.xml` — complete, real

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.preference.PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">

    <PreferenceCategory
        app:title="@string/pref_header_ai"
        app:iconSpaceReserved="false">

        <SwitchPreference
            android:defaultValue="false"
            android:key="cb_ai_enabled"
            android:title="@string/pref_title_ai_enabled"
            android:summary="@string/pref_summary_ai_enabled"
            app:iconSpaceReserved="false" />

        <Preference
            android:key="pref_ai_settings"
            android:title="@string/pref_title_ai_settings"
            app:iconSpaceReserved="false" />

    </PreferenceCategory>

</androidx.preference.PreferenceScreen>
```

### 5.3 `res/xml/pref_ai_detail.xml` — hosted by `AiSettingsActivity`

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.preference.PreferenceScreen xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto">

    <PreferenceCategory app:title="@string/pref_header_ai_models" app:iconSpaceReserved="false">

        <ListPreference
            android:key="sp_ai_model_triage"
            android:title="@string/pref_title_ai_model_triage"
            android:defaultValue=""
            android:negativeButtonText="@null" android:positiveButtonText="@null"
            app:iconSpaceReserved="false" />

        <ListPreference
            android:key="sp_ai_model_embedding"
            android:title="@string/pref_title_ai_model_embedding"
            android:summary="@string/pref_summary_ai_model_embedding"
            android:defaultValue="embedding_gemma"
            android:negativeButtonText="@null" android:positiveButtonText="@null"
            app:iconSpaceReserved="false" />

        <ListPreference
            android:key="sp_ai_model_digest"
            android:title="@string/pref_title_ai_model_digest"
            android:defaultValue="__same_as_triage__"
            android:negativeButtonText="@null" android:positiveButtonText="@null"
            app:iconSpaceReserved="false" />

        <ListPreference
            android:key="sp_ai_model_enrich"
            android:title="@string/pref_title_ai_model_enrich"
            android:summary="@string/pref_summary_ai_model_enrich"
            android:defaultValue="__off__"
            android:negativeButtonText="@null" android:positiveButtonText="@null"
            app:iconSpaceReserved="false" />

        <ListPreference
            android:key="sp_ai_model_learn"
            android:title="@string/pref_title_ai_model_learn"
            android:defaultValue="__off__"
            android:negativeButtonText="@null" android:positiveButtonText="@null"
            app:iconSpaceReserved="false" />

        <Preference
            android:key="pref_ai_manage_models"
            android:title="@string/pref_title_ai_manage_models"
            app:iconSpaceReserved="false" />

        <Preference
            android:key="pref_ai_delete_models"
            android:title="@string/pref_title_ai_delete_models"
            app:iconSpaceReserved="false" />

        <EditTextPreference
            android:key="edt_ai_hf_token"
            android:title="@string/pref_title_ai_hf_token"
            android:summary="@string/pref_summary_ai_hf_token"
            android:defaultValue=""
            android:inputType="textPassword"
            android:singleLine="true"
            android:isPreferenceVisible="false"
            app:iconSpaceReserved="false" />
    </PreferenceCategory>

    <PreferenceCategory app:title="@string/pref_header_ai_when" app:iconSpaceReserved="false">

        <ListPreference
            android:key="sp_ai_run_trigger"
            android:title="@string/pref_title_ai_run_trigger"
            android:defaultValue="charging"
            android:entries="@array/pref_ai_run_trigger"
            android:entryValues="@array/pref_ai_run_trigger_values"
            android:negativeButtonText="@null" android:positiveButtonText="@null"
            app:iconSpaceReserved="false" />

        <ListPreference
            android:key="sp_ai_batch_budget"
            android:title="@string/pref_title_ai_batch_budget"
            android:summary="@string/pref_summary_ai_batch_budget"
            android:defaultValue="30"
            android:entries="@array/pref_ai_batch_budget_values"
            android:entryValues="@array/pref_ai_batch_budget_values"
            android:negativeButtonText="@null" android:positiveButtonText="@null"
            app:iconSpaceReserved="false" />

        <ListPreference
            android:key="sp_ai_min_battery"
            android:title="@string/pref_title_ai_min_battery"
            android:defaultValue="30"
            android:entries="@array/pref_ai_min_battery"
            android:entryValues="@array/pref_ai_min_battery_values"
            android:negativeButtonText="@null" android:positiveButtonText="@null"
            app:iconSpaceReserved="false" />

        <SwitchPreference
            android:key="cb_ai_gpu_backend"
            android:title="@string/pref_title_ai_gpu_backend"
            android:summary="@string/pref_summary_ai_gpu_backend"
            android:defaultValue="false"
            app:iconSpaceReserved="false" />

        <SwitchPreference
            android:key="cb_ai_downloads_wifi_only"
            android:title="@string/pref_title_ai_wifi_only"
            android:summary="@string/pref_summary_ai_wifi_only"
            android:defaultValue="true"
            app:iconSpaceReserved="false" />

        <SwitchPreference
            android:key="cb_ai_thermal_pause"
            android:title="@string/pref_title_ai_thermal_pause"
            android:defaultValue="true"
            app:iconSpaceReserved="false" />
    </PreferenceCategory>

    <PreferenceCategory app:title="@string/pref_header_ai_taste" app:iconSpaceReserved="false">

        <EditTextPreference
            android:key="edt_ai_interests"
            android:title="@string/pref_title_ai_interests"
            android:defaultValue=""
            android:inputType="textMultiLine|textCapSentences"
            app:iconSpaceReserved="false" />

        <Preference
            android:key="pref_ai_suggest_interests"
            android:title="@string/pref_title_ai_suggest_interests"
            app:iconSpaceReserved="false" />

        <SwitchPreference
            android:key="cb_ai_star_is_like"
            android:title="@string/pref_title_ai_star_is_like"
            android:summary="@string/pref_summary_ai_star_is_like"
            android:defaultValue="true"
            app:iconSpaceReserved="false" />

        <Preference
            android:key="pref_ai_reset_taste"
            android:title="@string/pref_title_ai_reset_taste"
            android:summary="@string/pref_summary_ai_reset_taste"
            app:iconSpaceReserved="false" />
    </PreferenceCategory>

    <PreferenceCategory app:title="@string/pref_header_ai_diagnostics" app:iconSpaceReserved="false">

        <SwitchPreference
            android:key="cb_ai_digest_notify"
            android:title="@string/pref_title_ai_digest_notify"
            android:defaultValue="false"
            app:iconSpaceReserved="false" />

        <ListPreference
            android:key="sp_ai_digest_notify_time"
            android:title="@string/pref_title_ai_digest_notify_time"
            android:defaultValue="08:00"
            android:entries="@array/pref_ai_digest_time_values"
            android:entryValues="@array/pref_ai_digest_time_values"
            android:dependency="cb_ai_digest_notify"
            android:negativeButtonText="@null" android:positiveButtonText="@null"
            app:iconSpaceReserved="false" />

        <Preference
            android:key="pref_ai_last_run"
            android:title="@string/pref_title_ai_last_run"
            android:selectable="false"
            app:iconSpaceReserved="false" />

        <SwitchPreference
            android:key="cb_ai_debug_log"
            android:title="@string/pref_title_ai_debug_log"
            android:summary="@string/pref_summary_ai_debug_log"
            android:defaultValue="false"
            app:iconSpaceReserved="false" />
    </PreferenceCategory>

</androidx.preference.PreferenceScreen>
```
Note `android:isPreferenceVisible` (preference 1.2.1, `build.gradle:137` — available) for the HF-token row, and `android:dependency` for the notify time. Every node carries `app:iconSpaceReserved="false"`, house convention.

### 5.4 `SettingsFragment.onCreatePreferences()` — `:93-103`

```java
        addPreferencesFromResource(R.xml.pref_general);
        bindGeneralPreferences(this);

        addPreferencesFromResource(R.xml.pref_display);
        bindDisplayPreferences(this);

        addPreferencesFromResource(R.xml.pref_data_sync);
        bindDataSyncPreferences(this);

        addPreferencesFromResource(R.xml.pref_about);
        bindAboutPreferences(this);
```
→ insert between data_sync and about:
```java
        addPreferencesFromResource(R.xml.pref_data_sync);
        bindDataSyncPreferences(this);

        addPreferencesFromResource(R.xml.pref_ai);
        bindAiPreferences(this);

        addPreferencesFromResource(R.xml.pref_about);
        bindAboutPreferences(this);
```

### 5.5 `bindAiPreferences()` — real code, placed next to `bindDataSyncPreferences():290`

```java
    private void bindAiPreferences(final PreferenceFragmentCompat prefFrag) {
        final SwitchPreference enabled  = prefFrag.findPreference(CB_AI_ENABLED);
        final Preference       aiScreen = prefFrag.findPreference(PREF_AI_SETTINGS);

        final AiAvailability.Tier tier = AiAvailability.tierOf(requireContext());

        if (tier == AiAvailability.Tier.UNSUPPORTED) {
            enabled.setEnabled(false);
            enabled.setChecked(false);
            aiScreen.setEnabled(false);
            aiScreen.setSummary(R.string.pref_summary_ai_unsupported);
            return;
        }

        bindPreferenceBooleanToValue(enabled);

        enabled.setOnPreferenceChangeListener((preference, newValue) -> {
            ((TwoStatePreference) preference).setChecked((Boolean) newValue);
            if (Boolean.TRUE.equals(newValue) && !AiModelRepository.hasAnyInstalledLlm(requireContext())) {
                startActivity(new Intent(requireContext(), AiModelManagerActivity.class));
            }
            return true;
        });

        aiScreen.setSummary(AiModelRepository.settingsSummary(requireContext(), mPrefs));
        aiScreen.setOnPreferenceClickListener(preference -> {
            startActivity(new Intent(requireContext(), AiSettingsActivity.class));
            return true;
        });
    }
```
Pattern for launching an Activity from a `Preference` row is the existing `PREF_TTS_SETTINGS` handler at `SettingsFragment.java:255-268`. Refresh the summary in `onResume()` so acceptance criterion 14 ("summary updates without reopening Settings") passes:
```java
    @Override
    public void onResume() {
        super.onResume();
        Preference aiScreen = findPreference(SettingsActivity.PREF_AI_SETTINGS);
        if (aiScreen != null && aiScreen.isEnabled()) {
            aiScreen.setSummary(AiModelRepository.settingsSummary(requireContext(), mPrefs));
        }
    }
```
`SettingsFragment` currently has no `onResume` — this is the first one.

### 5.6 Sub-screen: separate Activity, **not** `OnPreferenceStartFragmentCallback`

The detail screen is a `PreferenceFragmentCompat`, so both work. I go with the Activity because the model manager is a `RecyclerView` with determinate progress bars and per-row action buttons — not expressible as a preference list — so a second Activity is needed regardless, and `SettingsActivity`/`SettingsFragment` then stay untouched except for the two lines in §5.4.

```java
public class AiSettingsActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle b) {
        ThemeChooser.chooseTheme(this);
        super.onCreate(b);
        ThemeChooser.afterOnCreate(this);
        setContentView(R.layout.activity_settings);          // reuse: toolbar + @id/container
        Toolbar t = findViewById(R.id.toolbar);
        setSupportActionBar(t);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setTitle(R.string.pref_header_ai);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, new AiSettingsFragment()).commit();
    }
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }
}
```
`activity_settings.xml` is reused verbatim (`@+id/toolbar_layout` include + `@+id/container` FrameLayout — see the file). `AiSettingsFragment.onCreatePreferences` must repeat the two non-obvious lines from `SettingsFragment:84-87`:
```java
        ((NewsReaderApplication) requireActivity().getApplication()).getAppComponent().injectFragment(this);
        getPreferenceManager().setSharedPreferencesName(sharedPreferencesFileName);
        addPreferencesFromResource(R.xml.pref_ai_detail);
```
Missing `setSharedPreferencesName` is the classic failure here — the app uses the custom file `"<pkg>_preferences"` injected as `@Named("sharedPreferencesFileName")` (`di/ApiModule.java:46-51`), and without that line the AI prefs land in a different file from every other pref and nothing reads them.

**`di/AppComponent.java` needs three new methods** (it has an explicit `injectXxx` per type, `:32-56`):
```java
    void injectActivity(AiSettingsActivity activity);
    void injectActivity(AiModelManagerActivity activity);
    void injectActivity(AiDigestActivity activity);
    void injectFragment(AiSettingsFragment fragment);
```

If the team overrules this and wants a nested `PreferenceScreen`, the whole change is:
```java
public class SettingsActivity extends AppCompatActivity
        implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {
    @Override public boolean onPreferenceStartFragment(PreferenceFragmentCompat caller, Preference pref) {
        Fragment f = getSupportFragmentManager().getFragmentFactory()
                .instantiate(getClassLoader(), pref.getFragment());
        Bundle args = pref.getExtras();
        args.putString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT, pref.getKey());
        f.setArguments(args);
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.container, f).addToBackStack(null).commit();
        return true;
    }
}
```
plus `android:fragment="de.luhmer.owncloudnewsreader.AiSettingsFragment"` on the `pref_ai_settings` node. It still does not get you the model manager.

---

## 6. Model download UI

**A `Preference` row opening a plain Activity.** `pref_ai_manage_models` → `AiModelManagerActivity extends AppCompatActivity` + ViewBinding. Not a preference screen.

`res/layout/activity_ai_model_manager.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent" android:layout_height="match_parent"
    android:fitsSystemWindows="true" android:orientation="vertical">

    <include android:id="@+id/toolbar_layout" layout="@layout/toolbar_layout" />

    <TextView android:id="@+id/storage_summary"
        android:layout_width="match_parent" android:layout_height="wrap_content"
        android:padding="@dimen/spacer_2x" android:textSize="13sp"
        android:textColor="?attr/colorOnSurfaceVariant"
        tools:text="2 installed · 2.8 GB used · 12.4 GB free" />

    <androidx.swiperefreshlayout.widget.SwipeRefreshLayout
        android:id="@+id/swipeRefresh"
        android:layout_width="match_parent" android:layout_height="0dp"
        android:layout_weight="1">
        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/model_list"
            android:layout_width="match_parent" android:layout_height="match_parent"
            android:scrollbars="vertical"
            tools:listitem="@layout/ai_model_row" />
    </androidx.swiperefreshlayout.widget.SwipeRefreshLayout>

    <include android:id="@+id/empty" layout="@layout/empty_content_view" />
</LinearLayout>
```

`res/layout/ai_model_row.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:layout_width="match_parent" android:layout_height="wrap_content"
    android:minHeight="88dp"
    android:paddingStart="@dimen/spacer_2x" android:paddingEnd="@dimen/spacer_1x"
    android:paddingTop="@dimen/spacer_1x" android:paddingBottom="@dimen/spacer_1x"
    android:background="?attr/selectableItemBackground">

    <TextView android:id="@+id/model_name"
        android:layout_width="0dp" android:layout_height="wrap_content"
        android:textSize="16sp" android:textStyle="bold" android:maxLines="1" android:ellipsize="end"
        android:textColor="?attr/primaryTextColor"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toStartOf="@id/model_action"
        app:layout_constraintTop_toTopOf="parent"
        tools:text="Gemma 4 E2B" />

    <TextView android:id="@+id/model_purpose"
        android:layout_width="0dp" android:layout_height="wrap_content"
        android:layout_marginTop="2dp" android:textSize="13sp"
        android:textColor="?attr/colorOnSurfaceVariant" android:maxLines="1" android:ellipsize="end"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toStartOf="@id/model_action"
        app:layout_constraintTop_toBottomOf="@id/model_name"
        tools:text="Triage &amp; digests · 2.58 GB" />

    <com.google.android.material.chip.Chip android:id="@+id/model_state_chip"
        style="@style/Widget.Material3.Chip.Assist"
        android:layout_width="wrap_content" android:layout_height="wrap_content"
        android:layout_marginTop="6dp" android:clickable="false"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintTop_toBottomOf="@id/model_purpose"
        tools:text="Recommended" />

    <com.google.android.material.progressindicator.LinearProgressIndicator
        android:id="@+id/model_progress"
        android:layout_width="0dp" android:layout_height="wrap_content"
        android:layout_marginTop="6dp" android:visibility="gone"
        app:indicatorColor="?attr/colorPrimary"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toStartOf="@id/model_action"
        app:layout_constraintTop_toBottomOf="@id/model_state_chip"
        tools:visibility="visible" tools:progress="41" />

    <com.google.android.material.button.MaterialButton android:id="@+id/model_action"
        style="@style/Widget.Material3.Button.TonalButton"
        android:layout_width="wrap_content" android:layout_height="wrap_content"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintTop_toTopOf="parent"
        tools:text="@string/ai_model_download" />

    <ImageButton android:id="@+id/model_overflow"
        android:layout_width="40dp" android:layout_height="40dp"
        android:background="?attr/selectableItemBackgroundBorderless"
        android:contentDescription="@string/ai_model_more_options"
        android:src="@drawable/ic_more_vert_24"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintTop_toBottomOf="@id/model_action" />
</androidx.constraintlayout.widget.ConstraintLayout>
```

- One `Chip` carries the whole state vocabulary (`Recommended` / `Installed` / `In use` / `Paused at 41%` / `Needs ~4.9 GB RAM` / `Needs 1.2 GB more space` / `Broken`). `LinearProgressIndicator` is `visibility="gone"` except during download.
- Progress arrives via **EventBus** (already a dependency, and `RssItemViewHolder:367-375` uses exactly this pattern for `PodcastDownloadService.DownloadProgressUpdate`) — a `AiModelDownloadProgressEvent(modelId, receivedBytes, totalBytes)` posted from the download service, `@Subscribe` in the model-row holder, registered/unregistered from `onViewAttachedToWindow`/`onViewDetachedFromWindow` like `NewsListRecyclerAdapter:303-314`. No polling, no LiveData, no new dependency.
- Licence gate: `androidx.browser:1.9.0` is on the classpath (`build.gradle:145`) and the app already has `chrometabs/` — open `https://huggingface.co/<repo>` in a Custom Tab, then re-check on `onResume`.
- Delete confirm and multi-select: `androidx.appcompat.app.AlertDialog.Builder(...).setMultiChoiceItems(...)`, same builder the app uses at `SettingsFragment:347-362`.

---

## 7. New string resources — `res/values/strings.xml` (default file only; Transifex owns translations)

```xml
    <!-- AI triage -->
    <string name="ai_for_you">For you</string>
    <string name="action_ai_more_like_this">More like this</string>
    <string name="action_ai_less_like_this">Less like this</string>
    <string name="ai_snack_more_like_this">More like this</string>
    <string name="ai_snack_less_like_this">Less like this</string>
    <string name="ai_snack_undo">Undo</string>

    <!-- onboarding / status strip -->
    <string name="ai_onboarding_title">Learning what you like</string>
    <string name="ai_onboarding_body">Swipe right on articles worth your time, left on the rest.</string>
    <string name="ai_onboarding_progress">%1$d of %2$d</string>
    <string name="ai_onboarding_seed_from_stars">Use my starred articles</string>
    <string name="ai_divider_not_personalised">Most recent — not yet personalised</string>
    <string name="ai_strip_off">AI triage off</string>
    <string name="ai_strip_off_action">Set up</string>
    <string name="ai_strip_downloading">Downloading %1$s · %2$s</string>
    <string name="ai_strip_similarity_only">Ranking by similarity. Add a scoring model for scores and summaries.</string>
    <string name="ai_strip_scoring">Scoring %1$d of %2$d…</string>
    <string name="ai_strip_waiting_charger">Waiting for charger</string>
    <string name="ai_strip_run_now">Run now</string>
    <string name="ai_strip_unsupported">This device can\'t run on-device AI</string>
    <string name="ai_strip_why">Why?</string>
    <string name="ai_strip_failed">Scoring failed</string>
    <string name="ai_strip_retry">Retry</string>
    <string name="ai_strip_smaller_model">Try a smaller model</string>

    <!-- empty states -->
    <string name="ai_empty_no_model_title">AI triage is off</string>
    <string name="ai_empty_no_model_desc">Download a model in Settings → AI to sort your feed by what matters to you.</string>
    <string name="ai_empty_nothing_title">Nothing worth your time in this batch</string>
    <string name="ai_empty_nothing_desc">%1$d new articles were scored. None matched your interests. They\'re all still in Unread.</string>
    <string name="ai_empty_done_title">You\'re through For you</string>
    <string name="ai_empty_done_desc">The next batch arrives after the next sync.</string>
    <string name="ai_empty_unsupported_title">This device can\'t run on-device AI</string>
    <string name="ai_empty_unsupported_desc">For you needs 4 GB of RAM. Everything else works normally.</string>
    <string name="ai_empty_action_setup">Set up</string>

    <!-- digest -->
    <string name="ai_digest_title">Digest</string>
    <string name="ai_digest_overline">DIGEST · %1$s · %2$d selected</string>
    <string name="ai_digest_read">Read digest</string>
    <string name="ai_digest_dismiss">Dismiss today\'s digest</string>
    <string name="ai_digest_mark_all_read">Mark all as read</string>
    <string name="ai_digest_theme_count">%1$s %2$d</string>

    <!-- settings: main screen -->
    <string name="pref_header_ai">AI triage</string>
    <string name="pref_title_ai_enabled">Enable on-device AI triage</string>
    <string name="pref_summary_ai_enabled">Rank and filter new articles with a model running on this phone. Nothing is sent anywhere.</string>
    <string name="pref_title_ai_settings">AI settings</string>
    <string name="pref_summary_ai_unsupported">Not supported on this device (needs a 64-bit CPU, 3 GB RAM, Android 7+)</string>
    <string name="pref_summary_ai_no_model">No model installed</string>
    <string name="pref_summary_ai_installed">%1$s · %2$d models · %3$s</string>

    <!-- settings: detail screen -->
    <string name="pref_header_ai_models">Models</string>
    <string name="pref_header_ai_when">When to run</string>
    <string name="pref_header_ai_taste">What I like</string>
    <string name="pref_header_ai_diagnostics">Diagnostics</string>
    <string name="pref_title_ai_model_triage">Triage model</string>
    <string name="pref_title_ai_model_embedding">Similarity model</string>
    <string name="pref_summary_ai_model_embedding">Used to learn what you like. Required.</string>
    <string name="pref_title_ai_model_digest">Digest writer</string>
    <string name="pref_title_ai_model_enrich">Article summaries</string>
    <string name="pref_summary_ai_model_enrich">Reads the full article to write a longer summary. Slow; runs only while charging.</string>
    <string name="pref_title_ai_model_learn">Interest-learning model</string>
    <string name="ai_model_same_as_triage">Same as triage model</string>
    <string name="ai_model_off">Off</string>
    <string name="ai_model_download_more">Download a model…</string>
    <string name="ai_model_recommended_for_device">Recommended for this device</string>
    <string name="pref_title_ai_manage_models">Download models</string>
    <string name="pref_summary_ai_manage_models">%1$d installed · %2$s used · %3$s free</string>
    <string name="pref_title_ai_delete_models">Delete downloaded models</string>
    <string name="pref_summary_ai_delete_models">Frees up to %1$s</string>
    <string name="pref_title_ai_hf_token">Hugging Face access token</string>
    <string name="pref_summary_ai_hf_token">Only needed if a download keeps failing after you accept the licence.</string>
    <string name="pref_title_ai_run_trigger">Run triage</string>
    <string name="pref_title_ai_batch_budget">Articles to score each run</string>
    <string name="pref_summary_ai_batch_budget">More articles, better coverage, longer run.</string>
    <string name="pref_title_ai_min_battery">Skip when battery is below</string>
    <string name="pref_title_ai_gpu_backend">Use GPU acceleration</string>
    <string name="pref_summary_ai_gpu_backend">Faster, but warms the phone. Turn off if triage crashes.</string>
    <string name="pref_title_ai_wifi_only">Download models on Wi-Fi only</string>
    <string name="pref_summary_ai_wifi_only">Models are 0.2–3.7 GB.</string>
    <string name="pref_title_ai_thermal_pause">Pause when the phone gets hot</string>
    <string name="pref_title_ai_interests">My interests</string>
    <string name="pref_summary_ai_interests_empty">Not set — ranking uses only what you star and skip.</string>
    <string name="pref_title_ai_suggest_interests">Suggest an update from my choices</string>
    <string name="pref_summary_ai_suggest_interests">%1$d of %2$d choices since the last update</string>
    <string name="pref_title_ai_star_is_like">Treat starred articles as liked</string>
    <string name="pref_summary_ai_star_is_like">Your stars stay on your Nextcloud server; the AI just reads them.</string>
    <string name="pref_title_ai_reset_taste">Forget what I like</string>
    <string name="pref_summary_ai_reset_taste">Clears the AI\'s learned preferences. Your stars are not touched.</string>
    <string name="pref_title_ai_digest_notify">Daily digest notification</string>
    <string name="pref_title_ai_digest_notify_time">Notification time</string>
    <string name="pref_title_ai_last_run">Last run</string>
    <string name="pref_summary_ai_last_run">%1$s · %2$d scored, %3$d selected · %4$s</string>
    <string name="pref_summary_ai_last_run_skipped">Skipped: %1$s</string>
    <string name="pref_title_ai_debug_log">Keep a diagnostic log</string>
    <string name="pref_summary_ai_debug_log">Records prompts and replies to a file you can attach to a bug report.</string>

    <!-- model manager -->
    <string name="ai_models_title">Models</string>
    <string name="ai_model_download">Download</string>
    <string name="ai_model_resume">Resume</string>
    <string name="ai_model_cancel">Cancel</string>
    <string name="ai_model_delete">Delete</string>
    <string name="ai_model_more_options">More options</string>
    <string name="ai_model_chip_recommended">Recommended</string>
    <string name="ai_model_chip_installed">Installed</string>
    <string name="ai_model_chip_in_use">In use</string>
    <string name="ai_model_chip_paused">Paused at %1$d%%</string>
    <string name="ai_model_chip_needs_ram">Needs ~%1$s RAM</string>
    <string name="ai_model_chip_needs_space">Needs %1$s more space</string>
    <string name="ai_model_chip_broken">Broken</string>
    <string name="ai_model_purpose_triage">Triage &amp; digests</string>
    <string name="ai_model_purpose_similarity">Similarity</string>
    <string name="ai_model_progress">%1$s / %2$s</string>
    <string name="ai_model_try_anyway">Try anyway</string>
    <string name="ai_model_try_anyway_warning">The app may be killed while this model loads.</string>
    <string name="ai_model_import_local">Import a model file…</string>

    <!-- licence gate -->
    <string name="ai_licence_title">Accept the Gemma terms</string>
    <string name="ai_licence_body">Google requires you to accept the Gemma licence on huggingface.co before downloading. We\'ll open your browser — accept, then come back and tap Download again.</string>
    <string name="ai_licence_open_browser">Open in browser</string>
    <string name="ai_licence_retry">I\'ve accepted — retry</string>
    <string name="ai_licence_not_granted">Access not granted yet — make sure you were signed in to Hugging Face when you accepted.</string>

    <!-- errors -->
    <string name="ai_err_corrupt">Download was corrupted</string>
    <string name="ai_err_incompatible">This model doesn\'t work with this version of the app</string>
    <string name="ai_err_no_space">Not enough space — free about %1$s and try again</string>
    <string name="ai_err_low_memory">Not enough free memory right now — close some apps and retry.</string>
    <string name="ai_err_model_changed">This model changed on Hugging Face; update the app</string>
    <string name="ai_err_file_changed">The file changed on the server; starting over.</string>
    <string name="ai_toast_triage_skipped">Triage will be skipped until you choose another model.</string>
    <string name="ai_snack_ready">AI triage is ready</string>
    <string name="ai_snack_run_now">Run now</string>

    <!-- notification channel -->
    <string name="ai_notification_channel_download">Model downloads</string>
    <string name="ai_notification_channel_triage">AI triage</string>
    <string name="ai_notification_digest_title">Your digest is ready</string>
```
Arrays (also `res/values/strings.xml`, `translatable="false"` on the value arrays, following the `pref_general_swipe_action` precedent at `:167-178`):
```xml
    <string-array name="pref_ai_run_trigger">
        <item>After every sync</item>
        <item>Only while charging</item>
        <item>Only when I open the AI folder</item>
        <item>Only when I ask</item>
    </string-array>
    <string-array name="pref_ai_run_trigger_values" translatable="false">
        <item>always</item><item>charging</item><item>on_open</item><item>manual</item>
    </string-array>
    <string-array name="pref_ai_batch_budget_values" translatable="false">
        <item>15</item><item>30</item><item>60</item><item>120</item>
    </string-array>
    <string-array name="pref_ai_min_battery">
        <item>Never skip</item><item>15%</item><item>30%</item><item>50%</item>
    </string-array>
    <string-array name="pref_ai_min_battery_values" translatable="false">
        <item>0</item><item>15</item><item>30</item><item>50</item>
    </string-array>
    <string-array name="pref_ai_digest_time_values" translatable="false">
        <item>06:00</item><item>07:00</item><item>08:00</item><item>09:00</item>
        <item>12:00</item><item>18:00</item><item>20:00</item>
    </string-array>
```
Three gotchas: `%` must be `%%` (`ai_model_chip_paused`), apostrophes must be escaped `\'`, `&` must be `&amp;`. `lint` runs with `abortOnError true` (`build.gradle:94-99`) and `StringFormatInvalid`/`PluralsCandidate` are error-severity — the `%1$d of %2$d` counters are candidates for `<plurals>`; use `getQuantityString` where the noun is countable, following `R.plurals.marked_as_read_message` at `NewsReaderDetailFragment:881`.

---

## 8. Pushback

Six places where the product spec does not fit this codebase without work it did not budget for.

**8.1 "Rows fill in with score pill + why as the batch completes" (§4, row-level progressive fill) — cut it from v1.**
`NewsListRecyclerAdapter` has no observer, no `ListAdapter`, no `DiffUtil`; its only update paths are `notifyDataSetChanged()` (`:397`, `:441`, `:504`) and `notifyItemRangeInserted` after a page load (`:473`). A live "Scoring 23 of 60…" that repaints individual rows means adding an EventBus subscriber that maps `rssItemId → adapter position` and calls `notifyItemChanged`. Doable, but it fights the exact same "content shifting under the thumb" that §4 forbids, because `notifyItemChanged` on a `wrap_content` row whose why-line appears **does** change its height and does push the rows below it. Ship: shimmer bar stays until the user leaves and re-enters the folder or pulls to refresh. The status strip's counter can update live (it is one fixed-height row).

**8.2 "For you is the intended default destination" — no.**
`NewsReaderListActivity:446`/`:584` land on `ALL_UNREAD_ITEMS`. Changing that breaks acceptance criterion 30 for every user who never enables AI, and lands a fresh install on a recency list under an AI header — the exact "unrecoverable first impression" §2 argues against. Drawer position 0 is the discovery mechanism; the default destination is not.

**8.3 The status strip cannot be "one line at the top" without either restructuring a shared layout or scrolling away.**
`fragment_newsreader_detail.xml` is `FrameLayout > SwipeRefreshLayout > RecyclerView`, with the empty view, the progress bar and the FAB as overlay siblings. A *sticky* strip requires converting that to a vertical `LinearLayout` wrapper, which changes the geometry of the empty view and the FAB anchor for **every** folder. I ship the strip as a `ConcatAdapter` header — it scrolls away. That is a real UX regression versus the spec (a user scrolled 20 rows down cannot see "Scoring failed") and product should know it is the trade being made. If sticky is required, budget the layout restructure plus a re-test of the FAB drag interaction (`FastMarkReadMotionListener`, `:730-887`, computes absolute screen coordinates).

**8.4 Two new fast-action buttons make the bar 5 wide.**
`activity_news_detail.xml:56-61` places the bar `alignParentBottom|alignParentEnd` with 16dp margins, `wrap_content`, each button 56dp. Five buttons = 312dp with margins, which clips on a 320dp-wide device and leaves 48dp on a 360dp one. Mitigation shipped above: default both to `GONE`, show only when `cb_ai_enabled`. If someone later wants them always visible, the bar needs the collapse toggle that is commented out at `:16-22` of `widget_fastactions_detailview.xml` and `:271-291` of `NewsDetailActivity` resurrected.

**8.5 The digest card's theme-chip filter re-runs the whole two-step query.**
There is no in-memory filtering path; "filter the list below" means rebuilding `CURRENT_RSS_ITEM_VIEW` via `insertIntoRssCurrentViewTable` (which does `deleteAll()` + `INSERT…SELECT` inside a transaction, `:660-679`) and re-running `getCurrentRssItemView(0)`. On a 3000-row cache that is ~50–150 ms on a mid-range phone, off the main thread, with a full `notifyDataSetChanged()` and a scroll-to-0. So chip taps are a **list rebuild**, not a filter — acceptable at 5–20 items, but it will flash. Cheaper alternative worth considering: make the chips scroll the list to the first item of that theme instead of filtering.

**8.6 "Undo returns the row to its original position" is only true within one session.**
`applyAiDecision` re-inserts into the in-memory `lazyList` at the captured index; the row's position in `CURRENT_RSS_ITEM_VIEW` is *not* restored (that table is only rewritten by `UpdateCurrentRssViewTask`). So Undo followed by a pull-to-refresh puts the article back at its rank-ordered position, which happens to be correct — but Undo followed by a lazy-load page fetch (`LoadMoreItemsAsyncTask`, `:452-479`, pages on `C._id > page*25`) will duplicate it if the restored row pushed the boundary. Guard: after `restoreItemAt`, if `lazyList.size() > cachedPages * 25`, drop the last element. Test AC 7 with 30+ items in the folder, not 5.

**Two bugs found on the way that must be fixed in the same PR** (both crash paths the AI feature opens up, both latent today):
- `NewsReaderDetailFragment.java:423` — unchecked `(RssItemViewHolder)` cast on `findViewHolderForLayoutPosition(i)`; `:436` shows the correct pattern.
- `NewsReaderListActivity.DownloadMoreItems()` — `dbConn.getFolderById(idFolder).getFeedList()` NPEs for any special folder not in the `specialFolders` list; `-13` already hits it.