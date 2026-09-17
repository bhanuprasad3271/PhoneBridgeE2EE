package com.phonebridge.collector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var dashboardStatus: TextView
    private lateinit var pairingScreen: View
    private lateinit var dashboardScreen: View
    private lateinit var prefs: BridgePrefs

    private val callPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val text = if (granted) "Call-log permission granted" else "Call-log permission denied"
        status.text = text
        dashboardStatus.text = text
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pairingScreen = findViewById(R.id.pairingScreen)
        dashboardScreen = findViewById(R.id.dashboardScreen)
        status = findViewById(R.id.status)
        dashboardStatus = findViewById(R.id.dashboardStatus)
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
                                status.text = "Paired successfully"
                                dashboardStatus.text = "Connected to Phone 1 · New calls only"
                                showDashboard()
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED
            ) {
                callPermission.launch(Manifest.permission.READ_CALL_LOG)
            } else {
                dashboardStatus.text = "Call-log permission already granted"
            }
        }

        findViewById<Button>(R.id.btnSync).setOnClickListener {
            WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<CallLogWorker>().build())
            dashboardStatus.text = "Sync requested · only calls after pairing are eligible"
        }
    }

    private fun clearPendingPair() {
        prefs.pairId = null
        prefs.receiverPublicKey = null
        prefs.pairedAtMs = 0L
        prefs.lastCallTimestamp = 0L
    }

    private fun showPairing() {
        pairingScreen.visibility = View.VISIBLE
        dashboardScreen.visibility = View.GONE
    }

    private fun showDashboard() {
        pairingScreen.visibility = View.GONE
        dashboardScreen.visibility = View.VISIBLE
    }
}
