# THE PLAN — veille → news-android-ai, on-device Gemma

Status: decisions made. Hand to implementers verbatim. Every file:line reference below was verified by product, engineering or validation in this cycle; claims marked SPIKE are unverified and have a resolving test in §3.

Vocabulary: the virtual folder id is **`AI_FOR_YOU(-14)`** (validation reports call the same id `AI_DIGEST(-14)` — same row, use `AI_FOR_YOU`).

---

## 1. Decisions

| # | DECISION | CHOSEN | REJECTED ALTERNATIVE | WHY |
|---|---|---|---|---|
| D1 | Where AI data lives | **Same DB file `OwncloudNewsReaderOrm.db`, own raw-SQL tables** (`AI_SCORE`, `AI_EMBEDDING`, `AI_DECISION`, `AI_TASTE`, `AI_CENTROID`, `AI_RUBRIC`, `AI_DIGEST*`, `AI_MODEL`, `AI_META`), created by `AiSchema.createOrMigrate(db)` called from `DatabaseHelperOrm` between `getWritableDatabase()` and `new DaoMaster(db)` | Separate `.db` file + `ATTACH`; separate file + Java-side ordering (`WHERE _id IN (…)`) | The brief's premise (PART D#1) is **wrong**. `database/model/DaoMaster.java:28-33` `dropAllTables()` drops exactly four named tables (Folder/Feed/RssItem/CurrentRssItemView); `DevOpenHelper.onUpgrade` (:55-58) calls only that. Tables greenDAO does not know survive a schema bump, `resetDatabase()` (`DatabaseConnectionOrm.java:78-83`) and Clear-cache. Same file kills the ATTACH-in-`runInTx` blocker, the multi-process (`:downloadWebPageProcess`, `:remote`) `SQLITE_BUSY` risk, and the second Java implementation of `onlyUnread`/search/dedupe. **Engineering won over product + brief.** Guarded by `AiSchemaTest`. |
| D2 | Durable identity | **Derived `AI_KEY` = `fingerprint`, or `"id:"+id` when fingerprint is empty.** `AI_SCORE` keyed on `RSS_ITEM_ID` (transient), everything durable keyed on `AI_KEY` | Key everything on `fingerprint` (product §1) | `InsertRssItemIntoDatabase.java:75` defaults fingerprint to `""`, not `null`, so the `null` guard at :91-93 never fires. A server omitting the field would give **one AI row for the entire corpus**. Split identity: `RSS_ITEM_ID` for the list JOIN, `AI_KEY` for taste/embeddings — which also makes the taste model survive Clear-cache for free. |
| D3 | How the list sorts by score | **Plain `JOIN AI_SCORE` inside `getAllItemsIdsForFolderSQL()`, early-return branch, no table aliases, `ORDER BY AI_SCORE.RANK_SCORE DESC, PUB_DATE DESC, _id DESC`.** Scores are **fanned out over `AI_KEY`** on write so every fingerprint-duplicate carries identical rank | ATTACH; denormalise into `CURRENT_RSS_ITEM_VIEW` from Java; alias the tables | `NewsReaderDetailFragment.java:538-543` blindly injects a bare `" GROUP BY FINGERPRINT "` at `indexOf("ORDER BY")`. With aliases it still parses and SQLite silently picks an arbitrary group member for `RANK_SCORE` → a list that renders, scrolls and pages correctly while being mis-ranked. Fan-out makes the arbitrary pick provably harmless. **`-14` must also be added to the exclusion set at `:611`** or it falls into the real-folder branch (`WHERE f._id = -14`, zero rows, no error). |
| D4 | Sort order pref | **`SP_SORT_ORDER` is ignored for `AI_FOR_YOU`** — the parameter is dropped inside the SQL builder, with a comment | Wire it "for consistency" | It is a relevance list, not a timeline. Dropping it inside the builder is the one place a future refactor cannot silently re-enable it. |
| D5 | The like/dislike signal | **New explicit two-polarity signal.** "More like this" / "Less like this". Swipe right/left **inside For You only** (overrides global prefs); new global swipe values `"4"`/`"5"` opt-in; two fast-action buttons in the reader, GONE unless `cb_ai_enabled`. Mandatory 5 s Snackbar UNDO writing an append-only `undo` row. One-time opt-in seed from ≤50 most recent starred | Reuse `starred` as keep; implicit "opened + scrolled" positive | Star has no negative twin, is server-synced, is the default right-swipe (`SettingsActivity.java:102`) and its distribution is "didn't have time" → biases the liked centroid toward long articles. Implicit positives cannot produce a negative, and `CB_MARK_AS_READ_WHILE_SCROLLING` means "read" here measures scroll velocity, not taste. Dwell-time weak positive is **out of v1 and stays out**. |
| D6 | Menu entry | **One entry, "For you", `AI_FOR_YOU(-14)`, first in the drawer, gated on device support.** Badge = `STATUS='selected' AND READ_TEMP != 1`. Digest is a card inside it, not a second row | Two entries ("For you" + "Digest"); a separate periodic digest screen | 5–20 items/day split across two rows makes both look abandoned. Badge lands via **one line** in `DatabaseConnectionOrm.java` after `:741` (`values[0].put(AI_FOR_YOU…)`) — `getGroupView():279-284` and the only-unread purge at `:518-519` both read that map generically, so **no branch at :286 and no purge exemption at :523 are needed**; the brief's checklist items 4 and 7 are obsolete. |
| D7 | Landing folder | **Unchanged — still `ALL_UNREAD_ITEMS`** | For You as default destination (product §2) | Changing it breaks AC #30 for every user who never enables AI, and lands a fresh install on a recency list under an AI header. Drawer position 0 is the discovery mechanism. **Engineering won.** |
| D8 | minSdk | **21 → 24, globally** | `tools:overrideLibrary` | Both AARs declare `minSdkVersion="24"`; manifest merge hard-fails (reproduced). The override is blocked by `gradle.properties:30 android.usesSdkInManifest.disallowed=true` and would only defer a guaranteed `UnsatisfiedLinkError`. Zero `LOLLIPOP`/`JELLY_BEAN` references in `src/main`. Do **not** go to 31. |
| D9 | ABI | **`ndk { abiFilters 'arm64-v8a', 'x86_64' }` on the AI flavor only.** Runtime hard gate: `Build.SUPPORTED_64_BIT_ABIS` must contain **`arm64-v8a`** | Universal APK | `litertlm-android:0.15.0` ships **only** arm64-v8a + x86_64 (`.so` verified). A universal APK installs on armeabi-v7a and dies in `System.loadLibrary` inside a background service. x86_64 is kept solely so `connectedAndroidTest` runs. Copy is "needs a 64-bit **ARM** processor", not "64-bit". |
| D10 | Distribution isolation | **Second flavor dimension `ml` = `mlNone` \| `mlGemma`.** `mlNone` = today's dependency graph byte-identical (F-Droid/oss path). `mlGemma` = arm64, `applicationIdSuffix ".ai"`, excludes `com.google.android.datatransport` | Dynamic feature module; single flavor | Dynamic delivery needs Play AAB; this ships APKs to F-Droid. MediaPipe `tasks-core` pulls Google Clearcut telemetry into an app whose `PRIVACY.md:3` promises no data leaves the device. Cost absorbed: 8 variants, duplicated `AiModule` FQN in two source sets, CI task pinning, applicationId-suffix stacking check against `@string/authority`. |
| D11 | Embedding model + runtime | **EmbeddingGemma `.task` from the MediaPipe CDN** `https://storage.googleapis.com/mediapipe-models/text_embedder/embedding_gemma/int4int8/latest/embedding_gemma.task` (183,816,181 B, **ungated plain GET**), via `com.google.mediapipe:tasks-text:1.0.0` `TextEmbedder` | `litert-community/embeddinggemma-300m`; Gecko; MobileBERT | That HF repo is **gated (401)** and ships 33 bare `.tflite` with **no `.task` bundle** — unusable. Gecko is 64-token input and English-centric (794 KB SP vocab, 1.1k CJK seqs) — reject. Fallback ladder is **EmbeddingGemma → USE-QA** (6.12 MB, 100-dim, cross-lingual), never Gecko. Resolves brief PART D open question. |
| D12 | Embedding contract | **`CLUSTERING` + `TextRole.QUERY`, one `TextFormatContext` built once and reused; `setL2Normalize(true)`, `setQuantize(false)`; text clipped to 1400 chars; float32 **little-endian** BLOB byte-identical to veille `embed.py` pack(); `MODEL`+`TASK` persisted per row.** The 1-arg `embed(String)` is **banned** | `SEMANTIC_SIMILARITY`; MRL truncation to 128/256; `TextEmbedder.cosineSimilarity` | 1-arg `embed` applies no task prefix → prefixed and unprefixed vectors live in different regions, cosine is garbage, **no error, no symptom** except a taste model that never converges. The operation is group membership, not STS. The graph input is a static `[1,512]` INT32, so 2000 chars silently overflow the tokenizer. MRL buys storage only (58 MiB at 20k rows; we GC to cache size anyway) at −1.5 to −2.9 MTEB. `cosineSimilarity` throws on zero-norm and can't express `cos(liked) − cos(rejected)` with a missing centroid contributing 0. |
| D13 | Scoring model, T2 (≥6 GB) | **`litert-community/gemma-4-E2B-it-litert-lm`, 2,588,147,712 B, sha256 `181938105e0eefd1…`, ungated** | Gemma3-1B-IT | Ungated (302→200 anonymous, verified), docs default, and — decisive — SentencePiece tokenizer (67,583 `▁` pieces in the first 2 MB) ⇒ **grammar-constrained decoding is available**. |
| D14 | Scoring model, T1 (3–6 GB) | **`litert-community/Qwen3-0.6B` `qwen3_0_6b_mixed_int4.litertlm`, 497,664,000 B, ungated** | `Gemma3-1B-IT` (product's T1 default) | Every Gemma-3 repo returns **401 `GatedRepo`** with `www-authenticate: Bearer`; browser licence acceptance does **not** unblock an anonymous request — a token is mandatory. Making the low-end default require an HF account + token paste is a wall, not a default. Cost: Qwen is BPE ⇒ no constrained decoding on T1, which is exactly what the unconstrained line-parser + repair ladder already handles. `Gemma3-1B-IT` stays in the catalogue as an advanced token-gated entry; promote it only if the §6 eval shows it materially beats Qwen. |
| D15 | Model per stage | **Embedding: EmbeddingGemma (mandatory floor, never `__off__`). Triage: E2B (T2) / Qwen3-0.6B (T1). Digest abstract: `__same_as_triage__`. Enrich: `__off__` (T2-only capability). Rubric learn: `__off__`, manual button only.** One LLM resident, ever | Enrich on by default; bilingual output; an LLM call for the notification line; digest-prompt learn | Enrich is 3–8k tokens × 20 items, the highest hallucination surface, and veille's own degrade path says "skip enrich → keep stage-2 why-lines". Bilingual `why_fr`+`why_en` → **one `why` in the device locale** (halves the highest-volume decode). Notification text = top item's title + its `why`. |
| D16 | Output format | **One wire format for both tokenizer families: pipe-delimited lines `n\|score\|tags\|why`, enforced by `ResponseFormat.regex(...)` where the tokenizer is SentencePiece, unconstrained + repair ladder otherwise.** `enableResponseFormat=true` is the 12th `ConversationConfig` arg | JSON everywhere (dev:data-and-pipeline); "assume constrained decoding does not work" (product:prompt-adaptation §0) | Both partly right. `Conversation.resolveResponseFormat` applies the schema unchanged when **no tools are registered** (our case) — proven from 0.15.0 bytecode; LLGuidance backs it. But a JSON path would mean two prompts, two parsers, two eval baselines for T1-vs-T2. Regex over one line format gives one of each, makes the structural guarantee mechanical (exactly N lines, index 1..N, score 0-3, no pipe in `why`), and avoids LLGuidance's `oneOf` gap. **Keep every product prompt-*content* edit; discard its "no constraint possible" premise.** The code-side parser is never deleted — the grammar guarantees shape, not that the model didn't renumber or hallucinate a tag. |
| D17 | Scoring batch size | **4 when constrained decoding is confirmed on the selected model; 1 otherwise. Pref `sp_ai_score_batch`, forced 1 for models < 500 MB. Repair ladder: parse → 1 repair turn on the same `Conversation` → re-run only the missing indices as batch-of-1 with a fresh `Conversation` → `LLM_SCORE = NULL`** | Always 1 (dev:data); always 4 (dev:runtime/product) | Batch size is driven entirely by alignment risk; the grammar removes alignment risk on SP models. Unconstrained, batch 1 collapses the problem to nothing at a cost of ~25 s per 30-article run. One `Conversation` **per batch**, closed after its repair turn — repair needs the prior turn, batch N+1 must not inherit batch N's KV cache. |
| D18 | Background execution | **`androidx.work:work-runtime:2.10.x` (Java `Worker`, not `-ktx`), unique work `"ai_triage"`, `ExistingWorkPolicy.KEEP`, enqueued from `OwnCloudSyncAdapter.onPerformSync()` after `startFaviconDownload()` (:85) and before `syncRunning=false`.** Foreground `dataSync`. Model downloads use a plain foreground `Service` + OkHttp 5 | Inline in `onPerformSync`; `JobIntentService`; copying `DownloadWebPageService` | `onPerformSync` is synchronous and its `syncRunning` flag drives `SyncStartedEvent`/`SyncFinishedEvent` — minutes of inference inline pins the UI in "syncing" and blocks periodic syncs. `JobIntentService` routes to `JobScheduler` (~10 min window) on API 31+. `DownloadWebPageService` is **not** a `JobIntentService` (`:48 extends Service`) and its manifest entry (`AndroidManifest.xml:209-212`) has no `foregroundServiceType` — it is **already crashing on targetSdk 34+**. Copy its notification/executor shape, not the class. Fix its manifest entry in the same PR. |
| D19 | Engine lifecycle | **`AiEngineManager`: refcounted, one `Engine` **or** one `TextEmbedder` resident at any instant, process-wide `ReentrantLock`, 90 s idle keep-alive, `ComponentCallbacks2`. Never `@Provides Engine`.** Strict sequence: embed all → `close()` → prefilter (pure SQL/math) → open LLM → score → digest → release | Dagger `@Singleton Engine` | `@Singleton` has no lifecycle so nothing ever `close()`s multi-GB of mmapped weights, and `NewsReaderApplication.onCreate` runs in **every** process (`:downloadWebPageProcess` at manifest :212, `:remote` at :290) → one engine per process. Native inference serialises anyway (`ResourceManager` mutex); the lock makes it explicit and prevents two `initialize()` allocations overlapping. |
| D20 | Settings shape | **Two rows in `pref_ai.xml` appended as the 5th resource in `SettingsFragment.onCreatePreferences()`; a separate `AiSettingsActivity` hosting `pref_ai_detail.xml`; a separate `AiModelManagerActivity` (RecyclerView, not a preference screen)** | `OnPreferenceStartFragmentCallback` nested screens | The model manager has progress bars and per-row buttons — not expressible as a preference list, so a second Activity is needed regardless. Precedent: `PREF_TTS_SETTINGS` → `startActivity` (`SettingsFragment.java:255-268`). `SettingsActivity`/`SettingsFragment` stay untouched except two lines + `bindAiPreferences`. |
| D21 | Per-stage model pickers | **`sp_ai_model_triage` = real `ListPreference`. `sp_ai_model_embedding` = non-clickable status `Preference`. Digest/enrich/learn = two `SwitchPreference` (`cb_ai_enrich_enabled`, `cb_ai_learn_enabled`, T2-only), implying `__same_as_triage__`; the `sp_ai_model_*` keys still exist and the resolver is unchanged** | Five `ListPreference`s (product §3 rows 4-7) | There is exactly one embedder, from a different CDN in a different format, and `TextEmbedderOptions` has no dimension setter — a perpetually single-entry disabled picker reads as broken. Three pickers whose only realistic values are two sentinels are settings noise. |
| D22 | HF gating flow | **Two regimes.** Ungated (E2B, E4B, Qwen, SmolLM, the CDN embedder): plain GET, no dialog. Gated (all Gemma-3): **401**, token mandatory → `edt_ai_hf_token` is **always visible**, flow is "accept terms in browser → mint token → paste" | Product §S2: browser-accept + `licence_ack_<repo>` + reveal the token field after two consecutive **403**s | Verified live: E2B `gated:False`, 302→200 anonymous; Gemma-3 repos `gated:auto`, **HTTP 401 `x-error-code: GatedRepo`, `www-authenticate: Bearer`**. Product's spec has a licence flow for a model that needs none and hides the only control gated models require behind an event that never occurs. Resolves product open item R4. |
| D23 | Download resume | **Persist only the `resolve/main/...` URL and re-resolve every attempt. `Range: bytes=N-`. Validate with `x-linked-size` + final SHA-256. No `If-Range`** | `If-Range: <etag>` (product §S4) | The HF CDN URL is signed with `Expires=` ~15 min (resume after a lunch break 403s from CloudFront) and its `etag` is the xet content hash, different from the origin's `x-linked-etag` (= the sha256). Both verified. |
| D24 | Disk & RAM gates | `DOWNLOAD_OK := externalFree ≥ size + 256 MB` **and** `internalFree ≥ 500 MB` (the `cacheDir` volume). `SELECT_OK := totalMem ≥ entry.minTotalRamBytes` (a **catalogue data field**, not a formula). Tiers on `ActivityManager.MemoryInfo.totalMem`: T0 <3 GB, T1 3–6, T2 ≥6 | `size × 1.15 + 250 MB`; `getMemoryClass()`; `availMem` for tiering | `.part → final` is a `renameTo` on the same fs, no 1.15× headroom; the rule over-charges 390 MB on E2B and **guards the wrong volume** — the compiled cache lands on internal `getCacheDir()`. `getMemoryClass()` bounds the Java heap; LiteRT mmaps natively — using it excludes every device. `availMem` flaps between launches. `minTotalRamBytes` as data lets the S1 spike replace guesses without touching code. |
| D25 | Smoke-load watchdog | **180 s**, inside the download foreground service with a "Preparing model…" line; measured load time recorded in `AI_META`, subsequent watchdog `3 × measured` | 45 s (product §S7) | First load of a 2.588 GB model writes the rearranged-weight/XNNPACK cache from scratch — plausibly 60–150 s mid-range. 45 s marks a good model BROKEN after a 2.6 GB download. |
| D26 | GPU backend | **`cb_ai_gpu_backend` defaults `false` on every tier**; smoke loads always CPU | `true` on T2 (product row 14) | Zero LiteRT GPU data for any model on any device; the only GPU numbers we have (embedder: 1445 ms init, 762 MB RSS vs 24.9 ms / 123 MB CPU) point the wrong way. GPU-delegate OOM presents as "the AI folder is permanently empty" on exactly the flagships this targets. Flip with data, not before. |
| D27 | Kotlin vs Java | **Java for all new logic** — engine facade, pipeline, parsers, stores, workers, activities. **Kotlin only for the two new RecyclerView view holders**, matching the 9 existing `.kt` holders in `adapter/` | Kotlin + coroutines throughout | LiteRT-LM's blocking `sendMessage(...)` and callback `sendMessageAsync(..., MessageCallback)` overloads are fully Java-callable (compiled and verified against 0.15.0); only the `Flow` overload needs coroutines and it is avoidable. `TextEmbedder` is Java-friendly. `detekt` (`maxIssues: 0`) and `spotlessCheck` target **Kotlin only** (`build.gradle:77-82`), and `detekt.yml:159 TooGenericExceptionCaught` forbids exactly the `catch (Exception e)` shape a never-lose-an-article parser requires. Java satisfies both gates trivially and matches 146-Java/31-Kotlin. |
| D28 | Status vocabulary | **Two vocabularies, two tables.** `AI_SCORE.STATUS ∈ {queued, selected, discarded}` (machine). `AI_TASTE.STATE ∈ {kept, rejected}` (human). `AI_DECISION` append-only, never updated, never deleted | `AI_SCORE.STATUS='kept'` (validate:android-risk risk 1) | veille's most carefully guarded invariant: *the pipeline never writes `kept` — that is a human's word.* One table holding both means some future path writes `kept` from a scoring result and the disagreement signal — the entire input to the learn loop — becomes unrecoverable. |
| D29 | Warm-up gate | **`liked.n < 3 \|\| rejected.n < 3 \|\| total < 10 ⇒ treat as cold`** — a one-line strengthening of veille's existing `cold_start` predicate, not a new subsystem | A separate "gate" branch (product §2 framing) | veille already refuses to rank when **either** centroid is missing. Framing the gate as new invites a parallel branch that drifts from the prefilter. Number (10 / ≥3 each) accepted as a shipping default. |
| D30 | Row-level progressive fill | **Cut from v1.** Shimmer bar stays until the user leaves and re-enters the folder or pulls to refresh. The status-strip counter may update live | Per-row `notifyItemChanged` as scores land (product §4) | `NewsListRecyclerAdapter` has no observer/`DiffUtil`; live per-row updates on a `wrap_content` row whose why-line appears **do** change height and push rows below — the exact "content shifting under the thumb" product §4 forbids. |
| D31 | List header (strip/onboarding/digest card) | **`ConcatAdapter`** (recyclerview 1.4.0, already a dep): `AiHeaderAdapter` + `NewsListRecyclerAdapter`. All 10 `(NewsListRecyclerAdapter) getAdapter()` casts become a `newsAdapter` field/accessor, same commit | New view types at position 0 in `NewsListRecyclerAdapter` | That adapter has hard positional coupling at `:354`, `:285`, `:365`, `:468-473`, and `NewsReaderDetailFragment:423` does an **unchecked** `(RssItemViewHolder)` cast on `findViewHolderForLayoutPosition` → guaranteed `ClassCastException` on first scroll with a card at position 0. Accepted trade: the strip scrolls away (see §2 Q4). |

---

## 2. Open questions for the human owner

| Q | Question | Recommended default — start work on this |
|---|---|---|
| **Q1** | **Distribution.** Does the project ship an arm64-only, non-F-Droid `mlGemma` artifact carrying two Google binary blobs (unbuildable from source, one of which pulls Clearcut telemetry)? | **Yes, as a separate variant.** `mlNone` remains the F-Droid/oss build with a byte-identical dependency graph to today. This is a policy call against `PRIVACY.md:3` and the `// 100% Open-Source Edition` comment — settle it in conversation, not in Gradle. All engineering below is written so `mlNone` is unaffected either way. |
| **Q2** | Do we bump the app's global `minSdk` to 24 for **all** flavors (D8), dropping API 21-23 users from the plain reader too? | **Yes.** Flavor-scoped minSdk is possible but doubles the manifest-merge surface for marginal reach; API 21-23 is <1% and the codebase has zero references to those levels. |
| **Q3** | Warm-up gate = 10 decisions (≥3 each polarity); digest trigger = ≥5 newly-selected items/day. Both are guesses. | Ship as-is. Re-measure in dogfood: Spearman ρ between consecutive rankings as decision count grows (gate), and the observed daily selected-item distribution (digest — if the median day is 3, the digest becomes weekly). |
| **Q4** | The status strip **scrolls away** with the `ConcatAdapter` header (D31). A sticky strip requires converting `fragment_newsreader_detail.xml` from `FrameLayout > SwipeRefreshLayout > RecyclerView` to a vertical wrapper, changing empty-view geometry and the FAB anchor for **every** folder, plus re-testing `FastMarkReadMotionListener` (`:730-887`, absolute screen coords). | **Ship scrolling.** Revisit only if dogfood shows users miss "Scoring failed". |

Everything else in the product and engineering specs is settled in §1.

---

## 3. Spikes — Phase 0, run in parallel, none blocks Phase 1

| # | Unknown | Exact resolution | If the answer is bad |
|---|---|---|---|
| **S0** | Dependency resolution under `android.dependency.useConstraints=true` with `kotlin-reflect:2.2.21` vs `ext.kotlin_version='2.2.10'`, and the guava 27 / `listenablefuture` collision | `./gradlew :News-Android-App:dependencies --configuration ossMlGemmaDebugRuntimeClasspath` on a spike branch. **Run this first, before any code.** | Bump `ext.kotlin_version` to `2.2.21`; if AGP's built-in Kotlin pins otherwise, add an explicit `constraints { }` block. |
| **S1** | Resident RSS and tok/s for `gemma-4-E2B` (T2) and `qwen3-0.6b-int4` (T1) on real phones | `Debug.getMemoryInfo()` + `adb shell dumpsys meminfo` around a 30-article run on D-high (≥8 GB) and D-low (4 GB). Record wall time. | Replace `minTotalRamBytes` in the catalogue JSON with measured values (no code change — D24). If T1 misses 15 articles / 5 min or >15% post-repair failure, **demote T1 to embeddings/centroids only** — the taste model alone is a shippable feature. |
| **S2** | Does `ResponseFormat.regex("(?:[1-9][0-9]?\|[0-3]\|[a-z0-9,\-]{0,80}\|[^\n\|]{0,160}\n){4}")` work end-to-end on E2B, and does it deadlock the decoder when the model wants to stop early? | The §4 `AiModelProbe` second half, run once on device. Catch `LiteRtLmJniException` containing *"Constrained decoding is only supported for SentencePiece tokenizer"*. | Drop the `{N}` quantifier for `(?:...\n)+`. If regex is unusable entirely, set `sentencePiece=false` in `AI_META` for that install, force batch = 1, and the unconstrained repair ladder carries the load — the feature still ships. |
| **S3** | Does `TextEmbedderGraph` honour `Delegate.GPU` / `Delegate.NPU`? | One device run with `setDelegate(Delegate.GPU)`; check for graph-init error. | Stay on CPU (the shipped default). Only cost is a slower first-sync backfill; `AI_EMBED_MAX_PER_SYNC` already bounds it. |
| **S4** | Does `embed(text, CTX)` prefix once or twice (Java `getGeckoEmbeddingText` vs the C++ graph)? | On device: `embed("x", CTX)` vs `embed("task: clustering \| query: x")`, compare `floatEmbedding()` element-wise. **Must run before any user vector is written.** | If double-prefixed, pass raw text and build the prefix ourselves — but the stored `TASK` column must then record which convention produced each vector. |
| **S5** | Does `tasks-text` still initialise with `com.google.android.datatransport` excluded? | Build `mlGemma` with the exclude, call `TextEmbedder.createFromOptions` on device, watch for `NoClassDefFoundError`. | Replace the exclude with a stub source set providing no-op `datatransport` classes. **Do not revert the exclude** — Q1 depends on it. |
| **S6** | First-load wall time for E2B with a cold `cacheDir` | Time `Engine.initialize()` twice: cold cache, then warm. | Sets the D25 watchdog. If >180 s, raise and add a progress line; do not lower. |
| **S7** | R8 full-mode survival of the JNI bridge | `./gradlew :News-Android-App:assembleOssMlGemmaRelease` + an on-device smoke run. Cannot be validated by compiling. | Extend the keep rules in §4.6. This must be in the **definition of done for the first AI PR**, not deferred. |
| **S8** | Is `systemInstruction` re-prefilled per `createConversation`? | Time 10× (`createConversation` + 1-article call) vs 1 conversation + 10 turns; read `Conversation.getBenchmarkInfo()`. | Informational only — moves `sp_ai_score_batch`'s default, no redesign. |
| **S9** | Duplicate ContentProvider authority from stacked `applicationIdSuffix` `.dev` + `.ai` | Install `devMlGemmaDebug` alongside `devMlNoneDebug`. `AndroidManifest.xml:193` uses `@string/authority`, **not** `${applicationId}`. | Make `@string/authority` flavor-scoped in `src/mlGemma/res/values/strings.xml`. A collision makes the app uninstallable alongside the other flavor. |

---

## 4. Architecture

### 4.1 Data flow

```
OwnCloudSyncAdapter.onPerformSync()          [unchanged, +1 line after :85]
  └─ AiTriageScheduler.enqueueAfterSync()    enqueue only, never inline
       └─ AiTriageWorker.doWork()            WorkManager, foreground dataSync, ONE return: Result.success()
            1  AiCandidates       unread ∧ (PUB_DATE IS NULL ∨ ≥ now-7d)        [veille stage 3]
            2  AiEngineManager.withEmbedder → MediaPipeEmbedder
                 embed ≤60 new AI_KEYs, newest first, backlog cursor in AI_META  [stage 4]
                 close()                                                          ← MANDATORY before LLM
            3  AiCentroidStore.backfill() + unit()      pure SQL + math, no model
            4  AiPrefilter                  cold ⇒ recency top-k; warm ⇒ sim≥0.05,
                                            sort sim DESC, cap top-k, THEN unscored
                                            fill remaining room by recency        [stage 5]
            5  AiEngineManager.withLlm → LiteRtLlm
                 for each batch: PromptTemplate → LlmCall.run → ScoreLineParser
                 → repair turn → split to batch-1 → LLM_SCORE=NULL                [stage 6]
                 commit ONE UPDATE per article (the table IS the checkpoint)
            6  AiRank  eff = clamp(llm + feedBump,0,3); rank = round(eff + 0.5*sim, 3)
                       llm IS NULL ⇒ rank = round(sim,3)                          [stage 8]
                       STATUS = eff>=2 ? 'selected' : 'discarded/low_score'
                       fan out the row over AI_KEY                                ← D3
            7  AiNotify  top item title + its why. No LLM. Off by default.        [stage 9]

AiDigestWorker.doWork()   lazy, first open of a new day, ≥5 newly selected, reuses the warm Engine
User swipe/fast-action → AiDecisionStore.decide() → AI_DECISION (append) + AI_TASTE (fold)
                       → AiCentroidStore.onDecision() (incremental ± , UNIT=NULL)
```

**Stages dropped:** `_stage_fetch` (the SyncAdapter is the fetcher), `_stage_dedupe` (the app's `GROUP BY FINGERPRINT` + the `AI_KEY` fan-out already give the effect; near-dup Jaccard over a 400-title window is not worth it on a phone), `_stage_enrich` (capability retained, `__off__`), digest-prompt learn.

### 4.2 DDL — `database/ai/AiSchema.java`

Created idempotently in `DatabaseHelperOrm.getDaoSession()` **between** `helper.getWritableDatabase()` and `new DaoMaster(db)` (so a greenDAO upgrade in the same call has already completed). Versioned by `AI_META.K='schema_version'`; we own our migrations.

```sql
AI_META      (K TEXT PK, V TEXT, B BLOB)
             -- schema_version, decision_count, interests_hash, last_run_*, embed_backlog_cursor,
             -- active_sync_id, feed_weight_map(JSON), digest_dismissed_day, model_load_ms

AI_SCORE     (RSS_ITEM_ID INTEGER PK,          -- == RSS_ITEM._id (Nextcloud server id). TRANSIENT.
              AI_KEY TEXT NOT NULL,            -- fingerprint | 'id:<n>'
              STATUS TEXT NOT NULL DEFAULT 'queued',   -- queued|selected|discarded   NEVER 'kept'
              DISCARD_REASON TEXT,             -- low_similarity|below_top_k|low_score|out_of_window
              SIM_SCORE REAL,                  -- NULL = never embedded; 0.0 = cold start. LOAD-BEARING
              LLM_SCORE INTEGER,               -- NULL = never judged (retry); 0 = judged off-topic (final)
              EFF_SCORE INTEGER, RANK_SCORE REAL,
              THEMES TEXT, FLAGS TEXT, WHY TEXT,   -- comma-joined; THEMES order is load-bearing
              SCORED_AT INTEGER, SYNC_ID INTEGER)
  IDX (STATUS, RANK_SCORE DESC) ; IDX (AI_KEY)

AI_EMBEDDING (AI_KEY TEXT PK, MODEL TEXT NOT NULL, TASK TEXT NOT NULL, DIM INTEGER NOT NULL,
              VEC BLOB NOT NULL,               -- float32 LITTLE-ENDIAN, == veille embed.py pack()
              CREATED_AT INTEGER NOT NULL)     IDX (MODEL, TASK)

AI_DECISION  (_id INTEGER PK AUTOINCREMENT, AI_KEY TEXT NOT NULL,
              ACTION TEXT NOT NULL,            -- keep|reject|restore|undo
              FROM_STATE TEXT NOT NULL, TO_STATE TEXT NOT NULL,
              LLM_SCORE_AT INTEGER,            -- NULL is INFORMATION (never reached the LLM)
              SOURCE TEXT NOT NULL,            -- swipe|fastaction|star_seed|settings
              TITLE_SNAP TEXT, CREATED_AT INTEGER NOT NULL)   -- APPEND-ONLY. never UPDATE/DELETE
  IDX (AI_KEY, _id DESC)

AI_TASTE     (AI_KEY TEXT PK, STATE TEXT NOT NULL,      -- kept|rejected  (undo ⇒ row deleted)
              DECIDED_AT INTEGER NOT NULL, IN_CENTROID INTEGER NOT NULL DEFAULT 0)   IDX (STATE)

AI_CENTROID  (CLASS TEXT PK,                   -- 'kept' | 'rejected'
              MODEL TEXT, TASK TEXT, DIM INTEGER, N INTEGER NOT NULL,
              SUM BLOB NOT NULL,               -- UNNORMALISED running sum
              UNIT BLOB)                       -- cached unit mean; NULL = recompute

AI_RUBRIC    (_id PK AUTOINCREMENT, BODY TEXT NOT NULL, RATIONALE TEXT,
              ACTIVE INTEGER NOT NULL DEFAULT 0, SOURCE TEXT NOT NULL, CREATED_AT INTEGER)
AI_DIGEST    (_id PK AUTOINCREMENT, DAY_KEY TEXT NOT NULL UNIQUE,   -- 'yyyy-MM-dd' device zone
              RANGE_FROM INTEGER, RANGE_TO INTEGER, ITEM_COUNT INTEGER,
              ABSTRACT TEXT, ABSTRACT_STATE TEXT DEFAULT 'pending', -- pending|ok|skipped|failed
              DISMISSED_AT INTEGER, CREATED_AT INTEGER)
AI_DIGEST_ITEM (DIGEST_ID INTEGER, AI_KEY TEXT, RSS_ITEM_ID INTEGER, THEME TEXT,
              RANK_SCORE REAL, POS INTEGER, PRIMARY KEY (DIGEST_ID, AI_KEY))
AI_MODEL     (MODEL_ID TEXT PK, KIND TEXT, PATH TEXT, STATE TEXT,   -- absent|partial|verifying|installed|broken
              SIZE_BYTES INTEGER, RECEIVED_BYTES INTEGER, ETAG TEXT, SHA256 TEXT,
              CAPS TEXT,                        -- {"responseFormat":true,"tokenizer":"sp","loadMs":91000}
              LAST_ERROR TEXT, UPDATED_AT INTEGER)
```

**GC** — `DatabaseConnectionOrm.aiGarbageCollect()`, called from `RssItemObservable.sync()` immediately after the existing `clearDatabaseOverSize()` (`:112`):
```sql
DELETE FROM AI_SCORE     WHERE RSS_ITEM_ID NOT IN (SELECT _id FROM RSS_ITEM);
DELETE FROM AI_EMBEDDING WHERE AI_KEY NOT IN (SELECT AI_KEY FROM AI_TASTE)
                           AND AI_KEY NOT IN (SELECT AI_KEY FROM AI_SCORE);
-- AI_DECISION / AI_TASTE / AI_CENTROID / AI_RUBRIC: never GC'd. They ARE the feature.
```
Steady state ≈ cache size + decided articles × 3,072 B = **3–6 MB**. No vector index, no MRL, no int8.

### 4.3 The list SQL — `DatabaseConnectionOrm.getAllItemsIdsForFolderSQL()`

First branch of the method, early-return (the shared `ORDER BY PUB_DATE` is appended at the end of the method). **`-14` must additionally be added to the exclusion set at `:611`.** No aliases. Exactly one literal `"ORDER BY"`.

```java
if (ID_FOLDER == AI_FOR_YOU.getValue()) {
    String ai = "SELECT " + RssItemDao.TABLENAME + "." + RssItemDao.Properties.Id.columnName
      + " FROM " + RssItemDao.TABLENAME
      + " JOIN AI_SCORE ON AI_SCORE.RSS_ITEM_ID = "
            + RssItemDao.TABLENAME + "." + RssItemDao.Properties.Id.columnName
      + " WHERE AI_SCORE.STATUS = 'selected'";
    if (onlyUnread) ai += " AND " + RssItemDao.TABLENAME + "." + RssItemDao.Properties.Read_temp.columnName + " != 1";
    ai += " ORDER BY AI_SCORE.RANK_SCORE DESC, "
        + RssItemDao.TABLENAME + "." + RssItemDao.Properties.PubDate.columnName + " DESC, "
        + RssItemDao.TABLENAME + "." + RssItemDao.Properties.Id.columnName + " DESC";
    return ai;   // sortDirection deliberately dropped — D4
}
```
`getAllItemsIdsForFolderSQLSearch()` gets **no** AI branch; hide the search menu item for `-14` instead (`NewsReaderListActivity.onCreateOptionsMenu():912`).

Badge, one line after `DatabaseConnectionOrm.java:741`:
`values[0].put(AI_FOR_YOU.getValue(), String.valueOf(getAiSelectedUnreadCount()));`

### 4.4 File list

Root `News-Android-App/src/`; package `de.luhmer.owncloudnewsreader`.

**`main/java/.../database/ai/`** (Java) — `AiSchema`, `AiKeys`, `AiDb` (the only place raw AI SQL lives), `AiScoreStore` (incl. the `AI_KEY` fan-out UPDATE + resume selector), `AiEmbeddingStore` (LE float32 pack/unpack + `assertCompatible()` model/task migration), `AiDecisionStore` (append + fold + the transition table), `AiCentroidStore` (incremental ±, `unit()`, `rebuild()`, `backfill()`, cache key `(decisionCount, model, task)`), `AiRubricStore`, `AiDigestStore`, `AiModelRegistry`.

**`main/java/.../ai/`** (Java, domain — pure, no Android types where avoidable) — `AiFeature` (the single `isEnabled()` gate), `AiCapability` (T0/T1/T2, `hasArm64`, `selectOk`, `downloadOk`, `lowMemoryRightNow`), `AiCandidates`, `AiPrefilter` (**verbatim port including the unscored-fills-remaining-room clause**), `AiSimilarity` (`unit`/`dot`/`sim`, NULL-vs-0.0 sentinels), `AiRank`, `AiText` (clip/sanitise), `AiVec`, `AiDegrade`.

**`main/java/.../ai/prompt/`** (Java) — `PromptTemplate` (single-pass `{{name}}` substitution — **not** chained `String.replace`, which lets scraped `{{articles}}` text be re-substituted), `AiPromptBuilder`, `ScoreLineParser`, `EnrichParser`, `AbstractGuard`, `TasteDraftGuard`, `AiScoreGrammar`.

**`main/java/.../ai/engine/`** (Java) — interfaces in `src/main` so nothing LiteRT leaks: `AiException(Kind)`, `AiOutputMode`, `AiPromptSpec`, `AiLlm`, `AiConversation`, `AiEmbedder`, `AiModelInfo`, `LlmCall`, `CancelToken`, `AiEngineManager`.
**`src/mlGemma/java/.../ai/engine/impl/`** — `LiteRtLlm`, `LiteRtConversation`, `MediaPipeEmbedder`, `AiModelProbe`.
**`src/mlNone/java/.../ai/engine/impl/`** — `NoopLlm`, `NoopEmbedder`.

**`main/java/.../ai/work/`** (Java) — `AiTriageWorker`, `AiTriageScheduler`, `AiDigestWorker`, `AiTasteDraftWorker`, `AiWorkerDeps` (Dagger target; WorkManager instantiates the `Worker`).
**`main/java/.../ai/download/`** (Java) — `AiModelDownloadService` (plain foreground `Service`, `foregroundServiceType="dataSync"`), `AiCatalog`, `AiCatalogEntry`, `AiModelRepository`, `AiPartMeta`.

**`main/java/.../ai/ui/`** — `AiSettingsActivity` + `AiSettingsFragment`, `AiModelManagerActivity` + `AiModelAdapter`, `AiDigestActivity` + `AiDigestAdapter`, `AiRubricDiffActivity`, `AiHeaderAdapter`, `AiEmptyState`, `AiDecisions` (the swipe/fast-action entry point) — all Java.
**Kotlin (2 files only):** `adapter/RssItemAiViewHolder.kt`, `adapter/DigestCardViewHolder.kt`.

**Resources** — `res/layout/`: `ai_list_item.xml` (must `<include layout="@layout/subscription_detail_list_item_podcast_wrapper"/>` — `NewsListRecyclerAdapter:253/293/297/350/363` dereferences the podcast views unconditionally), `ai_digest_card.xml`, `activity_ai_digest.xml`, `ai_digest_abstract.xml`, `ai_digest_theme_header.xml`, `ai_digest_item.xml`, `activity_ai_model_manager.xml`, `ai_model_row.xml`, `ai_header_strip.xml`, `ai_header_onboarding.xml`. `res/xml/`: `pref_ai.xml`, `pref_ai_detail.xml`. `res/raw/`: `ai_model_catalog.json`, `prompt_score_system.txt`, `prompt_score_user.txt`, `prompt_score_repair.txt`, `prompt_score_system_tiny.txt`, `prompt_enrich_system.txt`, `prompt_enrich_user.txt`, `prompt_abstract_system.txt`, `prompt_abstract_user.txt`, `prompt_taste_system.txt`, `prompt_taste_user.txt`. `res/values/`: the ~140 strings + 6 arrays enumerated in dev:ui-and-integration §7 (escape `%%`, `\'`, `&amp;`; use `<plurals>` for countable nouns — `lint` `StringFormatInvalid`/`PluralsCandidate` are error-severity with `abortOnError true`). `attrs.xml`: `aiMoreDrawable`, `aiLessDrawable`; `themes.xml`: bind them in **every** theme that defines `starredDrawable`. Drawables `swipe_ai_more`/`swipe_ai_less` must be **state-list** drawables keyed on `android.R.attr.state_above_anchor`, modelled on `swipe_setstarred` (`onChildDraw():712-715`).

**Files edited** (exhaustive): `gradle.properties`, root `build.gradle`, `News-Android-App/build.gradle`, `News-Android-App/proguard-rules.pro`, `AndroidManifest.xml`, `.github/workflows/ci.yml`, `ListView/SubscriptionExpandableListAdapter.java`, `database/DatabaseHelperOrm.java`, `database/DatabaseConnectionOrm.java`, `reader/nextcloud/RssItemObservable.java`, `authentication/OwnCloudSyncAdapter.java`, `NewsReaderDetailFragment.java`, `NewsReaderListActivity.java`, `NewsDetailActivity.java`, `adapter/NewsListRecyclerAdapter.java`, `SettingsActivity.java`, `SettingsFragment.java`, `di/AppComponent.java`, `di/TestComponent`, `NewsReaderApplication.java`, `res/layout/widget_fastactions_detailview.xml`, `res/layout/empty_content_view.xml`, `res/values/strings.xml`, `src/test/resources/org.robolectric.Config.properties`.

**Two latent bugs, fixed in the same PR** (both become crash paths once AI ships): `NewsReaderDetailFragment.java:423` unchecked `(RssItemViewHolder)` cast (compare the correct `instanceof` at `:436`); `NewsReaderListActivity.DownloadMoreItems()` NPEs on `getFolderById(idFolder).getFeedList()` for any special folder missing from its `specialFolders` list (`-13` already hits it) — add `AI_FOR_YOU`.

### 4.5 Stage → model table

| Stage | T2 (≥6 GB) | T1 (3–6 GB) | T0 | Calls/run | Constrained | Timeout |
|---|---|---|---|---|---|---|
| Embedding | `embedding-gemma-300m` `.task`, 183,816,181 B, MediaPipe CDN, **ungated** | same | — | 1 per **new** `AI_KEY`, cap 60/sync | n/a | 8 s/item, 3 min total |
| Triage/scoring | `gemma-4-E2B` 2,588,147,712 B, sha256 `181938105e…`, ungated, **SentencePiece** | `qwen3-0.6b-int4` 497,664,000 B, ungated, BPE | — | `sp_ai_batch_budget` ÷ batch | T2 **yes** (regex) / T1 no | 120 s per batch-of-4, 45 s batch-of-1, run cap 12 min |
| Digest abstract | `__same_as_triage__` | `__same_as_triage__` | — | 1, lazy on open | no (prose) | 90 s |
| Enrich | `__off__` (opt-in → same-as-triage, or E4B at ≥6.5 GB) | row hidden | — | 0 | yes | 120 s, ≤10 items |
| Taste/rubric learn | `__off__`, manual button | row hidden | — | 0 automatic | yes | 180 s |
| Engine load | — | — | — | — | — | **180 s** watchdog |

Constants: `SIM_FLOOR = 0.05` (unchanged — `sim` is a *difference* of cosines, so the model-specific cosine baseline cancels). `prefilter_top_k` / `sp_ai_batch_budget` = **30 (T2) / 15 (T1)**, values 15/30/60/120, and it is **also** `AI_EMBED_MAX_PER_SYNC`'s ceiling — every candidate must be embedded before the prefilter can rank it. `score_threshold = 2`, no pref. `WEIGHT_SCORE_BUMP {low:-1, normal:0, high:+1, bonus:+2}` per feed, stored as JSON in `AI_META` (**not** a `Feed` column — that needs a greenDAO bump and wipes every cache). `LEARN_MIN_DECISIONS = 25`, manual only. Few-shot disagreements: **3** rows (veille's 5 halved), predicate ported verbatim from `learn.py:62` with `restore` as a standalone OR term (its `llm_score_at` is NULL — do not "simplify" it).

### 4.6 Build, manifest, R8

`gradle.properties`: `ANDROID_BUILD_MIN_SDK_VERSION=21` → `24`. Root `build.gradle:5`: `ext.kotlin_version='2.2.10'` → `'2.2.21'` (pending S0). Do **not** touch `android.dependency.useConstraints` or `android.r8.strictFullModeForKeepRules`.

`News-Android-App/build.gradle`: `flavorDimensions = ["default","ml"]`; `mlNone {}`, `mlGemma { applicationIdSuffix ".ai"; ndk { abiFilters 'arm64-v8a','x86_64' } }`. Dependencies, **pinned** (never `latest.release` — Maven modules carry no status metadata, so Gradle would pick `0.16.0-alpha01` the day it lands):
```groovy
mlGemmaImplementation 'com.google.ai.edge.litertlm:litertlm-android:0.15.0'
mlGemmaImplementation('com.google.mediapipe:tasks-text:1.0.0') { exclude group: 'com.google.android.datatransport' }
mlGemmaImplementation 'androidx.work:work-runtime:2.10.6'
```
`packagingOptions.jniLibs.excludes += '**/libmediapipe_tasks_textgenai_jni.so'` (6.67 MB of TextSummarizer/TextProofreader JNI we never call; `TextEmbedder` binds `libmediapipe_tasks_jni.so`). `packagingOptions.resources.excludes += ['META-INF/NOTICE','META-INF/DEPENDENCIES']` (both MediaPipe AARs carry an identical 2.19 MB `META-INF/NOTICE`). No repository change — both artifacts are on `google()`. **CI JDK must be ≥ 21** (AAR classes are class-file major 65; `compileOptions VERSION_17` does not protect you).

Manifest: `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>` next to `:18-19`; `<uses-native-library>` for `libvndksupport.so`/`libOpenCL.so` (API 31+, ignored below); `AiModelDownloadService` declared in `src/mlGemma/AndroidManifest.xml` with `foregroundServiceType="dataSync"`; the three new Activities; **and add `android:foregroundServiceType="dataSync"` to the existing `DownloadWebPageService` at `:209-212`** (pre-existing crash on targetSdk 34+). `setForegroundAsync(new ForegroundInfo(id, n, FOREGROUND_SERVICE_TYPE_DATA_SYNC))` — 3-arg form mandatory on 34+, guard the constant with `SDK_INT >= 34` for `lint`.

`proguard-rules.pro`: append the LiteRT-LM + MediaPipe keep block verbatim from dev:runtime-and-models §5.4. Neither AAR ships a `proguard.txt` (verified) and `strictFullModeForKeepRules=true` means keeping an interface does **not** keep implementers. Debug passes; **release crashes at runtime** — this is why S7 gates the first AI PR. `-keepattributes` appends across files, so adding `Signature`/annotations does not disturb line 75.

Dagger — `di/AppComponent.java:30` → `@Component(modules = { ApiModule.class, AiModule.class })`, plus:
```java
void injectActivity(AiSettingsActivity a);      void injectActivity(AiModelManagerActivity a);
void injectActivity(AiDigestActivity a);        void injectActivity(AiRubricDiffActivity a);
void injectFragment(AiSettingsFragment f);      void injectWorkerDeps(AiWorkerDeps d);
```
`AiModule` needs **two copies at the same FQN**, one per `ml` source set (`AppComponent` lives in `src/main` and must compile in `mlNone`). `di/TestComponent` must mirror every added method or `connectedAndroidTest` fails to compile.

---

## 5. Implementation phases

Each phase leaves the app shippable. `<V>` below is the variant; use `OssMlGemmaDebug` for AI work and `OssMlNoneDebug` for the non-regression check.

### Phase 0 — Spikes + build groundwork
Build: run **S0 first**, then S1–S7 in parallel on the spike branch. minSdk 21→24; `ml` flavor dimension; pinned dependencies; `abiFilters`; `packagingOptions`; ProGuard keep rules; manifest permission + `DownloadWebPageService` FGS-type fix; `AiModule` in both source sets; `AppComponent`/`TestComponent` method stubs; CI task pinning; fix `org.robolectric.Config.properties` `emulateSdk=18` → `sdk=34`.
Proves: the app builds and behaves identically in both `ml` variants; the release build survives R8.
```
./gradlew :News-Android-App:dependencies --configuration ossMlGemmaDebugRuntimeClasspath
./gradlew :News-Android-App:assembleOssMlNoneDebug :News-Android-App:assembleOssMlGemmaDebug
./gradlew :News-Android-App:assembleOssMlGemmaRelease
./gradlew detekt spotlessCheck lint testOssMlNoneDebugUnitTest
```

### Phase 1 — AI schema + GC
Build: `AiSchema`, `AiKeys`, `AiDb`, all nine stores (CRUD only, no logic), the `DatabaseHelperOrm` hook, `aiGarbageCollect()` + its `RssItemObservable` call site.
Proves: **`AiSchemaTest`** — AI rows survive `DaoMaster.dropAllTables()` + `onCreate()`; `createOrMigrate` is idempotent; `AiKeys.of()` handles empty fingerprint. **`AiGcTest`** — orphan pruning keeps decided embeddings.
`./gradlew :News-Android-App:testOssMlGemmaDebugUnitTest --tests '*Ai*'`

### Phase 2 — The "For you" folder, no AI
Build: `SPECIAL_FOLDERS.AI_FOR_YOU`, drawer row + icon + `AiCapability` gate, the `:611` exclusion-set fix and the SQL branch, the badge one-liner, `ai_list_item.xml` + `RssItemAiViewHolder.kt` + adapter selection by `idFolder`, `ConcatAdapter` + the 10 cast→field conversions, the four empty states, the two latent-bug fixes, `updateMenuItemsState`/`DownloadMoreItems`/`updateDetailFragment` branches. Seed `AI_SCORE` from a debug menu item.
Proves: **`AiFolderSqlTest`** (the highest-value test in the project) — exact generated string; the `" GROUP BY FINGERPRINT "` injection applied character-for-character from `NewsReaderDetailFragment:538-543`; the result parses; `getCurrentRssItemView(0)` and `(1)` return rank order with contiguous `_id`; a duplicate-fingerprint pair yields **one** row at the correct rank.
`./gradlew :News-Android-App:testOssMlGemmaDebugUnitTest :News-Android-App:assembleDevMlGemmaDebug`

### Phase 3 — The signal
Build: `AiDecisions`, `AiDecisionStore` transition table, swipe values `"4"/"5"` + theme attrs + state-list drawables, the For-You swipe override, `updateSwipeDrawables` folder-awareness, `getMovementFlags` guard for non-article rows, Snackbar+UNDO, `removeItemAt`/`restoreItemAt`, the two fast-action buttons (GONE unless `cb_ai_enabled`), star-seed import.
Proves: `AiDecisionStateMachineTest` (4-row table; invalid transition = idempotent no-op with **no** `AI_DECISION` row; undo appends and deletes the `AI_TASTE` row). Manual: product AC 6–13.
`./gradlew :News-Android-App:testOssMlGemmaDebugUnitTest`

### Phase 4 — Embeddings, centroids, prefilter (the taste model — **works with no LLM at all**)
Build: `MediaPipeEmbedder`, `AiEngineManager.withEmbedder`, `AiEmbeddingStore` pack/unpack, `AiCentroidStore` (incremental + rebuild + backfill), `AiSimilarity`, `AiCandidates`, `AiPrefilter`, `AiRank`, `AiTriageWorker` stages 1–4+6 (no scoring), `AiTriageScheduler` + the `onPerformSync` line, the status strip states for the embedder-only ladder.
Proves: `AiPrefilterTest` (cold/warm/unscored-room matrix), `AiCentroidTest` (incremental == rebuild, property test), `AiRankTest` (the 0.5-never-crosses-a-tier invariant), `AiSimilarityTest` (NULL vs 0.0). Parity: dump one vector from Java and one from veille's `embed.py` for the same text and diff the bytes.
`./gradlew :News-Android-App:testOssMlGemmaDebugUnitTest :News-Android-App:connectedDevMlGemmaDebugAndroidTest --tests '*Embedder*'`

### Phase 5 — Model manager + settings
Build: `res/raw/ai_model_catalog.json` (pinned sizes + sha256 from the HF tree API), `AiCatalog`, `AiModelRepository` (`scan()` → INSTALLED/PARTIAL/BROKEN), `AiModelDownloadService` (Range resume, re-resolve the `resolve/main` URL, SHA-256/MD5 verify, EventBus progress), `AiModelProbe` (180 s smoke + the regex probe), `AiCapability`, `AiModelManagerActivity`, `pref_ai.xml` + `bindAiPreferences` + `onResume` summary refresh, `AiSettingsActivity`/`Fragment` + `pref_ai_detail.xml` (**must** call `setSharedPreferencesName(sharedPreferencesFileName)`), the two gating regimes, delete → sentinel reset.
Proves: `AiCatalogTest`, `AiCapabilityTest` (tier boundaries under Robolectric with stubbed `totalMem`), `AiPartMetaTest`. Manual: product AC 2–5, 8–12, 15–16 as rewritten by D22/D23/D24/D25.
`./gradlew :News-Android-App:testOssMlGemmaDebugUnitTest :News-Android-App:assembleDevMlGemmaDebug`

### Phase 6 — The LLM half
Build: `LiteRtLlm`/`LiteRtConversation`, `AiEngineManager.withLlm` + keep-alive + `ComponentCallbacks2` + `shutdownNow`, `PromptTemplate` + the 11 `res/raw` prompts, `AiScoreGrammar`, `LlmCall` (+ per-call watchdog + `CancelToken`), `ScoreLineParser` + repair ladder, `AiTriageWorker` stage 5, the full `AiDegrade` ladder + status strip, `pref_ai_last_run`, `cb_ai_debug_log`.
Proves: `ScoreLineParserTest` (the full guarantee table), `PromptBuilderTest`, `AiDegradeTest`, `AiTriageWorkerTest` with `FakeLlm` returning garbage — **assert every article that enters exits with an `AI_SCORE` row, `LLM_SCORE=NULL`, never a missing row**. Instrumented `EngineSmokeTest` (`@Ignore` without a model). The §6 eval fixture at both batch sizes.
`./gradlew :News-Android-App:testOssMlGemmaDebugUnitTest`; eval: `./gradlew :News-Android-App:connectedDevMlGemmaDebugAndroidTest -Pai.eval=true`

### Phase 7 — Digest
Build: `AiDigestStore`, `AiDigestBuilder` (theme-only grouping, each item once under `themes[0]`, blocks by `−sum(rank_score)`, range on selection time), `AiDigestWorker` + `AbstractGuard`, `DigestCardViewHolder.kt` in `AiHeaderAdapter`, chip filter (a list rebuild via `insertIntoRssCurrentViewTable`, not an in-memory filter), `AiDigestActivity` + adapter + share + mark-all-read, the opt-in notification.
Proves: `AiDigestBuilderTest` (no article in two sections; block order), `AbstractGuardTest` (<80 chars or letter-free ⇒ drop the abstract entirely). Manual: product AC 22–29. **Highest-risk detail:** the digest must call `insertIntoRssCurrentViewTable` with the digest id set before launching `NewsDetailActivity`, or the pager opens the wrong article.

### Phase 8 — Interests + manual learn
Build: `edt_ai_interests` (free text, always the source of truth), `AiRubricStore` (append-only, one ACTIVE, 5-version history + revert), `AiTasteDraftWorker`, `TasteDraftGuard`, `AiRubricDiffActivity` (LCS line diff, explicit Save — **no code path writes the note from a model response**), `pref_ai_suggest_interests` gated at 25 decisions, `pref_ai_reset_taste`. Saving a note invalidates the centroid cache key and marks stored `LLM_SCORE`s stale.
Proves: `TasteDraftGuardTest` (<60% length ⇒ warn banner not auto-reject; >1 topic removed ⇒ pre-select keep-current; strip lines copied verbatim from a scraped title).

### Phase 9 — Hardening + the dev debug APK
Build: full non-regression pass on `mlNone`; R8 release smoke on device; `lint`/`detekt`/`spotless`; thermal + battery + `sp_ai_run_trigger` policies; string/plurals audit; the S9 authority check.
Proves:
```
./gradlew detekt spotlessCheck lint
./gradlew :News-Android-App:testOssMlNoneDebugUnitTest :News-Android-App:testOssMlGemmaDebugUnitTest
./gradlew :News-Android-App:assembleOssMlGemmaRelease          # + on-device smoke (S7)
./gradlew :News-Android-App:assembleDevMlGemmaDebug            # <- the deliverable APK
# -> News-Android-App/build/outputs/apk/devMlGemma/debug/News-Android-App-dev-mlGemma-debug.apk
```

---

## 6. Test plan

### JVM (pure JUnit4, no Robolectric) — `News-Android-App/src/test/java/de/luhmer/owncloudnewsreader/ai/`
| File | Asserts |
|---|---|
| `ScoreLineParserTest.java` | regex acceptance; non-matching lines **ignored not fatal**; duplicate index → first wins; index out of 1..N dropped; `toIntOrNull ?: 0` then clamp [0,3]; `"2.5"` → 2; tags lowercased/hyphenated, closed-vocabulary filter, partition into flags/themes with **order preserved**, ≤6; `why` empty legal, >160 chars truncated at a word boundary; fence stripping (even count), BOM, `\r\n`, NBSP, U+FF5C `｜`→`\|`; whole-prompt echo → parse failure; **never throws** |
| `RepairLadderTest.java` | parse → repair turn → split-to-1 → `LLM_SCORE = NULL`; existing lines win on merge; extra indices dropped never appended; no positional fallback |
| `AiPrefilterTest.java` | cold (either centroid missing) ⇒ sim skipped entirely, recency by (pubDate, fetchedAt) DESC undated **last**; warm ⇒ `sim≥0.05` sorted, capped; `low_similarity` never applied to an unembedded article; **unscored fill remaining room by recency**; discard reasons recorded not deleted |
| `AiCentroidTest.java` | incremental ± == full rebuild (property test over random decision sequences); missing centroid contributes 0 not a zero vector; zero-norm → null; cache key `(decisionCount, model, task)`; backfill folds `IN_CENTROID=0` rows |
| `AiSimilarityTest.java` | `sim = cos(v,liked) − cos(v,rejected)`; NULL vs 0.0 sentinels |
| `AiRankTest.java` | `eff = clamp(llm+bump,0,3)`; `rank = round(eff+0.5*sim,3)`; `llm IS NULL ⇒ round(sim,3)`; **property: 0.5*sim never crosses a tier boundary, the feed bump can** |
| `AiDecisionStateMachineTest.java` | the 4-row table; invalid transition = idempotent no-op with **no** `AI_DECISION` row; undo appends + deletes the `AI_TASTE` row |
| `PromptBuilderTest.java` | single-pass substitution (a value containing `{{x}}` is **not** re-substituted); interests cap 1200 chars at the last newline; corrections block omitted **with its heading** when empty, max 3 rows; title sanitisation (control chars collapsed, pipes stripped, 120/200-char caps); `{{lang}}` = English language name not a BCP-47 tag |
| `AiScoreGrammarTest.java` | generated regex compiles; matches valid output; rejects a missing line, a 4-score, an embedded pipe in `why`; no `oneOf` in any emitted schema |
| `AiDigestBuilderTest.java` | theme-only grouping; each item once under `themes[0]`; blocks by `−sum(rank_score)`; range on selection time not `pubDate` |
| `AbstractGuardTest.java` | strips fences / `^#+ ` / "Here is…" / trailing pleasantries; ≤6 sentences; **<80 chars or letter-free ⇒ drop the abstract entirely** |
| `TasteDraftGuardTest.java` | <40 chars reject; <60% length ⇒ warn banner (not auto-reject); >1 topic line removed ⇒ pre-select keep-current; >12 lines truncated at a complete line; identical draft is a valid "no change"; strip lines copied verbatim from `{{starred}}` |
| `EnrichParserTest.java` | `WHY:`/`SUM:` regexes; unlabelled why recovered; **`fullTextAvailable==false` ⇒ SUM discarded unconditionally** |
| `AiCapabilityTest.java` | T0/T1/T2 boundaries; `hasArm64` rejects x86_64-only outside debug; `downloadOk` checks **both** volumes |
| `AiCatalogTest.java` | catalogue parses; every entry has size + (sha256 \| md5); gated flags match D22 |

### Robolectric (real SQLite) — `src/test/java/de/luhmer/owncloudnewsreader/database/ai/`
| File | Asserts |
|---|---|
| **`AiSchemaTest.java`** | `createOrMigrate` idempotent; insert AI rows → `DaoMaster.dropAllTables(db,true)` + `createAllTables` → **every AI row survives**; `resetDatabase()` and the Clear-cache path likewise preserve `AI_EMBEDDING`/`AI_DECISION`/`AI_CENTROID`/`AI_RUBRIC`. *Regression test for D1 — must exist before the first AI row is written.* |
| **`AiFolderSqlTest.java`** | exact generated string for `AI_FOR_YOU`; the `" GROUP BY FINGERPRINT "` injection applied character-for-character from `NewsReaderDetailFragment:538-543`; statement parses and `FINGERPRINT` resolves unqualified; `insertIntoRssCurrentViewTable` → `getCurrentRssItemView(0)`/`(1)` in rank order with contiguous `_id` from 1; a duplicate-fingerprint pair yields **one** row at the correct rank; `onlyUnread` honoured; `SP_SORT_ORDER` has **no** effect. *Regression test for D3/D4.* |
| `AiGcTest.java` | orphaned `AI_SCORE` pruned; embeddings of decided articles kept; `AI_DECISION` never touched |
| `AiScoreFanoutTest.java` | writing one score fans out identically across all rows sharing an `AI_KEY` |
| `AiTriageWorkerTest.java` | with `FakeLlm` emitting garbage: **every article entering scoring exits with an `AI_SCORE` row**, `LLM_SCORE=NULL`, no exception escapes; cancel mid-run leaves the DB consistent and the run resumable |

Fakes: `src/test/java/.../ai/FakeLlm.java`, `FakeEmbedder.java`, fixtures in `src/test/resources/ai/`. The interfaces live in `src/main` and carry no LiteRT types — that is what makes all of the above device-free.

### Instrumented — `src/androidTest/java/de/luhmer/owncloudnewsreader/ai/`
`EngineSmokeTest.java` (init latency, one round-trip, close; `@Ignore` if no model — `clearPackageData:'true'` at `build.gradle:22` means never rely on the model surviving), `EmbedderContractTest.java` (**S4**: `embed("x",CTX)` vs manually-prefixed, element-wise), `ConstrainedDecodingTest.java` (**S2**), `ScoreEvalTest.java` (the fixture below, `-Pai.eval=true`).

### Prompt eval fixture — `src/test/resources/ai/score_eval.json`
The 12-item set from product:prompt-adaptation §6, verbatim, with the interests note (DRT / FR-UK operators / competitors Via·Padam·Liftango·Spare / EU tender regulation / bus electrification) and vocabulary `drt, operators, competitors, electrification, competitor, regulation, customer`. Traps: 1 press-release-about-a-watched-company (3), 2 long-article-passing-mention (1), 3 short-and-entirely-on-topic (3), 4 off-topic (0), 5 empty excerpt but judgeable title (2, **must return a line**), 6 mojibake (0, **must return a line**), 7 regulation flag (3), 8 multi-tag customer+competitor (3), 9 adjacent-out-of-scope (0-1), 10 listicle (1), 11 German DRT — `why` must come back in the **device** locale (3), 12 vendor spam with topical keywords (0-1).

- **CI gate (recorded model outputs, runs every build):** 12 distinct indices 1..12, none missing, none extra; every score ∈ 0..3 after clamping; every tag in the closed vocabulary after filtering (report the *raw* invented-tag rate as a per-model metric); `why` non-empty for 1,3,7,8,11 and ≤160 chars everywhere; items 5 and 6 produce a line.
- **Ship gate (on device, per model):** ≥9/12 in band **and** 0 format failures. Ordering assertions, stricter and more informative than the bands: `score(3) > score(2)`, `score(3) ≥ score(10)`, `score(7) > score(9)`, `score(1) > score(12)`.
- **Measured budget gate (record, don't assume):** D-high, `gemma-4-E2B` **on CPU**, 30 articles ≤ 5 min, post-repair parse failure <5%. D-low, `qwen3-0.6b-int4` on CPU, 15 articles ≤ 5 min, <15%. Miss on D-low ⇒ demote T1 to embeddings/centroids only.

---

## 7. Risks, ranked

| # | Risk | Sev | Mitigation |
|---|---|---|---|
| **R1** | **Distribution/licensing collision.** MediaPipe `tasks-core` pulls Google Clearcut telemetry into an F-Droid app whose `PRIVACY.md:3` promises no data leaves the device; both AARs are unbuildable binary blobs F-Droid will not accept | Blocker | Q1 — settle in conversation **before code**. Engineering answer: `ml` flavor dimension (D10) + `exclude group: 'com.google.android.datatransport'` + S5 verifying `TextEmbedder` still initialises. `mlNone` never resolves either artifact. |
| **R2** | **Mis-ranked list that looks right.** `GROUP BY FINGERPRINT` string surgery + arbitrary group-member pick for `RANK_SCORE`. The whole feature's value is the ordering; there is no error anywhere | Blocker | D3: no aliases + `AI_KEY` fan-out + early return + exactly one literal `ORDER BY`. **`AiFolderSqlTest` in Phase 2, before the first AI row exists.** Cheapest insurance in the plan. |
| **R3** | **R8 full mode strips the JNI bridge.** Neither AAR ships `proguard.txt`; `strictFullModeForKeepRules=true`; debug passes, release crashes at runtime — and CI's `assembleDev` builds a minified variant | Blocker | The §4.6 keep block + **S7 in the definition of done for the first AI PR**, verified by `assembleOssMlGemmaRelease` plus an on-device run. |
| **R4** | **Silent embedding-quality loss.** 1-arg `embed(String)` applies no task prefix; or the C++ graph double-prefixes (S4). No error, no symptom except a taste model that never converges | Blocker | D12: the `AiEmbedder` interface does not expose the 1-arg form; CI grep `grep -rn "\.embed(" | grep -v CTX` must be empty; S4 before any user vector is written; `MODEL`+`TASK` persisted per row with an explicit `assertCompatible()` migration. |
| **R5** | **Device-reach cliff.** arm64-only, minSdk 24, 2.6 GB download, multi-GB RAM. If the mental model is "add a feature to the existing app", it is wrong | High | D8/D9/D10 + the T0 tier + `AiCapability` gating the drawer row, the settings row and every download button. The feature degrades to a chronological list without degrading the reader. Copy says "64-bit **ARM**", not "64-bit". |
| **R6** | **Engine memory kills the app mid-scroll.** Dagger `@Singleton` never closes; `NewsReaderApplication.onCreate` runs per process | High | D19 `AiEngineManager` + 90 s keep-alive + `ComponentCallbacks2` (`TRIM_MEMORY_UI_HIDDEN` **ignored** — that fires exactly when we want to run) + `mi.lowMemory` runtime defer + S1 measurement replacing `minTotalRamBytes`. |
| **R7** | **Scoring loses articles.** Truncation, OOM, native crash, cancellation, unparseable output | High | veille invariant #2 as code: per-article `try/catch(Throwable)`; the repair ladder; `LLM_SCORE = NULL ≠ 0`; per-article commit so the table is the checkpoint; `AiTriageWorker.doWork()` has exactly one `return Result.success()`; **`Result.failure()` is never returned** (retry storm). Asserted by `AiTriageWorkerTest` with a garbage `FakeLlm`. |
| **R8** | **`sp_ai_model_*` holds a dangling path** after a delete, model corruption, or an OS eviction → silent permanent AI outage | High | Delete resets the key to its sentinel + toast; `AiModelResolver` falls back tier-default → any `SELECT_OK` model → any model → `NOT_INSTALLED`; the strip states it. Never leave a stale path in prefs. |
| **R9** | **Flavor matrix cost discovered halfway.** 8 variants, duplicated `AiModule` FQN, `assembleDev` ambiguous, `test` fans out, stacked `applicationIdSuffix` vs `@string/authority` | High | All absorbed in Phase 0, with S9 as the explicit check. A duplicate ContentProvider authority makes the app uninstallable alongside the other flavor. |
| **R10** | **Constrained decoding unavailable on the chosen model** (non-SentencePiece, or LLGuidance rejects the grammar) | Med | S2 as part of the install smoke; result cached in `AI_MODEL.CAPS`; on `false` the model is still offered, batch forced to 1, and the repair ladder becomes load-bearing. The design never *gates* a feature on the grammar. |
| **R11** | **Download resume fails in the field** — signed CDN URL expires, CDN etag ≠ origin etag | Med | D23. Never persist the redirect target; validate with `x-linked-size` + final SHA-256; on `200` instead of `206`, discard the `.part` and restart with a toast. |
| **R12** | **First smoke load false-BROKEN** after a 2.6 GB download | Med | D25 (180 s, in-service progress line, measured time recorded, subsequent watchdog `3 × measured`) + S6. |
| **R13** | **Thermal/battery burn.** 200 articles ≈ 90–140 s of pinned multi-core CPU on mid-range | Med | `AI_EMBED_MAX_PER_SYNC = 60` + `sp_ai_batch_budget` (the single lever that keeps cost flat as feeds grow) + charger/battery/thermal constraints + the API-34 `dataSync` 6h/24h quota respected by the 12-min run cap and resumable progress. Full backfill only on charger+idle. |
| **R14** | **`lint` `abortOnError true` breaks CI** on `ForegroundServiceType`, `InlinedApi`, `MissingPermission`, `Instantiatable`, `StringFormatInvalid` | Med | Guard the API-34 constant; declare the permission; keep the `Worker` in the ProGuard keep set; escape `%%`/`\'`/`&amp;` and use `<plurals>`. **Do not widen the `disable` list.** Run `lint` in Phase 0. |
| **R15** | **Undo + lazy-load duplicates a row.** `restoreItemAt` re-inserts into `lazyList` while `LoadMoreItemsAsyncTask` pages on `C._id > page*25` | Low | After `restoreItemAt`, if `lazyList.size() > cachedPages*25`, drop the last element. Test AC 7 with 30+ items, not 5. |
| **R16** | **Fast-action bar overflows** at 5 × 56dp = 312dp on a 320dp device | Low | Both AI buttons default GONE, shown only when `cb_ai_enabled` — which also keeps the bar byte-identical for AC #30. |
| **R17** | **Digest opens the wrong article** — `NewsDetailActivity` pages over whatever `CURRENT_RSS_ITEM_VIEW` holds | Low but certain if unhandled | The digest calls `insertIntoRssCurrentViewTable` with the digest id set before launching. Covered by an instrumented test in Phase 7. |
| **R18** | **Rubric-learn collapse** — the model narrows the interests note to nothing over successive passes | Low | Manual trigger only, never on a timer; the anti-collapse system prompt kept; `TasteDraftGuard` flags (never auto-rejects) shrinkage; nothing is saved without an explicit tap; 5-version history + one-tap revert. |