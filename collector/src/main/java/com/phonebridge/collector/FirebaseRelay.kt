package com.phonebridge.collector

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.phonebridge.shared.CryptoBox
import com.phonebridge.shared.JsonCodec
import com.phonebridge.shared.PlainRecord
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

class FirebaseRelay(private val activityOrService: android.content.Context) {
    companion object {
        private val io = Executors.newSingleThreadExecutor()
        private val callbacks = ConcurrentHashMap<String, (Boolean, String?) -> Unit>()
        private var draining = false // accessed only on io
    }
    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private val prefs = BridgePrefs(activityOrService)
    private val crypto = CryptoBox(activityOrService, "phonebridge_collector_ecdh")

    fun ensureSignedIn(done: (Boolean, String?) -> Unit) {
        if (auth.currentUser != null) return done(true, null)
        auth.signInAnonymously().addOnCompleteListener {
            done(it.isSuccessful, it.exception?.message)
        }
    }

    fun registerPair(done: (Boolean, String?) -> Unit) {
        val pairId = prefs.pairId ?: return done(false, "No pairing code")
        val uid = auth.currentUser?.uid ?: return done(false, "Not authenticated")
        val pairRef = db.collection("pairs").document(pairId)
        val publicKey = try {
            crypto.publicKeyB64()
        } catch (_: Exception) {
            done(false, "Could not access the secure pairing key. Restart the phone and try again; do not clear app data.")
            return
        }
        val data = mapOf(
            "collectorUid" to uid,
            "collectorPublicKeyB64" to publicKey,
            "collectorPairedAt" to FieldValue.serverTimestamp()
        )

        pairRef.firestore.runTransaction { tx ->
            val snap = tx.get(pairRef)
            check(snap.exists() && snap.getLong("disconnectedAtMs") == null) { "Pairing unavailable" }
            check(snap.getString("receiverUid") != uid) { "Cannot pair a device with itself" }
            val receiverKey = snap.getString("receiverPublicKeyB64") ?: error("Receiver key missing")
            crypto.validatePeerKey(receiverKey)
            val owner = snap.getString("collectorUid")
            if (owner == null) {
                check((snap.getLong("expiresAtMs") ?: 0L) > System.currentTimeMillis()) { "Pairing code expired" }
                tx.update(pairRef, data)
            } else {
                check(owner == uid && snap.getString("collectorPublicKeyB64") == publicKey) { "Pairing code already used" }
                check(prefs.receiverPublicKey == null || prefs.receiverPublicKey == receiverKey) { "Receiver key changed" }
            }
            receiverKey
        }.addOnSuccessListener { receiverKey ->
            if (prefs.pairId != pairId) done(false, "Pairing changed")
            else { prefs.receiverPublicKey = receiverKey; done(true, null) }
        }
            .addOnFailureListener { done(false, it.message) }
    }

    fun send(record: PlainRecord, done: ((Boolean, String?) -> Unit)? = null) {
        val pairId = prefs.pairId ?: return done?.invoke(false, "Not paired") ?: Unit
        val receiverKey = prefs.receiverPublicKey ?: return done?.invoke(false, "Missing receiver key") ?: Unit
        val uid = auth.currentUser?.uid ?: return done?.invoke(false, "Not authenticated") ?: Unit

        try {
            val key = crypto.deriveAesKey(receiverKey, pairId)
            val id = UUID.randomUUID().toString()
            val aad = "$pairId|$id|${record.type}|1".toByteArray()
            val plain = JsonCodec.encode(record).toByteArray(Charsets.UTF_8)
            val (ct, nonce) = try { crypto.encrypt(key, plain, aad) } finally { key.fill(0); plain.fill(0) }
            val payload = mapOf(
                "id" to id,
                "pairId" to pairId,
                "type" to record.type,
                "ciphertextB64" to ct,
                "nonceB64" to nonce,
                "senderUid" to uid,
                "createdAt" to System.currentTimeMillis(),
                "aadVersion" to 1
            )
            if (done != null) callbacks[id] = done
            io.execute {
                if (prefs.pairId != pairId || prefs.receiverPublicKey != receiverKey) {
                    callbacks.remove(id)?.invoke(false, "Pairing changed")
                } else {
                    SenderOutbox(activityOrService).put(id, pairId, payload)
                    flushPending()
                }
            }
        } catch (e: Exception) {
            done?.invoke(false, e.message)
        }
    }

    /** Firestore transactions fail offline; the encrypted outbox preserves offline delivery. */
    fun flushPending() {
        io.execute {
            if (draining) return@execute
            draining = true
            drainNext()
        }
    }
    private fun drainNext() {
        val pair = prefs.pairId
        val receiverKey = prefs.receiverPublicKey
        val uid = auth.currentUser?.uid
        if (pair == null || receiverKey == null || uid == null) { draining = false; return }
        val outbox = SenderOutbox(activityOrService)
        val payload = outbox.next(pair) ?: run { draining = false; return }
        val id = payload.getValue("id").toString()
        val ref = db.collection("pairs").document(pair)
        db.runTransaction { tx ->
            val snap = tx.get(ref)
            if (!snap.exists() || snap.getLong("disconnectedAtMs") != null ||
                snap.getString("collectorUid") != uid || snap.getString("receiverPublicKeyB64") != receiverKey ||
                snap.getString("collectorPublicKeyB64") != crypto.publicKeyB64()) {
                false
            } else {
                tx.set(ref.collection("queue").document(id), payload)
                true
            }
        }.addOnSuccessListener { valid ->
            io.execute {
                if (!valid) {
                    SenderPairState.disconnect(activityOrService, pair)
                    callbacks.remove(id)?.invoke(false, "Disconnected by Receiver")
                    draining = false
                } else {
                    outbox.delete(id)
                    callbacks.remove(id)?.invoke(true, null)
                    drainNext()
                }
            }
        }.addOnFailureListener {
            io.execute { draining = false } // retain encrypted payload until the next connection/sync
        }
    }
}
