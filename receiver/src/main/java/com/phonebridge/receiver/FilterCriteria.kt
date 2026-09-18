package com.phonebridge.receiver

import org.json.JSONObject

data class FilterCriteria(
    val app: String = "",
    val nameOrContact: String = "",
    val number: String = "",
    val username: String = "",
    val fromMs: Long? = null,
    val toMs: Long? = null,
    val states: Set<String> = emptySet()
) {
    fun isActive(): Boolean =
        app.isNotBlank() ||
            nameOrContact.isNotBlank() ||
            number.isNotBlank() ||
            username.isNotBlank() ||
            fromMs != null ||
            toMs != null ||
            states.isNotEmpty()

    fun matches(record: RecordEntity): Boolean {
        val meta = try { JSONObject(record.metadataJson) } catch (_: Exception) { JSONObject() }

        fun containsIgnoreCase(haystack: String, needle: String): Boolean =
            needle.isBlank() || haystack.contains(needle, ignoreCase = true)

        val appText = listOf(
            record.source,
            meta.optString("appName"),
            meta.optString("packageName")
        ).joinToString(" ")

        val nameText = listOf(
            record.title,
            meta.optString("contactName"),
            meta.optString("sender"),
            meta.optString("conversationTitle")
        ).joinToString(" ")

        val numberText = listOf(
            meta.optString("number"),
            record.title,
            record.body
        ).joinToString(" ")

        val usernameText = listOf(
            record.title,
            meta.optString("sender"),
            meta.optString("conversationTitle"),
            meta.optString("username")
        ).joinToString(" ")

        if (!containsIgnoreCase(appText, app)) return false
        if (!containsIgnoreCase(nameText, nameOrContact)) return false
        if (!containsIgnoreCase(numberText, number)) return false
        if (!containsIgnoreCase(usernameText, username)) return false

        fromMs?.let { if (record.timestamp < it) return false }
        toMs?.let { if (record.timestamp > it) return false }

        if (states.isNotEmpty()) {
            val stateText = listOf(
                record.body,
                meta.optString("callType"),
                meta.optString("callState"),
                meta.optString("stateHistory")
            ).joinToString(" ")
            if (states.none { stateText.contains(it, ignoreCase = true) }) return false
        }

        return true
    }
}
