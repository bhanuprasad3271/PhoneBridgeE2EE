package com.phonebridge.mappreview

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.mapsforge.core.model.MapPosition
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.ExternalRenderTheme
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var mapView: MapView
    private lateinit var status: TextView
    private var store: MapDataStore? = null
    private var cache: TileCache? = null
    private var layer: TileRendererLayer? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importAndOpen(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidGraphicFactory.createInstance(application)
        setContentView(R.layout.activity_main)
        mapView = findViewById(R.id.mapView)
        status = findViewById(R.id.status)
        mapView.setBuiltInZoomControls(true)
        mapView.mapScaleBar.isVisible = false
        findViewById<Button>(R.id.pickMap).setOnClickListener { picker.launch(arrayOf("application/octet-stream", "application/*")) }
    }

    private fun importAndOpen(uri: Uri) {
        status.text = "Opening map…"
        try {
            val f = File(cacheDir, "preview.map")
            contentResolver.openInputStream(uri)!!.use { input -> f.outputStream().use { input.copyTo(it) } }
            layer?.let { mapView.layerManager.layers.remove(it) }; layer?.onDestroy(); store?.close(); cache?.destroy()
            store = MapFile(f)
            cache = AndroidUtil.createTileCache(this, "preview-cache", mapView.model.displayModel.tileSize, 1f, mapView.model.frameBufferModel.overdrawFactor)
            layer = TileRendererLayer(cache, store, mapView.model.mapViewPosition, AndroidGraphicFactory.INSTANCE).also {
                it.setXmlRenderTheme(ExternalRenderTheme(File(filesDir, "phonebridge-dark.xml").also { theme -> assets.open("phonebridge-dark.xml").use { i -> theme.outputStream().use { o -> i.copyTo(o) } } }))
                mapView.layerManager.layers.add(it)
            }
            val start = store!!.startPosition()
            if (start != null) mapView.model.mapViewPosition.mapPosition = MapPosition(start, (store!!.startZoomLevel() ?: 10).toByte())
            status.text = "PhoneBridge Dark preview · local rendering only"
        } catch (e: Exception) { status.text = "Could not open map: " + (e.message ?: "unknown error") }
    }

    override fun onDestroy() {
        layer?.let { mapView.layerManager.layers.remove(it) }; layer?.onDestroy(); store?.close(); cache?.destroy(); mapView.destroyAll(); AndroidGraphicFactory.clearResourceMemoryCache(); super.onDestroy()
    }
}
