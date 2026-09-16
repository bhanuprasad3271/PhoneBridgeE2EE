package com.phonebridge.collector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.phonebridge.shared.PairBundle

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val callPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> status.text = if (granted) "Call-log permission granted" else "Call-log permission denied" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        val prefs = BridgePrefs(this)
        if (prefs.pairId != null) status.text = "Paired: ${prefs.pairId}"

        findViewById<Button>(R.id.btnPair).setOnClickListener {
            val raw = findViewById<EditText>(R.id.pairBundle).text.toString().trim()
            try {
                val b = PairBundle.decode(raw)
                prefs.pairId = b.pairId
                prefs.receiverPublicKey = b.receiverPublicKeyB64
                val relay = FirebaseRelay(this)
                relay.ensureSignedIn { ok, err ->
                    if (!ok) runOnUiThread { status.text = "Auth failed: $err" }
                    else relay.registerPair { paired, e ->
                        runOnUiThread { status.text = if (paired) "Paired successfully" else "Pairing failed: $e" }
                    }
                }
            } catch (e: Exception) {
                status.text = "Invalid pairing bundle"
            }
        }

        findViewById<Button>(R.id.btnNotif).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        findViewById<Button>(R.id.btnCalls).setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED
            ) callPermission.launch(Manifest.permission.READ_CALL_LOG)
            else status.text = "Call-log permission already granted"
        }

        findViewById<Button>(R.id.btnSync).setOnClickListener {
            WorkManager.getInstance(this).enqueue(OneTimeWorkRequestBuilder<CallLogWorker>().build())
            status.text = "Call-log sync requested"
        }
    }
}
