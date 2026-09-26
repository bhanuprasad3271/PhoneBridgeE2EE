package com.phonebridge.collector

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.phonebridge.shared.PlainRecord
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

class LocationSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val FRESH_LOCATION_MAX_AGE_MS = 30_000L
        private const val FRESH_FIX_TIMEOUT_MS = 30_000L
    }

    override suspend fun doWork(): Result {
        val prefs = BridgePrefs(applicationContext)
        if (!prefs.locationSharingEnabled) return Result.success()
        val pairId = prefs.pairId ?: return Result.success()
        if (prefs.receiverPublicKey == null) return Result.success()
        if (!LocationSharingScheduler.hasBackgroundLocation(applicationContext)) return Result.success()

        val locationManager = applicationContext.getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !locationManager.isLocationEnabled) {
            return Result.success()
        }

        val relay = FirebaseRelay(applicationContext)
        val signedIn = suspendCancellableCoroutine<Boolean> { cont ->
            relay.ensureSignedIn { ok, _ -> if (cont.isActive) cont.resume(ok) }
        }
        if (!signedIn) return Result.retry()

        val requestSnapshot = suspendCancellableCoroutine<DocumentSnapshot?> { cont ->
            FirebaseFirestore.getInstance().collection("pairs").document(pairId).get()
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
        val requestedTrigger = inputData.getString("locationTrigger").orEmpty()
        val specialTextTrigger = requestedTrigger == "special_text"
        val requestId = if (specialTextTrigger) null else requestSnapshot?.getString("locationRequestId")
            ?.takeIf { it.isNotBlank() && it != prefs.lastHandledLocationRequestId }

        if (requestId != null) {
            updateRequestStatus(pairId, requestId, "sender_received")
            updateRequestStatus(pairId, requestId, "getting_location")
        }

        val trigger = when {
            specialTextTrigger -> "special_text"
            requestId != null -> "manual_refresh"
            else -> "periodic"
        }

        val fused = LocationServices.getFusedLocationProviderClient(applicationContext)
        val cached = try { fused.lastLocation.awaitOrNull() } catch (_: SecurityException) { null }

        // Always ask for a new high-accuracy fix first. Google Play services may still return a
        // recently acquired fix, so only accept it as "fresh" when it is <= 30 seconds old.
        val firstFresh = requestFreshFix(fused)
        if (isFresh(firstFresh)) {
            if (requestId != null) updateRequestStatus(pairId, requestId, "encrypting_sending")
            val sent = sendLocation(relay, firstFresh!!, trigger, requestId, "fresh")
            finishRequest(pairId, requestId, sent)
            return if (sent) Result.success() else Result.retry()
        }

        // Fresh acquisition failed/timed out. Only now may cache be used.
        val fallback = cached ?: firstFresh
        if (fallback == null) {
            if (requestId != null) updateRequestStatus(pairId, requestId, "location_failed")
            return Result.retry()
        }

        if (requestId != null) updateRequestStatus(pairId, requestId, "encrypting_sending")
        val cachedSent = sendLocation(relay, fallback, trigger, requestId, "cached_fallback")
        if (!cachedSent) {
            finishRequest(pairId, requestId, false)
            return Result.retry()
        }

        // The cached point is only an interim answer. Keep this worker alive for one more fresh
        // acquisition window and send the fresh point as a second queue record as soon as it arrives.
        if (requestId != null) updateRequestStatus(pairId, requestId, "cached_sent_waiting_fresh")
        val laterFresh = requestFreshFix(fused)
        if (isFresh(laterFresh)) {
            val freshSent = sendLocation(relay, laterFresh!!, trigger, requestId, "fresh_after_cache")
            finishRequest(pairId, requestId, freshSent)
            return if (freshSent) Result.success() else Result.retry()
        }

        // Do not mark the request handled yet: a later WorkManager run can continue looking for
        // the requested fresh point without requiring another Receiver refresh.
        if (requestId != null) updateRequestStatus(pairId, requestId, "cached_sent_fresh_pending")
        return Result.retry()
    }

    private suspend fun requestFreshFix(
        fused: com.google.android.gms.location.FusedLocationProviderClient
    ): Location? = try {
        val token = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0L)
            .setDurationMillis(FRESH_FIX_TIMEOUT_MS)
            .build()
        fused.getCurrentLocation(request, token.token).awaitOrNull()
    } catch (_: SecurityException) {
        null
    }

    private fun isFresh(location: Location?): Boolean {
        if (location == null || location.time <= 0L) return false
        val age = System.currentTimeMillis() - location.time
        return age in 0..FRESH_LOCATION_MAX_AGE_MS
    }

    private suspend fun sendLocation(
        relay: FirebaseRelay,
        location: Location,
        trigger: String,
        requestId: String?,
        freshness: String
    ): Boolean {
        val now = System.currentTimeMillis()
        val timestamp = if (location.time > 0L) location.time else now
        val record = PlainRecord(
            type = "location",
            timestamp = timestamp,
            source = "sender",
            title = "Sender location",
            body = "Encrypted background location update",
            metadata = mapOf(
                "latitude" to String.format(Locale.US, "%.6f", location.latitude),
                "longitude" to String.format(Locale.US, "%.6f", location.longitude),
                "accuracyMeters" to String.format(Locale.US, "%.1f", location.accuracy),
                "provider" to (location.provider ?: "fused"),
                "locationTimeMs" to timestamp.toString(),
                "collectedAtMs" to now.toString(),
                "trigger" to trigger,
                "freshness" to freshness,
                "requestId" to (requestId ?: "")
            )
        )
        return suspendCancellableCoroutine { cont ->
            relay.send(record) { ok, _ -> if (cont.isActive) cont.resume(ok) }
        }
    }

    private fun finishRequest(pairId: String, requestId: String?, sent: Boolean) {
        if (requestId == null) return
        if (sent) {
            BridgePrefs(applicationContext).lastHandledLocationRequestId = requestId
            updateRequestStatus(pairId, requestId, "sent")
        } else {
            updateRequestStatus(pairId, requestId, "send_failed")
        }
    }

    private fun updateRequestStatus(pairId: String, requestId: String, status: String) {
        FirebaseFirestore.getInstance().collection("pairs").document(pairId).update(
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
