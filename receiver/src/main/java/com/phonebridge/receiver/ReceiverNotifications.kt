package com.phonebridge.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject

class ReceiverNotifications(private val context: Context) {
    companion object {
        const val PREFS = "receiver_local_notifications"
        const val KEY_MASTER = "master"
        const val KEY_MESSAGES = "messages"
        const val KEY_CALLS = "calls"
        const val KEY_NOTIFICATIONS = "notifications"
        const val KEY_WP_CALLS = "wp_calls"
        const val KEY_ALERTS = "alerts"
        const val KEY_HIDE_CONTENT = "hide_content"
        const val KEY_APP_FILTER = "app_filter"
        const val KEY_CONTACT_FILTER = "contact_filter"
        const val KEY_NUMBER_FILTER = "number_filter"
        const val KEY_USERNAME_FILTER = "username_filter"
        const val KEY_INCOMING = "incoming"
        const val KEY_OUTGOING = "outgoing"
        const val KEY_MISSED = "missed"
        const val KEY_REJECTED = "rejected"

        const val EXTRA_SECTION = "open_section"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channels = listOf(
            NotificationChannel("pb_messages", "Messages", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel("pb_calls", "Calls", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel("pb_notifications", "Synced notifications", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel("pb_wp_calls", "WP Calls", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel("pb_alerts", "Sender alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        manager.createNotificationChannels(channels)
    }

    fun post(record: RecordEntity, localId: String) {
        if (!allows(record)) return

        val meta = try { JSONObject(record.metadataJson) } catch (_: Exception) { JSONObject() }
        val section = when (record.type) {
            "message" -> "message"
            "call" -> "call"
            "wp_call" -> "wp_call"
            "alert", "status" -> "alert"
            else -> "notification"
        }
        val channel = when (record.type) {
            "message" -> "pb_messages"
            "call" -> "pb_calls"
            "wp_call" -> "pb_wp_calls"
            "alert", "status" -> "pb_alerts"
            else -> "pb_notifications"
        }

        val app = meta.optString("appName", record.source)
        val hideContent = prefs.getBoolean(KEY_HIDE_CONTENT, false)
        val title = when (record.type) {
            "message" -> if (app.isNotBlank()) "$app · ${record.title}" else record.title
            "wp_call" -> if (app.isNotBlank()) "$app · ${record.title}" else record.title
            "call" -> record.title
            "alert", "status" -> record.title
            else -> if (app.isNotBlank()) app else record.title
        }.ifBlank { "PhoneBridge Receiver" }

        val body = if (hideContent) {
            when (record.type) {
                "message" -> "New message received"
                "call" -> "Call status updated"
                "wp_call" -> "VoIP call status updated"
                "alert", "status" -> "Sender status changed"
                else -> "New notification received"
            }
        } else {
            when (record.type) {
                "call", "wp_call" -> meta.optString("stateHistory", record.body)
                else -> record.body
            }.ifBlank { "New synced activity" }
        }

        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_SECTION, section)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        val pendingIntent = PendingIntent.getActivity(
            context,
            localId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(record.type == "call" || record.type == "wp_call")
            .build()

        try {
            NotificationManagerCompat.from(context).notify(localId.hashCode(), notification)
        } catch (_: SecurityException) {
            // Android 13+ notification permission may not yet be granted.
        }
    }

    private fun allows(record: RecordEntity): Boolean {
        if (!prefs.getBoolean(KEY_MASTER, true)) return false

        val categoryAllowed = when (record.type) {
            "message" -> prefs.getBoolean(KEY_MESSAGES, true)
            "call" -> prefs.getBoolean(KEY_CALLS, true)
            "wp_call" -> prefs.getBoolean(KEY_WP_CALLS, true)
            "alert", "status" -> prefs.getBoolean(KEY_ALERTS, true)
            else -> prefs.getBoolean(KEY_NOTIFICATIONS, true)
        }
        if (!categoryAllowed) return false

        val meta = try { JSONObject(record.metadataJson) } catch (_: Exception) { JSONObject() }
        val appNeedle = prefs.getString(KEY_APP_FILTER, "").orEmpty().trim()
        val contactNeedle = prefs.getString(KEY_CONTACT_FILTER, "").orEmpty().trim()
        val numberNeedle = prefs.getString(KEY_NUMBER_FILTER, "").orEmpty().trim()
        val usernameNeedle = prefs.getString(KEY_USERNAME_FILTER, "").orEmpty().trim()

        val appText = listOf(record.source, meta.optString("appName"), meta.optString("packageName")).joinToString(" ")
        val contactText = listOf(record.title, meta.optString("contactName"), meta.optString("sender")).joinToString(" ")
        val numberText = listOf(record.title, meta.optString("number")).joinToString(" ")
        val usernameText = listOf(record.title, meta.optString("sender"), meta.optString("conversationTitle")).joinToString(" ")

        if (appNeedle.isNotBlank() && !appText.contains(appNeedle, true)) return false
        if (contactNeedle.isNotBlank() && !contactText.contains(contactNeedle, true)) return false
        if (numberNeedle.isNotBlank() && !numberText.contains(numberNeedle, true)) return false
        if (usernameNeedle.isNotBlank() && !usernameText.contains(usernameNeedle, true)) return false

        if (record.type == "call" || record.type == "wp_call") {
            val state = listOf(
                record.body,
                meta.optString("callType"),
                meta.optString("callState"),
                meta.optString("stateHistory")
            ).joinToString(" ")

            val recognized = listOf("Incoming", "Outgoing", "Missed", "Rejected").filter { state.contains(it, true) }
            if (recognized.isNotEmpty()) {
                val allowed = recognized.any {
                    when (it) {
                        "Incoming" -> prefs.getBoolean(KEY_INCOMING, true)
                        "Outgoing" -> prefs.getBoolean(KEY_OUTGOING, true)
                        "Missed" -> prefs.getBoolean(KEY_MISSED, true)
                        "Rejected" -> prefs.getBoolean(KEY_REJECTED, true)
                        else -> true
                    }
                }
                if (!allowed) return false
            }
        }

        return true
    }
}
