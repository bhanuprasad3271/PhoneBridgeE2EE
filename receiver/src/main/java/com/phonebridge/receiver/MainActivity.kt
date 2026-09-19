package com.phonebridge.receiver

import android.app.DatePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.room.Room
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.phonebridge.shared.CryptoBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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
    private lateinit var filterButton: Button

    private var pairRegistration: ListenerRegistration? = null
    private var queueRegistration: ListenerRegistration? = null
    private var updateReceiverRegistered = false

    private val dataUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            lifecycleScope.launch { refreshLocal() }
        }
    }
    private var currentFilter = "message"
    private var cachedRows: List<RecordEntity> = emptyList()
    private val sectionFilters = mutableMapOf<String, FilterCriteria>()

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
        filterButton = findViewById(R.id.btnFilter)

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
        filterButton.setOnClickListener { showFilterDialog() }

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
                .addOnFailureListener { status.text = "Firebase auth failed: " + it.message }
        } else {
            resumeExistingPair()
        }

        findViewById<Button>(R.id.btnCreatePair).setOnClickListener { createPairCode() }
    }

    override fun onStart() {
        super.onStart()
        if (!updateReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                dataUpdateReceiver,
                IntentFilter(ReceiverSyncWorker.ACTION_DATA_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            updateReceiverRegistered = true
        }
        lifecycleScope.launch { refreshLocal() }
        if (auth.currentUser != null) {
            prefs.getString("pairId", null)?.let { listen(it) }
        }
    }

    override fun onStop() {
        queueRegistration?.remove()
        queueRegistration = null
        pairRegistration?.remove()
        pairRegistration = null
        if (updateReceiverRegistered) {
            unregisterReceiver(dataUpdateReceiver)
            updateReceiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        queueRegistration?.remove()
        pairRegistration?.remove()
        super.onDestroy()
    }

    private fun selectTab(filter: String, title: String) {
        currentFilter = filter
        listTitle.text = title
        alertsToolbar.visibility = if (filter == "alert") View.VISIBLE else View.GONE
        filterButton.visibility = if (filter == "alert") View.GONE else View.VISIBLE
        messagesButton.isSelected = filter == "message"
        callsButton.isSelected = filter == "call"
        notificationsButton.isSelected = filter == "notification"
        wpCallsButton.isSelected = filter == "wp_call"
        updateFilterButton()
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

    private fun showFilterDialog() {
        val existing = sectionFilters[currentFilter] ?: FilterCriteria()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 12, 40, 12)
        }

        fun edit(hint: String, value: String): EditText {
            return EditText(this).apply {
                this.hint = hint
                setText(value)
                box.addView(this)
            }
        }

        val appField = edit("App", existing.app)
        val nameField = edit("Name / contact", existing.nameOrContact)
        val numberField = edit("Number", existing.number)
        val usernameField = edit("Username", existing.username)

        var fromMs = existing.fromMs
        var toMs = existing.toMs
        val fromButton = Button(this).apply {
            text = "From date: " + formatDate(fromMs)
            setOnClickListener {
                chooseDate(fromMs, false) {
                    fromMs = it
                    text = "From date: " + formatDate(fromMs)
                }
            }
        }
        val toButton = Button(this).apply {
            text = "To date: " + formatDate(toMs)
            setOnClickListener {
                chooseDate(toMs, true) {
                    toMs = it
                    text = "To date: " + formatDate(toMs)
                }
            }
        }
        box.addView(fromButton)
        box.addView(toButton)

        val stateLabel = TextView(this).apply {
            text = "Call state"
            setPadding(0, 18, 0, 4)
        }
        box.addView(stateLabel)

        fun stateCheck(label: String): CheckBox {
            return CheckBox(this).apply {
                text = label
                isChecked = label in existing.states
                box.addView(this)
            }
        }

        val incoming = stateCheck("Incoming")
        val outgoing = stateCheck("Outgoing")
        val rejected = stateCheck("Rejected")
        val missed = stateCheck("Missed")

        val scroll = ScrollView(this).apply { addView(box) }

        AlertDialog.Builder(this)
            .setTitle("Filter " + listTitle.text)
            .setView(scroll)
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ ->
                sectionFilters[currentFilter] = FilterCriteria()
                updateFilterButton()
                applyFilter()
            }
            .setPositiveButton("Apply") { _, _ ->
                val states = mutableSetOf<String>()
                if (incoming.isChecked) states.add("Incoming")
                if (outgoing.isChecked) states.add("Outgoing")
                if (rejected.isChecked) states.add("Rejected")
                if (missed.isChecked) states.add("Missed")

                sectionFilters[currentFilter] = FilterCriteria(
                    app = appField.text.toString().trim(),
                    nameOrContact = nameField.text.toString().trim(),
                    number = numberField.text.toString().trim(),
                    username = usernameField.text.toString().trim(),
                    fromMs = fromMs,
                    toMs = toMs,
                    states = states
                )
                updateFilterButton()
                applyFilter()
            }
            .show()
    }

    private fun chooseDate(initial: Long?, endOfDay: Boolean, onSet: (Long) -> Unit) {
        val cal = Calendar.getInstance()
        if (initial != null) cal.timeInMillis = initial

        DatePickerDialog(
            this,
            { _, year, month, day ->
                val chosen = Calendar.getInstance().apply {
                    set(year, month, day)
                    if (endOfDay) {
                        set(Calendar.HOUR_OF_DAY, 23)
                        set(Calendar.MINUTE, 59)
                        set(Calendar.SECOND, 59)
                        set(Calendar.MILLISECOND, 999)
                    } else {
                        set(Calendar.HOUR_OF_DAY, 0)
                        set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                }
                onSet(chosen.timeInMillis)
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun formatDate(value: Long?): String {
        if (value == null) return "Any"
        return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(value))
    }

    private fun updateFilterButton() {
        if (!::filterButton.isInitialized) return
        val active = sectionFilters[currentFilter]?.isActive() == true
        filterButton.text = if (active) "Filter •" else "Filter"
    }

    private fun confirmPairAnother() {
        AlertDialog.Builder(this)
            .setTitle("Pair another phone?")
            .setMessage("The current Sender pairing will be removed and a new 6-digit code will be generated.")
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
            .setMessage("The Sender will no longer be connected. Records already stored on this receiver remain available.")
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
                status.text = "Enter this 6-digit code on the Sender. It will stay here until pairing succeeds."
                listen(code)
            }
            .addOnFailureListener {
                if (remainingAttempts > 1) {
                    createPairCodeAttempt(remainingAttempts - 1)
                } else {
                    pairCodeText.text = "------"
                    status.text = "Could not create pairing code: " + it.message
                }
            }
    }

    private fun resumeExistingPair() {
        val pairId = prefs.getString("pairId", null)
        if (pairId == null) {
            pairCodeText.text = "------"
            status.text = "Generate a 6-digit code to connect the Sender."
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
                status.text = "Connection error: " + err.message
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
                status.text = "Waiting for the Sender to enter this code..."
                return@addSnapshotListener
            }

            showDashboard()
            dashboardStatus.text = "Connected to Sender"
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
        queueRegistration?.remove()
        queueRegistration = null
        pairingScreen.visibility = View.VISIBLE
        dashboardScreen.visibility = View.GONE
    }

    private fun showDashboard() {
        pairingScreen.visibility = View.GONE
        dashboardScreen.visibility = View.VISIBLE

        enqueueReceiverSync()
        startQueueListener()

        lifecycleScope.launch { refreshLocal() }
    }

    private fun enqueueReceiverSync() {
        WorkManager.getInstance(this).enqueueUniqueWork(
            "receiver-foreground-sync",
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ReceiverSyncWorker>().build()
        )
    }

    private fun startQueueListener() {
        if (queueRegistration != null) return
        val pairId = prefs.getString("pairId", null) ?: return

        queueRegistration = fs.collection("pairs")
            .document(pairId)
            .collection("queue")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    dashboardStatus.text = "Connected to Sender • sync will retry when reopened"
                    return@addSnapshotListener
                }

                val hasNewData = snapshot?.documentChanges?.any {
                    it.type == DocumentChange.Type.ADDED || it.type == DocumentChange.Type.MODIFIED
                } == true

                if (hasNewData) enqueueReceiverSync()
            }
    }

    private fun applyFilter() {
        val baseRows = when (currentFilter) {
            "call" -> cachedRows.filter { it.type == "call" }
            "notification" -> cachedRows.filter {
                it.type == "notification" || it.type == "message" || it.type == "wp_call"
            }
            "message" -> cachedRows.filter { it.type == "message" }
            "wp_call" -> cachedRows.filter { it.type == "wp_call" }
            "alert" -> cachedRows.filter { it.type == "alert" || it.type == "status" }
            else -> cachedRows
        }

        val criteria = sectionFilters[currentFilter]
        adapter.submit(
            if (criteria == null || !criteria.isActive()) baseRows
            else baseRows.filter { criteria.matches(it) }
        )
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
        alertsButton.text = if (warningCount > 0) "🔔 " + warningCount else "🔔"
        applyFilter()
    }
}
