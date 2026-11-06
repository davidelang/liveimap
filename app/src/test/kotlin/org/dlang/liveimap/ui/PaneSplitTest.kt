package org.dlang.liveimap.ui

import org.dlang.liveimap.window.LayoutAutomatic
import org.dlang.liveimap.window.LayoutDrawerPinned
import org.dlang.liveimap.window.LayoutIndexBesideMessage
import org.dlang.liveimap.window.LayoutNode
import org.dlang.liveimap.window.LayoutPanel
import org.dlang.liveimap.window.LayoutRecord
import org.dlang.liveimap.window.SplitDirection
import org.dlang.liveimap.window.UnusedPanel
import org.dlang.liveimap.window.WindowLayerKind
import org.dlang.liveimap.window.WindowStack
import org.dlang.liveimap.window.factoryRecord
import org.dlang.liveimap.window.fill
import org.dlang.liveimap.window.keyLayer
import org.dlang.liveimap.window.keyPanel
import org.dlang.liveimap.window.zoomTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneSplitTest {
    @Test
    fun zoomOfTheOnlyPanelDoesNothing() {
        val stack = WindowStack.drawer("a")
        val filled = fill(factoryRecord(LayoutAutomatic), stack)
        assertNull(zoomTarget(filled, stack.layers.first()))
    }

    @Test
    fun aPinDoesNotBlockZoom() {
        val stack = WindowStack.drawer("a").pushFolderTree("root").pushMailboxIndex("INBOX")
            .pushMessage("1", WindowLayerKind.FolderIndex)
        val filled = fill(factoryRecord(LayoutDrawerPinned), stack)
        val zoomed = zoomTarget(filled, stack.layers.first())
        assertNotNull(zoomed)
        assertEquals(WindowLayerKind.Drawer, zoomed!!.layer.kind)
        assertEquals(filled.slots, zoomed.unzoomed.slots)
    }

    @Test
    fun coverSpanJoinsPanelsThatAreNotOneSubtree() {
        val root = LayoutNode.Split(
            SplitDirection.Beside,
            0.5f,
            LayoutNode.Split(
                SplitDirection.Beside,
                0.5f,
                LayoutNode.Panel(LayoutPanel("a")),
                LayoutNode.Panel(LayoutPanel("b")),
            ),
            LayoutNode.Panel(LayoutPanel("c")),
        )
        val record = LayoutRecord(root, UnusedPanel.Collapse, coverPanels = 2, unpinnedKeep = 2)
        val stack = WindowStack.drawer("a").pushFolderTree("root").pushMailboxIndex("INBOX")
        val filled = fill(record, stack)
        val cover = filled.slots.first { it.panelIds.size > 1 }
        assertEquals(listOf("b", "c"), cover.panelIds)
        assertFalse(coverIsOneSubtree(root, filled))
        val regions = panelRegions(root, filled, emptyMap(), width = 308f, height = 200f)
        val a = regions.getValue("a")
        val b = regions.getValue("b")
        val c = regions.getValue("c")
        assertEquals(b.x + b.w, c.x, 0.01f)
        assertEquals(b.y, c.y, 0.01f)
        assertEquals(b.h, c.h, 0.01f)
        assertTrue(a.x + a.w <= b.x + 0.01f)
        assertTrue(b.x - (a.x + a.w) > 1f)
    }

    @Test
    fun keysStayOnThePanelThatIsNotTheTopLayer() {
        val beside = factoryRecord(LayoutIndexBesideMessage)!!
        val stack = WindowStack.drawer("a").pushFolderTree("root").pushMailboxIndex("INBOX")
            .pushMessage("4", WindowLayerKind.FolderIndex)
        val filled = fill(beside, stack)
        val indexId = beside.panels.first().id
        val id = keyPanel(filled, indexId)
        val layer = keyLayer(filled, id)
        val indexSlot = filled.slots.first { indexId in it.panelIds }
        val messageSlot = filled.slots.first { it.layer?.kind == WindowLayerKind.Message }
        assertTrue(slotHoldsKeys(indexSlot, id, layer))
        assertFalse(slotHoldsKeys(messageSlot, id, layer))
        assertEquals(WindowLayerKind.Message, stack.layers.last().kind)
    }
}
