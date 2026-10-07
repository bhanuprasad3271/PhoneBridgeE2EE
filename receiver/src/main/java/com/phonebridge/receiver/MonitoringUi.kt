package com.phonebridge.receiver

import android.content.Context
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Saved filters of data already received; no collection or account lookup. */
class MonitoringUi(private val activity: AppCompatActivity, private val records: () -> List<RecordEntity>) {
    private val prefs get() = com.phonebridge.shared.SecurePreferences.get(activity, DeviceProfiles.preferenceName(activity, "monitoring"))
    private var identities = read()
    private var refreshOpen: (() -> Unit)? = null
    private var dialog: AlertDialog? = null
    private var searchJob: Job? = null
    private var searchGeneration = 0

    private fun read(): MutableList<MonitorIdentity> = try {
        val array = JSONArray(prefs.getString("identities", "[]"))
        (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            MonitorIdentity(obj.getString("id"), obj.getString("label"), obj.getString("kind"),
                obj.getString("value"), obj.optBoolean("partial"), obj.optBoolean("enabled", true))
        }.toMutableList()
    } catch (_: Exception) { mutableListOf() }

    private fun save() {
        val array = JSONArray()
        identities.forEach { item -> array.put(JSONObject().put("id", item.id).put("label", item.label)
            .put("kind", item.kind).put("value", item.value).put("partial", item.partial).put("enabled", item.enabled)) }
        prefs.edit().putString("identities", array.toString()).apply()
    }

    fun refresh() { refreshOpen?.invoke() }
    fun deviceChanged() { close(); identities = read() }
    fun close() {
        searchGeneration++
        searchJob?.cancel()
        searchJob = null
        refreshOpen = null
        dialog?.dismiss()
        dialog = null
    }

    private suspend fun matching(item: MonitorIdentity, snapshot: List<RecordEntity>): List<RecordEntity> =
        withContext(Dispatchers.Default) {
            val matches = ArrayList<RecordEntity>()
            val seen = HashSet<List<String>>()
            val accepts = MonitorMatcher.prepare(item)
            for (row in snapshot) {
                currentCoroutineContext().ensureActive()
                if (row.type !in setOf("message", "call", "notification", "wp_call")) continue
                val meta = try { JSONObject(row.metadataJson) } catch (_: Exception) { JSONObject() }
                val fields = listOf(row.title, row.body) + listOf("number", "phoneNumber", "sender",
                    "senderName", "username", "userName", "conversationTitle", "contactName").map { meta.optString(it) }
                if (accepts(row.source + " " + meta.optString("appName"), fields) &&
                    seen.add(listOf(row.type, row.source, row.timestamp.toString(), row.title, row.body))) {
                    matches.add(row)
                }
            }
            matches
        }

