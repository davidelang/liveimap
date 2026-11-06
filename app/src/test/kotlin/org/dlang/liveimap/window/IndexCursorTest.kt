package org.dlang.liveimap.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class IndexCursorTest {
    private val anchor = ScrollAnchor(uid = 3L, offsetPx = 12)
    private val index = IndexRecord(checked = setOf(1L, 2L), cursorUid = 5L, anchor = anchor)

    @Test
    fun recordHasChecksCursorAndAnchor() {
        assertEquals(setOf(1L, 2L), index.checked)
        assertEquals(5L, index.cursorUid)
        assertEquals(anchor, index.anchor)
    }

    @Test
    fun openingAMessageSetsTheCursorOnly() {
        val opened = index.withOpenedMessage(8L)
        assertEquals(8L, opened.cursorUid)
        assertEquals(index.checked, opened.checked)
        assertEquals(index.anchor, opened.anchor)
    }

    @Test
    fun newMailLeavesAllThree() {
        val after = index.afterNewMail()
        assertEquals(index.checked, after.checked)
        assertEquals(index.cursorUid, after.cursorUid)
        assertEquals(index.anchor, after.anchor)
        assertSame(index, after)
    }

    @Test
    fun nextAndPreviousMoveTheCursorAndDoNotAnchorAtTheTop() {
        val top = ScrollAnchor(uid = 1L, offsetPx = 0)
        val next = index.next(9L)
        val previous = index.previous(4L)
        assertEquals(9L, next.index.cursorUid)
        assertEquals(4L, previous.index.cursorUid)
        assertEquals(RowReveal.BringOnScreen, next.reveal)
        assertEquals(RowReveal.BringOnScreen, previous.reveal)
        assertEquals(9L, next.revealUid)
        assertEquals(4L, previous.revealUid)
        assertEquals(anchor, next.index.anchor)
        assertEquals(anchor, previous.index.anchor)
        assertEquals(index.checked, next.index.checked)
        assertEquals(index.checked, previous.index.checked)
        assertFalseTop(next.index.anchor, top)
        assertFalseTop(previous.index.anchor, top)
    }

    @Test
    fun deleteMovesToNextShowsOrClosesAndBothMoveTheCursor() {
        val shown = index.afterDelete(deleteMovesToNext = true, nextUid = 8L)
        val closed = index.afterDelete(deleteMovesToNext = false, nextUid = 8L)
        assertEquals(MessageAfterDelete.ShowNext, shown.message)
        assertEquals(MessageAfterDelete.Close, closed.message)
        assertEquals(8L, shown.nextUid)
        assertEquals(shown.nextUid, closed.nextUid)
        assertEquals(shown.index.cursorUid, closed.index.cursorUid)
        assertEquals(8L, shown.index.cursorUid)
        assertEquals(index.checked, shown.index.checked)
        assertEquals(index.checked, closed.index.checked)
        assertEquals(index.anchor, shown.index.anchor)
        assertEquals(index.anchor, closed.index.anchor)
    }

    private fun assertFalseTop(anchor: ScrollAnchor?, top: ScrollAnchor) {
        assertEquals(false, anchor == top)
    }
}
