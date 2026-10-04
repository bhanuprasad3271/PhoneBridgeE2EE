package com.phonebridge.receiver

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class DeviceProfile(val pairId: String, val name: String, val paired: Boolean = false, val archived: Boolean = false)

/** Pair IDs remain the cryptographic context. Display names never change keys. */
class DeviceProfiles(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("bridge", Context.MODE_PRIVATE)
    init {
        synchronized(lock) {
            if (!prefs.getBoolean("device_profiles_migrated", false)) {
                val legacy = prefs.getString("pairId", null)
                val entries = all().toMutableList()
                if (!legacy.isNullOrBlank() && entries.none { it.pairId == legacy }) {
                    entries.add(DeviceProfile(legacy, "Sender 1"))
                }
                write(entries, prefs.edit().putBoolean("device_profiles_migrated", true))
            }
        }
    }
    fun all(): List<DeviceProfile> {
        val array = JSONArray(prefs.getString("device_profiles", "[]"))
        return (0 until array.length()).map { i ->
            val row = array.getJSONObject(i)
            DeviceProfile(row.getString("pairId"), row.getString("name"), row.optBoolean("paired"), row.optBoolean("archived"))
        }.distinctBy { it.pairId }
    }
    fun activeId(): String? = prefs.getString("pairId", null)
    fun activeName(): String = if (activeId() == "") "Earlier records" else all().firstOrNull { it.pairId == activeId() }?.name ?: "Sender"
    fun select(id: String) {
        require(id.isEmpty() || all().any { it.pairId == id })
        check(prefs.edit().putString("pairId", id).commit())
    }
    fun add(id: String) = synchronized(lock) {
        require(id.isNotBlank())
        val entries = all()
        if (entries.none { it.pairId == id }) write(entries + DeviceProfile(id, "Sender ${entries.size + 1}"))
    }
    fun markPaired(id: String) = update(id) { it.copy(paired = true) }
    fun rename(id: String, name: String) {
        require(name.trim().isNotEmpty() && name.trim().length <= 60)
        update(id) { it.copy(name = name.trim()) }
    }
    fun archive(id: String) = update(id) { it.copy(archived = true) }
    private fun update(id: String, transform: (DeviceProfile) -> DeviceProfile) = synchronized(lock) {
        write(all().map { if (it.pairId == id) transform(it) else it })
    }
    private fun write(entries: List<DeviceProfile>, edit: SharedPreferences.Editor = prefs.edit()) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("pairId", it.pairId).put("name", it.name).put("paired", it.paired).put("archived", it.archived)) }
        check(edit.putString("device_profiles", array.toString()).commit())
    }
    companion object {
        private val lock = Any()
        fun preferenceName(context: Context, base: String, pairId: String? = DeviceProfiles(context).activeId()): String =
            if (pairId.isNullOrEmpty()) base else "${base}_device_${pairId}"
    }
}
