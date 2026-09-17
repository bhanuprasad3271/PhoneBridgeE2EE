package com.phonebridge.receiver

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.phonebridge.shared.CryptoBox
import com.phonebridge.shared.JsonCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.SecureRandom

class MainActivity : AppCompatActivity() {
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val fs by lazy { FirebaseFirestore.getInstance() }
    private val crypto by lazy { CryptoBox(this, "phonebridge_receiver_ecdh") }
    private val db by lazy {
        Room.databaseBuilder(this, LocalDb::class.java, "phonebridge.db").build()
    }
    private val prefs by lazy { getSharedPreferences("bridge", MODE_PRIVATE) }

    private lateinit var adapter: RecordAdapter
    private lateinit var status: TextView
    private lateinit var dashboardStatus: TextView
    private lateinit var pairCodeText: TextView
    private lateinit var listTitle: TextView
    private lateinit var pairingScreen: View
    private lateinit var dashboardScreen: View
    private lateinit var alertsToolbar: View
    private lateinit var alertsButton: Button
    private lateinit var messagesButton: Button
    private lateinit var callsButton: Button
    private lateinit var notificationsButton: Button
    private lateinit var wpCallsButton: Button
    private lateinit var menuButton: Button

    private var pairRegistration: ListenerRegistration? = null
    private var queueRegistration: ListenerRegistration? = null
    private var currentFilter = "message"
    private var cachedRows: List<RecordEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pairingScreen = findViewById(R.id.pairingScreen)
        dashboardScreen = findViewById(R.id.dashboardScreen)
        alertsToolbar = findViewById(R.id.alertsToolbar)
        status = findViewById(R.id.status)
        dashboardStatus = findViewById(R.id.dashboardStatus)
        pairCodeText = findViewById(R.id.pairCode)
        listTitle = findViewById(R.id.listTitle)
        alertsButton = findViewById(R.id.btnAlertsTab)
        messagesButton = findViewById(R.id.btnMessagesTab)
        callsButton = findViewById(R.id.btnCallsTab)
        notificationsButton = findViewById(R.id.btnNotificationsTab)
        wpCallsButton = findViewById(R.id.btnWpCallsTab)
        menuButton = findViewById(R.id.btnMenu)

