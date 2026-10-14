package org.dlang.liveimap.ui.contacts

import org.junit.Assert.assertEquals
import org.junit.Test

class AlpineCrlfTest {
    private fun crlf(s: String) = String(crlfLines(s.toByteArray()))

    @Test
    fun bareNewlinesBecomeCrlf() {
        assertEquals("Subject: x\r\n\r\na\tb\r\nc\r\n", crlf("Subject: x\n\na\tb\nc\n"))
    }

    @Test
    fun existingCrlfAndLoneCrAreKept() {
        assertEquals("a\r\nb\r\n", crlf("a\r\nb\n"))
        assertEquals("\r\nx\ry", crlf("\nx\ry"))
        assertEquals("", crlf(""))
    }
}
