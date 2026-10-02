package com.phonebridge.shared

import java.security.MessageDigest

object MessageIdentity {
    fun hash(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun of(app: String, conversation: String, sender: String, body: String, timestamp: Long): String =
        "msg_v1_" + hash(app, conversation, sender, body, timestamp.toString())
}
