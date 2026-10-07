package com.phonebridge.collector

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges

/** Runs only while Android keeps Sender's process alive; no foreground service. */
object SenderPairMonitor {
    private var registration: ListenerRegistration? = null
    private var context: Context? = null
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "pairId" || key == "receiverPublicKey") context?.let { watch(it) }
    }
    fun start(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        com.phonebridge.shared.SecurePreferences.get(context, "bridge").registerOnSharedPreferenceChangeListener(changed)
        watch(context.applicationContext)
    }
    private fun watch(context: Context) {
        registration?.remove(); registration = null
        val prefs = BridgePrefs(context)
        val pair = prefs.pairId ?: return
        if (prefs.receiverPublicKey == null) return
        FirebaseRelay(context).ensureSignedIn { ok, _ ->
            if (!ok || prefs.pairId != pair || prefs.receiverPublicKey == null) return@ensureSignedIn
            registration = FirebaseFirestore.getInstance().collection("pairs").document(pair).addSnapshotListener(MetadataChanges.INCLUDE) { snap, error ->
                if (error != null || snap == null || snap.metadata.isFromCache || prefs.pairId != pair) return@addSnapshotListener
                if (!snap.exists() || snap.getLong("disconnectedAtMs") != null || snap.getString("receiverPublicKeyB64") != prefs.receiverPublicKey ||
                    snap.getString("collectorUid") != com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid ||
                    snap.getString("collectorPublicKeyB64") != com.phonebridge.shared.CryptoBox(context, "phonebridge_collector_ecdh").publicKeyB64()) SenderPairState.disconnect(context, pair)
                else FirebaseRelay(context).flushPending()
            }
        }
    }
}
