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

    @Test
    fun defaultSelectionLayoutAndTail() {
        val layout = defaultSelectionBar()
        assertEquals(
            listOf(
                SelectionBarAction.Seen,
                SelectionBarAction.Flag,
                SelectionBarAction.Move,
                SelectionBarAction.Delete,
            ),
            layout.toolbar,
        )
        assertEquals(emptyList<SelectionBarAction>(), layout.overflow)
        assertEquals(emptyList<SelectionBarAction>(), layout.hidden)
        assertEquals(
            listOf(SelectionMenuEntry.Divider, SelectionMenuEntry.Customize),
            selectionMenuTail(layout),
        )
        assertEquals(layout, resetSelectionBar())
    }

    @Test
    fun moveSelectionToOverflowAndHidden() {
        val overflow = moveSelectionAction(defaultSelectionBar(), SelectionBarAction.Move, BarSection.Overflow)
        assertEquals(
            listOf(SelectionBarAction.Seen, SelectionBarAction.Flag, SelectionBarAction.Delete),
            overflow.toolbar,
        )
        assertEquals(listOf(SelectionBarAction.Move), overflow.overflow)
        assertEquals(emptyList<SelectionBarAction>(), overflow.hidden)
        assertEquals(
            listOf(
                SelectionMenuEntry.Divider,
                SelectionMenuEntry.Action(SelectionBarAction.Move),
                SelectionMenuEntry.Divider,
                SelectionMenuEntry.Customize,
            ),
            selectionMenuTail(overflow),
        )
        val hidden = moveSelectionAction(defaultSelectionBar(), SelectionBarAction.Move, BarSection.Hidden)
        assertFalse(hidden.toolbar.contains(SelectionBarAction.Move))
        assertFalse(hidden.overflow.contains(SelectionBarAction.Move))
        assertEquals(listOf(SelectionBarAction.Move), hidden.hidden)
    }

    @Test
    fun moveSelectionByOneLeavesTheStart() {
        val swapped = moveSelectionActionBy(defaultSelectionBar(), SelectionBarAction.Flag, 1)
        assertEquals(
            listOf(
                SelectionBarAction.Seen,
                SelectionBarAction.Move,
                SelectionBarAction.Flag,
                SelectionBarAction.Delete,
            ),
            swapped.toolbar,
        )
        assertEquals(emptyList<SelectionBarAction>(), swapped.overflow)
        assertEquals(emptyList<SelectionBarAction>(), swapped.hidden)
        assertEquals(
            defaultSelectionBar(),
            moveSelectionActionBy(defaultSelectionBar(), SelectionBarAction.Seen, -1),
        )
    }

    @Test
    fun defaultFolderLayoutAndMenu() {
        val layout = defaultFolderBar()
        val all = layout.toolbar + layout.overflow + layout.hidden
        assertEquals(listOf(FolderBarAction.Refresh), layout.toolbar)
        assertEquals(
            listOf(
                FolderBarAction.CollapseAll,
                FolderBarAction.SaveDefault,
                FolderBarAction.ResetDefault,
            ),
            layout.overflow,
        )
        assertEquals(emptyList<FolderBarAction>(), layout.hidden)
        assertEquals(FolderBarAction.entries.toSet(), all.toSet())
        assertEquals(all.size, all.toSet().size)
        assertEquals(
            listOf(
                FolderMenuEntry.Action(FolderBarAction.CollapseAll),
                FolderMenuEntry.Action(FolderBarAction.SaveDefault),
                FolderMenuEntry.Action(FolderBarAction.ResetDefault),
                FolderMenuEntry.Divider,
                FolderMenuEntry.Customize,
            ),
            folderMenu(layout, showUnsent = false),
        )
        assertEquals(
            listOf(
                FolderMenuEntry.Action(FolderBarAction.CollapseAll),
                FolderMenuEntry.Action(FolderBarAction.SaveDefault),
                FolderMenuEntry.Action(FolderBarAction.ResetDefault),
                FolderMenuEntry.Unsent,
                FolderMenuEntry.Divider,
                FolderMenuEntry.Customize,
            ),
            folderMenu(layout, showUnsent = true),
        )
        assertEquals(layout, resetFolderBar())
        assertEquals(
            listOf(IndexBarAction.Refresh, IndexBarAction.Search, IndexBarAction.Filter),
            defaultIndexBar().toolbar,
        )
        assertEquals(
            listOf(
                SelectionBarAction.Seen,
                SelectionBarAction.Flag,
                SelectionBarAction.Move,
                SelectionBarAction.Delete,
            ),
            defaultSelectionBar().toolbar,
        )
    }

    @Test
    fun moveFolderRefreshAndSaveDefault() {
        val overflow = moveFolderAction(defaultFolderBar(), FolderBarAction.Refresh, BarSection.Overflow)
        assertEquals(emptyList<FolderBarAction>(), overflow.toolbar)
        assertEquals(
            listOf(
                FolderBarAction.CollapseAll,
                FolderBarAction.SaveDefault,
                FolderBarAction.ResetDefault,
                FolderBarAction.Refresh,
            ),
            overflow.overflow,
        )
        val hidden = moveFolderAction(defaultFolderBar(), FolderBarAction.Refresh, BarSection.Hidden)
        assertFalse(hidden.toolbar.contains(FolderBarAction.Refresh))
        assertFalse(hidden.overflow.contains(FolderBarAction.Refresh))
        assertEquals(listOf(FolderBarAction.Refresh), hidden.hidden)
        assertFalse(folderMenu(hidden, showUnsent = false).contains(FolderMenuEntry.Action(FolderBarAction.Refresh)))
        val swapped = moveFolderActionBy(defaultFolderBar(), FolderBarAction.SaveDefault, -1)
        assertEquals(
            listOf(
                FolderBarAction.SaveDefault,
                FolderBarAction.CollapseAll,
                FolderBarAction.ResetDefault,
            ),
            swapped.overflow,
        )
        assertEquals(listOf(FolderBarAction.Refresh), swapped.toolbar)
        assertEquals(
            defaultFolderBar(),
            moveFolderActionBy(defaultFolderBar(), FolderBarAction.ResetDefault, 1),
        )
    }
}
