package com.phonebridge.collector

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.phonebridge.shared.CryptoBox

object PairAuthorization {
    fun active(context: Context, snap: DocumentSnapshot, pairId: String): Boolean {
        val prefs = BridgePrefs(context)
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return false
        return snap.exists() && !snap.metadata.isFromCache && snap.id == pairId && prefs.pairId == pairId &&
            snap.getLong("disconnectedAtMs") == null && snap.getString("collectorUid") == uid &&
            prefs.receiverPublicKey != null && snap.getString("receiverPublicKeyB64") == prefs.receiverPublicKey &&
            snap.getString("collectorPublicKeyB64") == CryptoBox(context, "phonebridge_collector_ecdh").publicKeyB64()
    }
}
