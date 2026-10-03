package com.phonebridge.collector

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog

class MainActivity : SenderActivity() {
    private lateinit var locationSharingStatus: TextView
    private lateinit var locationSharingButton: Button
    private val locationPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            LocationSharingScheduler.hasForegroundLocation(this)

        if (!granted) {
            prefs.locationSharingEnabled = false
            LocationSharingScheduler.cancel(this)
            updateLocationSharingUi()
            return@registerForActivityResult
        }

        prefs.locationSharingEnabled = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !LocationSharingScheduler.hasBackgroundLocation(this)
        ) {
            showBackgroundLocationInstructions()
        } else {
            activateLocationSharing()
        }
    }


    override fun bindEditionUi() {
        locationSharingStatus = findViewById(R.id.locationSharingStatus)
        locationSharingButton = findViewById(R.id.btnLocationSharing)
        locationSharingButton.setOnClickListener {
            when {
                prefs.locationSharingEnabled &&
                    LocationSharingScheduler.hasBackgroundLocation(this) -> {
                    AlertDialog.Builder(this)
                        .setTitle("Stop location sharing?")
                        .setMessage("PhoneBridge will stop the location service and stop sending location updates.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Stop sharing") { _, _ ->
                            prefs.locationSharingEnabled = false
                            LocationSharingScheduler.cancel(this)
                            updateLocationSharingUi()
                            reportSenderState(force = true)
                        }
                        .show()
                }

                prefs.locationSharingEnabled &&
                    LocationSharingScheduler.hasForegroundLocation(this) -> {
                    showBackgroundLocationInstructions()
                }

                else -> {
                    startLocationSharingSetup()
                }
            }
        }


    }
    override fun updateEditionUi() = updateLocationSharingUi()
    override fun stopLocationSharing() = LocationSharingScheduler.cancel(this)
    override fun locationState(): String = when {
        prefs.locationSharingEnabled && LocationSharingScheduler.hasBackgroundLocation(this) -> "enabled"
        prefs.locationSharingEnabled -> "needs_permission"
        else -> "off"
    }

    private fun startLocationSharingSetup() {
        AlertDialog.Builder(this)
            .setTitle("Enable Location Sharing")
            .setMessage(
                "PhoneBridge Sender will use Android background work for occasional location sharing after this app is closed. " +
                    "It will request an encrypted location about every 15 minutes when Android allows background work. " +
                    "Updates are best-effort and can be delayed by Android battery/Doze restrictions; no continuous location foreground service is kept running."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                val permissions = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                locationPermissions.launch(permissions.toTypedArray())
            }
            .show()
    }

    private fun showBackgroundLocationInstructions() {
        AlertDialog.Builder(this)
            .setTitle("Allow background location")
            .setMessage(
                "For occasional sharing after Sender is closed, Android requires 'Allow all the time'. " +
                    "On the next screen open Permissions > Location and choose Allow all the time, then return to PhoneBridge."
            )
            .setNegativeButton("Not now") { _, _ -> updateLocationSharingUi() }
            .setPositiveButton("Open app settings") { _, _ ->
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            .show()
    }

    private fun activateLocationSharing() {
        if (!prefs.locationSharingEnabled) return
        if (!LocationSharingScheduler.hasBackgroundLocation(this)) {
            updateLocationSharingUi()
            return
        }
        LocationSharingScheduler.schedule(this)
        updateLocationSharingUi()
        reportSenderState(force = true)
    }

    private fun updateLocationSharingUi() {
        if (!::locationSharingStatus.isInitialized || !::locationSharingButton.isInitialized) return

        val enabled = prefs.locationSharingEnabled
        val backgroundGranted = LocationSharingScheduler.hasBackgroundLocation(this)

        when {
            !enabled -> {
                locationSharingStatus.text = "Location sharing is off"
                locationSharingButton.text = "Enable Location Sharing"
            }
            backgroundGranted -> {
                locationSharingStatus.text = "Occasional location sharing active · Android schedules ~15-minute background checks · updates may be delayed"
                locationSharingButton.text = "Stop Location Sharing"
                LocationSharingScheduler.schedule(this)
            }
            else -> {
                locationSharingStatus.text = "Setup incomplete · grant Location > Allow all the time"
                locationSharingButton.text = "Finish Location Setup"
            }
        }
    }


}

