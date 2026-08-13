## Files created

| File (absolute) | Purpose |
|---|---|
| `/home/yohann/dev/padam/news-android-ai/News-Android-App/src/main/java/de/luhmer/owncloudnewsreader/ai/AiCapability.java` | The device gate. `hasArm64()`/`hasX8664()`/`abiSupported()` (x86_64 accepted **only** when `BuildConfig.DEBUG`), `totalRam()` via `ActivityManager.MemoryInfo.totalMem`, `tier()` (T0 <3 GB, T1 3–6, T2 ≥6), `isSupported()` = the drawer-row gate, plus `selectOk(ctx, minTotalRamBytes)` and `lowMemoryRightNow()`. Java, no AI runtime types → compiles unchanged in `mlNone`. |
| `.../main/java/de/luhmer/owncloudnewsreader/database/ai/AiDebugSeed.java` | Debug-only `AI_SCORE` seeder. Deterministic score/sim from `aiKey.hashCode()`, groups by `AI_KEY` first and writes through `AiScoreStore.writeResult()` so the **fan-out path is what gets exercised**. `DEBUG_SYNC_ID = -1` marks its rows; `clear()` removes exactly those. |
| `.../main/res/drawable/ic_ai_sparkle_24dp_theme_aware.xml` | Drawer icon, modelled on `ic_star_border_24dp_theme_aware.xml` (`android:tint="?attr/colorControlNormal"`). |
| `.../src/test/java/.../database/ai/AiFolderSqlTest.java` | 14 tests, Robolectric + real SQLite. |
| `.../src/test/java/de/luhmer/owncloudnewsreader/ai/AiCapabilityTest.java` | 6 tests (tier boundaries, 32-bit rejection, x86_64-in-debug). Not requested for Phase 2 but the drawer gate was otherwise untested. |

## Files edited

- `ListView/SubscriptionExpandableListAdapter.java` — `AI_FOR_YOU(-14)` in `SPECIAL_FOLDERS`; static import; drawer row inserted **as element 0** of `mCategories` in `loadCategoriesAndItemsFromDatabase()` behind `AiCapability.isSupported(mContext)`, `idFolder = null`; icon + `imgViewExpandableIndicator GONE` branch inserted **before** the starred branch in `getGroupView()`. No children branch (`feedItemList` stays null → `getChildrenCount()==0`).
- `database/DatabaseConnectionOrm.java` — the early-return AI branch in `getAllItemsIdsForFolderSQL()`; `AI_FOR_YOU` added to the exclusion set in the first `if`; `getAiSelectedUnreadCount()`; the one badge line after the `ALL_STARRED_ITEMS` put; `aiDebugSeedScores(int)` / `aiDebugClearScores()`; **a new `@VisibleForTesting public DatabaseConnectionOrm(Context, DaoSession)`** (see deviations).
- `NewsReaderDetailFragment.java` — `updateMenuItemsState()` disables "download more items" for `-14`; the `:423` unchecked `(RssItemViewHolder)` cast replaced with `instanceof RssItemViewHolder vh`; comment at the `getAllItemsIdsForFolderSQL` call site stating why there is no AI branch and why `sortDirection` is dropped.
- `NewsReaderListActivity.java` — `AI_FOR_YOU` (and `ALL_DOWNLOADED_PODCASTS`) added to `DownloadMoreItems()`'s `specialFolders`; `-14` title branch in **both** `updateDetailFragment()` and `updateDetailFragmentTitle()` (there are two copies of that if-chain, the plan only names one); new `menuItemSearch` field + `syncMenuItemSearch()` called from `syncMenuItemUnreadOnly()` to hide search in `-14`; the two debug menu handlers + `seedAiScores()`.
- `res/menu/news_reader.xml` — `menu_ai_seed_scores` / `menu_ai_clear_scores`, `android:visible="false"`, flipped to visible in `onCreateOptionsMenu` when `BuildConfig.DEBUG` (same shape as `menu_CreateDatabaseDump`).
- `res/values/strings.xml` — `<string name="ai_for_you">For you</string>`.

## The SQL, verbatim as generated (asserted character-for-character)

