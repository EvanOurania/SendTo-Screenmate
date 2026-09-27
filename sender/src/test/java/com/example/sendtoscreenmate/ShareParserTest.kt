package com.example.sendtoscreenmate

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareParserTest {

    @Test
    fun testGoogleMapsPlaceShare() {
        val shared = "Colosseo\nPiazza del Colosseo, 1, 00184 Roma RM\nhttps://maps.app.goo.gl/abc123"
        assertEquals(
            PreparedMessage("https://maps.app.goo.gl/abc123", "Colosseo"),
            ShareParser.prepareSharedText(shared)
        )
    }

    @Test
    fun testTitleSeparators() {
        assertEquals("Colosseo", ShareParser.prepareSharedText("Colosseo · Roma https://maps.app.goo.gl/abc").title)
        assertEquals("Colosseo", ShareParser.prepareSharedText("Colosseo - Roma https://maps.app.goo.gl/abc").title)
    }

    @Test
    fun testSubjectWins() {
        assertEquals(
            PreparedMessage("https://example.com/page", "Page title"),
            ShareParser.prepareSharedText("Look at this https://example.com/page", subject = "Page title")
        )
    }

    @Test
    fun testGenericTitles() {
        assertEquals("Link", ShareParser.prepareSharedText("https://example.com").title)
        assertEquals("Location", ShareParser.prepareSharedText("https://maps.app.goo.gl/abc").title)
        assertEquals(
            PreparedMessage("Buy milk", "Text Message"),
            ShareParser.prepareSharedText("Buy milk")
        )
    }

    @Test
    fun testGeoLinks() {
        assertEquals("Colosseo", ShareParser.prepareSharedText("geo:0,0?q=41.89,12.49(Colosseo)").title)
        assertEquals("Via Roma 1, Milano", ShareParser.extractGeoLabel("geo:0,0?q=Via%20Roma%201%2C%20Milano"))
        assertEquals(PreparedMessage("geo:41.89,12.49", "Position"), ShareParser.prepareGeoLink("geo:41.89,12.49"))
    }
}
