package com.phonebridge.receiver

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

sealed class TimelineEntry {
    abstract val samples: List<TimelinePoint>
    abstract val position: TimelinePoint
    val firstRecordedAt: Long get() = samples.first().timestamp
    val lastRecordedAt: Long get() = samples.last().timestamp

    data class Reading(val point: TimelinePoint) : TimelineEntry() {
        override val samples = listOf(point)
        override val position = point
    }

    data class Stay(override val samples: List<TimelinePoint>) : TimelineEntry() {
        // Use an actual reading with the best accuracy, rather than inventing a coordinate.
        override val position = samples.minBy { it.accuracy.toDoubleOrNull() ?: Double.POSITIVE_INFINITY }
        val recordedDurationMs: Long get() = lastRecordedAt - firstRecordedAt
    }
}

/** Groups display entries only. Room records and their original timestamps remain untouched. */
object TimelineStayDetector {
    const val MAX_SPREAD_METERS = 100.0
    const val MIN_DURATION_MS = 10L * 60L * 1000L
    const val MAX_GAP_MS = 30L * 60L * 1000L
    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /**
     * A stay needs at least two accurate readings, spanning ten minutes or more.
     * Every pair of readings must be within 100 m: a chain of nearby points must
     * not turn a journey into one stay. Leaving the area, a gap over 30 minutes,
     * or an unreliable reading ends the visit. Returning later creates a new stay.
     * Durations are observed spans, not proof of continuous presence between fixes.
     */
    fun group(points: List<TimelinePoint>): List<TimelineEntry> {
        val ordered = points.sortedBy { it.timestamp }
        val entries = mutableListOf<TimelineEntry>()
        val candidate = ArrayDeque<TimelinePoint>()
        fun qualifiesAsStay(): Boolean = candidate.size >= 2 &&
            candidate.last().timestamp - candidate.first().timestamp >= MIN_DURATION_MS
        fun finishVisit() {
            if (qualifiesAsStay()) entries.add(TimelineEntry.Stay(candidate.toList()))
            else candidate.forEach { entries.add(TimelineEntry.Reading(it)) }
            candidate.clear()
        }

        for (point in ordered) {
            if (!hasValidPosition(point)) {
                finishVisit()
                continue
            }
            if (!hasReliableAccuracy(point)) {
                finishVisit()
                entries.add(TimelineEntry.Reading(point))
                continue
            }
            if (candidate.isNotEmpty() && point.timestamp - candidate.last().timestamp > MAX_GAP_MS) {
                finishVisit()
            }
            if (candidate.any { distanceMeters(it, point) > MAX_SPREAD_METERS }) {
                if (qualifiesAsStay()) {
                    finishVisit()
                } else {
                    // Slide past passing points without losing the start of a later stay.
                    while (candidate.any { distanceMeters(it, point) > MAX_SPREAD_METERS }) {
                        entries.add(TimelineEntry.Reading(candidate.removeFirst()))
                    }
                }
            }
            candidate.addLast(point)
        }
        finishVisit()
        return entries
    }

    private fun hasValidPosition(point: TimelinePoint): Boolean =
        point.latitude.isFinite() && point.longitude.isFinite() &&
            point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0 && point.timestamp >= 0

    private fun hasReliableAccuracy(point: TimelinePoint): Boolean {
        val accuracy = point.accuracy.toDoubleOrNull() ?: return false
        return accuracy.isFinite() && accuracy in 0.0..MAX_SPREAD_METERS
    }

    private fun distanceMeters(a: TimelinePoint, b: TimelinePoint): Double {
        val latitudeDelta = Math.toRadians(b.latitude - a.latitude)
        val longitudeDelta = Math.toRadians(b.longitude - a.longitude)
        val h = (sin(latitudeDelta / 2) * sin(latitudeDelta / 2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) *
            sin(longitudeDelta / 2) * sin(longitudeDelta / 2)).coerceIn(0.0, 1.0)
        return 2 * EARTH_RADIUS_METERS * atan2(sqrt(h), sqrt(1 - h))
    }
}
