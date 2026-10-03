package com.phonebridge.collector

import android.content.Context
import androidx.work.WorkManager

object SenderEdition {
    fun onProcessStart(context: Context) {
        // Upgrade cleanup only. Keep pairing, identity, call state and delivery data.
        BridgePrefs(context).locationSharingEnabled = false
        val work = WorkManager.getInstance(context)
        for (name in listOf("phonebridge-location-sharing", "phonebridge-location-sharing-now",
            "phonebridge-location-sharing-special-text")) {
            work.cancelUniqueWork(name)
        }
    }
}
