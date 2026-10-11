package org.dlang.liveimap.engine

import org.dlang.liveimap.session.Capabilities
import org.dlang.liveimap.session.UidWindow
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class UidWindowTest {
    @Test
    fun countThenUids() {
        assertEquals(UidWindow(1200, listOf(9L, 7L, 3L)), uidWindowOf(longArrayOf(1200, 9, 7, 3)))
    }

    @Test
    fun missingCountFallsBackToWindowSize() {
        assertEquals(UidWindow(2, listOf(4L, 5L)), uidWindowOf(longArrayOf(-1, 4, 5)))
    }

    @Test
    fun emptyWindowKeepsTotal() {
        assertEquals(UidWindow(0, emptyList()), uidWindowOf(longArrayOf(0)))
        assertEquals(UidWindow(0, emptyList()), uidWindowOf(longArrayOf()))
    }

    @Test
    fun partialNeedsEsearchAndToken() {
        val base = "IMAP4rev1 SORT ESORT ESEARCH"
        assertTrue(Capabilities.parse("$base PARTIAL").partialWindows)
        assertFalse(Capabilities.parse(base).partialWindows)
        assertFalse(Capabilities.parse("IMAP4rev1 PARTIAL").partialWindows)
    }

    @Test
    fun countWithoutWindowMeansPartialIgnored() {
        assertTrue(partialWindowIgnored(UidWindow(60, emptyList()), 1))
        assertFalse(partialWindowIgnored(UidWindow(60, listOf(1L)), 1))
        assertFalse(partialWindowIgnored(UidWindow(3, emptyList()), 5))
    }

    @Test
    fun negativeRangesBecomePositive() {
        assertEquals(58 to 60, positiveRange(-3, -1, 60))
        assertEquals(58 to 60, positiveRange(-1, -3, 60))
        assertEquals(1 to 2, positiveRange(-5, -1, 2))
        assertEquals(null, positiveRange(-3, -1, 0))
    }
}
