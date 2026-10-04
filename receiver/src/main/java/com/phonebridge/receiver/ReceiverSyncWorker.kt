package com.phonebridge.receiver

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.room.withTransaction
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QueryDocumentSnapshot
import com.phonebridge.shared.CryptoBox
import com.phonebridge.shared.JsonCodec
import com.phonebridge.shared.MessageIdentity
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class ReceiverSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_PAIR_ID = "pairId"
        const val KEY_QUEUE_ID = "queueId"
        const val ACTION_DATA_UPDATED = "com.phonebridge.receiver.DATA_UPDATED"
    }

    private val auth = FirebaseAuth.getInstance()
    private val fs = FirebaseFirestore.getInstance()
    private val crypto = CryptoBox(appContext, "phonebridge_receiver_ecdh")
    private val db = LocalDb.get(appContext)

    override suspend fun doWork(): Result {
        DeviceRecovery.purge(applicationContext)
        val profiles = DeviceProfiles(applicationContext)
        val requested = inputData.getString(KEY_PAIR_ID)
        val pairIds = profiles.visible().filter { !it.archived && (requested == null || it.pairId == requested) }.map { it.pairId }
        if (pairIds.isEmpty()) return Result.success()
        val queueId = inputData.getString(KEY_QUEUE_ID)

        return try {
            if (auth.currentUser == null) auth.signInAnonymously().await()
            var failed = false
            for (pairId in pairIds) try {
            val pairRef = fs.collection("pairs").document(pairId)
            val pairSnap = pairRef.get(com.google.firebase.firestore.Source.SERVER).await()
            if (!pairSnap.exists() || pairSnap.getLong("disconnectedAtMs") != null) continue

            val collectorKey = pairSnap.getString("collectorPublicKeyB64")
                ?: continue
            val accepted = DeviceRecovery.mutex.withLock { profiles.bindSender(pairId, collectorKey) }
            if (!accepted) continue

            if (queueId != null) {
                val doc = pairRef.collection("queue").document(queueId).get().await()
                if (doc.exists()) processDoc(pairId, collectorKey, doc.id, doc.data.orEmpty())
            } else {
                val snap = pairRef.collection("queue").get().await()
                for (doc in snap.documents) {
                    processDoc(pairId, collectorKey, doc.id, doc.data.orEmpty())
                }
            }

            } catch (_: Exception) { failed = true }
            applicationContext.sendBroadcast(
                Intent(ACTION_DATA_UPDATED).setPackage(applicationContext.packageName)
            )
            if (failed) Result.retry() else Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private suspend fun processDoc(
        pairId: String,
        collectorKey: String,
        docId: String,
        data: Map<String, Any>
    ) {
        val id = data["id"] as? String ?: docId
        val type = data["type"] as? String ?: "unknown"
        val ct = data["ciphertextB64"] as? String ?: return
        val nonce = data["nonceB64"] as? String ?: return

        val key = crypto.deriveAesKey(collectorKey, pairId)
        val aad = (pairId + "|" + id + "|" + type + "|1").toByteArray()
        val plain = crypto.decrypt(key, ct, nonce, aad).toString(Charsets.UTF_8)
        val record = JsonCodec.decode(plain)
        val metadataJson = JSONObject(record.metadata)
        metadataJson.put("receiverReceivedAtMs", System.currentTimeMillis())
        val sessionKey = metadataJson.optString("sessionKey")

        val historyId = DeviceProfiles(applicationContext).historyId(pairId)
        val scopedIdentity = MessageIdentity.hash(historyId, collectorKey)
        val localId = when {
            record.type == "status" -> "sender_status_$scopedIdentity"
            record.type == "alert" && metadataJson.optString("kind") in DeviceStatus.permissionKinds ->
                "sender_permission_${scopedIdentity}_${metadataJson.optString("kind")}"
            record.type == "message" && record.metadata["messageId"]?.matches(Regex("msg_v1_[0-9a-f]{64}")) == true ->
                "message_" + MessageIdentity.hash(historyId, collectorKey, record.metadata.getValue("messageId"))
            (record.type == "wp_call" || record.type == "call") && sessionKey.isNotBlank() ->
                record.type + "_" + MessageIdentity.hash(historyId, collectorKey, sessionKey)
            else -> MessageIdentity.hash(pairId, collectorKey, id)
        }

        val entity = RecordEntity(
            id = localId,
            type = record.type,
            timestamp = record.timestamp,
            source = record.source,
            title = record.title,
            body = record.body,
            metadataJson = metadataJson.toString(),
            pairId = historyId
        )

        val isReplaceableRecord =
            record.type == "call" || record.type == "wp_call" || record.type == "status" ||
                record.type == "alert" && metadataJson.optString("kind") in DeviceStatus.permissionKinds
        DeviceRecovery.mutex.withLock {
        if (DeviceProfiles(applicationContext).visible().none { it.pairId == pairId && !it.archived }) return
        db.withTransaction {
        val existing = if (isReplaceableRecord) db.dao().getById(localId) else null
        val newUpdatedAt = metadataJson.optLong("updatedAtMs", record.timestamp)
        val oldUpdatedAt = if (existing != null) {
            try {
                JSONObject(existing.metadataJson).optLong("updatedAtMs", existing.timestamp)
            } catch (_: Exception) {
                existing.timestamp
            }
        } else {
            Long.MIN_VALUE
        }

        val isSenderState = record.type == "status" ||
            record.type == "alert" && metadataJson.optString("kind") in DeviceStatus.permissionKinds
        val newer = if (isSenderState && existing != null) DeviceStatus.compareState(entity, existing) >= 0
            else newUpdatedAt >= oldUpdatedAt
        if (!isReplaceableRecord || existing == null || newer) {
            db.dao().insert(entity)
            if (record.type == "alert" && metadataJson.optString("kind") in DeviceStatus.permissionKinds) {
                // Keep current permission state independently of dismissible warnings.
                db.dao().insert(entity.copy(id = "permission_state_$localId", type = "status"))
            }
        }
        }
        }

        val locationRequestId = if (record.type == "location") {
            metadataJson.optString("requestId")
        } else {
            ""
        }
        if (locationRequestId.isNotBlank()) {
            val interim = metadataJson.optString("locationFreshness") == "cached_interim"
            val currentRequest = fs.collection("pairs").document(pairId).get().await()
            val currentStatus = currentRequest.getString("locationRequestStatus")
            if (currentRequest.getString("locationRequestId") == locationRequestId &&
                (!interim || currentStatus != "sent" && currentStatus != "receiver_saved")
            ) {
                fs.collection("pairs")
                .document(pairId)
                .update(
                    mapOf(
                        "locationRequestStatusId" to locationRequestId,
                        "locationRequestStatus" to if (interim) "receiver_saved_interim" else "receiver_saved",
                        "locationRequestStatusAtMs" to System.currentTimeMillis()
                    )
                )
                .await()
            }
        }

        // Delivery acknowledgement: the Firebase queue item is removed only after
        // this exact record has been successfully persisted in Room. If Room save
        // fails, execution throws before this point and Firestore keeps the record
        // for the next Receiver sync attempt.
        fs.collection("pairs")
            .document(pairId)
            .collection("queue")
            .document(docId)
            .delete()
            .await()
    }
}
