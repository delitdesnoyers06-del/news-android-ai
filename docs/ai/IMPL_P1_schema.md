Phase 1 complete and verified.

## Files created

`News-Android-App/src/main/java/de/luhmer/owncloudnewsreader/database/ai/` (all Java, D27):

| File | Purpose |
|---|---|
| `AiSchema.java` | DDL for all 10 tables + 5 indexes, `createOrMigrate(SQLiteDatabase)` idempotent, versioned by `AI_META.K='schema_version'` (`AI_SCHEMA_VERSION = 1`). Catches `Throwable` → sets `isReady()=false` instead of throwing. Exposes `T_*` table-name constants and `ALL_TABLES`. |
| `AiKeys.java` | `of(RssItem)` / `of(String fingerprint, long id)` → fingerprint, else `"id:"+id`; `isSynthetic()`. Guards empty **and** null. |
| `AiDb.java` | Holds the only `SQLiteDatabase` handle; exec/query/insert/update/delete/`inTransaction` primitives, the `AI_META` K/V/BLOB accessors, and `garbageCollect()`. |
| `AiScoreStore.java` | `enqueue`, `get`, `byStatus`, `byAiKey`, `writeResult` (**the fan-out UPDATE keyed on `AI_KEY`**), `setSimScore`, `setStatus`, `unjudged(syncId,limit)` resume selector. Status constants are machine-only. |
| `AiEmbeddingStore.java` | static `pack`/`unpack` float32 **little-endian**; `put/get/getRow/has/count/decided`; `assertCompatible(model,task)` wipes `AI_EMBEDDING`+`AI_CENTROID` and resets `AI_TASTE.IN_CENTROID=0`, never touching `AI_DECISION`/`AI_TASTE`. |
| `AiDecisionStore.java` | `decide()` = transition table + append `AI_DECISION` + fold `AI_TASTE` + `decision_count`; `targetOf(action,from)` is the pure table; `currentState`, `history`, `recent`, `tasteState/Count/Keys`, `markInCentroid`, `pendingCentroidKeys`. |
| `AiCentroidStore.java` | get/put/putUnit/invalidateUnits/memberCount/unitCacheKey/delete. Storage only — the ± math is Phase 4. |
| `AiRubricStore.java` | `append(..., makeActive)` deactivates others in the same tx; `active()`, `activate(id)` (revert), `history(limit)`. |
| `AiDigestStore.java` | `findOrCreate(dayKey,…)` via `INSERT OR IGNORE` on the `UNIQUE DAY_KEY`; `setAbstract`, `dismiss`, `putItem/items/clearItems`, `pruneOlderThan`. |
| `AiModelRegistry.java` | `register`, `get/all/byState`, `pathOf` (installed only), `setState` (**nulls `PATH` whenever state leaves `installed`** — R8), `setProgress`, `setCaps`. |

## Files edited

- `database/DatabaseHelperOrm.java` — `AiSchema.createOrMigrate(db)` inserted between `helper.getWritableDatabase()` (line 40) and `new DaoMaster(db)`. Line numbers in the plan were accurate.
- `database/DatabaseConnectionOrm.java` — `aiGarbageCollect()` added before `getLastModified()`; guards on `AiSchema.isReady()`, catches `Throwable` (a GC failure must not fail a sync). Imports added in alphabetical position.
- `reader/nextcloud/RssItemObservable.java` — `mDbConn.aiGarbageCollect();` at line 114, immediately after `clearDatabaseOverSize()` (line 112).

## Tests — `src/test/java/de/luhmer/owncloudnewsreader/database/ai/`

`AiDbTestBase.java` (shared in-memory `SQLiteDatabase.create(null)` fixture), `AiSchemaTest`, `AiGcTest`, `AiDecisionStateMachineTest`, `AiKeysTest`, `AiEmbeddingStoreTest`, plus `AiScoreStoreTest` (not requested — added because the fan-out UPDATE ships in this phase and `AiFolderSqlTest` in Phase 2 depends on it holding).

`AiSchemaTest` does the real D1 thing: seeds one row in every AI table through the real stores, runs `DaoMaster.dropAllTables(db,true)` + `createAllTables(db,true)`, then asserts row counts **and values** (the packed vector, the taste state, the decision history, an `AI_META` value). Also asserts idempotency across three `createOrMigrate` calls and survival of DAO-level `DELETE FROM` (the `resetDatabase()` shape).

## Commands run — real outcomes

```
./gradlew :News-Android-App:compileOssMlGemmaDebugJavaWithJavac   -> BUILD SUCCESSFUL in 49s
./gradlew :News-Android-App:testOssMlGemmaDebugUnitTest --tests "*Ai*"  -> BUILD SUCCESSFUL in 25s
./gradlew :News-Android-App:assembleOssMlNoneDebug :News-Android-App:testOssMlNoneDebugUnitTest \
          :News-Android-App:testOssMlGemmaDebugUnitTest detekt spotlessCheck :News-Android-App:lint
   -> BUILD SUCCESSFUL in 2m 13s   (125 tasks)
```

Test counts, both variants identical (the AI layer is in `src/main`, so `mlNone` runs it too):

