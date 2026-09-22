package com.phonebridge.receiver

import android.app.DatePickerDialog
import android.os.Bundle
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.mapsforge.core.graphics.Paint
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Dimension
import org.mapsforge.core.model.Point
import org.mapsforge.core.util.LatLongUtils
import org.mapsforge.core.model.MapPosition
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.overlay.FixedPixelCircle
import org.mapsforge.map.layer.Layer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class LocationTimelineActivity : AppCompatActivity() {
    private val db by lazy { Room.databaseBuilder(this, LocalDb::class.java, "phonebridge.db").build() }
    private lateinit var mapView: MapView
    private lateinit var mapStatus: TextView
    private lateinit var dateButton: Button
    private lateinit var summary: TextView
    private var mapDataStore: MapDataStore? = null
    private var tileCache: TileCache? = null
    private var rendererLayer: TileRendererLayer? = null
    private var routeLayer: Polyline? = null
    private val pointLayers = mutableListOf<Layer>()
    private val selected = Calendar.getInstance()
    private val importMap = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importOfflineMap(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidGraphicFactory.createInstance(application)
        setContentView(R.layout.activity_location_timeline)
        mapView = findViewById(R.id.timelineMapView)
        mapStatus = findViewById(R.id.timelineMapStatus)
        dateButton = findViewById(R.id.btnTimelineDate)
        summary = findViewById(R.id.timelineSummary)
        mapView.isClickable = true
        mapView.setBuiltInZoomControls(true)
        mapView.mapScaleBar.isVisible = true
        findViewById<Button>(R.id.btnImportOfflineMap).setOnClickListener {
            importMap.launch(arrayOf("application/octet-stream", "application/*"))
        }
        openOfflineMap()

        findViewById<Button>(R.id.btnTimelineBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnTimelinePrevious).setOnClickListener { selected.add(Calendar.DAY_OF_MONTH, -1); loadDay() }
        findViewById<Button>(R.id.btnTimelineNext).setOnClickListener { selected.add(Calendar.DAY_OF_MONTH, 1); loadDay() }
        dateButton.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d -> selected.set(y, m, d); loadDay() },
                selected.get(Calendar.YEAR), selected.get(Calendar.MONTH), selected.get(Calendar.DAY_OF_MONTH)).show()
        }
        loadDay()
    }

    /**
     * Security boundary: this renderer opens only a local Mapsforge .map file.
     * It does not fetch tiles, geocode, route, map-match, or transmit timeline coordinates.
     * Put a downloaded file at filesDir/offline-maps/phonebridge.map.
     */
    private fun importOfflineMap(uri: Uri) {
        lifecycleScope.launch {
            mapStatus.visibility = View.VISIBLE
            mapStatus.text = "Importing offline map…"
            val ok = withContext(Dispatchers.IO) {
                try {
                    val dir = File(filesDir, "offline-maps").apply { mkdirs() }
                    val temp = File(dir, "phonebridge.map.tmp")
                    contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                        ?: return@withContext false
                    // Validate with the same local parser before replacing the active map.
                    val validationMap = MapFile(temp)
                    validationMap.close()
                    val target = File(dir, "phonebridge.map")
                    if (target.exists()) target.delete()
                    temp.renameTo(target)
                } catch (_: Exception) { false }
            }
            if (ok) {
                mapStatus.text = "Offline map imported · reopen Timeline to load it"
            } else {
                mapStatus.text = "Invalid or unsupported .map file"
            }
        }
    }

    private fun openOfflineMap() {
        val file = File(File(filesDir, "offline-maps"), "phonebridge.map")
        if (!file.isFile) {
            mapStatus.visibility = View.VISIBLE
            mapStatus.text = "Offline map not installed\nExpected: PhoneBridge offline map"
            return
        }
        try {
            mapDataStore = MapFile(file)
            tileCache = AndroidUtil.createTileCache(this, "timeline-map", mapView.model.displayModel.tileSize, 1f, mapView.model.frameBufferModel.overdrawFactor)
            rendererLayer = TileRendererLayer(tileCache, mapDataStore, mapView.model.mapViewPosition, AndroidGraphicFactory.INSTANCE).also {
                it.setXmlRenderTheme(MapsforgeThemes.MOTORIDER)
                mapView.layerManager.layers.add(it)
            }
            mapStatus.visibility = View.GONE
        } catch (_: Exception) {
            mapStatus.visibility = View.VISIBLE
            mapStatus.text = "Offline map could not be opened"
        }
    }

    private fun loadDay() {
        val start = (selected.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = (selected.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
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
                    TimelinePoint(lat, lon, row.timestamp, meta.optString("accuracyMeters"), meta.optString("trigger"))
                } catch (_: Exception) { null }
            }.sortedBy { it.timestamp }
            drawLocalTimeline(points)
            summary.text = when (points.size) {
                0 -> "No location data recorded for this day"
                1 -> "1 recorded location"
                else -> "${points.size} recorded locations · chronological line from start to end"
            }
        }
    }

    private fun drawLocalTimeline(points: List<TimelinePoint>) {
        routeLayer?.let { mapView.layerManager.layers.remove(it) }
        routeLayer = null
        pointLayers.forEach { mapView.layerManager.layers.remove(it) }
        pointLayers.clear()
        if (points.isEmpty()) return

        val latLongs = points.map { LatLong(it.latitude, it.longitude) }

        if (latLongs.size > 1) {
            val paint: Paint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                color = AndroidGraphicFactory.INSTANCE.createColor(255, 33, 150, 243)
                setStrokeWidth(7f)
                setStyle(Style.STROKE)
            }
            routeLayer = Polyline(paint, AndroidGraphicFactory.INSTANCE).also {
                it.latLongs.addAll(latLongs)
                mapView.layerManager.layers.add(it)
            }
        }

        points.forEachIndexed { index, point ->
            val isStart = points.size > 1 && index == 0
            val isEnd = points.size > 1 && index == points.lastIndex
            val radius = if (points.size == 1 || isStart || isEnd) 15 else 9
            val markerColor = when {
                points.size == 1 -> AndroidGraphicFactory.INSTANCE.createColor(255, 33, 150, 243)
                isStart -> AndroidGraphicFactory.INSTANCE.createColor(255, 46, 125, 50)
                isEnd -> AndroidGraphicFactory.INSTANCE.createColor(255, 211, 47, 47)
                else -> AndroidGraphicFactory.INSTANCE.createColor(255, 33, 150, 243)
            }
            val fill = AndroidGraphicFactory.INSTANCE.createPaint().apply {
                color = markerColor
                setStyle(Style.FILL)
            }
            val center = LatLong(point.latitude, point.longitude)
            val layer = object : FixedPixelCircle(center, radius, fill, null) {
                override fun onTap(tapLatLong: LatLong, layerXY: Point, tapXY: Point): Boolean {
                    if (!contains(layerXY, tapXY, mapView)) return false
                    val role = when {
                        points.size == 1 -> "LOCATION"
                        isStart -> "START"
                        isEnd -> "END"
                        else -> "POINT ${index + 1}"
                    }
                    val time = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(point.timestamp))
                    val source = when (point.trigger) {
                        "periodic" -> "Automatic"
                        "manual_refresh", "receiver_open" -> "Manual refresh"
                        "special_text" -> "Special text"
                        else -> point.trigger.ifBlank { "Location update" }
                    }
                    val accuracy = point.accuracy.takeIf { it.isNotBlank() }?.let { " · ±$it m" } ?: ""
                    Toast.makeText(this@LocationTimelineActivity, "$role · $time\n$source$accuracy", Toast.LENGTH_LONG).show()
                    return true
                }
            }
            pointLayers.add(layer)
            mapView.layerManager.layers.add(layer)
        }

        if (latLongs.size == 1) {
            mapView.model.mapViewPosition.mapPosition = MapPosition(latLongs.first(), 16.toByte())
        } else {
            mapView.post {
                val width = (mapView.width - 80).coerceAtLeast(1)
                val height = (mapView.height - 80).coerceAtLeast(1)
                val bounds = BoundingBox(latLongs)
                val zoom = LatLongUtils.zoomForBounds(
                    Dimension(width, height),
                    bounds,
                    mapView.model.displayModel.tileSize
                ).coerceAtMost(17.toByte())
                mapView.model.mapViewPosition.mapPosition = MapPosition(bounds.centerPoint, zoom)
            }
        }
    }

    override fun onDestroy() {
        routeLayer?.let { mapView.layerManager.layers.remove(it) }
        pointLayers.forEach { mapView.layerManager.layers.remove(it) }
        pointLayers.clear()
        rendererLayer?.let { mapView.layerManager.layers.remove(it) }
        rendererLayer?.onDestroy()
        mapDataStore?.close()
        tileCache?.destroy()
        mapView.destroyAll()
        AndroidGraphicFactory.clearResourceMemoryCache()
        super.onDestroy()
    }
}
