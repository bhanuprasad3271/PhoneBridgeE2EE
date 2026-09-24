package com.phonebridge.receiver

import org.junit.Assert.*
import org.junit.Test

class TimelineTextRowsTest {
    private fun point(time: Long) = TimelinePoint(17.0, 78.0, time, "5", "periodic")
    private val stay = TimelineEntry.Stay(listOf(point(0L), point(3_600_000L)))

    @Test fun stayAtEndpointKeepsBothRoles() {
        assertEquals("Start · Stayed", TimelineTextRows.role(stay, 0, 3))
        assertEquals("End · Stayed", TimelineTextRows.role(stay, 2, 3))
        assertEquals("Start / End · Stayed", TimelineTextRows.role(stay, 0, 1))
        assertEquals("Stayed", TimelineTextRows.role(stay, 0, 2, staysOnly = true))
    }

    @Test fun ordinaryPointsHaveChronologicalRoles() {
        val reading = TimelineEntry.Reading(point(0))
        assertEquals("Start", TimelineTextRows.role(reading, 0, 3))
        assertEquals("Point", TimelineTextRows.role(reading, 1, 3))
        assertEquals("End", TimelineTextRows.role(reading, 2, 3))
        assertEquals("Start / End", TimelineTextRows.role(reading, 0, 1))
    }

    @Test fun stayRowUsesNameAndRecordedDurationWithoutCoordinates() {
        val text = TimelineTextRows.text(stay, "Stayed", "College")
        assertTrue(text.startsWith("Stayed · College\n"))
        assertTrue(text.endsWith(" · 1 h"))
        assertFalse(text.contains("17.0"))
        assertEquals("1 h 15 min", TimelineTextRows.duration(4_500_000))
    }
}
