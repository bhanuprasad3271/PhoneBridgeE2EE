package com.phonebridge.receiver

import android.content.ActivityNotFoundException
import android.app.DatePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.phonebridge.shared.CryptoBox
import com.phonebridge.shared.WhatsAppNotificationClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {
    companion object {
        private const val DEFAULT_LOCATION_TRIGGER = "PBLOC#7421"
    }
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val fs by lazy { FirebaseFirestore.getInstance() }
    private val crypto by lazy { CryptoBox(this, "phonebridge_receiver_ecdh") }
    private val db by lazy {
        LocalDb.get(this)
    }
    private val prefs by lazy { com.phonebridge.shared.SecurePreferences.get(this, "bridge") }
    private val devices by lazy { DeviceProfiles(this) }
    private lateinit var deviceStatusPanel: LinearLayout
    private val statusValues = linkedMapOf<String, TextView>()
    private val queueListeners = mutableMapOf<String, ListenerRegistration>()
    private val deviceFilters = mutableMapOf<String, Map<String, FilterCriteria>>()
    private var displayedHistoryId: String? = null

    private lateinit var adapter: RecordAdapter
    private lateinit var locationHistoryAdapter: RecordAdapter
    private val locationDisplayNames by lazy { LocationDisplayNames(this) }
    private var recentAddressJob: Job? = null
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
    private lateinit var locationButton: Button
    private lateinit var menuButton: Button
    private lateinit var filterButton: Button
    private lateinit var locationRefreshButton: Button
    private lateinit var locationTriggerButton: Button
    private lateinit var locationRefreshProgress: TextView
    private lateinit var locationHistoryTitle: TextView
    private lateinit var locationTimelineButton: Button
    private lateinit var locationHistoryEmpty: TextView
    private lateinit var recordList: RecyclerView
    private lateinit var locationHistoryList: RecyclerView
    private lateinit var locationPanel: View
    private lateinit var locationStatus: TextView
    private lateinit var locationCoordinates: TextView
    private lateinit var locationUpdated: TextView
    private lateinit var locationAccuracy: TextView
    private lateinit var openMapsButton: Button

    private var pairRegistration: ListenerRegistration? = null
    private var queueRegistration: ListenerRegistration? = null
    private var updateReceiverRegistered = false
    private var activeLocationRefreshRequestId: String? = null
    private var locationRefreshStage = 0

    private val dataUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            lifecycleScope.launch {
            purgeExpiredLocations()
            refreshLocal()
        }
        }
    }
    private var currentFilter = "message"
    private var cachedRows: List<RecordEntity> = emptyList()
    private var latestLocation: RecordEntity? = null
    private val sectionFilters = mutableMapOf<String, FilterCriteria>()
    private lateinit var recordDates: RecordDates
    private var filterJob: Job? = null
    private var filterGeneration = 0
    private var filterSelection: List<Any?>? = null
    private var loadedStatus = DeviceStatus()
    private var loadedWarnings: List<RecordEntity> = emptyList()
    private val monitoring by lazy { MonitoringUi(this) {
        val id = devices.historyId(devices.activeId().orEmpty())
        withContext(Dispatchers.IO) { MessageDuplicates.visible(db.dao().forPair(id)) }
    } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pairingScreen = findViewById(R.id.pairingScreen)
        dashboardScreen = findViewById(R.id.dashboardScreen)
        alertsToolbar = findViewById(R.id.alertsToolbar)
        status = findViewById(R.id.status)
        dashboardStatus = findViewById(R.id.dashboardStatus)
        devices.all() // Migrate the existing pairing without changing its key.
        findViewById<Button>(R.id.btnPairingDevices).setOnClickListener { showDevices() }
        deviceStatusPanel = findViewById(R.id.deviceStatusPanel)
        for (label in listOf("Sender app", "Notifications", "Call access", "Location", "Last connected", "App version", "GitHub build")) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(8, 8, 8, 8) }
            row.addView(TextView(this).apply { text = label; setTextColor(0xFFCBD5E1.toInt()); textSize = 14f }, LinearLayout.LayoutParams(0, -2, 1f))
            val value = TextView(this).apply { text = "Unknown"; textSize = 14f; gravity = android.view.Gravity.END; setTextColor(0xFFCBD5E1.toInt()) }
            row.addView(value, LinearLayout.LayoutParams(0, -2, 1.2f))
            statusValues[label] = value
            deviceStatusPanel.addView(row)
        }
        pairCodeText = findViewById(R.id.pairCode)
        listTitle = findViewById(R.id.listTitle)
        alertsButton = findViewById(R.id.btnAlertsTab)
        messagesButton = findViewById(R.id.btnMessagesTab)
        callsButton = findViewById(R.id.btnCallsTab)
        notificationsButton = findViewById(R.id.btnNotificationsTab)
        wpCallsButton = findViewById(R.id.btnWpCallsTab)
        locationButton = findViewById(R.id.btnLocationTab)
        menuButton = findViewById(R.id.btnMenu)
        filterButton = findViewById(R.id.btnFilter)
        locationRefreshButton = findViewById(R.id.btnLocationRefresh)
        locationTriggerButton = findViewById(R.id.btnLocationTriggerText)
        locationRefreshProgress = findViewById(R.id.locationRefreshProgress)
        locationHistoryTitle = findViewById(R.id.locationHistoryTitle)
        locationTimelineButton = findViewById(R.id.btnLocationTimeline)
        locationHistoryEmpty = findViewById(R.id.locationHistoryEmpty)
        locationPanel = findViewById(R.id.locationPanel)
        locationStatus = findViewById(R.id.locationStatus)
        locationCoordinates = findViewById(R.id.locationCoordinates)
        locationUpdated = findViewById(R.id.locationUpdated)
        locationAccuracy = findViewById(R.id.locationAccuracy)
        openMapsButton = findViewById(R.id.btnOpenMaps)
        locationHistoryList = findViewById(R.id.locationHistoryList)

        adapter = RecordAdapter(bindLocationName = locationDisplayNames::bind, asyncUpdates = true) { record ->
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { db.dao().deleteById(record.id) }
                refreshLocal()
            }
        }
        recordList = findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
        recordDates = RecordDates(this) {
            sectionFilters[currentFilter]?.let { sectionFilters[currentFilter] = it.copy(fromMs = null, toMs = null) }
            updateFilterButton()
            applyFilter()
        }
        (recordList.parent as android.widget.LinearLayout).let { parent ->
            parent.addView(recordDates.view, parent.indexOfChild(recordList))
        }
        findViewById<Button>(R.id.btnMonitoring).setOnClickListener { monitoring.show() }

        locationHistoryAdapter = RecordAdapter(bindLocationName = locationDisplayNames::bind, asyncUpdates = true)
        locationHistoryList.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = locationHistoryAdapter
        }

        messagesButton.setOnClickListener { selectTab("message", "Messages") }
        callsButton.setOnClickListener { selectTab("call", "Calls") }
        notificationsButton.setOnClickListener { selectTab("notification", "Notifications") }
        wpCallsButton.setOnClickListener { selectTab("wp_call", "WP Calls") }
        locationButton.setOnClickListener { selectTab("location", "Location") }
        alertsButton.setOnClickListener { selectTab("alert", "Alerts / Device Status") }
        menuButton.setOnClickListener { showSettingsMenu(it) }
        filterButton.setOnClickListener { showFilterDialog() }
        locationRefreshButton.setOnClickListener { requestFreshLocationManually() }
        locationTriggerButton.setOnClickListener { showLocationTriggerEditor() }
        openMapsButton.setOnClickListener { openLatestLocationInMaps() }
        locationTimelineButton.setOnClickListener {
            startActivity(Intent(this, LocationTimelineActivity::class.java).putExtra("devicePairId", devices.activeId().orEmpty()))
        }

        findViewById<Button>(R.id.btnClearAlerts).setOnClickListener {
            val selectedPairId = devices.historyId(devices.activeId().orEmpty())
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    db.dao().clearAlerts(selectedPairId)
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

    private var liveDataJob: kotlinx.coroutines.Job? = null
    private var localRefreshGeneration = 0L

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { DeviceRecovery.purge(this@MainActivity) }
            DeviceRecovery.schedule(this@MainActivity)
        }
        locationDisplayNames.reloadSavedNames()
        if (!updateReceiverRegistered) {
            ContextCompat.registerReceiver(
                this,
                dataUpdateReceiver,
                IntentFilter(ReceiverSyncWorker.ACTION_DATA_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            updateReceiverRegistered = true
        }
        liveDataJob = lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.dao() }.observeChanges().collectLatest { refreshLocal() }
        }
        if (auth.currentUser != null) {
            ReceiverSyncRequests.catchUpAll(this)
            startQueueListener()
            prefs.getString("pairId", null)?.let { listen(it) }
        }
    }

    override fun onStop() {
        stopQueueListeners()
        liveDataJob?.cancel()
        liveDataJob = null
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
        stopQueueListeners()
        monitoring.close()
        recentAddressJob?.cancel()
        queueRegistration?.remove()
        pairRegistration?.remove()
        super.onDestroy()
    }

    private suspend fun purgeExpiredLocations() {
        val cutoff = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
        withContext(Dispatchers.IO) { db.dao().deleteLocationsOlderThan(cutoff) }
    }

    private fun selectTab(filter: String, title: String) {
        currentFilter = filter
        recordDates.select(filter)
        listTitle.text = title
        val isLocation = filter == "location"

        alertsToolbar.visibility = if (filter == "alert") View.VISIBLE else View.GONE
        deviceStatusPanel.visibility = if (filter == "alert") View.VISIBLE else View.GONE
        filterButton.visibility = if (filter == "alert" || isLocation) View.GONE else View.VISIBLE
        locationRefreshButton.visibility = if (isLocation) View.VISIBLE else View.GONE
        locationTriggerButton.visibility = if (isLocation) View.VISIBLE else View.GONE
        locationPanel.visibility = if (isLocation) View.VISIBLE else View.GONE
        recordList.visibility = if (isLocation) View.GONE else View.VISIBLE

        messagesButton.isSelected = filter == "message"
        callsButton.isSelected = filter == "call"
        notificationsButton.isSelected = filter == "notification"
        wpCallsButton.isSelected = filter == "wp_call"
        locationButton.isSelected = isLocation

        updateFilterButton()
        if (isLocation) {
            lifecycleScope.launch { refreshLocal() }
        } else {
            applyFilter()
        }
    }

    private fun showSettingsMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Pair another phone")
            menu.add(0, 4, 1, "Manage devices")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    4 -> { showDevices(); true }
                    1 -> {
                        confirmPairAnother()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun showDevices() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 8) }
        val dialog = AlertDialog.Builder(this).setTitle("Manage devices")
            .setView(ScrollView(this).apply { addView(box) }).setNegativeButton("Close", null).create()
        for (profile in devices.visible()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
            val label = TextView(this).apply {
                text = profile.name + if (profile.pairId == devices.activeId()) " ✓" else ""
                textSize = 16f; setPadding(12, 18, 8, 18)
                setOnClickListener { dialog.dismiss(); switchDevice(profile.pairId) }
            }
            row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val more = Button(this).apply {
                text = "⋮"; contentDescription = "Options for ${profile.name}"
                setOnClickListener { anchor ->
                    PopupMenu(this@MainActivity, anchor).apply {
                        menu.add("Rename").setOnMenuItemClickListener { dialog.dismiss(); renameDevice(profile.pairId); true }
                        menu.add("Remove device").setOnMenuItemClickListener { dialog.dismiss(); confirmRemoveDevice(profile.pairId); true }
                        show()
                    }
                }
            }
            row.addView(more, LinearLayout.LayoutParams(56.dp(), LinearLayout.LayoutParams.WRAP_CONTENT))
            box.addView(row)
            val detail = TextView(this).apply { text = "Loading status…"; textSize = 12f; setPadding(12, 0, 8, 12) }
            box.addView(detail)
            lifecycleScope.launch {
                val lastRecord = withContext(Dispatchers.IO) { db.dao().lastRecord(profile.historyId) }
                val lastContact = lastRecord?.let { DeviceStatus.metadata(it).optLong("receiverReceivedAtMs", it.timestamp) } ?: 0L
                val connection = when { profile.archived -> "Disconnected"; !profile.paired -> "Awaiting pairing"; else -> "Paired" }
                val last = if (lastContact > 0) SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(lastContact)) else "Not received yet"
                detail.text = "$connection · Last connected: $last"
            }
        }
        box.addView(Button(this).apply { text = "Earlier records"; isAllCaps = false; setOnClickListener { dialog.dismiss(); switchDevice("") } })
        box.addView(Button(this).apply { text = "Pair another phone"; isAllCaps = false; setOnClickListener { dialog.dismiss(); confirmPairAnother() } })
        dialog.show()
    }

    private fun Int.dp() = (this * resources.displayMetrics.density).toInt()

    private fun switchDevice(pairId: String, saveCurrent: Boolean = true) {
        if (saveCurrent) deviceFilters[devices.historyId(devices.activeId().orEmpty())] = sectionFilters.toMap()
        devices.select(pairId)
        if (pairId.isNotBlank() && devices.visible().any { it.pairId == pairId && !it.archived }) {
            ReceiverSyncRequests.enqueue(this, pairId, restart = true)
        }
        sectionFilters.clear()
        sectionFilters.putAll(deviceFilters[devices.historyId(pairId)].orEmpty())
        pairRegistration?.remove()
        pairRegistration = null
        recentAddressJob?.cancel()
        cachedRows = emptyList()
        loadedStatus = DeviceStatus()
        loadedWarnings = emptyList()
        filterGeneration++
        filterJob?.cancel()
        latestLocation = null
        activeLocationRefreshRequestId = null
        locationRefreshStage = 0
        locationRefreshButton.isEnabled = true
        locationRefreshProgress.text = "Ready · request a fresh location"
        locationDisplayNames.reloadSavedNames()
        monitoring.deviceChanged()
        displayedHistoryId = devices.historyId(pairId)
        adapter.submit(emptyList())
        locationHistoryAdapter.submit(emptyList())
        renderDeviceStatus()
        updateFilterButton()
        if (auth.currentUser != null) listen(pairId) else showDashboard()
        recordDates.select(currentFilter)
        lifecycleScope.launch { refreshLocal() }
    }

    private fun renameDevice(id: String = devices.activeId().orEmpty()) {
        val profile = devices.all().firstOrNull { it.pairId == id } ?: return
        val input = EditText(this).apply { setText(profile.name); filters = arrayOf(android.text.InputFilter.LengthFilter(60)) }
        val dialog = AlertDialog.Builder(this).setTitle("Name this device").setView(input)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) { input.error = "Enter a name"; return@setOnClickListener }
                devices.rename(id, name)
                if (devices.activeId() == id) {
                    dashboardStatus.text = devices.activeName() + if (profile.archived) " · stored records" else " · paired"
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun renderDeviceStatus() {
        val state = if (devices.activeId().isNullOrEmpty()) DeviceStatus() else loadedStatus
        val time = if (state.lastConnected > 0) SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(state.lastConnected)) else "Not received yet"
        val values = listOf(state.edition, state.notifications, state.calls, state.location, time, state.version, state.build)
        statusValues.values.zip(values).forEach { (view, value) ->
            view.text = value
            view.setTextColor(when (value) {
                "Enabled" -> 0xFF86EFAC.toInt()
                "Disabled", "Permission needed" -> 0xFFFCA5A5.toInt()
                else -> 0xFFCBD5E1.toInt()
            })
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
            .setMessage("Add a Sender without removing existing devices or their records. You can switch back at any time.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                createPairCode()
            }
            .show()
    }

    private fun confirmRemoveDevice(pairId: String) {
        val profile = devices.visible().firstOrNull { it.pairId == pairId } ?: return
        AlertDialog.Builder(this)
            .setTitle("Remove ${profile.name}?")
            .setMessage("Disconnect this device and hide its data. Pair the same Sender again within 6 hours to restore its history, name and settings. After 6 hours its saved data will be deleted. Other devices are kept.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ ->
                disconnectDevice(pairId)
            }
            .show()
    }

    private fun createPairCode() {
        createPairCodeAttempt(5)
    }

    private fun createPairCodeAttempt(remainingAttempts: Int) {
        val uid = auth.currentUser?.uid ?: return
        val publicKey = try {
            crypto.publicKeyB64()
        } catch (_: Exception) {
            pairCodeText.text = "------"
            status.text = "Could not access the secure pairing key. Restart the phone and try again; do not clear app data."
            return
        }
        val code = (SecureRandom().nextInt(900000) + 100000).toString()
        val now = System.currentTimeMillis()
        val pairDoc = mapOf(
            "receiverUid" to uid,
            "receiverPublicKeyB64" to publicKey,
            "collectorUid" to null,
            "collectorPublicKeyB64" to null,
            "createdAtMs" to now,
            "expiresAtMs" to now + 10 * 60 * 1000L,
            "locationTriggerText" to "PBLOC#${UUID.randomUUID().toString().replace("-", "")}"
        )

        pairCodeText.text = code
        status.text = "Creating secure pairing code..."
        fs.collection("pairs").document(code).set(pairDoc)
            .addOnSuccessListener {
                devices.add(code)
                switchDevice(code)
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
        ReceiverSyncRequests.catchUpAll(this)
        startQueueListener()
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
        startQueueListener()
        pairRegistration?.remove()
        queueRegistration?.remove()
        queueRegistration = null

        val profile = devices.all().firstOrNull { it.pairId == pairId }
        if (pairId.isEmpty() || profile?.archived == true) {
            showDashboard()
            dashboardStatus.text = devices.activeName() + " · stored records"
            return
        }

        val pairRef = fs.collection("pairs").document(pairId)
        pairRegistration = pairRef.addSnapshotListener { pairSnap, err ->
            if (devices.activeId() != pairId) return@addSnapshotListener
            if (err != null) {
                status.text = "Connection error: " + err.message
                return@addSnapshotListener
            }
            if (pairSnap == null || !pairSnap.exists()) {
                if (pairSnap?.metadata?.isFromCache == true) return@addSnapshotListener
                devices.archive(pairId)
                queueListeners.remove(pairId)?.remove()
                showDashboard()
                dashboardStatus.text = devices.activeName() + " · pairing unavailable (records kept)"
                return@addSnapshotListener
            }

            if (!com.phonebridge.shared.EnvelopePolicy.receiverOwns(pairSnap.data.orEmpty(), auth.currentUser?.uid, crypto.publicKeyB64())) {
                status.text = "Pairing identity mismatch. No data will be accepted."
                return@addSnapshotListener
            }

            if (pairSnap.getLong("disconnectedAtMs") != null && !pairSnap.metadata.isFromCache) {
                lifecycleScope.launch {
                    val selected = DeviceRecovery.mutex.withLock {
                        pairSnap.getString("collectorPublicKeyB64")?.let { devices.bindSender(pairId, it) }
                        val wasSelected = devices.activeId() == pairId
                        if (wasSelected) deviceFilters[devices.historyId(pairId)] = sectionFilters.toMap()
                        devices.remove(pairId, pairSnap.getLong("disconnectedAtMs")!!)
                        wasSelected
                    }
                    queueListeners.remove(pairId)?.remove()
                    DeviceRecovery.schedule(this@MainActivity)
                    if (selected) switchDevice(devices.activeId().orEmpty(), saveCurrent = false)
                }
                return@addSnapshotListener
            }

            handleLocationRefreshStatus(
                pairSnap.getString("locationRequestStatusId"),
                pairSnap.getString("locationRequestStatus")
            )
            val triggerText = pairSnap.getString("locationTriggerText")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: DEFAULT_LOCATION_TRIGGER
            locationTriggerButton.text = triggerText

            val collectorKey = pairSnap.getString("collectorPublicKeyB64")
            if (collectorKey.isNullOrBlank()) {
                showPairing()
                pairCodeText.text = pairId
                status.text = "Waiting for the Sender to enter this code..."
                return@addSnapshotListener
            }

            val wasPaired = devices.all().firstOrNull { it.pairId == pairId }?.paired == true
            if (pairSnap.metadata.isFromCache) {
                if (wasPaired) { showDashboard(); dashboardStatus.text = devices.activeName() + " · paired (offline status)" }
                return@addSnapshotListener
            }
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { DeviceRecovery.purge(this@MainActivity) }
                val oldHistoryId = devices.historyId(pairId)
                val accepted = DeviceRecovery.mutex.withLock { devices.bindSender(pairId, collectorKey) }
                if (!accepted || devices.activeId() != pairId) return@launch
                if (oldHistoryId != devices.historyId(pairId)) {
                    locationDisplayNames.reloadSavedNames()
                    monitoring.deviceChanged()
                }
                showDashboard()
                dashboardStatus.text = devices.activeName() + " · paired"
            }
        }
    }

    private fun disconnectDevice(pairId: String) {
        lifecycleScope.launch {
            try {
                val ref = fs.collection("pairs").document(pairId)
                // Keep the member UIDs on a tombstone so Sender can read revocation.
                val snap = ref.get(com.google.firebase.firestore.Source.SERVER).await()
                val key = snap.getString("collectorPublicKeyB64")
                if (key != null) DeviceRecovery.mutex.withLock { devices.bindSender(pairId, key) }
                if (snap.exists()) ref.update("disconnectedAtMs", System.currentTimeMillis()).await()
                val selected = DeviceRecovery.mutex.withLock {
                    val wasSelected = devices.activeId() == pairId
                    if (wasSelected) deviceFilters[devices.historyId(pairId)] = sectionFilters.toMap()
                    devices.remove(pairId)
                    wasSelected
                }
                queueListeners.remove(pairId)?.remove()
                DeviceRecovery.schedule(this@MainActivity)
                if (selected) switchDevice(devices.activeId().orEmpty(), saveCurrent = false)
            } catch (error: Exception) {
                AlertDialog.Builder(this@MainActivity).setTitle("Could not disconnect")
                    .setMessage("Reconnect to the internet and try again. ${error.message.orEmpty()}").setPositiveButton("OK", null).show()
            }
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

    private fun showLocationTriggerEditor() {
        val pairId = prefs.getString("pairId", null) ?: return
        if (!canRequestLocation(pairId)) return
        val input = EditText(this).apply {
            setText(locationTriggerButton.text.toString())
            setSelection(text.length)
            hint = DEFAULT_LOCATION_TRIGGER
            isSingleLine = true
        }

        AlertDialog.Builder(this)
            .setTitle("Special location text")
            .setMessage("Use this exact text in a WhatsApp, Instagram or SMS message to accelerate a pending location request.")
            .setView(input)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val value = input.text.toString().trim()
                if (value.length < 6 || value.length > 40) {
                    locationRefreshProgress.text = "Special text must be 6–40 characters"
                    return@setPositiveButton
                }
                fs.collection("pairs").document(pairId)
                    .update(
                        mapOf(
                            "locationTriggerText" to value,
                            "locationTriggerUpdatedAtMs" to System.currentTimeMillis()
                        )
                    )
                    .addOnSuccessListener {
                        locationTriggerButton.text = value
                        locationRefreshProgress.text = "Special text updated"
                    }
                    .addOnFailureListener {
                        locationRefreshProgress.text = "Could not update special text"
                    }
            }
            .show()
    }

    private fun requestFreshLocationManually() {
        val pairId = prefs.getString("pairId", null) ?: return
        if (!canRequestLocation(pairId)) return
        if (activeLocationRefreshRequestId != null) return

        val requestId = UUID.randomUUID().toString()
        activeLocationRefreshRequestId = requestId
        locationRefreshStage = 1
        locationRefreshButton.isEnabled = false
        locationRefreshProgress.text = "Request sent → waiting for Sender…"

        fs.collection("pairs")
            .document(pairId)
            .update(
                mapOf(
                    "locationRequestId" to requestId,
                    "locationRequestedAtMs" to System.currentTimeMillis(),
                    "locationRequestStatusId" to requestId,
                    "locationRequestStatus" to "requested",
                    "locationRequestStatusAtMs" to System.currentTimeMillis()
                )
            )
            .addOnFailureListener {
                activeLocationRefreshRequestId = null
                locationRefreshStage = 0
                locationRefreshButton.isEnabled = true
                locationRefreshProgress.text = "Refresh request failed · tap ↻ to retry"
            }
    }

    private fun handleLocationRefreshStatus(requestId: String?, status: String?) {
        val activeId = activeLocationRefreshRequestId ?: return
        if (requestId != activeId || status.isNullOrBlank()) return

        val stage = when (status) {
            "requested" -> 1
            "sender_received" -> 2
            "getting_location" -> 3
            "interim_sent", "receiver_saved_interim", "waiting_fresh" -> 4
            "encrypting_sending" -> 5
            "sent" -> 6
            "receiver_saved" -> 7
            "location_unavailable", "location_failed", "send_failed" -> 99
            else -> return
        }

        if (stage != 99 && stage < locationRefreshStage) return
        locationRefreshStage = stage

        locationRefreshProgress.text = when (status) {
            "requested" -> "Request sent → waiting for Sender…"
            "sender_received" -> "Request sent → Sender received"
            "getting_location" -> "Request sent → Sender received → getting fresh location…"
            "interim_sent" -> "Cached location sent → waiting for a fresh location…"
            "receiver_saved_interim" -> "Cached location saved → waiting for a fresh location…"
            "waiting_fresh" -> "Sender is still waiting for a fresh location…"
            "encrypting_sending" -> "Sender received → location received → encrypting & sending…"
            "sent" -> "Location sent → waiting for Receiver save…"
            "receiver_saved" -> "✓ Location received → saved to history"
            "location_unavailable" -> "Sender received → device Location is off"
            "location_failed" -> "Sender received → fresh location unavailable"
            "send_failed" -> "GPS received → encrypted upload failed"
            else -> locationRefreshProgress.text
        }

        if (stage == 7 || stage == 99) {
            activeLocationRefreshRequestId = null
            locationRefreshButton.isEnabled = true
        }
    }

    private fun enqueueReceiverSync() {
        for (device in devices.visible().filter { !it.archived }) {
            ReceiverSyncRequests.enqueue(this, device.pairId)
        }
    }

    private fun startQueueListener() {
        for (device in devices.visible().filter { !it.archived }) {
        val pairId = device.pairId
        if (queueListeners.containsKey(pairId)) continue
        queueListeners[pairId] = fs.collection("pairs")
            .document(pairId)
            .collection("queue")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // Firestore listener errors are terminal. Do not keep a dead
                    // registration that prevents reattachment on switch/resume.
                    queueListeners.remove(pairId)?.remove()
                    if (devices.activeId() == pairId) dashboardStatus.text = devices.activeName() + " · waiting to reconnect"
                    return@addSnapshotListener
                }

                val hasNewData = snapshot?.documentChanges?.any {
                    it.type == DocumentChange.Type.ADDED || it.type == DocumentChange.Type.MODIFIED
                } == true

                if (hasNewData) ReceiverSyncRequests.enqueue(this, pairId)
            }
        }
    }

    private fun stopQueueListeners() {
        queueListeners.values.forEach { it.remove() }
        queueListeners.clear()
    }

    private fun canRequestLocation(pairId: String): Boolean {
        val unavailable = pairId.isBlank() || devices.all().firstOrNull { it.pairId == pairId }?.archived == true
        val lite = loadedStatus.edition == "Lite"
        if (unavailable || lite) {
            AlertDialog.Builder(this).setTitle("Location unavailable")
                .setMessage(if (lite) "Sender Lite does not share location." else "This profile contains stored records and has no active pairing.")
                .setPositiveButton("OK", null).show()
            return false
        }
        return true
    }

    private fun applyFilter() {
        filterJob?.cancel()
        val generation = ++filterGeneration
        val section = currentFilter
        val historyId = devices.historyId(devices.activeId().orEmpty())
        val criteria = sectionFilters[section]
        recordDates.customRange(criteria?.fromMs, criteria?.toMs)
        val window = recordDates.window()
        val custom = criteria?.fromMs != null || criteria?.toMs != null
        val from = if (custom) criteria?.fromMs ?: Long.MIN_VALUE else window.first
        val to = if (custom) criteria?.toMs ?: Long.MAX_VALUE else window.second - 1
        val warnings = loadedWarnings
        val selection = listOf(historyId, section, from, to, criteria)
        if (selection != filterSelection) {
            filterSelection = selection
            adapter.submit(emptyList()) // Never display the previous device/date while loading.
        }
        filterJob = lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val types = when (section) {
                    "notification" -> listOf("notification", "message", "wp_call")
                    "message", "call", "wp_call" -> listOf(section)
                    else -> emptyList()
                }
                val base = if (section == "alert") warnings else if (types.isEmpty()) emptyList()
                    else MessageDuplicates.visible(db.dao().period(historyId, types, from, to))
                base.filter { row ->
                    (section != "message" || !isWhatsAppBackgroundCheck(row)) &&
                        (criteria == null || !criteria.isActive() || criteria.matches(row))
                }
            }
            if (generation == filterGeneration && currentFilter == section &&
                historyId == devices.historyId(devices.activeId().orEmpty())) adapter.submit(rows)
        }
    }

    // Also hides old misclassified records and records from older Sender versions.
    // Stored history is preserved and remains visible in Notifications.
    private fun isWhatsAppBackgroundCheck(record: RecordEntity): Boolean {
        val metadata = try { JSONObject(record.metadataJson) } catch (_: Exception) { null }
        return WhatsAppNotificationClassifier.isBackgroundCheck(
            packageName = record.source,
            title = record.title,
            body = record.body,
            conversationTitle = metadata?.optString("conversationTitle").orEmpty(),
            hasConversationMessages = metadata?.optBoolean("hasConversationMessages", false) == true
        )
    }

    private suspend fun refreshLocal() {
        val generation = ++localRefreshGeneration
        val selectedId = devices.activeId().orEmpty()
        val historyId = devices.historyId(selectedId)
        val section = currentFilter
        val snapshot = withContext(Dispatchers.IO) {
            val dao = db.dao()
            val states = dao.statusRows(historyId)
            val locations = if (section == "location" || activeLocationRefreshRequestId != null)
                dao.period(historyId, listOf("location"), Long.MIN_VALUE, Long.MAX_VALUE)
                else listOfNotNull(dao.latestLocation(historyId))
            Triple(states + locations, DeviceStatus.from(states).copy(lastConnected = dao.lastConnected(historyId)), DeviceStatus.warnings(states))
        }
        val rows = snapshot.first
        if (generation != localRefreshGeneration || selectedId != devices.activeId().orEmpty() || historyId != devices.historyId(selectedId)) return
        if (displayedHistoryId != historyId) {
            displayedHistoryId = historyId
            locationDisplayNames.reloadSavedNames()
            monitoring.deviceChanged()
            sectionFilters.clear()
            sectionFilters.putAll(deviceFilters[historyId].orEmpty())
            updateFilterButton()
        }
        cachedRows = rows
        loadedStatus = snapshot.second
        loadedWarnings = snapshot.third
        renderDeviceStatus()
        monitoring.refresh()
        val locationRows = cachedRows.filter { it.type == "location" }
        latestLocation = locationRows.firstOrNull()
        locationHistoryAdapter.submit(locationRows)
        locationHistoryTitle.text = "Location history · " + locationRows.size
        locationHistoryEmpty.visibility = if (locationRows.isEmpty()) View.VISIBLE else View.GONE
        locationHistoryList.visibility = if (locationRows.isEmpty()) View.GONE else View.VISIBLE

        val activeRequestId = activeLocationRefreshRequestId
        if (activeRequestId != null) {
            val savedForRequest = locationRows.firstOrNull { row ->
                try {
                    JSONObject(row.metadataJson).optString("requestId") == activeRequestId
                } catch (_: Exception) {
                    false
                }
            }
            if (savedForRequest != null) {
                val interim = try {
                    JSONObject(savedForRequest.metadataJson)
                        .optString("locationFreshness") == "cached_interim"
                } catch (_: Exception) {
                    false
                }
                if (interim) {
                    if (locationRefreshStage < 5) {
                        locationRefreshStage = 4
                        locationRefreshProgress.text =
                            "Cached location saved → waiting for a fresh location…"
                    }
                } else {
                    locationRefreshStage = 7
                    locationRefreshProgress.text = "✓ Location received → saved to history"
                    activeLocationRefreshRequestId = null
                    locationRefreshButton.isEnabled = true
                }
            }
        }

        val warningCount = loadedWarnings.size
        alertsButton.text = if (warningCount > 0) "🔔 " + warningCount else "🔔"

        if (currentFilter == "location") {
            refreshLocationView()
        } else {
            applyFilter()
        }
    }

    private fun refreshLocationView() {
        recentAddressJob?.cancel()
        recentAddressJob = null
        val record = latestLocation
        if (record == null) {
            locationStatus.text = "Waiting for Sender location"
            locationCoordinates.text = "No location received yet"
            locationUpdated.text = "Updated: —"
            locationAccuracy.text = "Accuracy: —"
            openMapsButton.isEnabled = false
            return
        }

        val meta = try {
            JSONObject(record.metadataJson)
        } catch (_: Exception) {
            JSONObject()
        }

        val latitude = meta.optString("latitude")
        val longitude = meta.optString("longitude")
        val accuracy = meta.optString("accuracyMeters")
        val ageMs = (System.currentTimeMillis() - record.timestamp).coerceAtLeast(0L)

        locationStatus.text = when {
            meta.optString("locationFreshness") == "cached_interim" -> "Cached · waiting for new fix"
            ageMs < 5 * 60 * 1000L -> "Live"
            ageMs <= 30 * 60 * 1000L -> "Recent"
            else -> "Old location"
        }

        recentAddressJob = locationDisplayNames.bind(record, locationCoordinates)

        locationUpdated.text = "Updated: " +
            SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                .format(Date(record.timestamp))

        locationAccuracy.text = if (accuracy.isNotBlank()) {
            "Accuracy: ±$accuracy m"
        } else {
            "Accuracy: —"
        }

        openMapsButton.isEnabled = latitude.toDoubleOrNull() != null &&
            longitude.toDoubleOrNull() != null
    }

    private fun openLatestLocationInMaps() {
        val record = latestLocation ?: return
        val meta = try {
            JSONObject(record.metadataJson)
        } catch (_: Exception) {
            return
        }

        val latitude = meta.optString("latitude").toDoubleOrNull() ?: return
        val longitude = meta.optString("longitude").toDoubleOrNull() ?: return
        val label = Uri.encode("Sender location")
        val geoUri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude($label)")

        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, geoUri)
                    .setPackage("com.google.android.apps.maps")
            )
        } catch (_: ActivityNotFoundException) {
            val webUri = Uri.parse(
                "https://www.google.com/maps/search/?api=1&query=$latitude,$longitude"
            )
            startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    }
}
