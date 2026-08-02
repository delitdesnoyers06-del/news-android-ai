Phases 3 and 4 are implemented and verified. Report below.

# Phases 3 + 4 — the feedback signal and the taste model

## Files created

### Phase 3 — the signal
| File (abs) | Purpose |
|---|---|
| `…/src/main/java/de/luhmer/owncloudnewsreader/ai/AiFeature.java` | The single `isEnabled()` gate: `AiCapability.isSupported()` **&&** `cb_ai_enabled` (default **off**). Also `prefsOf(Context)` — the app does **not** use `PreferenceManager.getDefaultSharedPreferences`; `ApiModule` provides `getSharedPreferences(packageName + "_preferences")`, and reading the wrong file would make the switch look off to half the code. |
| `…/ai/AiDecisions.java` | The one UI entry point. `record(Context\|AiDb, RssItem, action, source)` → `AiDecisionStore.decide()` → `AiCentroidStore.onDecision()`. Snapshots `LLM_SCORE_AT` (null = never reached the LLM = information), clips `TITLE_SNAP` to 120. `seedFromStarred(Context)` = the one-time ≤50 star import, guarded by `AI_META['star_seed_done']`. Catches `Throwable`, returns `false`. |
| `…/ai/AiText.java` | `clip` (surrogate-safe), `sanitise`, `embedInput(title, body)` @ 1400 chars, `titleSnap`. |

### Phase 4 — engine, math, pipeline
| File (abs) | Purpose |
|---|---|
| `…/ai/engine/AiException.java` | Checked, 8-kind taxonomy + `retryableNextSync()`. |
| `…/ai/engine/AiEmbedder.java` | `src/main`, **no MediaPipe type**. Only `embed(title, body)` — the 1-arg shape does not exist on the interface (D12 enforcement). |
| `…/ai/engine/AiEngineManager.java` | Static `ReentrantLock` gate (one engine process-wide), main-thread assertion, model resolution via `AiModelRegistry.pathOf`, `withEmbedder(Work)`, `embedderAvailable()`, `forTesting(AiEmbedder)`. |
| `…/src/mlGemma/…/ai/engine/impl/MediaPipeEmbedder.java` | `CLUSTERING` + `TextRole.QUERY`, **one static `CTX`**, `setL2Normalize(true)`, `setQuantize(false)`, `Delegate.CPU`, absolute `setModelAssetPath`. API verified by `javap` against the real `tasks-text-1.0.0.aar`/`tasks-core-1.0.0.aar`. |
| `…/src/mlGemma/…/ai/engine/impl/AiEngines.java` + `…/src/mlNone/…/ai/engine/impl/AiEngines.java` | The flavor seam — same FQN twice, like `AiModule`. mlNone throws `NOT_INSTALLED`. |
| `…/ai/AiVec.java` | `unit` (null on zero-norm), `dot` (0.0 on null/dim-mismatch, never throws), `addInPlace`, `scale`. |
| `…/ai/AiSimilarity.java` | `sim = cos(v,liked) − cos(v,rejected)`; `SIM_FLOOR = 0.05`; the two sentinels. |
| `…/ai/AiCandidates.java` | Stage 1: unread ∧ (`PUB_DATE IS NULL` ∨ ≥ now−7d), ordered `(PUB_DATE IS NULL) ASC, PUB_DATE DESC, LAST_MODIFIED DESC, _id DESC`. |
| `…/ai/AiPrefilter.java` | Verbatim port incl. the unscored-fills-remaining-room clause + `isCold()` (D29). |
| `…/ai/AiRank.java` | `eff`, `rank`, `bumpOf`, `round3`, `clampSim`. |
| `…/ai/AiTriagePipeline.java` | Stages 1–4 + rank, **no WorkManager, no Android beyond Context/SharedPreferences**. `// PHASE 6 SEAM` marks where scoring slots in. |
| `…/src/mlGemma/…/ai/work/AiTriageWorker.java` | Worker wrapper. Exactly **one** `return Result.success()`; `failure()`/`retry()` never returned. |
| `…/src/mlGemma/…/ai/work/AiTriageScheduler.java` + `…/src/mlNone/…/ai/work/AiTriageScheduler.java` | Enqueue-only, `ExistingWorkPolicy.KEEP`; mlNone is a no-op. |