```
SELECT RSS_ITEM._id FROM RSS_ITEM JOIN AI_SCORE ON AI_SCORE.RSS_ITEM_ID = RSS_ITEM._id WHERE AI_SCORE.STATUS = 'selected' ORDER BY AI_SCORE.RANK_SCORE DESC, RSS_ITEM.PUB_DATE DESC, RSS_ITEM._id DESC
```
(`onlyUnread` inserts ` AND RSS_ITEM.READ_TEMP != 1` before the `ORDER BY`.) No aliases, exactly one `ORDER BY`, `sortDirection` dropped.

`AiFolderSqlTest` asserts: both exact strings; exactly one `ORDER BY` literal; token-after-`FROM RSS_ITEM` is `JOIN` and token-after-`JOIN AI_SCORE` is `ON` (no aliases, plus no single-letter token anywhere); `asc`≡`desc`; a real folder still emits `WHERE f._id = 7` and `-14` never emits `f._id`; the `" GROUP BY FINGERPRINT "` injection copied verbatim from the fragment parses and `FINGERPRINT` resolves unqualified; `insertIntoRssCurrentViewTable` → rank order `[3,1,2]` with `CURRENT_RSS_ITEM_VIEW._id` = `[1,2,3]`; `getCurrentRssItemView(0)` returns the same order; a duplicate-fingerprint pair yields **one** row still at rank 0; `onlyUnread` honoured both ways; `queued`/`discarded`/unscored articles absent; rank ties fall back to PUB_DATE then `_id`; and the debug seeder produces a non-increasing-rank list that `clear()` empties.

## Commands run — real outcomes

```
./gradlew :News-Android-App:compileOssMlGemmaDebugJavaWithJavac      -> BUILD SUCCESSFUL in 31s
./gradlew :News-Android-App:testOssMlGemmaDebugUnitTest --tests "*AiFolderSql*"
      -> first run: "13 tests completed, 1 failed" (noTableIsAliased, my regex was wrong,
         `.*\bFROM RSS_ITEM [A-Za-z].*` matches "FROM RSS_ITEM JOIN"); rewrote as a token check
      -> BUILD SUCCESSFUL
./gradlew :News-Android-App:assembleOssMlGemmaDebug :News-Android-App:assembleOssMlNoneDebug \
          :News-Android-App:testOssMlNoneDebugUnitTest :News-Android-App:testOssMlGemmaDebugUnitTest \
          detekt spotlessCheck :News-Android-App:lint
      -> BUILD SUCCESSFUL in 1m 34s  (143 tasks)   [also passed at 2m 33s on the previous iteration]
```

Test counts, identical in both variants (the whole AI layer is in `src/main`):
```
AiCapabilityTest            tests= 6 failures=0 errors=0   (new)
AiFolderSqlTest             tests=14 failures=0 errors=0   (new)
AiDecisionStateMachineTest  tests=15   AiEmbeddingStoreTest tests=17
AiGcTest tests=4   AiKeysTest tests=7   AiSchemaTest tests=5   AiScoreStoreTest tests=8
pre-existing: RssItemToHtmlTaskTest 3, ImageHandlerTest 8, TtsTextSplitterTest 8 — all green
```

## D6 verified against the real code — the plan is right

- `getGroupView()` reads `unreadCountFolders.get((int) group.id_database)` generically for every `idFolder == null` row (`SubscriptionExpandableListAdapter.java:279-284`). The AI row has `idFolder == null`, so no branch at `:286` is needed.
- The only-unread purge (`NotifyDataSetChangedAsyncTask.onPostExecute`) removes a `FolderSubscribtionItem` only when `unreadCountFoldersTemp.get(id) == null`. `getAiSelectedUnreadCount()` returns a non-null `"0"`, so the row never disappears — same mechanism that keeps Starred alive. No exemption needed.

**One line, no extra branches. Confirmed, not assumed.**

## Deviations / judgement calls — read these

