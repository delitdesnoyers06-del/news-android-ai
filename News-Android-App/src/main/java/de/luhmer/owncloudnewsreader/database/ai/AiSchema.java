package de.luhmer.owncloudnewsreader.database.ai;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

/**
 * DDL for the AI tables. See docs/ai/PLAN.md D1 and §4.2.
 *
 * <p>These tables live in the very same database file as the greenDAO tables
 * ({@code OwncloudNewsReaderOrm.db}) but greenDAO must <b>never</b> learn about them:
 * {@code DaoMaster.dropAllTables()} drops exactly the four tables it generated, so anything it does
 * not know about survives a greenDAO schema bump, {@code DatabaseConnectionOrm.resetDatabase()} and
 * the Clear-cache path. {@code AiSchemaTest} is the regression test for that property.</p>
 *
 * <p>We own these migrations. The installed version is stored in {@code AI_META.K='schema_version'};
 * {@link #createOrMigrate(SQLiteDatabase)} is idempotent and is called from
 * {@code DatabaseHelperOrm.getDaoSession()} between {@code helper.getWritableDatabase()} and
 * {@code new DaoMaster(db)} — after any greenDAO upgrade triggered by the same call has completed.</p>
 *
 * <p>Degrade, never crash (PLAN invariant 7): a failure here disables the AI feature, it never
 * propagates to a user who only wants to read news.</p>
 */
public final class AiSchema {

    private static final String TAG = "AiSchema";

    /** Bump this and add a branch in {@link #migrate} when a table changes. */
    public static final int AI_SCHEMA_VERSION = 1;

    public static final String KEY_SCHEMA_VERSION = "schema_version";

    /** Table names. Nothing outside this package should need them. */
    public static final String T_META = "AI_META";
    public static final String T_SCORE = "AI_SCORE";
    public static final String T_EMBEDDING = "AI_EMBEDDING";
    public static final String T_DECISION = "AI_DECISION";
    public static final String T_TASTE = "AI_TASTE";
    public static final String T_CENTROID = "AI_CENTROID";
    public static final String T_RUBRIC = "AI_RUBRIC";
    public static final String T_DIGEST = "AI_DIGEST";
    public static final String T_DIGEST_ITEM = "AI_DIGEST_ITEM";
    public static final String T_MODEL = "AI_MODEL";

    /** All AI tables, in creation order. Used by the tests and by nothing else. */
    public static final String[] ALL_TABLES = {
            T_META, T_SCORE, T_EMBEDDING, T_DECISION, T_TASTE,
            T_CENTROID, T_RUBRIC, T_DIGEST, T_DIGEST_ITEM, T_MODEL
    };

    private static volatile boolean sReady;

    private AiSchema() {
    }

    /**
     * True once {@link #createOrMigrate(SQLiteDatabase)} has completed successfully in this process.
     * Every AI entry point should consult it before touching a store.
     */
    public static boolean isReady() {
        return sReady;
    }

