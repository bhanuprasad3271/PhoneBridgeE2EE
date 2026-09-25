package com.phonebridge.receiver

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The same grouped entries as the map, including one row per distinct stay. */
class TimelineTextUi(private val activity: AppCompatActivity, private val places: TimelinePlaceUi) {
    fun show(entries: List<TimelineEntry>, title: String, onSelected: (Int) -> Unit) {
        if (entries.isEmpty()) {
            AlertDialog.Builder(activity).setTitle(title)
                .setMessage("No locations recorded for this day.")
                .setNeutralButton("Saved places") { _, _ -> places.showSavedPlaces() }
                .setPositiveButton("Close", null).show()
            return
        }
        fun row(index: Int, name: String): TimelineTextColumns {
            val saved = places.store.match(entries[index].position)
            return TimelineTextRows.columns(entries[index], saved?.name ?: name, customName = saved != null)
        }
        val rows = entries.mapIndexed { index, entry -> row(index, places.immediateName(entry.position)) }.toMutableList()
        val builder = AlertDialog.Builder(activity)
        val context = builder.context
        val list = ListView(context).apply {
            dividerHeight = (resources.displayMetrics.density).toInt().coerceAtLeast(1)
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView as? ColumnsView ?: ColumnsView(context)
                view.bind(rows[position])
                return view
            }
        }
        list.adapter = adapter
        val header = ColumnsView(context, header = true).apply {
            bind(TimelineTextColumns("Time", "Place / address", "Duration"))
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Bound just the scrollable area; headers and actions remain visible.
            val metrics = resources.displayMetrics
            val height = minOf((metrics.heightPixels * 0.52).toInt(), (480 * metrics.density).toInt())
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
        }
        val dialog = builder.setTitle(title).setView(content)
            .setNeutralButton("Saved places", null).setPositiveButton("Close", null).create()
        var lookup: Job? = null
        val refresh: () -> Unit = {
            lookup?.cancel()
            entries.forEachIndexed { index, entry -> rows[index] = row(index, places.immediateName(entry.position)) }
            adapter.notifyDataSetChanged()
            lookup = activity.lifecycleScope.launch {
                for ((index, entry) in entries.withIndex()) {
                    val name = places.displayName(entry.position)
                    if (!dialog.isShowing) break
                    rows[index] = row(index, name)
                    adapter.notifyDataSetChanged()
                }
            }
        }
        dialog.setOnShowListener {
            // Rows map directly to entries: the column header is outside the list.
            list.setOnItemClickListener { _, _, index, _ -> onSelected(index) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { places.showSavedPlaces() }
            places.store.addListener(refresh)
            refresh()
        }
        dialog.setOnDismissListener { lookup?.cancel(); places.store.removeListener(refresh) }
        dialog.show()
    }

    /** Shared column widths for header and every row. Text wraps without truncation. */
    private class ColumnsView(context: Context, header: Boolean = false) : LinearLayout(context) {
        private val time: TextView
        private val place: TextView
        private val duration: TextView

        init {
            orientation = HORIZONTAL
            gravity = Gravity.TOP
            val density = resources.displayMetrics.density
            val horizontal = (16 * density).toInt()
            val vertical = ((if (header) 8 else 12) * density).toInt()
            setPadding(horizontal, vertical, horizontal, vertical)
            if (!header) minimumHeight = (56 * density).toInt()
            fun cell(weight: Float, align: Int, bold: Boolean = false): TextView {
                return TextView(context).apply {
                    textSize = if (header) 11f else 13f
                    gravity = align or Gravity.TOP
                    if (bold || header) setTypeface(typeface, Typeface.BOLD)
                    if (header) alpha = 0.75f
                    addView(this, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight))
                }
            }
            time = cell(0.32f, Gravity.START)
            place = cell(0.46f, Gravity.START, bold = true).apply { setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0) }
            duration = cell(0.22f, Gravity.END)
        }

        fun bind(row: TimelineTextColumns) {
            time.text = row.time
            place.text = row.place
            duration.text = row.duration
        }
    }
}
