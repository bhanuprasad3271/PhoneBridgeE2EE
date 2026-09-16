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
    var lastCallTimestamp: Long
        get() = p.getLong("lastCallTimestamp", 0L)
        set(v) = p.edit().putLong("lastCallTimestamp", v).apply()
}
