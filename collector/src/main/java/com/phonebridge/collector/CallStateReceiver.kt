package com.phonebridge.collector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return

        val prefs = BridgePrefs(context)
        if (prefs.pairId == null || prefs.receiverPublicKey == null || prefs.pairedAtMs <= 0L) return

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                prefs.currentCallStartedAtMs = System.currentTimeMillis()
                prefs.currentCallStateHistory = "Incoming"
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (prefs.currentCallStartedAtMs == 0L) {
                    prefs.currentCallStartedAtMs = System.currentTimeMillis()
                }
                val existing = prefs.currentCallStateHistory
                prefs.currentCallStateHistory = when {
                    existing.isBlank() -> "Outgoing → Ongoing"
                    "Ongoing" in existing -> existing
                    else -> "$existing → Ongoing"
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                val existing = prefs.currentCallStateHistory
                if (existing.isNotBlank() && "Ended" !in existing) {
                    prefs.currentCallStateHistory = "$existing → Ended"
                }

                val request = OneTimeWorkRequestBuilder<CallLogWorker>()
                    .setInitialDelay(3, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueue(request)
            }
        }
    }
}