1. **New `@VisibleForTesting public DatabaseConnectionOrm(Context, DaoSession)`.** The existing constructor goes through `NewsReaderApplication.getAppComponent().injectDatabaseConnection(this)` and the static `DatabaseHelperOrm.getDaoSession` singleton, i.e. a file-backed DB and Dagger. `AiFolderSqlTest` has to drive the *real* `getAllItemsIdsForFolderSQL` + `insertIntoRssCurrentViewTable` + `getCurrentRssItemView`, so it binds an explicit session over `SQLiteDatabase.create(null)`. Two lines in production code; the alternative was reflection or a fake reimplementation of the query, both of which would have made the highest-value test meaningless.
2. **`ALL_DOWNLOADED_PODCASTS(-13)` also added to `DownloadMoreItems()`'s `specialFolders`.** The plan only mandates `-14` but names `-13` as hitting the same NPE today. Adding it turns a crash into `startSync()`. This is a *behaviour change in the `mlNone` variant* (crash → sync) — flag it if byte-identical mlNone behaviour matters more than the crash.
3. **Two `-13` title chains, not one.** `updateDetailFragment():~886` and `updateDetailFragmentTitle():~786` are duplicated code; the plan cites only the first. Both patched, otherwise the title reverts to null on rotation/refresh.
4. **Search hidden via a new `menuItemSearch` field + `syncMenuItemSearch()`** hung off `syncMenuItemUnreadOnly()` (which is already called from every relevant place), rather than inline in `onCreateOptionsMenu()` — the menu is created once, the folder changes many times.
5. **`getAiSelectedUnreadCount()` guards on `AiSchema.isReady()` and catches `Throwable`.** A broken AI table must not take the whole sidebar down. Same shape as `aiGarbageCollect()`.
6. **`AI_FOR_YOU` added to the exclusion set even though the branch early-returns.** Belt and braces, with a comment: if the early return is ever removed, `-14` lands in the "all items" tail rather than `WHERE f._id = -14`.
7. **Debug seed is two menu items, not one** (seed + clear). Seeding 200 articles is one tap; `Clear AI scores` restores an empty folder. Both `BuildConfig.DEBUG`-gated at runtime, item `android:visible="false"` in XML.

## Left undone (deliberately, per the task)

- **D31 `ConcatAdapter` + the ten `(NewsListRecyclerAdapter) getAdapter()` cast→field conversions, `ai_list_item.xml`, `RssItemAiViewHolder.kt`, the four empty states, adapter selection by `idFolder`.** The list renders with the stock `NewsListRecyclerAdapter` today — no why-line, no status strip, no digest card. The `:423` cast fix is in, so a header at position 0 will no longer `ClassCastException` on the mark-as-read-while-scrolling path — but `onSwiped()` (`~:664-676`) still has four unchecked `(RssItemViewHolder)` casts. **Phase 3 must add the `if (!(viewHolder instanceof RssItemViewHolder)) return;` guard before the header ships.**
- **No instrumented/Robolectric test of the drawer row itself.** `AiCapability` is tested; that `loadCategoriesAndItemsFromDatabase()` puts the row at index 0 is not.
- `getAllItemsIdsForFolderSQLSearch()` untouched — search is hidden for `-14` instead (doc 07 §1.6). `-13`'s pre-existing broken search is left broken.

## What the next phase must know

- **`AI_SCORE.STATUS='selected'` is the only thing the list looks at.** `RANK_SCORE` orders it; `PUB_DATE`/`_id` break ties. An article with a score row but no `RANK_SCORE` sorts as NULL (last under `DESC`).
- **The fan-out is now load-bearing and observable.** `AiFolderSqlTest.aDuplicateFingerprintYieldsExactlyOneRowAtTheCorrectRank` passes because both duplicates carry rank 2.9. Write results with `AiScoreStore.writeResult(row)` and set `row.aiKey` — a `Row` with only `rssItemId` updates nothing (Phase 1's warning still stands).
- **APK byte sizes are not comparable across sessions.** `ossMlNone` now measures 15,709,223 B vs the 13,495,227 B Phase 1 recorded. I rebuilt the *untouched* `devMlNone` variant and it went 13,444,099 → 15,658,103 with no source change, so ~2.2 MB of it is zip padding from a fresh `package` task, not code. Real content delta of this phase: **+53,932 B uncompressed / +24,261 B compressed**, one new file (`res/drawable/ic_ai_sparkle_24dp_theme_aware.xml`).
- `AiCapability.isSupported()` currently gates on device only. `cb_ai_enabled` / `AiFeature.isEnabled()` does not exist yet — when Phase 5 adds it, the drawer row gate is the line to `&&` it into (`SubscriptionExpandableListAdapter.loadCategoriesAndItemsFromDatabase()`).
- The debug seeder writes `SYNC_ID = -1`. If Phase 4/6 uses negative sync ids for anything, change `AiDebugSeed.DEBUG_SYNC_ID`.