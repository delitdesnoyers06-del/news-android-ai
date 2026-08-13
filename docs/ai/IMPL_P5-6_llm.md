# Phases 5 + 6 — model manager, settings, and the LLM half

All paths absolute under `/home/yohann/dev/padam/news-android-ai/News-Android-App/src/`.

## Files created

### Phase 5 — catalogue, download, model manager, settings

| File | Purpose |
|---|---|
| `main/res/raw/ai_model_catalog.json` | 8 entries. **Sizes and sha256 re-fetched live from the HF tree API in this session**, not copied from a doc — see "verified facts" below. |
| `main/java/…/ai/model/AiCatalogEntry.java` | Gson DTO (package matches the existing `proguard-rules.pro` keep line). `downloadUrl()` always builds the `resolve/<rev>/` origin form — D23. |
| `…/ai/model/AiCatalog.java` | Parses `res/raw` once, cached. Malformed JSON ⇒ **empty catalogue**, never a crash on the settings screen. `defaultLlmFor(tier)`. |
| `…/ai/model/AiPartMeta.java` | The `.meta` sidecar. `matches(entry)` invalidates a partial download when size/checksum changed upstream. Never throws. |
| `…/ai/download/AiModelDownloader.java` | OkHttp 5, `Range: bytes=N-`, no `If-Range`, `x-linked-size` validation, final SHA-256/MD5, `renameTo`. **On a 200 where a 206 was asked for it discards the `.part` and restarts, once.** Returns an `Outcome` with a code; never throws. |
| `…/ai/download/AiModelRepository.java` | Storage layout, `scan()` (disk wins over the registry), `infoFor`, `resolveLlm` (the 5-step fallback chain), `delete()` + **pref reset**, summaries. |
| `…/ai/download/AiModelDownloadService.java` | Foreground `dataSync` Service, one transfer at a time, Cancel action, EventBus progress, then the smoke load with its own "Preparing model…" line. |
| `…/ai/download/AiModelDownloadEvent.java` | EventBus progress/phase event. |
| `…/ai/download/AiModelProbe.java` | 180 s smoke load (D25) + the `[0-3]` regex probe; records `sentencePiece`/`loadMs` in `AI_META` and `AI_MODEL.CAPS`. |
| `…/ai/ui/AiSettingsActivity.java`, `AiSettingsFragment.java` | Detail screen. Fragment calls `injectFragment(this)` **and** `setSharedPreferencesName(sharedPreferencesFileName)`. Triage picker is rebuilt in `onResume` from **installed** models only. |
| `…/ai/ui/AiModelManagerActivity.java`, `AiModelAdapter.java` | RecyclerView, per-row Download/Resume/Cancel/Delete, state text, EventBus progress, gated-model dialog. |
| `main/res/xml/pref_ai.xml`, `pref_ai_detail.xml` | Two rows in the main screen; the detail screen per **D21** (triage `ListPreference`, embedding = status row, `cb_ai_enrich_enabled`/`cb_ai_learn_enabled` switches, HF token **always visible**). |
| `main/res/layout/activity_ai_model_manager.xml`, `ai_model_row.xml` | Plain framework widgets (`ProgressBar`/`Button`), not Material Chip — see deviation 6. |

### Phase 6 — engine, prompts, scorer

| File | Purpose |
|---|---|
| `main/java/…/ai/engine/AiLlm.java`, `AiConversation.java`, `AiModelInfo.java`, `AiOutputMode.java`, `AiPromptSpec.java`, `CancelToken.java`, `LlmCall.java` | Interfaces + value types in `src/main`, **no LiteRT type**. `LlmCall.run` wraps a watchdog and never throws. |
| `mlGemma/…/ai/engine/impl/LiteRtLlm.java`, `LiteRtConversation.java` | The 0.15.0 binding. Signatures re-verified with `javap` on the real AAR this session (7-arg `EngineConfig`, 12-arg `ConversationConfig`, `sendMessage(String, Map, …, ResponseFormat)`, `Backend.CPU()` no-arg, `ResponseFormat.regex` static). Blocking overload — no coroutines. |
| `main/res/raw/prompt_*.txt` (10 files) | Verbatim from `02_product_prompts.md` §1.3–1.6, §2, §3, §4. |
| `main/java/…/ai/prompt/PromptTemplate.java` | **Single-pass** `{{name}}` scan. A substituted value is appended and never re-scanned. |
| `…/ai/prompt/ScoreLineParser.java` | The whole guarantee table. Never throws. |
| `…/ai/prompt/AiScoreGrammar.java` | `(?:[1-9][0-9]?\|[0-3]\|[a-z0-9,\-]{0,80}\|[^\n\|]{0,160}\n){N}` + `forAnyLength()` fallback + `compileOrNull`. |
| `…/ai/prompt/AiPromptBuilder.java` | Article payloads, interests cap at the last newline, corrections block (heading omitted with its content), tags, `{{lang}}` = English language name. Pipes stripped from all scraped text. |
| `…/ai/prompt/AiInterests.java` | Derives theme slugs from the free-text note — see deviation 3. |
| `…/ai/prompt/AiPrompts.java` | The three scoring prompts as data, so the scorer needs no `Context`. |
| `main/java/…/ai/AiScorer.java` | Batching + the full repair ladder + the terminal guarantee. |
| `main/java/…/ai/AiDegrade.java` | The degradation ladder as one table. |
| `main/java/…/ai/AiCorrections.java` | veille `learn.py:62` predicate, `restore` kept as a standalone OR term. |

