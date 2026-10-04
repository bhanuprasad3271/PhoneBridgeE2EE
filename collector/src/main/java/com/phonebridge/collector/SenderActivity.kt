package com.phonebridge.collector

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.phonebridge.shared.PlainRecord

abstract class SenderActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var dashboardStatus: TextView
    private lateinit var pairingScreen: View
    private lateinit var dashboardScreen: View
    protected lateinit var prefs: BridgePrefs
    private val disconnected = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (prefs.pairId == null) { showPairing(); status.text = "Disconnected by Receiver. Enter a new pairing code to reconnect." }
        }
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pairingScreen = findViewById(R.id.pairingScreen)
        dashboardScreen = findViewById(R.id.dashboardScreen)
        status = findViewById(R.id.status)
        dashboardStatus = findViewById(R.id.dashboardStatus)
        prefs = BridgePrefs(this)
        ContextCompat.registerReceiver(this, disconnected, IntentFilter(SenderPairState.ACTION_DISCONNECTED), ContextCompat.RECEIVER_NOT_EXPORTED)
        bindEditionUi()

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
                                try {
                                    NotificationListenerService.requestRebind(
                                        ComponentName(this@SenderActivity, BridgeNotificationListener::class.java)
                                    )
                                } catch (_: SecurityException) {
                                    dashboardStatus.text = "Paired · enable notification access to sync notifications"
                                }
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

        findViewById<Button>(R.id.btnSync).setOnClickListener {
            WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<CallLogWorker>().build())
            dashboardStatus.text = "Optional sync requested · automatic sync remains enabled"
        }
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized && prefs.pairId == null) showPairing()
        if (::prefs.isInitialized && prefs.pairId != null && prefs.receiverPublicKey != null) {
            updateEditionUi()
            reportSenderState(force = false)
        }
    }

    override fun onDestroy() {
        unregisterReceiver(disconnected)
        super.onDestroy()
    }

    protected open fun bindEditionUi() {}
    protected open fun updateEditionUi() {}
    protected abstract fun locationState(): String
    protected abstract fun stopLocationSharing()

    protected fun reportSenderState(force: Boolean) {
        if (prefs.pairId == null || prefs.receiverPublicKey == null) return

        val callGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val notifGranted = packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
        val locationState = locationState()
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }

        val relay = FirebaseRelay(this)
        relay.ensureSignedIn { ok, _ ->
            if (!ok) return@ensureSignedIn

            val callState = if (callGranted) "enabled" else "disabled"
            val stateChanged = prefs.lastCallPermissionState != callState ||
                prefs.lastNotificationAccessState != (if (notifGranted) "enabled" else "disabled") ||
                prefs.lastLocationPermissionState != locationState
            if (force || prefs.lastCallPermissionState != callState) {
                relay.send(
                    PlainRecord(
                        type = "alert",
                        timestamp = System.currentTimeMillis(),
                        source = "sender",
                        title = if (callGranted) "Automatic call sync active" else "Call sync permission missing",
                        body = if (callGranted) "Phone 2 can send live call-state updates and finalize calls from the call log." else "Phone 2 needs Call Log and Phone State permissions for automatic call syncing.",
                        metadata = mapOf("severity" to if (callGranted) "resolved" else "warning", "kind" to "call_permission", "state" to callState)
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
                        metadata = mapOf("severity" to if (notifGranted) "resolved" else "warning", "kind" to "notification_access", "state" to notifState)
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
                            "unsupported" -> "Location unavailable — Sender Lite"
                            "enabled" -> "Location sharing active"
                            "needs_permission" -> "Location sharing setup incomplete"
                            else -> "Location sharing off"
                        },
                        body = when (locationState) {
                            "unsupported" -> "Sender Lite does not collect location. Messages, calls and notifications remain available. Use Full Sender for location sharing."
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

            if (force || stateChanged || prefs.lastReportedVersion != version) {
                relay.send(
                    PlainRecord(
                        type = "status",
                        timestamp = System.currentTimeMillis(),
                        source = "sender",
                        title = "Sender app status",
                        body = "Sender version $version is connected.",
                        metadata = mapOf(
                            "appVersion" to version,
                            "githubBuild" to BuildConfig.GITHUB_BUILD,
                            "senderEdition" to getString(R.string.sender_edition),
                            "supportsLocation" to (locationState != "unsupported").toString(),
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
        stopLocationSharing()
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
        updateEditionUi()
    }
}
