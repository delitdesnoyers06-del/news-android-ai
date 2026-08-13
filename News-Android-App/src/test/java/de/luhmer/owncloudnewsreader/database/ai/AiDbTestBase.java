package de.luhmer.owncloudnewsreader.database.ai;

import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;

/**
 * Shared fixture for the AI persistence tests: a real, in-memory SQLite database with the AI schema
 * created. Robolectric supplies the real SQLite implementation, so everything asserted here is
 * asserted against the engine that actually runs on the device — not a mock.
 */
public abstract class AiDbTestBase {

    protected SQLiteDatabase sqlite;
    protected AiDb db;

    @Before
    public void openDatabase() {
        sqlite = SQLiteDatabase.create(null);   // in-memory
        AiSchema.createOrMigrate(sqlite);
        db = AiDb.of(sqlite);
    }

    @After
    public void closeDatabase() {
        if (sqlite != null && sqlite.isOpen()) {
            sqlite.close();
        }
    }

    protected long countOf(String table) {
        return db.queryLong("SELECT COUNT(*) FROM " + table, null, -1L);
    }
}
