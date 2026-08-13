Read the brief, then verified against the actual repo and against the real published AARs (downloaded from Google Maven and unzipped). Two of the brief's claims turned out to be wrong; both are called out below.

---

# RISK REGISTER — news-android-ai + LiteRT-LM feasibility red-team

Hard evidence gathered outside the repo (kept in `/tmp/claude-1000/-home-yohann-dev-padam/6f87a778-8192-4b03-9f72-7956a00ea38c/scratchpad/`):

| artifact | version | minSdk (AAR manifest) | native ABIs (uncompressed / deflated) | consumer proguard |
|---|---|---|---|---|
| `com.google.ai.edge.litertlm:litertlm-android` | **0.15.0** | **24** | **arm64-v8a 21.20 MB / 9.23 MB; x86_64 25.22 MB / 10.18 MB. NO armeabi-v7a, NO x86.** | **none** |
| `com.google.mediapipe:tasks-text` | **1.0.0** | **24** | arm64 14.44/6.67, v7a 9.83/5.51, x86 20.19/8.08, x86_64 17.89/7.49 | none |
| `com.google.mediapipe:tasks-core` | **1.0.0** | 24 | arm64 11.02/4.93, v7a 7.72/4.17, x86 15.62/6.18, x86_64 13.68/5.69 | none |

`litertlm-android-0.15.0.pom` already declares `kotlinx-coroutines-android:1.9.0` + `kotlin-reflect:2.2.21` + `gson:2.13.2` at compile scope. `tasks-core-1.0.0.pom` declares `guava:27.0.1-android`, `protobuf-javalite:4.26.1`, `flogger:0.6`, and **`com.google.android.datatransport:transport-backend-cct:3.1.0` + `transport-runtime:3.1.0`**.

---

## 1. `DaoMaster.dropAllTables()` drops only 4 tables by name — CONFIRMED, and it is the escape hatch
**SEVERITY: (resolved — this is the recommendation, not a risk)**

**EVIDENCE.**
- `database/model/DaoMaster.java:17` `SCHEMA_VERSION = 10`; `:55-58` `DevOpenHelper.onUpgrade` → `dropAllTables(db, true); onCreate(db);`.
- `database/model/DaoMaster.java:28-33` — `dropAllTables` drops **exactly four hard-coded tables**: `FolderDao`, `FeedDao`, `RssItemDao`, `CurrentRssItemViewDao`. Any other table in the file is untouched.
- `database/DatabaseHelperOrm.java:38` — `new DaoMaster.DevOpenHelper(context, DATABASE_NAME_ORM, null)`, with the comment at `:37` confirming intent.
- `database/DatabaseConnectionOrm.java:78-83` `resetDatabase()` likewise only calls `deleteAll()` on those four DAOs.
- **`RSS_ITEM._id` is the server-assigned Nextcloud item id**, not a local autoincrement: `reader/nextcloud/InsertRssItemIntoDatabase.java:71` → `rssItem.setId(e.get("id").getAsLong())`. So a foreign key `AI_SCORE.RSS_ITEM_ID → RSS_ITEM._id` **survives both the DevOpenHelper wipe and `resetDatabase()`**, and re-attaches itself on the next sync.
- `de.greenrobot.dao.AbstractDaoSession` (verified by `javap` on `greendao-2.1.0.jar`): `private final android.database.sqlite.SQLiteDatabase db;` → `daoSession.getDatabase()` is a real `SQLiteDatabase`, so raw `execSQL`/`rawQuery` are available (already used at `DatabaseConnectionOrm.java:306, 399, 523, 673, 715`).

### RECOMMENDATION: option (b). Same DB file, own tables via raw `execSQL`. Do NOT use ATTACH.

Create once, right after `getWritableDatabase()` in `DatabaseHelperOrm.java:40`:

```java
// DatabaseHelperOrm.java, insert between line 40 and 42
SQLiteDatabase db = helper.getWritableDatabase();
AiSchema.createIfMissing(db);            // NEW — idempotent, CREATE TABLE IF NOT EXISTS
DaoMaster daoMaster = new DaoMaster(db);
```

```java
// database/ai/AiSchema.java  (new, plain SQLiteDatabase, no greenDAO involvement)
static void createIfMissing(SQLiteDatabase db) {
    db.execSQL("CREATE TABLE IF NOT EXISTS AI_SCORE ("
        + "RSS_ITEM_ID INTEGER PRIMARY KEY NOT NULL,"   // == RSS_ITEM._id == Nextcloud id
        + "FINGERPRINT TEXT,"
        + "STATUS TEXT NOT NULL DEFAULT 'queued',"      // queued|kept|rejected|discarded
        + "LLM_SCORE INTEGER,"                          // NULL = never reached the LLM (load-bearing)
        + "EFF_SCORE INTEGER,"
        + "SIM_SCORE REAL,"                             // NULL = never embedded; 0.0 = cold start
        + "RANK_SCORE REAL,"
        + "THEMES TEXT, FLAGS TEXT, WHY TEXT,"
        + "DISCARD_REASON TEXT, SCORED_AT INTEGER, DECIDED_AT INTEGER)");
    db.execSQL("CREATE INDEX IF NOT EXISTS IDX_AI_SCORE_RANK ON AI_SCORE (RANK_SCORE DESC)");
    db.execSQL("CREATE INDEX IF NOT EXISTS IDX_AI_SCORE_STATUS ON AI_SCORE (STATUS)");
    db.execSQL("CREATE TABLE IF NOT EXISTS AI_EMBEDDING ("
        + "RSS_ITEM_ID INTEGER PRIMARY KEY NOT NULL, DIM INTEGER NOT NULL, VEC BLOB NOT NULL)");
    db.execSQL("CREATE TABLE IF NOT EXISTS AI_DECISION ("          // append-only
        + "_id INTEGER PRIMARY KEY AUTOINCREMENT, RSS_ITEM_ID INTEGER NOT NULL,"
        + "ACTION TEXT NOT NULL, LLM_SCORE_AT INTEGER, NOTE TEXT, CREATED_AT INTEGER NOT NULL)");
    db.execSQL("CREATE TABLE IF NOT EXISTS AI_META (K TEXT PRIMARY KEY NOT NULL, V TEXT)");  // schema ver, rubric, centroid cache key
}
```

Because the tables are ours, we own their versioning via `AI_META.K='schema_version'` — we get real migrations while greenDAO keeps its destructive ones.

