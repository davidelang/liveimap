package org.dlang.liveimap.engine

import kotlinx.coroutines.CancellationException
import org.dlang.liveimap.ui.reader.WireTextDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WireTextTest {
    @Test fun closeTwiceFreesOnce() {
        val decoder = WireTextDecoder("utf-8", "7bit", "UTF-8")
        decoder.close()
        decoder.close()
    }

    @Test fun cancelledUseCloses() {
        val decoder = WireTextDecoder("utf-8", "7bit", "UTF-8")
        try {
            decoder.use { throw CancellationException("stop") }
            fail("use returned")
        } catch (error: CancellationException) {
            assertEquals("stop", error.message)
        }
        decoder.close()
    }
}

class WireTextPathsTest {
    private fun parsed(headers: String) =
        org.dlang.liveimap.ui.compose.parseRfc822("$headers\r\nSubject: x\r\n\r\nbody".toByteArray(), "ISO-8859-1")

    @Test fun saveByFromNameWithCrLfRefused() {
        val m = parsed("From: =?utf-8?q?a=0D=0AA9_DELETE_INBOX?= <=?utf-8?q?x=0D=0AA9_DELETE_INBOX?=@evil>")
        val from = org.dlang.liveimap.ui.compose.headerValues(m, "From").first()
        val name = org.dlang.liveimap.ui.reader.saveFolderName(
            org.dlang.liveimap.settings.SaveNameRule.ByFrom, "saved", "", from, "", "", "",
        )
        // F60: the derived name is reduced to [a-z0-9._-] before it gets anywhere near the wire.
        assertEquals("xa9deleteinbox", name)
        assertTrue(wireTextOk(name))
        // wire() still refuses CR/LF underneath.
        assertTrue(runCatching { requireWireText("x\r\nA9 DELETE INBOX") }.isFailure)
    }

    @Test fun listPostCrLfNeverReachesRcpt() {
        val m = parsed("List-Post: <mailto:list@x.example%0D%0ARCPT%20TO:%3Cspy@evil%3E>")
        // F59: rejected outright now (null), so nothing reaches RCPT TO
        assertEquals(null, org.dlang.liveimap.ui.compose.listPostAddress(m))
    }
}
