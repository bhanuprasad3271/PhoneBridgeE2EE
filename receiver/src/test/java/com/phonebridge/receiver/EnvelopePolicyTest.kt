package com.phonebridge.receiver

import com.phonebridge.shared.EnvelopePolicy
import org.junit.Assert.*
import org.junit.Test

class EnvelopePolicyTest {
    private val valid = mapOf<String, Any>("id" to "record-1", "pairId" to "123456", "senderUid" to "sender-A", "aadVersion" to 1L,
        "type" to "message", "ciphertextB64" to "A".repeat(44), "nonceB64" to "A".repeat(16))
    @Test fun validEnvelopeAccepted() { EnvelopePolicy.validate("123456", "record-1", "sender-A", valid) }
    @Test fun forgedRoutingAndVersionsRejected() {
        for ((key, value) in mapOf("id" to "other", "pairId" to "654321", "senderUid" to "sender-B", "aadVersion" to 2L,
            "type" to "unknown", "ciphertextB64" to "short", "nonceB64" to "bad")) {
            try { EnvelopePolicy.validate("123456", "record-1", "sender-A", valid + (key to value)); fail("Accepted $key") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun receiverNeedsBothAuthIdentityAndPinnedKey() {
        val pair = mapOf<String, Any>("receiverUid" to "receiver-A", "receiverPublicKeyB64" to "key-A")
        assertTrue(EnvelopePolicy.receiverOwns(pair, "receiver-A", "key-A"))
        assertFalse(EnvelopePolicy.receiverOwns(pair, "receiver-B", "key-A"))
        assertFalse(EnvelopePolicy.receiverOwns(pair, "receiver-A", "key-B"))
        assertFalse(EnvelopePolicy.receiverOwns(pair, null, "key-A"))
    }
}
