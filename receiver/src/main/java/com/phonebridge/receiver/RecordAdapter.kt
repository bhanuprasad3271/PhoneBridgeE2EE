package com.phonebridge.receiver

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import org.json.JSONObject
import kotlinx.coroutines.Job
import java.text.DateFormat
import java.util.Date

class RecordAdapter(
    private val bindLocationName: ((RecordEntity, TextView) -> Job?)? = null,
    private val asyncUpdates: Boolean = false,
    private val onDelete: (RecordEntity) -> Unit = {}
) : RecyclerView.Adapter<RecordAdapter.H>() {
    private val differ = AsyncListDiffer(this, object : DiffUtil.ItemCallback<RecordEntity>() {
        override fun areItemsTheSame(old: RecordEntity, new: RecordEntity) = old.id == new.id
        override fun areContentsTheSame(old: RecordEntity, new: RecordEntity) = old == new
    })
    private var immediateItems: List<RecordEntity> = emptyList()
    private val items get() = if (asyncUpdates) differ.currentList else immediateItems

    fun submit(v: List<RecordEntity>) {
        if (asyncUpdates) differ.submitList(v)
        else {
            immediateItems = v
            notifyDataSetChanged()
        }
    }

    class H(v: View) : RecyclerView.ViewHolder(v) {
        var addressJob: Job? = null
        val title: TextView = v.findViewById(R.id.title)
        val body: TextView = v.findViewById(R.id.body)
        val meta: TextView = v.findViewById(R.id.meta)
        val delete: Button = v.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H =
        H(LayoutInflater.from(parent.context).inflate(R.layout.item_record, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: H, position: Int) {
        h.addressJob?.cancel()
        h.addressJob = null
        val r = items[position]
        val metaJson = try { JSONObject(r.metadataJson) } catch (_: Exception) { JSONObject() }
        val whenText = DateFormat.getDateTimeInstance().format(Date(r.timestamp))

        when (r.type) {
            "call" -> {
                val number = metaJson.optString("number")
                val type = metaJson.optString("callType", r.body)
                val history = metaJson.optString("stateHistory", type)
                val duration = formatDuration(metaJson.optLong("durationSec", 0L))
                val account = metaJson.optString("phoneAccountId")
                h.title.text = if (number.isNotBlank() && number != r.title) "${r.title} · $number" else r.title
                h.body.text = history
                h.meta.text = buildString {
                    append(whenText)
                    append(" · Duration: ").append(duration)
                    if (account.isNotBlank()) append(" · SIM/Account: ").append(account)
                }
            }
            "wp_call" -> {
                val app = metaJson.optString("appName", r.source)
                val state = metaJson.optString("callState", "Call")
                val history = metaJson.optString("stateHistory", r.body.ifBlank { state })
                val durationSec = metaJson.optLong("durationSec", 0L)
                h.title.text = "$app · ${r.title}"
                h.body.text = history
                h.meta.text = buildString {
                    append(whenText)
                    if (durationSec > 0L) append(" · Duration: ").append(formatDuration(durationSec))
                }
            }
            "message" -> {
                val app = metaJson.optString("appName", r.source)
                val conversation = metaJson.optString("conversationTitle")
                h.title.text = "$app · ${r.title}"
                h.body.text = r.body
                h.meta.text = buildString {
                    if (conversation.isNotBlank()) append(conversation).append(" · ")
                    append(whenText)
                }
            }
            "notification" -> {
                val app = metaJson.optString("appName", r.source)
                h.title.text = "$app · ${r.title}"
                h.body.text = r.body
                h.meta.text = whenText
            }
            "alert" -> {
                val severity = metaJson.optString("severity", "warning")
                h.title.text = if (severity == "warning") "⚠ ${r.title}" else "✓ ${r.title}"
                h.body.text = r.body
                h.meta.text = whenText
            }
            "status" -> {
                h.title.text = r.title
                h.body.text = r.body
                h.meta.text = whenText
            }
            "location" -> {
                val accuracy = metaJson.optString("accuracyMeters")
                val trigger = metaJson.optString("trigger")
                h.title.text = "Address unavailable"
                h.addressJob = bindLocationName?.invoke(r, h.title)
                h.body.text = whenText
                h.meta.text = buildString {
                    if (accuracy.isNotBlank()) append("Accuracy: ±").append(accuracy).append(" m")
                    val freshness = metaJson.optString("locationFreshness")
                    if (freshness == "cached_interim") {
                        if (isNotEmpty()) append(" · ")
                        append("Cached interim · waiting for new fix")
                    } else if (freshness == "cached_recent") {
                        if (isNotEmpty()) append(" · ")
                        append("Recent cache (<1 min)")
                    }
                    if (trigger.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append(
                            when (trigger) {
                                "receiver_open" -> "Manual refresh"
                                "manual_refresh" -> "Manual refresh"
                                "periodic" -> "15-minute update"
                                "special_text" -> "Special text request"
                                "service_start" -> "Sharing started"
                                else -> trigger
                            }
                        )
                    }
                }
            }
            else -> {
                h.title.text = r.title
                h.body.text = r.body
                h.meta.text = "$whenText · ${r.source}"
            }
        }

        h.delete.visibility = if (r.type == "alert" || r.type == "status") View.VISIBLE else View.GONE
        h.delete.setOnClickListener { onDelete(r) }
    }

    override fun onViewRecycled(holder: H) {
        holder.addressJob?.cancel()
        holder.addressJob = null
        super.onViewRecycled(holder)
    }

    private fun formatDuration(seconds: Long): String {
        val minutes = seconds / 60
        val secs = seconds % 60
        return when {
            minutes > 0 -> "$minutes min $secs sec"
            else -> "$secs sec"
        }
    }
}
