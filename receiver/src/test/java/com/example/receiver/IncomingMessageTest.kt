package com.example.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingMessageTest {

    @Test
    fun testParseSenderJson() {
        val message = IncomingMessage.parse("""{"url":"https://maps.app.goo.gl/abc123","title":"Colosseo"}""")!!
        assertEquals("https://maps.app.goo.gl/abc123", message.targetUrl)
        assertEquals("Colosseo", message.displayTitle)
        assertEquals("Colosseo", message.refinedTitle)
        assertTrue(message.isMapsLink)
        assertTrue(message.isLocation)
    }

    @Test
    fun testGenericTitleIsReplacedByPlaceName() {
        val message = IncomingMessage.parse(
            """{"url":"https://www.google.com/maps/place/Colosseo/@41.89,12.49,17z","title":"Location"}"""
        )!!
        assertEquals("Colosseo", message.refinedTitle)
    }

    @Test
    fun testParsePlainLinkAndGeoLink() {
        val web = IncomingMessage.parse("https://www.corriere.it")!!
        assertEquals("https://www.corriere.it", web.targetUrl)
        assertEquals("", web.displayTitle)
        assertFalse(web.isLocation)

        assertTrue(IncomingMessage.parse("geo:0,0?q=Via+Roma+1,+Milano")!!.isLocation)
    }

    @Test
    fun testParseEmptyLink() {
        assertNull(IncomingMessage.parse("""{"url":"","title":"Colosseo"}"""))
    }

    @Test
    fun testOpenAction() {
        val location = IncomingMessage.parse("""{"url":"https://maps.app.goo.gl/abc123","title":"Colosseo"}""")!!
        assertEquals(OpenAction.NAVIGATOR, location.openAction(0, ReceiverRepository.APP_WAZE))
        assertEquals(OpenAction.CHOOSER, location.openAction(5, ReceiverRepository.APP_WAZE))
        assertEquals(OpenAction.CHOOSER, location.openAction(0, ReceiverRepository.APP_NONE))

        // Web pages open in the browser even with delay 0 and a navigator chosen
        val web = IncomingMessage.parse("""{"url":"https://www.corriere.it","title":"Link"}""")!!
        assertEquals(OpenAction.DEFAULT_APP, web.openAction(0, ReceiverRepository.APP_WAZE))
    }

    @Test
    fun testIsRecent() {
        val sentAt = 1_000_000L
        assertTrue(IncomingMessage.isRecent(sentAt, sentAt + 30 * 60, 30 * 60))
        assertFalse(IncomingMessage.isRecent(sentAt, sentAt + 30 * 60 + 1, 30 * 60))
        assertTrue(IncomingMessage.isRecent(0, sentAt, 30 * 60)) // Unknown time: treat as recent
    }
}