The list query then stays one string and needs **no change to the pipeline shape**. New branch inside `DatabaseConnectionOrm.getAllItemsIdsForFolderSQL()` (`:607-635`):

```java
else if (ID_FOLDER == AI_DIGEST.getValue()) {
    buildSQL = "SELECT " + RssItemDao.TABLENAME + "." + RssItemDao.Properties.Id.columnName
        + " FROM " + RssItemDao.TABLENAME
        + " JOIN AI_SCORE ON AI_SCORE.RSS_ITEM_ID = "
        + RssItemDao.TABLENAME + "." + RssItemDao.Properties.Id.columnName
        + " WHERE AI_SCORE.STATUS = 'kept'"
        + (onlyUnread ? " AND " + RssItemDao.TABLENAME + "." + RssItemDao.Properties.Read_temp.columnName + " != 1" : "")
        + " ORDER BY AI_SCORE.RANK_SCORE DESC, "
        + RssItemDao.TABLENAME + "." + RssItemDao.Properties.PubDate.columnName + " DESC";
    return buildSQL;      // early-return: skip the shared ORDER BY append at :633
}
```

Note the table is **not aliased** — see risk 2 for why that is mandatory.

**Why not (a) ATTACH.** Three concrete blockers, not aesthetics:
1. `insertIntoRssCurrentViewTable` wraps its `execSQL` in `daoSession.runInTx(...)` (`DatabaseConnectionOrm.java:669-675`). SQLite forbids `ATTACH` inside a transaction, so the ATTACH must be hoisted to session creation anyway — at which point you have all of (b)'s plumbing and none of its safety.
2. `DatabaseHelperOrm.daoSession` is a **process-local** lazy singleton (`:31-48`), and this app is genuinely multi-process — `AndroidManifest.xml:212` declares `android:process=":downloadWebPageProcess"` and `:290` declares `:remote`. The ATTACH would have to be re-issued per process, and two processes holding two `SQLiteDatabase` connection pools over the same pair of files is exactly the configuration that produces `SQLITE_BUSY`/`database is locked` under load.
3. On AOSP, `SQLiteDatabase.executeSql()` special-cases `STATEMENT_ATTACH` and calls `disableWriteAheadLogging()` the first time it sees one — you permanently lose WAL on the main article DB. *(I could not cite this from a file in either repo; verify with a one-line instrumented test before relying on it. It does not change the recommendation: 1 and 2 alone are sufficient.)*

**Why not (c) as the primary path.** It moves "which rows are in the list" out of SQL into Java for this one folder, so `onlyUnread`, sort direction, the search variants (`getAllItemsIdsForFolderSQLSearch():641`) and fingerprint dedup all need a second Java implementation. Keep (c) as a **narrow escape hatch only**, in the form of a new overload — not as the architecture:

```java
// DatabaseConnectionOrm — add next to insertIntoRssCurrentViewTable(String) at :660
public void insertIntoRssCurrentViewTable(List<Long> orderedRssItemIds) {
    daoSession.runInTx(() -> {
        daoSession.getCurrentRssItemViewDao().deleteAll();
        List<CurrentRssItemView> rows = new ArrayList<>(orderedRssItemIds.size());
        for (int i = 0; i < orderedRssItemIds.size(); i++)
            rows.add(new CurrentRssItemView((long) (i + 1), orderedRssItemIds.get(i)));  // _id must be 1..N contiguous
        daoSession.getCurrentRssItemViewDao().insertInTx(rows);
    });
}
```
This is correct only because `CURRENT_RSS_ITEM_VIEW._id` is `INTEGER PRIMARY KEY NOT NULL` — i.e. a rowid alias (`database/model/CurrentRssItemViewDao.java:38-40`) — and `getCurrentRssItemView(page)` pages on `C._id > page*25 AND C._id <= (page+1)*25 ORDER BY C._id` (`DatabaseConnectionOrm.java:486-494`). Contiguity from 1 is a hard precondition; the existing `INSERT…SELECT` path gets it for free from rowid assignment after `deleteAll()`.

---

## 2. The `" GROUP BY FINGERPRINT "` string surgery assumes a single-table query and will silently corrupt AI ordering
**SEVERITY: high** *(this is the specific trap inside recommendation 1 — the reason the table must not be aliased)*

**EVIDENCE.** `NewsReaderDetailFragment.java:539-545` (inside `UpdateCurrentRssViewTask.doInBackground`):
```java
int index = sqlSelectStatement.indexOf("ORDER BY");
if (index == -1) index = sqlSelectStatement.length();
sqlSelectStatement = new StringBuilder(sqlSelectStatement)
        .insert(index, " GROUP BY " + RssItemDao.Properties.Fingerprint.columnName + " ").toString();
dbConn.insertIntoRssCurrentViewTable(sqlSelectStatement);
```
It injects a **bare, unqualified** `GROUP BY FINGERPRINT` in front of the first literal `ORDER BY`.

**FAILURE SCENARIO.** If the AI branch aliases the tables (`FROM RSS_ITEM r JOIN AI_SCORE a`), `FINGERPRINT` still resolves (only `r` has it) — but `SELECT r._id … GROUP BY FINGERPRINT ORDER BY a.RANK_SCORE DESC` makes SQLite pick an **arbitrary** row per fingerprint group, so both the emitted `_id` and the `RANK_SCORE` used for ordering come from an arbitrary group member. Cross-feed duplicates therefore land at an arbitrary rank. Worse: if any future AI SQL contains the substring `ORDER BY` inside a subquery, `indexOf` finds the wrong one and produces a syntax error at runtime, inside a background `AsyncTask` with no error path.

**MITIGATION.** (a) Never alias in the AI branch — qualify with the bare table names as shown above, so `GROUP BY FINGERPRINT` remains valid. (b) Add `AI_SCORE.FINGERPRINT` (denormalised copy) and score **per fingerprint**, so the group is rank-homogeneous and the arbitrary pick is harmless. (c) Cover it with `AiFolderSqlTest` (risk 18) asserting the exact generated string and the resulting `getCurrentRssItemView(0)` order.

---

## 3. minSdk 21 → 24 is forced, and the usual escape hatch is disabled by a gradle.properties flag
**SEVERITY: blocker (build-breaking on day 1)**

