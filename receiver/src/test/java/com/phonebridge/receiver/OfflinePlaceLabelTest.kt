package com.phonebridge.receiver

import org.junit.Assert.*
import org.junit.Test

class OfflinePlaceLabelTest {
    @Test fun combinesAvailableAddressParts() {
        assertEquals("Cafe, 12 Main Road, Hyderabad, 500001", OfflinePlaceLabel.fromTags(mapOf(
            "name" to "Cafe", "addr:housenumber" to "12", "addr:street" to "Main Road",
            "addr:city" to "Hyderabad", "addr:postcode" to "500001")))
    }
    @Test fun missingAddressDoesNotInventOne() {
        assertNull(OfflinePlaceLabel.fromTags(emptyMap()))
        assertNull(OfflinePlaceLabel.fromTags(mapOf("addr:housenumber" to "12")))
        assertNull(OfflinePlaceLabel.fromTags(mapOf("name" to " ")))
    }
    @Test fun supportsNamedRoadsAndFullAddresses() {
        assertEquals("Main Road", OfflinePlaceLabel.fromTags(mapOf("name" to "Main Road", "highway" to "residential")))
        assertEquals("12 Main Road", OfflinePlaceLabel.fromTags(mapOf("addr:full" to "12 Main Road")))
    }
}
