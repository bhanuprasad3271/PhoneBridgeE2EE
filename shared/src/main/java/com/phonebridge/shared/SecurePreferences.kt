package com.phonebridge.shared

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Keeps the SharedPreferences contract, encrypting values and migrating existing preferences. */
class SecurePreferences private constructor(context: Context, private val name: String) : SharedPreferences {
    private val disk = context.getSharedPreferences("$name.secure-v1", Context.MODE_PRIVATE)
    private val listeners = ConcurrentHashMap<SharedPreferences.OnSharedPreferenceChangeListener, SharedPreferences.OnSharedPreferenceChangeListener>()
    init {
        val old = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        if (!disk.getBoolean(MIGRATED, false)) {
            val edit = disk.edit()
            for ((key, value) in old.all) if (value != null) edit.putString(key, encode(key, value))
            check(edit.putBoolean(MIGRATED, true).commit()) { "Cannot migrate private settings" }
        }
        // Detect a missing/invalid Keystore key before any writes can create a new key.
        disk.all.keys.firstOrNull { it != MIGRATED }?.let { value(it) }
        // A crash between the two commits is safe: the encrypted copy is authoritative.
        if (old.all.isNotEmpty()) check(old.edit().clear().commit()) { "Cannot remove old settings copy" }
    }
    private fun encode(key: String, value: Any): String {
        val type = when (value) { is String -> "s"; is Boolean -> "b"; is Int -> "i"; is Long -> "l"; is Float -> "f"; is Set<*> -> "ss"; else -> error("Unsupported setting") }
        val json = JSONObject().put("t", type).put("v", if (value is Set<*>) JSONArray(value.toList()) else value)
        return LocalVault.seal(json.toString().toByteArray(Charsets.UTF_8), "prefs|$name|$key")
    }
    private fun value(key: String): Any? {
        val raw = disk.getString(key, null) ?: return null
        val plain = LocalVault.open(raw, "prefs|$name|$key")
        val json = try { JSONObject(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
        return when (json.getString("t")) {
            "s" -> json.getString("v"); "b" -> json.getBoolean("v"); "i" -> json.getInt("v")
            "l" -> json.getLong("v"); "f" -> json.getDouble("v").toFloat()
            "ss" -> json.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            else -> error("Invalid private setting")
        }
    }
    override fun getAll(): Map<String, *> = disk.all.keys.filter { it != MIGRATED }.associateWith { value(it) }
    override fun getString(key: String, defValue: String?): String? = value(key) as? String ?: defValue
    @Suppress("UNCHECKED_CAST") override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (value(key) as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int): Int = value(key) as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = value(key) as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = value(key) as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = value(key) as? Boolean ?: defValue
    override fun contains(key: String): Boolean = key != MIGRATED && disk.contains(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        val proxy = listeners.getOrPut(listener) { SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key != MIGRATED) listener.onSharedPreferenceChanged(this, key) } }
        disk.registerOnSharedPreferenceChangeListener(proxy)
    }
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(listener)?.let { disk.unregisterOnSharedPreferenceChangeListener(it) }
    }
    private inner class Editor : SharedPreferences.Editor {
        private val delegate = disk.edit()
        private fun put(key: String, value: Any?): SharedPreferences.Editor {
            require(key != MIGRATED)
            if (value == null) delegate.remove(key) else delegate.putString(key, encode(key, value))
            return this
        }
        override fun putString(key: String, value: String?) = put(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?) = put(key, values)
        override fun putInt(key: String, value: Int) = put(key, value)
        override fun putLong(key: String, value: Long) = put(key, value)
        override fun putFloat(key: String, value: Float) = put(key, value)
        override fun putBoolean(key: String, value: Boolean) = put(key, value)
        override fun remove(key: String): SharedPreferences.Editor { require(key != MIGRATED); delegate.remove(key); return this }
        override fun clear(): SharedPreferences.Editor { delegate.clear().putBoolean(MIGRATED, true); return this }
        override fun commit(): Boolean = delegate.commit()
        override fun apply() = delegate.apply()
    }
    companion object {
        private const val MIGRATED = "__phonebridge_secure_migrated"
        private val instances = mutableMapOf<String, SecurePreferences>()
        @Synchronized fun get(context: Context, name: String): SharedPreferences =
            instances.getOrPut(context.packageName + ":" + name) { SecurePreferences(context.applicationContext, name) }
    }
}
