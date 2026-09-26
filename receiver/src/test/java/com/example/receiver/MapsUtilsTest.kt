package com.example.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapsUtilsTest {

    @Test
    fun testIsGoogleMapsLink() {
        // Simple containment checks as per implementation
        assertTrue("Contains google. and /maps/", MapsUtils.isGoogleMapsLink("https://www.google.com/maps/place/Colosseo/"))
        assertTrue("Contains goo.gl", MapsUtils.isGoogleMapsLink("https://goo.gl/maps/abcde"))
    }

    @Test
    fun testIsMapLink() {
        assertTrue("Google Maps link", MapsUtils.isMapLink("https://www.google.com/maps/place/Colosseo/"))
        assertTrue("Waze link", MapsUtils.isMapLink("https://www.waze.com/ul?ll=41.89,12.49"))
        // Test false positive
        assertFalse("Generic Google Search", MapsUtils.isMapLink("https://www.google.com/search?q=pizza"))
    }

    @Test
    fun testExtractCoordinates() {
        // Test AT_COORDS regex: @([-+]?\\d+\\.\\d+),([-+]?\\d+\\.\\d+)
        val url = "https://www.google.com/maps/@41.8902,12.4922,15z"
        assertEquals("41.8902,12.4922", MapsUtils.extractCoordinates(url))
        
        // Test GENERIC_COORDS regex: ([-+]?\\d+\\.\\d+)\\s*,\\s*([-+]?\\d+\\.\\d+)
        assertEquals("41.89,12.49", MapsUtils.extractCoordinates("41.89, 12.49"))
    }

    @Test
    fun testGetGenericMapsUri() {
        // Test when it should wrap in geo:
        val input = "google.com/maps/place/Rome"
        val expected = "geo:0,0?q=google.com%2Fmaps%2Fplace%2FRome"
        assertEquals(expected, MapsUtils.getGenericMapsUri(input))
    }
}
