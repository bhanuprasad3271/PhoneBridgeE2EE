package com.phonebridge.shared

data class PairBundle(val pairId: String, val receiverPublicKeyB64: String) {
    fun encode(): String = "$pairId.$receiverPublicKeyB64"
    companion object {
        fun decode(s: String): PairBundle {
            val i = s.indexOf('.')
            require(i > 0) { "Invalid pairing bundle" }
            return PairBundle(s.substring(0, i), s.substring(i + 1))
        }
    }
}

data class PlainRecord(val type: String, val timestamp: Long, val source: String = "", val title: String = "", val body: String = "", val metadata: Map<String, String> = emptyMap())
