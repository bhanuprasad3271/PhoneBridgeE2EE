package com.phonebridge.receiver

import java.util.Locale

data class MonitorIdentity(
    val id: String, val label: String, val kind: String, val value: String,
    val partial: Boolean = false, val enabled: Boolean = true
)

object MonitorMatcher {
    val kinds = listOf("Phone", "Instagram", "Snapchat", "WhatsApp", "Custom")

    fun phone(value: String): String {
        val digits = value.filter { it.isDigit() }
        return when {
            digits.length == 12 && digits.startsWith("91") -> digits.drop(2)
            digits.length == 11 && digits.startsWith("0") -> digits.drop(1)
            else -> digits
        }
    }

    fun matches(identity: MonitorIdentity, source: String, fields: List<String>): Boolean {
        return prepare(identity)(source, fields)
    }

    /** Compile once per search instead of once per record/field. */
    fun prepare(identity: MonitorIdentity): (String, List<String>) -> Boolean {
        val numeric = identity.kind == "Phone" ||
            identity.kind == "WhatsApp" && identity.value.count { it.isDigit() } >= 7
        val wantedPhone = phone(identity.value)
        val wanted = identity.value.trim().removePrefix("@").lowercase(Locale.ROOT)
        val pattern = Regex("(?<![a-z0-9_.])" + Regex.escape(wanted) + "(?![a-z0-9_.])", RegexOption.IGNORE_CASE)
        val phonePattern = Regex("""(?<![\w])\+?\d[\d ()-]{5,}\d(?![\w])""")
        return match@ { source, fields ->
            if (!identity.enabled || wanted.isBlank()) return@match false
            val app = source.lowercase(Locale.ROOT)
            if (identity.kind == "Instagram" && !app.contains("instagram")) return@match false
            if (identity.kind == "Snapchat" && !app.contains("snapchat")) return@match false
            if (identity.kind == "WhatsApp" && !app.contains("whatsapp")) return@match false
            if (numeric) {
                wantedPhone.length >= 7 && fields.any { field ->
                    phonePattern.findAll(field).any { phone(it.value) == wantedPhone }
                }
            } else {
                fields.any { if (identity.partial) it.lowercase(Locale.ROOT).contains(wanted) else pattern.containsMatchIn(it) }
            }
        }
    }
}