### Tests
`test/java/…/ai/`: `ScoreLineParserTest` (16), `AiScoreGrammarTest` (10), `PromptBuilderTest` (16), `AiScoringPipelineTest` (12), `AiCatalogTest` (8), `AiPartMetaTest` (7), `AiScoreEvalTest` (8), `AiDegradeTest` (5), `FakeLlm.java`. Fixture `test/resources/ai/score_eval.json`.

## Files edited

- `main/java/…/ai/engine/AiEngineManager.java` — `withLlm(info, gpu, token, work)`, 180 s load watchdog on a dedicated thread, **90 s idle keep-alive**, embedder↔LLM mutual eviction under the one static gate, `shutdownNow(reason)` (non-blocking, `tryLock`), lazily-registered `ComponentCallbacks2` that **ignores `TRIM_MEMORY_UI_HIDDEN`**, `forTesting(embedder, llm)`.
- `main/java/…/ai/AiTriagePipeline.java` — the Phase 6 seam is gone; `withScoring(Scoring)`, `scoreAndCommit()`, `commitScored()`, `feedTitles()`. Report gained `scored`/`unjudged`/`inventedTags`.
- `main/java/…/ai/AiCapability.java` — `downloadOk(ctx, size)` checking **both** volumes, `freeBytes(File)`, `DOWNLOAD_HEADROOM_BYTES`/`INTERNAL_HEADROOM_BYTES`.
- `mlGemma|mlNone/…/ai/engine/impl/AiEngines.java` — both gained `llmSupported()` / `openLlm(...)`.
- `mlGemma/…/ai/work/AiTriageWorker.java` — resolves the model, builds `Scoring`, `onStopped()` cancels the token + `shutdownNow`. Still exactly one `return Result.success()`.
- `SettingsActivity.java` — `SP_AI_SCORE_BATCH`, `CB_AI_ENRICH_ENABLED`, `CB_AI_LEARN_ENABLED`.
- `SettingsFragment.java` — `pref_ai` as the 5th resource, `bindAiPreferences()`, first-ever `onResume()`, switch-off ⇒ `AiTriageScheduler.cancel` + `AiEngineManager.shutdownNow`.
- `ListView/SubscriptionExpandableListAdapter.java` — drawer gate `AiCapability.isSupported` → **`AiFeature.isEnabled`** (the Phase 3 hand-off item).
- `di/AppComponent.java` — `injectActivity(AiSettingsActivity|AiModelManagerActivity)`, `injectFragment(AiSettingsFragment)`. `TestComponent` needed **no** edit: it `extends AppComponent`, so it inherits them (verified by compiling `androidTest`).
- `main/AndroidManifest.xml` — the two activities. `mlGemma/AndroidManifest.xml` — `AiModelDownloadService` with `foregroundServiceType="dataSync"`.
- `main/res/values/strings.xml` — ~70 strings + 5 arrays.

## Commands run — real outcomes

