package com.phonebridge.receiver

import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** Display-only address binding for the main Location screen and recycled history rows. */
class LocationDisplayNames(private val activity: AppCompatActivity) {
    private val addresses = OfflineTimelineAddress(File(activity.filesDir, "offline-maps/phonebridge.map"))
    private var places = SavedPlaceStore(activity)
    private var generation = 0

    fun reloadSavedNames() {
        places = SavedPlaceStore(activity)
        generation++
    }

    fun bind(record: RecordEntity, label: TextView): Job? {
        val point = try {
            val meta = JSONObject(record.metadataJson)
            val latitude = meta.optString("latitude").toDoubleOrNull()
            val longitude = meta.optString("longitude").toDoubleOrNull()
            if (latitude == null || longitude == null || !latitude.isFinite() || !longitude.isFinite() ||
                latitude !in -90.0..90.0 || longitude !in -180.0..180.0) null
            else TimelinePoint(latitude, longitude, record.timestamp,
                meta.optString("accuracyMeters"), meta.optString("trigger"))
        } catch (_: Exception) { null }
        if (point == null) {
            label.text = "Address unavailable"
            return null
        }
        places.match(point)?.let {
            label.text = it.name
            return null
        }
        label.text = "Looking up offline address…"
        val requestedGeneration = generation
        return activity.lifecycleScope.launch {
            val address = addresses.address(point)
            // Ignore lookups started before returning from a place-name edit.
            if (requestedGeneration == generation) label.text = places.match(point)?.name ?: address
        }
    }
}
