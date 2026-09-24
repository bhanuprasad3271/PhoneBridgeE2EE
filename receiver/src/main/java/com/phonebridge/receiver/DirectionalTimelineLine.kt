package com.phonebridge.receiver

import org.mapsforge.core.graphics.Canvas
import org.mapsforge.core.graphics.Paint
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.BoundingBox
import org.mapsforge.core.model.Point
import org.mapsforge.core.model.Rotation
import org.mapsforge.core.util.MercatorProjection
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.layer.overlay.Polyline
import kotlin.math.hypot

/** Straight chronological connections, not a reconstructed road route. */
class DirectionalTimelineLine(paint: Paint) : Polyline(paint, AndroidGraphicFactory.INSTANCE) {
    private val arrowPaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
        color = AndroidGraphicFactory.INSTANCE.createColor(255, 255, 193, 7)
        setStyle(Style.FILL)
    }

    override fun draw(bounds: BoundingBox, zoom: Byte, canvas: Canvas, topLeft: Point, rotation: Rotation) {
        super.draw(bounds, zoom, canvas, topLeft, rotation)
        val size = MercatorProjection.getMapSize(zoom, displayModel.tileSize)
        val preferredArrowSize = 9.0 * displayModel.scaleFactor
        val points = latLongs.toList()
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val ax = MercatorProjection.longitudeToPixelX(a.longitude, size) - topLeft.x
            val ay = MercatorProjection.latitudeToPixelY(a.latitude, size) - topLeft.y
            val bx = MercatorProjection.longitudeToPixelX(b.longitude, size) - topLeft.x
            val by = MercatorProjection.latitudeToPixelY(b.latitude, size) - topLeft.y
            val length = hypot(bx - ax, by - ay)
            // Coincident dots have no direction; shrink arrows on short visible connections.
            if (length < 2.0) continue
            val arrowSize = minOf(preferredArrowSize, length / 5.0)
            val ux = (bx - ax) / length
            val uy = (by - ay) / length
            // Offset from halfway so outward/return arrows on the same line stay distinct.
            val x = ax + (bx - ax) * 0.62
            val y = ay + (by - ay) * 0.62
            val path = AndroidGraphicFactory.INSTANCE.createPath()
            path.moveTo((x + ux * arrowSize).toFloat(), (y + uy * arrowSize).toFloat())
            path.lineTo((x - ux * arrowSize - uy * arrowSize * 0.65).toFloat(),
                (y - uy * arrowSize + ux * arrowSize * 0.65).toFloat())
            path.lineTo((x - ux * arrowSize + uy * arrowSize * 0.65).toFloat(),
                (y - uy * arrowSize - ux * arrowSize * 0.65).toFloat())
            path.close()
            canvas.drawPath(path, arrowPaint)
        }
    }
}
