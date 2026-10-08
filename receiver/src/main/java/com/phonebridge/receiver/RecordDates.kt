package com.phonebridge.receiver

import android.app.DatePickerDialog
import android.content.Context
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import java.time.LocalDate
import java.time.ZoneId
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

object RecordDateRange {
    fun start(date: LocalDate, weekly: Boolean): LocalDate =
        if (weekly) date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) else date

    fun contains(timestamp: Long, date: LocalDate, weekly: Boolean, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        val first = start(date, weekly)
        return timestamp >= first.atStartOfDay(zone).toInstant().toEpochMilli() &&
            timestamp < first.plusDays(if (weekly) 7 else 1).atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

class RecordDates(context: Context, private val changed: () -> Unit) {
    val view = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val previous = Button(context).apply { text = "‹"; contentDescription = "Previous period" }
    private val label = Button(context).apply { isAllCaps = false; textSize = 13f }
    private val next = Button(context).apply { text = "›"; contentDescription = "Next period" }
    private val dates = mutableMapOf<String, LocalDate>()
    private val profiles = DeviceProfiles(context)
    private var selectedDevice = ""
    private val sectionKey get() = selectedDevice + "|" + section
    private var bounds = 0L to 0L
    private var section = "message"
    private val weekly get() = section == "wp_call"
    private val date get() = dates[sectionKey] ?: LocalDate.now()

    init {
        val width = (48 * context.resources.displayMetrics.density).toInt()
        view.addView(previous, LinearLayout.LayoutParams(width, -2))
        view.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        view.addView(next, LinearLayout.LayoutParams(width, -2))
        previous.setOnClickListener { move(-1) }
        next.setOnClickListener { move(1) }
        label.setOnClickListener {
            DatePickerDialog(context, { _, y, m, d ->
                dates[sectionKey] = LocalDate.of(y, m + 1, d)
                render(); changed()
            }, date.year, date.monthValue - 1, date.dayOfMonth).apply {
                datePicker.maxDate = System.currentTimeMillis()
            }.show()
        }
        label.setOnLongClickListener { dates.remove(sectionKey); render(); changed(); true }
        render()
    }

    fun select(value: String) {
        selectedDevice = profiles.historyId(profiles.activeId().orEmpty())
        section = value; render()
    }
    fun window(): Pair<Long, Long> = bounds
    fun customRange(from: Long?, to: Long?) {
        render()
        if (from != null || to != null) {
            val format = java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
            label.text = (from?.let { format.format(java.util.Date(it)) } ?: "Any") +
                " – " + (to?.let { format.format(java.util.Date(it)) } ?: "Any")
            label.contentDescription = "Custom date range. Choose a date to return to day or week view"
        }
    }
    fun matches(row: RecordEntity): Boolean =
        view.visibility != View.VISIBLE || (row.timestamp >= bounds.first && row.timestamp < bounds.second)

    private fun move(direction: Long) {
        dates[sectionKey] = date.plusDays(direction * if (weekly) 7 else 1)
        render(); changed()
    }

    private fun render() {
        view.visibility = if (section in setOf("message", "call", "notification", "wp_call")) View.VISIBLE else View.GONE
        val first = RecordDateRange.start(date, weekly)
        val zone = ZoneId.systemDefault()
        bounds = first.atStartOfDay(zone).toInstant().toEpochMilli() to
            first.plusDays(if (weekly) 7 else 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val format = DateTimeFormatter.ofPattern("dd MMM yyyy")
        label.text = if (weekly) first.format(format) + " – " + first.plusDays(6).format(format) else first.format(format)
        label.contentDescription = "${label.text}. Choose date; hold to return to today"
        next.isEnabled = first < RecordDateRange.start(LocalDate.now(), weekly)
    }
}

