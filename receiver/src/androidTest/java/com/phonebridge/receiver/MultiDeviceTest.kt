package com.phonebridge.receiver

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.phonebridge.shared.CryptoBox
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Disposable CI emulator only: no real Firebase pair or user phone is touched. */
@RunWith(AndroidJUnit4::class)
class MultiDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun switchingDeviceRestartsOnlyItsBlockedSyncChain() {
        val work = androidx.work.WorkManager.getInstance(context)
        val a = "sync-test-A"; val b = "sync-test-B"
        fun stalled() = androidx.work.OneTimeWorkRequestBuilder<ReceiverSyncWorker>()
            .setInitialDelay(1, java.util.concurrent.TimeUnit.DAYS).build()
        val oldA = stalled(); val oldB = stalled()
        try {
            work.enqueueUniqueWork(ReceiverSyncRequests.name(a), androidx.work.ExistingWorkPolicy.REPLACE, oldA)
                .result.get(10, java.util.concurrent.TimeUnit.SECONDS)
            work.enqueueUniqueWork(ReceiverSyncRequests.name(b), androidx.work.ExistingWorkPolicy.REPLACE, oldB)
                .result.get(10, java.util.concurrent.TimeUnit.SECONDS)
            ReceiverSyncRequests.enqueue(context, b).result.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertTrue("New data must not wait behind the old delayed job",
                work.getWorkInfosByTag(ReceiverSyncRequests.name(b) + "-event")
                    .get(10, java.util.concurrent.TimeUnit.SECONDS).any { it.state != androidx.work.WorkInfo.State.BLOCKED })
            ReceiverSyncRequests.enqueue(context, a, restart = true).result.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(androidx.work.WorkInfo.State.CANCELLED,
                work.getWorkInfoById(oldA.id).get(10, java.util.concurrent.TimeUnit.SECONDS)!!.state)
            assertEquals(androidx.work.WorkInfo.State.ENQUEUED,
                work.getWorkInfoById(oldB.id).get(10, java.util.concurrent.TimeUnit.SECONDS)!!.state)
            assertTrue(work.getWorkInfosForUniqueWork(ReceiverSyncRequests.name(a))
                .get(10, java.util.concurrent.TimeUnit.SECONDS).any { it.id != oldA.id })
        } finally {
            work.cancelUniqueWork(ReceiverSyncRequests.name(a)).result.get(10, java.util.concurrent.TimeUnit.SECONDS)
            work.cancelUniqueWork(ReceiverSyncRequests.name(b)).result.get(10, java.util.concurrent.TimeUnit.SECONDS)
            work.cancelAllWorkByTag(ReceiverSyncRequests.name(b) + "-event").result.get(10, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    @Test fun inactiveSenderRecordsAreSavedDespiteOneBadQueueItem() = runBlocking {
        val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("device_profiles_migrated", true).commit()
        val profiles = DeviceProfiles(context)
        val db = Room.inMemoryDatabaseBuilder(context, LocalDb::class.java).build()
        try {
            profiles.add("batch-A"); profiles.add("batch-B"); profiles.select("batch-B")
            val acknowledged = mutableListOf<String>()
            val complete = ReceiverQueueBatch.process(listOf("bad", "message", "call", "notification", "location")) { type ->
                if (type == "bad") error("Undecodable test item")
                db.dao().insert(RecordEntity(type, type, 1, "sender", "", "new A data", "{}", "batch-A"))
                acknowledged.add(type)
            }
            assertFalse(complete) // retry the failed item, but not at the expense of new data
            assertEquals("batch-B", profiles.activeId())
            assertTrue(db.dao().forPair("batch-B").isEmpty())
            profiles.select("batch-A")
            assertEquals(setOf("message", "call", "notification", "location"),
                db.dao().forPair(profiles.historyId(profiles.activeId()!!)).map { it.type }.toSet())
            assertFalse("Failed records must not be acknowledged", "bad" in acknowledged)
            var reachedSecond = false
            try {
                ReceiverQueueBatch.process(listOf(1, 2)) {
                    if (it == 1) throw kotlinx.coroutines.CancellationException("switch restart")
                    reachedSecond = true
                }
                fail("Cancellation must be propagated")
            } catch (_: kotlinx.coroutines.CancellationException) { }
            assertFalse(reachedSecond)
        } finally { db.close(); prefs.edit().clear().commit() }
    }

    @Test fun fullUpgradeReplacesLiteDespiteClockChangesAndDelayedDelivery() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LocalDb::class.java).build()
        fun state(edition: String, revision: Long, time: Long) = RecordEntity("sender-state", "status", time,
            "sender", "", "", """{"senderEdition":"$edition","appVersion":"0.1.2154-$edition","senderStateRevision":"$revision","locationSharing":"${if (edition == "lite") "unsupported" else "enabled"}"}""", "test-pair")
        val legacyLite = state("lite", 0, 90000)
        val full = state("full", 2, 100)
        val delayedLite = state("lite", 1, 80000)
        try {
            db.dao().insert(legacyLite)
            for (incoming in listOf(full, delayedLite, legacyLite)) {
                val existing = db.dao().getById(incoming.id)!!
                if (DeviceStatus.compareState(incoming, existing) >= 0) db.dao().insert(incoming)
            }
            val status = DeviceStatus.from(db.dao().forPair("test-pair"))
            assertEquals("Full", status.edition)
            assertEquals("Enabled", status.location)
            val oldPermission = RecordEntity("old-permission", "status", 99999, "sender", "", "",
                """{"kind":"location_sharing","state":"unsupported","senderStateRevision":"1"}""", "test-pair")
            assertEquals("Enabled", DeviceStatus.from(listOf(full, oldPermission)).location)
            val liteAgain = state("lite", 3, 50)
            assertTrue(DeviceStatus.compareState(liteAgain, full) > 0)
            assertEquals("Unavailable in Lite", DeviceStatus.from(listOf(full, liteAgain)).location)
        } finally { db.close() }
    }

