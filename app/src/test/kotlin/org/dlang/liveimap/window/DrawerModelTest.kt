package org.dlang.liveimap.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerModelTest {
    private val drawer = WindowLayer(WindowLayerKind.Drawer, "account")
    private val reading = WindowStack.drawer("account")
        .pushFolderTree("root")
        .pushMailboxIndex("INBOX")
        .pushMessage("4", WindowLayerKind.FolderIndex)
    private val phone = LayoutRecord(LayoutNode.Panel(LayoutPanel("main")), UnusedPanel.Collapse, 0, 1)

    @Test fun menuPeeksAndHidesWithoutTouchingTheStack() {
        val start = DrawerView(reading, peek = false)
        assertFalse(drawerInPanel(fill(phone, reading)))
        val peek = drawerStep(start, DrawerInput.Menu, drawerInPanel = false)
        assertTrue(peek.peek)
        assertEquals(reading, peek.stack)
        assertEquals(start, drawerStep(peek, DrawerInput.Menu, false))
        assertEquals(start, drawerStep(peek, DrawerInput.Hide, false))
        // Back with the peek showing hides it; it does not pop.
        assertEquals(start, drawerStep(peek, DrawerInput.Back, false))
    }

    @Test fun aPickReplacesEverythingAboveTheDrawer() {
        val peek = drawerStep(DrawerView(reading, false), DrawerInput.Menu, false)
        val settings = drawerStep(peek, DrawerInput.Pick(listOf(WindowLayer(WindowLayerKind.Screen, "settings"))), false)
        assertFalse(settings.peek)
        assertEquals(listOf(drawer, WindowLayer(WindowLayerKind.Screen, "settings")), settings.stack.layers)
        // Settings is what the single panel shows.
        assertEquals(WindowLayer(WindowLayerKind.Screen, "settings"), displayedFill(phone, settings.stack, null).slots.single().layer)
        val inbox = drawerStep(settings, DrawerInput.Pick(listOf(WindowLayer(WindowLayerKind.FolderIndex, "INBOX"))), false)
        assertEquals(listOf(drawer, WindowLayer(WindowLayerKind.FolderIndex, "INBOX")), inbox.stack.layers)
    }

    @Test fun backPopsDownToTheDrawerAndAPickFromThereReplacesToo() {
        var view = DrawerView(reading, false)
        repeat(3) { view = drawerStep(view, DrawerInput.Back, false) }
        assertEquals(listOf(drawer), view.stack.layers)
        // With only the drawer left, the single panel shows it, so the menu button has nothing to peek.
        assertTrue(drawerInPanel(fill(phone, view.stack)))
        assertEquals(view, drawerStep(view, DrawerInput.Menu, drawerInPanel = true))
        val tree = drawerStep(view, DrawerInput.Pick(listOf(WindowLayer(WindowLayerKind.FolderTree, "root"))), true)
        assertEquals(listOf(drawer, WindowLayer(WindowLayerKind.FolderTree, "root")), tree.stack.layers)
        assertEquals(listOf(drawer), drawerStep(view, DrawerInput.Back, true).stack.layers)
    }

    @Test fun aPanelThatShowsTheDrawerPicksInPlace() {
        val twoPanel = LayoutRecord(
            LayoutNode.Split(SplitDirection.Beside, 0.5f,
                LayoutNode.Panel(LayoutPanel("side", WindowLayerKind.Drawer)), LayoutNode.Panel(LayoutPanel("main"))),
            UnusedPanel.Collapse, 0, 1,
        )
        assertTrue(drawerInPanel(fill(twoPanel, reading)))
        val view = DrawerView(reading, false)
        assertEquals(view, drawerStep(view, DrawerInput.Menu, drawerInPanel = true))
        val picked = drawerStep(view, DrawerInput.Pick(listOf(WindowLayer(WindowLayerKind.Screen, "help"))), true)
        assertEquals(listOf(drawer, WindowLayer(WindowLayerKind.Screen, "help")), picked.stack.layers)
    }
}
