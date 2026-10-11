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
        assertEquals(raw, headerViewText(text, decode = false))
        assertTrue(headerViewText(text, decode = true).startsWith("Subject: Café"))
        assertTrue(headerViewText(text, decode = true).contains("<script>alert(1)</script>"))
    }

    @Test
    fun hugeHeaderStopsAtTheCap() = runBlocking {
        val huge = ByteArray(RawViewCapBytes * 3) { 'A'.code.toByte() }
        var fetched = 0
        val (text, truncated) = readCappedText(RawViewCapBytes) { o, l -> source(huge)(o, l).also { fetched += it.size } }
        assertEquals(RawViewCapBytes, text.length)
        assertTrue(truncated)
        assertEquals(RawViewCapBytes + 1, fetched)
    }

    @Test
    fun exactlyCapIsNotTruncated() = runBlocking {
        val exact = ByteArray(8192) { 'B'.code.toByte() }
        assertFalse(readCappedText(8192, source(exact)).second)
    }
}
