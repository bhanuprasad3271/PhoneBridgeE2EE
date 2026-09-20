package com.phonebridge.collector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object LocationSharingScheduler {
    private const val PERIODIC_WORK = "phonebridge-location-sharing"
    private const val IMMEDIATE_WORK = "phonebridge-location-sharing-now"

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

    fun requestImmediate(context: Context) {
        val prefs = BridgePrefs(context)
        if (!prefs.locationSharingEnabled || !hasBackgroundLocation(context)) return

        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<LocationSyncWorker>().build()
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE_WORK)
        context.stopService(android.content.Intent(context, LocationSharingService::class.java))
    }
}
