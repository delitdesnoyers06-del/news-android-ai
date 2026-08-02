# Phases 7 + 8 — the digest and the interests/taste note

All paths absolute under `/home/yohann/dev/padam/news-android-ai/News-Android-App/src/`.

## Files created

### Phase 7 — digest
| File | Purpose |
|---|---|
| `main/java/…/ai/AiDigestBuilder.java` | Pure grouping. Range filter on **selection time** (`AI_SCORE.SCORED_AT`), exclusive low / inclusive high; dedupe by `AI_KEY`; each item once under `themes[0]`; blocks by `−sum(rank_score)`, tie → theme name; untagged block forced **last**. veille's country axis dropped. |
| `main/java/…/ai/AiDigests.java` | The DB half: `dayKey`, `ensureToday` (pure SQL, no inference), `selectedInRange`, `entries`, `chips`, `currentViewSql`, `themeFilterClause`, `shareMarkdown`, dismiss, and the `currentViewDirty` flag (see deviation 4). |
| `main/java/…/ai/prompt/AbstractGuard.java` | Fence/heading/preamble/pleasantry stripping, ≤6 sentences, **<80 chars or letter-free ⇒ return null = render the digest without one**. Also `itemsBlock` (25 items / 3000 chars + "(and k more)") and `briefBlock`. |
| `main/java/…/ai/AiAbstract.java` | One greedy free-text call, no repair turn, never throws, returns `null` for every failure mode. Testable with `FakeLlm`. |
| `mlGemma/…/ai/work/AiDigestWorker.java` | Fills `ABSTRACT_STATE` pending→ok/skipped/failed. Exactly one `return Result.success()`. |
| `main/java/…/ai/ui/AiHeaderAdapter.java` | The `ConcatAdapter` header (0–1 items). Digest card bind, chips built programmatically, tap-selected-chip-clears. |
| `main/java/…/ai/ui/AiDigestActivity.java` + `AiDigestAdapter.java` | Full screen: abstract / theme header / item rows, share, mark-all-read. |
| `main/res/layout/`: `ai_digest_card.xml`, `activity_ai_digest.xml`, `ai_digest_abstract.xml`, `ai_digest_theme_header.xml`, `ai_digest_item.xml` | MaterialCardView + ChipGroup + MaterialButton — the app theme **is** `Theme.Material3.DayNight` (`themes.xml:4`), so Phase 5's "avoid Material widgets" caution does not apply. |
| `main/res/drawable/`: `ai_shimmer_bar.xml`, `ic_ai_close_24.xml` | Static placeholder bar (not animated, see deviation 6) + card dismiss icon. |

### Phase 8 — interests + manual learn
| File | Purpose |
|---|---|
| `main/java/…/ai/LineDiff.java` | 80-line LCS line diff. No `java-diff-utils`. Used by the guard (collapse detection) and the diff screen. |
| `main/java/…/ai/prompt/TasteDraftGuard.java` | `REJECTED / NEEDS_REPAIR / NO_CHANGE / OK` + `warnShrink`, `warnTopicsRemoved`, `preselectKeepCurrent`, `strippedEchoLines`. `LEARN_MIN_DECISIONS = 25`. |
| `main/java/…/ai/AiTasteDraft.java` | Prompt assembly + **exactly one** repair turn, then reject. Returns a verdict, never writes. |
| `main/java/…/ai/AiTasteDrafts.java` | The `AI_META` parking spot (`taste_draft_state/body/meta`). A draft is data, not an event — the worker may finish long after the screen is gone. |
| `main/java/…/ai/AiNote.java` | The note facade: `current` (pref, falling back to the active `AI_RUBRIC` row), `save` (append version + mirror + **invalidate**), `history`, `revert`, `canSuggest`, `resetTaste`. |
| `mlGemma/…/ai/work/AiTasteDraftWorker.java` | Manual-trigger draft. Gated at 25 decisions. Uses `AI_DECISION.TITLE_SNAP`, not a join on `RSS_ITEM` (the article may be long evicted). |
| `main/java/…/ai/ui/AiRubricDiffActivity.java` + `main/res/layout/activity_ai_rubric_diff.xml` | Strikethrough/`+` diff, warn banner, **Save is the only path from a model response to the note**. |
| `mlGemma/…/ai/work/AiLazyScheduler.java` + `mlNone/…/ai/work/AiLazyScheduler.java` | The flavor seam for both on-demand passes. `available()` is `false` in mlNone, which is what hides the Suggest button instead of offering a dead control. |

