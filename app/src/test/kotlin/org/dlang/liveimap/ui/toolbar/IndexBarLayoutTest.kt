package org.dlang.liveimap.ui.toolbar

import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.ReaderAction
import org.dlang.liveimap.settings.defaultReaderBar
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

    @Test
    fun readerToolbarFollowsReaderBarUntilSaved() {
        val layout = readerToolbarFrom(defaultReaderBar)
        assertEquals(
            listOf(
                ReaderToolbarAction.Refresh,
                ReaderToolbarAction.Reply,
                ReaderToolbarAction.ReplyAll,
                ReaderToolbarAction.Forward,
                ReaderToolbarAction.Delete,
            ),
            layout.toolbar,
        )
        assertEquals(
            listOf(
                ReaderToolbarAction.Move,
                ReaderToolbarAction.Spam,
                ReaderToolbarAction.Bounce,
            ),
            layout.overflow,
        )
        assertEquals(emptyList<ReaderToolbarAction>(), layout.hidden)
        val replyOnly = readerToolbarFrom(listOf(ReaderAction.Reply))
        assertEquals(
            listOf(ReaderToolbarAction.Refresh, ReaderToolbarAction.Reply),
            replyOnly.toolbar,
        )
        assertEquals(
            listOf(
                ReaderToolbarAction.ReplyAll,
                ReaderToolbarAction.Forward,
                ReaderToolbarAction.Delete,
                ReaderToolbarAction.Move,
                ReaderToolbarAction.Spam,
                ReaderToolbarAction.Bounce,
            ),
            replyOnly.overflow,
        )
        assertEquals(
            listOf(
                ReaderToolbarAction.ReplyAll,
                ReaderToolbarAction.Forward,
                ReaderToolbarAction.Delete,
                ReaderToolbarAction.Move,
                ReaderToolbarAction.Bounce,
            ),
            visibleReaderActions(replyOnly.overflow, ""),
        )
        assertEquals(replyOnly.overflow, visibleReaderActions(replyOnly.overflow, "Junk"))
        val appended = moveReaderAction(layout, ReaderToolbarAction.Bounce, BarSection.Toolbar)
        assertEquals(layout.toolbar + ReaderToolbarAction.Bounce, appended.toolbar)
        assertFalse(appended.overflow.contains(ReaderToolbarAction.Bounce))
        assertEquals(emptyList<ReaderToolbarAction>(), appended.hidden)
        assertEquals(layout, resetReaderToolbar())
        val swapped = moveReaderActionBy(layout, ReaderToolbarAction.ReplyAll, -1)
        assertEquals(
            listOf(
                ReaderToolbarAction.Refresh,
                ReaderToolbarAction.ReplyAll,
                ReaderToolbarAction.Reply,
                ReaderToolbarAction.Forward,
                ReaderToolbarAction.Delete,
            ),
            swapped.toolbar,
        )
        assertEquals(layout, moveReaderActionBy(layout, ReaderToolbarAction.Refresh, -1))
        assertEquals(layout, effectiveReaderToolbar(AccountSettings()))
        val followed = effectiveReaderToolbar(AccountSettings(readerBar = listOf(ReaderAction.Reply)))
        assertEquals(replyOnly, followed)
        val pinned = AccountSettings(readerBar = listOf(ReaderAction.Reply), readerToolbar = layout)
        assertEquals(layout, effectiveReaderToolbar(pinned))
    }

    @Test
    fun composePostponeMovesAndMenu() {
        val layout = defaultComposeBar()
        assertEquals(emptyList<ComposeBarAction>(), layout.toolbar)
        assertEquals(listOf(ComposeBarAction.Postpone), layout.overflow)
        assertEquals(emptyList<ComposeBarAction>(), layout.hidden)
        assertEquals(
            listOf(
                ComposeMenuEntry.Action(ComposeBarAction.Postpone),
                ComposeMenuEntry.Divider,
                ComposeMenuEntry.Customize,
            ),
            composeMenu(layout),
        )
        assertEquals(layout, resetComposeBar())
        val onBar = moveComposeAction(layout, ComposeBarAction.Postpone, BarSection.Toolbar)
        assertEquals(listOf(ComposeBarAction.Postpone), onBar.toolbar)
        assertEquals(emptyList<ComposeBarAction>(), onBar.overflow)
        assertEquals(emptyList<ComposeBarAction>(), onBar.hidden)
        assertEquals(
            listOf(ComposeMenuEntry.Divider, ComposeMenuEntry.Customize),
            composeMenu(onBar),
        )
        val hidden = moveComposeAction(layout, ComposeBarAction.Postpone, BarSection.Hidden)
        assertFalse(hidden.toolbar.contains(ComposeBarAction.Postpone))
        assertFalse(hidden.overflow.contains(ComposeBarAction.Postpone))
        assertEquals(listOf(ComposeBarAction.Postpone), hidden.hidden)
        assertEquals(
            listOf(ComposeMenuEntry.Divider, ComposeMenuEntry.Customize),
            composeMenu(hidden),
        )
        assertEquals(layout, moveComposeActionBy(layout, ComposeBarAction.Postpone, 1))
        assertEquals(layout, moveComposeActionBy(layout, ComposeBarAction.Postpone, -1))
    }

    @Test
    fun dragIndexWithinAndAcrossSections() {
        val swapped = dragIndexLayout(defaultIndexBar(), 2, 3)
        assertEquals(
            listOf(IndexBarAction.Refresh, IndexBarAction.Filter, IndexBarAction.Search),
            swapped.toolbar,
        )
        assertEquals(emptyList<IndexBarAction>(), swapped.overflow)
        assertEquals(emptyList<IndexBarAction>(), swapped.hidden)
        val hidden = dragIndexLayout(defaultIndexBar(), 3, 5)
        assertEquals(listOf(IndexBarAction.Refresh, IndexBarAction.Search), hidden.toolbar)
        assertEquals(emptyList<IndexBarAction>(), hidden.overflow)
        assertEquals(listOf(IndexBarAction.Filter), hidden.hidden)
        assertEquals(defaultIndexBar(), dragIndexLayout(defaultIndexBar(), 0, 3))
        assertEquals(defaultIndexBar(), dragIndexLayout(defaultIndexBar(), 1, 6))
    }

    @Test
    fun dragComposeOntoTheToolbar() {
        val dragged = dragComposeLayout(defaultComposeBar(), 2, 0)
        assertEquals(listOf(ComposeBarAction.Postpone), dragged.toolbar)
        assertEquals(emptyList<ComposeBarAction>(), dragged.overflow)
        assertEquals(emptyList<ComposeBarAction>(), dragged.hidden)
    }
}
