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
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return Result.failure()

        val prefs = BridgePrefs(applicationContext)
        if (prefs.pairId == null) return Result.failure()

        val pairedAt = prefs.pairedAtMs
        if (pairedAt <= 0L) return Result.failure()

        val relay = FirebaseRelay(applicationContext)
        val signedIn = suspendCancellableCoroutine<Boolean> { cont ->
            relay.ensureSignedIn { ok, _ -> if (cont.isActive) cont.resume(ok) }
        }
        if (!signedIn) return Result.retry()

        val projection = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME
        )

        val startAfter = maxOf(pairedAt, prefs.lastCallTimestamp)
        var newest = startAfter

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

            while (c.moveToNext()) {
                val date = c.getLong(d)
                if (date <= pairedAt) continue

                newest = maxOf(newest, date)
                val type = when (c.getInt(t)) {
                    CallLog.Calls.INCOMING_TYPE -> "incoming"
                    CallLog.Calls.OUTGOING_TYPE -> "outgoing"
                    CallLog.Calls.MISSED_TYPE -> "missed"
                    CallLog.Calls.REJECTED_TYPE -> "rejected"
                    else -> "other"
                }

                relay.send(
                    PlainRecord(
                        type = "call",
                        timestamp = date,
                        source = "phone",
                        title = c.getString(name) ?: c.getString(n).orEmpty(),
                        body = type,
                        metadata = mapOf(
                            "number" to c.getString(n).orEmpty(),
                            "callType" to type,
                            "durationSec" to c.getLong(dur).toString()
                        )
                    )
                )
            }
        }

        prefs.lastCallTimestamp = newest
        return Result.success()
    }
}
