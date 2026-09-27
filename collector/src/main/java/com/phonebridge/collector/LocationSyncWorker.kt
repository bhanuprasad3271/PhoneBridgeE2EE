package com.phonebridge.collector

import android.content.Context
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

    private companion object {
        const val FIX_TIMEOUT_MS = 30_000L
        const val CLOCK_TOLERANCE_MS = 10_000L
    }

    override suspend fun doWork(): Result {
        val prefs = BridgePrefs(applicationContext)
        if (!prefs.locationSharingEnabled) return Result.success()
        val pairId = prefs.pairId ?: return Result.success()
        if (prefs.receiverPublicKey == null) return Result.success()

        val relay = FirebaseRelay(applicationContext)
        val signedIn = suspendCancellableCoroutine<Boolean> { cont ->
            relay.ensureSignedIn { ok, _ -> if (cont.isActive) cont.resume(ok) }
        }
        if (!signedIn) return Result.retry()

        // In occasional mode Receiver refresh is queued. The next WorkManager run
        // sees the request and returns that run's location as the requested update.
        val requestSnapshot = suspendCancellableCoroutine<DocumentSnapshot?> { cont ->
            FirebaseFirestore.getInstance().collection("pairs").document(pairId).get()
                .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
                .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        }
        val requestedTrigger = inputData.getString("locationTrigger").orEmpty()
        val specialTextTrigger = requestedTrigger == "special_text"
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

        // A refresh must contain a fix measured for this attempt. getCurrentLocation
        // can otherwise serve a cached fix, and falling back to lastLocation can
        // silently turn a refresh into an old position.
        val startedAt = System.currentTimeMillis()
        val fused = LocationServices.getFusedLocationProviderClient(applicationContext)
        val request = CurrentLocationRequest.Builder()
            .setPriority(
                if (requestId != null || specialTextTrigger) Priority.PRIORITY_HIGH_ACCURACY
                else Priority.PRIORITY_BALANCED_POWER_ACCURACY
            )
            .setMaxUpdateAgeMillis(0L)
            .setDurationMillis(FIX_TIMEOUT_MS)
            .build()
        val token = CancellationTokenSource()
        val location = try {
            fused.getCurrentLocation(request, token.token).awaitOrNull()
                ?.takeIf { it.time > 0L && it.time >= startedAt - CLOCK_TOLERANCE_MS }
        } catch (_: SecurityException) {
            null
        } finally {
            token.cancel()
        }

        if (location == null) {
            if (requestId != null) updateRequestStatus(pairId, requestId, "location_failed")
            return Result.success()
        }

        if (requestId != null) updateRequestStatus(pairId, requestId, "encrypting_sending")

        val now = System.currentTimeMillis()
        val timestamp = location.time
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
                "trigger" to when {
                    specialTextTrigger -> "special_text"
                    requestId != null -> "manual_refresh"
                    else -> "periodic"
                },
                "requestId" to (requestId ?: "")
            )
        )

        val sent = suspendCancellableCoroutine<Boolean> { cont ->
            relay.send(record) { ok, _ -> if (cont.isActive) cont.resume(ok) }
        }

        if (requestId != null) {
            if (sent) {
                prefs.lastHandledLocationRequestId = requestId
                updateRequestStatus(pairId, requestId, "sent")
            } else {
                updateRequestStatus(pairId, requestId, "send_failed")
            }
        }

        return if (sent) Result.success() else Result.retry()
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
