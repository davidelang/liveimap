package org.dlang.liveimap.ui

import org.dlang.liveimap.window.WindowLayerKind
import org.dlang.liveimap.window.WindowStack
import org.dlang.liveimap.window.factoryRecord
import org.dlang.liveimap.window.fill
import org.dlang.liveimap.window.zoomTarget
import org.dlang.liveimap.window.LayoutAutomatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DrawerHiddenTest {
    @Test fun menuZoomShowsTheDrawerAndKeepsTheFill() {
        val stack = WindowStack.drawer("a").pushFolderTree("root")
        val filled = fill(factoryRecord(LayoutAutomatic), stack)
        val zoomed = zoomTarget(filled, stack.layers.first())
        assertNotNull(zoomed)
        assertEquals(WindowLayerKind.Drawer, zoomed!!.layer.kind)
        assertEquals(filled.slots, zoomed.unzoomed.slots)
        assertEquals(stack.layers, stack.layers)
    }
}
