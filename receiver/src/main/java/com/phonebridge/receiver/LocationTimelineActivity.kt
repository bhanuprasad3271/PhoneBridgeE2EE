package com.phonebridge.receiver

import android.app.DatePickerDialog
import android.os.Bundle
import android.content.res.Configuration
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
import kotlinx.coroutines.Job
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
import org.mapsforge.map.rendertheme.ExternalRenderTheme
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
    private lateinit var themeButton: Button
    private lateinit var staysButton: Button
    private lateinit var stayUi: TimelineStayUi
    private var currentStays: List<TimelineEntry.Stay> = emptyList()
    private var dayLoadJob: Job? = null
    private var manualLight: Boolean? = null
    private var mapDataStore: MapDataStore? = null
    private var tileCache: TileCache? = null
    private var rendererLayer: TileRendererLayer? = null
    private val routeLayers = mutableListOf<Polyline>()
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
        themeButton = findViewById(R.id.btnTimelineTheme)
        staysButton = findViewById(R.id.btnTimelineStays)
        stayUi = TimelineStayUi(this, mapView)
        staysButton.setOnClickListener { stayUi.showList(currentStays) }
        themeButton.setOnClickListener { manualLight = !isLightMap(); applyMapTheme() }
        updateThemeButton()
        mapView.isClickable = true
        mapView.setBuiltInZoomControls(false)
        mapView.mapScaleBar.isVisible = false
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
                val assetName = if (isLightMap()) "phonebridge-light.xml" else "phonebridge-dark.xml"
                val themeFile = File(filesDir, assetName).also { localTheme ->
                    assets.open(assetName).use { input ->
                        localTheme.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                it.setXmlRenderTheme(ExternalRenderTheme(themeFile))
                mapView.layerManager.layers.add(it)
            }
            mapStatus.visibility = View.GONE
        } catch (_: Exception) {
            mapStatus.visibility = View.VISIBLE
            mapStatus.text = "Offline map could not be opened"
        }
    }

    private fun systemLight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES

    private fun isLightMap(): Boolean = manualLight ?: systemLight()

    private fun updateThemeButton() {
        themeButton.text = if (isLightMap()) "☾" else "☀"
        themeButton.contentDescription = if (isLightMap()) "Switch map to dark mode" else "Switch map to light mode"
    }

    private fun applyMapTheme() {
        val layer = rendererLayer ?: return
        val assetName = if (isLightMap()) "phonebridge-light.xml" else "phonebridge-dark.xml"
        val themeFile = File(filesDir, assetName)
        assets.open(assetName).use { input -> themeFile.outputStream().use { output -> input.copyTo(output) } }
        layer.setXmlRenderTheme(ExternalRenderTheme(themeFile))
        layer.requestRedraw()
        updateThemeButton()
    }

    private fun loadDay() {
        val start = (selected.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val end = (selected.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        dateButton.text = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(start))

        dayLoadJob?.cancel()
        dayLoadJob = lifecycleScope.launch {
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
            val entries = withContext(Dispatchers.Default) { TimelineStayDetector.group(points) }
            currentStays = entries.filterIsInstance<TimelineEntry.Stay>()
            staysButton.text = "Stays (${currentStays.size})"
            staysButton.contentDescription = "View ${currentStays.size} recorded stays"
            staysButton.visibility = if (currentStays.isEmpty()) View.GONE else View.VISIBLE
            drawLocalTimeline(entries)
            summary.text = when (points.size) {
                0 -> "No location data recorded for this day"
                1 -> "1 recorded location"
                else -> "${points.size} recorded locations"
            } + when (currentStays.size) {
                0 -> ""
                1 -> " · 1 stay"
                else -> " · ${currentStays.size} stays"
            }
        }
    }

    private fun drawLocalTimeline(entries: List<TimelineEntry>) {
        routeLayers.forEach { mapView.layerManager.layers.remove(it) }
        routeLayers.clear()
        pointLayers.forEach { mapView.layerManager.layers.remove(it); it.onDestroy() }
        pointLayers.clear()
        if (entries.isEmpty()) return

        val latLongs = entries.map { LatLong(it.position.latitude, it.position.longitude) }

        // Connect display entries in time order, without drawing the jitter inside a
        // stay or implying a route across long periods with no location readings.
        val route = mutableListOf<LatLong>()
        entries.forEachIndexed { index, entry ->
            if (index > 0 && entry.firstRecordedAt - entries[index - 1].lastRecordedAt > TimelineStayDetector.MAX_GAP_MS) {
                addRouteLine(route)
                route.clear()
            }
            route.add(latLongs[index])
        }
        addRouteLine(route)

        var stayNumber = 0
        entries.forEachIndexed { index, entry ->
            if (entry is TimelineEntry.Stay) {
                val layer = stayUi.markerFor(entry, ++stayNumber)
                pointLayers.add(layer)
                mapView.layerManager.layers.add(layer)
                return@forEachIndexed
            }
            val point = entry.position
            val isStart = entries.size > 1 && index == 0
            val isEnd = entries.size > 1 && index == entries.lastIndex
            val radius = if (entries.size == 1 || isStart || isEnd) 15f else 9f
            val markerColor = when {
                entries.size == 1 -> AndroidGraphicFactory.INSTANCE.createColor(255, 33, 150, 243)
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
                        entries.size == 1 -> "LOCATION"
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

        if (latLongs.all { it == latLongs.first() }) {
            mapView.model.mapViewPosition.mapPosition = MapPosition(latLongs.first(), 17.toByte())
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

    private fun addRouteLine(points: List<LatLong>) {
        if (points.size < 2) return
        val paint: Paint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            color = AndroidGraphicFactory.INSTANCE.createColor(255, 33, 150, 243)
            setStrokeWidth(7f)
            setStyle(Style.STROKE)
        }
        val layer = Polyline(paint, AndroidGraphicFactory.INSTANCE).also {
            it.latLongs.addAll(points)
            mapView.layerManager.layers.add(it)
        }
        routeLayers.add(layer)
    }

    override fun onDestroy() {
        dayLoadJob?.cancel()
        routeLayers.forEach { mapView.layerManager.layers.remove(it) }
        routeLayers.clear()
        pointLayers.forEach { mapView.layerManager.layers.remove(it); it.onDestroy() }
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
