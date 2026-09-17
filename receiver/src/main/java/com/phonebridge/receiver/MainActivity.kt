package com.phonebridge.receiver

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.room.Room
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
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
    private var queueListening = false
    private var currentFilter = "call"
    private var cachedRows: List<RecordEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pairingScreen = findViewById(R.id.pairingScreen)
        dashboardScreen = findViewById(R.id.dashboardScreen)
        status = findViewById(R.id.status)
        dashboardStatus = findViewById(R.id.dashboardStatus)
        pairCodeText = findViewById(R.id.pairCode)
        listTitle = findViewById(R.id.listTitle)

        adapter = RecordAdapter()
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        findViewById<Button>(R.id.btnMessagesTab).setOnClickListener {
            currentFilter = "message"
            listTitle.text = "Messages"
            applyFilter()
        }
        findViewById<Button>(R.id.btnCallsTab).setOnClickListener {
            currentFilter = "call"
            listTitle.text = "Calls"
            applyFilter()
        }
        findViewById<Button>(R.id.btnNotificationsTab).setOnClickListener {
            currentFilter = "notification"
            listTitle.text = "Notifications"
            applyFilter()
        }

        lifecycleScope.launch { refreshLocal() }
        showPairing()

        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnSuccessListener { resumeExistingPair() }
                .addOnFailureListener { status.text = "Firebase auth failed: ${it.message}" }
        } else {
            resumeExistingPair()
        }

        findViewById<Button>(R.id.btnCreatePair).setOnClickListener {
            createPairCode()
        }
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

        status.text = "Creating secure pairing code..."
        fs.collection("pairs").document(code).set(pairDoc)
            .addOnSuccessListener {
                prefs.edit().putString("pairId", code).apply()
                pairCodeText.text = code
                status.text = "Enter this 6-digit code on Phone 2."
                queueListening = false
                listen(code)
            }
            .addOnFailureListener {
                if (remainingAttempts > 1) {
                    createPairCodeAttempt(remainingAttempts - 1)
                } else {
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
        val pairRef = fs.collection("pairs").document(pairId)
        pairRef.addSnapshotListener { pairSnap, err ->
            if (err != null) {
                status.text = "Connection error: ${err.message}"
                return@addSnapshotListener
            }
            if (pairSnap == null || !pairSnap.exists()) return@addSnapshotListener

            val collectorKey = pairSnap.getString("collectorPublicKeyB64")
            if (collectorKey.isNullOrBlank()) {
                showPairing()
                status.text = "Waiting for Phone 2 to enter the code..."
                return@addSnapshotListener
            }

            showDashboard()
            dashboardStatus.text = "Connected to Phone 2"

            if (!queueListening) {
                queueListening = true
                listenForRecords(pairRef, pairId, collectorKey)
            }
        }
    }

    private fun listenForRecords(
        pairRef: com.google.firebase.firestore.DocumentReference,
        pairId: String,
        collectorKey: String
    ) {
        pairRef.collection("queue").addSnapshotListener { snap, e ->
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
            "message" -> cachedRows.filter {
                it.type == "message" ||
                    (it.type == "notification" && isMessagingSource(it.source))
            }
            else -> cachedRows
        }
        adapter.submit(rows)
    }

    private fun isMessagingSource(source: String): Boolean {
        val s = source.lowercase()
        return listOf(
            "whatsapp", "telegram", "signal", "messenger", "messages",
            "sms", "instagram", "snapchat"
        ).any { it in s }
    }

    private suspend fun refreshLocal() {
        cachedRows = withContext(Dispatchers.IO) { db.dao().all() }
        applyFilter()
    }
}
