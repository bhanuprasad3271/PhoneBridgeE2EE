package com.phonebridge.shared

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Device-bound at-rest encryption; losing the key never silently resets stored data. */
object LocalVault {
    private const val ALIAS = "phonebridge_local_vault_v1"
    @Synchronized private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Local encryption key unavailable; do not clear app data" }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun seal(value: ByteArray, context: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(true))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value), Base64.NO_WRAP)
    }
    fun open(value: String, context: String): ByteArray {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size >= 28) { "Invalid local encrypted value" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes, 12, bytes.size - 12)
    }
}
