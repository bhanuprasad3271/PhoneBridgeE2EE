package com.phonebridge.receiver

import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The same grouped entries as the map, including one row per distinct stay. */
class TimelineTextUi(private val activity: AppCompatActivity, private val places: TimelinePlaceUi) {
    fun show(entries: List<TimelineEntry>, title: String, staysOnly: Boolean = false, onSelected: (Int) -> Unit) {
        if (entries.isEmpty()) {
            AlertDialog.Builder(activity).setTitle(title)
                .setMessage("No locations recorded for this day.")
                .setNeutralButton("Saved places") { _, _ -> places.showSavedPlaces() }
                .setPositiveButton("Close", null).show()
            return
        }
        fun row(index: Int, name: String) = TimelineTextRows.text(entries[index],
            TimelineTextRows.role(entries[index], index, entries.size, staysOnly), name)
        val labels = entries.mapIndexed { index, entry -> row(index, places.immediateName(entry.position)) }.toMutableList()
        val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, labels)
        val dialog = AlertDialog.Builder(activity).setTitle(title).setAdapter(adapter, null)
            .setNeutralButton("Saved places", null).setPositiveButton("Close", null).create()
        var lookup: Job? = null
        val refresh: () -> Unit = {
            lookup?.cancel()
            entries.forEachIndexed { index, entry -> labels[index] = row(index, places.immediateName(entry.position)) }
            adapter.notifyDataSetChanged()
            lookup = activity.lifecycleScope.launch {
                for ((index, entry) in entries.withIndex()) {
                    val name = places.displayName(entry.position)
                    if (!dialog.isShowing) break
                    labels[index] = row(index, name)
                    adapter.notifyDataSetChanged()
                }
            }
        }
        dialog.setOnShowListener {
            // Keep the list underneath details, so naming a place refreshes this list immediately.
            dialog.listView.setOnItemClickListener { _, _, index, _ -> onSelected(index) }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { places.showSavedPlaces() }
            places.store.addListener(refresh)
            refresh()
        }
        dialog.setOnDismissListener { lookup?.cancel(); places.store.removeListener(refresh) }
        dialog.show()
    }
}
