package com.phonebridge.receiver

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.map.android.view.MapView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local display of inferred visits. No geocoding or changes to stored readings. */
class TimelineStayUi(private val activity: AppCompatActivity, private val mapView: MapView) {
    fun showList(stays: List<TimelineEntry.Stay>) {
        if (stays.isEmpty()) return
        val labels = stays.mapIndexed { index, stay ->
            "${index + 1}. ${time(stay.firstRecordedAt)} – ${time(stay.lastRecordedAt)}\n" +
                "${duration(stay.recordedDurationMs)} · ${stay.samples.size} readings"
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Stayed here")
            .setItems(labels) { _, index ->
                val stay = stays[index]
                mapView.model.mapViewPosition.mapPosition = MapPosition(
                    LatLong(stay.position.latitude, stay.position.longitude), 17.toByte()
                )
                showDetails(stay, index + 1)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    fun showDetails(stay: TimelineEntry.Stay, number: Int) {
        val message = "First recorded: ${time(stay.firstRecordedAt)}\n" +
            "Last recorded: ${time(stay.lastRecordedAt)}\n" +
            "Recorded span: ${duration(stay.recordedDurationMs)}\n" +
            "${stay.samples.size} readings within 100 m\n\n" +
            coordinates(stay.position) + "\n\n" +
            "Estimated from recorded locations. Time between readings is not continuously tracked."
        AlertDialog.Builder(activity)
            .setTitle("Stayed here · $number")
            .setMessage(message)
            .setNeutralButton("View readings") { _, _ -> showReadings(stay, number) }
            .setPositiveButton("Close", null)
            .show()
    }

    private fun showReadings(stay: TimelineEntry.Stay, number: Int) {
        val labels = stay.samples.map { point ->
            val source = when (point.trigger) {
                "periodic" -> "Automatic"
                "manual_refresh", "receiver_open" -> "Manual refresh"
                "special_text" -> "Special text"
                else -> point.trigger.ifBlank { "Location update" }
            }
            "${time(point.timestamp, true)} · ±${point.accuracy} m\n${coordinates(point)}\n$source"
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Stay $number · recorded locations")
            .setItems(labels, null)
            .setPositiveButton("Back") { _, _ -> showDetails(stay, number) }
            .show()
    }

    private fun time(timestamp: Long, seconds: Boolean = false): String =
        SimpleDateFormat(if (seconds) "hh:mm:ss a" else "hh:mm a", Locale.getDefault()).format(Date(timestamp))

    private fun coordinates(point: TimelinePoint): String =
        String.format(Locale.US, "%.6f, %.6f", point.latitude, point.longitude)

    private fun duration(milliseconds: Long): String {
        val minutes = milliseconds / 60_000
        return when {
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0L -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

}
