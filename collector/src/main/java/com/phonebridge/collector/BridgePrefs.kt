package com.phonebridge.collector

import android.content.Context

class BridgePrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("bridge", Context.MODE_PRIVATE)

    var pairId: String?
        get() = p.getString("pairId", null)
        set(v) = p.edit().putString("pairId", v).apply()

    var receiverPublicKey: String?
        get() = p.getString("receiverPublicKey", null)
        set(v) = p.edit().putString("receiverPublicKey", v).apply()

    var pairedAtMs: Long
        get() = p.getLong("pairedAtMs", 0L)
        set(v) = p.edit().putLong("pairedAtMs", v).apply()

    var lastCallTimestamp: Long
        get() = p.getLong("lastCallTimestamp", 0L)
        set(v) = p.edit().putLong("lastCallTimestamp", v).apply()

    var lastCallPermissionState: String?
        get() = p.getString("lastCallPermissionState", null)
        set(v) = p.edit().putString("lastCallPermissionState", v).apply()

    var lastNotificationAccessState: String?
        get() = p.getString("lastNotificationAccessState", null)
        set(v) = p.edit().putString("lastNotificationAccessState", v).apply()

    var lastReportedVersion: String?
        get() = p.getString("lastReportedVersion", null)
        set(v) = p.edit().putString("lastReportedVersion", v).apply()

    var currentCallStateHistory: String
        get() = p.getString("currentCallStateHistory", "").orEmpty()
        set(v) = p.edit().putString("currentCallStateHistory", v).apply()

    var currentCallStartedAtMs: Long
        get() = p.getLong("currentCallStartedAtMs", 0L)
        set(v) = p.edit().putLong("currentCallStartedAtMs", v).apply()
}
