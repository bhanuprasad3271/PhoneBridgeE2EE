package com.phonebridge.collector

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.WorkManager

object LocationSharingScheduler {
    private const val LEGACY_PERIODIC_WORK = "phonebridge-location-sharing"
    private const val LEGACY_IMMEDIATE_WORK = "phonebridge-location-sharing-now"

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

        cancelLegacyWork(context)

        val intent = Intent(context, LocationSharingService::class.java)
            .setAction(LocationSharingService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    fun requestImmediate(context: Context) {
        val prefs = BridgePrefs(context)
        if (!prefs.locationSharingEnabled || !hasBackgroundLocation(context)) return

        val intent = Intent(context, LocationSharingService::class.java)
            .setAction(LocationSharingService.ACTION_REFRESH)
        ContextCompat.startForegroundService(context, intent)
    }

    fun cancel(context: Context) {
        cancelLegacyWork(context)
        context.stopService(Intent(context, LocationSharingService::class.java))
    }

    private fun cancelLegacyWork(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_PERIODIC_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_IMMEDIATE_WORK)
    }
}
