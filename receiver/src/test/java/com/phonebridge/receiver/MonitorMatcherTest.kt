package com.phonebridge.receiver

import org.junit.Assert.*
import org.junit.Test

class MonitorMatcherTest {
    private fun identity(kind: String, value: String, partial: Boolean = false) =
        MonitorIdentity("test", "Test", kind, value, partial)

    @Test fun indianNumberFormatsMatchButSuffixesDoNot() {
        val target = identity("Phone", "9876543210")
        for (value in listOf("+91 98765 43210", "09876543210", "9876543210")) {
            assertTrue(value, MonitorMatcher.matches(target, "phone", listOf("Call from $value")))
        }
        assertFalse(MonitorMatcher.matches(target, "phone", listOf("19876543210")))
    }

    @Test fun usernameMatchingIsScopedAndHasBoundaries() {
        val target = identity("Instagram", "@Bhanu")
        assertTrue(MonitorMatcher.matches(target, "com.instagram.android", listOf("@BHANU sent a message")))
        assertFalse(MonitorMatcher.matches(target, "com.snapchat.android", listOf("@bhanu")))
        assertFalse(MonitorMatcher.matches(target, "com.instagram.android", listOf("@bhanu_123")))
        assertFalse(MonitorMatcher.matches(target.copy(enabled = false), "com.instagram.android", listOf("@bhanu")))
        assertTrue(MonitorMatcher.matches(target.copy(partial = true), "com.instagram.android", listOf("@bhanu_123")))
    }
}
