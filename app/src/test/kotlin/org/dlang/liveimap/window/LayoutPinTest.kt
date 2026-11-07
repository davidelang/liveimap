package org.dlang.liveimap.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins come from the layout record; fill and Back apply one generic rule to whatever kind is pinned. */
class LayoutPinTest {
    private fun three(a: WindowLayerKind?, b: WindowLayerKind?, c: WindowLayerKind? = null) = LayoutRecord(
        LayoutNode.Split(SplitDirection.Beside, 0.33f, LayoutNode.Panel(LayoutPanel("a", a)),
            LayoutNode.Split(SplitDirection.Beside, 0.5f, LayoutNode.Panel(LayoutPanel("b", b)), LayoutNode.Panel(LayoutPanel("c", c)))),
        UnusedPanel.Collapse, 0, 1,
    )
    private val withTree = WindowStack.drawer("acct").pushFolderTree("root").pushMailboxIndex("INBOX")
        .pushMessage("1", WindowLayerKind.FolderIndex)
    private val noTree = WindowStack.drawer("acct").pushMailboxIndex("INBOX").pushMessage("1", WindowLayerKind.FolderIndex)
    private fun shown(r: LayoutRecord, s: WindowStack) = fill(r, s).slots.associate { it.panelIds.single() to it.layer?.kind }

    @Test fun pinnedDrawerIsTakenOutOfTheFlow() {
        val m = shown(three(WindowLayerKind.Drawer, null), withTree)
        assertEquals(WindowLayerKind.Drawer, m["a"])
        assertEquals(WindowLayerKind.FolderIndex, m["b"]); assertEquals(WindowLayerKind.Message, m["c"])
    }

    @Test fun pinnedFolderKeepsShowingWhileOverflowHidesInTheFreePanels() {
        val m = shown(three(null, WindowLayerKind.FolderIndex), withTree)
        assertEquals(WindowLayerKind.FolderIndex, m["b"])
        // a and c share drawer/tree/message: the top two unpinned layers show, in stack order.
        assertEquals(WindowLayerKind.FolderTree, m["a"]); assertEquals(WindowLayerKind.Message, m["c"])
    }

    @Test fun pinnedFolderStaysEmptyAfterASettingsPick() {
        val settings = WindowStack(listOf(WindowLayer(WindowLayerKind.Drawer, "acct"), WindowLayer(WindowLayerKind.Screen, "settings")))
        val slots = fill(three(null, WindowLayerKind.FolderIndex), settings).slots
        val b = slots.single { it.panelIds == listOf("b") }
        assertNull(b.layer); assertFalse(b.collapsed)
        assertTrue(slots.none { it.layer?.kind == WindowLayerKind.Screen && it.panelIds == listOf("b") })
    }

    @Test fun optionalFolderListPresentOrAbsent() {
        val r = three(null, WindowLayerKind.FolderTree)
        assertEquals(WindowLayerKind.FolderTree, shown(r, withTree)["b"])
        assertNull(shown(r, noTree)["b"])
        assertTrue(fill(r, noTree).slots.single { it.panelIds == listOf("b") }.collapsed.not())
    }

    @Test fun backNeverPopsAPinnedLayerWhateverItsKind() {
        val drawerOnly = pinnedKinds(three(WindowLayerKind.Drawer, null))
        assertTrue(backLeavesWindow(WindowStack.drawer("acct").pushFolderTree("root"), drawerOnly))
        assertFalse(backLeavesWindow(withTree, drawerOnly))
        assertFalse(backLeavesWindow(WindowStack.drawer("acct").pushFolderTree("root"), emptySet()))
        val both = pinnedKinds(three(WindowLayerKind.Drawer, WindowLayerKind.FolderTree))
        assertTrue(backLeavesWindow(WindowStack.drawer("acct").pushFolderTree("root").pushMailboxIndex("INBOX"), both))
    }

    @Test fun allPinnedIsRejectedAndRotationReleasesTheLastPin() {
        val all = three(WindowLayerKind.Drawer, WindowLayerKind.FolderTree, WindowLayerKind.FolderIndex)
        assertEquals(LayoutRecordProblem.NoUnpinnedPanel, layoutRecordProblem(all))
        assertEquals(setOf(WindowLayerKind.Drawer, WindowLayerKind.FolderTree), pinnedKinds(all))
    }
}
