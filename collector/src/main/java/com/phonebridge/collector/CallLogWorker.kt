package com.phonebridge.collector

import android.Manifest
import android.content.pm.PackageManager
import android.provider.CallLog
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.phonebridge.shared.PlainRecord
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class CallLogWorker(appContext: android.content.Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = BridgePrefs(applicationContext)
        if (prefs.pairId == null || prefs.receiverPublicKey == null) return Result.failure()

        val relay = FirebaseRelay(applicationContext)
        val signedIn = suspendCancellableCoroutine<Boolean> { cont ->
            relay.ensureSignedIn { ok, _ -> if (cont.isActive) cont.resume(ok) }
        }
        if (!signedIn) return Result.retry()

        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            relay.send(
                PlainRecord(
                    type = "alert",
                    timestamp = System.currentTimeMillis(),
                    source = "collector",
                    title = "Call log permission removed",
                    body = "Phone 2 can no longer read new call-log entries.",
                    metadata = mapOf("severity" to "warning", "kind" to "call_permission")
                )
            )
            return Result.failure()
        }

        val pairedAt = prefs.pairedAtMs
        if (pairedAt <= 0L) return Result.failure()

        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.PHONE_ACCOUNT_ID
        )

        val startAfter = maxOf(pairedAt, prefs.lastCallTimestamp)
        var newest = startAfter
        var sentAny = false
        val observedHistory = prefs.currentCallStateHistory

        applicationContext.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            projection,
            "${CallLog.Calls.DATE} > ?",
            arrayOf(startAfter.toString()),
            "${CallLog.Calls.DATE} ASC"
        )?.use { c ->
            val n = c.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val t = c.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val d = c.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val dur = c.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val name = c.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
            val account = c.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)

            while (c.moveToNext()) {
                val date = c.getLong(d)
                if (date <= pairedAt) continue

                newest = maxOf(newest, date)
                val type = when (c.getInt(t)) {
                    CallLog.Calls.INCOMING_TYPE -> "Incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                    CallLog.Calls.MISSED_TYPE -> "Missed"
                    CallLog.Calls.REJECTED_TYPE -> "Rejected"
                    CallLog.Calls.BLOCKED_TYPE -> "Blocked"
                    else -> "Other"
                }

                val number = c.getString(n).orEmpty()
                val contactName = c.getString(name).orEmpty()
                val durationSec = c.getLong(dur)
                val accountId = if (account >= 0) c.getString(account).orEmpty() else ""
                val stateHistory = buildStateHistory(type, durationSec, observedHistory)

                relay.send(
                    PlainRecord(
                        type = "call",
                        timestamp = date,
                        source = "phone",
                        title = contactName.ifBlank { number.ifBlank { "Unknown caller" } },
                        body = stateHistory,
                        metadata = mapOf(
                            "number" to number,
                            "contactName" to contactName,
                            "callType" to type,
                            "stateHistory" to stateHistory,
                            "durationSec" to durationSec.toString(),
                            "phoneAccountId" to accountId,
                            "startedAtMs" to date.toString()
                        )
                    )
                )
                sentAny = true
            }
        }

        prefs.lastCallTimestamp = newest
        if (sentAny) {
            prefs.currentCallStateHistory = ""
            prefs.currentCallStartedAtMs = 0L
        }
        return Result.success()
    }

    private fun buildStateHistory(type: String, durationSec: Long, observed: String): String {
        return when (type) {
            "Missed" -> "Incoming → Missed"
            "Rejected" -> "Incoming → Rejected"
            "Blocked" -> "Blocked"
            "Incoming" -> {
                if (durationSec > 0) {
                    when {
                        observed.startsWith("Incoming") && "Ongoing" in observed ->
                            ensureEnded(observed)
                        else -> "Incoming → Ongoing → Ended"
                    }
                } else {
                    "Incoming → Ended"
                }
            }
            "Outgoing" -> {
                if (durationSec > 0) {
                    when {
                        observed.startsWith("Outgoing") && "Ongoing" in observed ->
                            ensureEnded(observed)
                        else -> "Outgoing → Ongoing → Ended"
                    }
                } else {
                    "Outgoing → Ended"
                }
            }
            else -> observed.ifBlank { type }
        }
    }

    private fun ensureEnded(history: String): String =
        if ("Ended" in history) history else "$history → Ended"
}
