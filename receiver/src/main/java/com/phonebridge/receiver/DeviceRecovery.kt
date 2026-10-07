package com.phonebridge.receiver

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.tasks.await
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source

/** Serialize removal, recovery, queue saves and expiry within this app process. */
object DeviceRecovery {
    val mutex = Mutex()
    suspend fun purge(context: Context, now: Long = System.currentTimeMillis()) = mutex.withLock {
        val profiles = DeviceProfiles(context)
        for (device in profiles.all().filter { it.removedAtMs > 0 && now >= it.removedAtMs + DeviceProfiles.RECOVERY_MS }) {
            LocalDb.get(context).dao().deleteDevice(device.historyId)
            for (base in listOf("monitoring", "timeline_place_names")) {
                com.phonebridge.shared.SecurePreferences.get(context, DeviceProfiles.preferenceName(context, base, device.pairId)).edit().clear().commit()
            }
            profiles.forget(device.pairId)
        }
    }
    fun schedule(context: Context) {
        for (device in DeviceProfiles(context).all().filter { it.removedAtMs > 0 }) {
        WorkManager.getInstance(context).enqueueUniqueWork("device-recovery-expiry-${device.pairId}", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DeviceExpiryWorker>()
                .setInputData(androidx.work.workDataOf("pairId" to device.pairId))
                .setInitialDelay((device.removedAtMs + DeviceProfiles.RECOVERY_MS - System.currentTimeMillis()).coerceAtLeast(0L), TimeUnit.MILLISECONDS).build())
        }
    }
}

class DeviceExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { return try {
        DeviceRecovery.purge(applicationContext)
        val pair = inputData.getString("pairId") ?: return Result.success()
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) auth.signInAnonymously().await()
        val fs = FirebaseFirestore.getInstance()
        val ref = fs.collection("pairs").document(pair)
        val snap = ref.get(Source.SERVER).await()
        val removed = snap.getLong("disconnectedAtMs")
        // Never delete queues from an active/reused code. Preserve the revocation
        // marker so a Sender that was offline can still discover disconnection.
        if (removed != null && System.currentTimeMillis() >= removed + DeviceProfiles.RECOVERY_MS) {
            while (true) {
                val docs = ref.collection("queue").limit(400).get(Source.SERVER).await().documents
                if (docs.isEmpty()) break
                val deleted = fs.runTransaction { tx ->
                    val current = tx.get(ref)
                    if (current.getLong("disconnectedAtMs") != removed) false
                    else { docs.forEach { tx.delete(it.reference) }; true }
                }.await()
                if (!deleted) break
            }
        }
        Result.success()
    } catch (_: Exception) { Result.retry() } }
}

