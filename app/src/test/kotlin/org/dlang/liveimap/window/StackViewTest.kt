package org.dlang.liveimap.window

import org.junit.Assert.assertEquals
import org.junit.Test

class StackViewTest {
    private val phone = LayoutRecord(LayoutNode.Panel(LayoutPanel("main")), UnusedPanel.Collapse, 0, 1)
    private val two = LayoutRecord(
        LayoutNode.Split(SplitDirection.Beside, 0.5f, LayoutNode.Panel(LayoutPanel("a")), LayoutNode.Panel(LayoutPanel("b"))),
        UnusedPanel.Collapse, 0, 2,
    )
    private val deep = WindowStack.drawer("acct").pushFolderTree("root").pushMailboxIndex("INBOX")
        .pushMessage("1", WindowLayerKind.FolderIndex)
    private fun kinds(f: FillResult) = f.slots.filter { !it.collapsed }.map { it.layer?.kind }

    @Test fun noArrowsWhenEveryLayerIsVisible() {
        val short = WindowStack.drawer("acct").pushMailboxIndex("INBOX")
        assertEquals(StackArrows(down = false, up = false), stackArrows(fill(two, short)))
    }

    @Test fun arrowsWalkDownAndBackUpWithoutTouchingTheStack() {
        assertEquals(StackArrows(down = true, up = false), stackArrows(fill(two, deep)))
        var view: StackView? = null
        view = stackViewStep(two, view, deep, down = true)
        assertEquals(listOf(WindowLayerKind.FolderTree, WindowLayerKind.FolderIndex), kinds(fill(two, deep, viewOffset(view, deep))))
        assertEquals(StackArrows(down = true, up = true), stackArrows(fill(two, deep, viewOffset(view, deep))))
        view = stackViewStep(two, view, deep, down = true)
        assertEquals(listOf(WindowLayerKind.Drawer, WindowLayerKind.FolderTree), kinds(fill(two, deep, viewOffset(view, deep))))
        assertEquals(StackArrows(down = false, up = true), stackArrows(fill(two, deep, viewOffset(view, deep))))
        // At the bottom another down does nothing.
        assertEquals(view, stackViewStep(two, view, deep, down = true))
        view = stackViewStep(two, view, deep, down = false)
        view = stackViewStep(two, view, deep, down = false)
        assertEquals(listOf(WindowLayerKind.FolderIndex, WindowLayerKind.Message), kinds(fill(two, deep, viewOffset(view, deep))))
        assertEquals(StackArrows(down = true, up = false), stackArrows(fill(two, deep, viewOffset(view, deep))))
    }

    @Test fun anyStackChangeShowsTheTopAgain() {
        val moved = stackViewStep(phone, null, deep, down = true)
        assertEquals(WindowLayerKind.FolderIndex, fill(phone, deep, viewOffset(moved, deep)).slots.single().layer?.kind)
        // Back pops: the view is tied to the old stack, so the new top shows.
        val popped = back(false, null, deep).stack
        assertEquals(0, viewOffset(moved, popped))
        assertEquals(WindowLayerKind.FolderIndex, fill(phone, popped, viewOffset(moved, popped)).slots.single().layer?.kind)
    }

    @Test fun pinnedPanelsStayPutWhileTheViewMoves() {
        val pinned = LayoutRecord(
            LayoutNode.Split(SplitDirection.Beside, 0.5f, LayoutNode.Panel(LayoutPanel("a", WindowLayerKind.Drawer)), LayoutNode.Panel(LayoutPanel("b"))),
            UnusedPanel.Collapse, 0, 1,
        )
        val view = stackViewStep(pinned, null, deep, down = true)
        val f = fill(pinned, deep, viewOffset(view, deep))
        assertEquals(WindowLayerKind.Drawer, f.slots[0].layer?.kind)
        assertEquals(WindowLayerKind.FolderIndex, f.slots[1].layer?.kind)
        // The pinned drawer is never "hidden below": down stops at the folder list.
        val bottom = stackViewStep(pinned, stackViewStep(pinned, view, deep, true), deep, true)
        assertEquals(WindowLayerKind.FolderTree, fill(pinned, deep, viewOffset(bottom, deep)).slots[1].layer?.kind)
        assertEquals(false, stackArrows(fill(pinned, deep, viewOffset(bottom, deep))).down)
    }
}
