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
    fun testExtractCoordinatesUsesDirectionsDestination() {
        // Directions from Rome to Milan: the destination is the last waypoint, not the first
        val url = "https://www.google.com/maps/dir/Roma/Milano/@43.5,10.9,7z/data=!3m1!4b1!4m13!4m12" +
            "!1m5!1m1!1s0x132f6196f9928ebb:0xb90f770693656e38!2m2!1d12.4963655!2d41.9027835" +
            "!1m5!1m1!1s0x4786c1493f1275e7:0x3cffcd13c6740e8d!2m2!1d9.1899820!2d45.4642035"
        assertEquals("45.4642035,9.1899820", MapsUtils.extractCoordinates(url))
    }

    @Test
    fun testGetGenericMapsUri() {
        // Test when it should wrap in geo:
        val input = "google.com/maps/place/Rome"
        val expected = "geo:0,0?q=google.com%2Fmaps%2Fplace%2FRome"
        assertEquals(expected, MapsUtils.getGenericMapsUri(input))
    }
}
