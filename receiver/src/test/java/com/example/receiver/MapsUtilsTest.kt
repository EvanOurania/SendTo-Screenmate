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
    fun testWazeUriForRealDirectionsLink() {
        // Where a real maps.app.goo.gl route link redirects to: 4 stops, the last one is the destination
        val url = "https://www.google.com/maps/dir/Centro+%E0%B8%AD%E0%B9%88%E0%B8%AD%E0%B8%99%E0%B8%99%E0%B8%B8%E0%B8%8A/" +
            "ANAPANA+-+Lad+Krabang/%E0%B8%AA%E0%B8%A3%E0%B8%B2%E0%B8%8D%E0%B8%AA%E0%B8%B4%E0%B8%A3%E0%B8%B4/" +
            "%E0%B8%9A%E0%B8%A3%E0%B8%B4%E0%B8%97%E0%B8%B2%E0%B9%80%E0%B8%99%E0%B8%B5%E0%B8%A2/" +
            "@13.6860887,100.731166,12z/data=!4m26!4m25" +
            "!1m5!1m1!1s0x311d67ac86235af1:0x8410ef05f53686d9!2m2!1d100.7392196!2d13.723162" +
            "!1m5!1m1!1s0x311d67835fe657d5:0xcb0331728d125c3b!2m2!1d100.7930087!2d13.7204914" +
            "!1m5!1m1!1s0x311d6778a9e16419:0xcfb638412001d639!2m2!1d100.795794!2d13.7008666" +
            "!1m5!1m1!1s0x311d5b87b96c8fe9:0xe3809f382f1c3e37!2m2!1d100.786181!2d13.6522024" +
            "!3e0?authuser=0&coh=198943&entry=tts"
        assertEquals("waze://?ll=13.6522024,100.786181&navigate=yes", MapsUtils.getWazeUri(url, "Centro"))
    }

    @Test
    fun testWazeUriForDirectionsFromCurrentLocation() {
        // Empty start = current location, which has no coordinates in the route details
        val url = "https://www.google.com/maps/dir//Colosseo,+Roma/@41.89,12.49,15z/data=!4m8!4m7!1m0" +
            "!1m5!1m1!1s0x132f61b6532013ad:0x28f1c82e908503c4!2m2!1d12.4922309!2d41.8902102"
        assertEquals("waze://?ll=41.8902102,12.4922309&navigate=yes", MapsUtils.getWazeUri(url, ""))
    }

    @Test
    fun testWazeUriForDirectionsWithoutCoordinates() {
        // Without route details, search the destination instead of using the map center (@...)
        val url = "https://www.google.com/maps/dir/Roma/Milano,+MI/@43.5,10.9,7z"
        assertEquals("waze://?q=Milano%2C+MI&navigate=yes", MapsUtils.getWazeUri(url, "Roma"))
    }

    @Test
    fun testWazeUriForDirectionsApiLinks() {
        assertEquals(
            "waze://?ll=45.4642,9.19&navigate=yes",
            MapsUtils.getWazeUri("https://www.google.com/maps/dir/?api=1&origin=Roma&destination=45.4642,+9.19", "")
        )
        assertEquals(
            "waze://?q=Milano&navigate=yes",
            MapsUtils.getWazeUri("https://maps.google.com/maps?saddr=Roma&daddr=Milano", "")
        )
    }

    @Test
    fun testWazeUriForPlaceIsUnchanged() {
        val url = "https://www.google.com/maps/place/Colosseo/@41.8902102,12.4922309,17z/data=!3m1!4b1!4m6!3m5" +
            "!1s0x132f61b6532013ad:0x28f1c82e908503c4!8m2!3d41.8902102!4d12.4922309"
        assertEquals("waze://?ll=41.8902102,12.4922309&navigate=yes&q=Colosseo", MapsUtils.getWazeUri(url, ""))
    }

    @Test
    fun testMapsUriForDirectionsWithoutCoordinates() {
        // Without route details, search the destination instead of using the map center (@...)
        val url = "https://www.google.com/maps/dir/Roma/Milano,+MI/@43.5,10.9,7z"
        assertEquals("geo:0,0?q=Milano%2C+MI", MapsUtils.getMapsUri(url))
    }

    @Test
    fun testMapsUriForDirectionsWithCoordinates() {
        val url = "https://www.google.com/maps/dir/Roma/Milano/@43.5,10.9,7z/data=!3m1!4b1!4m13!4m12" +
            "!1m5!1m1!1s0x132f6196f9928ebb:0xb90f770693656e38!2m2!1d12.4963655!2d41.9027835" +
            "!1m5!1m1!1s0x4786c1493f1275e7:0x3cffcd13c6740e8d!2m2!1d9.1899820!2d45.4642035"
        assertEquals("geo:45.4642035,9.1899820?q=45.4642035,9.1899820", MapsUtils.getMapsUri(url))
    }

    @Test
    fun testMapsUriForPlaceAndShortLinkIsUnchanged() {
        val url = "https://www.google.com/maps/place/Colosseo/@41.8902102,12.4922309,17z/data=!3m1!4b1!4m6!3m5" +
            "!1s0x132f61b6532013ad:0x28f1c82e908503c4!8m2!3d41.8902102!4d12.4922309"
        assertEquals("geo:41.8902102,12.4922309?q=41.8902102,12.4922309", MapsUtils.getMapsUri(url))
        assertEquals("https://maps.app.goo.gl/abc123", MapsUtils.getMapsUri("https://maps.app.goo.gl/abc123"))
    }

    @Test
    fun testIsDroppedPinTitle() {
        assertTrue(MapsUtils.isDroppedPinTitle("Dropped pin"))
        assertTrue(MapsUtils.isDroppedPinTitle("Segnaposto"))
        assertTrue(MapsUtils.isDroppedPinTitle("Repère placé"))
        assertFalse(MapsUtils.isDroppedPinTitle("Pinerolo"))
        assertFalse(MapsUtils.isDroppedPinTitle("Pizzeria Alpina"))
    }

    @Test
    fun testGetGenericMapsUri() {
        // Test when it should wrap in geo:
        val input = "google.com/maps/place/Rome"
        val expected = "geo:0,0?q=google.com%2Fmaps%2Fplace%2FRome"
        assertEquals(expected, MapsUtils.getGenericMapsUri(input))
    }
}
