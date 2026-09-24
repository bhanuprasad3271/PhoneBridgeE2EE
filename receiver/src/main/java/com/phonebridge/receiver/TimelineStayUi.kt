package com.phonebridge.receiver

import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.map.android.view.MapView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Local display of inferred visits. Offline address lookup; no changes to stored readings. */
class TimelineStayUi(private val activity: AppCompatActivity, private val mapView: MapView, private val places: TimelinePlaceUi, private val textUi: TimelineTextUi) {
    fun showList(stays: List<TimelineEntry.Stay>) {
        if (stays.isEmpty()) return
        textUi.show(stays, "Stays", staysOnly = true) { index ->
            val stay = stays[index]
            mapView.model.mapViewPosition.mapPosition = MapPosition(
                LatLong(stay.position.latitude, stay.position.longitude), 17.toByte()
            )
            showDetails(stay, index + 1)
        }
    }

    fun showDetails(stay: TimelineEntry.Stay, number: Int) {
        val message = "First recorded: ${time(stay.firstRecordedAt)}\n" +
            "Last recorded: ${time(stay.lastRecordedAt)}\n" +
            "Recorded span: ${duration(stay.recordedDurationMs)}\n" +
            "${stay.samples.size} readings within 100 m\n\n" +
            "ADDRESS_PLACEHOLDER" + "\n\n" +
            "Estimated from recorded locations. Time between readings is not continuously tracked."
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Stayed here · $number")
            .setMessage(message.replace("ADDRESS_PLACEHOLDER", "Address: Looking up offline map…"))
            .setNegativeButton("Name this place", null)
            .setNeutralButton("View readings") { _, _ -> showReadings(stay, number) }
            .setPositiveButton("Close", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { places.showActions(stay.position) }
        places.bindName(dialog, stay.position) { name ->
            dialog.setMessage(message.replace("ADDRESS_PLACEHOLDER", name))
        }
    }

    private fun showReadings(stay: TimelineEntry.Stay, number: Int) {
        val loading = AlertDialog.Builder(activity)
            .setTitle("Stay $number · recorded locations")
            .setMessage("Looking up addresses in offline map…")
            .setNegativeButton("Close", null)
            .show()
        activity.lifecycleScope.launch {
            val labels = mutableListOf<String>()
            for (point in stay.samples) {
                if (!loading.isShowing) return@launch
                val address = places.displayName(point)
                val source = when (point.trigger) {
                    "periodic" -> "Automatic"
                    "manual_refresh", "receiver_open" -> "Manual refresh"
                    "special_text" -> "Special text"
                    else -> point.trigger.ifBlank { "Location update" }
                }
                labels.add("${time(point.timestamp, true)} · ±${point.accuracy} m\n$address\n$source")
            }
            if (!loading.isShowing) return@launch
            loading.dismiss()
            AlertDialog.Builder(activity)
                .setTitle("Stay $number · recorded locations")
                .setItems(labels.toTypedArray(), null)
                .setPositiveButton("Back") { _, _ -> showDetails(stay, number) }
                .show()
        }
    }

    private fun time(timestamp: Long, seconds: Boolean = false): String =
        SimpleDateFormat(if (seconds) "hh:mm:ss a" else "hh:mm a", Locale.getDefault()).format(Date(timestamp))

    private fun duration(milliseconds: Long): String {
        val minutes = milliseconds / 60_000
        return when {
            minutes < 60 -> "$minutes min"
            minutes % 60 == 0L -> "${minutes / 60} h"
            else -> "${minutes / 60} h ${minutes % 60} min"
        }
    }

}
