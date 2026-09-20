package com.phonebridge.collector

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.phonebridge.shared.PlainRecord

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var dashboardStatus: TextView
    private lateinit var locationSharingStatus: TextView
    private lateinit var locationSharingButton: Button
    private lateinit var pairingScreen: View
    private lateinit var dashboardScreen: View
    private lateinit var prefs: BridgePrefs

    private val callPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val callGranted = result[Manifest.permission.READ_CALL_LOG] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        val phoneGranted = result[Manifest.permission.READ_PHONE_STATE] == true ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

        val text = if (callGranted && phoneGranted) {
            "Live call sync enabled"
        } else {
            "Live call sync permissions are incomplete"
        }
        status.text = text
        dashboardStatus.text = text
        reportSenderState(force = true)
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pairingScreen = findViewById(R.id.pairingScreen)
        dashboardScreen = findViewById(R.id.dashboardScreen)
        status = findViewById(R.id.status)
        dashboardStatus = findViewById(R.id.dashboardStatus)
        locationSharingStatus = findViewById(R.id.locationSharingStatus)
        locationSharingButton = findViewById(R.id.btnLocationSharing)
        prefs = BridgePrefs(this)

        if (prefs.pairId != null && prefs.receiverPublicKey != null) {
            if (prefs.pairedAtMs == 0L) {
                val now = System.currentTimeMillis()
                prefs.pairedAtMs = now
                prefs.lastCallTimestamp = now
            }
            showDashboard()
            dashboardStatus.text = "Connected to Phone 1"
        } else {
            showPairing()
        }

        findViewById<Button>(R.id.btnPair).setOnClickListener {
            val code = findViewById<EditText>(R.id.pairCode).text.toString().trim()
            if (!code.matches(Regex("\\d{6}"))) {
                status.text = "Enter the 6-digit code shown on Phone 1"
                return@setOnClickListener
            }

            status.text = "Connecting..."
            prefs.pairId = code
            prefs.receiverPublicKey = null
            prefs.pairedAtMs = 0L
            prefs.lastCallTimestamp = 0L

            val relay = FirebaseRelay(this)
            relay.ensureSignedIn { ok, err ->
                if (!ok) {
                    runOnUiThread {
                        clearPendingPair()
                        status.text = "Auth failed: $err"
                    }
                } else {
                    relay.registerPair { paired, e ->
                        runOnUiThread {
                            if (paired) {
                                val pairedAt = System.currentTimeMillis()
                                prefs.pairedAtMs = pairedAt
                                prefs.lastCallTimestamp = pairedAt
                                prefs.lastCallPermissionState = null
                                prefs.lastNotificationAccessState = null
                                prefs.lastLocationPermissionState = null
                                prefs.lastReportedVersion = null
                                status.text = "Paired successfully"
                                dashboardStatus.text = "Connected to Phone 1 · New activity only"
                                showDashboard()
                                NotificationListenerService.requestRebind(
                                    ComponentName(this@MainActivity, BridgeNotificationListener::class.java)
                                )
                                reportSenderState(force = true)
                            } else {
                                clearPendingPair()
                                status.text = "Pairing failed. Check the 6-digit code and try again. ${e ?: ""}"
                            }
                        }
                    }
                }
            }
        }

        findViewById<Button>(R.id.btnNotif).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btnCalls).setOnClickListener {
            callPermissions.launch(
                arrayOf(
                    Manifest.permission.READ_CALL_LOG,
                    Manifest.permission.READ_PHONE_STATE
                )
            )
        }

        locationSharingButton.setOnClickListener {
            when {
                prefs.locationSharingEnabled &&
                    LocationSharingScheduler.hasBackgroundLocation(this) -> {
                    AlertDialog.Builder(this)
                        .setTitle("Stop location sharing?")
                        .setMessage("PhoneBridge will stop collecting and sending background location updates.")
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

        findViewById<Button>(R.id.btnSync).setOnClickListener {
            WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<CallLogWorker>().build())
            dashboardStatus.text = "Optional sync requested · automatic sync remains enabled"
        }
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized && prefs.pairId != null && prefs.receiverPublicKey != null) {
            updateLocationSharingUi()
            reportSenderState(force = false)
        }
    }

    private fun startLocationSharingSetup() {
        AlertDialog.Builder(this)
            .setTitle("Enable Location Sharing")
            .setMessage(
                "PhoneBridge Sender will periodically obtain your location in the background, encrypt it, " +
                    "and share the latest location with the paired Receiver until you turn this off. " +
                    "Android controls the exact timing and may show its own privacy reminders."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                locationPermissions.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
            .show()
    }

    private fun showBackgroundLocationInstructions() {
        AlertDialog.Builder(this)
            .setTitle("Allow background location")
            .setMessage(
                "To keep sharing after Sender is closed, Android requires 'Allow all the time'. " +
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
                locationSharingStatus.text = "Location sharing active · encrypted update about every 15 minutes"
                locationSharingButton.text = "Stop Location Sharing"
                LocationSharingScheduler.schedule(this)
            }
            else -> {
                locationSharingStatus.text = "Setup incomplete · grant Location > Allow all the time"
                locationSharingButton.text = "Finish Location Setup"
            }
        }
    }

    private fun reportSenderState(force: Boolean) {
        if (prefs.pairId == null || prefs.receiverPublicKey == null) return

        val callGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val notifGranted = packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
        val locationState = when {
            prefs.locationSharingEnabled && LocationSharingScheduler.hasBackgroundLocation(this) -> "enabled"
            prefs.locationSharingEnabled -> "needs_permission"
            else -> "off"
        }
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }

        val relay = FirebaseRelay(this)
        relay.ensureSignedIn { ok, _ ->
            if (!ok) return@ensureSignedIn

            val callState = if (callGranted) "enabled" else "disabled"
            if (force || prefs.lastCallPermissionState != callState) {
                relay.send(
                    PlainRecord(
                        type = "alert",
                        timestamp = System.currentTimeMillis(),
                        source = "sender",
                        title = if (callGranted) "Automatic call sync active" else "Call sync permission missing",
                        body = if (callGranted) "Phone 2 can send live call-state updates and finalize calls from the call log." else "Phone 2 needs Call Log and Phone State permissions for automatic call syncing.",
                        metadata = mapOf("severity" to if (callGranted) "resolved" else "warning", "kind" to "call_permission")
                    )
                )
                prefs.lastCallPermissionState = callState
            }

            val notifState = if (notifGranted) "enabled" else "disabled"
            if (force || prefs.lastNotificationAccessState != notifState) {
                relay.send(
                    PlainRecord(
                        type = "alert",
                        timestamp = System.currentTimeMillis(),
                        source = "sender",
                        title = if (notifGranted) "Notification access active" else "Notification access disabled",
                        body = if (notifGranted) "Phone 2 can send permitted notification previews." else "Notification access must be enabled on Phone 2 to receive message and notification previews.",
                        metadata = mapOf("severity" to if (notifGranted) "resolved" else "warning", "kind" to "notification_access")
                    )
                )
                prefs.lastNotificationAccessState = notifState
            }

            if (force || prefs.lastLocationPermissionState != locationState) {
                relay.send(
                    PlainRecord(
                        type = "alert",
                        timestamp = System.currentTimeMillis(),
                        source = "sender",
                        title = when (locationState) {
                            "enabled" -> "Location sharing active"
                            "needs_permission" -> "Location sharing setup incomplete"
                            else -> "Location sharing off"
                        },
                        body = when (locationState) {
                            "enabled" -> "PhoneBridge can send encrypted background location updates to this Receiver."
                            "needs_permission" -> "Background location permission is still required on Phone 2."
                            else -> "PhoneBridge is not collecting background location."
                        },
                        metadata = mapOf(
                            "severity" to if (locationState == "needs_permission") "warning" else "resolved",
                            "kind" to "location_sharing",
                            "state" to locationState
                        )
                    )
                )
                prefs.lastLocationPermissionState = locationState
            }

            if (force || prefs.lastReportedVersion != version) {
                relay.send(
                    PlainRecord(
                        type = "status",
                        timestamp = System.currentTimeMillis(),
                        source = "sender",
                        title = "Sender app status",
                        body = "Sender version $version is connected.",
                        metadata = mapOf(
                            "appVersion" to version,
                            "callSync" to callState,
                            "notificationAccess" to notifState,
                            "locationSharing" to locationState
                        )
                    )
                )
                prefs.lastReportedVersion = version
            }
        }
    }

    private fun clearPendingPair() {
        LocationSharingScheduler.cancel(this)
        prefs.locationSharingEnabled = false
        prefs.pairId = null
        prefs.receiverPublicKey = null
        prefs.pairedAtMs = 0L
        prefs.lastCallTimestamp = 0L
        prefs.lastCallPermissionState = null
        prefs.lastNotificationAccessState = null
        prefs.lastLocationPermissionState = null
        prefs.lastReportedVersion = null
        prefs.currentCallStateHistory = ""
        prefs.currentCallStartedAtMs = 0L
        prefs.currentCallSessionId = null
        prefs.currentCallNumber = ""
    }

    private fun showPairing() {
        pairingScreen.visibility = View.VISIBLE
        dashboardScreen.visibility = View.GONE
    }

    private fun showDashboard() {
        pairingScreen.visibility = View.GONE
        dashboardScreen.visibility = View.VISIBLE
        updateLocationSharingUi()
    }
}
