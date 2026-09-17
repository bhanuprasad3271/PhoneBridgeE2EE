package com.phonebridge.collector

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.phonebridge.shared.PlainRecord

class BridgeNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        sendAlert("Notification access active", "Notification access is enabled on Phone 2.", "resolved")
    }

    override fun onListenerDisconnected() {
        sendAlert("Notification access disabled", "Notification access is not active on Phone 2.", "warning")
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

        val appName = try {
            val ai = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            sbn.packageName
        }

        val isVoipCall = isVoipPackage(sbn.packageName) && isCallNotification(n, combined)
        val isMessage = !isVoipCall && (n.category == Notification.CATEGORY_MESSAGE || isMessagingPackage(sbn.packageName))
        val bestBody = when {
            bigText.isNotBlank() -> bigText
            textLines.isNotEmpty() -> textLines.joinToString("\n")
            else -> text
        }

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

        val type: String
        val body: String
        if (isVoipCall) {
            type = "wp_call"
            val callState = detectCallState(combined)
            metadata["callState"] = callState
            metadata["callType"] = callState
            metadata["durationSec"] = "0"
            body = when {
                bestBody.isNotBlank() -> bestBody
                callState.isNotBlank() -> callState
                else -> "VoIP call"
            }
        } else {
            type = if (isMessage) "message" else "notification"
            body = bestBody
        }

        val record = PlainRecord(
            type = type,
            timestamp = sbn.postTime,
            source = sbn.packageName,
            title = if (title.isNotBlank()) title else appName,
            body = body,
            metadata = metadata
        )

        val relay = FirebaseRelay(this)
        relay.ensureSignedIn { ok, _ -> if (ok) relay.send(record) }
    }

    private fun isCallNotification(notification: Notification, text: String): Boolean {
        if (notification.category == Notification.CATEGORY_CALL) return true
        return listOf(
            "incoming call", "outgoing call", "calling", "ringing", "ongoing call",
            "missed call", "video call", "voice call", "call ended"
        ).any { it in text }
    }

    private fun detectCallState(text: String): String = when {
        "missed call" in text -> "missed"
        "outgoing" in text || "calling" in text -> "outgoing"
        "incoming" in text || "ringing" in text -> "incoming"
        "ongoing" in text -> "ongoing"
        "call ended" in text || "ended" in text -> "ended"
        else -> "call"
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
                        source = "collector",
                        title = title,
                        body = body,
                        metadata = mapOf("severity" to severity, "kind" to "notification_access")
                    )
                )
            }
        }
    }
}
