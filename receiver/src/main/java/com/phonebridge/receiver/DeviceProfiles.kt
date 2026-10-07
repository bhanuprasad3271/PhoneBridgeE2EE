package com.phonebridge.receiver

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class DeviceProfile(
    val pairId: String, val name: String, val paired: Boolean = false, val archived: Boolean = false,
    val senderKey: String = "", val historyId: String = pairId, val removedAtMs: Long = 0L
)

/** Pair IDs remain the cryptographic context. Display names never change keys. */
class DeviceProfiles(context: Context) {
    private val prefs = com.phonebridge.shared.SecurePreferences.get(context.applicationContext, "bridge")
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
            val id = row.getString("pairId")
            DeviceProfile(id, row.getString("name"), row.optBoolean("paired"), row.optBoolean("archived"),
                row.optString("senderKey"), row.optString("historyId", id), row.optLong("removedAtMs"))
        }.distinctBy { it.pairId }
    }
    fun activeId(): String? = prefs.getString("pairId", null)
    fun activeName(): String = if (activeId() == "") "Earlier records" else all().firstOrNull { it.pairId == activeId() }?.name ?: "Sender"
    fun select(id: String) {
        require(id.isEmpty() || all().any { it.pairId == id && it.removedAtMs == 0L })
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
    fun historyId(id: String): String = all().firstOrNull { it.pairId == id }?.historyId ?: id
    fun visible(): List<DeviceProfile> = all().filter { it.removedAtMs == 0L }
    fun remove(id: String, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val entries = all().map { if (it.pairId == id && it.removedAtMs == 0L) it.copy(archived = true, removedAtMs = now) else it }
        val edit = prefs.edit()
        if (activeId() == id) edit.putString("pairId", entries.firstOrNull { it.removedAtMs == 0L && !it.archived }?.pairId ?: "")
        write(entries, edit)
    }
    /** Bind only a server-confirmed Sender key. History stays under its original local ID. */
    fun bindSender(id: String, key: String, now: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        if (key.isBlank()) return@synchronized false
        val entries = all()
        val target = entries.firstOrNull { it.pairId == id && it.removedAtMs == 0L } ?: return@synchronized false
        if (target.senderKey.isNotEmpty() && target.senderKey != key) return@synchronized false
        val recovery = entries.filter { it.pairId != id && it.senderKey == key && it.removedAtMs > 0L && now < it.removedAtMs + RECOVERY_MS }
            .maxByOrNull { it.removedAtMs }
        val bound = if (recovery == null) target.copy(senderKey = key, paired = true)
            else target.copy(senderKey = key, paired = true, name = recovery.name, historyId = recovery.historyId, archived = false)
        if (recovery == null && bound == target) return@synchronized true
        write(entries.filter { it.pairId != recovery?.pairId }.map { if (it.pairId == id) bound else it })
        true
    }
    fun forget(id: String) = synchronized(lock) { write(all().filter { it.pairId != id }) }
    private fun update(id: String, transform: (DeviceProfile) -> DeviceProfile) = synchronized(lock) {
        write(all().map { if (it.pairId == id) transform(it) else it })
    }
    private fun write(entries: List<DeviceProfile>, edit: SharedPreferences.Editor = prefs.edit()) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("pairId", it.pairId).put("name", it.name).put("paired", it.paired).put("archived", it.archived)
            .put("senderKey", it.senderKey).put("historyId", it.historyId).put("removedAtMs", it.removedAtMs)) }
        check(edit.putString("device_profiles", array.toString()).commit())
    }
    companion object {
        private val lock = Any()
        const val RECOVERY_MS = 6 * 60 * 60 * 1000L
        fun preferenceName(context: Context, base: String, pairId: String? = DeviceProfiles(context).activeId()): String =
            if (pairId.isNullOrEmpty()) base else "${base}_device_${DeviceProfiles(context).historyId(pairId)}"
    }
}

