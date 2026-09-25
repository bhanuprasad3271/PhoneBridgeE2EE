package com.phonebridge.receiver

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

class TimelineTextRowsTest {
    private fun point(time: Long) = TimelinePoint(17.0, 78.0, time, "5", "periodic")
    private fun columns(entry: TimelineEntry, name: String, custom: Boolean = false) =
        TimelineTextRows.columns(entry, name, custom, Locale.US, TimeZone.getTimeZone("UTC"))

    @Test fun ordinaryReadingHasTimeNameAndNoInventedDuration() {
        val row = columns(TimelineEntry.Reading(point(0)), "Home", true)
        assertEquals("12:00 AM", row.time)
        assertEquals("Home", row.place)
        assertEquals("—", row.duration)
    }

    @Test fun stayCrossingNoonKeepsBothPeriodsAndSeparateDuration() {
        val stay = TimelineEntry.Stay(listOf(point(11 * 3_600_000L), point(13 * 3_600_000L)))
        val row = columns(stay, "Work", true)
        assertEquals("11:00 AM\n– 01:00 PM", row.time)
        assertEquals("Work", row.place)
        assertEquals("2 h", row.duration)
    }

    @Test fun stripsOnlyAutomaticOfflineSuffixAndKeepsNearQualifier() {
        val entry = TimelineEntry.Reading(point(0))
        assertEquals("Near Road Number 30", columns(entry, "Near Road Number 30 · offline map").place)
        assertEquals("Address unavailable in offline map", columns(entry, "Address unavailable in offline map").place)
        assertEquals("Home · offline map", columns(entry, "Home · offline map", true).place)
    }

    @Test fun longNamesAndNamesContainingOldRoleWordsArePreserved() {
        val name = "Point · Start House, Block 1, A long street name"
        assertEquals(name, columns(TimelineEntry.Reading(point(0)), name, true).place)
        assertEquals("1 h 15 min", TimelineTextRows.duration(4_500_000))
    }
}
