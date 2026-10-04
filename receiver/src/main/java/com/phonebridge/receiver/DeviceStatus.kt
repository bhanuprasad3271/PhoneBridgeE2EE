package com.phonebridge.receiver

import org.json.JSONObject

data class DeviceStatus(val edition: String = "Unknown", val notifications: String = "Unknown",
    val calls: String = "Unknown", val location: String = "Unknown", val lastConnected: Long = 0,
    val version: String = "Unknown", val build: String = "Unknown") {
    companion object {
        val permissionKinds = setOf("call_permission", "notification_access", "location_sharing")
        fun metadata(row: RecordEntity): JSONObject = try { JSONObject(row.metadataJson) } catch (_: Exception) { JSONObject() }
        fun isWarning(row: RecordEntity): Boolean = row.type == "alert" && metadata(row).optString("severity", "warning") == "warning"
        fun compareState(a: RecordEntity, b: RecordEntity): Int {
            val am = metadata(a); val bm = metadata(b)
            val ar = am.optLong("senderStateRevision", 0L)
            val br = bm.optLong("senderStateRevision", 0L)
            if (ar != br) return ar.compareTo(br)
            return am.optLong("updatedAtMs", a.timestamp).compareTo(bm.optLong("updatedAtMs", b.timestamp))
        }
        private fun states(rows: List<RecordEntity>) = rows.filter { it.type in setOf("status", "alert") }
            .sortedWith(Comparator { a, b -> compareState(b, a) })
        fun warnings(rows: List<RecordEntity>): List<RecordEntity> {
            val sorted = states(rows)
            val newestStatus = sorted.firstOrNull { it.type == "status" && metadata(it).has("senderEdition") }
            val status = newestStatus?.let { metadata(it) }
            val seen = mutableSetOf<String>()
            return sorted.filter { row ->
                if (row.type != "alert") return@filter false
                val kind = metadata(row).optString("kind")
                if (kind in permissionKinds && !seen.add(kind)) return@filter false
                if (!isWarning(row)) return@filter false
                val key = when (kind) { "call_permission" -> "callSync"; "notification_access" -> "notificationAccess"; "location_sharing" -> "locationSharing"; else -> null }
                if (key != null && newestStatus != null && compareState(newestStatus, row) >= 0 && status?.has(key) == true) {
                    val current = status?.optString(key).orEmpty()
                    if (kind == "location_sharing") current == "needs_permission" else current == "disabled"
                } else true
            }
        }
        fun from(rows: List<RecordEntity>): DeviceStatus {
            val sorted = states(rows)
            fun field(key: String, kind: String? = null): String? {
                for (row in sorted) {
                    val m = metadata(row)
                    if (row.type == "status" && m.has(key)) return m.optString(key)
                    if (kind != null && row.type in setOf("alert", "status") && m.optString("kind") == kind) {
                        if (m.has("state")) return m.optString("state")
                        return if (m.optString("severity") == "resolved") "enabled" else "disabled"
                    }
                }
                return null
            }
            fun label(value: String?): String = when (value) {
                "enabled" -> "Enabled"; "disabled", "off" -> "Disabled"
                "needs_permission" -> "Permission needed"; "unsupported" -> "Unavailable in Lite"
                else -> "Unknown"
            }
            val version = field("appVersion") ?: "Unknown"
            val location = field("locationSharing", "location_sharing")
            val edition = field("senderEdition") ?: when {
                version.endsWith("-lite") || location == "unsupported" -> "lite"
                version.endsWith("-full") -> "full"
                else -> null
            }
            // Historical releases used the documented 2000 + workflow run number.
            val legacyBuild = Regex("^0\\.1\\.(2[0-9]{3})(?:-(?:full|lite))?$").matchEntire(version)
                ?.groupValues?.get(1)?.toInt()?.minus(2000)?.takeIf { it > 0 }?.toString()
            val build = field("githubBuild")?.takeIf { it.toIntOrNull()?.let { n -> n > 0 } == true } ?: legacyBuild
            return DeviceStatus(when (edition) { "lite" -> "Lite"; "full" -> "Full"; else -> "Unknown" },
                label(field("notificationAccess", "notification_access")), label(field("callSync", "call_permission")),
                if (edition == "lite") "Unavailable in Lite" else label(location),
                rows.maxOfOrNull { metadata(it).optLong("receiverReceivedAtMs", it.timestamp) } ?: 0,
                version, build?.let { "#$it" } ?: "Unknown")
        }
    }
}
