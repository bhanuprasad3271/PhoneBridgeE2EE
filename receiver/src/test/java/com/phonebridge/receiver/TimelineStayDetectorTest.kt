package com.phonebridge.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineStayDetectorTest {
    private fun point(minute: Long, metersEast: Double = 0.0, accuracy: String = "10.0") =
        TimelinePoint(0.0, Math.toDegrees(metersEast / 6_371_000.0), minute * 60_000, accuracy, "periodic")

    @Test fun stationaryReadingsBecomeOneStayAndKeepOriginalSamples() {
        val points = listOf(point(0, 0.0, "25"), point(15, 30.0, "5"), point(30, 10.0, "20"))
        val stay = TimelineStayDetector.group(points).single() as TimelineEntry.Stay
        assertEquals(points, stay.samples)
        assertEquals(points[1], stay.position)
        assertEquals(30 * 60_000L, stay.recordedDurationMs)
    }

    @Test fun shortVisitsAndSingleReadingsStayAsIndividualPoints() {
        val points = listOf(point(0), point(5, 20.0))
        assertTrue(TimelineStayDetector.group(points).all { it is TimelineEntry.Reading })
        assertTrue(TimelineStayDetector.group(listOf(point(0))).single() is TimelineEntry.Reading)
        assertTrue(TimelineStayDetector.group(emptyList()).isEmpty())
    }

    @Test fun repeatedCachedFixesDoNotInventDuration() {
        val points = listOf(point(15), point(15), point(15))
        assertEquals(3, TimelineStayDetector.group(points).size)
        assertTrue(TimelineStayDetector.group(points).all { it is TimelineEntry.Reading })
    }

    @Test fun leavingAndReturningCreatesTwoSeparateVisits() {
        val points = listOf(point(0), point(15, 10.0), point(30, 1_000.0), point(45), point(60, 20.0))
        val entries = TimelineStayDetector.group(points)
        assertEquals(3, entries.size)
        assertEquals(0L, (entries[0] as TimelineEntry.Stay).firstRecordedAt)
        assertTrue(entries[1] is TimelineEntry.Reading)
        assertEquals(45 * 60_000L, (entries[2] as TimelineEntry.Stay).firstRecordedAt)
        assertEquals(points, entries.flatMap { it.samples })
    }

    @Test fun longRecordingGapsSplitStaysEvenAtTheSameCoordinates() {
        val entries = TimelineStayDetector.group(listOf(point(0), point(15), point(75), point(90)))
        assertEquals(2, entries.size)
        assertTrue(entries.all { it is TimelineEntry.Stay })
        assertTrue(entries.all { (it as TimelineEntry.Stay).recordedDurationMs == 15 * 60_000L })
        assertTrue(TimelineStayDetector.group(listOf(point(0), point(180))).all { it is TimelineEntry.Reading })
    }

    @Test fun aChainOfNearbyPointsCannotBecomeOneLongDistanceStay() {
        val entries = TimelineStayDetector.group(listOf(point(0), point(15, 80.0), point(30, 160.0)))
        assertEquals(2, entries.size)
        assertEquals(2, (entries.first() as TimelineEntry.Stay).samples.size)
        assertTrue(entries.last() is TimelineEntry.Reading)
    }

    @Test fun distanceThresholdSeparatesNearbyPlaces() {
        assertTrue(TimelineStayDetector.group(listOf(point(0), point(15, 99.0))).single() is TimelineEntry.Stay)
        assertTrue(TimelineStayDetector.group(listOf(point(0), point(15, 101.0))).all { it is TimelineEntry.Reading })
    }

    @Test fun durationAndGapLimitsAreInclusive() {
        assertTrue(TimelineStayDetector.group(listOf(point(0), point(10))).single() is TimelineEntry.Stay)
        assertTrue(TimelineStayDetector.group(listOf(point(0), point(30))).single() is TimelineEntry.Stay)
        assertTrue(TimelineStayDetector.group(listOf(point(0), point(31))).all { it is TimelineEntry.Reading })
    }

    @Test fun inaccurateOrUnknownReadingsArePreservedAndBreakAStay() {
        for (accuracy in listOf("101", "", "NaN", "Infinity", "-1")) {
            val points = listOf(point(0), point(15, 0.0, accuracy), point(30), point(45))
            val entries = TimelineStayDetector.group(points)
            assertEquals(3, entries.size)
            assertTrue(entries[0] is TimelineEntry.Reading)
            assertTrue(entries[1] is TimelineEntry.Reading)
            assertTrue(entries[2] is TimelineEntry.Stay)
            assertEquals(points, entries.flatMap { it.samples })
        }
    }

    @Test fun sortingDoesNotChangeTheOriginalInputOrLoseReadings() {
        val points = listOf(point(30), point(0), point(15))
        val entries = TimelineStayDetector.group(points)
        assertEquals(points.sortedBy { it.timestamp }, entries.flatMap { it.samples })
        assertEquals(30 * 60_000L, points.first().timestamp)
    }

    @Test fun invalidCoordinatesDoNotCrashOrJoinSurroundingStays() {
        val invalid = listOf(
            point(15).copy(latitude = Double.NaN), point(15).copy(latitude = 91.0),
            point(15).copy(longitude = Double.POSITIVE_INFINITY), point(15).copy(longitude = 181.0)
        )
        for (bad in invalid) {
            val entries = TimelineStayDetector.group(listOf(point(0), bad, point(30)))
            assertEquals(2, entries.size)
            assertTrue(entries.all { it is TimelineEntry.Reading })
        }
    }

    @Test fun anEarlyPassingPointDoesNotHideALaterStay() {
        val points = listOf(point(0), point(2, 90.0), point(10, 160.0), point(15, 165.0))
        val entries = TimelineStayDetector.group(points)
        assertEquals(2, entries.size)
        assertTrue(entries.first() is TimelineEntry.Reading)
        assertEquals(points.drop(1), (entries.last() as TimelineEntry.Stay).samples)
    }

    @Test fun nearbyReadingsAcrossTheDateLineCanFormAStay() {
        val points = listOf(point(0).copy(longitude = 179.9998), point(15).copy(longitude = -179.9998))
        assertTrue(TimelineStayDetector.group(points).single() is TimelineEntry.Stay)
    }
}
