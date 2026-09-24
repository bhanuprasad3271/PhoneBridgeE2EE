package com.phonebridge.receiver

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimelineTextRows {
    fun role(entry: TimelineEntry, index: Int, count: Int, staysOnly: Boolean = false): String {
        if (staysOnly) return "Stayed"
        val endpoint = when {
            count == 1 -> "Start / End"
            index == 0 -> "Start"
            index == count - 1 -> "End"
            else -> null
        }
        return when {
            entry is TimelineEntry.Stay && endpoint != null -> "$endpoint · Stayed"
            entry is TimelineEntry.Stay -> "Stayed"
            endpoint != null -> endpoint
            else -> "Point"
        }
    }

    fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0L -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

    fun text(entry: TimelineEntry, role: String, name: String): String {
        val clock = SimpleDateFormat("hh:mm a", Locale.getDefault())
        val first = clock.format(Date(entry.firstRecordedAt))
        val timing = if (entry is TimelineEntry.Stay) {
            "$first – ${clock.format(Date(entry.lastRecordedAt))} · ${duration(entry.recordedDurationMs)}"
        } else first
        return "$role · $name\n$timing"
    }
}
