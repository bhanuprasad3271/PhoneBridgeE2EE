package com.phonebridge.collector

import android.content.Context
import com.phonebridge.shared.PlainRecord

/** One complete snapshot, ordered independently of changes to the phone clock. */
object SenderStatusReport {
    fun nextRevision(context: Context): Long = synchronized(this) {
        val p = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
        val next = p.getLong("senderStateRevision", 0L) + 1L
        check(p.edit().putLong("senderStateRevision", next).commit())
        next
    }
    fun create(context: Context, version: String, calls: String, notifications: String,
        location: String): PlainRecord {
        val revision = nextRevision(context)
        return PlainRecord(type = "status", timestamp = System.currentTimeMillis(), source = "sender",
            title = "Sender app status", body = "Sender version $version is connected.",
            metadata = mapOf("appVersion" to version, "githubBuild" to BuildConfig.GITHUB_BUILD,
                "senderEdition" to context.getString(R.string.sender_edition),
                "supportsLocation" to (context.getString(R.string.sender_edition) == "full").toString(),
                "callSync" to calls, "notificationAccess" to notifications,
                "locationSharing" to location, "senderStateRevision" to revision.toString()))
    }

    fun acknowledge(context: Context, pair: String, receiverKey: String, record: PlainRecord) = synchronized(this) {
        val p = context.getSharedPreferences("bridge", Context.MODE_PRIVATE)
        // An old delivery callback must not confirm another pair or a newer state.
        if (p.getString("pairId", null) == pair && p.getString("receiverPublicKey", null) == receiverKey &&
            p.getLong("senderStateRevision", 0L) == record.metadata["senderStateRevision"]?.toLongOrNull()) {
            p.edit().putString("lastReportedVersion", record.metadata["appVersion"])
                .putString("lastCallPermissionState", record.metadata["callSync"])
                .putString("lastNotificationAccessState", record.metadata["notificationAccess"])
                .putString("lastLocationPermissionState", record.metadata["locationSharing"]).apply()
        }
    }
}
