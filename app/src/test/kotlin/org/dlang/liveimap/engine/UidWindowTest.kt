package org.dlang.liveimap.engine

import org.dlang.liveimap.session.Capabilities
import org.dlang.liveimap.session.UidWindow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
