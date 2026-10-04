package com.phonebridge.collector

import android.content.Context
import android.content.Intent
import androidx.work.WorkManager

object SenderPairState {
    const val ACTION_DISCONNECTED = "com.phonebridge.collector.PAIR_DISCONNECTED"
    /** Compare-and-clear: a delayed response for an old pair must never remove a new one. */
    fun disconnect(context: Context, expectedPair: String): Boolean = synchronized(this) {
        val prefs = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
        if (prefs.getString("pairId", null) != expectedPair) return@synchronized false
        check(prefs.edit().remove("pairId").remove("receiverPublicKey")
            .putBoolean("locationSharingEnabled", false).putLong("pairedAtMs", 0L)
            .putLong("lastCallTimestamp", 0L).remove("lastCallPermissionState")
            .remove("lastNotificationAccessState").remove("lastLocationPermissionState")
            .remove("lastReportedVersion").remove("lastSentLocationTimeMs")
            .remove("lastHandledLocationRequestId").remove("currentCallSessionId")
            .putLong("currentCallStartedAtMs", 0L).putString("currentCallStateHistory", "")
            .putString("currentCallNumber", "").commit())
        SenderOutbox(context).deletePair(expectedPair)
        for (name in listOf("phonebridge-location-sharing", "phonebridge-location-sharing-now", "phonebridge-location-sharing-special-text")) {
            WorkManager.getInstance(context).cancelUniqueWork(name)
        }
        // Secure identity is deliberately retained for the 6-hour Receiver recovery.
        context.sendBroadcast(Intent(ACTION_DISCONNECTED).setPackage(context.packageName))
        true
    }
}
