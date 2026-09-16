package com.phonebridge.receiver

import android.os.Bundle
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
import com.phonebridge.shared.PairBundle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.SecureRandom
import android.util.Base64

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
    private lateinit var pairText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        pairText = findViewById(R.id.pairBundle)

        adapter = RecordAdapter()
        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        lifecycleScope.launch { refreshLocal() }

        if (auth.currentUser == null) {
            auth.signInAnonymously().addOnSuccessListener { resumeExistingPair() }
                .addOnFailureListener { status.text = "Firebase auth failed: ${it.message}" }
        } else resumeExistingPair()

        findViewById<Button>(R.id.btnCreatePair).setOnClickListener {
            createPair()
        }
    }

    private fun createPair() {
        val uid = auth.currentUser?.uid ?: return
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val pairId = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val bundle = PairBundle(pairId, crypto.publicKeyB64()).encode()
        val pairDoc = mapOf(
            "receiverUid" to uid,
            "receiverPublicKeyB64" to crypto.publicKeyB64(),
            "collectorUid" to null,
            "collectorPublicKeyB64" to null,
            "createdAtMs" to System.currentTimeMillis()
        )
        fs.collection("pairs").document(pairId).set(pairDoc)
            .addOnSuccessListener {
                prefs.edit().putString("pairId", pairId).apply()
                pairText.text = bundle
                status.text = "Pair created. Paste this bundle into Phone 2."
                listen(pairId)
            }
            .addOnFailureListener { status.text = "Pair creation failed: ${it.message}" }
    }

    private fun resumeExistingPair() {
        val pairId = prefs.getString("pairId", null) ?: return
        pairText.text = PairBundle(pairId, crypto.publicKeyB64()).encode()
        listen(pairId)
    }

    private fun listen(pairId: String) {
        status.text = "Listening for encrypted records..."
        val pairRef = fs.collection("pairs").document(pairId)
        pairRef.addSnapshotListener { pairSnap, err ->
            if (err != null || pairSnap == null || !pairSnap.exists()) return@addSnapshotListener
            val collectorKey = pairSnap.getString("collectorPublicKeyB64") ?: return@addSnapshotListener

            pairRef.collection("queue")
                .addSnapshotListener { snap, e ->
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
                                // Delete only after successful decryption + local insertion.
                                d.reference.delete()
                            }
                        } catch (ex: Exception) {
                            status.text = "Decrypt failed for one record: ${ex.message}"
                        }
                    }
                }
        }
    }

    private suspend fun refreshLocal() {
        val rows = withContext(Dispatchers.IO) { db.dao().all() }
        adapter.submit(rows)
    }
}
