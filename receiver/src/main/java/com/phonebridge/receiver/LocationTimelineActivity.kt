package com.phonebridge.receiver

import android.app.DatePickerDialog
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class LocationTimelineActivity : AppCompatActivity() {
    private val db by lazy {
        Room.databaseBuilder(this, LocalDb::class.java, "phonebridge.db").build()
    }
    private lateinit var timelineView: LocationTimelineView
    private lateinit var dateButton: Button
    private lateinit var summary: TextView
    private val selected = Calendar.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_location_timeline)

        timelineView = findViewById(R.id.timelineView)
        dateButton = findViewById(R.id.btnTimelineDate)
        summary = findViewById(R.id.timelineSummary)

        findViewById<Button>(R.id.btnTimelineBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnTimelinePrevious).setOnClickListener {
            selected.add(Calendar.DAY_OF_MONTH, -1)
            loadDay()
        }
        findViewById<Button>(R.id.btnTimelineNext).setOnClickListener {
            selected.add(Calendar.DAY_OF_MONTH, 1)
            loadDay()
        }
        dateButton.setOnClickListener {
            DatePickerDialog(
                this,
                { _, y, m, d ->
                    selected.set(y, m, d)
                    loadDay()
                },
                selected.get(Calendar.YEAR),
                selected.get(Calendar.MONTH),
                selected.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
        loadDay()
    }

    private fun loadDay() {
        val start = (selected.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = (selected.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        dateButton.text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(start))

        lifecycleScope.launch {
            val cutoff = System.currentTimeMillis() - 30L * 24L * 60L * 60L * 1000L
            val rows = withContext(Dispatchers.IO) {
                db.dao().deleteLocationsOlderThan(cutoff)
                db.dao().locationsBetween(start, end)
            }
            val points = rows.mapNotNull { row ->
                try {
                    val meta = JSONObject(row.metadataJson)
                    val lat = meta.optString("latitude").toDoubleOrNull() ?: return@mapNotNull null
                    val lon = meta.optString("longitude").toDoubleOrNull() ?: return@mapNotNull null
                    TimelinePoint(
                        latitude = lat,
                        longitude = lon,
                        timestamp = row.timestamp,
                        accuracy = meta.optString("accuracyMeters"),
                        trigger = meta.optString("trigger")
                    )
                } catch (_: Exception) { null }
            }
            timelineView.setPoints(points)
            summary.text = when (points.size) {
                0 -> "No location data recorded for this day"
                1 -> "1 recorded location · tap the point for details"
                else -> "${points.size} recorded locations · chronological line from start to end"
            }
        }
    }
}
