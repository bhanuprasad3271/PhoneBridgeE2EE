package com.phonebridge.collector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object LocationSharingScheduler {
    private const val PERIODIC_WORK = "phonebridge-location-sharing"
    private const val IMMEDIATE_WORK = "phonebridge-location-sharing-now"
    private const val SPECIAL_WORK = "phonebridge-location-sharing-special-text"
    const val SPECIAL_ATTEMPT = "specialRetryAttempt"

    fun hasForegroundLocation(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasBackgroundLocation(context: Context): Boolean {
        if (!hasForegroundLocation(context)) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun schedule(context: Context) {
        val prefs = BridgePrefs(context)
        if (!prefs.locationSharingEnabled || !hasBackgroundLocation(context)) {
            cancel(context)
            return
        }

        // Occasional/background sharing: no continuously running location foreground service.
        context.stopService(android.content.Intent(context, LocationSharingService::class.java))

        val periodic = PeriodicWorkRequestBuilder<LocationSyncWorker>(
            15, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            periodic
        )
    }

    fun requestImmediate(context: Context, trigger: String = "manual_refresh") {
        val prefs = BridgePrefs(context)
        if (!prefs.locationSharingEnabled || !hasBackgroundLocation(context)) return

        val request = OneTimeWorkRequestBuilder<LocationSyncWorker>()
            .setInputData(workDataOf("locationTrigger" to trigger))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            if (trigger == "special_text") SPECIAL_WORK else IMMEDIATE_WORK,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    // Append to the running special-text request; the next attempt starts only
    // after this one finishes. Android may postpone any scheduled delay.
    suspend fun scheduleSpecialRetry(context: Context, attempt: Int): Boolean =
        withContext(Dispatchers.IO) {
            val delayMinutes = when (attempt) {
                1, 2 -> 1L
                3, 4 -> 5L
                else -> 15L
            }
            val request = OneTimeWorkRequestBuilder<LocationSyncWorker>()
                .setInputData(workDataOf(
                    "locationTrigger" to "special_text",
                    SPECIAL_ATTEMPT to attempt
                ))
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
                .build()
            try {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    SPECIAL_WORK,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request
                ).result.get()
                true
            } catch (_: Exception) {
                false
            }
        }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(SPECIAL_WORK)
        context.stopService(android.content.Intent(context, LocationSharingService::class.java))
    }
}
