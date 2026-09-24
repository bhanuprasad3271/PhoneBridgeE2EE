package com.phonebridge.receiver

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.Tile
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.reader.MapFile
import kotlin.math.cos
import kotlin.math.hypot

/** Reads the user's .map directly. Never invokes Android Geocoder or a network service. */
class OfflineTimelineAddress(private val file: File) {
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 256
    }

    suspend fun address(point: TimelinePoint): String {
        val key = "${file.lastModified()}:${file.length()}:${point.latitude}:${point.longitude}"
        cache[key]?.let { return it }
        val result = withContext(Dispatchers.IO) { lookup(point) }
        cache[key] = result
        return result
    }

    private fun lookup(point: TimelinePoint): String {
        if (!file.isFile) return "Address unavailable · import an offline map"
        return try {
            val map = MapFile(file)
            try {
                val zoom: Byte = 16
                val latitude = point.latitude.coerceIn(-85.05112878, 85.05112878)
                val tile = Tile(MercatorProjection.longitudeToTileX(point.longitude, zoom),
                    MercatorProjection.latitudeToTileY(latitude, zoom), zoom, 256)
                var nearest: Pair<Double, String>? = null
                fun consider(tags: Map<String, String>, distance: Double) {
                    if (distance > 150.0 || !distance.isFinite()) return
                    val label = OfflinePlaceLabel.fromTags(tags) ?: return
                    if (nearest == null || distance < nearest!!.first) nearest = distance to label
                }
                for (nearTile in tile.neighbours + tile) {
                    val data = map.readMapData(nearTile) ?: continue
                    for (poi in data.pois) {
                        consider(poi.tags.associate { it.key to it.value }, distance(point, arrayOf(poi.position)))
                    }
                    for (way in data.ways) {
                        val tags = way.tags.associate { it.key to it.value }
                        // A named waterway/boundary is not a street address or nearby venue.
                        if (!tags.containsKey("highway") && !tags.containsKey("building") &&
                            !tags.containsKey("amenity") && !tags.containsKey("shop") &&
                            tags.keys.none { it.startsWith("addr:") }) continue
                        val distance = way.latLongs.minOfOrNull { distance(point, it) } ?: continue
                        consider(tags, distance)
                    }
                }
                nearest?.let { "Near ${it.second} · offline map" } ?: "Address unavailable in offline map"
            } finally { map.close() }
        } catch (_: Exception) { "Address unavailable in offline map" }
    }

    /** Local metres, measuring road segments rather than only their vertices. */
    private fun distance(point: TimelinePoint, nodes: Array<LatLong>): Double {
        if (nodes.isEmpty()) return Double.POSITIVE_INFINITY
        fun xy(node: LatLong): Pair<Double, Double> {
            val longitude = ((node.longitude - point.longitude + 540.0) % 360.0) - 180.0
            return longitude * 111_195.0 * cos(Math.toRadians(point.latitude)) to
                (node.latitude - point.latitude) * 111_195.0
        }
        var previous = xy(nodes.first())
        var best = hypot(previous.first, previous.second)
        for (node in nodes.drop(1)) {
            val next = xy(node)
            val dx = next.first - previous.first
            val dy = next.second - previous.second
            val squared = dx * dx + dy * dy
            val fraction = if (squared == 0.0) 0.0 else
                (-(previous.first * dx + previous.second * dy) / squared).coerceIn(0.0, 1.0)
            best = minOf(best, hypot(previous.first + fraction * dx, previous.second + fraction * dy))
            previous = next
        }
        return best
    }
}

internal object OfflinePlaceLabel {
    fun fromTags(tags: Map<String, String>): String? {
        fun value(key: String) = tags[key]?.trim()?.takeIf { it.isNotEmpty() }
        value("addr:full")?.let { return it }
        val street = value("addr:street") ?: value("addr:place")
        val streetLine = listOfNotNull(value("addr:housenumber"), street).joinToString(" ")
        val name = value("name") ?: value("name:en")
        // A house number alone cannot locate an address.
        if (street == null && name == null) return null
        return listOfNotNull(name, streetLine.takeIf { street != null }, value("addr:suburb"),
            value("addr:city"), value("addr:postcode")).distinct().joinToString(", ").ifBlank { null }
    }
}