    @Test fun recoveryKeepsHistoryAndSettingsOnlyForSameKeyBeforeSixHours() = runBlocking {
        val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("device_profiles_migrated", true).commit()
        val profiles = DeviceProfiles(context)
        val db = LocalDb.get(context)
        val now = System.currentTimeMillis()
        try {
            profiles.add("old-A"); profiles.bindSender("old-A", "key-A")
            profiles.rename("old-A", "C21Y")
            profiles.add("live-B"); profiles.bindSender("live-B", "key-B")
            profiles.select("old-A")
            val setting = context.getSharedPreferences(DeviceProfiles.preferenceName(context, "monitoring", "old-A"), Context.MODE_PRIVATE)
            setting.edit().putString("test", "kept").commit()
            db.dao().insert(RecordEntity("recovery-A", "message", now, "sender", "", "saved A", "{}", "old-A"))
            db.dao().insert(RecordEntity("recovery-B", "message", now, "sender", "", "saved B", "{}", "live-B"))
            profiles.remove("old-A", now)
            assertEquals("live-B", profiles.activeId())
            assertFalse(profiles.visible().any { it.pairId == "old-A" })
            DeviceRecovery.purge(context, now + DeviceProfiles.RECOVERY_MS - 1)
            assertEquals(1, db.dao().forPair("old-A").size)
            profiles.add("wrong"); profiles.bindSender("wrong", "key-other", now + 1)
            assertEquals("wrong", profiles.historyId("wrong"))
            profiles.add("new-A")
            assertTrue(profiles.bindSender("new-A", "key-A", now + DeviceProfiles.RECOVERY_MS - 1))
            assertEquals("old-A", profiles.historyId("new-A"))
            assertEquals("C21Y", profiles.all().first { it.pairId == "new-A" }.name)
            assertEquals("kept", context.getSharedPreferences(DeviceProfiles.preferenceName(context, "monitoring", "new-A"), Context.MODE_PRIVATE).getString("test", null))
            DeviceRecovery.purge(context, now + DeviceProfiles.RECOVERY_MS)
            assertEquals(1, db.dao().forPair("old-A").size) // old expiry cannot delete restored history
            assertFalse(profiles.bindSender("new-A", "key-other", now + 1))
            profiles.remove("new-A", now)
            profiles.add("too-late")
            profiles.bindSender("too-late", "key-A", now + DeviceProfiles.RECOVERY_MS)
            assertEquals("too-late", profiles.historyId("too-late"))
            DeviceRecovery.purge(context, now + DeviceProfiles.RECOVERY_MS)
            assertTrue(db.dao().forPair("old-A").isEmpty())
            assertTrue(setting.all.isEmpty())
            assertEquals(1, db.dao().forPair("live-B").size)
        } finally {
            db.dao().deleteDevice("old-A"); db.dao().deleteDevice("live-B")
            context.getSharedPreferences("monitoring_device_old-A", Context.MODE_PRIVATE).edit().clear().commit()
            prefs.edit().clear().commit()
        }
    }

