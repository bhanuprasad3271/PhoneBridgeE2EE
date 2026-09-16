package com.phonebridge.collector

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.phonebridge.shared.CryptoBox
import com.phonebridge.shared.JsonCodec
import com.phonebridge.shared.PlainRecord
import java.util.UUID

class FirebaseRelay(private val activityOrService: android.content.Context) {
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
        val pairId = prefs.pairId ?: return done(false, "No pair id")
        val uid = auth.currentUser?.uid ?: return done(false, "Not authenticated")
        val data = mapOf(
            "collectorUid" to uid,
            "collectorPublicKeyB64" to crypto.publicKeyB64(),
            "collectorPairedAt" to FieldValue.serverTimestamp()
        )
        db.collection("pairs").document(pairId)
            .update(data)
            .addOnSuccessListener { done(true, null) }
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
            val (ct, nonce) = crypto.encrypt(key, JsonCodec.encode(record).toByteArray(), aad)
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
            db.collection("pairs").document(pairId)
                .collection("queue").document(id)
                .set(payload)
                .addOnSuccessListener { done?.invoke(true, null) }
                .addOnFailureListener { done?.invoke(false, it.message) }
        } catch (e: Exception) {
            done?.invoke(false, e.message)
        }
    }
}
