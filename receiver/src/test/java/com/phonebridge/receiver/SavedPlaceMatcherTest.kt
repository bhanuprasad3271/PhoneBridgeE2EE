package com.phonebridge.receiver

import org.junit.Assert.*
import org.junit.Test

class SavedPlaceMatcherTest {
    private fun point(metres: Double, time: Long = 0L) = TimelinePoint(metres / 111_195.0, 0.0, time, "5", "periodic")
    private fun place(id: String, metres: Double, radius: Double = 100.0, name: String = id, updated: Long = 0L) =
        SavedTimelinePlace(id, name, metres / 111_195.0, 0.0, radius, updated)

    @Test fun appliesToPastAndFutureReadingsWithinRadius() {
        val home = place("home", 0.0)
        assertEquals(home, SavedPlaceMatcher.closest(point(99.0, 1L), listOf(home)))
        assertEquals(home, SavedPlaceMatcher.closest(point(99.0, 999999L), listOf(home)))
        assertNull(SavedPlaceMatcher.closest(point(101.0), listOf(home)))
    }

    @Test fun overlappingPlacesChooseClosestEligibleAnchorRegardlessOfListOrder() {
        val home = place("home", 0.0)
        val cafe = place("cafe", 70.0)
        assertEquals(cafe, SavedPlaceMatcher.closest(point(60.0), listOf(home, cafe)))
        assertEquals(cafe, SavedPlaceMatcher.closest(point(60.0), listOf(cafe, home)))
        assertEquals(home, SavedPlaceMatcher.closest(point(10.0), listOf(cafe, home)))
    }

    @Test fun respectsEachPlacesOwnRadius() {
        val narrow = place("narrow", 0.0, 10.0)
        val wide = place("wide", 60.0, 100.0)
        assertEquals(wide, SavedPlaceMatcher.closest(point(20.0), listOf(narrow, wide)))
    }

    @Test fun exactOverlapUsesNewestNameDeterministically() {
        val older = place("a", 0.0, updated = 1L)
        val newer = place("b", 0.0, updated = 2L)
        assertEquals(newer, SavedPlaceMatcher.closest(point(0.0), listOf(newer, older)))
    }

    @Test fun renameAndRemovalDoNotChangeRecordedPoint() {
        val recorded = point(5.0, 120000L)
        val before = recorded.copy()
        val saved = place("home", 0.0)
        assertEquals("My home", SavedPlaceMatcher.closest(recorded, listOf(saved.copy(name = "My home")))?.name)
        assertNull(SavedPlaceMatcher.closest(recorded, emptyList()))
        assertEquals(before, recorded)
    }

    @Test fun handlesDatelineAndInvalidCoordinates() {
        val saved = SavedTimelinePlace("edge", "Edge", 0.0, 179.9999)
        assertEquals(saved, SavedPlaceMatcher.closest(TimelinePoint(0.0, -179.9999, 0L, "", ""), listOf(saved)))
        assertNull(SavedPlaceMatcher.closest(point(0.0).copy(latitude = Double.NaN), listOf(saved)))
        assertNull(SavedPlaceMatcher.closest(point(0.0), listOf(place("bad", 0.0).copy(radiusMeters = -1.0))))
    }

    @Test fun savedNamesRequireValidNameCoordinatesAndRadius() {
        val valid = place("home", 0.0)
        assertTrue(valid.isValid())
        assertFalse(valid.copy(name = "  ").isValid())
        assertFalse(valid.copy(name = "a".repeat(81)).isValid())
        assertFalse(valid.copy(latitude = 91.0).isValid())
        assertFalse(valid.copy(radiusMeters = Double.NaN).isValid())
    }

    @Test fun namedReturnVisitsRemainSeparateStays() {
        val readings = listOf(point(0.0, 0), point(0.0, 600000), point(500.0, 1200000),
            point(0.0, 1800000), point(0.0, 2400000))
        val entries = TimelineStayDetector.group(readings)
        val stays = entries.filterIsInstance<TimelineEntry.Stay>()
        assertEquals(2, stays.size)
        assertTrue(stays.all { SavedPlaceMatcher.closest(it.position, listOf(place("Home", 0.0)))?.name == "Home" })
        assertEquals(3, entries.size)
    }
}
