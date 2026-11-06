package org.dlang.liveimap.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutFillTest {
    private val stack = WindowStack.drawer("account")
        .pushFolderTree("root")
        .pushMailboxIndex("INBOX")
        .pushMessage("4", WindowLayerKind.FolderIndex)

    @Test
    fun pinKeepsALayerTheUnpinnedOrderWouldDrop() {
        val record = LayoutRecord(
            panels = listOf(
                LayoutPanel("side", WindowLayerKind.Drawer),
                LayoutPanel("main"),
            ),
            unused = UnusedPanel.Collapse,
            coverPanels = 0,
            unpinnedKeep = 1,
        )
        val filled = fill(record, stack)
        assertEquals(WindowLayerKind.Drawer, filled.slots[0].layer?.kind)
        assertEquals(WindowLayerKind.Message, filled.slots[1].layer?.kind)
        assertEquals("4", filled.slots[1].layer?.key)
    }

    @Test
    fun unusedPanelCollapsesOrStaysEmpty() {
        val panels = listOf(LayoutPanel("side"), LayoutPanel("main"))
        val collapse = fill(
            LayoutRecord(panels, UnusedPanel.Collapse, coverPanels = 0, unpinnedKeep = 1),
            stack,
        )
        assertNull(collapse.slots[0].layer)
        assertTrue(collapse.slots[0].collapsed)
        assertEquals(WindowLayerKind.Message, collapse.slots[1].layer?.kind)

        val empty = fill(
            LayoutRecord(panels, UnusedPanel.StayEmpty, coverPanels = 0, unpinnedKeep = 1),
            stack,
        )
        assertNull(empty.slots[0].layer)
        assertFalse(empty.slots[0].collapsed)
        assertEquals(WindowLayerKind.Message, empty.slots[1].layer?.kind)
    }

    @Test
    fun coveringLayerTakesPanelsFromTheFocusedEnd() {
        val record = LayoutRecord(
            panels = listOf(LayoutPanel("a"), LayoutPanel("b"), LayoutPanel("c")),
            unused = UnusedPanel.StayEmpty,
            coverPanels = 2,
            unpinnedKeep = 3,
        )
        val filled = fill(record, stack)
        assertEquals(listOf("a"), filled.slots[0].panelIds)
        assertEquals(WindowLayerKind.FolderIndex, filled.slots[0].layer?.kind)
        assertEquals(listOf("b", "c"), filled.slots[1].panelIds)
        assertEquals(WindowLayer(WindowLayerKind.Message, "4"), filled.slots[1].layer)
    }

    @Test
    fun twoPanelsNeverReceiveTheSameLayer() {
        val record = LayoutRecord(
            panels = listOf(
                LayoutPanel("a", WindowLayerKind.Drawer),
                LayoutPanel("b", WindowLayerKind.Drawer),
            ),
            unused = UnusedPanel.StayEmpty,
            coverPanels = 0,
            unpinnedKeep = 0,
        )
        val filled = fill(record, stack)
        val layers = filled.slots.map { it.layer }
        assertEquals(WindowLayerKind.Drawer, layers[0]?.kind)
        assertNull(layers[1])
        assertEquals(layers.filterNotNull().distinct().size, layers.filterNotNull().size)
    }

    @Test
    fun emptyCatalogFillsOnePanelWithTheTopLayer() {
        val top = stack.layers.last()
        val missing = fill(null, stack)
        assertEquals(listOf(FillSlot(listOf("panel"), top, collapsed = false)), missing.slots)
        val empty = fill(
            LayoutRecord(emptyList(), UnusedPanel.Collapse, coverPanels = 2, unpinnedKeep = 1),
            stack,
        )
        assertEquals(missing, empty)
    }

    @Test
    fun zoomFillsOnePanelAndRemembersTheUnzoomedFill() {
        val current = fill(null, stack)
        val layer = stack.layers.first()
        val zoomed = zoom(current, layer)
        assertEquals(current, zoomed.unzoomed)
        assertEquals(
            listOf(FillSlot(listOf("zoom"), layer, collapsed = false)),
            zoomFill(zoomed).slots,
        )
    }

    @Test
    fun backClearsZoomBeforeItPopsAndADialogIsNotALayer() {
        assertFalse(WindowLayerKind.entries.any { it.name == "Dialog" })
        val zoomed = zoom(fill(null, stack), stack.layers.last())
        val dialog = back(dialogOpen = true, zoom = zoomed, stack = stack)
        assertTrue(dialog.dismissedDialog)
        assertEquals(zoomed, dialog.zoom)
        assertEquals(stack.layers, dialog.stack.layers)

        val cleared = back(dialogOpen = false, zoom = zoomed, stack = stack)
        assertFalse(cleared.dismissedDialog)
        assertNull(cleared.zoom)
        assertEquals(stack, cleared.stack)

        val popped = back(dialogOpen = false, zoom = null, stack = stack)
        assertEquals(stack.pop(), popped.stack)
        assertEquals(WindowLayerKind.FolderIndex, popped.stack.layers.last().kind)

        val filled = fill(
            LayoutRecord(listOf(LayoutPanel("main")), UnusedPanel.StayEmpty, 0, 1),
            stack,
        )
        assertEquals(filled, fill(filled.let { LayoutRecord(listOf(LayoutPanel("main")), UnusedPanel.StayEmpty, 0, 1) }, stack))
        assertTrue(filled.slots.none { it.layer == null && it.panelIds.isEmpty() })
    }
}
