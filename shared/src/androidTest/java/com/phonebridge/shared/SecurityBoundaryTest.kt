package com.phonebridge.shared

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SecurityBoundaryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun rejected(block: () -> Unit) {
        try { block() } catch (_: Exception) { return }
        fail("Unauthorized/tampered data was accepted")
    }
    @Test fun onlyPairedReceiverDecryptsAndTamperingIsRejected() {
        val aliases = (1..3).map { "security-test-${UUID.randomUUID()}" }
        try {
            val sender = CryptoBox(context, aliases[0]); val receiver = CryptoBox(context, aliases[1]); val other = CryptoBox(context, aliases[2])
            val pair = "123456"
            val key = sender.deriveAesKey(receiver.publicKeyB64(), pair)
            val authorized = receiver.deriveAesKey(sender.publicKeyB64(), pair)
            val wrongReceiver = other.deriveAesKey(sender.publicKeyB64(), pair)
            val wrongPair = receiver.deriveAesKey(sender.publicKeyB64(), "654321")
            val aad = "$pair|record-1|message|1".toByteArray()
            val plain = "private sender data".toByteArray()
            val (ct, nonce) = sender.encrypt(key, plain, aad)
            assertArrayEquals(plain, receiver.decrypt(authorized, ct, nonce, aad))
            rejected { other.decrypt(wrongReceiver, ct, nonce, aad) }
            rejected { receiver.decrypt(wrongPair, ct, nonce, aad) }
            for (changed in listOf("123456|record-2|message|1", "123456|record-1|location|1", "654321|record-1|message|1")) {
                rejected { receiver.decrypt(authorized, ct, nonce, changed.toByteArray()) }
            }
            fun corrupt(s: String): String {
                val bytes = Base64.decode(s, Base64.NO_WRAP); bytes[0] = (bytes[0].toInt() xor 1).toByte()
                return Base64.encodeToString(bytes, Base64.NO_WRAP)
            }
            rejected { receiver.decrypt(authorized, corrupt(ct), nonce, aad) }
            rejected { receiver.decrypt(authorized, ct, corrupt(nonce), aad) }
            rejected { sender.deriveAesKey("not a public key", pair) }
            val nonces = (1..100).map { sender.encrypt(key, plain, aad).second }
            assertEquals(100, nonces.toSet().size)
            listOf(key, authorized, wrongReceiver, wrongPair).forEach { it.fill(0) }
        } finally {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            aliases.forEach { ks.deleteEntry(it); ks.deleteEntry("${it}_wrapping_v1"); android.util.AtomicFile(File(context.noBackupFilesDir, "$it.wrapped-v1")).delete() }
        }
    }
    @Test fun settingsMigrateWithoutPlaintextAndRejectFieldSubstitution() {
        val name = "secure-test-${UUID.randomUUID()}"
        val old = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        old.edit().putString("secret", "sensitive-test-value").putLong("time", 42L).putBoolean("enabled", true).commit()
        val secured = SecurePreferences.get(context, name)
        assertEquals("sensitive-test-value", secured.getString("secret", null))
        assertEquals(42L, secured.getLong("time", 0L)); assertTrue(secured.getBoolean("enabled", false))
        assertTrue(old.all.isEmpty())
        val disk = context.getSharedPreferences("$name.secure-v1", Context.MODE_PRIVATE)
        assertFalse(disk.all.toString().contains("sensitive-test-value"))
        secured.edit().putInt("i", 7).putFloat("f", 1.5f).putStringSet("ss", mutableSetOf("one", "two")).commit()
        assertEquals(7, secured.getInt("i", 0)); assertEquals(1.5f, secured.getFloat("f", 0f))
        assertEquals(setOf("one", "two"), secured.getStringSet("ss", null))
        disk.edit().putString("substituted", disk.getString("secret", null)).commit()
        rejected { secured.getString("substituted", null) }
        secured.edit().clear().commit(); assertTrue(secured.all.isEmpty())
    }
    @Test fun localCiphertextIsBoundToItsPurpose() {
        val sealed = LocalVault.seal("private".toByteArray(), "field-A")
        assertEquals("private", LocalVault.open(sealed, "field-A").toString(Charsets.UTF_8))
        rejected { LocalVault.open(sealed, "field-B") }
    }
}
