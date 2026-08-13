I have everything I need. Writing the design.

---

# TECHNICAL DESIGN — data layer & inference pipeline

**Verification stance.** Everything below that names a file, line, table or symbol I read myself in this session or is CONFIRMED in a validation report. Claims I could not verify are marked `[UNVERIFIED]` with the resolving test. I found three things nobody flagged; they change the design.

---

## 0. Three corrections that reshape the plan

### 0.1 There is no second database. Put the AI tables in `OwncloudNewsReaderOrm.db`.

Both product specs and the brief (PART D#1) assume a separate DB file, forced by the `DevOpenHelper` wipe. **The premise is wrong.**

`database/model/DaoMaster.java:28-33` — verified verbatim:

```java
public static void dropAllTables(SQLiteDatabase db, boolean ifExists) {
    FolderDao.dropTable(db, ifExists);
    FeedDao.dropTable(db, ifExists);
    RssItemDao.dropTable(db, ifExists);
    CurrentRssItemViewDao.dropTable(db, ifExists);
}
```

Four tables, by name. `DevOpenHelper.onUpgrade` (`:55-58`) calls exactly that plus `onCreate`. Any table greenDAO does not know about is **untouched by a schema-version bump**. Same for `DatabaseConnectionOrm.resetDatabase():78-83`, which is four `deleteAll()` calls, and for the `Settings → Clear cache` path (`SettingsFragment.java:310-313` → `checkForUnsycedChangesInDatabaseAndResetDatabase`).

Consequences, all good:
- No `ATTACH`. The three blockers validate:android-risk lists against ATTACH (runInTx, `:downloadWebPageProcess` / `:remote` multi-process connection pools, the AOSP WAL-disable) all evaporate rather than needing mitigation.
- No "compute the ordered id list in Java and emit `WHERE _id IN (...)`" (validate:embeddings §8.5). That fork would have required a second Java implementation of `onlyUnread`, sort direction, the search variants and fingerprint dedupe. Rejected.
- The list query is one plain `JOIN`, and `insertIntoRssCurrentViewTable(String)` is untouched.

We own our tables' migrations via `AI_META.K='schema_version'`. greenDAO keeps its destructive ones.

### 0.2 `RssItem.fingerprint` can be the empty string. Keying on it alone corrupts everything.

`reader/nextcloud/InsertRssItemIntoDatabase.java:75`:

```java
rssItem.setFingerprint(getStringOrDefault("fingerprint", "", e));
```

Default is `""`, not `null`. The guard at `:91-93` (`if (rssItem.getFingerprint() == null) setFingerprint(UUID.randomUUID())`) therefore **never fires** for a server that omits the field. `RSS_ITEM.FINGERPRINT` is `TEXT NOT NULL` (`RssItemDao.java:134`) and can legitimately hold `""` for every row.

product:triage-ux says "Key on `RssItem.fingerprint`, **not** `id`". Taken literally that gives one AI row for the entire corpus on such a server. (The existing app already mis-behaves here — `GROUP BY FINGERPRINT` collapses all empty-fingerprint items to one list row — which is evidence that in practice Nextcloud News does send it, but it is not a guarantee we can build a primary key on.)

**Resolution — one derived key, computed in Java, stored:**

```java
// AiKeys.java
static String of(RssItem it) {
    String fp = it.getFingerprint();
    return (fp == null || fp.isEmpty()) ? "id:" + it.getId() : fp;
}
```

`AI_KEY` is the durable identity (embeddings, decisions, taste). `RSS_ITEM_ID` is the transient identity (per-sync scores, the list JOIN). Both are stored on `AI_SCORE`.

### 0.3 The `GROUP BY FINGERPRINT` surgery: fan the score out across the group, do not alias.

`NewsReaderDetailFragment.java:538-543`, verified:

```java
int index = sqlSelectStatement.indexOf("ORDER BY");
if (index == -1) index = sqlSelectStatement.length();
sqlSelectStatement = new StringBuilder(sqlSelectStatement)
        .insert(index, " GROUP BY " + RssItemDao.Properties.Fingerprint.columnName + " ").toString();
dbConn.insertIntoRssCurrentViewTable(sqlSelectStatement);
```

validate:android-risk risk 2 is right that a bare `GROUP BY FINGERPRINT` over a JOIN makes SQLite pick an arbitrary group member, so `ORDER BY AI_SCORE.RANK_SCORE` reads an arbitrary rank. Their mitigation (b) — "score per fingerprint so the group is rank-homogeneous" — is the correct one, but it can't be a fingerprint-keyed table (see 0.2). The implementable form is a **fan-out write**:

```sql
-- after writing the score for the representative RSS_ITEM_ID
UPDATE AI_SCORE SET LLM_SCORE=?, EFF_SCORE=?, SIM_SCORE=?, RANK_SCORE=?, STATUS=?, THEMES=?, FLAGS=?, WHY=?, SCORED_AT=?
 WHERE AI_KEY = ?;
```

Because `AI_KEY` is derived from the fingerprint, every duplicate of an article shares one `AI_KEY` and gets one identical rank. The arbitrary group pick is then provably harmless. **And do not alias the tables in the AI branch** — `GROUP BY FINGERPRINT` must stay resolvable unqualified.

---

## 1. STORAGE — settled

### 1.1 File

All AI tables live in **`OwncloudNewsReaderOrm.db`** (`di/ApiModule.java`, `@Named("databaseFileName")`). Created idempotently at session construction, one hook:

```java
// database/DatabaseHelperOrm.java — insert between the existing lines 40 and 41
SQLiteDatabase db = helper.getWritableDatabase();
AiSchema.createOrMigrate(db);                 // NEW
DaoMaster daoMaster = new DaoMaster(db);
```

`DatabaseHelperOrm.getDaoSession` is a double-checked singleton inside `synchronized (DatabaseHelperOrm.class)`, so this runs exactly once per process. It must be **before** `new DaoMaster(db)` so a greenDAO upgrade in the same `getWritableDatabase()` call has already completed.

### 1.2 DDL — `database/ai/AiSchema.java`

```java
static final int AI_SCHEMA_VERSION = 1;

static void createOrMigrate(SQLiteDatabase db) {
  db.execSQL("CREATE TABLE IF NOT EXISTS AI_META ("
    + "K TEXT PRIMARY KEY NOT NULL, V TEXT, B BLOB)");

  // ---- transient: per-article triage state for articles currently in RSS_ITEM ----
  db.execSQL("CREATE TABLE IF NOT EXISTS AI_SCORE ("
    + "RSS_ITEM_ID   INTEGER PRIMARY KEY NOT NULL,"  // == RSS_ITEM._id (Nextcloud server id)
    + "AI_KEY        TEXT NOT NULL,"                 // fingerprint, or 'id:<n>' (see 0.2)
    + "STATUS        TEXT NOT NULL DEFAULT 'queued',"// queued|selected|discarded  (never 'kept')
    + "DISCARD_REASON TEXT,"                         // low_similarity|below_top_k|low_score|out_of_window
    + "SIM_SCORE     REAL,"                          // NULL = never embedded; 0.0 = cold start.  LOAD-BEARING
    + "LLM_SCORE     INTEGER,"                       // NULL = never reached the LLM.  LOAD-BEARING. Stored UNBUMPED
    + "EFF_SCORE     INTEGER,"                       // clamp(LLM_SCORE + feed bump, 0, 3)
    + "RANK_SCORE    REAL,"                          // eff + 0.5*sim, or round(sim,3) if LLM_SCORE IS NULL
    + "THEMES        TEXT,"                          // comma-joined slugs, relevance order preserved
    + "FLAGS         TEXT,"                          // comma-joined subset of competitor,regulation,customer
    + "WHY           TEXT,"                          // one line, device locale
    + "SCORED_AT     INTEGER,"                       // epoch ms
    + "SYNC_ID       INTEGER)");                     // which run produced this; used for resume + 'this batch'
  db.execSQL("CREATE INDEX IF NOT EXISTS IDX_AI_SCORE_RANK   ON AI_SCORE (STATUS, RANK_SCORE DESC)");
  db.execSQL("CREATE INDEX IF NOT EXISTS IDX_AI_SCORE_AI_KEY ON AI_SCORE (AI_KEY)");

  // ---- durable: survives the greenDAO wipe, the account reset and Clear cache ----
  db.execSQL("CREATE TABLE IF NOT EXISTS AI_EMBEDDING ("
    + "AI_KEY   TEXT PRIMARY KEY NOT NULL,"
    + "MODEL    TEXT NOT NULL,"      // e.g. 'embedding_gemma@int4int8'
    + "TASK     TEXT NOT NULL,"      // TextFormatContext EmbeddingType, e.g. 'CLUSTERING'  (see 3.1)
    + "DIM      INTEGER NOT NULL,"
    + "VEC      BLOB NOT NULL,"      // float32 LITTLE-ENDIAN, byte-identical to veille embed.py pack()
    + "CREATED_AT INTEGER NOT NULL)");
  db.execSQL("CREATE INDEX IF NOT EXISTS IDX_AI_EMB_MODEL ON AI_EMBEDDING (MODEL, TASK)");

  db.execSQL("CREATE TABLE IF NOT EXISTS AI_DECISION ("   // APPEND-ONLY. never UPDATE, never DELETE
    + "_id        INTEGER PRIMARY KEY AUTOINCREMENT,"
    + "AI_KEY     TEXT NOT NULL,"
    + "ACTION     TEXT NOT NULL,"    // keep|reject|restore|undo
    + "FROM_STATE TEXT NOT NULL,"    // the taste-state the row was in before
    + "TO_STATE   TEXT NOT NULL,"    // queued|kept|rejected
    + "LLM_SCORE_AT INTEGER,"        // snapshot; NULL = 'never reached the LLM' and is INFORMATION
    + "SOURCE     TEXT NOT NULL,"    // swipe|fastaction|star_seed|settings
    + "TITLE_SNAP TEXT,"             // sanitised title, 120 chars — the few-shot / learn corpus
    + "CREATED_AT INTEGER NOT NULL)");
  db.execSQL("CREATE INDEX IF NOT EXISTS IDX_AI_DEC_KEY ON AI_DECISION (AI_KEY, _id DESC)");

  db.execSQL("CREATE TABLE IF NOT EXISTS AI_TASTE ("      // current fold of AI_DECISION; one row per article
    + "AI_KEY   TEXT PRIMARY KEY NOT NULL,"
    + "STATE    TEXT NOT NULL,"      // kept|rejected   (queued rows are DELETEd — 'undo' means 'not a member')
    + "DECIDED_AT INTEGER NOT NULL,"
    + "IN_CENTROID INTEGER NOT NULL DEFAULT 0)");  // 1 = its vector is already folded into AI_CENTROID
  db.execSQL("CREATE INDEX IF NOT EXISTS IDX_AI_TASTE_STATE ON AI_TASTE (STATE)");

  db.execSQL("CREATE TABLE IF NOT EXISTS AI_CENTROID ("   // incremental accumulator, see §3.2
    + "CLASS    TEXT PRIMARY KEY NOT NULL,"  // 'kept' | 'rejected'
    + "MODEL    TEXT NOT NULL, TASK TEXT NOT NULL, DIM INTEGER NOT NULL,"
    + "N        INTEGER NOT NULL,"           // members folded in
    + "SUM      BLOB NOT NULL,"              // UNNORMALISED float32 running sum
    + "UNIT     BLOB)");                     // cached unit-normalised mean; NULL = recompute on read

  db.execSQL("CREATE TABLE IF NOT EXISTS AI_RUBRIC ("     // append-only, exactly one ACTIVE
    + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
    + "BODY TEXT NOT NULL, RATIONALE TEXT, ACTIVE INTEGER NOT NULL DEFAULT 0,"
    + "SOURCE TEXT NOT NULL,"                // user|model_approved|seed
    + "CREATED_AT INTEGER NOT NULL)");

  db.execSQL("CREATE TABLE IF NOT EXISTS AI_DIGEST ("
    + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
    + "DAY_KEY TEXT NOT NULL UNIQUE,"        // 'yyyy-MM-dd' in the device zone — enforces once/day
    + "RANGE_FROM INTEGER NOT NULL, RANGE_TO INTEGER NOT NULL,"  // on SCORED_AT, see §2 stage 8
    + "ITEM_COUNT INTEGER NOT NULL,"
    + "ABSTRACT TEXT,"                       // NULL = not generated yet / rejected by AbstractGuard
    + "ABSTRACT_STATE TEXT NOT NULL DEFAULT 'pending'," // pending|ok|skipped|failed
    + "DISMISSED_AT INTEGER,"
    + "CREATED_AT INTEGER NOT NULL)");
  db.execSQL("CREATE TABLE IF NOT EXISTS AI_DIGEST_ITEM ("
    + "DIGEST_ID INTEGER NOT NULL, AI_KEY TEXT NOT NULL, RSS_ITEM_ID INTEGER,"
    + "THEME TEXT, RANK_SCORE REAL, POS INTEGER NOT NULL,"
    + "PRIMARY KEY (DIGEST_ID, AI_KEY))");

  db.execSQL("CREATE TABLE IF NOT EXISTS AI_MODEL ("      // the local registry; catalogue is res/raw
    + "MODEL_ID  TEXT PRIMARY KEY NOT NULL,"  // catalogue id, e.g. 'gemma-4-E2B'
    + "KIND      TEXT NOT NULL,"              // llm | embedder
    + "PATH      TEXT,"                       // absolute path to the final file; NULL until INSTALLED
    + "STATE     TEXT NOT NULL,"              // absent|partial|verifying|installed|broken
    + "SIZE_BYTES INTEGER, RECEIVED_BYTES INTEGER, ETAG TEXT, SHA256 TEXT,"
    + "CAPS      TEXT,"                       // JSON probe result: {\"responseFormat\":true,\"tokenizer\":\"sp\"}
    + "LAST_ERROR TEXT, UPDATED_AT INTEGER NOT NULL)");
}
```

`AI_META` rows in use: `schema_version`, `interests_hash`, `decision_count`, `last_run_*`, `embed_backlog_cursor`, `active_sync_id`.

**Note on `AI_TASTE` vs `AI_DECISION`.** veille keeps the current status on `candidates` and the log on `decisions`. Here `candidates` is transient (`AI_SCORE`, dies with the article) while the taste membership must be durable — hence the split. `AI_TASTE` is a pure fold of `AI_DECISION` and is rebuildable from it; `AI_DECISION` is the source of truth and never mutated. That is what makes "Undo" cheap (append an `undo` row, delete the `AI_TASTE` row, subtract from `AI_CENTROID`) while preserving the disagreement history the learn loop needs.

**Note on `STATUS` vocabulary.** veille's rule "the pipeline never writes `kept` — that is a human's word" is preserved by using *different* vocabularies in the two tables: `AI_SCORE.STATUS ∈ {queued, selected, discarded}` (machine), `AI_TASTE.STATE ∈ {kept, rejected}` (human). No column can be ambiguous about who wrote it. product's "`status = 'selected'`" for the badge is therefore correct as written; validate:android-risk's proposed `STATUS='kept'` in `AI_SCORE` is wrong and would violate the invariant.

### 1.3 The list-ordering SQL — actual code

New branch in `DatabaseConnectionOrm.getAllItemsIdsForFolderSQL()`. The method appends a shared `ORDER BY PUB_DATE <dir>` at its end (verified, the last statement before `return buildSQL`), so the AI branch must **early-return**:

```java
public static final int AI_FOR_YOU = -14;   // SPECIAL_FOLDERS.AI_FOR_YOU

// inside getAllItemsIdsForFolderSQL, as the FIRST branch:
if (ID_FOLDER == AI_FOR_YOU) {
    String sql = "SELECT " + RssItemDao.TABLENAME + "." + RssItemDao.Properties.Id.columnName +
        " FROM " + RssItemDao.TABLENAME +
        " JOIN AI_SCORE ON AI_SCORE.RSS_ITEM_ID = "
              + RssItemDao.TABLENAME + "." + RssItemDao.Properties.Id.columnName +
        " WHERE AI_SCORE.STATUS = 'selected'";
    if (onlyUnread) {
        sql += " AND " + RssItemDao.TABLENAME + "." + RssItemDao.Properties.Read_temp.columnName + " != 1";
    }
    // NOTE: no alias anywhere. UpdateCurrentRssViewTask injects a bare
    // " GROUP BY FINGERPRINT " immediately before the first literal "ORDER BY".
    // It is rank-homogeneous because AI_SCORE is fanned out over AI_KEY (see §0.3).
    sql += " ORDER BY AI_SCORE.RANK_SCORE DESC, "
         + RssItemDao.TABLENAME + "." + RssItemDao.Properties.PubDate.columnName + " DESC, "
         + RssItemDao.TABLENAME + "." + RssItemDao.Properties.Id.columnName + " DESC";
    return sql;                     // deliberate: SP_SORT_ORDER does not apply to a relevance list
}
```

Three properties this must hold, each covered by a test (§4, `AiFolderSqlTest`):
1. The string contains exactly one literal `"ORDER BY"` (no subquery) so `indexOf` finds the right one.
2. After the injection the statement parses and `FINGERPRINT` resolves unqualified.
3. `insertIntoRssCurrentViewTable` writes `CURRENT_RSS_ITEM_VIEW._id` contiguous from 1. This holds because `_id` is `INTEGER PRIMARY KEY NOT NULL` **without AUTOINCREMENT** (`CurrentRssItemViewDao.java:38-40`, verified) so rowids restart after `deleteAll()`, and `getCurrentRssItemView(page)` pages on `C._id > page*25 AND C._id <= (page+1)*25` (`DatabaseConnectionOrm.java:486-494`, verified).

Search variant: `getAllItemsIdsForFolderSQLSearch()` gets the same JOIN with the `LIKE` predicates ANDed in. **Do not** filter in Java after `getCurrentRssItemView` — the existing `ALL_DOWNLOADED_PODCASTS` post-filter at `NewsReaderDetailFragment.java:551-557` only filters page 0 and is already a bug; do not replicate it.

Badge count (`SubscriptionExpandableListAdapter` :286 / `NotifyDataSetChangedAsyncTask.doInBackground()` :494):
```sql
SELECT COUNT(1) FROM RSS_ITEM JOIN AI_SCORE ON AI_SCORE.RSS_ITEM_ID = RSS_ITEM._id
 WHERE AI_SCORE.STATUS='selected' AND RSS_ITEM.READ_TEMP != 1
```

### 1.4 GC — must be wired or the DB grows forever

`RssItemObservable.sync()` calls `mDbConn.clearDatabaseOverSize()` at its top (verified, `RssItemObservable.java:112`); the prune SQL is `DatabaseConnectionOrm.java:768-800` and knows nothing about our tables. Add, immediately after it in `RssItemObservable.sync()`:

```java
mDbConn.aiGarbageCollect();
```
```java
// DatabaseConnectionOrm
public void aiGarbageCollect() {
    SQLiteDatabase db = daoSession.getDatabase();
    // 1. AI_SCORE is transient: it dies with its article, unconditionally.
    db.execSQL("DELETE FROM AI_SCORE WHERE RSS_ITEM_ID NOT IN (SELECT _id FROM RSS_ITEM)");
    // 2. Embeddings of articles the user ruled on are the taste model. Keep forever.
    //    Everything else follows the article cache.
    db.execSQL("DELETE FROM AI_EMBEDDING WHERE AI_KEY NOT IN (SELECT AI_KEY FROM AI_TASTE)"
             + " AND AI_KEY NOT IN (SELECT AI_KEY FROM AI_SCORE)");
    // 3. AI_DECISION / AI_TASTE / AI_CENTROID / AI_RUBRIC: never GC'd. They ARE the feature.
}
```

Steady state: `AI_EMBEDDING` ≈ `Constants.maxItemsCount` + decided articles. At 768 float32 = 3,072 B/row and a typical 1,000–2,000 row cache, that is **3–6 MB**. Do not MRL-truncate; do not int8-quantise; do not build an index. (validate:embeddings §8 measured a naive 20k×768 scalar scan at ~16 ms on JVM 21; we never scan more than the decided set, which is hundreds.)

---

## 2. PIPELINE — 9 veille stages, mapped 1:1

Runs as **one WorkManager `Worker`**, `AiTriageWorker`, unique work name `"ai_triage"`, `ExistingWorkPolicy.KEEP`.

**Why WorkManager and not the brief's `DownloadWebPageService` template.** validate:android-risk §8 is correct and I confirmed it: `services/DownloadWebPageService.java:48` is `extends Service`, not `JobIntentService`, it calls `startForeground` at `:84`, and its manifest entry (`AndroidManifest.xml:209-212`) has **no `foregroundServiceType`** while `targetSdk=35`. That class is already broken on Android 14+; it is not a template. `JobIntentService` (the two real ones, `SyncItemStateService:42`, `DownloadImagesService:51`) routes to `JobScheduler` on API 31+ with a ~10-minute window, which a 30-article scoring pass will exceed. Add `androidx.work:work-runtime:2.10.x` — the **Java** `Worker` API, not `-ktx`.

**Trigger.** `authentication/OwnCloudSyncAdapter.onPerformSync()`, one line after `startFaviconDownload()` (verified `:85`, exactly where the brief marks it) and **before** `syncRunning = false`:

```java
AiTriageScheduler.enqueueAfterSync(getContext());   // enqueue only; never inline
```
It must be enqueue-only: `onPerformSync` is synchronous and the `syncRunning` flag it holds is observed by the UI through `SyncStartedEvent`/`SyncFinishedEvent` (verified `:74`,`:89-91`). Minutes of inference inline would pin the UI in "syncing" and block subsequent periodic syncs from `SettingsFragment.setAccountSyncInterval()`.

**Constraints** (from `sp_ai_run_trigger`, `sp_ai_min_battery`): `setRequiresCharging` per pref, `setRequiresBatteryNotLow(true)`, `NetworkType.NOT_REQUIRED` (inference is offline; only downloads need `UNMETERED`). Foreground via `setForegroundAsync(new ForegroundInfo(id, n, FOREGROUND_SERVICE_TYPE_DATA_SYNC))` — the 3-arg ctor is mandatory on API 34+, and `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>` must be added next to `AndroidManifest.xml:18-19` (verified absent). Guard the `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` constant with `SDK_INT >= 34` or `lint`'s `InlinedApi` (error severity, `abortOnError true`, `build.gradle:94`) fails CI.

| # | veille stage | on-device | reason |
|---|---|---|---|
| 1 | `_stage_fetch` (382) | **DROP** | The SyncAdapter is the fetcher. `RssItemObservable.performDatabaseBatchInsert()` (`:104`) is the choke point. |
| 2 | `_stage_dedupe` (484) — canonical_url, title_hash, Jaccard≥0.85 | **DROP the algorithm, KEEP the effect** | The app already dedupes by fingerprint via the injected `GROUP BY FINGERPRINT`. Our `AI_KEY` fan-out (§0.3) makes AI state fingerprint-granular for free. Near-dup Jaccard is not worth a 400-title sliding window on a phone; the loss is a duplicate story from two feeds occasionally scoring twice, which costs one extra inference and shows as two rows — the same as today's Unread. |
| 3 | `_stage_candidates` (583) — `out_of_window`, NULL date KEPT | **KEEP, cheap SQL** | `WHERE READ_TEMP != 1 AND (PUB_DATE IS NULL OR PUB_DATE >= now - windowDays)`. Preserve the NULL-date-is-kept rule exactly. `windowDays` default 7, no pref in v1. |
| 4 | `_stage_embed` (690) — once per article ever | **KEEP verbatim.** `MediaPipe TextEmbedder`. | Idempotent via `LEFT JOIN AI_EMBEDDING` on `AI_KEY`. This is what makes the cost model true across syncs. Text = `(title + "\n" + summary)` truncated to **1400 chars** — not veille's 2000: the shipped `embedding_gemma.task` graph has a **static `[1,512]` INT32 input** (validate:embeddings §1 parsed the flatbuffer), so 2000 chars silently overflows the tokenizer. Cap `AI_EMBED_MAX_PER_SYNC = 60`, newest first, remainder carried in `AI_META.embed_backlog_cursor`. |
| 5 | `_stage_prefilter` (770) — **the taste model** | **KEEP verbatim, including the parts nobody quoted.** See §3. | This is the whole feature. |
| 6 | `_stage_score` (860) — batch 10 | **KEEP, batch = 1, schema-constrained.** See §2.2. | |
| 7 | `_stage_enrich` (1100) — full text + 4-language synthesis | **DROP from v1.** Capability retained behind `sp_ai_model_enrich = __off__`. | Input is 3–8k tokens of jsoup body × ~20 items. It is the highest hallucination surface and veille's own degrade path (invariant #8) says "skip enrich → keep stage-2 why-lines". The stage-2 `why` is already the product. I agree with product:models-ux here. |
| 8 | `_stage_rank` (1193) | **KEEP verbatim**, including the `llm_score IS NULL → round(sim,3)` branch and the 0.5 coefficient. | The 0.5 is chosen so similarity can never cross a tier boundary while the feed weight deliberately can. Do not "tune" it. |
| 9 | `notify.maybe_notify` | **KEEP, no LLM.** | Notification text = top item's title + its `why`. An LLM call to write a push line is pure waste. Off by default. |
| — | `ai/learn.py` rubric learn | **KEEP, manual only.** See §3.5. | Never on a timer. |
| — | `domain/digests.py::create` | **KEEP**, country axis collapsed to theme-only. | One user, one market. |

### 2.1 Per-run budget, cancellation, resume

```
budget:      sp_ai_batch_budget articles scored per run. T2 default 30, T1 default 15.
             + AI_EMBED_MAX_PER_SYNC = 60 embeddings.
wall clock:  hard watchdog at 12 min. On expiry: cancelProcess(), close, mark partial, return
             Result.success() (never failure — a retry storm is worse than a late batch).
serialise:   one Engine, one worker thread, embedder CLOSED before the Engine opens.
```

**Sequencing is mandatory, not stylistic.** validate:embeddings §7 confirmed the three `.so` files export zero overlapping symbols (no link conflict) but each spins its own XNNPACK pool; EmbeddingGemma is 123 MB resident at seq512 on top of a 1–3 GB LLM. The worker body is strictly:

```
embed-all → TextEmbedder.close() → prefilter (pure SQL+math, no model) →
AiEngineManager.acquire() → score loop → digest abstract (same warm Engine) → release()
```

**Cancellation.** `Conversation.cancelProcess()` is a real public method with a confirmed JNI export (`Java_..._nativeConversationCancelProcess`); it lands within roughly one token and leaves the `Conversation` usable. `Worker.onStopped()` → `AiEngineManager.cancelInFlight()`. The in-flight article is then treated exactly like veille's unparseable case: `LLM_SCORE = NULL`, rank on `SIM_SCORE` alone, and it stays `queued` so the next run retries it. **`LLM_SCORE = NULL` ≠ `0`.** Null means "never judged, retry"; 0 means "judged off-topic, final".

**Resume after kill.** Every article's row is committed the moment it is scored (one `UPDATE` per article, no batch transaction) with `SYNC_ID = AI_META.active_sync_id`. On restart the worker selects `STATUS='queued' AND LLM_SCORE IS NULL` ordered by `SIM_SCORE DESC` and continues. There is no separate checkpoint; the table *is* the checkpoint. This also satisfies the API-34 `dataSync` 6h/24h cumulative quota — every run is bounded and progress is never lost.

### 2.2 Scoring: schema-constrained JSON, batch = 1

**This overrides product:prompt-adaptation §0 ("no JSON anywhere in the on-device path").** That decision was made without knowing what validate:litert-lm §5 proved from the 0.15.0 bytecode: `ConversationConfig` has a 12th parameter `enableResponseFormat`, and `Conversation.resolveResponseFormat` returns your `ResponseFormat` **unchanged when no tools are registered** — which is our case. The grammar engine is LLGuidance with JSON Schema 2020-12 support. The pipe-delimited line format was designed to route around a problem the runtime solves at the decoder.

```java
String schema =
 "{\"type\":\"object\",\"additionalProperties\":false,"
 + "\"required\":[\"score\",\"themes\",\"flags\",\"why\"],\"properties\":{"
 + "\"score\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":3},"
 + "\"themes\":{\"type\":\"array\",\"maxItems\":3,\"items\":{\"enum\":[<userSlugs>]}},"
 + "\"flags\":{\"type\":\"array\",\"maxItems\":3,"
 +   "\"items\":{\"enum\":[\"competitor\",\"regulation\",\"customer\"]}},"
 + "\"why\":{\"type\":\"string\",\"maxLength\":160}}}";

ConversationConfig cfg = new ConversationConfig(
    Contents.Companion.of(systemPrompt), Collections.emptyList(), Collections.emptyList(),
    new SamplerConfig(1, 1.0d, 0.0d, 0), false, Collections.emptyList(),
    Collections.emptyMap(), null, false, 160, null, /*enableResponseFormat=*/ true);
Conversation c = engine.createConversation(cfg);
Message m = c.sendMessage(userPrompt, Collections.emptyMap(),
                          null, null, null, null, null, ResponseFormat.json(schema));
```

Note: no `oneOf` anywhere — LLGuidance rejects it by default ("Enable 'coerce_one_of'"). `enum` and `minimum`/`maximum` are supported.

**Batch = 1.** The array-of-N alignment problem — the reason veille needs invariants #2/#3 — disappears entirely: there is no id to align, one call is one article, a truncation loses one article instead of nine. Cost is 30 prefills instead of 3. The fixed prompt is ~550 tokens at ~500 tok/s prefill ≈ 1.1 s/call, so batch-1 costs ~25 s more per 30-article run than batch-4, against a run budget of minutes. Buy the reliability. `sp_ai_batch_size` exists (default 1) so the measurement in §7 can move it without a redesign; at batch >1 the schema becomes an array with `minItems=maxItems=N` and `prefixItems`, and the id is the array index.

**The blocking unknown, and it gates model choice.** `[UNVERIFIED]` Whether `gemma-4-E2B-it-litert-lm` / `Gemma3-1B-IT` use a SentencePiece tokenizer. The native lib contains the literal string `"Constrained decoding is only supported for SentencePiece tokenizer."`. Gemma 3 does use SentencePiece; the `gemma-4-*` bundles are unverified. **Resolving test:** at model install (S7 smoke load), create a conversation with `enableResponseFormat=true` and send a trivial 1-key schema. Catch `LiteRtLmJniException`; store the outcome in `AI_MODEL.CAPS` as `{"responseFormat":true|false}`. A model with `false` is offered but flagged, and `AiScoreParser` falls back to the lenient path below. This is one probe, already on the install critical path, and it removes the guesswork entirely.

**Parser — `AiScoreParser`, ~80 lines, always runs even in the constrained path.** The schema guarantees shape, not sanity.

| model behaviour | code guarantee |
|---|---|
| fenced ```` ```json ```` / preamble | strip even fence pairs, then depth-tracking balanced scan for the first `{`, respecting strings and escapes (veille invariant #4) |
| wrapper `{"results":{...}}` | unwrap `results\|articles\|items\|scores` (invariant #5) |
| `score` missing / non-numeric | `0`, **not** null (invariant #6). `"2.5"` → `Float.parseFloat` → round → 2 |
| `score` = 7 / −1 | clamp `[0,3]` |
| tag outside the closed vocabulary | `trim().toLowerCase().replace(' ','-')` then drop silently; `flags = tags ∩ FLAG_VOCAB`, `themes = tags − FLAG_VOCAB`, **order preserved** (`themes[0]` drives digest grouping) |
| `why` > 160 chars | truncate at word boundary + `…`; empty `why` is legal, UI falls back to the excerpt's first sentence |
| whole response unparseable | **one** repair turn on the same `Conversation` → still bad → `LLM_SCORE = NULL`, article stays `queued`, retried next run (invariant #2) |
| `LiteRtLmJniException` / OOM / native crash / user disabled AI mid-run | `try { } catch (Exception e)` around the per-article call → `LLM_SCORE = NULL`, loop continues. **Nothing propagates to the SyncAdapter.** |

Write this in **Java**. `detekt.yml:159` has `TooGenericExceptionCaught` active with `maxIssues: 0`, which forbids exactly the `catch (Exception e)` shape a never-lose-an-article parser needs; `detekt` and `spotless` target Kotlin only (`build.gradle:77-82`, target `src/*/java/**/*.kt`). Writing the pipeline in Java satisfies both gates trivially.

### 2.3 Prompts

Keep product:prompt-adaptation's **content** edits — they are well-argued and I endorse the cuts: doctoc block, the `score_threshold` leak (it hands the model a second objective), `published_at`/`lang` distractors, summary 1200→600 chars, `why_fr`+`why_en` → one `why` in the device locale, corrections block capped at 3 rows and omitted entirely (no heading) when empty. Keep the five calibration rules they kept: judge-the-article-not-the-source, judge-what-is-written, press-release-is-about-the-company, length-is-not-relevance, still-answer-0.

Discard their **format** half (§1.3–1.6 pipe lines, §5.1 `ScoreLineParser`). Replace the `ANSWER` section of `prompt_score_user.txt` with a one-line JSON shape note; the grammar does the enforcing.

Storage: `res/raw/prompt_*.txt`, `{{name}}` placeholders, a 6-line `String.replace` substituter. Correct call — `strings.xml` would force `%%` and `\'` escaping on every prompt and drag them into the translation pipeline.

---

## 3. THE TASTE MODEL

### 3.1 Embedding contract — get this wrong and nothing else matters

```java
// AiEmbedder — ONE context, built once, reused for every article and every centroid member
private static final TextEmbedder.TextFormatContext CTX =
    TextEmbedder.TextFormatContext.builder()
        .setTaskType(TextEmbedder.EmbeddingType.CLUSTERING)
        .setRole(TextEmbedder.TextRole.QUERY).build();

TextEmbedderOptions opts = TextEmbedderOptions.builder()
    .setBaseOptions(BaseOptions.builder().setModelAssetPath(absPath).build())
    .setL2Normalize(true)      // article vectors arrive unit-length
    .setQuantize(false)        // we need float[] to average into a centroid
    .build();
```

- **The 1-arg `embed(String)` overload is banned in code review.** It applies no task prefix. Prefixed and unprefixed vectors live in different regions of the space and cosine against the centroid is garbage — with no error and no symptom other than a taste model that never converges. Add a detekt/lint-style grep to CI: `grep -rn "\.embed(" | grep -v "CTX"` must return nothing.
- `CLUSTERING` over `SEMANTIC_SIMILARITY`: the operation is "is this article in the same neighbourhood as my liked pile", i.e. group membership, not sentence-pair STS. `RETRIEVAL_QUERY` is wrong — there is no asymmetric query/document split.
- `MODEL` and `TASK` are stored on every `AI_EMBEDDING` row. Changing either invalidates every vector and every centroid: `AiEmbeddingStore.assertCompatible()` compares against `AI_CENTROID.MODEL/TASK` and, on mismatch, truncates `AI_EMBEDDING` + `AI_CENTROID` and re-embeds the decided set first. Explicit migration, never silent drift.
- Source: `https://storage.googleapis.com/mediapipe-models/text_embedder/embedding_gemma/int4int8/latest/embedding_gemma.task`, 183,816,181 B, **ungated plain GET**. The brief's `litert-community/embeddinggemma-300m` is gated and ships 33 bare `.tflite` with no `.task` bundle — unusable. Two download regimes, not one generic HF downloader.
- `TextEmbedder.cosineSimilarity(Embedding,Embedding)` is `public static` and does exist, but **do not use it**: it throws `IllegalArgumentException` on zero L2-norm, and we need `cos(v,liked) − cos(v,rejected)` with a *missing* centroid contributing 0, not throwing. Write the 6-line dot product.

### 3.2 Centroids — incremental, with an exact-rebuild escape

veille recomputes the full mean per `(veille_id, decision_count)`. On a phone we can do better, because `mean = sum/n` and the incoming signal is one decision at a time.

```java
// AiCentroidStore.java
// AI_CENTROID holds the UNNORMALISED running SUM and N per class.
void onDecision(String aiKey, String from /*kept|rejected|none*/, String to) {
    float[] v = embeddings.get(aiKey);      // may be null — article never embedded
    if (v != null) {
        if (!"none".equals(from)) add(from, v, -1);   // SUM -= v ; N -= 1
        if (!"none".equals(to))   add(to,   v, +1);
        markInCentroid(aiKey, !"none".equals(to));
    }
    // If v == null the decision still counts toward decisionCount (the warm-up gate) but not the
    // centroid; AI_TASTE.IN_CENTROID stays 0 so backfill() folds it in once the vector exists.
    invalidateUnit();                        // AI_CENTROID.UNIT = NULL
    AiMeta.inc("decision_count");
}

float[] unit(String cls) {                   // lazily recomputed, cached in AI_CENTROID.UNIT
    if (n == 0) return null;                 // MISSING centroid, contributes 0. Not a zero vector.
    return unitNormalise(sum / n);           // null on zero-norm — veille embed.py _unit()
}
```

**Cache-invalidation key.** veille's `(veille_id, decision_count)` becomes `(decisionCount, embeddingModelId, embeddingTaskType)`. There is one veille. Persist the key in `AI_META` alongside `AI_CENTROID.UNIT`; any mismatch nulls `UNIT` and recomputes. The persisted `UNIT` also means a cold process start does not re-read hundreds of BLOBs.

**`rebuild()`** recomputes both classes from `AI_TASTE ⋈ AI_EMBEDDING`. Runs when: `AI_CENTROID.N` disagrees with `SELECT COUNT(*) FROM AI_TASTE WHERE STATE=? AND IN_CENTROID=1`, or model/task changed, or the user taps `pref_ai_reset_taste`. Float32 accumulation drift over ~10³ additions is ~1e-4 relative and irrelevant to a cosine, but the consistency check is free.

**Backfill.** Any decision whose article had no vector gets one later (the article may be re-fetched, or the embed backlog catches up). `backfill()` runs at the top of each worker: `SELECT AI_KEY FROM AI_TASTE WHERE IN_CENTROID=0` → embed if the text is still available → fold in. If the article is gone from `RSS_ITEM` forever, the row stays `IN_CENTROID=0` and is a permanent, harmless no-op.

### 3.3 `sim_score` and the two sentinels

```java
// AiSimilarity.java — port of veille embed.py sim_score(), semantics identical
static Float sim(float[] vec, float[] liked, float[] rejected) {
    if (vec == null) return null;                    // NULL = never embedded
    float[] u = unitNormalise(vec);
    if (u == null) return 0.0f;
    float s = 0f;
    if (liked    != null) s += dot(u, liked);
    if (rejected != null) s -= dot(u, rejected);
    return s;
}
```

`SIM_SCORE` **NULL = never embedded** (the prefilter could not judge this); **0.0 = embedded, but there are no decisions to compare against** (judged neutral). They look identical in a list and mean opposite things. Preserve.

### 3.4 Prefilter — exact port, including the clause everyone dropped

I read `pipeline.py:_stage_prefilter` (770-870). Every summary of it I was given omits the last third. Full semantics:

```java
boolean cold = (liked == null || rejected == null);   // EITHER missing => cold, not "weak"
int topK = max(1, prefilterTopK);

if (cold) {
    // similarity is SKIPPED ENTIRELY. Every candidate gets sim = 0.0.
    sort by recencyKey DESC          // (pubDate, fetchedAt), undated LAST
    selected = ranked[0..topK]
    dropped  = ranked[topK..] with reason "below_top_k"
} else {
    scored   = candidates with sim != null
    unscored = candidates with sim == null            // NOT "dissimilar" — unjudgeable
    above = scored where sim >= SIM_FLOOR, sorted by (sim, recencyKey) DESC
    below = scored where sim <  SIM_FLOOR
    selected = above[0..topK]
    dropped  = above[topK..] as "below_top_k"
             + below         as "low_similarity"
    // >>> THE CLAUSE EVERYONE MISSED <<<
    // unembedded articles FILL WHATEVER CAPACITY THE SCORED ONES LEFT, by recency.
    int room = max(0, topK - selected.size());
    unscored.sort(recencyKey DESC);
    selected += unscored[0..room];
    dropped  += unscored[room..] as "below_top_k";
}
// Discard reasons are RECORDED, never deleted. Rows stay in AI_SCORE with STATUS='discarded'.
```

That last block is why `low_similarity` is never applied to an unembedded article — "it would be a lie about them". It also means the first warm sync after an embed backlog still fills its budget instead of scoring three articles. Port it literally.

### 3.5 Numbers, justified

| constant | veille | phone | justification |
|---|---|---|---|
| `SIM_FLOOR` | 0.05 | **0.05, unchanged** | `sim` is a *difference of cosines*, so the model-specific baseline (EmbeddingGemma's cosines are compressed high, cohere-embed-v3's are not) cancels in the subtraction. The floor's meaning — "closer to the liked pile than the rejected pile, by a hair" — is model-independent. Empirically it also rarely binds: with `topK=30` against 60–200 candidates, `topK` is the selector and the floor is a safety net against a pathological run where almost everything is net-negative. Changing it without evidence would be cargo cult. |
| `prefilter_top_k` | 60 | **30 (T2) / 15 (T1)**, pref `sp_ai_batch_budget`, values 15/30/60/120 | This is a *scoring budget*, and scoring is now the wall clock. At batch=1 on a 1B int4 (~550 tok prefill @ ~500 tok/s + ~80 tok decode @ ~25 tok/s) one article is ≈ 4.3 s; 30 articles ≈ 2 min 10 s, 60 ≈ 4 min 20 s. veille's 60 was chosen against Bedrock latency, not a battery. 30 keeps a T2 run inside 5 minutes with margin for a repair turn. **This is the single lever that keeps cost flat as feeds grow** and it must stay a first-class pref. |
| `AI_EMBED_MAX_PER_SYNC` | n/a (veille embeds everything) | **60** | Separate from `topK` and necessarily ≥ it, because every candidate must be embedded *before* the prefilter can rank it. At 169–200 ms/article on a flagship and 450–700 ms on mid-range (published benchmarks, validate:embeddings §5), 60 articles = 10–42 s. 200 would be 90–140 s of pinned multi-core CPU — a thermal event. Backlog carries forward; the full first-sync backfill (200–400 articles) is gated on charger + idle. |
| warm-up gate | none | **10 decisions, ≥3 of each polarity** | I accept product's number as a *shipping* default but disagree with their framing — see Pushback §7.4. Below the gate the pipeline runs `cold` (which is already veille's own behaviour when either centroid is missing), so the gate costs zero code: it is `liked.n < 3 || rejected.n < 3 || total < 10 ⇒ treat as cold`. |
| `score_threshold` | 2 | **2**, no pref | `selected iff EFF_SCORE >= 2`. |
| `WEIGHT_SCORE_BUMP` | low −1 / normal 0 / high +1 / bonus +2 | **keep**, per-feed, default `normal` | Stored in `AI_META` as a JSON map keyed on feed id (not a `Feed` column — that would need a greenDAO schema bump and wipe every user's cache). |
| `LEARN_MIN_DECISIONS` | 25 | **25**, manual button only | Gate on `decision_count − AI_RUBRIC.active.created_at_count`. Never on a timer. |
| few-shot disagreements | 5 | **3** | ~30 tokens each against a ~550-token fixed prompt. Predicate ported verbatim from `learn.py:62`: `action ∈ (keep,reject,restore) AND (restore OR (reject AND llm_score_at ≥ threshold) OR (keep AND llm_score_at < threshold))`. `restore` is a standalone OR term **because its `llm_score_at` is NULL** — do not "simplify" it into the other two. |

### 3.6 Decision state machine

Ported from `domain/decisions.py:29-38`, verified. Table is `(action → allowed-from, target)`:

| action | valid from | → |
|---|---|---|
| keep | queued, rejected, discarded | kept |
| reject | queued, kept, discarded | rejected |
| restore | discarded | queued |
| undo | kept, rejected | queued |

Anything else is a **silent idempotent no-op with no `AI_DECISION` row**. `decide()` returns `(newState, changed)`; `changed == false` must not toast, must not vibrate, must not log an error. On the phone this matters more than on the web: the UI fires from a list that moved under the user's thumb.

Note the mapping: our `AI_SCORE.STATUS` is the machine's word and `AI_TASTE.STATE` is the human's. "valid from `discarded`" reads as: an article the pipeline dropped can still be kept/rejected by the user from a search result or from Unread. `undo` deletes the `AI_TASTE` row (state "none") and appends an `AI_DECISION` row with `TO_STATE='queued'` — the disagreement history is never erased, which is exactly what the learn loop consumes.

---

## 4. CLASS LIST

Package root `de.luhmer.owncloudnewsreader`, matching the existing convention (`database/`, `di/`, `adapter/`, `services/`, `helper/`, `reader/nextcloud/`). Paths are relative to `News-Android-App/src/`.

**All Java** except the two view-holder/UI files, which are Kotlin to match `adapter/`'s 9 existing `.kt` view holders. Rationale: `spotless`/`detekt` only touch `src/*/java/**/*.kt`, LiteRT-LM and MediaPipe are both fully Java-callable (validate:litert-lm §4 compiled real Java against 0.15.0; validate:embeddings §3 confirmed `TextEmbedder` is `AutoCloseable` + Java-friendly), and nothing forces coroutines — the blocking `sendMessage(...)` and callback `sendMessageAsync(..., MessageCallback)` overloads exist; only the `Flow` overload needs Kotlin and is avoidable.

### `main/java/.../database/ai/` — persistence (Java)
| file | responsibility |
|---|---|
| `AiSchema.java` | The DDL above; `createOrMigrate(SQLiteDatabase)`, idempotent, owns `AI_META.schema_version`. |
| `AiKeys.java` | `AI_KEY` derivation (fingerprint, or `id:<n>`); the empty-fingerprint guard of §0.2. |
| `AiDb.java` | Thin raw-SQL facade over `daoSession.getDatabase()`. Every AI query lives here; nothing else touches SQL. |
| `AiScoreStore.java` | CRUD on `AI_SCORE`, incl. the `AI_KEY` fan-out UPDATE of §0.3 and the resume selector. |
| `AiEmbeddingStore.java` | `AI_EMBEDDING` read/write; float32-LE `pack`/`unpack` byte-identical to veille `embed.py`; `assertCompatible()` model/task migration. |
| `AiDecisionStore.java` | Append-only `AI_DECISION` + the `AI_TASTE` fold; implements the §3.6 transition table. |
| `AiCentroidStore.java` | `AI_CENTROID` incremental add/subtract, `unit()`, `rebuild()`, `backfill()`, the cache key. |
| `AiRubricStore.java` | `AI_RUBRIC` append-only, exactly one `ACTIVE`; version history + revert. |
| `AiDigestStore.java` | `AI_DIGEST` / `AI_DIGEST_ITEM`; `DAY_KEY` uniqueness is what enforces once-per-day. |
| `AiModelRegistry.java` | `AI_MODEL` state machine (`absent→partial→verifying→installed\|broken`), `CAPS` probe result. |

### `main/java/.../ai/` — domain (Java)
| file | responsibility |
|---|---|
| `AiFeature.java` | The single `isEnabled(Context)` gate: pref AND tier AND 64-bit ABI AND installed model. Every entry point calls it first. |
| `AiDeviceTier.java` | T0/T1/T2 from `SUPPORTED_64_BIT_ABIS`, `ActivityManager.MemoryInfo.totalMem`, `SDK_INT`. |
| `AiCandidates.java` | veille stage 3: unread + publish-window, NULL-date kept. |
| `AiPrefilter.java` | veille stage 5 **verbatim**, including the unscored-fills-remaining-room clause (§3.4). |
| `AiSimilarity.java` | `unit()`, `dot()`, `sim()`; the NULL-vs-0.0 sentinel contract. |
| `AiRank.java` | `effective = clamp(llm + bump, 0, 3)`; `rank = round(eff + 0.5*sim, 3)`, or `round(sim,3)` when `llm IS NULL`. |
| `AiPromptBuilder.java` | `res/raw/prompt_*.txt` + `{{}}` substitution; interests cap 1200 chars at a newline; corrections block omitted entirely when empty; title/note sanitisation (control chars collapsed, 200-char cap, pipes stripped). |
| `AiScoreSchema.java` | Builds the JSON Schema string from the live theme-slug set. No `oneOf`. |
| `AiScoreParser.java` | The §2.2 table. Depth-tracking balanced-brace extraction, wrapper unwrap, clamping, vocabulary filter, one repair turn, degrade to `score=null`. **Never throws.** |
| `AiAbstractGuard.java` | Strips fences/`Here is…`/trailing pleasantries; keeps ≤6 sentences; **drops the abstract entirely if <80 chars or letter-free**. A bad abstract is worse than none. |
| `AiTasteDraftGuard.java` | The anti-collapse checks: `<60%` of current length ⇒ warn banner (never auto-reject); `>1` topic line removed ⇒ pre-select "keep current"; strip lines copied verbatim from a scraped title. |
| `AiDigestBuilder.java` | Theme grouping, each item once under `themes[0]`, blocks by `−sum(rank_score)`; range on decision/selection time, not `pubDate`. |
| `AiDegrade.java` | The §6 ladder as one enum + one `describe()` returning the status-strip string. |

### `main/java/.../ai/engine/` — runtime (Java, `mlGemma` source set)
| file | responsibility |
|---|---|
| `LlmClient.java` (**`src/main`**, interface) | `String complete(String system, String user, String jsonSchemaOrNull, int maxTokens)`. The seam that makes the domain JVM-testable. |
| `Embedder.java` (**`src/main`**, interface) | `float[] embed(String text)` + `String modelId()`, `String taskType()`. |
| `AiEngineManager.java` | Refcounted singleton holder of one `Engine`. `acquire()` does `new Engine(cfg); initialize()` off the main thread (blocking, up to ~10 s); `release()` closes after an idle timeout; `cancelInFlight()` calls `Conversation.cancelProcess()`. **Never `@Provides Engine` directly** — see §5. |
| `LiteRtLlmClient.java` | `LlmClient` over `Conversation.sendMessage(..., ResponseFormat)`, blocking, on the worker thread. |
| `MediaPipeEmbedder.java` | `Embedder` over `TextEmbedder`; owns the single `TextFormatContext`; `close()` before the LLM opens. |
| `AiModelProbe.java` | The S7 smoke load: init, one 8-token generation, one 1-key constrained-JSON call → writes `AI_MODEL.CAPS`. 45 s watchdog. |
| `NoopLlmClient.java` / `NoopEmbedder.java` (**`src/mlNone`**) | Always report unavailable. |

### `main/java/.../ai/work/` — execution (Java)
| file | responsibility |
|---|---|
| `AiTriageWorker.java` | The `Worker`. Stage sequence, budget, watchdog, `setForegroundAsync`, `onStopped()` → cancel. |
| `AiTriageScheduler.java` | `enqueueUniqueWork("ai_triage", KEEP, …)` + constraints from prefs. Called from `OwnCloudSyncAdapter`. |
| `AiDigestWorker.java` | Lazy abstract generation on first open of a new day; reuses the warm `Engine`. |
| `AiModelDownloadWorker.java` | OkHttp 5 streaming to `.part` + `.meta`, `Range:`/`If-Range:` resume, SHA-256 verify, then `AiModelProbe`. |
| `AiWorkerDeps.java` | Dagger injection target — `Worker` is instantiated by WorkManager, so it injects a deps holder rather than itself. |

### `main/java/.../ai/ui/` — surfaces
| file | lang | responsibility |
|---|---|---|
| `AiSettingsActivity.java` + `AiSettingsFragment.java` | Java | Hosts `res/xml/pref_ai_detail.xml`; must call `getPreferenceManager().setSharedPreferencesName(sharedPreferencesFileName)`. |
| `AiModelManagerActivity.java` | Java | RecyclerView + ViewBinding over the catalogue. Not a preference screen. |
| `AiDigestActivity.java` | Java | Full digest screen. |
| `AiRubricDiffActivity.java` | Java | Line-diff + explicit Save. Nothing writes `AI_RUBRIC` from a model response. |
| `adapter/AiRssItemViewHolder.kt` | Kotlin | 8th view-holder variant, selected by `idFolder == AI_FOR_YOU`, binds `ai_list_item.xml` (why-line + score pill). |
| `adapter/DigestCardViewHolder.kt` | Kotlin | Position-0 card in `NewsListRecyclerAdapter`. |

### `test/java/.../ai/` (JUnit4 + Robolectric 4.16.1)
`AiScoreParserTest`, `AiPrefilterTest` (the cold/warm/unscored-room matrix), `AiCentroidTest` (incremental == rebuild, property test), `AiRankTest` (the 0.5-never-crosses-a-tier invariant), `AiDecisionStateMachineTest`, `AiPromptBuilderTest`, `AiDigestBuilderTest`, `AiAbstractGuardTest`, plus the two that matter most:

- **`database/ai/AiSchemaTest.java`** — real SQLite under Robolectric: create schema, insert AI rows, call `DaoMaster.dropAllTables(db,true)` + `createAllTables`, assert **every AI row survives**. This is the regression test for §0.1 and it must exist before the first AI row is ever written.
- **`database/ai/AiFolderSqlTest.java`** — assert the exact generated string for `AI_FOR_YOU`, apply the `" GROUP BY FINGERPRINT "` injection from `NewsReaderDetailFragment:538-543` character-for-character, run it, then assert `getCurrentRssItemView(0)` and `(1)` return the ranked order with contiguous `_id`. Also assert a duplicate-fingerprint pair produces one row at the correct rank. This is the regression test for §0.3 and §1.3, and it is the cheapest insurance in the project.

Also fix `src/test/resources/org.robolectric.Config.properties`: `emulateSdk=18` is a Robolectric 2.x key and inert under 4.16.1. Replace with `sdk=34`.

---

## 5. DAGGER WIRING

`di/AppComponent.java:29-30` is a single `@Singleton @Component(modules = { ApiModule.class })` with one explicit `injectXxx` per type. Verified — there is no `@ContributesAndroidInjector`, no Hilt.

### New module `di/AiModule.java`

Two copies, **same FQN**, one per `ml` flavor source set (`src/mlGemma/java/.../di/AiModule.java`, `src/mlNone/java/.../di/AiModule.java`). `AppComponent` lives in `src/main` and references `AiModule.class`, so both must exist or the `mlNone` variant will not compile. This is the main structural cost of the flavor split.

```java
@Module
public class AiModule {
    private final Application app;
    public AiModule(Application app) { this.app = app; }

    @Provides @Singleton AiDb provideAiDb(@Named("databaseFileName") String dbName) { ... }
    @Provides @Singleton AiScoreStore      provideScoreStore(AiDb db);
    @Provides @Singleton AiEmbeddingStore  provideEmbeddingStore(AiDb db);
    @Provides @Singleton AiDecisionStore   provideDecisionStore(AiDb db);
    @Provides @Singleton AiCentroidStore   provideCentroidStore(AiDb db, AiEmbeddingStore e);
    @Provides @Singleton AiRubricStore     provideRubricStore(AiDb db);
    @Provides @Singleton AiDigestStore     provideDigestStore(AiDb db);
    @Provides @Singleton AiModelRegistry   provideModelRegistry(AiDb db, SharedPreferences p);

    @Provides @Singleton AiDeviceTier      provideTier();                       // pure, cached
    @Provides @Singleton AiFeature         provideFeature(SharedPreferences p, AiDeviceTier t,
                                                          AiModelRegistry r);

    // >>> NEVER @Provides Engine, and never @Provides LlmClient as a @Singleton. <<<
    @Provides @Singleton AiEngineManager   provideEngineManager(AiModelRegistry r, SharedPreferences p);
    @Provides @Singleton AiEmbedderManager provideEmbedderManager(AiModelRegistry r);
}
```

**Why no `@Provides Engine`.** Two reasons, both fatal. (a) Dagger `@Singleton` has no lifecycle — nothing will ever call `close()` on an `AutoCloseable` holding multi-GB of mmapped weights, so the LRU killer takes the app down mid-scroll. (b) `NewsReaderApplication.onCreate()` builds the component and runs in **every** process; the manifest declares `android:process=":downloadWebPageProcess"` (`AndroidManifest.xml:212`, `:217`) and `:remote` (`:290`), verified. A `@Singleton Engine` would be constructed once per process. `AiEngineManager` is a refcounted holder whose `acquire()`/`release()` bracket the `Worker`'s `doWork()` body and whose `close()` is called from `onStopped()`.

### Methods to add to `di/AppComponent.java`

```java
@Component(modules = { ApiModule.class, AiModule.class })   // <- change line 30
public interface AppComponent {
    // ... existing 16 methods unchanged ...

    void injectActivity(AiSettingsActivity activity);
    void injectActivity(AiModelManagerActivity activity);
    void injectActivity(AiDigestActivity activity);
    void injectActivity(AiRubricDiffActivity activity);

    void injectFragment(AiSettingsFragment fragment);

    void injectWorkerDeps(AiWorkerDeps deps);   // AiTriageWorker / AiDigestWorker /
                                                // AiModelDownloadWorker all take deps via this
}
```

**Not needed:** `injectService(OwnCloudSyncAdapter)` already exists (`:49`) — the enqueue hook adds no new injection point. Do **not** add an inject for `NewsListRecyclerAdapter`; it takes its dependencies through its constructor today and must keep doing so.

`NewsReaderApplication.onCreate()` must pass the new module: `DaggerAppComponent.builder().apiModule(new ApiModule(this)).aiModule(new AiModule(this)).build()`.

**Instrumented tests.** `di/TestComponent` mirrors `AppComponent` — every method added above must be added there too, with a `TestAiModule` providing `NoopLlmClient`/`NoopEmbedder`. Missing this breaks `connectedDevMlGemmaDebugAndroidTest` with a compile error, not a test failure.

---

## 6. DEGRADATION LADDER

The rule is veille invariant #8, and it is enforced structurally: **`AiTriageWorker.doWork()` has exactly one `return`, `Result.success()`, and its whole body is inside a `try/catch(Throwable)`.** There is no code path from the AI half into `onPerformSync`. `Result.failure()` is never returned (it would trigger a retry storm) and `Result.retry()` only for a transient IO fault in the download worker.

| condition | detection | pipeline behaviour | list order | strip |
|---|---|---|---|---|
| `cb_ai_enabled = false` | pref | `AiTriageScheduler.enqueue` is a no-op. Nothing is scheduled, nothing runs. | `AI_FOR_YOU` falls back to recency (`AiPrefilter` cold path) | "AI triage off · Set up" |
| No model installed | `AI_MODEL` has no `installed` LLM | embed still runs if the embedder is installed → **sim ranking works with no LLM at all**; `LLM_SCORE` stays NULL; `RANK_SCORE = round(sim,3)` | ranked by similarity, no pill, no why | "Ranking by similarity. Add a scoring model for scores and summaries." |
| No embedder either | `AI_MODEL` has no `installed` embedder | `SIM_SCORE` NULL for everything → `AiPrefilter` cold path → recency top-`k` | recency | "AI triage off" |
| Device T0 (no 64-bit ABI / <3 GB / SDK<24) | `AiDeviceTier` | `AiFeature.isEnabled()` false. `cb_ai_enabled` and `pref_ai_settings` disabled. No download possible. | recency, permanently | "This device can't run on-device AI · Why?" |
| `mi.lowMemory` at acquire time | `ActivityManager.getMemoryInfo` | **skip the scoring stage only**; embed + prefilter still run, so sim ranking survives | similarity | "Not enough free memory right now" |
| Deferred (not charging / battery < `sp_ai_min_battery` / thermal ≥ MODERATE) | WorkManager constraint + `PowerManager.getCurrentThermalStatus()` (API 29+, no-op below) | work not started, or aborted mid-run; committed rows keep their scores | last completed ranking | "Waiting for charger · Run now" |
| `Engine.initialize()` fails / times out (>45 s) | watchdog | mark `AI_MODEL.STATE='broken'` + `LAST_ERROR`; reset `sp_ai_model_triage` to sentinel; whole batch → `LLM_SCORE = NULL` | similarity | "Scoring failed · Retry · Try a smaller model" |
| `LiteRtLmJniException` / OOM / native crash mid-batch | `catch (Throwable)` per article | **that article only** → `LLM_SCORE = NULL`, stays `queued`, retried next run. Loop continues. | mixed: scored rows keep pills, unscored rank on sim | "Scoring failed" only if >50% of the batch failed |
| Unparseable response | `AiScoreParser` | one repair turn → `LLM_SCORE = NULL` | as above | silent |
| Constrained decoding unsupported by the model | `AI_MODEL.CAPS.responseFormat == false` | `enableResponseFormat=false`, lenient parser path, repair turn becomes load-bearing | unchanged | silent |
| User disables AI mid-run | pref listener → `WorkManager.cancelUniqueWork` | `onStopped()` → `cancelProcess()` → in-flight article `LLM_SCORE = NULL`; foreground notification removed within 5 s; DB consistent (per-article commits) | last completed | "AI triage off" |
| Model file deleted while referenced | `AI_MODEL.PATH` missing at acquire | reset the `sp_ai_model_*` key to its sentinel, toast once. **Never leave a dangling path in prefs** — that is the shape of a silent permanent outage. | similarity | "Scoring failed" |
| `AI_SCORE` empty for a folder open | — | folder still renders, from recency | recency | onboarding card |

Two invariants that make this auditable, both testable:
1. **Every article that enters the scoring stage exits with an `AI_SCORE` row.** `score = null` on failure, never a missing row. Assert over a run with the model forced to emit garbage.
2. **No article ever disappears.** A `discarded` AI row changes nothing about `RSS_ITEM`; every article remains findable in Unread with identical read/starred state. This is what the "Nothing worth your time in this batch — they're all still in Unread" empty state is asserting, and it must be true.

---

## 7. Pushback

### 7.1 Two separate databases — **rejected.** (product:triage-ux §1, product:models-ux, brief PART D#1)

Both specs treat "separate AI SQLite DB" as forced. It is not: `DaoMaster.dropAllTables` names four tables (§0.1, quoted). Same file, own tables. This deletes the ATTACH-vs-denormalise decision, deletes validate:embeddings' Java-side `WHERE _id IN (...)` proposal, deletes the `SQLITE_BUSY` risk across `:downloadWebPageProcess`/`:remote`, and turns the list query into a two-line JOIN. **The `AiSchemaTest` in §4 is the guard** — if a future greenDAO regen ever adds our tables to `dropAllTables`, that test fails loudly.

### 7.2 "Key on fingerprint, not id" — **wrong as stated.** (product:triage-ux §1)

`fingerprint` defaults to `""`, not `null`, so the existing null-guard never fires (§0.2, quoted). One `AI_KEY` for the whole corpus on any server that omits the field. Use the derived `AI_KEY`, and split transient (`RSS_ITEM_ID`) from durable (`AI_KEY`) identity.

### 7.3 "No JSON anywhere on-device" / pipe-delimited lines — **rejected.** (product:prompt-adaptation §0, §1.3–1.6, §5.1)

Well-reasoned against the wrong runtime. `ConversationConfig.enableResponseFormat` + `ResponseFormat.json(schema)` gives decoder-level grammar enforcement (LLGuidance, JSON Schema 2020-12) whenever no tools are registered — proven from the 0.15.0 bytecode. The pipe format was engineering around a solved problem, and it costs the closed-vocabulary guarantee (`enum` in the schema is stronger than a prompt request) plus the score range (`minimum/maximum` beats `coerceIn`). **Keep their prompt-content edits, discard their format.** Add the tokenizer probe (§2.2) so a model that cannot do it is caught at install, not three hours later as an empty folder.

Also: their batch-of-4 arithmetic assumes the fixed prompt is re-prefilled per call. Whether `systemInstruction` is re-prefilled per `createConversation` is `[UNVERIFIED]` and is the one measurement that could move the default — their own §7.2 says so and they are right. Ship batch=1; measure with `Conversation.getBenchmarkInfo()`.

### 7.4 The 10-decision warm-up gate — **accept the number, reject the framing.** (product:triage-ux §2)

They present it as a new invention ("veille has no such gate"). veille *does* have it: `cold_start(liked, rejected)` fires when **either** centroid is missing, and a user who has only ever swiped right has no rejected centroid — so veille already refuses to rank on one class. The gate is therefore `liked.n < 3 || rejected.n < 3 || total < 10 ⇒ cold`, which is a one-line strengthening of an existing predicate, not a new subsystem. Framing it as new invites someone to implement it as a separate branch that then drifts from the prefilter.

### 7.5 Deleting the model on `edt_clearCache` — **product is right, and the risk is worse than they state.**

`NewsFileUtils.getCacheDirPath()` returns `getExternalCacheDir()` (verified, `:119-121`), which is a different root from `getExternalFilesDir(null)/models/` — so the model is safe from *that*. But `EDT_CLEAR_CACHE`'s actual handler (`SettingsFragment.java:310-313`, verified) calls `checkForUnsycedChangesInDatabaseAndResetDatabase` → `resetDatabase()`, which wipes `RSS_ITEM`. Under our design that orphans `AI_SCORE` (GC'd, fine) while `AI_EMBEDDING`/`AI_DECISION`/`AI_CENTROID`/`AI_RUBRIC` survive on `AI_KEY` — **the taste model is preserved across a full cache clear.** That is the correct behaviour and it falls out of the key split for free. It also needs a test.

### 7.6 `AI_SCORE.STATUS = 'kept'` — **rejected.** (validate:android-risk risk 1 DDL)

Their proposed schema puts `kept|rejected|discarded|queued` in the machine's table and the list query selects `STATUS='kept'`. That collapses veille's most carefully guarded invariant: *the pipeline never writes `kept` — that is a human's word*. Once one table can hold both, some future code path will write `kept` from a scoring result and the disagreement signal — the entire input to the learn loop — becomes unrecoverable. Two vocabularies, two tables (§1.2).

### 7.7 Product's separate "Digest writer" / "Interest-learning model" pickers — **keep the prefs, but they must not be able to hold a second resident model.**

product:models-ux' `__same_as_triage__` sentinel is the right default, but the spec allows selecting a *different* installed model for digest/enrich/learn. `AiEngineManager` holds exactly one `Engine`. If a stage names a different model, the manager must `close()` and re-`initialize()` — up to 10 s and a full weight remap. Enforce it: **`AiEngineManager.acquire(modelId)` closes and reopens on mismatch, and logs a `MODEL_SWITCH` counter to `pref_ai_last_run`.** Their acceptance criterion 17 ("`Engine.initialize()` called at most once for the whole sync+digest sequence") is then automatically satisfied for the default config and observably violated for a non-default one, which is the honest outcome.

### 7.8 Dwell-time weak positive — **agree it's out of v1, and it should never come back.**

Product defers it with a dogfood criterion. I'd go further: `CB_MARK_AS_READ_WHILE_SCROLLING_STRING` and `CB_SYNC_WHEN_SCROLLED_TO_BOTTOM_STRING` mean "read" in this app is generated *by scrolling past things*. Any implicit signal derived from that surface is measuring scroll velocity, not taste. If it is ever revisited it must be dwell in `NewsDetailActivity` only, and it must still never enter the centroid.

### 7.9 Where I am uncertain

| # | unknown | blocks | resolving test |
|---|---|---|---|
| U1 | Tokenizer of the default scoring model → is constrained decoding available? | whether §2.2 is a solved problem or a repair-loop slog | `AiModelProbe` at install: one 1-key schema call; catch `LiteRtLmJniException` containing "SentencePiece". **Do this before freezing the T2 default.** |
| U2 | Is `systemInstruction` re-prefilled per `createConversation`? | batch=1 vs batch=4 | time 10× (`createConversation` + 1-article call) vs 1 conversation + 10 turns; read `Conversation.getBenchmarkInfo()` |
| U3 | Resident RSS of a 2.58 GB `gemma-4-E2B` on a mid-range phone | whether `SELECT_OK = totalMem ≥ size*1.5 + 1 GB` is right, and whether the `:ai` process split is needed | `Debug.getMemoryInfo()` + `adb shell dumpsys meminfo` around a real 30-article run |
| U4 | Does `TextEmbedderGraph` honour `Delegate.GPU`/`NPU`? | 169 ms → 18 ms/article; would raise `AI_EMBED_MAX_PER_SYNC` a lot | one device run with `setDelegate(Delegate.GPU)`; check for a graph-init error |
| U5 | Does MediaPipe double-prefix if the C++ graph also formats? | silent embedding-quality loss | on device: `embed("x", CTX)` vs `embed("task: clustering \| query: x")`, compare `floatEmbedding()` element-wise. **Must run before any user vector is written.** |
| U6 | Anonymous HF download post-licence-acceptance, or is a token mandatory? | ordering of the licence flow and the default visibility of `edt_ai_hf_token` | two `curl -I` against `.../resolve/main/<file>.litertlm`, once anonymous, once with a token |

### 7.10 The decision that is not mine and must be made before code

validate:android-risk's #1 stands and I endorse it: `com.google.mediapipe:tasks-core:1.0.0` pulls `com.google.android.datatransport:transport-backend-cct` (Clearcut) into an app whose flavor is literally commented `// 100% Open-Source Edition` (`build.gradle:66`), ships on F-Droid, and whose `PRIVACY.md:3` promises no data leaves the device. Plus both AARs are unbuildable binary blobs F-Droid will not accept, plus LiteRT-LM ships **only** `arm64-v8a` and `x86_64`, plus `minSdk 21 → 24` is forced (proven by a real manifest-merge failure) with the `overrideLibrary` escape hatch blocked by `android.usesSdkInManifest.disallowed=true` (`gradle.properties:30`, verified).

The engineering answer is the second flavor dimension (`ml` = `mlNone` | `mlGemma`), `ndk { abiFilters 'arm64-v8a' }` on `mlGemma` only, `exclude group: 'com.google.android.datatransport'`, and the R8 keep rules from validate:android-risk risk 12 verbatim (neither AAR ships a `proguard.txt`; `android.r8.strictFullModeForKeepRules=true` at `gradle.properties:33` will strip the JNI callback types and the failure appears only in `assembleDev`'s release build, i.e. in CI). Consequences to absorb the same day: 8 variants, a duplicated `AiModule` FQN in two source sets, `ci.yml` `assembleDev` becomes ambiguous, and the stacked `applicationIdSuffix` `.dev` + `.ai` must be checked against the `authenticator.xml`/`@string/authority` sync-provider authority (`AndroidManifest.xml:193` uses `@string/authority`, not `${applicationId}`) — a duplicate ContentProvider authority makes the app uninstallable alongside the other flavor.

**But whether news-android-ai ships an arm64-only, non-F-Droid, Google-Maven-blob variant at all is a distribution decision, not an engineering one.** It has to be settled in a conversation before the first line is written.