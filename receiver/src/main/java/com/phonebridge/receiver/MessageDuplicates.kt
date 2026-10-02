package com.phonebridge.receiver

import org.json.JSONObject

/** Display-only exact deduplication. Ambiguous historical entries stay visible. */
object MessageDuplicates {
    fun visible(rows: List<RecordEntity>): List<RecordEntity> {
        val seen = HashSet<List<String>>()
        return rows.filter { row ->
            if (row.type != "message") true
            else {
                val meta = try { JSONObject(row.metadataJson) } catch (_: Exception) { JSONObject() }
                val conversation = meta.optString("conversationTitle").ifBlank { row.title }
                seen.add(listOf(row.source, conversation, row.title, row.body, row.timestamp.toString()))
            }
        }
    }
}
