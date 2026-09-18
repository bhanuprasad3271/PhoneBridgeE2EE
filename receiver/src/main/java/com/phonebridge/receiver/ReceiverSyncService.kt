package com.phonebridge.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.room.Room
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.phonebridge.shared.CryptoBox
import com.phonebridge.shared.JsonCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

class ReceiverSyncService : Service() {
    companion object {
        const val ACTION_DATA_UPDATED = "com.phonebridge.receiver.DATA_UPDATED"
        private const val SYNC_CHANNEL = "pb_receiver_sync"
        private const val FOREGROUND_ID = 24001
    }

    private val auth by lazy { FirebaseAuth.getInstance() }
    private val fs by lazy { FirebaseFirestore.getInstance() }
    private val crypto by lazy { CryptoBox(this, "phonebridge_receiver_ecdh") }
    private val db by lazy {
        Room.databaseBuilder(this, LocalDb::class.java, "phonebridge.db").build()
    }
    private val notifications by lazy { ReceiverNotifications(this) }
    private val prefs by lazy { getSharedPreferences("bridge", MODE_PRIVATE) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var pairRegistration: ListenerRegistration? = null
    private var queueRegistration: ListenerRegistration? = null
    private var activeCollectorKey: String? = null

    override fun onCreate() {
        super.onCreate()
        createSyncChannel()
        notifications.createChannels()
        startForeground(FOREGROUND_ID, buildForegroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startListening()
        return START_STICKY
    }

    override fun onDestroy() {
        pairRegistration?.remove()
        queueRegistration?.remove()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createSyncChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                SYNC_CHANNEL,
                "Receiver background sync",
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    private fun buildForegroundNotification(): android.app.Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            FOREGROUND_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, SYNC_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("PhoneBridge Receiver active")
            .setContentText("Listening for Sender activity")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun startListening() {
        val pairId = prefs.getString("pairId", null)
        if (pairId.isNullOrBlank()) {
            stopSelf()
            return
        }

        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnSuccessListener { listenPair(pairId) }
                .addOnFailureListener { stopSelf() }
        } else {
            listenPair(pairId)
        }
    }

    private fun listenPair(pairId: String) {
        pairRegistration?.remove()
        val pairRef = fs.collection("pairs").document(pairId)

        pairRegistration = pairRef.addSnapshotListener { snap, err ->
            if (err != null || snap == null || !snap.exists()) {
                queueRegistration?.remove()
                queueRegistration = null
                activeCollectorKey = null
                if (snap != null && !snap.exists()) stopSelf()
                return@addSnapshotListener
            }

            val collectorKey = snap.getString("collectorPublicKeyB64")
            if (collectorKey.isNullOrBlank()) return@addSnapshotListener

            if (queueRegistration == null || activeCollectorKey != collectorKey) {
                queueRegistration?.remove()
                activeCollectorKey = collectorKey
                queueRegistration = listenForRecords(pairRef, pairId, collectorKey)
            }
        }
    }

    private fun listenForRecords(
        pairRef: DocumentReference,
        pairId: String,
        collectorKey: String
    ): ListenerRegistration {
        return pairRef.collection("queue").addSnapshotListener { snap, e ->
            if (e != null || snap == null) return@addSnapshotListener

            snap.documentChanges.forEach { change ->
                if (change.type.name != "ADDED") return@forEach
                val d = change.document
                val id = d.getString("id") ?: d.id
                val type = d.getString("type") ?: "unknown"
                val ct = d.getString("ciphertextB64") ?: return@forEach
                val nonce = d.getString("nonceB64") ?: return@forEach

                try {
                    val key = crypto.deriveAesKey(collectorKey, pairId)
                    val aad = (pairId + "|" + id + "|" + type + "|1").toByteArray()
                    val plain = crypto.decrypt(key, ct, nonce, aad).toString(Charsets.UTF_8)
                    val record = JsonCodec.decode(plain)

                    scope.launch {
                        val metadataJson = JSONObject(record.metadata)
                        val sessionKey = metadataJson.optString("sessionKey")
                        val localId = if ((record.type == "wp_call" || record.type == "call") && sessionKey.isNotBlank()) {
                            record.type + "_" + sessionKey.hashCode()
                        } else {
                            id
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

                        val isSessionRecord = record.type == "call" || record.type == "wp_call"
                        val existing = if (isSessionRecord) db.dao().getById(localId) else null
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

                        if (!isSessionRecord || existing == null || newUpdatedAt >= oldUpdatedAt) {
                            db.dao().insert(entity)
                            notifications.post(entity, localId)
                            sendBroadcast(
                                Intent(ACTION_DATA_UPDATED)
                                    .setPackage(packageName)
                            )
                        }

                        d.reference.delete()
                    }
                } catch (_: Exception) {
                    // Leave the item in Firestore if decryption fails so it is not silently lost.
                }
            }
        }
    }
}