```
./gradlew :News-Android-App:compileOssMlGemmaDebugJavaWithJavac            -> BUILD SUCCESSFUL in 32s
./gradlew :…:compileOssMlGemmaDebugJavaWithJavac :…:compileOssMlNoneDebugJavaWithJavac
                                                                          -> BUILD SUCCESSFUL in 40s
./gradlew :…:testOssMlGemmaDebugUnitTest --tests "*ScoreLineParser*" …    -> "54 tests completed, 1 failed"
        (ScoreLineParserTest.scoreIsCoercedThenClamped: my expectation was wrong —
         doc 02 §5.1 says a float is ROUNDED, so "2.5" is 3, not 2. Test + a stale javadoc line fixed.)
                                                                          -> BUILD SUCCESSFUL in 27s
./gradlew :News-Android-App:testOssMlGemmaDebugUnitTest --tests "*Ai*"    -> BUILD SUCCESSFUL in 37s
./gradlew :…:assembleOssMlGemmaDebug :…:assembleOssMlNoneDebug \
          :…:testOssMlNoneDebugUnitTest :…:testOssMlGemmaDebugUnitTest \
          detekt spotlessCheck :News-Android-App:lint
                                                                          -> BUILD SUCCESSFUL in 2m 49s (143 tasks)
./gradlew :News-Android-App:assembleOssMlGemmaRelease                     -> BUILD SUCCESSFUL in 2m 16s  (R8 full mode, S7 compile half)
./gradlew :News-Android-App:compileOssMlGemmaDebugAndroidTestJavaWithJavac -> BUILD SUCCESSFUL in 20s   (proves TestComponent still generates)
# after the drawer-gate + switch-off edits, the full gate was re-run:
                                                                          -> BUILD SUCCESSFUL in 2m 29s (143 tasks)
```

Final test counts, **identical in both variants** (everything except the Worker/Scheduler/LiteRt impls is in `src/main`):

```
testOssMlNoneDebugUnitTest   tests=257 failures=0 errors=0 skipped=0
testOssMlGemmaDebugUnitTest  tests=257 failures=0 errors=0 skipped=0

new this phase: ScoreLineParserTest 16  PromptBuilderTest 16  AiScoringPipelineTest 12
                AiScoreGrammarTest 10   AiCatalogTest 8  AiScoreEvalTest 8
                AiPartMetaTest 7        AiDegradeTest 5
prior: AiEmbeddingStoreTest 17  AiPrefilterTest 16  AiDecisionStateMachineTest 15  AiFolderSqlTest 14
       AiTriagePipelineTest 12  AiCentroidTest 12  AiRankTest 11  AiDecisionFlowTest 11
       AiSimilarityTest 9  AiVecTextTest 9  AiScoreStoreTest 8  AiKeysTest 7  AiCapabilityTest 6
       AiSchemaTest 5  AiGcTest 4
pre-existing: ImageHandlerTest 8  TtsTextSplitterTest 8  RssItemToHtmlTaskTest 3
```

APKs: `oss-mlGemma-debug` 90,440,948 B; `oss-mlNone-debug` 15,719,296 B.

### Independently verified, not assumed
- **R4 guard still clean.** `grep -rn "\.embed(" src --include=*.java | grep -v CTX` returns exactly one line: `AiTriagePipeline.java:495 embedder.embed(c.title, c.body)`.
- **mlNone stays clean.** APK has **0** `.so` files, **0** `com/google/mediapipe`, **0** `androidx/work` in any dex. The only `litertlm` byte-string in mlNone's dex is our own cache-directory name constant (`AiEngineManager.engineCacheDir` / `AiModelRepository.ENGINE_CACHE_DIR`) — not the library. mlNone's merged manifest has **0** `AiModelDownloadService`, 2 AI activities.
- **mlGemma merged manifest** contains `<service android:name="…AiModelDownloadService" android:exported="false" android:foregroundServiceType="dataSync" />`.
- **Catalogue numbers are live-fetched, not doc-copied.** From `https://huggingface.co/api/models/<repo>/tree/main?recursive=true` on 2026-08-01: E2B `2588147712 / 181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c`; E4B `3659530240 / 0b2a8980…`; Qwen3-0.6B-int4 `497664000 / b1baab46…`; Qwen3-1.7B `2056729520 / 66064a4e…`; SmolLM2-360M `373719040 / 8e2834da…`. Gated repos (`Gemma3-1B-IT`, `gemma-3-270m-it`) return **masked oids (`****`)** — so their `sha256` is `null` in the catalogue and they are verified on size alone. Embedder confirmed by `curl -I` on the CDN: `content-length: 183816181`, `etag: "dabc0e55b47b898a38472d5d99f37892"`.
- **LiteRT-LM API** re-verified by `javap` on `litertlm-android-0.15.0.aar` in the Gradle cache. `Backend$GPU` exists.

## Deviations — read these

