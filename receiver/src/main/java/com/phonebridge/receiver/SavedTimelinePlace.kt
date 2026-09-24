package com.phonebridge.receiver

import kotlin.math.*

data class SavedTimelinePlace(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Double = 100.0,
    val updatedAt: Long = 0L
) {
    fun isValid(): Boolean = id.isNotBlank() && name.isNotBlank() && name.length <= 80 &&
        latitude.isFinite() && latitude in -90.0..90.0 &&
        longitude.isFinite() && longitude in -180.0..180.0 &&
        radiusMeters.isFinite() && radiusMeters in 10.0..1000.0
}

/** Display names only: matching never changes coordinates or visit grouping. */
object SavedPlaceMatcher {
    fun closest(point: TimelinePoint, places: List<SavedTimelinePlace>): SavedTimelinePlace? {
        if (!point.latitude.isFinite() || point.latitude !in -90.0..90.0 ||
            !point.longitude.isFinite() || point.longitude !in -180.0..180.0) return null
        return places.asSequence().filter { it.isValid() }.map { place ->
            val lat = Math.toRadians(place.latitude - point.latitude)
            val lon = Math.toRadians(place.longitude - point.longitude)
            val a = sin(lat / 2).pow(2) + cos(Math.toRadians(point.latitude)) *
                cos(Math.toRadians(place.latitude)) * sin(lon / 2).pow(2)
            val distance = 6_371_000.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
            place to distance
        }.filter { (place, distance) -> distance <= place.radiusMeters }
            .sortedWith(compareBy<Pair<SavedTimelinePlace, Double>> { it.second }
                .thenByDescending { it.first.updatedAt }.thenBy { it.first.id })
            .firstOrNull()?.first
    }
}
