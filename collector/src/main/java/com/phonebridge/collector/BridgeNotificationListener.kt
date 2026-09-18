package com.phonebridge.collector

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.phonebridge.shared.PlainRecord

class BridgeNotificationListener : NotificationListenerService() {

    private val voipPrefs by lazy {
        getSharedPreferences("voip_call_sessions", MODE_PRIVATE)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        sendAlert("Notification access active", "Notification access is enabled on the Sender.", "resolved")
    }

    override fun onListenerDisconnected() {
        sendAlert("Notification access disabled", "Notification access is not active on the Sender.", "warning")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val prefs = BridgePrefs(this)
        if (prefs.pairId == null || prefs.receiverPublicKey == null) return
        if (prefs.pairedAtMs > 0L && sbn.postTime < prefs.pairedAtMs) return

        val n = sbn.notification
        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val summary = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString().orEmpty()
        val conversation = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.map { it.toString() }
            ?.filter { it.isNotBlank() }
            .orEmpty()

        val combinedRaw = listOf(title, text, subText, bigText, summary, conversation)
            .plus(textLines)
            .joinToString(" ")
        val combined = combinedRaw.lowercase()

        if (listOf("otp", "verification code", "security code", "one-time password").any { it in combined }) return

        val appName = appNameFor(sbn.packageName)
        val isVoipCall = isVoipPackage(sbn.packageName) && isCallNotification(n, combined)

        if (isVoipCall) {
            sendVoipCallUpdate(
                sbn = sbn,
                appName = appName,
                title = title.ifBlank { appName },
                bodyText = bestBody(bigText, textLines, text),
                detectedState = detectCallState(combined)
            )
            return
        }

        val isMessage = n.category == Notification.CATEGORY_MESSAGE || isMessagingPackage(sbn.packageName)
        val metadata = linkedMapOf(
            "appName" to appName,
            "packageName" to sbn.packageName,
            "category" to (n.category ?: ""),
            "sender" to title,
            "messageText" to text,
            "conversationTitle" to conversation,
            "subText" to subText,
            "summaryText" to summary,
            "bigText" to bigText,
            "textLines" to textLines.joinToString("\n"),
            "notificationKey" to sbn.key
        )

        val record = PlainRecord(
            type = if (isMessage) "message" else "notification",
            timestamp = sbn.postTime,
            source = sbn.packageName,
            title = if (title.isNotBlank()) title else appName,
            body = bestBody(bigText, textLines, text),
            metadata = metadata
        )

        val relay = FirebaseRelay(this)
        relay.ensureSignedIn { ok, _ -> if (ok) relay.send(record) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!isVoipPackage(sbn.packageName)) return

        val title = sbn.notification.extras
            .getCharSequence(Notification.EXTRA_TITLE)
            ?.toString()
            .orEmpty()
        val slot = sessionSlot(sbn.packageName, title)
        val sessionKey = voipPrefs.getString(slot, null) ?: return
        val history = voipPrefs.getString(historyKey(sessionKey), "").orEmpty()
        if (history.isBlank() || history.endsWith("Missed") || history.endsWith("Ended")) {
            clearSession(slot, sessionKey)
            return
        }

        sendVoipCallUpdate(
            sbn = sbn,
            appName = appNameFor(sbn.packageName),
            title = title.ifBlank { appNameFor(sbn.packageName) },
            bodyText = "Call ended",
            detectedState = "Ended"
        )
    }

