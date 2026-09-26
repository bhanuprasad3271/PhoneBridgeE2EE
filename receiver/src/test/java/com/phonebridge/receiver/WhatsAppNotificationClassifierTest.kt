package com.phonebridge.receiver

import com.phonebridge.shared.WhatsAppNotificationClassifier
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsAppNotificationClassifierTest {
    private fun background(
        title: String = "WhatsApp",
        body: String = "Checking for new messages",
        source: String = "com.whatsapp",
        conversation: String = "",
        structured: Boolean = false
    ) = WhatsAppNotificationClassifier.isBackgroundCheck(source, title, body, conversation, structured)

    @Test fun identifiesScreenshotStatusAndBusinessVariant() {
        assertTrue(background())
        assertTrue(background(source = "com.whatsapp.w4b", title = "WhatsApp Business"))
    }

    @Test fun handlesCaseWhitespaceAndTrailingEllipsis() {
        assertTrue(background(body = "  CHECKING\u00a0for  new messages…  "))
        assertTrue(background(body = "Checking for new messages..."))
    }

    @Test fun keepsContactsEvenWhenTheySendTheSameWords() {
        assertFalse(background(title = "Alice"))
        assertFalse(background(title = "Family group"))
    }

    @Test fun keepsStructuredMessagesAndNamedConversations() {
        assertFalse(background(structured = true))
        assertFalse(background(conversation = "Family"))
    }

    @Test fun keepsOrdinaryMessagesAndNotificationsWithSimilarWords() {
        assertFalse(background(body = "I am checking for new messages"))
        assertFalse(background(body = "Checking for new messages\nAlice: Hello"))
        assertFalse(background(body = "Hello"))
        assertFalse(background(body = "3 new messages"))
        assertFalse(background(body = "Incoming call"))
        assertFalse(background(body = ""))
    }

    @Test fun doesNotMatchOtherAppsOrLookalikePackages() {
        assertFalse(background(source = "com.example.whatsapp"))
        assertFalse(background(source = "org.telegram.messenger"))
        assertFalse(background(source = "sms"))
    }
}
