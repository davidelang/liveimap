package org.dlang.liveimap.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins come from the layout record; fill and Back apply one generic rule to whatever kind is pinned. */
class LayoutPinTest {
    private fun three(a: WindowLayerKind?, b: WindowLayerKind?, c: WindowLayerKind? = null, keep: Int = 3) = LayoutRecord(
        LayoutNode.Split(SplitDirection.Beside, 0.33f, LayoutNode.Panel(LayoutPanel("a", a)),
            LayoutNode.Split(SplitDirection.Beside, 0.5f, LayoutNode.Panel(LayoutPanel("b", b)), LayoutNode.Panel(LayoutPanel("c", c)))),
        UnusedPanel.Collapse, 0, keep,
    )
    private val withTree = WindowStack.drawer("acct").pushFolderTree("root").pushMailboxIndex("INBOX")
        .pushMessage("1", WindowLayerKind.FolderIndex)
    private val noTree = WindowStack.drawer("acct").pushMailboxIndex("INBOX").pushMessage("1", WindowLayerKind.FolderIndex)
    private fun shown(r: LayoutRecord, s: WindowStack, off: Int = 0) =
        fill(r, s, off).slots.associate { it.panelIds.single() to it.layer?.kind }

    @Test fun pinnedDrawerIsTakenOutOfTheFlow() {
        val m = shown(three(WindowLayerKind.Drawer, null), withTree)
        assertEquals(WindowLayerKind.Drawer, m["a"])
        assertEquals(WindowLayerKind.FolderIndex, m["b"]); assertEquals(WindowLayerKind.Message, m["c"])
    }

    @Test fun pinnedFolderKeepsShowingWhileOverflowHidesInTheFreePanels() {
        val m = shown(three(null, WindowLayerKind.FolderIndex), withTree)
        assertEquals(WindowLayerKind.FolderIndex, m["b"])
        assertEquals(WindowLayerKind.FolderTree, m["a"]); assertEquals(WindowLayerKind.Message, m["c"])
    }

    @Test fun pinnedFolderStaysEmptyAfterASettingsPick() {
        val settings = WindowStack(listOf(WindowLayer(WindowLayerKind.Drawer, "acct"), WindowLayer(WindowLayerKind.Screen, "settings")))
        val b = fill(three(null, WindowLayerKind.FolderIndex), settings).slots.single { it.panelIds == listOf("b") }
        assertNull(b.layer); assertFalse(b.collapsed)
    }

    @Test fun optionalFolderListPresentOrAbsent() {
        val r = three(null, WindowLayerKind.FolderTree)
        assertEquals(WindowLayerKind.FolderTree, shown(r, withTree)["b"])
        assertNull(shown(r, noTree)["b"])
    }

    @Test fun pinnedMessagePaneFollowsTheOpenMessageAndStaysEmptyWithout() {
        val r = three(null, null, WindowLayerKind.Message)
        val read = fill(r, withTree).slots.single { it.panelIds == listOf("c") }
        assertEquals(WindowLayer(WindowLayerKind.Message, "1"), read.layer)
        // The index and the tree flow through a and b, beside the message.
        assertEquals(WindowLayerKind.FolderIndex, shown(r, withTree)["b"])
        val closed = fill(r, withTree.dropMessage()).slots.single { it.panelIds == listOf("c") }
        assertNull(closed.layer); assertFalse(closed.collapsed)
        assertEquals(WindowLayerKind.FolderIndex, shown(r, withTree.dropMessage())["b"])
    }

    @Test fun backNeverPopsAPinnedLayerWhateverItsKind() {
        val drawerOnly = pinnedKinds(three(WindowLayerKind.Drawer, null))
        assertTrue(backLeavesWindow(WindowStack.drawer("acct").pushFolderTree("root"), drawerOnly))
        assertFalse(backLeavesWindow(withTree, drawerOnly))
        assertFalse(backLeavesWindow(WindowStack.drawer("acct").pushFolderTree("root"), emptySet()))
    }

    @Test fun anAllPinnedRecordIsReportedNotRewritten() {
        val all = three(WindowLayerKind.Drawer, WindowLayerKind.FolderTree, WindowLayerKind.FolderIndex)
        assertEquals(LayoutRecordProblem.NoUnpinnedPanel, layoutRecordProblem(all))
        val result = fill(all, withTree)
        assertEquals(LayoutRecordProblem.NoUnpinnedPanel, result.problem)
        // Every pin keeps its own kind; nothing is silently unpinned to make room for the message.
        assertEquals(listOf(WindowLayerKind.Drawer, WindowLayerKind.FolderTree, WindowLayerKind.FolderIndex),
            result.slots.map { it.layer?.kind })
        assertEquals(3, pinnedKinds(all).size)
    }
}
