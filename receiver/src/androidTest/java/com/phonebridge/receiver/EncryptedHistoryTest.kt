package com.phonebridge.receiver

import android.content.ContextWrapper
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EncryptedHistoryTest {
    private fun isolated(block: (ContextWrapper) -> Unit) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "history-test-${UUID.randomUUID()}").apply { mkdirs() }
        val ctx = object : ContextWrapper(base) {
            override fun getDatabasePath(name: String) = if (File(name).isAbsolute) File(name) else File(root, name)
            override fun getNoBackupFilesDir() = File(root, "no-backup").apply { mkdirs() }
            override fun deleteDatabase(name: String) = SQLiteDatabase.deleteDatabase(getDatabasePath(name))
        }
        try { block(ctx) } finally { root.deleteRecursively() }
    }
    private fun seed(ctx: ContextWrapper) {
        SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath("phonebridge.db"), null).use {
            it.execSQL("CREATE TABLE records (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, timestamp INTEGER NOT NULL, source TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, metadataJson TEXT NOT NULL)")
            it.execSQL("INSERT INTO records VALUES ('old','message',1,'sender','Private','sensitive-history-sentinel','{}')")
            it.version = 1
        }
    }
    @Test fun oldHistorySurvivesEncryptionAndRestart() = isolated { ctx -> runBlocking {
        seed(ctx)
        val factory = EncryptedHistory.prepare(ctx)
        val path = ctx.getDatabasePath(EncryptedHistory.NAME)
        val db = Room.databaseBuilder(ctx, LocalDb::class.java, path.absolutePath).openHelperFactory(factory).addMigrations(LocalDb.MIGRATION_1_2, LocalDb.MIGRATION_2_3).build()
        try {
            val rows = db.dao().all()
            assertEquals("Room must reopen the exported database at ${path.path}", path.path, db.openHelper.writableDatabase.path)
            assertEquals("All migrated rows must be retained", 1, rows.size)
            assertEquals("sensitive-history-sentinel", rows.single().body)
        } finally { db.close() }
        assertFalse(ctx.getDatabasePath("phonebridge.db").exists())
        val bytes = path.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.startsWith("SQLite format 3")); assertFalse(bytes.contains("sensitive-history-sentinel"))
        val reopened = Room.databaseBuilder(ctx, LocalDb::class.java, path.absolutePath)
            .openHelperFactory(EncryptedHistory.prepare(ctx)).addMigrations(LocalDb.MIGRATION_1_2, LocalDb.MIGRATION_2_3).build()
        try { assertEquals("sensitive-history-sentinel", reopened.dao().all().single().body) } finally { reopened.close() }
    } }
    @Test fun invalidTargetCannotDestroyPlaintextSource() = isolated { ctx ->
        seed(ctx)
        // Create a valid key first, then supply a damaged encrypted target alongside a legacy source.
        val old = ctx.getDatabasePath("phonebridge.db")
        val held = File(old.path + ".held"); check(old.renameTo(held))
        EncryptedHistory.prepare(ctx)
        check(held.renameTo(old))
        ctx.getDatabasePath(EncryptedHistory.NAME).writeText("corrupt-target")
        try { EncryptedHistory.prepare(ctx); fail("Corrupt target must fail closed") } catch (_: Exception) { }
        assertTrue(old.exists())
        SQLiteDatabase.openDatabase(old.path, null, SQLiteDatabase.OPEN_READONLY).use {
            it.rawQuery("SELECT body FROM records", null).use { c -> assertTrue(c.moveToFirst()); assertEquals("sensitive-history-sentinel", c.getString(0)) }
        }
    }
}
