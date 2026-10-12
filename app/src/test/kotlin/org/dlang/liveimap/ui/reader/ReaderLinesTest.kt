package org.dlang.liveimap.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderLinesTest {
    @Test
    fun crlfLinesCarryNoCr() {
        assertEquals(listOf("a", "b", ""), readerLines("a\r\nb\r\n\r\n"))
        assertEquals(listOf("x\ry"), readerLines("x\ry\n"))  // a lone CR inside a line is kept
        assertEquals(listOf("one"), readerLines("one"))
    }
}
