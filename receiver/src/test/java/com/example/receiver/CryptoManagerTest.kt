package com.example.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoManagerTest {

    @Test
    fun testLooksEncrypted() {
        // Base64 of IV + ciphertext + tag, as produced by CryptoManager.encrypt()
        assertTrue(CryptoManager.looksEncrypted("pIvefayyFSh0ZP/S3jkp6ph07qtzR3bHuc+Nsvt5p2n89BEcddthuiau2vbdl28M"))

        // Plain messages sent with encryption turned off in the Sender
        assertFalse(CryptoManager.looksEncrypted("""{"url":"https://maps.app.goo.gl/abc","title":"Colosseo"}"""))
        assertFalse(CryptoManager.looksEncrypted("https://www.google.com/maps/place/Colosseo"))
        assertFalse(CryptoManager.looksEncrypted("ciao"))
    }
}
