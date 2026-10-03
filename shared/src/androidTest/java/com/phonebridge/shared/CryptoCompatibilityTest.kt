package com.phonebridge.shared

import android.os.Build
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CryptoCompatibilityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun withAliases(test: (String, String) -> Unit) {
        val a = "test_sender_${UUID.randomUUID()}"
        val b = "test_receiver_${UUID.randomUUID()}"
        try { test(a, b) } finally {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            for (alias in listOf(a, b)) {
                store.deleteEntry(alias)
                store.deleteEntry("${alias}_wrapping_v1")
                android.util.AtomicFile(File(context.noBackupFilesDir, "$alias.wrapped-v1")).delete()
            }
        }
    }

    @Test fun identitiesSurviveRecreationAndExchangeEncryptedMessages() = withAliases { a, b ->
        val sender = CryptoBox(context, a)
        val receiver = CryptoBox(context, b)
        val senderPublic = sender.publicKeyB64()
        val receiverPublic = receiver.publicKeyB64()
        assertEquals(senderPublic, CryptoBox(context, a).publicKeyB64())
        val senderKey = sender.deriveAesKey(receiverPublic, "123456")
        val receiverKey = receiver.deriveAesKey(senderPublic, "123456")
        assertArrayEquals(senderKey, receiverKey)
        val aad = "123456|test|message|1".toByteArray()
        val (ciphertext, nonce) = sender.encrypt(senderKey, "Android compatibility".toByteArray(), aad)
        assertEquals("Android compatibility", String(receiver.decrypt(receiverKey, ciphertext, nonce, aad)))
        try {
            receiver.decrypt(receiverKey, ciphertext, nonce, "wrong aad".toByteArray())
            fail("Altered metadata must fail authentication")
        } catch (_: java.security.GeneralSecurityException) { }
        if (Build.VERSION.SDK_INT < 31) {
            assertTrue(File(context.noBackupFilesDir, "$a.wrapped-v1").exists())
            assertFalse(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(a))
        }
    }

    @Test fun interoperatesWithStandardP256Peer() = withAliases { a, _ ->
        val peer = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val local = CryptoBox(context, a)
        val localPair = local.ensureKeyPair()
        val left = javax.crypto.KeyAgreement.getInstance("ECDH").apply {
            init(localPair.private); doPhase(peer.public, true)
        }.generateSecret()
        val right = javax.crypto.KeyAgreement.getInstance("ECDH").apply {
            init(peer.private); doPhase(localPair.public, true)
        }.generateSecret()
        assertArrayEquals(left, right)
    }

    @Test fun corruptedStoredIdentityIsRejectedRatherThanReplaced() = withAliases { a, _ ->
        if (Build.VERSION.SDK_INT >= 31) return@withAliases
        CryptoBox(context, a).publicKeyB64()
        val file = File(context.noBackupFilesDir, "$a.wrapped-v1")
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        try {
            CryptoBox(context, a).publicKeyB64()
            fail("Corrupt keys must not silently reset pairing")
        } catch (_: java.security.GeneralSecurityException) { }
        assertArrayEquals(bytes, file.readBytes())
    }
}