### Resources
`res/drawable/`: `swipe_ai_more.xml`, `swipe_ai_less.xml` (state-list on `state_above_anchor`, modelled on `swipe_setstarred`), `ic_thumb_{up,down}_white_24.xml`, `ic_thumb_{up,down}_24_theme_aware.xml`.

### Tests
`…/src/test/java/…/ai/`: `AiPrefilterTest`, `AiRankTest`, `AiSimilarityTest`, `AiVecTextTest`, `AiDecisionFlowTest`, `AiTriagePipelineTest`, `FakeEmbedder`. `…/database/ai/AiCentroidTest.java`.

## Files edited

- `SettingsActivity.java` — the full AI constant block from doc 07 §5.1 verbatim (Phase 5 needs the exact spellings; a key that changes between phases orphans a user's setting).
- `NewsReaderDetailFragment.java` — `SWIPE_AI_MORE="4"`/`SWIPE_AI_LESS="5"` constants; `getLayoutId()` cases 4/5 → `R.attr.aiMoreDrawable`/`aiLessDrawable`; `updateSwipeDrawables()` folder-aware + forced call from `setData()`; **new `getMovementFlags()` override** returning 0 for non-`RssItemViewHolder`; `onSwiped()` instanceof guard + For-You override + `case "4"/"5": … return;`; new `isAiFolder()` and `applyAiDecision()` (Snackbar `LENGTH_LONG` + undo, anchored on `fabDoneAll`).
- `adapter/NewsListRecyclerAdapter.java` — `removeItemAt(int)` / `restoreItemAt(int, RssItem)`, both with the `UnsupportedOperationException` → `notifyDataSetChanged()` fallback.
- `NewsDetailActivity.java` — `faAiUp`/`faAiDown` visibility gated on `AiFeature.isEnabled`, listeners → new `recordAiDecision(boolean)`.
- `res/layout/widget_fastactions_detailview.xml` — two `AppCompatImageButton`s, `visibility="gone"`, with `contentDescription`.
- `res/values/{strings,attrs,themes,colors}.xml` — `action_ai_{more,less}_like_this`, `ai_snack_{more_like_this,less_like_this,undo}`, `ai_notification_triage_running`; swipe arrays extended to items 4/5; `aiMoreDrawable`/`aiLessDrawable` attrs bound in the one theme that defines `starredDrawable`; `aiMoreColor`/`aiLessColor`.
- `database/ai/AiDecisionStore.java` — `Result` gained a `from` field (the centroid needs the observed pre-state).
- `database/ai/AiCentroidStore.java` — `onDecision`, `unit`, `refreshCacheKey`, `backfill`, `rebuild`, `consistent`, private `add(cls,row,sign)`. `N ≤ 0` **deletes** the row (missing ≠ zero vector).
- `database/ai/AiEmbeddingStore.java` — `hasForeign(model, task)`, the read-only counterpart of `assertCompatible`.
- `database/DatabaseConnectionOrm.java` — `aiDb()` (returns `AiDb`, not a `SQLiteDatabase`, guarded on `AiSchema.isReady()`); `getStarredRssItemsNewestFirst(int)`.
- `notification/NextcloudNotificationManager.java` — `buildNotificationAiTriage`.
- `authentication/OwnCloudSyncAdapter.java` — one line, `AiTriageScheduler.enqueueAfterSync(getContext())` immediately after `startFaviconDownload()`.
- `src/mlGemma/AndroidManifest.xml` — `<service android:name="androidx.work.impl.foreground.SystemForegroundService" android:foregroundServiceType="dataSync" tools:node="merge"/>`.

## Commands run — real outcomes

```
./gradlew :News-Android-App:compileOssMlGemmaDebugJavaWithJavac            -> BUILD SUCCESSFUL in 23s   (after Phase 3)
./gradlew :…:compileOssMlGemmaDebugJavaWithJavac :…:compileOssMlNoneDebugJavaWithJavac
                                                                          -> BUILD SUCCESSFUL in 32s
./gradlew :…:testOssMlGemmaDebugUnitTest --tests "*AiPrefilter*" …        -> "48 tests completed, 1 failed"  (AiRankTest, my expectation was wrong: round(0.4239,3)=0.424 not 0.42 — test fixed)
./gradlew :…:testOssMlGemmaDebugUnitTest --tests "*AiTriagePipeline*" …   -> "23 tests completed, 1 failed"  (a REAL bug, see deviation 4)
./gradlew :News-Android-App:testOssMlGemmaDebugUnitTest --tests "*Ai*"    -> BUILD SUCCESSFUL in 31s
./gradlew :…:assembleOssMlGemmaDebug :…:assembleOssMlNoneDebug \
          :…:testOssMlNoneDebugUnitTest :…:testOssMlGemmaDebugUnitTest \
          detekt spotlessCheck :News-Android-App:lint
                                                                          -> BUILD SUCCESSFUL in 2m 37s  (143 tasks)
```

Counts (identical in both variants — everything except the Worker/Scheduler/MediaPipeEmbedder is in `src/main`):

```
new:  AiPrefilterTest 16   AiTriagePipelineTest 12   AiCentroidTest 12   AiRankTest 11
      AiDecisionFlowTest 11   AiSimilarityTest 9   AiVecTextTest 9
prior: AiEmbeddingStoreTest 17  AiDecisionStateMachineTest 15  AiFolderSqlTest 14
      AiScoreStoreTest 8  AiKeysTest 7  AiCapabilityTest 6  AiSchemaTest 5  AiGcTest 4
pre-existing: RssItemToHtmlTaskTest 3  ImageHandlerTest 8  TtsTextSplitterTest 8
all: failures=0 errors=0 skipped=0
```

Independently verified, not assumed:
- **R4 guard is clean.** `grep -rn "\.embed(" src --include=*.java` returns exactly two lines: `AiTriagePipeline:312 embedder.embed(c.title, c.body)` and `MediaPipeEmbedder:98 embedder.embed(text, CTX)`. No 1-arg call anywhere.
- **Manifest merge worked.** Merged `ossMlGemmaDebug` manifest shows `SystemForegroundService … android:foregroundServiceType="dataSync"`. The `ossMlNoneDebug` merged manifest contains **0** occurrences of `androidx.work`.
- APKs: `oss-mlGemma-debug` 90,385,174 B; `oss-mlNone-debug` 15,663,522 B (vs 15,709,223 B recorded by Phase 2 — sizes are not comparable across sessions, see Phase 2's note).

## Deviations — read these

1. **`AiEngineManager` cannot be "the only class that touches MediaPipe".** It lives in `src/main` and must compile in `mlNone`. It delegates to `impl.AiEngines`, which exists twice at the same FQN, one per `ml` source set. That indirection is unavoidable given the flavor split; `MediaPipeEmbedder` is package-private inside `src/mlGemma`.

2. **No `NoopEmbedder`.** `mlNone`'s `AiEngines.openEmbedder` throws `NOT_INSTALLED`, which the pipeline already handles (it is the same state as "model not downloaded yet"). An embedder that returns `null` for everything would add a code path that means exactly the same thing while looking like it worked. If you want the class, it is 20 lines — but it buys nothing.

3. **`AiTriageWorker` and `AiTriageScheduler` live in `src/mlGemma`, not `src/main`** (the plan's file list puts them in `main/…/ai/work/`). `androidx.work:work-runtime` is an `mlGemmaImplementation` dependency and `build.gradle:77-79` states that mlNone's dependency graph must stay byte-identical (F-Droid). Moving WorkManager to plain `implementation` would break that. **The pipeline logic is in `src/main` as `AiTriagePipeline`** — that is what is tested; the Worker is a 60-line wrapper. `AiTriageScheduler` is duplicated per flavor so `OwnCloudSyncAdapter` carries one unconditional line.

4. **`AiRank` clamps `sim` to `[-1,1]` before ranking. veille does not.** The plan asserts "the 0.5 coefficient is chosen so similarity can NEVER cross a tier boundary" — but `sim` is a *difference* of two cosines and can legally reach ±2, at which point `0.5·sim = ±1` and it *does* cross. Invariant 2 says the code is the guarantee, so `clampSim()` makes it true for every input; `AiRankTest.similarityNeverCrossesATierBoundary` samples `sim ∈ [-2.5, 2.5]` 20,000 times to prove it. **The clamp is ranking-only** — `AI_SCORE.SIM_SCORE` and the `SIM_FLOOR` comparison use the raw value.

5. **A real bug found by a test, then fixed.** `embedMissing()` originally computed the not-yet-embedded list *before* opening the engine, and `assertCompatible()` runs *inside* it. On the sync where the model changed, every candidate looked already-embedded, then its vector was deleted underneath — that sync embedded nothing and the folder went cold for a whole cycle. The todo list is now built inside the open engine, after `assertCompatible`. Covered by `aChangedEmbeddingModelWipesTheVectorsButNotTheDecisions`.

6. **`decision_count` is incremented by `AiDecisionStore.decide()`, not by `AiCentroidStore.onDecision()`** (doc 06 §3.2's snippet puts it in `onDecision`). It belongs in the same transaction as the `AI_DECISION` append, so a centroid failure can never desynchronise the warm-up counter from the history.

7. **Cold start writes the sentinel, not a blanket `0.0`.** BRIEF says "sim = 0.0 for all" in cold; doc 06 §3.3 says NULL = never embedded. Both are satisfied: the pipeline passes `null` centroids while cold, so `AiSimilarity` naturally yields `0.0` for embedded articles and `null` for unembedded ones, and the prefilter's cold branch ignores the values entirely. Asserted in `AiTriagePipelineTest`.

8. **`AI_SCORE.DISCARD_REASON = 'out_of_window'` is never written.** veille's stage 3 discards out-of-window articles into rows; that would mean an `AI_SCORE` row for every article the user ever cached. `AiCandidates` simply does not return them. The constant stays unused.

9. **`AiEngineManager` is no longer `final`** and gained `forTesting(AiEmbedder)` (an anonymous subclass overriding `withEmbedder`/`embedderAvailable`, still taking the gate). This is what lets the whole pipeline run under plain JUnit.

10. **Fast actions get a Toast, not a Snackbar+undo.** In the reader there is no row to remove, so the opposite button — one tap away, always visible — is a better undo than a 5 s timer. The mandatory Snackbar undo is on the swipe path, where a row *does* disappear.

11. **The star seed is invoked from `AiTriageWorker`**, gated on `cb_ai_star_is_like`, not from a settings screen (Phase 5 owns settings). The `AI_META` flag makes it one-time regardless.

## Left undone / open questions

- **⚠️ A swiped article comes back on refresh.** The For You SQL is `WHERE AI_SCORE.STATUS = 'selected'` (PLAN §4.3, asserted character-for-character by `AiFolderSqlTest`). A keep/reject writes `AI_TASTE`, never `AI_SCORE.STATUS` (invariant 5), so the row reappears until the next triage pass re-ranks it. `applyAiDecision` removes it from the adapter, so it is invisible until a refresh. **This needs a decision the plan does not make**: either the SQL gains `AND AI_SCORE.AI_KEY NOT IN (SELECT AI_KEY FROM AI_TASTE)` (breaks the Phase-2 exact-string test, which would need updating) or a decision also marks the article read. I did not change it unilaterally — the plan is explicit and Phase 2 locked it down.
- **`AiTriageWorker` has no test.** `AiTriagePipeline` is covered end to end; the Worker wrapper, `setForegroundAsync`, and the WorkManager enqueue are not. The FGS manifest attribute is verified in the merged manifest but has never actually started on a device.
- **`MediaPipeEmbedder` has never run.** No model file exists yet (Phase 5 owns the downloader), so `AiModelRegistry.pathOf("embedding_gemma_int4int8")` is always null today and `withEmbedder` always throws `NOT_INSTALLED`. The API is verified by `javap` against the real AARs and it compiles, but the first real `createFromOptions` call is a Phase 5 event. **The veille byte-parity check (dump one vector from Java, one from `embed.py`, diff) is not done** — it cannot be until a model is installed.
- **`AiEngineManager.withLlm` / keep-alive / `ComponentCallbacks2` / `shutdownNow` do not exist.** Phase 6.
- **Feed weights are not read.** `AiRank.bumpOf(String)` exists; nothing parses `AI_META['feed_weight_map']` yet, and the pipeline passes `feedBump = 0`. Harmless while `LLM_SCORE` is always null (the bump only affects `eff`).
- `AiPrefilter.MIN_PER_CLASS`/`MIN_TOTAL_DECISIONS` and `topK` are not user-settable yet beyond reading `sp_ai_batch_budget` (Phase 5 adds the pref UI).

## What the next phase must know

- **The Phase 6 seam is one marked line** in `AiTriagePipeline.run()`: `// ---- PHASE 6 SEAM: scoring goes here, between the prefilter and the write ----`. Above it you have `outcome.selected`; below, replace `row.llmScore = null` / `row.rankScore = AiRank.rank(null, 0, sim)` with the real values and re-derive `row.status` from `AiRank.selectable(eff)`. **Do not touch the write path** — `AiScoreStore.writeResult(row)` with `row.aiKey` set is the fan-out and `AiTriagePipelineTest.duplicateFingerprintsAreScoredOnceAndFannedOut` guards it.
- The pipeline already dedupes candidates by `AI_KEY` before the prefilter, so the scorer sees each article once and the fan-out carries the result to its duplicates.
- `AiEngineManager.forTesting(FakeEmbedder)` is the pattern for `FakeLlm`: add `withLlm` + a matching override.
- **`AiEngines` is the flavor seam.** Anything naming a LiteRT-LM or MediaPipe type must go behind it; both copies must gain the same method or `mlNone` stops compiling.
- `AiFeature.isEnabled()` is now the gate everything asks. Phase 5 must `&&` it into `SubscriptionExpandableListAdapter.loadCategoriesAndItemsFromDatabase()` (today the drawer row still gates on `AiCapability.isSupported` alone) and call `AiTriageScheduler.cancel(ctx)` when the switch flips off.
- `AI_META` keys now claimed: `schema_version`, `decision_count`, `centroid_unit_cache_key`, `embed_backlog_cursor`, `active_sync_id`, `star_seed_done`. Still free: `feed_weight_map`, `interests_hash`, `digest_dismissed_day`, `model_load_ms`.
- `AiModelRegistry.register(...)` with `MODEL_ID = "embedding_gemma_int4int8"` and `STATE='installed'` + a `PATH` is all `withEmbedder` needs — that is the contract Phase 5's downloader must satisfy (`AiEngineManager.EMBEDDING_MODEL_ID`).
- SQLite 3.9 rule still holds (no UPSERT). All new SQL in this phase is plain `SELECT`/`UPDATE`.
- `AiTriagePipeline.run(nowMs, syncId)` takes the clock as a parameter — keep it that way, the 7-day window test depends on it.