### Tests created
`test/java/…/ai/`: `AiDigestBuilderTest` (12), `AbstractGuardTest` (14), `TasteDraftGuardTest` (13), `AiDigestAbstractTest` (9), `AiTasteDraftTest` (10).
`test/java/…/database/ai/AiDigestFlowTest.java` (14, Robolectric + real SQLite + real `DaoMaster` tables).

## Files edited

- `NewsReaderDetailFragment.java` — `newsAdapter`/`aiHeaderAdapter`/`aiThemeFilter` fields + `getNewsAdapter()`; **all 7 in-fragment `(NewsListRecyclerAdapter) binding.list.getAdapter()` casts removed**; `ConcatAdapter` wiring in `loadRssItemsIntoView`; `totalItemCount` now read from the *attached* adapter in both scroll paths (otherwise "reached bottom" fires a row early in For You); theme-filter clause injection in `UpdateCurrentRssViewTask`; `loadAiHeader`/`applyAiHeader`/`DigestCardListener`; `onResume` consumes `AiDigests.consumeCurrentViewDirty()`; `setData` clears the theme filter; `onCreateView` nulls both adapter fields.
- `NewsReaderListActivity.java` — the 2 remaining casts → `ndf.getNewsAdapter()` (one of them was unguarded and would have CCE'd on the first For-You tap).
- `androidTest/.../ScreenshotTest.java`, `androidTest/.../NewsReaderListActivityUiTests.java` — same 2 casts.
- `database/ai/AiScoreStore.java` — `markScoresStale()`: clears `LLM_SCORE`/`EFF_SCORE` only. **`STATUS`, `RANK_SCORE` and `SCORED_AT` are deliberately left alone** (`SCORED_AT` is the digest's range key).
- `ai/prompt/AiPrompts.java` — `abstractSystem/abstractUser/tasteSystem/tasteUser` loaders.
- `ai/prompt/AiPromptBuilder.java` — `oneLine` package-private → public (used from `ai/`).
- `notification/NextcloudNotificationManager.java` — `showNotificationAiDigest(...)`, opens `AiDigestActivity` for a specific digest id.
- `mlGemma/…/AiTriageWorker.java` — interests note now read via `AiNote.current(prefs, db)`.
- `ai/ui/AiSettingsFragment.java` — `bindInterests()`, `onSuggestClicked()`, `showHistory()` (5-version revert dialog), reset-taste confirm, `refreshNoteSummary()` in `refresh()`.
- `SettingsActivity.java` — `PREF_AI_NOTE_HISTORY`.
- `res/xml/pref_ai_detail.xml` — `pref_ai_suggest_interests`, `pref_ai_note_history`, `pref_ai_reset_taste`, `cb_ai_digest_notify`.
- `res/values/strings.xml` — ~40 strings + 3 `<plurals>`.
- `main/AndroidManifest.xml` — `AiDigestActivity`, `AiRubricDiffActivity` (both `exported="false"`).

## Commands run — real outcomes

```
./gradlew :…:testOssMlGemmaDebugUnitTest --tests "*AiDigestBuilder*" "*AbstractGuard*" "*TasteDraftGuard*"
   -> "39 tests completed, 1 failed"  (AbstractGuardTest: my LEAD_IN regex had "sure," as an
      alternative and \b after a comma never matches — real bug in the guard, fixed) -> BUILD SUCCESSFUL in 18s
./gradlew :…:compileOssMlGemmaDebugJavaWithJavac        -> BUILD SUCCESSFUL in 21s (after 11 missing-string errors)
./gradlew :…:testOssMlGemmaDebugUnitTest --tests "*AiDigestFlow*"       -> BUILD SUCCESSFUL in 27s (14 tests)
./gradlew :…:testOssMlGemmaDebugUnitTest --tests "*AiDigestAbstract*" "*AiTasteDraftTest*"
   -> "19 tests completed, 1 failed"  (my test's collapse draft was 39 chars, i.e. legitimately
      below MIN_CHARS=40; test fixed, guard unchanged) -> BUILD SUCCESSFUL in 25s
./gradlew :…:compileOssMlGemmaDebugAndroidTestJavaWithJavac            -> BUILD SUCCESSFUL in 34s
./gradlew :…:assembleOssMlGemmaDebug :…:assembleOssMlNoneDebug \
          :…:testOssMlNoneDebugUnitTest :…:testOssMlGemmaDebugUnitTest \
          :…:compileOssMlGemmaDebugAndroidTestJavaWithJavac detekt spotlessCheck :News-Android-App:lint
                                                                       -> BUILD SUCCESSFUL in 1m 56s (157 tasks)
./gradlew :News-Android-App:assembleOssMlGemmaRelease                  -> BUILD SUCCESSFUL in 2m 6s (R8 full mode)
```

Test counts, **identical in both variants**:
```
testOssMlGemmaDebugUnitTest   tests=330 failures=0 errors=0 skipped=0
testOssMlNoneDebugUnitTest    tests=330 failures=0 errors=0 skipped=0
new: AbstractGuardTest 14  AiDigestFlowTest 14  TasteDraftGuardTest 13  AiDigestBuilderTest 12
     AiTasteDraftTest 10  AiDigestAbstractTest 9  (+1 in AiScoreStoreTest, 8->9)
prior 257 all still green.
```
APKs: `oss-mlGemma-debug` 90,475,029 B; `oss-mlNone-debug` 15,753,381 B.

### Independently verified, not assumed
- **mlNone stays clean.** Its APK dex contains **0** `androidx/work`, **0** `com/google/mediapipe`, **0** `AiDigestWorker`, **0** `AiTasteDraftWorker`, **0** `.so` files. `AiLazyScheduler` appears (the no-op copy), and the two new activities are in its merged manifest — they name no AI runtime type.
- **No `(NewsListRecyclerAdapter)` cast survives anywhere:** `grep -rn "NewsListRecyclerAdapter)" --include=*.java src` returns nothing (was 11 sites).
- **The R17 contract is asserted, not assumed.** `AiDigestFlowTest.theCurrentViewRebuildMaterialisesTheDigestInPosOrder` runs the exact `dbConn.insertIntoRssCurrentViewTable(AiDigests.currentViewSql(id))` call the activity makes and asserts `CURRENT_RSS_ITEM_VIEW` holds `[1,2,3,4,5]` with contiguous `_id`.

## Deviations — read these

1. **`AiHeaderAdapter`'s view holder is Java, not `DigestCardViewHolder.kt`.** The plan permits a Kotlin view holder; it does not require one. Java keeps the file out of `detekt` (maxIssues 0) and `ktlint` entirely, which is the reason invariant 1 exists. Same shape, one fewer gate.
2. **The theme filter is delimiter-anchored, not a bare `LIKE`.** `THEMES` is comma-joined with no spaces, so `LIKE '%regulation%'` also selects every `deregulation` article and the chip count stops matching the list length. Ships as `(',' || AI_SCORE.THEMES || ',') LIKE '%,<slug>,%'`; asserted.
3. **The untagged theme block is forced last**, not sorted by weight with the rest. A similarity-only run has *no* themes at all, so without this a digest with one strong untagged article leads with "Everything else". Documented in the builder, asserted in `AiDigestBuilderTest`.
4. **New `AiDigests.markCurrentViewDirty()` / `consumeCurrentViewDirty()`, consumed in `NewsReaderDetailFragment.onResume()`.** `CURRENT_RSS_ITEM_VIEW` is global and singular; the digest screen must overwrite it (R17), and the list fragment's lazy paging reads it. Without the flag, returning from the digest and scrolling to the bottom of For You pages in the *digest's* articles. This is a bug the plan does not mention.
5. **The article adapter is rebuilt when the ConcatAdapter-ness changes**, not only when it is null. A `ConcatAdapter` keeps an observer registered on every child it was given, so handing the same `NewsListRecyclerAdapter` to a bare `setAdapter` after leaving For You would leave a live wrapper behind. Costs one adapter construction per in/out folder switch.
6. **`ai_shimmer_bar` is a static rounded rect, not an animated shimmer.** An animation on a header that may sit there for a minute is battery with no information in it.
7. **Mark-all-read goes through `dbConn.markAllItemsAsReadForCurrentView()`** over a view rebuilt from the digest, not a hand-rolled `setRead_temp` loop — the hand-rolled version would not go through the path the delayed sync watches and the articles would come back unread.
8. **Digest notification is a switch only; no time picker and no eager "generate at 08:00 while charging".** `sp_ai_digest_notify_time` exists as a constant and is unused. Generation stays lazy-on-open; the notification fires when the abstract lands. Phase 9 owns run policies.
9. **No `injectActivity(AiDigestActivity)` / `injectActivity(AiRubricDiffActivity)` in `AppComponent`.** Neither has an `@Inject` field (they use `AiFeature.prefsOf`, like the rest of the AI layer). Adding the methods would be unused generated code.
10. **`AiDigestActivity` uses `findViewById`, not ViewBinding.** ViewBinding is enabled, but the two model-manager screens from Phase 5 use `findViewById` and consistency inside the AI UI package was worth more than matching the doc snippet.
11. **`AiNote.resetTaste` keeps the note and its rubric history.** It deletes `AI_DECISION`/`AI_TASTE`/`AI_CENTROID` and zeroes `decision_count`. A "forget what I like" button is not licence to delete the reader's own prose.
12. **`markScoresStale` does not touch `SCORED_AT`** (see above) — the doc's "mark stored llmScores stale" would have made every already-exported article eligible for the next digest again.

## Left undone / open questions

- **⚠️ The Phase-3 carry-over is still open and now interacts with the digest.** For You is `WHERE AI_SCORE.STATUS='selected'`; a swipe writes `AI_TASTE`, not `AI_SCORE`, so a decided article returns on refresh. It also stays in the digest, since digest membership is frozen at build time. Still needs the product decision Phase 3 flagged.
- **Nothing here has ever talked to a real model.** `AiAbstract` and `AiTasteDraft` are covered end to end against `FakeLlm`; `AiDigestWorker` and `AiTasteDraftWorker` (the WorkManager wrappers, ~60 lines each) have **no test** and have never run. No `AiDigestWorkerTest` is possible in `src/test` — it would break `testOssMlNoneDebugUnitTest`'s compile, same constraint as `AiScoringPipelineTest`.
- **No UI test of the digest card.** That `AiHeaderAdapter` lands at position 0, that the chip rebuild preserves selection, and that the ConcatAdapter swap on folder switch works are all reasoned-about, not observed on a device or in Robolectric.
- **The status strip / onboarding card / four empty states / `ai_list_item.xml` + `RssItemAiViewHolder` (the why-line) are still the Phase-2 carry-over.** `AiHeaderAdapter` is now the place they go — it already has the ConcatAdapter seat, it just renders one view type. `AiDegrade` still renders nowhere.
- **`AiSettingsFragment` writes the note on the main thread** (`onPreferenceChange` → rubric append + `markScoresStale` UPDATE). Bounded by cache size (a few hundred rows) and matches what `SettingsFragment` already does, but it is a main-thread DB write.
- **The Suggest button polls nothing.** A draft that completes while the settings screen is open only shows up on the next `onResume`. An EventBus post from the worker would fix it; the worker lives in `src/mlGemma` and EventBus is available there.
- `AiDigestBuilder.MAX_ITEMS = 60` and `AiDigests.KEEP_DAYS = 14` are unvalidated defaults.

## What the next phase must know

- **`NewsReaderDetailFragment.getNewsAdapter()` is now the only sanctioned way to reach the article adapter.** `getRecyclerView().getAdapter()` is a `ConcatAdapter` in For You. Anything new that touches it must use the accessor or `instanceof`.
- **`getBindingAdapterPosition()`, never `getAbsoluteAdapterPosition()`**, for anything that indexes `NewsListRecyclerAdapter` (the swipe/undo path already does).
- **The digest is built by `AiDigests.ensureToday(db, now)` from the fragment's background thread**, on every For-You list rebuild. It is idempotent (`DAY_KEY UNIQUE`) and returns the existing row after the first call of the day.
- **`AI_META` keys now claimed:** `digest_dismissed_day`, `interests_hash`, `taste_draft_state`, `taste_draft_body`, `taste_draft_meta` — plus everything Phases 1–6 claimed. **Still free:** `feed_weight_map`.
- **New prefs:** `cb_ai_digest_notify`, `pref_ai_note_history`. `sp_ai_digest_notify_time` is declared and unused.
- **`AiLazyScheduler` is a second flavor seam** alongside `AiEngines`/`AiTriageScheduler`. Four methods; add to one copy and you must add to the other or mlNone stops compiling.
- **`AiNote.save(ctx, body, source)` is the only write path to the interests note**, and it carries the three invalidations. Never write `edt_ai_interests` directly.
- **`AiDigests.markCurrentViewDirty()` must be called by anything else that overwrites `CURRENT_RSS_ITEM_VIEW` outside the list fragment.**
- SQLite 3.9 rule still holds (no UPSERT). All new SQL this phase is `SELECT`/`UPDATE`/`DELETE` plus the existing `INSERT OR IGNORE` in `AiDigestStore`.