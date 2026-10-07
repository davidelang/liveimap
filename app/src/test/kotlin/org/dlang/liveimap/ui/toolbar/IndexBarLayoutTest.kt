package org.dlang.liveimap.ui.toolbar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class IndexBarLayoutTest {
    @Test
    fun defaultLayoutAndTail() {
        val layout = defaultIndexBar()
        assertEquals(
            listOf(IndexBarAction.Refresh, IndexBarAction.Search, IndexBarAction.Filter),
            layout.toolbar,
        )
        assertEquals(emptyList<IndexBarAction>(), layout.overflow)
        assertEquals(emptyList<IndexBarAction>(), layout.hidden)
        assertEquals(
            listOf(IndexMenuEntry.Divider, IndexMenuEntry.Customize),
            indexMenuTail(layout),
        )
        assertEquals(layout, resetIndexBar())
    }

    @Test
    fun moveToOverflowAndHidden() {
        val overflow = moveIndexAction(defaultIndexBar(), IndexBarAction.Search, BarSection.Overflow)
        assertEquals(listOf(IndexBarAction.Refresh, IndexBarAction.Filter), overflow.toolbar)
        assertEquals(listOf(IndexBarAction.Search), overflow.overflow)
        assertEquals(emptyList<IndexBarAction>(), overflow.hidden)
        assertEquals(
            listOf(
                IndexMenuEntry.Divider,
                IndexMenuEntry.Action(IndexBarAction.Search),
                IndexMenuEntry.Divider,
                IndexMenuEntry.Customize,
            ),
            indexMenuTail(overflow),
        )
        val hidden = moveIndexAction(defaultIndexBar(), IndexBarAction.Search, BarSection.Hidden)
        assertFalse(hidden.toolbar.contains(IndexBarAction.Search))
        assertFalse(hidden.overflow.contains(IndexBarAction.Search))
        assertEquals(listOf(IndexBarAction.Search), hidden.hidden)
        assertEquals(
            listOf(IndexMenuEntry.Divider, IndexMenuEntry.Customize),
            indexMenuTail(hidden),
        )
        assertEquals(
            defaultIndexBar(),
            moveIndexAction(defaultIndexBar(), IndexBarAction.Search, BarSection.Toolbar),
        )
    }

    @Test
    fun moveByOneLeavesTheEnds() {
        val swapped = moveIndexActionBy(defaultIndexBar(), IndexBarAction.Search, 1)
        assertEquals(
            listOf(IndexBarAction.Refresh, IndexBarAction.Filter, IndexBarAction.Search),
            swapped.toolbar,
        )
        assertEquals(emptyList<IndexBarAction>(), swapped.overflow)
        assertEquals(emptyList<IndexBarAction>(), swapped.hidden)
        val stayed = moveIndexActionBy(defaultIndexBar(), IndexBarAction.Refresh, -1)
        assertEquals(defaultIndexBar(), stayed)
    }
}
