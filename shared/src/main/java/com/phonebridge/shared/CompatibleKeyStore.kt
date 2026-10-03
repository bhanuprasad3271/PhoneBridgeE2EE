package com.phonebridge.shared

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** ECDH is Keystore-backed on API 31+. Older devices wrap an EC key with Keystore AES. */
internal class CompatibleKeyStore(context: Context, private val alias: String) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "$alias.wrapped-v1"))
    private val wrappingAlias = "${alias}_wrapping_v1"
    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun getOrCreate(): KeyPair = synchronized(lock) {
        val store = keyStore()
        // Always retain the old identity, including after an Android OS upgrade.
        if (file.baseFile.exists() || File(file.baseFile.path + ".bak").exists()) {
            return@synchronized readWrapped(store)
        }
        if (store.containsAlias(alias)) {
            return@synchronized KeyPair(store.getCertificate(alias).publicKey,
                store.getKey(alias, null) as PrivateKey)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_AGREE_KEY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build())
            return@synchronized generator.generateKeyPair()
        }
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val wrappingKey = if (store.containsAlias(wrappingAlias)) {
            store.getKey(wrappingAlias, null) as SecretKey
        } else {
            KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(wrappingAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
        }
        val privateBytes = pair.private.encoded
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
            val publicBytes = pair.public.encoded
            cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
            cipher.updateAAD(publicBytes)
            val ciphertext = cipher.doFinal(privateBytes)
            val bytes = ByteArrayOutputStream().also { buffer ->
                DataOutputStream(buffer).use { out ->
                    out.writeInt(1)
                    for (part in listOf(publicBytes, cipher.iv, ciphertext)) {
                        out.writeInt(part.size)
                        out.write(part)
                    }
                }
            }.toByteArray()
            val output = file.startWrite()
            try {
                output.write(bytes)
                file.finishWrite(output)
            } catch (e: Exception) {
                file.failWrite(output)
                throw e
            }
        } finally {
            privateBytes.fill(0)
        }
        pair
    }

    private fun readWrapped(store: KeyStore): KeyPair {
        // Missing/corrupt storage must fail visibly, never silently replace a paired identity.
        val key = store.getKey(wrappingAlias, null) as? SecretKey
            ?: error("Pairing key is unavailable; restore access before pairing again")
        DataInputStream(ByteArrayInputStream(file.readFully())).use { input ->
            require(input.readInt() == 1) { "Unsupported pairing key format" }
            fun part(): ByteArray {
                val size = input.readInt()
                require(size in 1..4096) { "Invalid pairing key data" }
                return ByteArray(size).also { input.readFully(it) }
            }
            val publicBytes = part()
            val iv = part()
            val ciphertext = part()
            require(iv.size == 12 && input.available() == 0) { "Invalid pairing key data" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(alias.toByteArray(Charsets.UTF_8))
            cipher.updateAAD(publicBytes)
            val privateBytes = cipher.doFinal(ciphertext)
            try {
                val factory = KeyFactory.getInstance("EC")
                return KeyPair(factory.generatePublic(X509EncodedKeySpec(publicBytes)),
                    factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes)))
            } finally {
                privateBytes.fill(0)
            }
        }
    }

    companion object { private val lock = Any() }
}
