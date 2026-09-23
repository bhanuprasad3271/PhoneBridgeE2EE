package com.phonebridge.receiver

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.core.model.Point
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.layer.overlay.Marker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/** Local display of inferred visits. No geocoding or changes to stored readings. */
class TimelineStayUi(private val activity: AppCompatActivity, private val mapView: MapView) {
    fun markerFor(stay: TimelineEntry.Stay, number: Int): Marker {
        val bitmap = markerBitmap(number, duration(stay.recordedDurationMs))
        return object : Marker(LatLong(stay.position.latitude, stay.position.longitude), bitmap, 0, -bitmap.height / 2) {
            override fun onTap(tapLatLong: LatLong, layerXY: Point, tapXY: Point): Boolean {
                if (!contains(layerXY, tapXY, mapView)) return false
                showDetails(stay, number)
                return true
            }
        }
    }

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

    private fun showDetails(stay: TimelineEntry.Stay, number: Int) {
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

    private fun markerBitmap(number: Int, duration: String): org.mapsforge.core.graphics.Bitmap {
        val metrics = activity.resources.displayMetrics
        val density = metrics.density
        val title = "Stayed here"
        val subtitle = "$number · $duration"
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(66, 32, 6)
            textSize = 13f * metrics.scaledDensity
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(66, 32, 6)
            textSize = 12f * metrics.scaledDensity
        }
        val titleHeight = titlePaint.fontMetrics.descent - titlePaint.fontMetrics.ascent
        val subtitleHeight = subtitlePaint.fontMetrics.descent - subtitlePaint.fontMetrics.ascent
        val width = ceil(maxOf(titlePaint.measureText(title), subtitlePaint.measureText(subtitle)) + 24 * density).toInt()
        val bodyHeight = ceil(titleHeight + subtitleHeight + 16 * density).toInt()
        val height = bodyHeight + ceil(7 * density).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Keep the density explicit so BitmapDrawable does not rescale our measured text.
        bitmap.density = metrics.densityDpi
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(251, 191, 36) }
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(120, 53, 15)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * density
        }
        val edge = 1.5f * density
        val tail = Path().apply {
            moveTo(width / 2f - 7 * density, bodyHeight - 2 * density)
            lineTo(width / 2f, height - edge)
            lineTo(width / 2f + 7 * density, bodyHeight - 2 * density)
            close()
        }
        canvas.drawPath(tail, fill)
        canvas.drawRoundRect(edge, edge, width - edge, bodyHeight - edge, 9 * density, 9 * density, fill)
        canvas.drawRoundRect(edge, edge, width - edge, bodyHeight - edge, 9 * density, 9 * density, outline)
        canvas.drawText(title, (width - titlePaint.measureText(title)) / 2f,
            6 * density - titlePaint.fontMetrics.ascent, titlePaint)
        canvas.drawText(subtitle, (width - subtitlePaint.measureText(subtitle)) / 2f,
            9 * density + titleHeight - subtitlePaint.fontMetrics.ascent, subtitlePaint)
        // Mapsforge can retain this same bitmap on older Android versions. Its
        // Marker.onDestroy owns cleanup; recycling it here would erase the marker.
        return AndroidGraphicFactory.convertToBitmap(BitmapDrawable(activity.resources, bitmap))
    }
}