1. **The embedder's catalogue id is `embedding_gemma_int4int8`, not the doc's `embedding-gemma-300m`.** Phase 4 already hard-coded `AiEngineManager.EMBEDDING_MODEL_ID = "embedding_gemma_int4int8"` as the `AI_MODEL.MODEL_ID` the engine looks up. Changing the constant would orphan any already-written `AI_EMBEDDING.MODEL` rows. `AiCatalogTest.theEmbedderIdMatchesTheEngineManagersConstant` locks the two together so they can never drift again.

2. **`AiScoreStore.unjudged(syncId, limit)` is still unused.** The plan wanted resume-from-`SCORED_AT IS NULL`. What ships instead: the scorer commits **one row per article as its batch completes**, and a killed run simply leaves those articles with `LLM_SCORE IS NULL` + `STATUS='selected'`, so the next run re-picks them through the normal candidate path. Same checkpoint semantics, one fewer code path. If you want mid-run resume *within* a sync, `unjudged()` is the selector to wire in.

3. **Theme slugs are derived from the interests note; the plan never says where they come from on-device.** `AiInterests.themeSlugs()` takes each note line, strips the bullet, cuts at the first `( , : ;`, and slugifies the first 3 words (≤21 entries, leaving room for the 3 flags inside the 24 cap). Deterministic and tested. An empty note yields no themes, which is a working configuration — the three flags still apply. **If the product wants an explicit slug list, this is the one function to replace.**

4. **`AiScoreEvalTest`'s recorded transcript is synthetic.** There is no device and no model, so I wrote a plausible 12-line response into the fixture and the test enforces the **format** gate against it (12 distinct indices, scores in range, closed vocabulary, `why` where required, items 5 and 6 present) plus the four ordering assertions. The band data is in the fixture so the on-device calibration gate is a fixture read, not a rewrite. **Do not read a passing `AiScoreEvalTest` as "the model is calibrated".**

5. **`AiTriageWorkerTest` is named `AiScoringPipelineTest`.** The Worker lives in `src/mlGemma`, and `src/test` is shared across variants — a test naming it would break `testOssMlNoneDebugUnitTest`'s compile. The test drives `AiTriagePipeline.withScoring(...)`, which is the Worker's entire body minus the notification. The garbage-`FakeLlm` assertion the plan demands is there and passes: 6 articles in, 6 `AI_SCORE` rows out, all `LLM_SCORE IS NULL`, all still `selected`.

6. **`ai_model_row.xml` uses `ProgressBar` + `Button`, not `Chip` + `LinearProgressIndicator` + `MaterialButton`.** The doc's layout references `@style/Widget.Material3.Chip.Assist`, `@drawable/ic_more_vert_24`, `?attr/colorOnSurfaceVariant` and `@dimen/spacer_2x`, none of which I could confirm exist in this project's themes/dimens. Framework widgets compile everywhere and `lint` (abortOnError) stays green. Cosmetic; swap them once someone checks the theme.

7. **`ScoreLineParser`'s line regex is deliberately lax on the score field** (`([^|]*?)` rather than `([0-9])`). A strict group would make `2.5` fail to match *at all* — a dropped article — whereas the guarantee table says it must be coerced. The strict shape is the grammar's job.

8. **Whole-prompt echo is not a total parse failure**, and the test says so honestly. `prompt_score_user.txt` contains its own worked example (`2|3|regulation,on-demand-transport|…`), which is byte-identical in shape to a real answer. `wholePromptEchoNeverProducesACompleteBatch` asserts the property that actually matters: an echo never yields a full batch, so the repair ladder always fires.

9. **`AiEngineManager.shutdownNow` does not block on the gate.** It cancels the active token unconditionally and only closes the warm engine if it can `tryLock`. Blocking would deadlock: it is called from `onTrimMemory` on the main thread while a worker holds the lock. The worker's own `finally` does the close.

10. **`PREF_AI_LAST_RUN` timestamp is written to `pref_ai_last_run_at`**, a different key from the display-only `Preference` — storing a `long` under a Preference's own key is a latent `ClassCastException` in the preference framework.

11. **`AiModelDownloader` uses `InputStream`/`FileOutputStream`, not Okio.** The doc's snippet uses `Okio.appendingSink`; plain streams have zero API risk against whichever Okio version OkHttp 5.3.2 drags in, and the loop is identical.

## Left undone / open questions

