package com.phonebridge.receiver

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class TimelineTextColumns(val time: String, val place: String, val duration: String)

object TimelineTextRows {
    fun duration(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0L -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

    fun columns(
        entry: TimelineEntry,
        name: String,
        customName: Boolean = false,
        locale: Locale = Locale.getDefault(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): TimelineTextColumns {
        val clock = SimpleDateFormat("hh:mm a", locale).apply { this.timeZone = timeZone }
        val first = clock.format(Date(entry.firstRecordedAt))
        val timing = if (entry is TimelineEntry.Stay) {
            "$first\n– ${clock.format(Date(entry.lastRecordedAt))}"
        } else first
        // Keep user-chosen names verbatim; only remove the automatic source annotation.
        val place = if (customName) name else name.removeSuffix(" · offline map")
        return TimelineTextColumns(timing, place,
            if (entry is TimelineEntry.Stay) duration(entry.recordedDurationMs) else "—")
    }
}