```
AiDecisionStateMachineTest  tests=15 failures=0 errors=0
AiEmbeddingStoreTest        tests=17 failures=0 errors=0
AiGcTest                    tests= 4 failures=0 errors=0
AiKeysTest                  tests= 7 failures=0 errors=0
AiSchemaTest                tests= 5 failures=0 errors=0
AiScoreStoreTest            tests= 8 failures=0 errors=0
(pre-existing: RssItemToHtmlTaskTest 3, ImageHandlerTest 8, TtsTextSplitterTest 8 — all still green)
```

`News-Android-App-oss-mlNone-debug.apk` = 13,495,227 B (Phase 0 recorded `devMlNone` at 13,444,099; the delta is the `dev`/`oss` flavor, not this change).

## Deviations from the plan — read these

1. **`AiDb` is "the only place that holds a `SQLiteDatabase`", not "the only place a SQL string is typed."** The stores compose their own table-specific statements and hand them to `AiDb`'s exec/query primitives; `AiDb` owns the plumbing, `AI_META` and the cross-table GC. Putting every column list of ten tables inside `AiDb` would have made it a 600-line god object duplicating each store. The choke-point property the plan wanted (nothing outside `database/ai/` touches AI SQL; nothing outside `AiDb` touches a DB handle) does hold.

2. **No SQLite UPSERT anywhere.** `ON CONFLICT … DO UPDATE` needs SQLite 3.24; minSdk 24 (Android 7.0) ships **3.9**. `enqueue`, `findOrCreate` and `register` use `INSERT OR IGNORE` (+ a follow-up `UPDATE` where needed). If you write new AI SQL, stay inside SQLite 3.9 — this will not show up in a Robolectric test, which uses a modern SQLite.

3. **`AI_SCORE.STATUS='selected'` is normalised to `queued` in the transition table.** PLAN D28 / doc 06 §3.6 lists exactly four rows and `selected` appears in none of them. Taken literally, swiping in the For You folder — where *every* row is `STATUS='selected'` — would be a silent no-op and the entire Phase 3 signal would be dead. `AiDecisionStore.targetOf` maps `selected → queued` before matching; the table itself is implemented verbatim. `FROM_STATE` still records the *observed* state (so an `AI_DECISION` row can literally say `selected`). Covered by `selectedNormalisesToQueued`. **If Phase 3 disagrees, that is the line to change.**

4. **DDL taken from doc `06_dev_data-pipeline.md` §1.2, not the condensed PLAN §4.2**, where they differ. §4.2 is not executable SQL (`AI_RUBRIC (_id PK AUTOINCREMENT, …)`). Concrete differences: `AI_DIGEST.RANGE_FROM/RANGE_TO/ITEM_COUNT` and `AI_MODEL.UPDATED_AT` are `NOT NULL` (§4.2 leaves them nullable); `AI_CENTROID.MODEL/TASK/DIM` are `NOT NULL`. Everything else is identical.

5. **`AI_TASTE.IN_CENTROID` is reset to 0 on every state change** (including kept→rejected), so `AiCentroidStore.backfill()` re-folds it into the right class. Conservative choice; Phase 4's incremental `onDecision()` may `markInCentroid(key,true)` right after and skip the backfill.

6. **`garbageCollect()` has a third statement** not in the plan: `DELETE FROM AI_DIGEST_ITEM WHERE DIGEST_ID NOT IN (SELECT _id FROM AI_DIGEST)`. Cheap, and `AI_DIGEST_ITEM` has no FK.

## Left undone / what Phase 2+ must know

- **The `DatabaseHelperOrm` hook has no automated test.** `getDaoSession` is a static double-checked singleton needing a Dagger-injected DB filename; `AiSchemaTest` proves `createOrMigrate` on a real DB, not that the singleton calls it. Verify on-device / in an instrumented test when one exists.
- `AiCentroidStore` is storage only — no `onDecision`/`unit`/`rebuild`/`backfill`. `AiEmbeddingStore.assertCompatible` exists and is tested, but nothing calls it yet.
- `AI_META` keys written so far: `schema_version`, `decision_count` (by `AiDecisionStore`), `centroid_unit_cache_key` (constant only). `embed_backlog_cursor`, `active_sync_id`, `feed_weight_map`, `interests_hash`, `digest_dismissed_day`, `model_load_ms` are unclaimed — use `AiDb.getMeta/putMeta/getMetaLong/putMetaLong/getMetaBlob/putMetaBlob/incrementMeta`.
- Nothing is wired into Dagger. `AiModule` is still empty in both flavor source sets, and `AppComponent`/`TestComponent` still have no `injectXxx` stubs (Phase 0 deliberately left them out). Stores are constructed as `new AiXStore(AiDb.of(daoSession.getDatabase()))`; `DatabaseConnectionOrm` is the only class today that can hand out that `SQLiteDatabase`.
- **Robolectric + real SQLite works** with `RobolectricTestRunner` + `sdk=34` and `SQLiteDatabase.create(null)`; `DaoMaster.createAllTables(db,true)` on the same handle gives you `RSS_ITEM` for join tests. That's the pattern `AiFolderSqlTest` should copy — see `AiGcTest.insertRssItem()` for the minimal NOT-NULL column set (`_id, FEED_ID, AUTHOR, GUID, GUID_HASH, FINGERPRINT`).
- `AiScoreStore.writeResult(Row)` takes `row.aiKey` as the *selector* — a `Row` with only `rssItemId` set will update nothing. Phase 6 must set `aiKey`.