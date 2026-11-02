package org.dlang.liveimap.ui.reader

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllHeadersTest {
    private fun source(bytes: ByteArray): suspend (Int, Int) -> ByteArray = { offset, length ->
        bytes.copyOfRange(minOf(offset, bytes.size), minOf(offset + length, bytes.size))
    }

    @Test
    fun smallHeaderIsReadWhole() = runBlocking {
        val raw = "Subject: =?utf-8?q?Caf=C3=A9?=\r\nX-Evil: <script>alert(1)</script>\r\n\r\n"
        val (text, truncated) = readCappedText(RawViewCapBytes, source(raw.toByteArray()))
        assertEquals(raw, text)
        assertFalse(truncated)
        // Shown unparsed by default; decoding encoded-words is an option, and markup stays text either way.
        assertEquals(raw, headerViewText(text, decode = false, fallback = "ISO-8859-1"))
        assertTrue(headerViewText(text, decode = true, fallback = "ISO-8859-1").startsWith("Subject: Café"))
        assertTrue(headerViewText(text, decode = true, fallback = "ISO-8859-1").contains("<script>alert(1)</script>"))
    }
}
