package com.phonebridge.shared

/** Reject malformed/cross-pair envelopes before deriving a key or acknowledging a record. */
object EnvelopePolicy {
    val types = setOf("message", "call", "notification", "location", "wp_call", "alert", "status")
    fun validate(pairId: String, docId: String, senderUid: String, data: Map<String, Any>) {
        require(data["id"] == docId && data["pairId"] == pairId) { "Envelope identity mismatch" }
        require(senderUid.isNotBlank() && data["senderUid"] == senderUid) { "Sender identity mismatch" }
        require((data["aadVersion"] as? Number)?.toLong() == 1L) { "Unsupported envelope version" }
        require(data["type"] in types) { "Unsupported record type" }
        require(docId.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid record ID" }
        require(((data["ciphertextB64"] as? String)?.length ?: 0) in 24..786432) { "Invalid ciphertext size" }
        require((data["nonceB64"] as? String)?.length == 16) { "Invalid nonce" }
    }
    fun receiverOwns(pair: Map<String, Any>, uid: String?, publicKey: String): Boolean =
        !uid.isNullOrBlank() && pair["receiverUid"] == uid && pair["receiverPublicKeyB64"] == publicKey
}