- **Nothing here has ever talked to a real model.** `LiteRtLlm`, `LiteRtConversation` and `AiModelProbe` compile and their signatures are `javap`-verified, but `Engine.initialize()` has never been called. S2 (does `ResponseFormat.regex` with `{N}` work on E2B?), S6 (cold load time), S5 (`TextEmbedder` without datatransport) all still need hardware. **`AiScoreGrammar.forAnyLength()` is the documented fallback if `{N}` deadlocks.**
- **The download has never run against the real CDN.** The resume logic, the 206 handling and the `x-linked-size` check are unit-tested only at the verify/sidecar level (`AiPartMetaTest`); there is **no MockWebServer test of the transfer loop**. `mockwebserver` is already a `testImplementation` dep — that is the cheapest next test to write.
- **`cb_ai_downloads_wifi_only`, `sp_ai_run_trigger`, `sp_ai_min_battery`, `cb_ai_thermal_pause`, `cb_ai_debug_log` are rendered but not enforced.** The `ConnectivityManager.registerNetworkCallback` Wi-Fi gate from doc §2.3 is **not implemented** — the service downloads on any network. Phase 9 owns the run policies; the Wi-Fi one arguably belongs here and I did not build it.
- **No status strip.** `AiDegrade` computes the level and is tested, but nothing renders it — the `ConcatAdapter`/`AiHeaderAdapter` work is still the Phase-2 carry-over. Same for `pref_ai_last_run`'s summary, which is a bare title today.
- **The enrich/abstract/taste prompts are shipped but unused** (Phases 7–8). Only `prompt_score_{system,user,repair}.txt` are wired. `prompt_score_system_tiny.txt` has no code path at all.
- **`AiModelProbe` is only called from the download service**, so a model side-loaded into the directory never gets probed and keeps the catalogue's `sentencePiece` claim.
- **`AiRank.bumpOf` / `AI_META['feed_weight_map']` still unread** — the pipeline passes `feedBump = 0` everywhere.
- ⚠️ **The Phase-3 carry-over is still open**: a swiped article reappears on refresh, because the For You SQL is `WHERE AI_SCORE.STATUS='selected'` and a decision writes `AI_TASTE`, not `AI_SCORE`. Unchanged by this phase.
- **`AiSettingsFragment`/`AiModelManagerActivity` have no tests.** The Dagger wiring compiles; that the fragment actually writes to the right prefs file is unverified on a device.

## What the next phase must know

- **`AiTriagePipeline.withScoring(Scoring)` is the whole scoring entry point.** `Scoring == null` is a first-class, tested configuration (similarity-only). `AiTriagePipeline.Scoring` carries model, prompts, note, slugs, corrections, locale, gpu, batch, token.
- **`AiEngineManager.forTesting(FakeEmbedder, FakeLlm)`** is how you drive anything engine-shaped from plain JUnit. `FakeLlm.perfect()/garbage()/exploding()` already exist, plus a `Script` lambda taking `(userText, turnIndex)`. `FakeLlm.countArticles(userText)` lets a script branch on batch size — that is how the split-to-1 test distinguishes turns.
- **`withLlm` keeps the engine warm for 90 s after it returns.** A digest worker that runs right after triage with the *same* `catalogId` reuses the load; a different id evicts. Never call it from the main thread — it throws `IllegalStateException` by design.
- **`AiModelRepository.resolveLlm(prefs, key)` is the only sanctioned way to get an `AiModelInfo`.** It re-stats the file every call, so a deleted model can never reach the engine. `AI_MODEL.PATH` is a cache, not the truth.
- **`AI_META` keys now claimed**: `model_sp:<id>`, `model_load_ms:<id>`, `model_suspect`, plus the existing `schema_version`, `decision_count`, `centroid_unit_cache_key`, `embed_backlog_cursor`, `active_sync_id`, `star_seed_done`. Still free: `feed_weight_map`, `interests_hash`, `digest_dismissed_day`.
- **New prefs**: `sp_ai_score_batch` (0/absent = auto), `cb_ai_enrich_enabled`, `cb_ai_learn_enabled`, `pref_ai_last_run_at` (long).
- **`AiEngines` remains the flavor seam** and now has four methods (`embedderSupported`, `openEmbedder`, `llmSupported`, `openLlm`). Add to one copy and you must add to the other or `mlNone` stops compiling.
- **The drawer row is now gated on `AiFeature.isEnabled`**, i.e. it disappears when `cb_ai_enabled` is off (default). Anything expecting "For you" to exist on a fresh install must flip that pref first.
- **`ScoreLineParser.merge(first, repair)` — existing lines win.** If Phase 7 adds another repair stage, keep that direction.
- SQLite 3.9 rule still holds (no UPSERT). All new SQL this phase is plain `SELECT` (`AiCorrections`, `feedTitles`).