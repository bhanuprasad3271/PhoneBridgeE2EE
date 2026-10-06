package com.phonebridge.receiver

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException

/** Display selection never determines which device gets synchronized. */
object ReceiverSyncRequests {
    fun name(pairId: String) = "receiver-foreground-sync-$pairId"
    fun enqueue(context: Context, pairId: String, restart: Boolean = false): Operation {
        val request = OneTimeWorkRequestBuilder<ReceiverSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(name(pairId) + "-event")
                .setInputData(workDataOf(ReceiverSyncWorker.KEY_PAIR_ID to pairId)).build()
        val work = WorkManager.getInstance(context)
        // Queue events must not append behind an older job's retry backoff.
        // Concurrent saves are serialized by DeviceRecovery.mutex and use stable IDs.
        return if (restart) work.enqueueUniqueWork(name(pairId), ExistingWorkPolicy.REPLACE, request)
            else work.enqueue(request)
    }

    fun catchUpAll(context: Context) {
        // Retire the legacy all-device retry chain; each device now has its own.
        WorkManager.getInstance(context).cancelUniqueWork("receiver-foreground-sync")
        for (device in DeviceProfiles(context).visible().filter { !it.archived }) {
            enqueue(context, device.pairId, restart = true)
        }
    }
}

/** One undecodable/failed record must not hold the rest of a device's queue hostage. */
object ReceiverQueueBatch {
    suspend fun <T> process(items: List<T>, saveAndAck: suspend (T) -> Unit): Boolean {
        var failed = false
        for (item in items) try {
            saveAndAck(item)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) { failed = true }
        return !failed
    }
}
