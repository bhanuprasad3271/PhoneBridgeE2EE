package com.phonebridge.receiver

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import com.phonebridge.shared.LocalVault
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.SecureRandom

/** One-time copy/verify/rename migration. The original is never deleted before validation. */
object EncryptedHistory {
    const val NAME = "phonebridge-encrypted.db"
    @Synchronized fun prepare(context: Context): SupportOpenHelperFactory {
        System.loadLibrary("sqlcipher")
        val target = context.getDatabasePath(NAME)
        target.parentFile?.mkdirs()
        val keyFile = AtomicFile(File(context.noBackupFilesDir, "history-key-v1"))
        val password: ByteArray
        if (keyFile.baseFile.exists() || File(keyFile.baseFile.path + ".bak").exists()) {
            password = LocalVault.open(keyFile.readFully().toString(Charsets.UTF_8), "history-key-v1")
        } else {
            check(!target.exists()) { "History key missing; do not clear app data" }
            password = Base64.encode(SecureRandom().generateSeed(32), Base64.NO_WRAP)
            val wrapped = LocalVault.seal(password, "history-key-v1").toByteArray(Charsets.UTF_8)
            val out = keyFile.startWrite()
            try { out.write(wrapped); keyFile.finishWrite(out) }
            catch (e: Exception) { keyFile.failWrite(out); throw e }
        }
        try {
            val old = context.getDatabasePath("phonebridge.db")
            if (old.exists()) {
                // Old Room has not been opened in this process; this is the sole migration entry.
                val original = SQLiteDatabase.openOrCreateDatabase(old, "", null, null, null)
                try {
                    original.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { while (it.moveToNext()) { } }
                    val count = original.rawQuery("SELECT COUNT(*) FROM records", null).use { it.moveToFirst(); it.getLong(0) }
                    val version = original.version
                    if (!target.exists()) {
                        val pending = File(target.path + ".migrating")
                        // A previously interrupted export is disposable; the original still exists.
                        if (pending.exists()) check(pending.delete())
                        original.execSQL("ATTACH DATABASE ? AS encrypted KEY ?", arrayOf(pending.path, password.toString(Charsets.UTF_8)))
                        try {
                            original.rawQuery("SELECT sqlcipher_export('encrypted')", null).use { while (it.moveToNext()) { } }
                            original.execSQL("PRAGMA encrypted.user_version = $version")
                        } finally { original.execSQL("DETACH DATABASE encrypted") }
                        verify(pending, password, count)
                        check(pending.renameTo(target)) { "Cannot install encrypted history" }
                    }
                    verify(target, password, count)
                } finally { original.close() }
                check(context.deleteDatabase("phonebridge.db")) { "Cannot remove plaintext history after verified migration" }
            }
            return SupportOpenHelperFactory(password.copyOf())
        } finally { password.fill(0) }
    }

    private fun verify(file: File, password: ByteArray, expectedCount: Long) {
        val db = SQLiteDatabase.openOrCreateDatabase(file, password, null, null, null)
        try {
            db.rawQuery("PRAGMA integrity_check", null).use { check(it.moveToFirst() && it.getString(0) == "ok") }
            db.rawQuery("PRAGMA cipher_integrity_check", null).use { check(!it.moveToFirst()) }
            db.rawQuery("SELECT COUNT(*) FROM records", null).use { check(it.moveToFirst() && it.getLong(0) == expectedCount) }
        } finally { db.close() }
    }
}
