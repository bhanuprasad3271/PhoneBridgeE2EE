package com.phonebridge.collector

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.phonebridge.shared.PlainRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

class LocationSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private companion object {
        const val FIX_TIMEOUT_MS = 30_000L
        const val CLOCK_TOLERANCE_MS = 10_000L
        const val RECENT_CACHE_AGE_MS = 60_000L
        const val MAX_INTERIM_AGE_MS = 15 * 60_000L
        val sendMutex = Mutex()
    }

    override suspend fun doWork(): Result {
        val prefs = BridgePrefs(applicationContext)
        if (!prefs.locationSharingEnabled) return Result.success()
        val pairId = prefs.pairId ?: return Result.success()
        if (prefs.receiverPublicKey == null) return Result.success()

        val specialTextTrigger = inputData.getString("locationTrigger") == "special_text"
        val relay = FirebaseRelay(applicationContext)
        val signedIn = suspendCancellableCoroutine<Boolean> { cont ->
            relay.ensureSignedIn { ok, _ -> if (cont.isActive) cont.resume(ok) }
        }
        if (!signedIn) return retryPending(specialTextTrigger)

        // In occasional mode Receiver refresh is queued. The next WorkManager run
        // sees the request and returns that run's location as the requested update.
        val requestSnapshot = suspendCancellableCoroutine<DocumentSnapshot?> { cont ->
            FirebaseFirestore.getInstance().collection("pairs").document(pairId).get()
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
        val requestId = if (specialTextTrigger) {
            null
        } else {
            requestSnapshot?.getString("locationRequestId")
                ?.takeIf { it.isNotBlank() && it != prefs.lastHandledLocationRequestId }
        }

        if (requestId != null) updateRequestStatus(pairId, requestId, "sender_received")
        if (!LocationSharingScheduler.hasBackgroundLocation(applicationContext)) {
            if (requestId != null) updateRequestStatus(pairId, requestId, "location_unavailable")
            return Result.success()
        }
        val locationManager = applicationContext.getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !locationManager.isLocationEnabled) {
            if (requestId != null) updateRequestStatus(pairId, requestId, "location_unavailable")
            return Result.success()
        }
        if (requestId != null) updateRequestStatus(pairId, requestId, "getting_location")

        val startedAt = System.currentTimeMillis()
        val fused = LocationServices.getFusedLocationProviderClient(applicationContext)
        val lastSentAt = prefs.lastSentLocationTimeMs
        // Android can return null before the request's duration expires. Keep
        // requesting until the 30-second window ends or a new fix arrives.
        val measured = seekNewFix(fused, startedAt, lastSentAt,
            if (requestId != null || specialTextTrigger) Priority.PRIORITY_HIGH_ACCURACY
            else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
        val now = System.currentTimeMillis()
        val newFix = measured?.takeIf {
            it.time > lastSentAt && it.time >= startedAt - CLOCK_TOLERANCE_MS &&
                it.time <= now + CLOCK_TOLERANCE_MS
        }
        // After the 30-second attempt, a cache younger than one minute is
        // acceptable as final. Older caches are interim only, never "fresh".
        val cached = if (newFix == null) {
            val last = try { fused.lastLocation.awaitOrNull() }
                catch (_: SecurityException) { null }
            listOfNotNull(measured, last).filter {
                it.time > lastSentAt && it.time <= now &&
                    now - it.time < MAX_INTERIM_AGE_MS
            }.maxByOrNull { it.time }
        } else null
        val candidate = newFix ?: cached
        val freshness = when {
            newFix != null -> "fresh"
            candidate != null && now - candidate.time < RECENT_CACHE_AGE_MS -> "cached_recent"
            candidate != null -> "cached_interim"
            else -> null
        }

        if (candidate == null || freshness == null) {
            if (requestId != null) updateRequestStatus(pairId, requestId, "waiting_fresh")
            // WorkManager will retry while the request is pending. Normal periodic
            // work will also try again; neither keeps GPS running continuously.
            return when {
                specialTextTrigger -> retryPending(true)
                requestId != null -> Result.retry()
                else -> Result.success()
            }
        }

        if (requestId != null && freshness != "cached_interim") {
            updateRequestStatus(pairId, requestId, "encrypting_sending")
        }
        val record = PlainRecord(
            type = "location",
            timestamp = candidate.time,
            source = "sender",
            title = "Sender location",
            body = "Encrypted background location update",
            metadata = mapOf(
                "latitude" to String.format(Locale.US, "%.6f", candidate.latitude),
                "longitude" to String.format(Locale.US, "%.6f", candidate.longitude),
                "accuracyMeters" to String.format(Locale.US, "%.1f", candidate.accuracy),
                "provider" to (candidate.provider ?: "fused"),
                "locationTimeMs" to candidate.time.toString(),
                "collectedAtMs" to now.toString(),
                "locationFreshness" to freshness,
                "trigger" to when {
                    specialTextTrigger -> "special_text"
                    requestId != null -> "manual_refresh"
                    else -> "periodic"
                },
                "requestId" to (requestId ?: "")
            )
        )
        // Periodic and explicit workers may run together. Serialize the check
        // and send so they cannot both report the same measured fix as new.
        val outcome = sendMutex.withLock {
            if (candidate.time <= prefs.lastSentLocationTimeMs) {
                SendOutcome.ALREADY_SENT
            } else {
                val sent = suspendCancellableCoroutine<Boolean> { cont ->
                    relay.send(record) { ok, _ -> if (cont.isActive) cont.resume(ok) }
                }
                if (sent) {
                    prefs.lastSentLocationTimeMs = candidate.time
                    SendOutcome.SENT
                } else SendOutcome.FAILED
            }
        }
        if (outcome != SendOutcome.SENT) {
            if (requestId != null) {
                updateRequestStatus(pairId, requestId,
                    if (outcome == SendOutcome.FAILED) "send_failed" else "waiting_fresh")
            }
            return when {
                specialTextTrigger -> retryPending(true)
                requestId != null || outcome == SendOutcome.FAILED -> Result.retry()
                else -> Result.success()
            }
        }

        if (requestId != null) {
            if (freshness == "cached_interim") {
                updateRequestStatus(pairId, requestId, "interim_sent")
            } else {
                prefs.lastHandledLocationRequestId = requestId
                updateRequestStatus(pairId, requestId, "sent")
            }
        }
        return when {
            freshness != "cached_interim" -> Result.success()
            specialTextTrigger -> retryPending(true)
            requestId != null -> Result.retry()
            else -> Result.success()
        }
    }

    private enum class SendOutcome { SENT, FAILED, ALREADY_SENT }

    private suspend fun retryPending(specialTextTrigger: Boolean): Result {
        if (!specialTextTrigger) return Result.retry()
        val nextAttempt = inputData.getInt(LocationSharingScheduler.SPECIAL_ATTEMPT, 0) + 1
        return if (LocationSharingScheduler.scheduleSpecialRetry(applicationContext, nextAttempt)) {
            Result.success()
        } else Result.retry()
    }

    private suspend fun seekNewFix(
        fused: FusedLocationProviderClient,
        startedAt: Long,
        lastSentAt: Long,
        priority: Int
    ): Location? {
        val deadline = startedAt + FIX_TIMEOUT_MS
        var bestCached: Location? = null
        while (System.currentTimeMillis() < deadline && !isStopped) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            val token = CancellationTokenSource()
            val request = CurrentLocationRequest.Builder()
                .setPriority(priority)
                .setMaxUpdateAgeMillis(0L)
                .setDurationMillis(remaining)
                .build()
            val candidate = try {
                withTimeoutOrNull(remaining) {
                    fused.getCurrentLocation(request, token.token).awaitOrNull()
                }
            } catch (_: SecurityException) {
                null
            } finally {
                token.cancel()
            }
            val now = System.currentTimeMillis()
            if (candidate != null && candidate.time > lastSentAt &&
                candidate.time >= startedAt - CLOCK_TOLERANCE_MS &&
                candidate.time <= now + CLOCK_TOLERANCE_MS
            ) return candidate
            if (candidate != null && candidate.time > (bestCached?.time ?: 0L)) {
                bestCached = candidate
            }
            val rest = deadline - System.currentTimeMillis()
            if (rest > 0L) delay(minOf(3_000L, rest))
        }
        return bestCached
    }

    private fun updateRequestStatus(pairId: String, requestId: String, status: String) {
        FirebaseFirestore.getInstance()
            .collection("pairs")
            .document(pairId)
            .update(
                mapOf(
                    "locationRequestStatusId" to requestId,
                    "locationRequestStatus" to status,
                    "locationRequestStatusAtMs" to System.currentTimeMillis()
                )
            )
    }

    private suspend fun <T> Task<T>.awaitOrNull(): T? =
        suspendCancellableCoroutine { cont ->
            addOnSuccessListener { value -> if (cont.isActive) cont.resume(value) }
            addOnFailureListener { if (cont.isActive) cont.resume(null) }
            addOnCanceledListener { if (cont.isActive) cont.resume(null) }
        }
}