**EVIDENCE.** `gradle.properties:20` `ANDROID_BUILD_MIN_SDK_VERSION=21`, consumed at `News-Android-App/build.gradle:13`. Both AARs declare `<uses-sdk android:minSdkVersion="24"/>` (verified in the AAR manifests). Manifest merger fails hard: *"uses-sdk:minSdkVersion 21 cannot be smaller than version 24 declared in library"*. The standard workaround is `<uses-sdk tools:overrideLibrary="com.google.ai.edge.litertlm"/>` in `AndroidManifest.xml` — but **`gradle.properties:30` sets `android.usesSdkInManifest.disallowed=true`**, which rejects a `<uses-sdk>` element in the source manifest. So the override is unavailable without also flipping that flag.

**MITIGATION.** Bump `ANDROID_BUILD_MIN_SDK_VERSION` to **24** globally. Do not use `overrideLibrary` — it would only defer a guaranteed runtime `UnsatisfiedLinkError`. Nothing in the codebase breaks: the API-level guards are all *above* 24 or trivially dead afterwards — `Build.VERSION_CODES` usages across `src/main` are N×8, M×8, O×6, S×3, TIRAMISU×1, Q×1, P×1, KITKAT×1, with **zero `LOLLIPOP` and zero `JELLY_BEAN` references**. Only two things become dead code and can be left alone: `NewsReaderListActivity.java:1060` (`>= M` guard) and `AndroidManifest.xml:14-16` (`AUTHENTICATE_ACCOUNTS` with `maxSdkVersion="22"`, which simply stops applying). Core library desugaring (`build.gradle:41`, `desugar_jdk_libs:2.1.5`) is unaffected by the bump. `useLibrary 'android.test.runner'` (`:91-93`) is unaffected.

---

## 4. LiteRT-LM ships NO armeabi-v7a and NO x86 — the AI feature is arm64-only, and a 32-bit device gets a crash, not a graceful degrade
**SEVERITY: blocker (silent runtime crash for a real device population)**

**EVIDENCE.** `unzip -l litertlm-android-0.15.0.aar` → exactly two `.so`: `jni/arm64-v8a/liblitertlm_jni.so`, `jni/x86_64/liblitertlm_jni.so`. Meanwhile `tasks-text`/`tasks-core` **do** ship all four ABIs, so the two libraries disagree about which devices they support. `News-Android-App/build.gradle` has **no `ndk { abiFilters }`, no `splits`, no `packagingOptions.jniLibs`** anywhere (verified over the whole file, lines 1-243).

**FAILURE SCENARIO.** A universal APK (which is what F-Droid and the CI `assembleDev` artifact produce) installs fine on an armeabi-v7a-only device. MediaPipe's v7a lib is present, so embedding works; then `NativeLibraryLoader.load()` → `LiteRtLmJni.nativeCheckLoaded()` throws `UnsatisfiedLinkError` at first scoring call, inside a background service, and the user just never sees an AI digest.

**MITIGATION.** Treat "arm64-v8a + API 24" as the hard device gate. Detect at runtime before offering the feature: `Build.SUPPORTED_64_BIT_ABIS` contains `arm64-v8a`. Gate the Settings entry and the drawer folder on it. Set `ndk { abiFilters 'arm64-v8a' }` in the AI variant (see risk 5) so the x86_64 lib does not ship to phones, and add a separate emulator-only variant if the team wants to run it on x86_64 emulators.

---

## 5. APK size: +47 MB uncompressed for arm64-only, ~157 MB for a universal APK — and native libs are stored *uncompressed* at minSdk ≥ 23
**SEVERITY: high**

**EVIDENCE.** Sums from the table above. arm64-v8a only: 21.20 (litertlm) + 14.44 (tasks-text) + 11.02 (tasks-core) = **46.66 MB of `.so`**. All four ABIs: 46.42 + 62.35 + 48.04 = **156.8 MB**. AGP sets `android:extractNativeLibs="false"` by default once minSdk ≥ 23, so these are the *on-disk APK* numbers, not the deflated ones. There is currently no `splits`/`abiFilters` block in `News-Android-App/build.gradle`, and CI publishes a single universal APK (`.github/workflows/ci.yml:52-58`, `assembleDev` → one `News-Android-App-dev-debug.apk`).

**MITIGATION.**
```groovy
// News-Android-App/build.gradle, inside android { } — AI variant only
defaultConfig { ndk { abiFilters 'arm64-v8a' } }       // in the ml_gemma flavor block
splits { abi { enable true; reset(); include 'arm64-v8a'; universalApk false } }
```
Ship the AI build as an arm64-only APK (~47 MB of libs + current app). Keep the default flavor free of both AARs entirely so the F-Droid/oss APK is byte-identical to today. Model weights (2.58 GB for `gemma-4-E2B`, 3.65 GB for E4B) are **never** in the APK — downloaded to `getExternalFilesDir()` per the Gallery pattern.

---

## 6. MediaPipe drags in Google Clearcut telemetry (`transport-backend-cct`) into an app that is on F-Droid and whose PRIVACY.md promises no data collection
**SEVERITY: blocker (policy/licensing, not technical)**

**EVIDENCE.** `tasks-core-1.0.0.pom` compile-scope deps include `com.google.android.datatransport:transport-backend-cct:3.1.0` and `transport-runtime:3.1.0` — the Firebase/Clearcut upload transport. Against:
- `News-Android-App/build.gradle:66-69` — the flavor is literally commented `// 100% Open-Source Edition`, named `oss`.
- `README.md:16, 22-24` — F-Droid badges; the app is published at `f-droid.org/packages/de.luhmer.owncloudnewsreader`.
- `PRIVACY.md:3` — *"does not collect or send any data from you or your device to a server of the developers"*.
- F-Droid additionally will not build prebuilt `.so` blobs from Maven; `tasks-*` and `litertlm-android` are both binary-only (no `proguard.txt`, no sources).