    private fun sendVoipCallUpdate(
        sbn: StatusBarNotification,
        appName: String,
        title: String,
        bodyText: String,
        detectedState: String
    ) {
        val slot = sessionSlot(sbn.packageName, title)
        var sessionKey = voipPrefs.getString(slot, null)
        if (sessionKey == null) {
            sessionKey = "${sbn.packageName}|${title.lowercase()}|${System.currentTimeMillis()}"
            voipPrefs.edit()
                .putString(slot, sessionKey)
                .putLong(startKey(sessionKey), System.currentTimeMillis())
                .apply()
        }

        val previous = voipPrefs.getString(historyKey(sessionKey), "").orEmpty()
        val history = appendState(previous, detectedState)
        val startedAt = voipPrefs.getLong(startKey(sessionKey), sbn.postTime)
        val finalState = detectedState == "Ended" || detectedState == "Missed"
        val durationSec = if (detectedState == "Ended") {
            ((System.currentTimeMillis() - startedAt).coerceAtLeast(0L) / 1000L)
        } else {
            0L
        }

        voipPrefs.edit().putString(historyKey(sessionKey), history).apply()

        val metadata = linkedMapOf(
            "appName" to appName,
            "packageName" to sbn.packageName,
            "notificationKey" to sbn.key,
            "sessionKey" to sessionKey,
            "callState" to detectedState,
            "callType" to detectedState,
            "stateHistory" to history,
            "startedAtMs" to startedAt.toString(),
            "updatedAtMs" to System.currentTimeMillis().toString(),
            "durationSec" to durationSec.toString()
        )

        val relay = FirebaseRelay(this)
        relay.ensureSignedIn { ok, _ ->
            if (ok) {
                relay.send(
                    PlainRecord(
                        type = "wp_call",
                        timestamp = startedAt,
                        source = sbn.packageName,
                        title = title,
                        body = history.ifBlank { bodyText.ifBlank { "VoIP call" } },
                        metadata = metadata
                    )
                ) { success, _ ->
                    if (success && finalState) clearSession(slot, sessionKey)
                }
            }
        }
    }

    private fun appendState(history: String, state: String): String {
        if (state.isBlank() || state == "Call") return history.ifBlank { "Call" }
        if (history.isBlank() || history == "Call") return state
        val states = history.split(" → ")
        if (states.lastOrNull() == state) return history
        return "$history → $state"
    }

    private fun clearSession(slot: String, sessionKey: String) {
        voipPrefs.edit()
            .remove(slot)
            .remove(historyKey(sessionKey))
            .remove(startKey(sessionKey))
            .apply()
    }

    private fun sessionSlot(packageName: String, title: String): String =
        "active_${packageName}_${title.lowercase().hashCode()}"

    private fun historyKey(sessionKey: String): String = "history_${sessionKey.hashCode()}"

    private fun startKey(sessionKey: String): String = "start_${sessionKey.hashCode()}"

    private fun bestBody(bigText: String, lines: List<String>, text: String): String = when {
        bigText.isNotBlank() -> bigText
        lines.isNotEmpty() -> lines.joinToString("\n")
        else -> text
    }

    private fun appNameFor(packageName: String): String = try {
        val ai = packageManager.getApplicationInfo(packageName, 0)
        packageManager.getApplicationLabel(ai).toString()
    } catch (_: Exception) {
        packageName
    }

    private fun isCallNotification(notification: Notification, text: String): Boolean {
        if (notification.category == Notification.CATEGORY_CALL) return true
        return listOf(
            "incoming call", "outgoing call", "calling", "ringing", "ongoing call",
            "missed call", "video call", "voice call", "call ended"
        ).any { it in text }
    }

    private fun detectCallState(text: String): String = when {
        "missed call" in text -> "Missed"
        "call ended" in text || "ended" in text -> "Ended"
        "ongoing" in text -> "Ongoing"
        "incoming" in text || "ringing" in text -> "Incoming"
        "outgoing" in text || "calling" in text -> "Outgoing"
        else -> "Call"
    }

    private fun isVoipPackage(packageName: String): Boolean {
        val p = packageName.lowercase()
        return listOf(
            "whatsapp", "telegram", "signal", "messenger", "facebook.orca",
            "instagram", "meet", "skype", "teams", "viber"
        ).any { it in p }
    }

    private fun isMessagingPackage(packageName: String): Boolean {
        val p = packageName.lowercase()
        return listOf(
            "whatsapp", "telegram", "signal", "messenger", "facebook.orca",
            "instagram", "snapchat", "messages", "messaging"
        ).any { it in p }
    }

    private fun sendAlert(title: String, body: String, severity: String) {
        val prefs = BridgePrefs(this)
        if (prefs.pairId == null || prefs.receiverPublicKey == null) return
        val relay = FirebaseRelay(this)
        relay.ensureSignedIn { ok, _ ->
            if (ok) {
                relay.send(
                    PlainRecord(
                        type = "alert",
                        timestamp = System.currentTimeMillis(),
                        source = "sender",
                        title = title,
                        body = body,
                        metadata = mapOf("severity" to severity, "kind" to "notification_access")
                    )
                )
            }
        }
    }
}
