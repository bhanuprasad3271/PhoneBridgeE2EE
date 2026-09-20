package com.phonebridge.collector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.phonebridge.shared.PlainRecord
import java.util.Locale

class LocationSharingService : Service() {

    companion object {
        const val ACTION_START = "com.phonebridge.collector.action.START_LOCATION_SHARING"
        const val ACTION_REFRESH = "com.phonebridge.collector.action.REFRESH_LOCATION"

        private const val CHANNEL_ID = "phonebridge_location_sharing"
        private const val NOTIFICATION_ID = 4102
        private const val INTERVAL_MS = 15 * 60 * 1000L
        private const val FRESH_FIX_TIMEOUT_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { BridgePrefs(this) }
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }

    private var requestRegistration: ListenerRegistration? = null
    private var cancellationTokenSource: CancellationTokenSource? = null
    private var fetchInProgress = false
    private var pendingReceiverRequestId: String? = null
    private var pendingPeriodicRefresh = false

    private val periodicRunnable = object : Runnable {
        override fun run() {
            requestFreshLocation(reason = "periodic")
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!canShareLocation()) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, buildNotification())
        attachReceiverRequestListener()

        handler.removeCallbacks(periodicRunnable)
        handler.postDelayed(periodicRunnable, INTERVAL_MS)

        when (intent?.action) {
            ACTION_REFRESH -> requestFreshLocation(reason = "manual")
            else -> requestFreshLocation(reason = "service_start")
        }

        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(periodicRunnable)
        requestRegistration?.remove()
        requestRegistration = null
        cancellationTokenSource?.cancel()
        cancellationTokenSource = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun canShareLocation(): Boolean {
        if (!prefs.locationSharingEnabled) return false
        if (prefs.pairId.isNullOrBlank() || prefs.receiverPublicKey.isNullOrBlank()) return false
        return LocationSharingScheduler.hasBackgroundLocation(this)
    }

    private fun attachReceiverRequestListener() {
        if (requestRegistration != null) return

        val pairId = prefs.pairId ?: return
        val relay = FirebaseRelay(this)
        relay.ensureSignedIn { ok, _ ->
            if (!ok || requestRegistration != null) return@ensureSignedIn

            requestRegistration = FirebaseFirestore.getInstance()
                .collection("pairs")
                .document(pairId)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null || !snapshot.exists()) {
                        return@addSnapshotListener
                    }

                    val requestId = snapshot.getString("locationRequestId").orEmpty()
                    if (requestId.isBlank()) return@addSnapshotListener
                    if (requestId == prefs.lastHandledLocationRequestId) return@addSnapshotListener

                    requestFreshLocation(
                        reason = "receiver_open",
                        receiverRequestId = requestId
                    )
                }
        }
    }

    private fun requestFreshLocation(
        reason: String,
        receiverRequestId: String? = null
    ) {
        if (!canShareLocation()) {
            stopSelf()
            return
        }

        if (fetchInProgress) {
            if (receiverRequestId != null) {
                pendingReceiverRequestId = receiverRequestId
            } else if (reason == "periodic") {
                pendingPeriodicRefresh = true
            }
            return
        }

        val locationManager = getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && !locationManager.isLocationEnabled) {
            finishFetch(receiverRequestId)
            return
        }

        fetchInProgress = true
        val tokenSource = CancellationTokenSource()
        cancellationTokenSource = tokenSource

        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0L)
            .setDurationMillis(FRESH_FIX_TIMEOUT_MS)
            .build()

        try {
            fused.getCurrentLocation(request, tokenSource.token)
                .addOnSuccessListener { location ->
                    if (location == null) {
                        finishFetch(receiverRequestId)
                    } else {
                        sendLocation(location, reason, receiverRequestId)
                    }
                }
                .addOnFailureListener {
                    finishFetch(receiverRequestId)
                }
                .addOnCanceledListener {
                    finishFetch(receiverRequestId)
                }
        } catch (_: SecurityException) {
            finishFetch(receiverRequestId)
        }
    }

    private fun sendLocation(
        location: Location,
        reason: String,
        receiverRequestId: String?
    ) {
        val now = System.currentTimeMillis()
        val timestamp = if (location.time > 0L) location.time else now
        val relay = FirebaseRelay(this)

        relay.ensureSignedIn { signedIn, _ ->
            if (!signedIn) {
                finishFetch(receiverRequestId)
                return@ensureSignedIn
            }

            relay.send(
                PlainRecord(
                    type = "location",
                    timestamp = timestamp,
                    source = "sender",
                    title = "Sender location",
                    body = "Encrypted location update",
                    metadata = mapOf(
                        "latitude" to String.format(Locale.US, "%.6f", location.latitude),
                        "longitude" to String.format(Locale.US, "%.6f", location.longitude),
                        "accuracyMeters" to String.format(Locale.US, "%.1f", location.accuracy),
                        "provider" to (location.provider ?: "fused"),
                        "locationTimeMs" to timestamp.toString(),
                        "collectedAtMs" to now.toString(),
                        "trigger" to reason
                    )
                )
            ) { sent, _ ->
                if (sent && receiverRequestId != null) {
                    prefs.lastHandledLocationRequestId = receiverRequestId
                }
                finishFetch(receiverRequestId)
            }
        }
    }

    private fun finishFetch(receiverRequestId: String?) {
        fetchInProgress = false
        cancellationTokenSource = null

        val pendingRequest = pendingReceiverRequestId
        if (pendingRequest != null &&
            pendingRequest != prefs.lastHandledLocationRequestId &&
            pendingRequest != receiverRequestId
        ) {
            pendingReceiverRequestId = null
            handler.post {
                requestFreshLocation(
                    reason = "receiver_open",
                    receiverRequestId = pendingRequest
                )
            }
            return
        }

        pendingReceiverRequestId = null

        if (pendingPeriodicRefresh) {
            pendingPeriodicRefresh = false
            handler.post { requestFreshLocation(reason = "periodic") }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Location sharing",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while PhoneBridge is actively sharing location."
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification() =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("PhoneBridge")
            .setContentText("Location sharing active")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
