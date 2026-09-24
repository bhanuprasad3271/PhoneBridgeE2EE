package com.phonebridge.receiver

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Receiver-only preferences, independent of Room's location retention and schema. */
class SavedPlaceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("timeline_place_names", Context.MODE_PRIVATE)
    private val writeLock = Mutex()
    private var places = read()
    private val listeners = mutableSetOf<() -> Unit>()

    fun all(): List<SavedTimelinePlace> = places.toList()
    fun match(point: TimelinePoint): SavedTimelinePlace? = SavedPlaceMatcher.closest(point, places)
    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    suspend fun save(place: SavedTimelinePlace): Boolean = writeLock.withLock {
        require(place.isValid())
        persist(places.filterNot { it.id == place.id } + place.copy(name = place.name.trim()))
    }

    suspend fun remove(id: String): Boolean = writeLock.withLock {
        persist(places.filterNot { it.id == id })
    }

    private suspend fun persist(updated: List<SavedTimelinePlace>): Boolean {
        val encoded = JSONArray().apply {
            updated.forEach { place -> put(JSONObject().apply {
                put("id", place.id); put("name", place.name)
                put("latitude", place.latitude); put("longitude", place.longitude)
                put("radiusMeters", place.radiusMeters); put("updatedAt", place.updatedAt)
            }) }
        }.toString()
        val ok = withContext(Dispatchers.IO) { prefs.edit().putString("places", encoded).commit() }
        if (ok) {
            places = updated
            listeners.toList().forEach { it() }
        }
        return ok
    }

    private fun read(): List<SavedTimelinePlace> {
        val array = try { JSONArray(prefs.getString("places", "[]")) } catch (_: Exception) { return emptyList() }
        return (0 until array.length()).mapNotNull { index ->
            try {
                val row = array.getJSONObject(index)
                SavedTimelinePlace(row.getString("id"), row.getString("name"), row.getDouble("latitude"),
                    row.getDouble("longitude"), row.optDouble("radiusMeters", 100.0), row.optLong("updatedAt", 0L))
                    .takeIf { it.isValid() }
            } catch (_: Exception) { null }
        }.distinctBy { it.id }
    }
}
