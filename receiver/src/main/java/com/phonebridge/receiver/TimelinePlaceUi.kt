package com.phonebridge.receiver

import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

class TimelinePlaceUi(
    private val activity: AppCompatActivity,
    val store: SavedPlaceStore,
    private val addresses: OfflineTimelineAddress
) {
    fun immediateName(point: TimelinePoint): String = store.match(point)?.name ?: "Looking up offline map…"

    suspend fun displayName(point: TimelinePoint): String {
        store.match(point)?.let { return it.name }
        val offline = addresses.address(point)
        // A name can be saved while an offline lookup is running.
        return store.match(point)?.name ?: offline
    }

    /** Keep an open details dialog up to date after naming, renaming or removal. */
    fun bindName(dialog: AlertDialog, point: TimelinePoint, render: (String) -> Unit) {
        var job: Job? = null
        val refresh: () -> Unit = {
            job?.cancel()
            render(immediateName(point))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.text =
                if (store.match(point) == null) "Name this place" else "Edit place"
            job = activity.lifecycleScope.launch {
                val name = displayName(point)
                if (dialog.isShowing) render(name)
            }
        }
        store.addListener(refresh)
        dialog.setOnDismissListener { job?.cancel(); store.removeListener(refresh) }
        refresh()
    }

    fun showActions(point: TimelinePoint) {
        val place = store.match(point)
        if (place == null) { edit(null, point); return }
        AlertDialog.Builder(activity).setTitle(place.name)
            .setItems(arrayOf("Rename / change area", "Name a different place here", "Remove name")) { _, choice ->
                when (choice) {
                    0 -> edit(place, point)
                    1 -> edit(null, point)
                    2 -> remove(place)
                }
            }.setNegativeButton("Cancel", null).show()
    }

    fun showSavedPlaces() {
        val places = store.all().sortedBy { it.name.lowercase() }
        if (places.isEmpty()) {
            AlertDialog.Builder(activity).setTitle("Saved places")
                .setMessage("No saved names yet. Tap a timeline point or stay, then choose Name this place.")
                .setPositiveButton("Close", null).show()
            return
        }
        AlertDialog.Builder(activity).setTitle("Saved places")
            .setItems(places.map { "${it.name} · ${it.radiusMeters.toInt()} m" }.toTypedArray()) { _, index ->
                val place = places[index]
                val anchor = TimelinePoint(place.latitude, place.longitude, 0L, "", "")
                // Select the exact saved place, even if another place overlaps it.
                AlertDialog.Builder(activity).setTitle(place.name)
                    .setItems(arrayOf("Rename / change area", "Remove name")) { _, choice ->
                        if (choice == 0) edit(place, anchor) else remove(place)
                    }.setNegativeButton("Cancel", null).show()
            }.setPositiveButton("Close", null).show()
    }

    private fun edit(existing: SavedTimelinePlace?, point: TimelinePoint) {
        val padding = (20 * activity.resources.displayMetrics.density).toInt()
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val name = EditText(activity).apply {
            hint = "Place name, e.g. Home"
            contentDescription = "Place name"
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(80))
            setText(existing?.name.orEmpty())
        }
        val radius = EditText(activity).apply {
            hint = "Matching radius in metres"
            contentDescription = "Matching radius in metres"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText((existing?.radiusMeters?.toInt() ?: 100).toString())
        }
        form.addView(name)
        form.addView(TextView(activity).apply { text = "Area radius (10–1000 metres)" })
        form.addView(radius)
        form.addView(TextView(activity).apply {
            text = "Applies to past and future readings in this area. If areas overlap, the closest saved place wins."
        })
        val dialog = AlertDialog.Builder(activity)
            .setTitle(if (existing == null) "Name this place" else "Edit saved place")
            .setView(form).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = name.text.toString().trim()
                val metres = radius.text.toString().toDoubleOrNull()
                if (label.isBlank()) { name.error = "Enter a name"; return@setOnClickListener }
                if (metres == null || !metres.isFinite() || metres !in 10.0..1000.0) {
                    radius.error = "Enter 10 to 1000 metres"; return@setOnClickListener
                }
                val saved = SavedTimelinePlace(existing?.id ?: UUID.randomUUID().toString(), label,
                    existing?.latitude ?: point.latitude, existing?.longitude ?: point.longitude,
                    metres, System.currentTimeMillis())
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                activity.lifecycleScope.launch {
                    if (store.save(saved)) dialog.dismiss()
                    else {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        Toast.makeText(activity, "Could not save this name. Please try again.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun remove(place: SavedTimelinePlace) {
        AlertDialog.Builder(activity).setTitle("Remove ${place.name}?")
            .setMessage("The saved name will be removed. Location history will be kept.")
            .setPositiveButton("Remove") { _, _ ->
                activity.lifecycleScope.launch {
                    if (!store.remove(place.id)) Toast.makeText(activity, "Could not remove name. Please try again.", Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton("Cancel", null).show()
    }
}
