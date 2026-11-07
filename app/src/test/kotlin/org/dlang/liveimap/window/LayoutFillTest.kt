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

    private fun record(
        panels: List<LayoutPanel>,
        unused: UnusedPanel,
        coverPanels: Int,
        unpinnedKeep: Int,
    ): LayoutRecord {
        val root = panels.foldRight<LayoutPanel, LayoutNode?>(null) { panel, acc ->
            val leaf = LayoutNode.Panel(panel)
            if (acc == null) leaf else LayoutNode.Split(SplitDirection.Beside, 0.5f, leaf, acc)
        }
        return LayoutRecord(root, unused, coverPanels, unpinnedKeep)
    }

    @Test
    fun pinKeepsALayerTheUnpinnedOrderWouldDrop() {
        val record = record(
            listOf(
                LayoutPanel("side", WindowLayerKind.Drawer),
                LayoutPanel("main"),
            ),
            UnusedPanel.Collapse,
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
            record(panels, UnusedPanel.Collapse, coverPanels = 0, unpinnedKeep = 1),
            stack,
        )
        assertNull(collapse.slots[0].layer)
        assertTrue(collapse.slots[0].collapsed)
        assertEquals(WindowLayerKind.Message, collapse.slots[1].layer?.kind)

        val empty = fill(
            record(panels, UnusedPanel.StayEmpty, coverPanels = 0, unpinnedKeep = 1),
            stack,
        )
        assertNull(empty.slots[0].layer)
        assertFalse(empty.slots[0].collapsed)
        assertEquals(WindowLayerKind.Message, empty.slots[1].layer?.kind)
    }

    @Test
    fun coveringLayerTakesPanelsFromTheFocusedEnd() {
        val record = record(
            listOf(LayoutPanel("a"), LayoutPanel("b"), LayoutPanel("c")),
            UnusedPanel.StayEmpty,
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
        val record = record(
            listOf(
                LayoutPanel("a", WindowLayerKind.Drawer),
                LayoutPanel("b", WindowLayerKind.Drawer),
            ),
            UnusedPanel.StayEmpty,
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
            record(emptyList(), UnusedPanel.Collapse, coverPanels = 2, unpinnedKeep = 1),
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
            record(listOf(LayoutPanel("main")), UnusedPanel.StayEmpty, 0, 1),
            stack,
        )
        assertEquals(filled, fill(record(listOf(LayoutPanel("main")), UnusedPanel.StayEmpty, 0, 1), stack))
        assertTrue(filled.slots.none { it.layer == null && it.panelIds.isEmpty() })
    }

    @Test
    fun factoriesAreRecordsAndUnusedPanelsCollapse() {
        val one = factoryRecord(LayoutAutomatic)!!
        assertEquals(1, one.panels.size)
        assertEquals(UnusedPanel.Collapse, one.unused)
        assertEquals(1, one.unpinnedKeep)
        val beside = factoryRecord(LayoutIndexBesideMessage)!!
        val above = factoryRecord(LayoutListAboveMessage)!!
        assertEquals(2, beside.unpinnedKeep)
        assertEquals(2, above.unpinnedKeep)
        val besideSplit = beside.root as LayoutNode.Split
        val aboveSplit = above.root as LayoutNode.Split
        assertEquals(SplitDirection.Beside, besideSplit.direction)
        assertEquals(SplitDirection.Above, aboveSplit.direction)
        assertTrue(besideSplit.share > 0f && besideSplit.share < 1f)
        assertTrue(aboveSplit.share > 0f && aboveSplit.share < 1f)
        for (id in listOf(LayoutFolderTreePinned, LayoutDrawerPinned)) {
            val three = factoryRecord(id)!!
            assertEquals(3, three.panels.size)
            assertEquals(2, three.unpinnedKeep)
            assertEquals(UnusedPanel.Collapse, three.unused)
            assertEquals(1, three.panels.count { it.pin != null })
        }
        assertEquals(WindowLayerKind.FolderTree, factoryRecord(LayoutFolderTreePinned)!!.panels.first().pin)
        assertEquals(WindowLayerKind.Drawer, factoryRecord(LayoutDrawerPinned)!!.panels.first().pin)
        val collapsed = fill(beside, WindowStack.drawer("account"))
        assertTrue(collapsed.slots.any { it.collapsed })
    }

    @Test
    fun keysStayOnAPanelThatIsNotTheTopLayer() {
        val beside = factoryRecord(LayoutIndexBesideMessage)!!
        val filled = fill(beside, stack)
        val indexId = beside.panels.first().id
        val keys = keyPanel(filled, indexId)
        assertEquals(indexId, keys)
        assertEquals(WindowLayerKind.FolderIndex, keyLayer(filled, keys)?.kind)
        assertEquals(WindowLayerKind.Message, stack.layers.last().kind)
    }

    @Test
    fun aScreenFillsTheTopSlotsOfTheFactoryLayouts() {
        val one = factoryRecord(LayoutAutomatic)
        val beside = factoryRecord(LayoutIndexBesideMessage)
        val above = factoryRecord(LayoutListAboveMessage)
        assertEquals(1, one!!.panels.size)
        assertEquals(2, beside!!.panels.size)
        assertEquals(2, above!!.panels.size)
        val screen = stack.pushScreen("settings")
        assertEquals(
            listOf(WindowLayer(WindowLayerKind.Screen, "settings")),
            fill(one, screen).slots.map { it.layer },
        )
        val wide = fill(beside, screen)
        assertEquals(WindowLayerKind.Message, wide.slots[0].layer?.kind)
        assertEquals("4", wide.slots[0].layer?.key)
        assertEquals(WindowLayer(WindowLayerKind.Screen, "settings"), wide.slots[1].layer)
        val piled = screen.pushScreen("filter")
        val twoScreens = fill(beside, piled)
        assertEquals(WindowLayer(WindowLayerKind.Screen, "settings"), twoScreens.slots[0].layer)
        assertEquals(WindowLayer(WindowLayerKind.Screen, "filter"), twoScreens.slots[1].layer)
        assertEquals(fill(beside, stack).slots.map { it.layer?.kind }, fill(above, stack).slots.map { it.layer?.kind })
        assertEquals(
            listOf(WindowLayerKind.Message, WindowLayerKind.Screen),
            fill(above, screen).slots.map { it.layer?.kind },
        )
    }

    @Test
    fun zoomOfAnAlreadyFullPanelDoesNothing() {
        val current = fill(factoryRecord(LayoutAutomatic), stack)
        assertNull(zoomTarget(current, stack.layers.last()))
        val beside = fill(factoryRecord(LayoutIndexBesideMessage), stack)
        val drawer = stack.layers.first()
        val zoomed = zoomTarget(beside, drawer)
        assertEquals(drawer, zoomed?.layer)
        assertEquals(beside, zoomed?.unzoomed)
        assertNull(zoomTarget(zoomFill(zoomed!!), drawer))
    }

    private val drawerLayer get() = stack.layers.first()

    @Test
    fun drawerOpensOverTheSinglePanelAndTheFillStaysUnderIt() {
        val phone = record(listOf(LayoutPanel("main")), UnusedPanel.Collapse, coverPanels = 0, unpinnedKeep = 1)
        val base = fill(phone, stack)
        val open = toggleDrawer(base, drawerLayer, null)
        assertTrue(drawerOverlayOpen(open))
        // The message stays drawn under the sheet; the scrim, a swipe and a pick can close it.
        assertEquals(base, displayedFill(phone, stack, open))
        assertEquals(WindowLayerKind.Message, displayedFill(phone, stack, open).slots.single().layer?.kind)
        // A second hamburger press closes it.
        assertNull(toggleDrawer(base, drawerLayer, open))
        assertEquals(base, displayedFill(phone, stack, null))
    }

    @Test
    fun drawerAlreadyInAPanelDoesNotOpenASecondCopy() {
        val twoPanel = record(
            listOf(LayoutPanel("side", WindowLayerKind.Drawer), LayoutPanel("main")),
            UnusedPanel.Collapse,
            coverPanels = 0,
            unpinnedKeep = 1,
        )
        val base = fill(twoPanel, stack)
        assertNull(toggleDrawer(base, drawerLayer, null))
        assertFalse(drawerOverlayOpen(toggleDrawer(base, drawerLayer, null)))
    }

    @Test
    fun aNonDrawerZoomStillReplacesTheFill() {
        val twoPanel = record(listOf(LayoutPanel("side"), LayoutPanel("main")), UnusedPanel.StayEmpty, 0, 2)
        val base = fill(twoPanel, stack)
        val message = stack.layers.last()
        val zoomed = zoomTarget(base, message)!!
        assertFalse(drawerOverlayOpen(zoomed))
        assertEquals(zoomFill(zoomed), displayedFill(twoPanel, stack, zoomed))
    }

    @Test
    fun singlePanelShowsTheHamburgerOnEveryLayerAndKeepsItOverTheOpenDrawer() {
        val phone = record(listOf(LayoutPanel("main")), UnusedPanel.Collapse, coverPanels = 0, unpinnedKeep = 1)
        val tree = WindowStack.drawer("account").pushFolderTree("root")
        val index = tree.pushMailboxIndex("INBOX")
        val settings = stack.pushScreen("settings")
        for (each in listOf(tree, index, stack, settings)) {
            val base = fill(phone, each)
            assertEquals(HamburgerPlan(slot = 0, overOpenDrawer = false), hamburgerPlan(base, base, null))
            val open = toggleDrawer(base, drawerLayer, null)
            val shown = displayedFill(phone, each, open)
            // Still on the same bar, and drawn again above the sheet so a second tap closes it.
            assertEquals(HamburgerPlan(slot = 0, overOpenDrawer = true), hamburgerPlan(base, shown, open))
            assertNull(toggleDrawer(base, drawerLayer, open))
        }
    }

    @Test
    fun multiPanelShowsTheHamburgerOnlyWhenNoPanelHasTheDrawer() {
        val pinned = record(
            listOf(LayoutPanel("side", WindowLayerKind.Drawer), LayoutPanel("main")),
            UnusedPanel.Collapse, coverPanels = 0, unpinnedKeep = 1,
        )
        val withDrawer = fill(pinned, stack)
        assertEquals(HamburgerPlan(slot = null, overOpenDrawer = false), hamburgerPlan(withDrawer, withDrawer, null))
        val unpinned = record(listOf(LayoutPanel("side"), LayoutPanel("main")), UnusedPanel.Collapse, 0, 2)
        val noDrawer = fill(unpinned, stack)
        assertEquals(HamburgerPlan(slot = 0, overOpenDrawer = false), hamburgerPlan(noDrawer, noDrawer, null))
    }

    @Test
    fun aScreenPushedOverAZoomIsShownAndOpeningItClearsTheZoom() {
        val twoPanel = record(listOf(LayoutPanel("side"), LayoutPanel("main")), UnusedPanel.StayEmpty, 0, 2)
        val zoomed = zoomTarget(fill(twoPanel, stack), stack.layers.last())!!
        val withSettings = stack.pushScreen("settings")
        val shown = displayedFill(twoPanel, withSettings, zoomed)
        assertTrue(shown.slots.any { !it.collapsed && it.layer == WindowLayer(WindowLayerKind.Screen, "settings") })
        assertEquals(zoomFill(zoomed), displayedFill(twoPanel, stack, zoomed))
        assertNull(zoomAfterScreenOpen(zoomed, "settings"))
        val screenZoom = ZoomState(WindowLayer(WindowLayerKind.Screen, "settings"), shown)
        assertEquals(screenZoom, zoomAfterScreenOpen(screenZoom, "settings"))
        // The drawer opened over Settings, then Settings again from the drawer: shown, not hidden.
        val phone = record(listOf(LayoutPanel("main")), UnusedPanel.Collapse, 0, 1)
        val open = toggleDrawer(fill(phone, stack), drawerLayer, null)
        assertEquals(
            WindowLayer(WindowLayerKind.Screen, "settings"),
            displayedFill(phone, withSettings, zoomAfterScreenOpen(open, "settings")).slots.single().layer,
        )
    }
}
