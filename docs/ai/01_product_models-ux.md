Read the brief plus the target's actual settings plumbing (`pref_*.xml`, `SettingsFragment.java:82-125,255-262,305-316`, `SettingsActivity.java:58-106`, `AndroidManifest.xml` permissions, `NewsFileUtils.java:119-129`). Below is the spec.

---

# Model management & settings — spec

## 0. Framing decision that drives everything else

**Exactly one LLM + one embedder is resident by default, ever.** Per-stage pickers exist (the user asked for them), but only the *triage* picker has a real model default. Every other LLM stage defaults to the sentinel `__same_as_triage__`. This is what keeps the default install at ~2.8 GB instead of ~6.4 GB, and it means engine init (up to 10 s per the brief) is paid once per sync, not four times.

---

## 1. Stage → model matrix

### Stages dropped, with reason

| Dropped | Why |
|---|---|
| **Bilingual output** (`why_fr`+`why_en`, and enrich's 4-language `title_*`/`why_*`/`synthesis_*`) | Doubles-to-quadruples output tokens on the highest-volume stage for zero value to a single user. Emit one language = device locale. This is PART D #8, settled. |
| **Enrichment / synthesis as a default-on stage** | Input is full jsoup body (3–8k tokens) × ~20 items; it is the stage most exposed to hallucination, and veille's own degrade path (invariant #8) says "skip enrich → keep stage-2 why-lines". The why-line from triage is already the product. Keep the *capability*, default it off. |
| **Digest-prompt learn** (second learn loop) | A phone user has one digest style. Not worth a model. |
| **Notify LLM call** (200 tokens for a push line) | Use the top article's title + its why-line. An LLM call to write a notification is pure waste. |
| **Rubric-learn as an automatic loop** | `learn.md`'s explicit anti-collapse warning is precisely the failure mode a 2B model will hit. Keep it as a manual, opt-in, diff-reviewed button. Never on a timer. |

### The matrix

| Stage | Default model | Input size | Output | Calls / sync | Latency tolerance | JSON? | Verdict |
|---|---|---|---|---|---|---|---|
| **Embedding** (taste centroids, stage 1b) | **`embeddinggemma-300m`** — no picker realistically (see risk R3) | `title\n\nsummary`[:2000], well under its 2K ctx | 768-dim float vector (truncatable to 128 via MRL) | 1 per *new* article ever (veille embeds once) — 50–400 on first sync, ~20–60 after | High: can run unattended, ~22 ms/item class | No | **Mandatory floor.** <200 MB RAM, 100+ languages (load-bearing: a FR and an EN article on the same subject must land near each other). `TextEmbedder.cosineSimilarity` is a built-in static — the centroid math is free. This stage alone delivers the taste model with zero LLM. |
| **Triage / scoring** (stage 2) | **`gemma-4-E2B` (2.58 GB)** on Full tier; **`Gemma3-1B-IT`** on Light tier | ~600–900 tok: interests text + scale + flags + 3 few-shot disagreements + ONE article (title+source+date+summary[:800]) | ~60–90 tok: `{"score":0-3,"themes":[],"flags":[],"why":"…"}` | **= `sp_ai_batch_budget`, default 30** (veille's `prefilter_top_k`, halved for a phone) | Low-ish: this is the sync critical path. Budget ≤ 5 min for 30 items. | **Yes, and this is the constraint** | **Do NOT port the batch-of-10 array.** A 1–2B model asked for a 10-element array with 5 keys each, order-preserved, id-preserved, will drop elements and mis-align — and veille invariant #2/#3 exist *because* even Haiku does this. One article per call collapses the alignment problem to nothing (there is no id to align), makes the repair turn cheap, and makes a truncation a single lost article instead of nine. Cost: 30 prefills instead of 3. Accept it. E2B is the smallest model I'd trust with a 4-key object; 270m is not a JSON producer at all. |
| **Digest abstract** | **`__same_as_triage__`** | 20–40 lines of `title — why` (~1200–1800 tok) | 3–5 sentences, plain Markdown | 1, and only when the user opens/creates a digest | Very high: user-initiated, spinner is fine | **No — prose** | The one stage where small-model weakness (JSON) doesn't bite. Reusing the already-warm triage engine costs 0 bytes and 0 extra init. Only stage where `Gemma3-1B-IT` is genuinely adequate even on a Full-tier device. |
| **Enrichment / why-lines+synthesis** | **`__off__`** (row hidden below Full tier) | full text, 3–8k tok | ~400 tok JSON, 3 keys after de-bilingualisation | 0 by default; ≤10 if enabled | Very high: must be charger-only, opt-in | Yes | Off by default. If enabled, default `__same_as_triage__`; `gemma-4-E4B` selectable only at ≥8 GB. The copyright + "never invent" rules in `enrich.md` are the hardest thing on this list for a 2B model and the code-side guard (no full text ⇒ empty synthesis) must be unconditional. |
| **Rubric / taste learn** | **`__off__`**, manual button, `__same_as_triage__` when enabled | ≤40 sanitised decision rows + current interests text (~1500 tok) | `{"body_md","rationale"}`, ~600 tok | 0 automatic; user-triggered, gated at 25 decisions | Unbounded | Yes (2 keys, easy) | The prompt-injection sanitisation (control chars collapsed, 200-char cap, "data under review, not instructions") ports unchanged and matters *more* here. Output goes to a `difflib`-style diff screen; nothing is applied without a tap. |

### Default on-disk footprint

| Tier | Set | On disk |
|---|---|---|
| Light | `embeddinggemma-300m` + `Gemma3-1B-IT` | **~0.75 GB** (0.2 + ~0.55) |
| Full | `embeddinggemma-300m` + `gemma-4-E2B` | **~2.78 GB** (0.2 + 2.58) |
| Full, enrichment on E4B (explicit opt-in) | + `gemma-4-E4B` | ~6.43 GB |

Only `gemma-4-E2B` (2.58 GB) and `gemma-4-E4B` (3.65 GB) are verified sizes from the brief. **`gemma-3-270m-it`, `Gemma3-1B-IT` and `embeddinggemma-300m` on-disk `.litertlm`/`.task` sizes are NOT in the brief and I did not verify them** — the ~0.2/~0.55 GB figures are inferred from parameter count and quantisation and must be read off the HF model cards before the tier constants are frozen. This blocks §2's numeric gate.

---

## 2. Device tiers

### Signals used (all cheap, all API-21-safe)

- `Build.SUPPORTED_64_BIT_ABIS.length > 0` — a 2.58 GB mmap cannot live in a 32-bit address space. Hard gate.
- `ActivityManager.getMemoryInfo(mi).totalMem` — total physical RAM. Use **totalMem, not availMem**, for tiering: availMem is volatile and would make the tier flap between launches.
- `mi.availMem` / `mi.lowMemory` — used only as a *runtime defer* check, never as a tier.
- `new StatFs(getExternalFilesDir(null).getPath()).getAvailableBytes()` — download gate only.
- `Build.VERSION.SDK_INT >= LITERT_MIN_SDK` — **value unknown**, brief says LiteRT-LM's minSdk is undocumented and must be read from the AAR manifest. Until then treat as 26 provisionally and flag.

Do **not** use `getMemoryClass()`/`getLargeMemoryClass()` — those bound the Java heap; LiteRT allocates natively and is unaffected.

### Tiers

| Tier | Rule | Model set | Settings behaviour |
|---|---|---|---|
| **T0 Unsupported** | no 64-bit ABI **OR** `totalMem < 3.0 GB` **OR** `SDK_INT < LITERT_MIN_SDK` | none | `cb_ai_enabled` disabled; `pref_ai_settings` disabled with summary "Not supported on this device (needs a 64-bit CPU, 3 GB RAM, Android N+)". No downloads possible. |
| **T1 Light** | `3.0 GB ≤ totalMem < 6.0 GB` | `embeddinggemma-300m` + `Gemma3-1B-IT` | Enrichment and rubric-learn rows **hidden**. `sp_ai_batch_budget` default drops to 15. GPU backend default off (low-RAM devices are where GPU delegate OOMs). |
| **T2 Full** | `totalMem ≥ 6.0 GB` | `embeddinggemma-300m` + `gemma-4-E2B` | All rows shown. `gemma-4-E4B` offered in pickers only when `totalMem ≥ 8.0 GB`. |

### Our per-model gate (the Gallery's "memory check before allowing selection")

Two distinct checks, both required:

```
DOWNLOAD_OK(m)  :=  freeBytes(modelDir) >= m.sizeBytes * 1.15 + 250 MB
SELECT_OK(m)    :=  totalMem            >= m.sizeBytes * 1.5  + 1.0 GB
```

`SELECT_OK` yields: `Gemma3-1B-IT` (~0.55 GB) → needs 1.8 GB; `gemma-4-E2B` (2.58 GB) → needs **4.87 GB**, so a 6 GB phone passes and a 4 GB phone fails; `gemma-4-E4B` (3.65 GB) → needs **6.47 GB**, so 8 GB passes. These thresholds are consistent with the T1/T2 boundaries above and are the numbers to hardcode.

Additionally, at activation time only: if `mi.lowMemory` is true, refuse and show "Not enough free memory right now — close some apps and retry." Do not fail the sync; skip the AI stage (invariant #8: degrade, never throw).

**Override:** a catalogue row failing `SELECT_OK` shows "Needs ~X GB RAM" and is greyed, but an overflow item "Try anyway" opens a confirm dialog ("The app may be killed while this model loads"). OEM `totalMem` reporting is inconsistent enough that a hard lock will generate bug reports; the override costs nothing and is honest.

---

## 3. The settings screen

### Structure decision: **no nested PreferenceScreen. A separate Activity.**

Do **not** implement `OnPreferenceStartFragmentCallback`. Reasons: (a) the download manager is a `RecyclerView` with progress bars and per-row action buttons — that is not expressible as a preference list at all, so a second Activity is needed regardless; (b) the app already has the precedent of a preference row launching an Activity (`PREF_TTS_SETTINGS` → `startActivity` at `SettingsFragment.java:260-262`); (c) it keeps `SettingsActivity`/`SettingsFragment` untouched except for one `addPreferencesFromResource` line.

Three artefacts:
- **`res/xml/pref_ai.xml`** — appended as the 5th resource in `SettingsFragment.onCreatePreferences()` (after `pref_data_sync`, before `pref_about`), with a matching `bindAiPreferences(this)`. Two rows only.
- **`res/xml/pref_ai_detail.xml`** — hosted by a new `AiSettingsActivity` → `AiSettingsFragment extends PreferenceFragmentCompat`. Must call `getPreferenceManager().setSharedPreferencesName(sharedPreferencesFileName)` and inject via `AppComponent` (add `injectFragment` overload / new `injectActivity` — see brief: **AppComponent needs an explicit method per injected type**).
- **`AiModelManagerActivity`** — plain Activity + RecyclerView + ViewBinding. Not a preference screen.

All keys must be mirrored as `public static final String` in `SettingsActivity.java:58-106` (house convention) and read through the **injected** `SharedPreferences` (custom file name `"<pkg>_preferences"`), never `PreferenceManager.getDefaultSharedPreferences`.

Every node needs `app:iconSpaceReserved="false"` (house convention, universal in the existing XMLs).

### The table

| # | File | Key | Type | Title | Summary | Default | Notes |
|---|---|---|---|---|---|---|---|
| — | pref_ai | — | PreferenceCategory | `@string/pref_header_ai` = "AI triage" | — | — | 5th category in the main settings list |
| 1 | pref_ai | `cb_ai_enabled` | SwitchPreference | "Enable on-device AI triage" | "Rank and filter new articles with a model running on this phone. Nothing is sent anywhere." | `false` | **Master kill switch = veille's `AI_ENABLED`.** Off ⇒ every AI entry point fails fast, every caller degrades (no embed ⇒ recency order; no score ⇒ `llm_score` NULL, rank on sim). Disabled+unchecked on T0. Turning it on with no model installed opens `AiModelManagerActivity`. |
| 2 | pref_ai | `pref_ai_settings` | Preference | "AI settings" | *dynamic*: "Gemma 4 E2B · 2 models · 2.8 GB" / "No model installed" / "Not supported on this device" | — | `startActivity(AiSettingsActivity)`. `setEnabled(tier != T0)`. |
| — | detail | — | PreferenceCategory | "Models" | — | — | |
| 3 | detail | `sp_ai_model_triage` | ListPreference | "Triage model" | *dynamic*: model name + size + "Recommended for this device" | `""` → resolved to tier default on first install | Entries built **at runtime** from INSTALLED models only, plus a trailing "Download a model…" entry that opens the manager. Never list a model that isn't on disk. |
| 4 | detail | `sp_ai_model_embedding` | ListPreference | "Similarity model" | "Used to learn what you like. Required." | `embeddinggemma-300m` | Single entry today ⇒ `setEnabled(entries.length > 1)`. Keep the row visible: it is what explains the ~200 MB download to the user. |
| 5 | detail | `sp_ai_model_digest` | ListPreference | "Digest writer" | *dynamic* | `__same_as_triage__` | Sentinel is the first entry, labelled "Same as triage model". |
| 6 | detail | `sp_ai_model_enrich` | ListPreference | "Article summaries" | "Reads the full article to write a longer summary. Slow; runs only while charging." | `__off__` | Entries: "Off" (default), "Same as triage model", then installed models. **Row hidden when tier != T2.** |
| 7 | detail | `sp_ai_model_learn` | ListPreference | "Interest-learning model" | *dynamic* | `__off__` | Same entry shape as #6. **Hidden when tier != T2.** |
| 8 | detail | `pref_ai_manage_models` | Preference | "Download models" | *dynamic*: "2 installed · 2.8 GB used · 12.4 GB free" | — | → `AiModelManagerActivity` |
| 9 | detail | `pref_ai_delete_models` | Preference | "Delete downloaded models" | *dynamic*: "Frees up to 2.8 GB" | — | Multi-select confirm dialog. `setEnabled(installedCount > 0)`. |
| 10 | detail | `edt_ai_hf_token` | EditTextPreference (`inputType="textPassword"`) | "Hugging Face access token" | "Only needed if a download keeps failing after you accept the licence." | `""` | **`android:isPreferenceVisible="false"` by default**; made visible programmatically only after a licence-acked download 403s twice. Stored plaintext in prefs — acceptable; it is a read-only public-model token. |
| — | detail | — | PreferenceCategory | "When to run" | — | — | |
| 11 | detail | `sp_ai_run_trigger` | ListPreference | "Run triage" | *bound to value* | `charging` | entries/values: "After every sync"/`always`, "Only while charging"/`charging`, "Only when I open the AI folder"/`on_open`, "Only when I ask"/`manual`. |
| 12 | detail | `sp_ai_batch_budget` | ListPreference | "Articles to score each run" | "More articles, better coverage, longer run." | `30` (T2) / `15` (T1) | values `15,30,60,120`. **This is veille's `prefilter_top_k` — the single lever that keeps cost flat as feeds grow.** |
| 13 | detail | `sp_ai_min_battery` | ListPreference | "Skip when battery is below" | *bound to value* | `30` | values `0,15,30,50` (%). `0` = "Never skip". |
| 14 | detail | `cb_ai_gpu_backend` | SwitchPreference | "Use GPU acceleration" | "Faster, but warms the phone. Turn off if triage crashes." | `true` on T2, `false` on T1 | Selects `Backend.GPU()` vs `Backend.CPU()`. Requires the two `<uses-native-library>` entries (`libvndksupport.so`, `libOpenCL.so`) in `<application>`. |
| 15 | detail | `cb_ai_downloads_wifi_only` | SwitchPreference | "Download models on Wi-Fi only" | "Models are 0.5–3.7 GB." | `true` | Downloads only. Inference is always offline. |
| 16 | detail | `cb_ai_thermal_pause` | SwitchPreference | "Pause when the phone gets hot" | — | `true` | `PowerManager.getCurrentThermalStatus() >= THERMAL_STATUS_MODERATE` ⇒ abort the run, resume next sync. API 29+; no-op below. |
| — | detail | — | PreferenceCategory | "What I like" | — | — | |
| 17 | detail | `edt_ai_interests` | EditTextPreference (multiline) | "My interests" | *first 80 chars of the value*, or "Not set — ranking uses only what you star and skip." | `""` | **This is the rubric on a phone** (PART D #5). Empty is a valid, supported state: cold start = centroids-only + recency, exactly like veille. |
| 18 | detail | `pref_ai_suggest_interests` | Preference | "Suggest an update from my choices" | *dynamic*: "18 of 25 choices since the last update" | — | `setEnabled(decisionsSinceRubricCreatedAt >= 25)` — veille's `LEARN_MIN_DECISIONS`. Opens a diff screen; nothing applies without a tap. Hidden when `sp_ai_model_learn == __off__`. |
| 19 | detail | `cb_ai_star_is_like` | SwitchPreference | "Treat starred articles as liked" | "Your stars stay on your Nextcloud server; the AI just reads them." | `true` | PART D #6. Star = `keep`. The explicit reject signal comes from a swipe action, not from here. |
| 20 | detail | `pref_ai_reset_taste` | Preference | "Forget what I like" | "Clears the AI's learned preferences. Your stars are not touched." | — | Confirm dialog. Wipes the decisions table + centroid cache in **our** DB only. |
| — | detail | — | PreferenceCategory | "Diagnostics" | — | — | |
| 21 | detail | `pref_ai_last_run` | Preference | "Last run" | *dynamic*: "1 Aug 07:14 · 30 scored, 9 selected · 3 min 12 s" or "Skipped: not charging" | — | Non-clickable (`android:selectable="false"`) unless a run failed, in which case it opens the log. |
| 22 | detail | `cb_ai_debug_log` | SwitchPreference | "Keep a diagnostic log" | "Records prompts and replies to a file you can attach to a bug report." | `false` | Capped ring file in `getExternalCacheDir()`; cleared by the existing `edt_clearCache` action. |

---

## 4. Download flow

### Storage layout

```
getExternalFilesDir(null)/models/<normalizedRepo>/<version>/<fileName>     final weights
                                                          /<fileName>.part in-flight body
                                                          /<fileName>.meta {url, etag, totalBytes, receivedBytes, sha256}
getCacheDir()/litertlm/                                                    EngineConfig cacheDir
```

The `getExternalFilesDir` path is the Gallery convention from the brief and is correct: it is **not** OS-reclaimable. Critically, this is a *different* root from `NewsFileUtils.getCacheDirPath()` (which returns `getExternalCacheDir()`, `NewsFileUtils.java:119-121`) — so the existing **`edt_clearCache` handler (`SettingsFragment.java:305-316`) must not touch `models/`**, or a routine cache clear silently deletes 2.8 GB the user waited 20 minutes for.

### Screen by screen

**S1 — Discovery.** `AiModelManagerActivity`, RecyclerView over the catalogue. Row = name, one-line purpose ("Triage & digests" / "Similarity"), size, and exactly one state chip: `Recommended` · `Download` · `Downloading 41% · 1.1/2.6 GB` · `Paused — Resume` · `Installed` · `In use` · `Needs ~4.9 GB RAM` (greyed, overflow "Try anyway") · `Needs 1.2 GB more space`. Sorted: recommended-for-tier first, then installed, then the rest.

**S2 — Licence gate.** Gemma weights on HF are gated: a plain GET returns 403 until the licence is accepted in a browser. On the first tap of Download for a repo with `gated: true` and no local `licence_ack_<repo>` flag:

> **Accept the Gemma terms**
> Google requires you to accept the Gemma licence on huggingface.co before downloading. We'll open your browser — accept, then come back and tap Download again.
> `[Open in browser]` `[Cancel]`

Opens `https://huggingface.co/<repo>` in a Custom Tab. On return, set `licence_ack_<repo>=true` and auto-retry once. If the retry 403s: inline error "Access not granted yet — make sure you were signed in to Hugging Face when you accepted" with `[Retry]` and `[Open again]`. **On the second consecutive 403, reveal `edt_ai_hf_token` (row 10)** with an explainer and a deep link to `https://huggingface.co/settings/tokens`; subsequent requests send `Authorization: Bearer <token>`.

> **Uncertainty (highest-impact in this section):** I do not know whether anonymous download works at all after browser acceptance, or whether HF always requires a token for gated repos. The Gallery uses `AuthorizationService` (OAuth), which suggests a token is often required. **Resolve by:** `curl -sI` the actual `.../resolve/main/<file>.litertlm` URL, once anonymously and once with a token from an account that has accepted the terms. If anonymous never works, promote the token row to always-visible and put it *before* the browser step in the flow.

**S3 — Download.** A **foreground service** `AiModelDownloadService` (not `JobIntentService` — that cannot hold a multi-minute download on modern Android, and unlike `DownloadWebPageService` this job is user-initiated and unbounded). Ongoing low-priority notification with determinate progress and a `Cancel` action, mirroring the `DownloadWebPageService` notification pattern. **Requires adding `android:permission.FOREGROUND_SERVICE_DATA_SYNC` to the manifest and `android:foregroundServiceType="dataSync"` to the `<service>`** — the manifest today declares only `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (verified, `AndroidManifest.xml:18-19`). OkHttp 5 is already a dependency; stream `ResponseBody.source()` to the `.part` file, update `.meta.receivedBytes` every 4 MB.

Do **not** add WorkManager. It is the textbook tool here, but the app has none today, resume is already solved by HTTP Range + a startup scan, and one new dependency is cheaper than one new dependency plus a scheduling model nobody else in the codebase uses.

**S4 — Resume after kill.** `.part` + `.meta` survive process death. On `AiModelManagerActivity` start (and on app start, for the notification), `AiModelRepository.scan()` walks `models/` and classifies each entry `INSTALLED | PARTIAL | BROKEN`. `PARTIAL` rows show `Paused at 41% — Resume`. Resume sends `Range: bytes=<receivedBytes>-` **and** `If-Range: <etag>`. If the server answers `200` instead of `206`, or the ETag differs, discard the `.part` and restart from zero with a toast "The file changed on the server; starting over."

**S5 — Wi-Fi loss / IO error.** Never delete a `.part` on error. Pause, notification → "Paused — waiting for Wi-Fi". Register a `ConnectivityManager.NetworkCallback` with `NET_CAPABILITY_NOT_METERED` (when `cb_ai_downloads_wifi_only`) and auto-resume on `onAvailable`. Max 3 automatic resumes with 5/15/45 s backoff; after that the row sits at `Paused — Retry` and waits for a tap. Metered-network switch mid-download while wifi-only is on ⇒ pause immediately, do not burn mobile data.

**S6 — Low disk.** Pre-flight `DOWNLOAD_OK(m)` (§2) before the first byte; re-check every 32 MB. On failure abort with "Not enough space — free about X GB and try again", keep the `.part` (the user may free space and resume). If the abort itself was caused by a full disk, delete the `.part` only when the user taps Cancel.

**S7 — Verify.** Two gates, both blocking:
1. **SHA-256** of the completed file vs the pinned hash in the catalogue. Mismatch ⇒ delete, one silent auto-retry, then `BROKEN` with "Download was corrupted".
2. **Smoke load.** On a background thread with a 45 s watchdog: `Engine(EngineConfig(path, Backend.CPU(), cacheDir, maxNumTokens))`, `initialize()`, one conversation, one ~8-token generation, `close()`. Pass ⇒ atomically rename `.part` → final and mark `INSTALLED`. Fail ⇒ `BROKEN` + "This model doesn't work with this version of the app" + `[Delete]`. This is worth the 45 s: it is the only thing that catches a `.litertlm` the shipped runtime cannot open, and without it the failure surfaces silently three hours later as an empty AI folder.

**S8 — Activate.** On first successful install of an LLM, set `sp_ai_model_triage` to it and show a snackbar `AI triage is ready` `[Run now]`. Never auto-activate a model that fails `SELECT_OK`.

**Delete.** Two entry points: overflow → Delete on a catalogue row, and `pref_ai_delete_models` (multi-select checkbox dialog listing name + size, footer "Frees 2.8 GB"). Deletes the whole `<normalizedRepo>/<version>/` directory. **If the deleted model is referenced by any `sp_ai_model_*`,** reset that key to its sentinel and toast "Triage will be skipped until you choose another model." Never leave a dangling path in prefs — a stale `modelPath` is the exact shape of a silent, permanent AI outage.

---

## 5. Catalogue: hardcoded, not remote

**Decision: hardcode `res/raw/ai_model_catalog.json` in the APK** (parsed with Gson, already a dependency), with two explicit escape hatches.

Reasons, in order of weight:

1. **Audience.** A Nextcloud News user self-hosts specifically to avoid third-party calls. A background fetch of a GitHub-hosted allowlist on every launch is a phone-home the app does not currently make — the only remote non-server call today is `DownloadChangelogTask`, and that is user-initiated. F-Droid-style packaging treats a silent remote config fetch as an anti-feature.
2. **Coupling.** The catalogue is coupled to the LiteRT-LM runtime version, not to time. A remote catalogue that advertises a model format the *shipped* runtime cannot open produces a failure mode we cannot fix remotely — and it is the exact failure S7's smoke load exists to detect. Shipping models and runtime together is the correct coupling.
3. **Volume.** Five models. The list changes at roughly the cadence of an app release anyway.

Escape hatches, which cover the "ship a new model without an app update" need without making it default behaviour:

- **`pref_ai_import_local`** in the manager's overflow: SAF `ACTION_OPEN_DOCUMENT` for a `.litertlm`/`.task`, copied into `models/local/<uuid>/`, hash recorded not checked, still subject to `SELECT_OK` and the S7 smoke load. This is how a power user gets a new model today.
- **`pref_ai_catalog_url`** — an `EditTextPreference`, **empty by default**, "Extra model list (advanced)". Fetched only when non-empty, only on manual pull-to-refresh in the manager, never in the background. Gate it behind a `BuildConfig` boolean so the `oss` flavour can hide it entirely if the packager wants a zero-network-surface build.

**Accepted cost:** pinned SHA-256s mean an upstream re-upload breaks that model's download until an app update. Handle it explicitly — on hash mismatch *with a full-length download*, say "This model changed on Hugging Face; update the app", not a generic corruption error.

---

## 6. Acceptance criteria (device-executable)

Run on two devices: **D-low** (4 GB RAM, 64-bit) and **D-high** (≥8 GB RAM). Build: `./gradlew :News-Android-App:assembleDevDebug`.

1. Fresh install, AI never touched: `Settings` shows an "AI triage" category with exactly two rows; `cb_ai_enabled` is **off**; a full sync completes and article order is byte-identical to the pre-AI build.
2. On a 32-bit-only or <3 GB device (or with `totalMem` stubbed under Robolectric), `cb_ai_enabled` and `pref_ai_settings` are both disabled and the summary reads "Not supported on this device".
3. On D-low, `AiSettingsActivity` shows no "Article summaries" and no "Interest-learning model" row; `sp_ai_batch_budget` reads `15`.
4. On D-high, both rows are present and `sp_ai_batch_budget` reads `30`.
5. In the model manager on D-low, `gemma-4-E2B` is greyed with "Needs ~4.9 GB RAM"; on D-high it is chipped `Recommended`. `gemma-4-E4B` is greyed on any device under 8 GB.
6. Tapping `Download` on `gemma-4-E2B` with no licence ack opens the browser to the HF model page and does **not** start a transfer. Returning and tapping again starts it.
7. With the licence unaccepted, force a 403 twice: `edt_ai_hf_token` becomes visible in `AiSettingsActivity`; pasting a valid token makes the same download succeed.
8. Mid-download, `adb shell am force-stop <pkg>`. Reopening the manager shows `Paused at N% — Resume`; resuming issues a `Range:` header (verify in the debug log) and the final file's SHA-256 matches the catalogue.
9. Mid-download, `adb shell svc wifi disable`: the row goes to `Paused — waiting for Wi-Fi` within 10 s, the `.part` still exists, and re-enabling wifi auto-resumes without user input.
10. With `cb_ai_downloads_wifi_only` on, switching from wifi to mobile mid-download pauses within 10 s and transfers **0** additional bytes over the mobile interface.
11. Fill the device to under `size*1.15 + 250 MB` free: `Download` is greyed with "Needs X GB more space", and a download already in flight aborts cleanly with the `.part` retained.
12. Corrupt a completed `.part` before verification: the file is deleted, one retry occurs, then the row reads `BROKEN` with a working `[Delete]`.
13. Place a valid-SHA but unloadable file: the S7 smoke load fails within 45 s and the row reads "doesn't work with this version of the app". The app does not ANR.
14. First successful LLM install auto-sets `sp_ai_model_triage`; `pref_ai_settings`' summary in the main Settings screen updates to "<model> · 2 models · ~2.8 GB" without reopening Settings.
15. `Settings → Clear cache` (`edt_clearCache`) is run: `getExternalFilesDir(null)/models/` is byte-for-byte unchanged and the model is still `Installed`.
16. Delete the model that `sp_ai_model_triage` points at: the pref resets to its sentinel, a toast appears, and the next sync completes with AI silently skipped (no crash, no empty list, article order falls back to recency).
17. With `sp_ai_model_digest = __same_as_triage__` and a digest generated right after a triage run, `Engine.initialize()` is called at most **once** for the whole sync+digest sequence (assert via the debug log).
18. Turning `cb_ai_enabled` off mid-run aborts the current inference within 5 s, leaves no foreground notification, and leaves the DB consistent.
19. `sp_ai_min_battery = 30` with the device at 25% and unplugged: triage is skipped and `pref_ai_last_run` reads "Skipped: battery low". Same for `sp_ai_run_trigger = charging` while unplugged.
20. **Measured budget gate (must be recorded, not assumed):** on D-high with `gemma-4-E2B` on GPU, 30 articles complete triage in **≤ 5 min** wall clock, and the JSON-parse failure rate after the single repair turn is **< 5%** over a 200-article corpus. On D-low with `Gemma3-1B-IT`, 15 articles in **≤ 5 min** with **< 15%** post-repair failure. If D-low misses this, the correct response is to demote T1's default from "triage on" to "embeddings/centroids only" — the taste model alone is still a shippable feature.
21. Every article that enters triage exits with a row in our AI DB — `score=null` on failure, never a missing row (veille invariant #2). Assert over a run with the model deliberately made to emit garbage.
22. `./gradlew detekt spotlessCheck lint test` all pass; `lint` in particular must not flag the new foreground-service type or the `<uses-native-library>` entries.

---

## Open items that block implementation

| # | Unknown | Blocks | Resolve by |
|---|---|---|---|
| R1 | On-disk `.litertlm` size of `gemma-3-270m-it`, `Gemma3-1B-IT`, and the `embeddinggemma-300m` bundle | The numeric constants in `SELECT_OK`/`DOWNLOAD_OK`, the T1 footprint figure, and the catalogue JSON | Read the three HF model card file listings |
| R2 | LiteRT-LM's actual `minSdk` | The T0 rule's API clause and whether the app's `minSdk 21` must move | `unzip -p <aar> AndroidManifest.xml` on the resolved `litertlm-android` artifact |
| R3 | Whether `litert-community/embeddinggemma-300m` ships the MediaPipe `.task` bundle `TextEmbedder` needs | **The entire embedding stage**, and whether row 4 needs to be a real picker (Gecko 768d / MobileBERT 512d fallback ladder). If it falls to MobileBERT, the FR/EN cross-language centroid assumption dies and the taste model degrades badly | Inspect the repo's file list; if absent, test `TextEmbedder.createFromFile` against whatever it does ship |
| R4 | Whether anonymous HF download succeeds post-licence-acceptance, or a token is mandatory | The ordering of S2 and the default visibility of `edt_ai_hf_token` | Two `curl -I` calls against the `resolve/main` URL |
| R5 | Whether `ExperimentalFlags.enableConversationConstrainedDecoding` can enforce a plain JSON schema (brief says it is "primarily wired for function calling") | If yes, it substantially de-risks the triage stage and could make `Gemma3-1B-IT` viable on T2, cutting the default footprint from 2.8 GB to 0.75 GB. Worth a spike **before** freezing the T2 default | One prototype constraining a 4-key object |