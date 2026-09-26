package com.phonebridge.shared

import java.util.Locale

/** A narrow match for background checks, never a keyword filter over conversations. */
object WhatsAppNotificationClassifier {
    private val packages = setOf("com.whatsapp", "com.whatsapp.w4b")
    private val appTitles = setOf("whatsapp", "whatsapp business", "whatsapp messenger")
    private val backgroundChecks = setOf("checking for new messages", "checking for messages")

    fun isBackgroundCheck(
        packageName: String,
        title: String,
        body: String,
        conversationTitle: String = "",
        hasConversationMessages: Boolean = false
    ): Boolean {
        if (packageName.lowercase(Locale.ROOT) !in packages) return false
        if (hasConversationMessages || conversationTitle.isNotBlank()) return false
        if (normalize(title) !in appTitles) return false
        return normalize(body) in backgroundChecks
    }

    private fun normalize(value: String): String = value
        .replace('\u00a0', ' ')
        .trim()
        .trimEnd('.', '\u2026')
        .trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)
}