**MITIGATION.** Two decisions, both required:
1. **Never put these deps in the `oss` flavor.** Use a second flavor dimension (risk 7) so the F-Droid artifact never resolves `com.google.mediapipe` or `com.google.ai.edge.litertlm`.
2. If MediaPipe must be used, add `exclude group: 'com.google.android.datatransport'` on the `tasks-text` dependency and verify at runtime that nothing calls into it (MediaPipe's logging path is lazily initialised; excluding it risks a `NoClassDefFoundError` — test it, do not assume). **Preferred alternative: drop MediaPipe entirely** and get embeddings from LiteRT-LM's `Session` prefill path or a bare LiteRT `.tflite` interpreter, avoiding 25 MB of arm64 `.so`, guava 27, protobuf-javalite, flogger and the telemetry transport in one move. That needs prototyping — LiteRT-LM has no documented embedding API (verified: no `Embed*` class in `litertlm-android-0.15.0`'s `classes.jar`; the 44 public classes are Engine/Conversation/Session/Tool/Config types only).

---

## 7. Dynamic feature modules are unusable here; a second flavor dimension is the only viable isolation, and it doubles the variant matrix
**SEVERITY: high**

**EVIDENCE.** Dynamic feature modules require Play App Bundle delivery. This app ships APKs to F-Droid (`README.md:16`) and as a CI artifact (`ci.yml:52-58`); `settings.gradle:7` includes exactly one module. So dynamic delivery is off the table.
`build.gradle:63` `flavorDimensions = ["default"]` with `oss`/`dev` (`:65-75`). `src/dev/` contains **only resources** — `src/dev/res/drawable/ic_launcher_foreground.xml` and `src/dev/res/values/strings.xml` — so there is no precedent in this repo for a flavor carrying Java/Kotlin source.

**MITIGATION.** Add a second dimension:
```groovy
flavorDimensions = ["default", "ml"]
productFlavors {
    oss { dimension "default" }
    dev { dimension "default"; applicationIdSuffix ".dev" }
    mlNone  { dimension "ml" }                                        // no AI deps at all
    mlGemma { dimension "ml"; applicationIdSuffix ".ai"; ndk { abiFilters 'arm64-v8a' } }
}
dependencies {
    mlGemmaImplementation "com.google.ai.edge.litertlm:litertlm-android:0.15.0"
    mlGemmaImplementation "com.google.mediapipe:tasks-text:1.0.0"
}
```
Declare the AI surface as an interface in `src/main` (`ai/AiTriage.java` + a `AiTriage.Factory`), with a no-op impl in `src/mlNone/java` and the real one in `src/mlGemma/java`. **Consequences that must be handled the same day:** variants go 2×2×2 = **8**; `ci.yml:53` `assembleDev` becomes ambiguous → change to `assembleDevMlGemma` (+ `assembleDevMlNone`); `ci.yml:39` `./gradlew test` doubles to 8 unit-test tasks; the `dev` flavor's `applicationIdSuffix ".dev"` composes with `".ai"` giving `de.luhmer.owncloudnewsreader.dev.ai`, which must not collide with the `authenticator.xml` / `@string/authority` sync-provider authority (`AndroidManifest.xml:193` uses `@string/authority`, not `${applicationId}`) — **check this, a duplicate ContentProvider authority makes the app uninstallable alongside the other flavor**.

---

## 8. The brief is wrong about `DownloadWebPageService`: it is not a `JobIntentService`, and it is already broken on targetSdk 34+
**SEVERITY: high (invalidates the proposed template)**

**EVIDENCE.**
- `services/DownloadWebPageService.java:48` — `public class DownloadWebPageService extends Service`. The only `JobIntentService` subclasses in the repo are `services/SyncItemStateService.java:42` and `services/DownloadImagesService.java:51`.
- `DownloadWebPageService.java:84` — `startForeground(NOTIFICATION_ID, mNotificationWebPages.build());`
- `AndroidManifest.xml:209-212` — the `<service>` declaration has **no `android:foregroundServiceType`**. The only service in the manifest that has one is `PodcastPlaybackService` (`:261`, `mediaPlayback`). Declared permissions are `FOREGROUND_SERVICE` (`:18`) and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` (`:19`) only.
- `gradle.properties:21` `targetSdk=35`.
- Started via `NewsReaderListActivity.java:1077` `startForegroundService(...)`.

**FAILURE SCENARIO.** On Android 14+ (targetSdk ≥ 34), `startForeground()` from a service with no manifest `foregroundServiceType` throws `MissingForegroundServiceTypeException`. "Download articles for offline reading" is therefore already crashing on modern devices. Also note `NewsReaderListActivity.java:1062, 1067` calls `checkSelfPermission`/`requestPermissions` on `FOREGROUND_SERVICE`, which is a **normal** permission — that check always passes and the request is a no-op; it is cargo cult, not a gate.

**MITIGATION.** Do not copy this class. Fix it separately (add `android:foregroundServiceType="dataSync"` + `FOREGROUND_SERVICE_DATA_SYNC`), and build the AI job on WorkManager — risk 9.

---

## 9. Background execution: `JobIntentService` is dead-ended; add WorkManager
**SEVERITY: high**

**EVIDENCE.** `JobIntentService` is deprecated; on API 31+ `enqueueWork` routes to `JobScheduler`, whose execution window is ~10 minutes. Scoring 60 articles with `sendMessage` on a 1B–4B model at phone token rates will exceed that. `androidx.work` appears **nowhere** in `News-Android-App/build.gradle:124-243`. The natural hook `OwnCloudSyncAdapter.onPerformSync()` (`:67-94`) is synchronous and sets a `syncRunning` flag at `:74`/`:89` that the UI observes via `SyncStartedEvent`/`SyncFinishedEvent` — doing minutes of inference inline would leave the UI showing "syncing" for the whole run and block subsequent periodic syncs scheduled by `SettingsFragment.setAccountSyncInterval()`.

**RECOMMENDATION: WorkManager `androidx.work:work-runtime:2.10.x`** (the Java `Worker`/`ListenableWorker` API — **not** `-ktx`, so no coroutine requirement). Enqueue from `OwnCloudSyncAdapter.onPerformSync()` after `startFaviconDownload()` (`:85`) as unique work with `ExistingWorkPolicy.KEEP`; never inline.

Manifest / permission implications:
```xml
<!-- next to AndroidManifest.xml:18-19 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```
- WorkManager 2.9+ declares its own `androidx.work.impl.foreground.SystemForegroundService` with `foregroundServiceType="dataSync|..."`; you must still declare the *permission* in the app manifest or `startForeground` throws `SecurityException` on API 34+.
- Call `setForegroundAsync(new ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))` — the 3-arg constructor is mandatory on API 34+.
- Constraints: `setRequiresCharging(true)` + `NetworkType.NOT_REQUIRED` (inference is offline; only model download needs `UNMETERED`) + `setRequiresBatteryNotLow(true)`.
- **API 34+ imposes a cumulative ~6h/24h quota on `dataSync` foreground services.** A nightly 5-minute triage is far inside it; a "score everything on demand" button is not — cap the per-run item count with the `prefilter_top_k` lever (that is exactly what it is for) and persist progress in `AI_SCORE` so a killed run resumes.
- Reuse `NextcloudNotificationManager` (`notification/NextcloudNotificationManager.java:116` is the existing builder pattern) and add a channel; do **not** reuse `CHANNEL_ID = "Download Web Page Service"`.

---

## 10. Kotlin/coroutines: this is a **non-issue**, contrary to the brief — but the Kotlin version constraint is a real one
**SEVERITY: medium**

**EVIDENCE.**
- `gradle.properties:35-36` `android.builtInKotlin=true`, `android.newDsl=true`; `News-Android-App/build.gradle:1-6` applies `com.android.application`, `ksp`, `detekt`, `spotless` — **no `org.jetbrains.kotlin.android`**. 31 `.kt` files compile today under built-in Kotlin, so `.kt` sources work as-is.
- **`kotlinx-coroutines-android:1.9.0` arrives transitively** from `litertlm-android-0.15.0.pom` at compile scope. Nothing needs adding.
- **The Flow API is optional.** `javap` on `Conversation.class` shows three *blocking* Java-callable overloads `Message sendMessage(String, …)` / `(Message, …)` / `(Contents, …)` with the full `$default` ladder, plus callback-style `void sendMessageAsync(String, MessageCallback, …)`. Only the `Flow<Message>` overloads need coroutines. **Recommendation: call blocking `sendMessage(String)` from a plain Java worker thread inside the WorkManager `Worker`.** No new Kotlin, no coroutine scopes, no Java/Kotlin interop surface at all.
- `Engine` and `Conversation` both `implements java.lang.AutoCloseable` (confirmed by `javap`), so Java try-with-resources works directly.

**THE ACTUAL RISK.** `litertlm-android` pulls `org.jetbrains.kotlin:kotlin-reflect:2.2.21` while the project pins `kotlin_version = '2.2.10'` (`build.gradle:5`, `:14`), **and `gradle.properties:32` sets `android.dependency.useConstraints=true`**, which makes AGP publish strict-ish constraints aligning `kotlin-stdlib` to the plugin's Kotlin version. Resolution can fail with a constraint-rejection error, or succeed and pull `kotlin-stdlib:2.2.21` under a 2.2.10 compiler.

**MITIGATION.** Bump `kotlin_version` to `2.2.21` in `build.gradle:5` so plugin, stdlib and `kotlin-reflect` agree. If AGP 9.1.0's built-in Kotlin pins a different version, add an explicit `implementation platform(...)`/`constraints { implementation('org.jetbrains.kotlin:kotlin-reflect:2.2.10') }`. **This is the single fastest thing to de-risk — run `./gradlew :News-Android-App:dependencies --configuration mlGemmaOssDebugRuntimeClasspath` on a spike branch before anything else is built.** I could not run Gradle here (no wrapper distribution cached), so treat the exact failure mode as unverified; the version skew itself is verified from the two POMs.

---

## 11. Dagger: enumeration, and a `@Singleton` engine is the wrong shape
**SEVERITY: high**

**EVIDENCE.** `di/AppComponent.java:29-56` — a single `@Singleton @Component(modules = { ApiModule.class })` with explicit `injectXxx` methods; there is no `@ContributesAndroidInjector`, no Hilt. `NewsReaderApplication.java:14-23` builds the component in `onCreate()` — which runs **in every process**, including `:downloadWebPageProcess` and `:remote`.

**What must be added to `di/AppComponent.java`:**
- Component modules line `:30` → `@Component(modules = { ApiModule.class, AiModule.class })`. `AiModule` lives in the `mlGemma` source set; `mlNone` needs a same-FQN `AiModule` providing no-op impls, otherwise `AppComponent` (in `src/main`) will not compile in the `mlNone` variant. **This is the main structural cost of the flavor split — plan for it.**
- `void injectService(AiTriageWorker.Deps deps);` (WorkManager instantiates `Worker` itself, so inject a holder or use a `WorkerFactory`).
- `void injectFragment(AiSettingsFragment fragment);` and `void injectFragment(ModelManagerFragment fragment);` — needed because nested preference screens require new fragments (`SettingsFragment.java:84` injects itself at `onCreatePreferences`; nested sub-screens are used nowhere today, so `SettingsActivity` must additionally implement `PreferenceFragmentCompat.OnPreferenceStartFragmentCallback`).
- `void injectReceiver(AiNotificationActionReceiver receiver);` if the notification gets a Stop action (mirrors `helper/NotificationActionReceiver`, `AndroidManifest.xml:224-230`).
- **Not needed:** `injectService(OwnCloudSyncAdapter)` already exists at `:49` — the enqueue hook needs no new method. Do **not** add an inject for `NewsListRecyclerAdapter`; it takes its dependencies through its constructor today, keep it that way.

**THE ENGINE RISK.** `Engine` holds multi-GB of mapped weights and is `AutoCloseable`. Dagger `@Singleton` has **no lifecycle** — nothing will ever call `close()`, so the memory is held until process death, and the LRU killer will take the app down mid-scroll. Also, because `NewsReaderApplication.onCreate` runs per process, a `@Provides @Singleton Engine` would be constructed **once per process** that touches it.

**MITIGATION.** Never provide `Engine` directly.
```java
@Provides @Singleton AiEngineManager provideEngineManager(Application app, SharedPreferences prefs);
```
`AiEngineManager` is a tiny refcounted holder: `acquire()` lazily does `new Engine(cfg); engine.initialize()` (documented up to ~10 s — must be off the main thread; `Engine.initialize()` is a plain blocking Java method per `javap`), `release()` decrements and closes after an idle timeout. Bind acquire/release to the `Worker`'s `doWork()` body, and call `close()` in `onStopped()`. Additionally: run the worker in a dedicated process (`android:process=":ai"` on WorkManager's service is not straightforward — simpler is a dedicated foreground `Service` in `:ai` that the worker binds to) so an OOM kill takes the model down, not the reader. That is what `:downloadWebPageProcess` (`AndroidManifest.xml:212`) already does for WebViews; the precedent exists.

---

## 12. R8 full mode + `strictFullModeForKeepRules` will break the JNI bridge — neither AAR ships consumer keep rules
**SEVERITY: blocker (release build only, so it will be found late)**

**EVIDENCE.**
- `gradle.properties:33` `android.r8.strictFullModeForKeepRules=true`; `build.gradle:54-60` release = `minifyEnabled true` + `shrinkResources true`.
- **Verified: none of the three AARs contains a `proguard.txt`** (`unzip -l | grep -i proguard` returns nothing for all three).
- `javap` on `LiteRtLmJni.class` shows 14 `native` methods whose signatures reference `SamplerConfig`, `InputData[]`, `ThinkingConfig`, and a `LiteRtLmJni$JniInferenceCallback` / `LiteRtLmJni$JniMessageCallback` that **native code calls back into**. `NativeLibraryLoader` also declares `native void nativeCheckLoaded()`.
- `Conversation` uses `kotlin-reflect` via `ReflectionTool`/`ToolManager`/`@Tool`/`@ToolParam`, and Gson (`com.google.gson.JsonObject` in `resolveResponseFormat`/`handleToolCalls`).
- `proguard-rules.pro:75` currently keeps only `-keepattributes SourceFile,LineNumberTable` — **no `Signature`, no `*Annotation*`**, which breaks Gson generics and any annotation-driven reflection.
- CI runs `assembleDev` (`ci.yml:53`) which builds `devRelease` → minified. So this fails in CI, not just on release day.

**MITIGATION — append to `News-Android-App/proguard-rules.pro`:**
```proguard
###############
# LiteRT-LM (no consumer rules shipped in the AAR)
-keep class com.google.ai.edge.litertlm.LiteRtLmJni { *; }
-keep class com.google.ai.edge.litertlm.LiteRtLmJni$* { *; }
-keep class com.google.ai.edge.litertlm.NativeLibraryLoader { *; }
-keepclasseswithmembernames,includedescriptorclasses class com.google.ai.edge.litertlm.** {
    native <methods>;
}
# types that cross the JNI boundary by field/ctor access from C++
-keep class com.google.ai.edge.litertlm.SamplerConfig { *; }
-keep class com.google.ai.edge.litertlm.InputData { *; }
-keep class com.google.ai.edge.litertlm.ThinkingConfig { *; }
-keep class com.google.ai.edge.litertlm.Message { *; }
-keep class com.google.ai.edge.litertlm.Content { *; }
-keep class com.google.ai.edge.litertlm.Content$* { *; }
-keep class com.google.ai.edge.litertlm.Contents { *; }
-keep class com.google.ai.edge.litertlm.Backend { *; }
-keep class com.google.ai.edge.litertlm.Backend$* { *; }
-keep class com.google.ai.edge.litertlm.ResponseFormat { *; }
-keep class com.google.ai.edge.litertlm.ResponseFormat$* { *; }
# strictFullModeForKeepRules: keeping the interface does NOT keep implementers.
# Our own callback impls must be kept explicitly:
-keep interface com.google.ai.edge.litertlm.MessageCallback { *; }
-keep interface com.google.ai.edge.litertlm.ResponseCallback { *; }
-keep class de.luhmer.owncloudnewsreader.ai.** implements com.google.ai.edge.litertlm.MessageCallback { *; }
# kotlin-reflect / @Tool metadata
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
-dontwarn kotlin.reflect.**

###############
# MediaPipe Tasks (no consumer rules shipped)
-keep class com.google.mediapipe.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class com.google.mediapipe.tasks.**.AutoValue_* { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.flogger.**
-dontwarn com.google.android.datatransport.**
-dontwarn com.google.errorprone.annotations.**

###############
# Our own Gson DTOs for the score/enrich JSON
-keep class de.luhmer.owncloudnewsreader.ai.dto.** { *; }
```
Note `-keepattributes` **appends** across rule files, so adding `Signature`/`*Annotation*` does not disturb line 75. Verify with `./gradlew :News-Android-App:assembleOssMlGemmaRelease` and inspect the emitted `out.map` (`proguard-rules.pro:74`).

---

## 13. `./gradlew lint` has `abortOnError true` — new error-severity checks will land
**SEVERITY: medium**

**EVIDENCE.** `build.gradle:94-99`: `abortOnError true`, `checkReleaseBuilds false`, `ignoreWarnings true`, with a small `disable` list. CI runs bare `./gradlew lint` (`ci.yml:25`). So only **error**-severity issues abort — but several relevant checks are errors:
- `ForegroundServiceType` / `InlinedApi` / `NewApi` if any API-34-only constant (`ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC`) is referenced without a version guard while minSdk is 24.
- `MissingPermission` if `FOREGROUND_SERVICE_DATA_SYNC` is not declared.
- `Instantiatable` for a `Worker` R8 cannot see.
- `ScopedStorage` on the existing `WRITE_EXTERNAL_STORAGE` (`AndroidManifest.xml:9`, no `maxSdkVersion`) is a warning today and stays one.

**MITIGATION.** Guard the FGS-type constant with `Build.VERSION.SDK_INT >= 34` (WorkManager's 3-arg `ForegroundInfo` is safe to pass on older APIs, but lint wants the guard on the constant). Do **not** widen the `disable` list — run `./gradlew lint` on the spike branch early. detekt (`maxIssues: 0`, `config/detekt/detekt.yml:2`) and spotless/ktlint (`build.gradle:77-82`, target `src/*/java/**/*.kt`) only touch Kotlin — **and the recommendation in risk 10 is to write the feature in Java**, so both gates are trivially satisfied. If any Kotlin is written, `detekt.yml:159` `TooGenericExceptionCaught` is *active* and forbids `catch (Exception e)` — which is exactly the shape a "never lose an article, degrade to score=null" parser wants. Name the variable `ignored`/`expected` to satisfy `allowedExceptionNameRegex: "^(_|(ignore|expected).*)"` (`detekt.yml:169`), or write the parser in Java.

---

## 14. `Backend.NPU(nativeLibraryDir)` conflicts with uncompressed native libs
**SEVERITY: medium (uncertain)**

**EVIDENCE.** `javap` confirms `Backend$NPU(java.lang.String nativeLibraryDir)`. At minSdk 24 AGP defaults `android:extractNativeLibs="false"`, so `.so` files are page-mapped out of the APK and `applicationInfo.nativeLibraryDir` may not contain real files that a vendor NPU delegate can `dlopen` by path.

**MITIGATION.** Ship `Backend.CPU` as the default and `Backend.GPU` as an opt-in. Only if NPU is pursued, set `android:extractNativeLibs="true"` on `<application>` (`AndroidManifest.xml:21-31`) — which re-inflates installed size by ~47 MB. **Resolve by measuring**: build with GPU, run on a device, check `logcat` for the loader path. GPU additionally needs, inside `<application>`:
```xml
<uses-native-library android:name="libvndksupport.so" android:required="false"/>
<uses-native-library android:name="libOpenCL.so" android:required="false"/>
```
(`<uses-native-library>` is API 31+; harmless on 24-30 as an unknown element.)

---

## 15. `clearDatabaseOverSize()` prunes RSS_ITEM rows and will orphan AI rows
**SEVERITY: medium**

**EVIDENCE.** `reader/nextcloud/RssItemObservable.java:112` calls `mDbConn.clearDatabaseOverSize()` at the top of every sync; the pruning SQL is around `DatabaseConnectionOrm.java:771-800`. Nothing will delete the matching `AI_SCORE` / `AI_EMBEDDING` rows.

**FAILURE SCENARIO.** `AI_EMBEDDING` grows unbounded (768 floats × 4 B = 3 KB per article, plus `AI_SCORE`); after a year of a busy reader that is tens of MB of dead rows, and the centroid computation silently includes embeddings of articles that no longer exist — which is arguably *correct* for taste learning but definitely not intended.

**MITIGATION.** Two separate policies, decided explicitly: `AI_SCORE`/`AI_EMBEDDING` for **undecided** articles get GC'd alongside the prune (a single `DELETE … WHERE RSS_ITEM_ID NOT IN (SELECT _id FROM RSS_ITEM) AND STATUS='queued'` after `clearDatabaseOverSize()`); `AI_DECISION` and the embeddings of `kept`/`rejected` articles are **kept forever** — they *are* the taste model, and veille's `decisions` table is explicitly append-only. Store the centroid itself in `AI_META` keyed by decision count so it survives even a full embedding GC.

---

## 16. `getCurrentRssItemView` paging is fragile w.r.t. any AI-side filtering after the fact
**SEVERITY: medium**

**EVIDENCE.** `DatabaseConnectionOrm.java:486-494` pages on `C._id > page*25 AND C._id <= (page+1)*25`, requiring contiguous `_id` from 1. `NewsReaderDetailFragment.java:553-558` shows the existing precedent for post-filtering in Java (`ALL_DOWNLOADED_PODCASTS` filters `items` after `getCurrentRssItemView(0)`) — and that precedent is **already buggy**: it filters only page 0, so pages 1+ are unfiltered, and the "less than 10 items" branch at `:569` sees a post-filter count.

**MITIGATION.** Do all AI filtering in the SQL (risk 1's JOIN), never in Java after `getCurrentRssItemView`. Do not copy the podcast pattern.

---

## 17. Model download UX: 2.58–3.65 GB, gated Gemma weights (HTTP 403), and no existing download infrastructure
**SEVERITY: high**

**EVIDENCE.** The repo has no download manager: `DownloadImagesService` (`JobIntentService`) and `PodcastDownloadService` are the only precedents, both for small files, neither with resume. HuggingFace `litert-community/gemma-*` weights are gated (403 until the user accepts terms in a browser). `EngineConfig.getCacheDir()` exists (verified by `javap`) and will hold a multi-hundred-MB compiled cache **in addition** to the weights.

**MITIGATION.** Copy the `google-ai-edge/gallery` shape: `model_allowlist.json` → `AllowedModel` → download to `getExternalFilesDir()/{name}/{version}/{file}`, `PARTIALLY_DOWNLOADED` resumed at app start, memory check before selection. Use the existing OkHttp 5 (`build.gradle:185`) with `Range:` headers rather than adding a download library. Handle 403 by opening a CustomTab (`androidx.browser` is already a dep, `build.gradle:145`) to the model card and re-checking on return. **Put `EngineConfig.cacheDir` on internal storage, not the same volume as the weights**, and never inside a directory that `NewsFileUtils` cleans. Recommend defaults: `Gemma3-1B-IT` for scoring (the high-volume stage, ~60 items/run), `gemma-4-E2B` only if the user opts in for enrich/digest; `embeddinggemma-300m` for embeddings — but note the **open question from the brief is still open**: whether `litert-community/embeddinggemma-300m` ships the `.task` bundle MediaPipe `TextEmbedder.createFromFile` requires. Resolve by downloading the repo file list before committing to MediaPipe at all (see risk 6's preferred alternative).

---

## 18. Testability: the SQL and the maths are JVM-testable; the runtime is not. Robolectric config is dead.
**SEVERITY: medium**

**EVIDENCE.** `src/test/resources/org.robolectric.Config.properties` contains only `emulateSdk=18` with the comment *"Robolectric doesn't know how to support SDK 19 yet."* — `emulateSdk` is a **Robolectric 2.x key**; under Robolectric 4.16.1 (`build.gradle:213`) it is inert and tests run at targetSdk. `junit_tests/TestDbTest.java` is wrapped in a `/* … */` from line 1, so there is no live DB test. Existing tests are pure-function (`ImageHandlerTest`, `TtsTextSplitterTest`, `RssItemToHtmlTaskTest.kt` — all three test static string helpers). `build.gradle:9` `unitTests.includeAndroidResources = true` and `:37` `returnDefaultValues = true` are both already set.

**The good news:** Robolectric 4.16's native runtime ships a real SQLite, so `SQLiteDatabase` works on the JVM. **The AI schema, the folder-SQL builder, the `GROUP BY` surgery, `insertIntoRssCurrentViewTable` and `getCurrentRssItemView` paging are all unit-testable without a device.** That covers the highest-risk code in this project (risks 1, 2, 16).

**Design rule that makes this possible:** two interfaces in `src/main`, implementations in `src/mlGemma`, fakes in `src/test`:
```java
public interface LlmClient  { String complete(String system, String user, int maxTokens); }
public interface Embedder   { float[] embed(String text); }
```

**Exact test file list:**
```
src/test/java/de/luhmer/owncloudnewsreader/ai/JsonExtractorTest.java         // ```json fences, depth-tracking balanced scan w/ strings+escapes, wrapper {"results"|"articles"|"items"|"scores"}
src/test/java/de/luhmer/owncloudnewsreader/ai/ScoreResponseParserTest.java   // align by id first / position second; missing object -> score=null NOT 0; extra object dropped; clamp [0,3]; flag vocabulary filter; one repair turn then degrade
src/test/java/de/luhmer/owncloudnewsreader/ai/CentroidTest.java              // unit-norm; cos; missing centroid contributes 0; SIM_FLOOR 0.05; cache key (decision_count)
src/test/java/de/luhmer/owncloudnewsreader/ai/PrefilterTest.java             // cold start = recency by (pubDate,fetchedAt) DESC, undated last; warm = sim DESC capped at top_k; discard reasons low_similarity / below_top_k
src/test/java/de/luhmer/owncloudnewsreader/ai/RankScoreTest.java             // WEIGHT_SCORE_BUMP clamp; rank = eff + 0.5*sim; llm_score NULL -> round(sim,3); the 0.5-never-crosses-a-tier invariant as a property test
src/test/java/de/luhmer/owncloudnewsreader/ai/DecisionStateMachineTest.java  // the 4-row transition table; invalid transition = idempotent no-op with NO AI_DECISION row; undo writes a row
src/test/java/de/luhmer/owncloudnewsreader/ai/PromptBuilderTest.java         // batch_json shape + summary[:1200]; few-shot fallback "(none yet — …)"; note/title sanitisation + 200-char cap
src/test/java/de/luhmer/owncloudnewsreader/ai/DigestGrouperTest.java         // country->theme->items; one appearance under themes[0]; theme blocks by -sum(rank_score); range on decided_at not pubDate
src/test/java/de/luhmer/owncloudnewsreader/database/AiSchemaTest.java        // @RunWith(RobolectricTestRunner) real SQLite: createIfMissing idempotent; DaoMaster.dropAllTables() leaves AI_* rows intact  <-- THE regression test for risk 1
src/test/java/de/luhmer/owncloudnewsreader/database/AiFolderSqlTest.java     // @RunWith(RobolectricTestRunner): getAllItemsIdsForFolderSQL(AI_DIGEST) + the injected " GROUP BY FINGERPRINT " + insertIntoRssCurrentViewTable + getCurrentRssItemView(0/1) exact order  <-- regression test for risks 2 & 16
src/androidTest/java/de/luhmer/owncloudnewsreader/ai/EngineSmokeTest.java    // device only: initialize() latency, one sendMessage round-trip, close(); @Ignore-if-no-model
```
Also fix `src/test/resources/org.robolectric.Config.properties` to `sdk=34` (explicit and supported) rather than leaving the dead `emulateSdk=18`.

**Gradle tasks.** Local: `./gradlew :News-Android-App:testOssMlGemmaDebugUnitTest`. CI `ci.yml:39` currently runs bare `./gradlew test`, which will fan out to 8 tasks after the flavor split — pin it to `testOssMlNoneDebugUnitTest testOssMlGemmaDebugUnitTest` to keep CI time sane. Device: `./gradlew :News-Android-App:connectedDevMlGemmaDebugAndroidTest` (note `testInstrumentationRunnerArguments clearPackageData: 'true'` at `build.gradle:22` wipes app data between tests — a downloaded model in `getExternalFilesDir` survives `pm clear` on most devices but **do not rely on it**; the smoke test must skip gracefully).

---

## 19. Small but certain build-time snags
**SEVERITY: medium**

- Both MediaPipe AARs contain an identical 2.19 MB `META-INF/NOTICE` (same CRC `7486264a`). AAR-root `META-INF/` entries are not recognised AAR content and are normally ignored by AGP, but `build.gradle:84-88` already excludes `META-INF/NOTICE.txt` and **not** `META-INF/NOTICE`. If the duplicate-file error appears, add `'META-INF/NOTICE'` and `'META-INF/DEPENDENCIES'` to that `excludes` list. *(Low confidence that it triggers; zero cost to pre-empt.)*
- `guava:27.0.1-android` from `tasks-core` collides with `com.google.guava:listenablefuture:1.0` and needs `failureaccess`. `proguard-rules.pro:87-103` already has the guava `-dontwarn` block from a previous life, so R8 is covered; dependency resolution under `android.dependency.useConstraints=true` (`gradle.properties:32`) is the part to verify.
- `repositories` (`build.gradle:104-115`) already has `google()` first and unfiltered — **no repository change needed**; both artifacts are on Google Maven (`dl.google.com/dl/android/maven2`), not Maven Central. Verified: `repo1.maven.org` returns 404 for `com.google.mediapipe:tasks-text`.
- There is **no `gradle/verification-metadata.xml`** in this repo (only `gradle/wrapper/`), despite `org.gradle.dependency.verification.console=verbose` at `gradle.properties:25`. So adding dependencies does **not** require regenerating checksums. Good news; I checked because that flag usually implies the opposite.

---

# The three things most likely to sink this project

**1. The licensing/distribution collision, not the engineering.**
`tasks-core-1.0.0.pom` pulls `com.google.android.datatransport:transport-backend-cct` (Google Clearcut telemetry) into an app whose flavor is named `oss` and commented *"100% Open-Source Edition"* (`build.gradle:66-69`), which ships on F-Droid (`README.md:16`), and whose `PRIVACY.md:3` states it *"does not collect or send any data from you or your device"*. Both AARs are also unbuildable binary blobs, which F-Droid will not accept. This cannot be fixed by writing better code — it forces a permanent fork of the distribution story (an arm64-only, Play-or-sideload-only `mlGemma` variant that F-Droid never sees) and someone has to own that decision before a line is written. **Resolve first, in a conversation, not in Gradle.**

**2. The device-reach cliff makes the feature a minority build.**
LiteRT-LM 0.15.0 ships **only** `arm64-v8a` and `x86_64` — no armeabi-v7a, no x86 — at minSdk 24, and needs a 2.58 GB model download plus multi-GB RAM at inference. Combined with the ~47 MB of native libs (uncompressed at minSdk ≥ 23) and a mandatory `minSdk 21 → 24` bump whose usual escape hatch is blocked by `android.usesSdkInManifest.disallowed=true` (`gradle.properties:30`), the AI build is a separate, large, arm64-only, modern-Android-only artifact. If the team's mental model is "add a feature to the existing app," that model is wrong and the flavor-matrix cost (2×2×2 = 8 variants, a duplicated `AiModule` FQN in two source sets, `ci.yml:53` `assembleDev` becoming ambiguous, a possible ContentProvider authority collision from stacked `applicationIdSuffix`) will be discovered halfway through.

**3. The list pipeline's string surgery, which will produce wrong rankings that look right.**
`NewsReaderDetailFragment.java:539-545` blindly injects `" GROUP BY FINGERPRINT "` at `indexOf("ORDER BY")` into whatever SQL string it is handed, and `getCurrentRssItemView` (`DatabaseConnectionOrm.java:486-494`) pages on `_id > page*25` assuming contiguous rowids from 1. A JOIN against `AI_SCORE` slots into this fine *syntactically* while SQLite silently picks an arbitrary row per fingerprint group for the `ORDER BY a.RANK_SCORE` — so the AI digest will render, scroll, and page correctly while being subtly mis-ranked, with no error anywhere. The whole feature's value is the ordering. This is the failure that ships. `AiFolderSqlTest` (risk 18) is the cheapest insurance in the entire plan and should exist before the first AI row is ever written.