    /**
     * Creates the AI tables if they are absent and runs any pending migration. Idempotent:
     * calling it twice on the same database is a no-op the second time.
     *
     * <p>Never throws. On failure the AI feature stays off for this process.</p>
     */
    public static void createOrMigrate(SQLiteDatabase db) {
        try {
            db.beginTransaction();
            try {
                createAll(db);
                int from = readVersion(db);
                if (from != AI_SCHEMA_VERSION) {
                    migrate(db, from, AI_SCHEMA_VERSION);
                    writeVersion(db, AI_SCHEMA_VERSION);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            sReady = true;
        } catch (Throwable t) {
            // Degrade, never crash: a broken AI schema must not take the reader down with it.
            sReady = false;
            Log.e(TAG, "createOrMigrate failed - AI features disabled for this process", t);
        }
    }

    /**
     * @param from the version already installed, {@code 0} when the schema is brand new
     */
    private static void migrate(SQLiteDatabase db, int from, int to) {
        // v1 is the initial schema; createAll() above already produced it. Future versions add
        // their ALTER TABLE statements here, guarded by `if (from < N)`.
        Log.i(TAG, "AI schema migrated from " + from + " to " + to);
    }

    private static void createAll(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS AI_META ("
                + "K TEXT PRIMARY KEY NOT NULL,"
                + "V TEXT,"
                + "B BLOB)");

        // ---- transient: triage state for articles currently present in RSS_ITEM ----
        db.execSQL("CREATE TABLE IF NOT EXISTS AI_SCORE ("
                + "RSS_ITEM_ID    INTEGER PRIMARY KEY NOT NULL," // == RSS_ITEM._id, TRANSIENT
                + "AI_KEY         TEXT NOT NULL,"                // fingerprint, or 'id:<n>'  (D2)
                + "STATUS         TEXT NOT NULL DEFAULT 'queued'," // queued|selected|discarded, NEVER 'kept'
                + "DISCARD_REASON TEXT,"       // low_similarity|below_top_k|low_score|out_of_window
                + "SIM_SCORE      REAL,"       // NULL = never embedded; 0.0 = cold start. LOAD-BEARING
                + "LLM_SCORE      INTEGER,"    // NULL = never judged (retry); 0 = judged off-topic (final)
                + "EFF_SCORE      INTEGER,"    // clamp(LLM_SCORE + feed bump, 0, 3)
                + "RANK_SCORE     REAL,"       // eff + 0.5*sim, or round(sim,3) when LLM_SCORE IS NULL
                + "THEMES         TEXT,"       // comma-joined slugs; order is LOAD-BEARING
                + "FLAGS          TEXT,"       // comma-joined subset of competitor,regulation,customer
                + "WHY            TEXT,"       // one line, device locale
                + "SCORED_AT      INTEGER,"    // epoch ms
                + "SYNC_ID        INTEGER)");  // which run produced this; resume + 'this batch'
        db.execSQL("CREATE INDEX IF NOT EXISTS IDX_AI_SCORE_RANK   ON AI_SCORE (STATUS, RANK_SCORE DESC)");
        db.execSQL("CREATE INDEX IF NOT EXISTS IDX_AI_SCORE_AI_KEY ON AI_SCORE (AI_KEY)");

        // ---- durable: survives the greenDAO wipe, the account reset and Clear cache ----
        db.execSQL("CREATE TABLE IF NOT EXISTS AI_EMBEDDING ("
                + "AI_KEY     TEXT PRIMARY KEY NOT NULL,"
                + "MODEL      TEXT NOT NULL,"   // e.g. 'embedding_gemma@int4int8'
                + "TASK       TEXT NOT NULL,"   // TextFormatContext EmbeddingType, e.g. 'CLUSTERING'
                + "DIM        INTEGER NOT NULL,"
                + "VEC        BLOB NOT NULL,"   // float32 LITTLE-ENDIAN == veille embed.py pack()
                + "CREATED_AT INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS IDX_AI_EMB_MODEL ON AI_EMBEDDING (MODEL, TASK)");

        // APPEND-ONLY. never UPDATE, never DELETE. Undo APPENDS a row.
        db.execSQL("CREATE TABLE IF NOT EXISTS AI_DECISION ("
                + "_id          INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "AI_KEY       TEXT NOT NULL,"
                + "ACTION       TEXT NOT NULL,"  // keep|reject|restore|undo
                + "FROM_STATE   TEXT NOT NULL,"  // the state observed before the action
                + "TO_STATE     TEXT NOT NULL,"  // queued|kept|rejected
                + "LLM_SCORE_AT INTEGER,"        // snapshot; NULL = never reached the LLM, INFORMATION
                + "SOURCE       TEXT NOT NULL,"  // swipe|fastaction|star_seed|settings
                + "TITLE_SNAP   TEXT,"           // sanitised title, 120 chars: few-shot / learn corpus
                + "CREATED_AT   INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS IDX_AI_DEC_KEY ON AI_DECISION (AI_KEY, _id DESC)");

        // current fold of AI_DECISION; one row per decided article
        db.execSQL("CREATE TABLE IF NOT EXISTS AI_TASTE ("
                + "AI_KEY      TEXT PRIMARY KEY NOT NULL,"
                + "STATE       TEXT NOT NULL,"  // kept|rejected  (undo => the row is DELETEd)
                + "DECIDED_AT  INTEGER NOT NULL,"
                + "IN_CENTROID INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS IDX_AI_TASTE_STATE ON AI_TASTE (STATE)");

        db.execSQL("CREATE TABLE IF NOT EXISTS AI_CENTROID ("
                + "CLASS TEXT PRIMARY KEY NOT NULL,"  // 'kept' | 'rejected'
                + "MODEL TEXT NOT NULL,"
                + "TASK  TEXT NOT NULL,"
                + "DIM   INTEGER NOT NULL,"
                + "N     INTEGER NOT NULL,"           // members folded in
                + "SUM   BLOB NOT NULL,"              // UNNORMALISED float32 running sum
                + "UNIT  BLOB)");                     // cached unit mean; NULL = recompute on read

        db.execSQL("CREATE TABLE IF NOT EXISTS AI_RUBRIC ("  // append-only, exactly one ACTIVE
                + "_id        INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "BODY       TEXT NOT NULL,"
                + "RATIONALE  TEXT,"
                + "ACTIVE     INTEGER NOT NULL DEFAULT 0,"
                + "SOURCE     TEXT NOT NULL,"          // user|model_approved|seed
                + "CREATED_AT INTEGER NOT NULL)");

        db.execSQL("CREATE TABLE IF NOT EXISTS AI_DIGEST ("
                + "_id            INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "DAY_KEY        TEXT NOT NULL UNIQUE,"  // 'yyyy-MM-dd' device zone: once per day
                + "RANGE_FROM     INTEGER NOT NULL,"
                + "RANGE_TO       INTEGER NOT NULL,"
                + "ITEM_COUNT     INTEGER NOT NULL,"
                + "ABSTRACT       TEXT,"                  // NULL = not generated / rejected by the guard
                + "ABSTRACT_STATE TEXT NOT NULL DEFAULT 'pending'," // pending|ok|skipped|failed
                + "DISMISSED_AT   INTEGER,"
                + "CREATED_AT     INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS AI_DIGEST_ITEM ("
                + "DIGEST_ID   INTEGER NOT NULL,"
                + "AI_KEY      TEXT NOT NULL,"
                + "RSS_ITEM_ID INTEGER,"
                + "THEME       TEXT,"
                + "RANK_SCORE  REAL,"
                + "POS         INTEGER NOT NULL,"
                + "PRIMARY KEY (DIGEST_ID, AI_KEY))");

        db.execSQL("CREATE TABLE IF NOT EXISTS AI_MODEL ("  // local registry; catalogue is res/raw
                + "MODEL_ID       TEXT PRIMARY KEY NOT NULL,"  // catalogue id, e.g. 'gemma-4-E2B'
                + "KIND           TEXT NOT NULL,"              // llm | embedder
                + "PATH           TEXT,"                       // absolute path; NULL until installed
                + "STATE          TEXT NOT NULL,"              // absent|partial|verifying|installed|broken
                + "SIZE_BYTES     INTEGER,"
                + "RECEIVED_BYTES INTEGER,"
                + "ETAG           TEXT,"
                + "SHA256         TEXT,"
                + "CAPS           TEXT,"   // {"responseFormat":true,"tokenizer":"sp","loadMs":91000}
                + "LAST_ERROR     TEXT,"
                + "UPDATED_AT     INTEGER NOT NULL)");
    }

    /** @return the installed schema version, or {@code 0} when none is recorded yet. */
    static int readVersion(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT V FROM AI_META WHERE K = ?", new String[]{KEY_SCHEMA_VERSION})) {
            if (c.moveToFirst() && !c.isNull(0)) {
                return Integer.parseInt(c.getString(0));
            }
        } catch (Throwable t) {
            Log.w(TAG, "unreadable AI schema version, treating as absent", t);
        }
        return 0;
    }

    private static void writeVersion(SQLiteDatabase db, int version) {
        db.execSQL("INSERT OR REPLACE INTO AI_META (K, V) VALUES (?, ?)",
                new Object[]{KEY_SCHEMA_VERSION, String.valueOf(version)});
    }
}
