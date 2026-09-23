package com.phonebridge.receiver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class LocationTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private var points: List<TimelinePoint> = emptyList()
    private var selectedIndex = -1
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF60A5FA.toInt(); strokeWidth = 6f; style = Paint.Style.STROKE }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF8FAFC.toInt(); style = Paint.Style.FILL }
    private val startPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF22C55E.toInt(); style = Paint.Style.FILL }
    private val endPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEF4444.toInt(); style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF334155.toInt(); strokeWidth = 1f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF8FAFC.toInt(); textSize = 32f }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCBD5E1.toInt(); textSize = 25f }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xEE1E293B.toInt(); style = Paint.Style.FILL }
    private val mapped = mutableListOf<Pair<Float, Float>>()

    fun setPoints(value: List<TimelinePoint>) {
        points = value.sortedBy { it.timestamp }
        selectedIndex = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xFF0F172A.toInt())
        val left = 55f; val top = 55f; val right = width - 55f; val bottom = height - 80f
        for (i in 1..4) {
            val x = left + (right-left) * i / 5f
            val y = top + (bottom-top) * i / 5f
            canvas.drawLine(x, top, x, bottom, gridPaint)
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        mapped.clear()
        if (points.isEmpty()) {
            canvas.drawText("No points for this day", left, top + 50f, subPaint)
            return
        }
        val minLat = points.minOf { it.latitude }; val maxLat = points.maxOf { it.latitude }
        val minLon = points.minOf { it.longitude }; val maxLon = points.maxOf { it.longitude }
        val latSpan = max(abs(maxLat-minLat), 0.0001)
        val lonSpan = max(abs(maxLon-minLon), 0.0001)
        points.forEach { p ->
            val x = (left + ((p.longitude-minLon)/lonSpan) * (right-left)).toFloat()
            val y = (bottom - ((p.latitude-minLat)/latSpan) * (bottom-top)).toFloat()
            mapped.add(x to y)
        }
        for (i in 1 until mapped.size) {
            canvas.drawLine(mapped[i-1].first, mapped[i-1].second, mapped[i].first, mapped[i].second, linePaint)
        }
        mapped.forEachIndexed { i, xy ->
            val paint = when (i) { 0 -> startPaint; mapped.lastIndex -> endPaint; else -> pointPaint }
            canvas.drawCircle(xy.first, xy.second, if (i == selectedIndex) 15f else 10f, paint)
        }
        if (selectedIndex in points.indices) drawInfo(canvas, points[selectedIndex])
        canvas.drawText("START", left, height - 35f, startPaint.apply { textSize = 25f })
        val endLabel = "END"
        canvas.drawText(endLabel, right - textPaint.measureText(endLabel), height - 35f, endPaint.apply { textSize = 25f })
    }

    private fun drawInfo(canvas: Canvas, p: TimelinePoint) {
        val box = RectF(35f, 30f, width - 35f, 180f)
        canvas.drawRoundRect(box, 18f, 18f, bubblePaint)
        canvas.drawText(SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(p.timestamp)), 60f, 78f, textPaint)
        canvas.drawText(String.format(Locale.US, "%.6f, %.6f", p.latitude, p.longitude), 60f, 118f, subPaint)
        val label = when (p.trigger) {
            "manual_refresh" -> "Manual refresh"
            "special_text" -> "Special text request"
            "periodic" -> "Automatic update"
            else -> p.trigger.ifBlank { "Location" }
        }
        canvas.drawText("Accuracy ±${p.accuracy} m · $label", 60f, 158f, subPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP || mapped.isEmpty()) return true
        var best = -1; var bestDistance = Float.MAX_VALUE
        mapped.forEachIndexed { i, xy ->
            val d = abs(event.x-xy.first) + abs(event.y-xy.second)
            if (d < bestDistance) { bestDistance = d; best = i }
        }
        if (bestDistance < 100f) { selectedIndex = best; invalidate() }
        return true
    }
}