    @Test fun profilesMigrateRenameSwitchAndKeepKeys() {
        val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("pairId", "123456").commit()
        val key = CryptoBox(context, "phonebridge_receiver_ecdh").publicKeyB64()
        val profiles = DeviceProfiles(context)
        assertEquals("123456", profiles.activeId())
        profiles.markPaired("123456")
        profiles.rename("123456", "Realme C21Y")
        profiles.add("654321")
        profiles.markPaired("654321")
        profiles.select("654321")
        assertEquals(2, DeviceProfiles(context).all().size)
        profiles.select("123456")
        assertEquals("Realme C21Y", profiles.activeName())
        assertEquals(key, CryptoBox(context, "phonebridge_receiver_ecdh").publicKeyB64())
        profiles.archive("654321")
        assertFalse(profiles.all().first { it.pairId == "123456" }.archived)
        assertTrue(profiles.all().first { it.pairId == "654321" }.archived)
        assertNotEquals(DeviceProfiles.preferenceName(context, "monitoring", "123456"),
            DeviceProfiles.preferenceName(context, "monitoring", "654321"))
        assertEquals("timeline_place_names", DeviceProfiles.preferenceName(context, "timeline_place_names", ""))
        prefs.edit().clear().commit()
    }

    @Test fun roomMigrationKeepsUnattributedHistoryAndIsolatesDevices() = runBlocking {
        val name = "multi-device-test-${UUID.randomUUID()}.db"
        val old = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        old.execSQL("CREATE TABLE records (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, timestamp INTEGER NOT NULL, source TEXT NOT NULL, title TEXT NOT NULL, body TEXT NOT NULL, metadataJson TEXT NOT NULL)")
        old.execSQL("INSERT INTO records VALUES ('old', 'message', 1, 'sender', 'Old', 'Kept', '{}')")
        old.version = 1
        old.close()
        val db = Room.databaseBuilder(context, LocalDb::class.java, name).addMigrations(LocalDb.MIGRATION_1_2).build()
        try {
            assertEquals("Kept", db.dao().forPair("").single().body)
            assertTrue(db.dao().forPair("123456").isEmpty())
            fun row(id: String, pair: String, type: String = "message") = RecordEntity(id, type, 5, "sender", "Title", "Body", "{}", pair)
            db.dao().insert(row("A", "123456"))
            db.dao().insert(row("B", "654321"))
            db.dao().insert(row("warning-A", "123456", "alert"))
            db.dao().insert(row("warning-B", "654321", "alert"))
            db.dao().insert(row("status-A", "123456", "status"))
            db.dao().clearAlerts("123456")
            assertEquals(setOf("A", "status-A"), db.dao().forPair("123456").map { it.id }.toSet())
            assertEquals(setOf("B", "warning-B"), db.dao().forPair("654321").map { it.id }.toSet())
            assertEquals(1, db.dao().forPair("").size)
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun statusUsesLatestFieldsAndHidesResolvedWarnings() {
        fun row(id: String, type: String, time: Long, meta: String) = RecordEntity(id, type, time, "sender", "", "", meta, "123456")
        val older = row("old", "status", 10, """{"appVersion":"0.1.2149-lite","senderEdition":"lite","notificationAccess":"disabled","callSync":"enabled","locationSharing":"unsupported"}""")
        val fixed = row("fixed", "alert", 20, """{"kind":"notification_access","state":"enabled","severity":"resolved"}""")
        val warning = row("warning", "alert", 15, """{"kind":"notification_access","severity":"warning"}""")
        val status = DeviceStatus.from(listOf(older, fixed, warning))
        assertEquals("Lite", status.edition)
        assertEquals("Enabled", status.notifications)
        assertEquals("Unavailable in Lite", status.location)
        assertEquals("#149", status.build)
        assertTrue(DeviceStatus.warnings(listOf(older, fixed, warning)).isEmpty())
        val newer = row("new", "status", 30, """{"appVersion":"0.1.2150-full","githubBuild":"150","senderEdition":"full","notificationAccess":"enabled","callSync":"disabled","locationSharing":"off","receiverReceivedAtMs":40}""")
        val next = DeviceStatus.from(listOf(older, fixed, warning, newer))
        assertEquals("Full", next.edition)
        assertEquals("#150", next.build)
        assertEquals("Disabled", next.calls)
        assertEquals(40L, next.lastConnected)
        assertEquals("Unknown", DeviceStatus.from(emptyList()).edition)
    }
}
