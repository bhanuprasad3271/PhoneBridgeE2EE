package com.phonebridge.collector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.Task
import com.phonebridge.shared.PlainRecord
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

class LocationSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = BridgePrefs(applicationContext)
        if (!prefs.locationSharingEnabled) return Result.success()
        if (prefs.pairId == null || prefs.receiverPublicKey == null) return Result.success()
        if (!LocationSharingScheduler.hasBackgroundLocation(applicationContext)) {
            return Result.success()
        }

        val locationManager = applicationContext.getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !locationManager.isLocationEnabled) {
            return Result.success()
        }

        val fused = LocationServices.getFusedLocationProviderClient(applicationContext)

        val last = try {
            fused.lastLocation.awaitOrNull()
        } catch (_: SecurityException) {
            null
        }

        val now = System.currentTimeMillis()
        val usableLast = last?.takeIf {
            it.time > 0L && now - it.time <= 15 * 60 * 1000L
        }

        val location = usableLast ?: try {
            val token = CancellationTokenSource()
            fused.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                token.token
            ).awaitOrNull() ?: last
        } catch (_: SecurityException) {
            last
        }

        if (location == null) return Result.success()

        val relay = FirebaseRelay(applicationContext)
        val signedIn = suspendCancellableCoroutine<Boolean> { cont ->
            relay.ensureSignedIn { ok, _ ->
                if (cont.isActive) cont.resume(ok)
            }
        }
        if (!signedIn) return Result.retry()

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
                "collectedAtMs" to now.toString()
            )
        )

        val sent = suspendCancellableCoroutine<Boolean> { cont ->
            relay.send(record) { ok, _ ->
                if (cont.isActive) cont.resume(ok)
            }
        }

        return if (sent) Result.success() else Result.retry()
    }

    private suspend fun <T> Task<T>.awaitOrNull(): T? =
        suspendCancellableCoroutine { cont ->
            addOnSuccessListener { value ->
                if (cont.isActive) cont.resume(value)
            }
            addOnFailureListener {
                if (cont.isActive) cont.resume(null)
            }
            addOnCanceledListener {
                if (cont.isActive) cont.resume(null)
            }
        }
}