    fun show(selectedId: String? = null) {
        close()
        val builder = AlertDialog.Builder(activity)
        val context = builder.context
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 8, 16, 8) }
        val picker = Spinner(context)
        val info = TextView(context).apply { textSize = 13f; setPadding(8, 12, 8, 12) }
        val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
        }
        val more = Button(context).apply { text = "Load more"; isAllCaps = false; visibility = View.GONE }
        val actions = LinearLayout(context)
        val add = Button(context).apply { text = "Add"; isAllCaps = false }
        val edit = Button(context).apply { text = "Manage"; isAllCaps = false }
        actions.addView(add, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
        val adapter = RecordAdapter(asyncUpdates = true)
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            this.adapter = adapter
        }
        panel.addView(picker)
        panel.addView(actions)
        panel.addView(info)
        panel.addView(progress, LinearLayout.LayoutParams(-1, (4 * context.resources.displayMetrics.density).toInt()))
        panel.addView(list, LinearLayout.LayoutParams(-1, (activity.resources.displayMetrics.heightPixels * 0.45).toInt()))
        panel.addView(more)
        fun selected() = identities.getOrNull(picker.selectedItemPosition)
        picker.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item,
            identities.map { it.label + if (it.enabled) "" else " (paused)" }.ifEmpty { listOf("No saved identities") })
        picker.setSelection(identities.indexOfFirst { it.id == selectedId }.coerceAtLeast(0))
        var allMatches: List<RecordEntity> = emptyList()
        var visibleLimit = 100
        var lastItem: MonitorIdentity? = null
        var lastSnapshot: List<RecordEntity>? = null
        fun renderMatches(item: MonitorIdentity) {
            val count = minOf(visibleLimit, allMatches.size)
            adapter.submit(allMatches.take(count))
            more.visibility = if (count < allMatches.size) View.VISIBLE else View.GONE
            info.text = "${item.kind}: ${item.value} · ${allMatches.size} matches · showing $count\nAll stored dates · matches may be mentions in text."
        }
        more.setOnClickListener {
            visibleLimit += 100
            selected()?.let { renderMatches(it) }
        }
        val update: () -> Unit = update@ {
            val item = selected()
            val snapshot = records()
            if (item == lastItem && snapshot === lastSnapshot) return@update
            val changedIdentity = item != lastItem
            lastItem = item
            lastSnapshot = snapshot
            searchJob?.cancel()
            val generation = ++searchGeneration
            edit.isEnabled = item != null
            if (changedIdentity || item == null || !item.enabled) {
                visibleLimit = 100
                allMatches = emptyList()
                adapter.submit(emptyList())
                more.visibility = View.GONE
            }
            if (item == null || !item.enabled) {
                progress.visibility = View.GONE
                info.text = if (item == null) "Add a number, username or text to filter received records."
                    else "Paused · use Manage to resume."
                return@update
            }
            progress.visibility = View.VISIBLE
            info.text = "Searching received records…"
            searchJob = activity.lifecycleScope.launch {
                val results = matching(item, snapshot)
                if (generation != searchGeneration) return@launch
                allMatches = results
                progress.visibility = View.GONE
                renderMatches(item)
            }
        }
        picker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { update() }
            override fun onNothingSelected(parent: AdapterView<*>?) { update() }
        }
        add.setOnClickListener { editor(null) }
        edit.setOnClickListener { selected()?.let { manage(it) } }
        val shown = builder.setTitle("Monitoring").setView(panel).setPositiveButton("Close", null).create()
        dialog = shown
        shown.setOnDismissListener { if (dialog === shown) { close() } }
        shown.show()
        refreshOpen = update
        update()
    }

    private fun manage(item: MonitorIdentity) {
        AlertDialog.Builder(activity).setTitle(item.label)
            .setItems(arrayOf("Edit / rename", if (item.enabled) "Pause" else "Resume", "Delete")) { _, index ->
                when (index) {
                    0 -> editor(item)
                    1 -> { identities[identities.indexOf(item)] = item.copy(enabled = !item.enabled); save(); show(item.id) }
                    2 -> AlertDialog.Builder(activity).setTitle("Delete saved filter?")
                        .setMessage("Received records will remain on this phone.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                            identities.remove(item); save(); show()
                        }.show()
                }
            }.show()
    }

    private fun editor(existing: MonitorIdentity?) {
        val builder = AlertDialog.Builder(activity)
        val context = builder.context
        val panel = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        val label = EditText(context).apply { hint = "Display name (optional)"; setText(existing?.label); isSingleLine = true }
        val kind = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, MonitorMatcher.kinds)
            setSelection(MonitorMatcher.kinds.indexOf(existing?.kind).coerceAtLeast(0))
        }
        val value = EditText(context).apply { hint = "Phone number, username or text"; setText(existing?.value); isSingleLine = true }
        val partial = CheckBox(context).apply { text = "Partial text match (numbers always exact)"; isChecked = existing?.partial == true }
        panel.addView(kind); panel.addView(label); panel.addView(value); panel.addView(partial)
        panel.addView(TextView(context).apply { text = "Only received text is searchable. If an app sends a display name instead of its username, add that name separately. Indian local numbers use +91 normalization." })
        val editor = builder.setTitle(if (existing == null) "Add identity" else "Edit identity").setView(panel)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        editor.setOnShowListener {
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val entered = value.text.toString().trim()
                if (entered.removePrefix("@").isBlank()) { value.error = "Enter an identifier"; return@setOnClickListener }
                if (kind.selectedItem == "Phone" && MonitorMatcher.phone(entered).length !in 7..15) {
                    value.error = "Enter a complete phone number"; return@setOnClickListener
                }
                val item = MonitorIdentity(existing?.id ?: UUID.randomUUID().toString(),
                    label.text.toString().trim().ifBlank { entered }, kind.selectedItem.toString(), entered,
                    partial.isChecked, existing?.enabled ?: true)
                if (existing == null) identities.add(item) else identities[identities.indexOf(existing)] = item
                save(); editor.dismiss(); show(item.id)
            }
        }
        editor.show()
    }
}