        adapter = RecordAdapter { record ->
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { db.dao().deleteById(record.id) }
                refreshLocal()
            }
        }
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        messagesButton.setOnClickListener { selectTab("message", "Messages") }
        callsButton.setOnClickListener { selectTab("call", "Calls") }
        notificationsButton.setOnClickListener { selectTab("notification", "Notifications") }
        wpCallsButton.setOnClickListener { selectTab("wp_call", "WP Calls") }
        alertsButton.setOnClickListener { selectTab("alert", "Alerts / Device Status") }
        menuButton.setOnClickListener { showSettingsMenu(it) }

        findViewById<Button>(R.id.btnClearAlerts).setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    db.dao().deleteByType("alert")
                    db.dao().deleteByType("status")
                }
                refreshLocal()
            }
        }

        lifecycleScope.launch { refreshLocal() }
        showPairing()
        selectTab("message", "Messages")

        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnSuccessListener { resumeExistingPair() }
                .addOnFailureListener { status.text = "Firebase auth failed: ${it.message}" }
        } else {
            resumeExistingPair()
        }

        findViewById<Button>(R.id.btnCreatePair).setOnClickListener { createPairCode() }
    }

    override fun onDestroy() {
        pairRegistration?.remove()
        queueRegistration?.remove()
        super.onDestroy()
    }

    private fun selectTab(filter: String, title: String) {
        currentFilter = filter
        listTitle.text = title
        alertsToolbar.visibility = if (filter == "alert") View.VISIBLE else View.GONE
        messagesButton.isSelected = filter == "message"
        callsButton.isSelected = filter == "call"
        notificationsButton.isSelected = filter == "notification"
        wpCallsButton.isSelected = filter == "wp_call"
        applyFilter()
    }

    private fun showSettingsMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Pair another phone")
            menu.add(0, 2, 1, "Reset pairing")
            menu.add(0, 3, 2, "Remove pairing")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> {
                        confirmPairAnother()
                        true
                    }
                    2 -> {
                        confirmResetPairing()
                        true
                    }
                    3 -> {
                        confirmRemovePairing()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun confirmPairAnother() {
        AlertDialog.Builder(this)
            .setTitle("Pair another phone?")
            .setMessage("The current Phone 2 pairing will be removed and a new 6-digit code will be generated.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                disconnectCurrentPair {
                    showPairing()
                    createPairCode()
                }
            }
            .show()
    }

    private fun confirmResetPairing() {
        AlertDialog.Builder(this)
            .setTitle("Reset pairing?")
            .setMessage("This clears the current pairing and returns to the pairing screen.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset") { _, _ ->
                disconnectCurrentPair {
                    pairCodeText.text = "------"
                    status.text = "Pairing reset. Generate a new 6-digit code when ready."
                    showPairing()
                }
            }
            .show()
    }

    private fun confirmRemovePairing() {
        AlertDialog.Builder(this)
            .setTitle("Remove pairing?")
            .setMessage("Phone 2 will no longer be connected. Records already stored on this receiver remain available.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                disconnectCurrentPair {
                    pairCodeText.text = "------"
                    status.text = "Pairing removed."
                    showPairing()
                }
            }
            .show()
    }

    private fun createPairCode() {
        createPairCodeAttempt(5)
    }

    private fun createPairCodeAttempt(remainingAttempts: Int) {
        val uid = auth.currentUser?.uid ?: return
        val code = (SecureRandom().nextInt(900000) + 100000).toString()
        val now = System.currentTimeMillis()
        val pairDoc = mapOf(
            "receiverUid" to uid,
            "receiverPublicKeyB64" to crypto.publicKeyB64(),
            "collectorUid" to null,
            "collectorPublicKeyB64" to null,
            "createdAtMs" to now,
            "expiresAtMs" to now + 10 * 60 * 1000L
        )

        pairCodeText.text = code
        status.text = "Creating secure pairing code..."
        fs.collection("pairs").document(code).set(pairDoc)
            .addOnSuccessListener {
                prefs.edit().putString("pairId", code).apply()
                pairCodeText.text = code
                status.text = "Enter this 6-digit code on Phone 2. It will stay here until pairing succeeds."
                listen(code)
            }
            .addOnFailureListener {
                if (remainingAttempts > 1) {
                    createPairCodeAttempt(remainingAttempts - 1)
                } else {
                    pairCodeText.text = "------"
                    status.text = "Could not create pairing code: ${it.message}"
                }
            }
    }

    private fun resumeExistingPair() {
        val pairId = prefs.getString("pairId", null)
        if (pairId == null) {
            pairCodeText.text = "------"
            status.text = "Generate a 6-digit code to connect Phone 2."
            showPairing()
            return
        }
        pairCodeText.text = if (pairId.length == 6) pairId else "Existing pair"
        listen(pairId)
    }

    private fun listen(pairId: String) {
        pairRegistration?.remove()
        queueRegistration?.remove()
        queueRegistration = null

        val pairRef = fs.collection("pairs").document(pairId)
        pairRegistration = pairRef.addSnapshotListener { pairSnap, err ->
            if (err != null) {
                status.text = "Connection error: ${err.message}"
                return@addSnapshotListener
            }
            if (pairSnap == null || !pairSnap.exists()) {
                prefs.edit().remove("pairId").apply()
                pairCodeText.text = "------"
                status.text = "Pairing is no longer active. Generate a new code."
                showPairing()
                return@addSnapshotListener
            }

            val collectorKey = pairSnap.getString("collectorPublicKeyB64")
            if (collectorKey.isNullOrBlank()) {
                showPairing()
                pairCodeText.text = pairId
                status.text = "Waiting for Phone 2 to enter this code..."
                return@addSnapshotListener
            }

            showDashboard()
            dashboardStatus.text = "Connected to Phone 2"
            if (queueRegistration == null) {
                queueRegistration = listenForRecords(pairRef, pairId, collectorKey)
            }
        }
    }

    private fun listenForRecords(
        pairRef: DocumentReference,
        pairId: String,
        collectorKey: String
    ): ListenerRegistration {
        return pairRef.collection("queue").addSnapshotListener { snap, e ->
            if (e != null || snap == null) return@addSnapshotListener

            snap.documentChanges.forEach { change ->
                if (change.type.name != "ADDED") return@forEach
                val d = change.document
                val id = d.getString("id") ?: d.id
                val type = d.getString("type") ?: "unknown"
                val ct = d.getString("ciphertextB64") ?: return@forEach
                val nonce = d.getString("nonceB64") ?: return@forEach

                try {
                    val key = crypto.deriveAesKey(collectorKey, pairId)
                    val aad = "$pairId|$id|$type|1".toByteArray()
                    val plain = crypto.decrypt(key, ct, nonce, aad).toString(Charsets.UTF_8)
                    val record = JsonCodec.decode(plain)

                    lifecycleScope.launch {
                        db.dao().insert(
                            RecordEntity(
                                id = id,
                                type = record.type,
                                timestamp = record.timestamp,
                                source = record.source,
                                title = record.title,
                                body = record.body,
                                metadataJson = JSONObject(record.metadata).toString()
                            )
                        )
                        refreshLocal()
                        d.reference.delete()
                    }
                } catch (ex: Exception) {
                    dashboardStatus.text = "Could not decrypt one record: ${ex.message}"
                }
            }
        }
    }

    private fun disconnectCurrentPair(after: () -> Unit) {
        val pairId = prefs.getString("pairId", null)
        pairRegistration?.remove()
        pairRegistration = null
        queueRegistration?.remove()
        queueRegistration = null

        fun finish() {
            prefs.edit().remove("pairId").apply()
            after()
        }

        if (pairId == null) {
            finish()
        } else {
            fs.collection("pairs").document(pairId).delete()
                .addOnCompleteListener { finish() }
        }
    }

    private fun showPairing() {
        pairingScreen.visibility = View.VISIBLE
        dashboardScreen.visibility = View.GONE
    }

    private fun showDashboard() {
        pairingScreen.visibility = View.GONE
        dashboardScreen.visibility = View.VISIBLE
    }

    private fun applyFilter() {
        val rows = when (currentFilter) {
            "call" -> cachedRows.filter { it.type == "call" }
            "notification" -> cachedRows.filter { it.type == "notification" }
            "message" -> cachedRows.filter { it.type == "message" }
            "wp_call" -> cachedRows.filter { it.type == "wp_call" }
            "alert" -> cachedRows.filter { it.type == "alert" || it.type == "status" }
            else -> cachedRows
        }
        adapter.submit(rows)
    }

    private suspend fun refreshLocal() {
        cachedRows = withContext(Dispatchers.IO) { db.dao().all() }
        val warningCount = cachedRows.count {
            if (it.type != "alert") return@count false
            try {
                JSONObject(it.metadataJson).optString("severity", "warning") == "warning"
            } catch (_: Exception) {
                true
            }
        }
        alertsButton.text = if (warningCount > 0) "🔔 $warningCount" else "🔔"
        applyFilter()
    }
}
