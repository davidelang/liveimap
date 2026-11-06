package org.dlang.liveimap.ui

import org.dlang.liveimap.window.WindowLayerKind
import org.dlang.liveimap.window.WindowStack
import org.dlang.liveimap.window.factoryRecord
import org.dlang.liveimap.window.fill
import org.dlang.liveimap.window.zoomTarget
import org.dlang.liveimap.window.LayoutAutomatic
import org.dlang.liveimap.window.LayoutDrawerPinned
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
}
