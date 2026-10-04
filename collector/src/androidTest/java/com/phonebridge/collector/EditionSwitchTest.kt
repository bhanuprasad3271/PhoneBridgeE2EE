package com.phonebridge.collector

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.phonebridge.shared.CryptoBox
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class EditionSwitchTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val sentinel get() = context.getSharedPreferences("edition_switch_test", Context.MODE_PRIVATE)

    @Test fun removalClearsPairAndOutboxButPreservesIdentity() {
        val p = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
        val saved = p.all
        val identity = CryptoBox(context, "phonebridge_collector_ecdh").publicKeyB64()
        val box = SenderOutbox(context)
        try {
            p.edit().putString("pairId", "remove-test").putString("receiverPublicKey", "peer")
                .putBoolean("locationSharingEnabled", true).commit()
            box.put("remove-test-item", "remove-test", mapOf("id" to "remove-test-item", "ciphertextB64" to "encrypted-test"))
            box.put("keep-test-item", "other-test", mapOf("id" to "keep-test-item", "ciphertextB64" to "other-encrypted"))
            assertFalse(SenderPairState.disconnect(context, "old-pair"))
            assertNotNull(box.next("remove-test"))
            assertTrue(SenderPairState.disconnect(context, "remove-test"))
            assertNull(BridgePrefs(context).pairId)
            assertNull(BridgePrefs(context).receiverPublicKey)
            assertFalse(BridgePrefs(context).locationSharingEnabled)
            assertNull(box.next("remove-test"))
            assertNotNull(box.next("other-test"))
            assertEquals(identity, CryptoBox(context, "phonebridge_collector_ecdh").publicKeyB64())
        } finally {
            box.deletePair("remove-test"); box.deletePair("other-test")
            val edit = p.edit().clear()
            saved.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Long -> edit.putLong(key, value)
                is Int -> edit.putInt(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Float -> edit.putFloat(key, value)
            } }
            edit.commit()
        }
    }

    @Test fun seedPairing() {
        val prefs = BridgePrefs(context)
        prefs.pairId = "654321"
        prefs.receiverPublicKey = CryptoBox(context, "edition_test_peer").publicKeyB64()
        prefs.pairedAtMs = 123456789L
        prefs.lastCallTimestamp = 234567890L
        prefs.locationSharingEnabled = true
        // Each instrumentation invocation exits its process. Flush pending apply()
        // writes before the next invocation checks persistence or replaces the APK.
        assertTrue("Persist seeded pairing before test process exits",
            context.getSharedPreferences("bridge", Context.MODE_PRIVATE).edit()
                .putBoolean("edition_test_seeded", true).commit())
        assertEquals("654321", prefs.pairId)
        context.getSharedPreferences("message_dedupe", Context.MODE_PRIVATE)
            .edit().putBoolean("edition_test_sentinel", true).commit()
        val crypto = CryptoBox(context, "phonebridge_collector_ecdh")
        val key = crypto.deriveAesKey(prefs.receiverPublicKey!!, prefs.pairId!!)
        val (ciphertext, nonce) = crypto.encrypt(key, "preserved encrypted data".toByteArray(), byteArrayOf(1))
        assertTrue(sentinel.edit().putString("publicKey", crypto.publicKeyB64())
            .putString("ciphertext", ciphertext).putString("nonce", nonce).commit())
        @Suppress("UNCHECKED_CAST")
        val worker = Class.forName("com.phonebridge.collector.LocationSyncWorker") as Class<out androidx.work.ListenableWorker>
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build()
        val work = WorkManager.getInstance(context)
        for (name in listOf("phonebridge-location-sharing", "phonebridge-location-sharing-now",
            "phonebridge-location-sharing-special-text")) {
            val request = androidx.work.OneTimeWorkRequest.Builder(worker)
                .setConstraints(constraints).setInitialDelay(1, TimeUnit.DAYS).build()
            work.enqueueUniqueWork(name, androidx.work.ExistingWorkPolicy.REPLACE, request)
                .result.get(10, TimeUnit.SECONDS)
        }
    }

    @Test fun verifyPairingAndEdition() {
        val prefs = BridgePrefs(context)
        assertEquals("654321", prefs.pairId)
        assertEquals(123456789L, prefs.pairedAtMs)
        assertEquals(234567890L, prefs.lastCallTimestamp)
        assertTrue(context.getSharedPreferences("message_dedupe", Context.MODE_PRIVATE)
            .getBoolean("edition_test_sentinel", false))
        val crypto = CryptoBox(context, "phonebridge_collector_ecdh")
        assertEquals(sentinel.getString("publicKey", null), crypto.publicKeyB64())
        val key = crypto.deriveAesKey(prefs.receiverPublicKey!!, prefs.pairId!!)
        assertEquals("preserved encrypted data", String(crypto.decrypt(key,
            sentinel.getString("ciphertext", null)!!, sentinel.getString("nonce", null)!!, byteArrayOf(1))))
        val requested = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        if (context.getString(R.string.sender_edition) == "lite") {
            assertFalse(prefs.locationSharingEnabled)
            assertFalse(requested.any { it.contains("LOCATION") })
            for (name in listOf("LocationSyncWorker", "LocationSharingService", "LocationSharingScheduler")) {
                try {
                    Class.forName("com.phonebridge.collector.$name")
                    fail("Location implementation must be absent from Lite: $name")
                } catch (_: ClassNotFoundException) { }
            }
            try {
                Class.forName("com.google.android.gms.location.LocationServices")
                fail("Play Services Location must be absent from Lite")
            } catch (_: ClassNotFoundException) { }
            for (name in listOf("phonebridge-location-sharing", "phonebridge-location-sharing-now",
                "phonebridge-location-sharing-special-text")) {
                val work = WorkManager.getInstance(context)
                assertTrue(work.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).all { it.state.isFinished })
            }
        } else {
            assertTrue(requested.contains("android.permission.ACCESS_FINE_LOCATION"))
            assertNotNull(Class.forName("com.phonebridge.collector.LocationSyncWorker"))
        }
    }
}
