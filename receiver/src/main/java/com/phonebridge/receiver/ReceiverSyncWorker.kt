package com.phonebridge.receiver

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.QueryDocumentSnapshot
import com.phonebridge.shared.CryptoBox
import com.phonebridge.shared.JsonCodec
import kotlinx.coroutines.tasks.await
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
    private val db = Room.databaseBuilder(appContext, LocalDb::class.java, "phonebridge.db").build()

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("bridge", Context.MODE_PRIVATE)
        val pairId = inputData.getString(KEY_PAIR_ID)
            ?: prefs.getString("pairId", null)
            ?: return Result.success()
        val queueId = inputData.getString(KEY_QUEUE_ID)

        return try {
            if (auth.currentUser == null) auth.signInAnonymously().await()
            val pairRef = fs.collection("pairs").document(pairId)
            val pairSnap = pairRef.get().await()
            if (!pairSnap.exists()) return Result.success()

            val collectorKey = pairSnap.getString("collectorPublicKeyB64")
                ?: return Result.retry()

            if (queueId != null) {
                val doc = pairRef.collection("queue").document(queueId).get().await()
                if (doc.exists()) processDoc(pairId, collectorKey, doc.id, doc.data.orEmpty())
            } else {
                val snap = pairRef.collection("queue").get().await()
                for (doc in snap.documents) {
                    processDoc(pairId, collectorKey, doc.id, doc.data.orEmpty())
                }
            }

            applicationContext.sendBroadcast(
                Intent(ACTION_DATA_UPDATED).setPackage(applicationContext.packageName)
            )
            Result.success()
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
        val sessionKey = metadataJson.optString("sessionKey")

        val localId = when {
            (record.type == "wp_call" || record.type == "call") && sessionKey.isNotBlank() ->
                record.type + "_" + sessionKey.hashCode()
            else -> id
        }

        val entity = RecordEntity(
            id = localId,
            type = record.type,
            timestamp = record.timestamp,
            source = record.source,
            title = record.title,
            body = record.body,
            metadataJson = metadataJson.toString()
        )

        val isReplaceableRecord =
            record.type == "call" || record.type == "wp_call"
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

        if (!isReplaceableRecord || existing == null || newUpdatedAt >= oldUpdatedAt) {
            db.dao().insert(entity)
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
