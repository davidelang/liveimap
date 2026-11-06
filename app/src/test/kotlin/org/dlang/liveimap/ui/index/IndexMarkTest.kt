package org.dlang.liveimap.ui.index

import org.dlang.liveimap.window.IndexRecord
import org.dlang.liveimap.window.ScrollAnchor
import org.dlang.liveimap.window.afterNewMail
import org.dlang.liveimap.window.withOpenedMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IndexMarkTest {
    @Test
    fun openingWritesTheCursorOnly() {
        val anchor = ScrollAnchor(3L, 12)
        val opened = IndexRecord(setOf(1L), 5L, anchor).withOpenedMessage(8L)
        assertEquals(openedCursor(5L, 8L), opened.cursorUid)
        assertEquals(setOf(1L), opened.checked)
        assertEquals(anchor, opened.anchor)
    }

    @Test
    fun newMailLeavesTheCursor() {
        val index = IndexRecord(setOf(1L), 5L, ScrollAnchor(3L, 12))
        assertEquals(index, index.afterNewMail())
    }

    @Test
    fun aVisibleRowDoesNotScrollAndACollapsedThreadExpands() {
        assertEquals(MarkReveal.Shown, markReveal(4L, setOf(4L), setOf(4L), emptySet(), null))
        assertEquals(MarkReveal.Scroll, markReveal(4L, emptySet(), setOf(4L), emptySet(), null))
        assertEquals(MarkReveal.Load, markReveal(9L, emptySet(), setOf(4L), emptySet(), null))
        assertEquals(MarkReveal.Expand, markReveal(9L, emptySet(), setOf(4L), emptySet(), 4L))
        assertEquals(4L, collapsedRoot(mapOf(4L to listOf(9L)), 9L, emptySet()))
        assertNull(collapsedRoot(mapOf(4L to listOf(9L)), 9L, setOf(4L)))
    }

    @Test
    fun deleteMovesTheMarkedRowAndLeavesAnotherRow() {
        val shown = markedDelete(5L, setOf(5L), false, deleteMovesToNext = true, nextUid = 8L)
        val closed = markedDelete(5L, setOf(5L), false, deleteMovesToNext = false, nextUid = 8L)
        val other = markedDelete(5L, setOf(2L), false, deleteMovesToNext = true, nextUid = 8L)
        assertTrue(shown.moved)
        assertTrue(shown.showNext)
        assertFalse(shown.closeMessage)
        assertEquals(8L, shown.nextUid)
        assertTrue(closed.closeMessage)
        assertFalse(closed.showNext)
        assertEquals(8L, closed.nextUid)
        assertFalse(other.moved)
        assertEquals(5L, other.nextUid)
        val nowhere = markedDelete(5L, setOf(5L), false, deleteMovesToNext = true, nextUid = null)
        assertTrue(nowhere.closeMessage)
        assertFalse(nowhere.showNext)
    }

    @Test
    fun aHiddenRowDrawsNoMark() {
        assertFalse(rowMarked(5L, 5L, shown = false))
        assertTrue(rowMarked(5L, 5L, shown = true))
        assertFalse(rowMarked(5L, 6L, shown = true))
    }
}
