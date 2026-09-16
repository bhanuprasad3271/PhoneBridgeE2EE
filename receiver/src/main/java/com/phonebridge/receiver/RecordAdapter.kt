package com.phonebridge.receiver

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.DateFormat
import java.util.Date

class RecordAdapter : RecyclerView.Adapter<RecordAdapter.H>() {
    private var items: List<RecordEntity> = emptyList()

    fun submit(v: List<RecordEntity>) { items = v; notifyDataSetChanged() }

    class H(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.title)
        val body: TextView = v.findViewById(R.id.body)
        val meta: TextView = v.findViewById(R.id.meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H =
        H(LayoutInflater.from(parent.context).inflate(R.layout.item_record, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: H, position: Int) {
        val r = items[position]
        h.title.text = "[${r.type}] ${r.title}"
        h.body.text = r.body
        h.meta.text = "${DateFormat.getDateTimeInstance().format(Date(r.timestamp))} • ${r.source}"
    }
}
