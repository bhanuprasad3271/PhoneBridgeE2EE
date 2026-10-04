package com.phonebridge.collector

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** Private, encrypted payloads awaiting a server-validated pairing. No plaintext is stored. */
class SenderOutbox(context: Context) {
    private val db = helper(context).writableDatabase
    fun put(id: String, pair: String, payload: Map<String, Any>) {
        db.insertWithOnConflict("outbox", null, ContentValues().apply {
            put("id", id); put("pairId", pair); put("payload", JSONObject(payload).toString())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun next(pair: String): Map<String, Any>? = db.query("outbox", arrayOf("payload"), "pairId = ?", arrayOf(pair), null, null, "rowid ASC", "1").use { c ->
        if (!c.moveToFirst()) null else JSONObject(c.getString(0)).let { json -> json.keys().asSequence().associateWith { json.get(it) } }
    }
    fun delete(id: String) { db.delete("outbox", "id = ?", arrayOf(id)) }
    fun deletePair(pair: String) { db.delete("outbox", "pairId = ?", arrayOf(pair)) }
    companion object {
        @Volatile private var instance: SQLiteOpenHelper? = null
        private fun helper(context: Context): SQLiteOpenHelper = instance ?: synchronized(this) {
            instance ?: object : SQLiteOpenHelper(context.applicationContext, "sender-outbox.db", null, 1) {
                override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE outbox (id TEXT PRIMARY KEY NOT NULL, pairId TEXT NOT NULL, payload TEXT NOT NULL)") }
                override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }.also { instance = it }
        }
    }
}
