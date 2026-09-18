package com.phonebridge.collector

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.phonebridge.shared.PlainRecord
import java.util.concurrent.TimeUnit

class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return

        val prefs = BridgePrefs(context)
        if (prefs.pairId == null || prefs.receiverPublicKey == null || prefs.pairedAtMs <= 0L) return

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
                if (prefs.currentCallSessionId.isNullOrBlank()) {
                    prefs.currentCallStartedAtMs = System.currentTimeMillis()
                    prefs.currentCallSessionId = "phone_${prefs.currentCallStartedAtMs}"
                }
                if (number.isNotBlank()) prefs.currentCallNumber = number

                val previous = prefs.currentCallStateHistory
                val next = appendState(previous, "Incoming")
                if (next != previous) {
                    prefs.currentCallStateHistory = next
                    sendLiveCall(context, prefs, next)
                }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (prefs.currentCallSessionId.isNullOrBlank()) {
                    prefs.currentCallStartedAtMs = System.currentTimeMillis()
                    prefs.currentCallSessionId = "phone_${prefs.currentCallStartedAtMs}"
                }
                var previous = prefs.currentCallStateHistory
                if (previous.isBlank()) {
                    previous = "Outgoing"
                    prefs.currentCallStateHistory = previous
                }
                val next = appendState(previous, "Ongoing")
                if (next != previous) {
                    prefs.currentCallStateHistory = next
                    sendLiveCall(context, prefs, next)
                }
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                val sessionId = prefs.currentCallSessionId ?: return
                val previous = prefs.currentCallStateHistory
                if (previous.isBlank()) return

                val next = appendState(previous, "Ended")
                if (next != previous) {
                    prefs.currentCallStateHistory = next
                    sendLiveCall(context, prefs, next)
                }

                val request = OneTimeWorkRequestBuilder<CallLogWorker>()
                    .setInitialDelay(3, TimeUnit.SECONDS)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "finalize_$sessionId",
                    ExistingWorkPolicy.KEEP,
                    request
                )
            }
        }
    }

    private fun appendState(history: String, state: String): String {
        if (history.isBlank()) return state
        val parts = history.split(" → ")
        if (parts.lastOrNull() == state) return history
        return "$history → $state"
    }

    private fun sendLiveCall(context: Context, prefs: BridgePrefs, history: String) {
        val sessionId = prefs.currentCallSessionId ?: return
        val startedAt = prefs.currentCallStartedAtMs.takeIf { it > 0L } ?: System.currentTimeMillis()
        val number = prefs.currentCallNumber

        val relay = FirebaseRelay(context)
        relay.ensureSignedIn { ok, _ ->
            if (!ok) return@ensureSignedIn
            relay.send(
                PlainRecord(
                    type = "call",
                    timestamp = startedAt,
                    source = "phone",
                    title = number.ifBlank { "Phone call" },
                    body = history,
                    metadata = mapOf(
                        "sessionKey" to sessionId,
                        "number" to number,
                        "contactName" to "",
                        "callType" to history.substringBefore(" → "),
                        "stateHistory" to history,
                        "durationSec" to "0",
                        "phoneAccountId" to "",
                        "startedAtMs" to startedAt.toString(),
                        "liveUpdate" to "true"
                    )
                )
            )
        }
    }
}
