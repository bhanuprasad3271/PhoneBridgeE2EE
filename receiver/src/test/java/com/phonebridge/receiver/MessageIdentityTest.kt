package com.phonebridge.receiver

import com.phonebridge.shared.MessageIdentity
import org.junit.Assert.*
import org.junit.Test

class MessageIdentityTest {
    @Test fun repeatDeliveryHasTheSameIdentity() {
        assertEquals(MessageIdentity.of("app", "chat", "Alice", "Hello", 123L),
            MessageIdentity.of("app", "chat", "Alice", "Hello", 123L))
    }
    @Test fun intentionallyRepeatedTextAtDifferentTimesRemainsSeparate() {
        assertNotEquals(MessageIdentity.of("app", "chat", "Alice", "Hello", 123L),
            MessageIdentity.of("app", "chat", "Alice", "Hello", 124L))
    }
    @Test fun separateChatsAndSendersDoNotMerge() {
        val key = MessageIdentity.of("app", "chat", "Alice", "Hello", 123L)
        assertNotEquals(key, MessageIdentity.of("app", "other chat", "Alice", "Hello", 123L))
        assertNotEquals(key, MessageIdentity.of("app", "chat", "Bob", "Hello", 123L))
        assertNotEquals(key, MessageIdentity.of("other app", "chat", "Alice", "Hello", 123L))
        assertNotEquals(key, MessageIdentity.of("app", "chat", "Alice", "Edited", 123L))
    }
    @Test fun fieldSeparatorsCannotProduceTheSameHash() {
        assertNotEquals(MessageIdentity.hash("a|b", "c"), MessageIdentity.hash("a", "b|c"))
    }
}
