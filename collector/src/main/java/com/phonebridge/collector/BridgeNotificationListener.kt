package com.phonebridge.collector

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.phonebridge.shared.PlainRecord

class BridgeNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val prefs = BridgePrefs(this)
        if (prefs.pairId == null) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

        // Skip obvious authentication/security codes.
        val combined = "$title $text".lowercase()
        if (listOf("otp", "verification code", "security code", "one-time password").any { it in combined }) return

        val record = PlainRecord(
            type = "notification",
            timestamp = sbn.postTime,
            source = sbn.packageName,
            title = title,
            body = text,
            metadata = mapOf("key" to sbn.key)
        )

        val relay = FirebaseRelay(this)
        relay.ensureSignedIn { ok, _ -> if (ok) relay.send(record) }
    }
}
