package com.phonebridge.receiver

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.widget.Button
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** Disposable Android 11 CI emulator; no real pair or cloud data. */
@RunWith(AndroidJUnit4::class)
class HistoryLoadingTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val today get() = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun indexedPeriodKeepsDatesDevicesTypesAndReplacementUpdatesIsolated() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LocalDb::class.java).build()
        try {
            val dao = db.dao()
            db.withTransaction {
                repeat(20000) { dao.insert(RecordEntity("old-$it", "message", today - 1 - it, "app", "", "old", "{}", "A")) }
                dao.insert(RecordEntity("today", "message", today, "app", "", "today", "{}", "A"))
                dao.insert(RecordEntity("other-device", "message", today, "app", "", "private B", "{}", "B"))
                dao.insert(RecordEntity("call", "call", today, "app", "", "call", "{}", "A"))
                dao.insert(RecordEntity("tomorrow", "message", today + 86400000, "app", "", "future", "{}", "A"))
                dao.insert(RecordEntity("received-old", "message", today - 999, "app", "", "old event newly received", "{\"receiverReceivedAtMs\":${today + 1000}}", "A"))
            }
            assertEquals(listOf("today"), dao.period("A", listOf("message"), today, today + 86399999).map { it.id })
            assertEquals(20001, dao.period("A", listOf("message"), Long.MIN_VALUE, today - 1).size)
            assertEquals(today + 86400000, dao.lastConnected("A"))
            dao.deleteById("tomorrow")
            assertEquals(today + 1000, dao.lastConnected("A"))
            dao.insert(RecordEntity("today", "message", today, "app", "", "replacement", "{}", "A"))
            assertEquals("replacement", dao.period("A", listOf("message"), today, today + 100).single().body)
            val plan = db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN SELECT * FROM records WHERE pairId='A' AND type='message' AND timestamp >= 0 AND timestamp <= 99").use { c ->
                buildString { while(c.moveToNext()) append(c.getString(3)) }
            }
            assertTrue(plan.contains("index_records_pairId_type_timestamp"))
        } finally { db.close() }
    }

    @Test fun versionTwoIndexMigrationPreservesHistory() = runBlocking {
        val name = "period-migration-${java.util.UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path, null).use {
            it.execSQL("CREATE TABLE records (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, timestamp INTEGER NOT NULL, source TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, metadataJson TEXT NOT NULL, pairId TEXT NOT NULL DEFAULT '')")
            it.execSQL("INSERT INTO records VALUES ('kept','message',1,'app','','kept','{}','A')")
            it.version = 2
        }
        val db = Room.databaseBuilder(context, LocalDb::class.java, name)
            .addMigrations(LocalDb.MIGRATION_2_3).build()
        try {
            assertEquals("kept", db.dao().period("A", listOf("message"), 0, 2).single().body)
            assertTrue(db.dao().period("B", listOf("message"), 0, 2).isEmpty())
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun receiverSurvivesRepeatedTabsWithLargeEncryptedHistory() = runBlocking {
        val prefs = com.phonebridge.shared.SecurePreferences.get(context, "bridge")
        prefs.edit().clear().putBoolean("device_profiles_migrated", true).putString("pairId", "").commit()
        val dao = LocalDb.get(context).dao()
        LocalDb.get(context).withTransaction {
            repeat(12000) { dao.insert(RecordEntity("perf-old-$it", "message", today - 1 - it, "app", "", "old", "{}")) }
            dao.insert(RecordEntity("perf-today", "message", today, "app", "", "today", "{}"))
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val ids = listOf(R.id.btnMessagesTab, R.id.btnCallsTab, R.id.btnNotificationsTab, R.id.btnWpCallsTab)
                repeat(12) {
                    for (id in ids) {
                        scenario.onActivity { activity -> activity.findViewById<Button>(id).performClick() }
                        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    }
                }
                scenario.onActivity { it.findViewById<Button>(R.id.btnMessagesTab).performClick() }
                val deadline = System.currentTimeMillis() + 15000
                var count = -1
                while (count != 1 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100)
                    scenario.onActivity { count = it.findViewById<RecyclerView>(R.id.list).adapter!!.itemCount }
                }
                assertEquals("Today must load without returning yesterday's history", 1, count)
            }
        } finally {
            dao.deleteDevice("")
            prefs.edit().clear().commit()
        }
    }
}
