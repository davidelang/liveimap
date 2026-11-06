package org.dlang.liveimap.settings

import org.dlang.liveimap.window.LayoutAutomatic
import org.dlang.liveimap.window.LayoutCatalog
import org.dlang.liveimap.window.LayoutDrawerPinned
import org.dlang.liveimap.window.LayoutFolderTreePinned
import org.dlang.liveimap.window.LayoutIndexBesideMessage
import org.dlang.liveimap.window.LayoutListAboveMessage
import org.dlang.liveimap.window.LayoutNode
import org.dlang.liveimap.window.LayoutPanel
import org.dlang.liveimap.window.LayoutRecord
import org.dlang.liveimap.window.LayoutRecordProblem
import org.dlang.liveimap.window.SplitDirection
import org.dlang.liveimap.window.UnusedPanel
import org.dlang.liveimap.window.WindowLayerKind
import org.dlang.liveimap.window.WindowStack
import org.dlang.liveimap.window.decodeLayoutCatalog
import org.dlang.liveimap.window.delete
import org.dlang.liveimap.window.editFactory
import org.dlang.liveimap.window.encodeLayoutCatalog
import org.dlang.liveimap.window.factoryLayoutIds
import org.dlang.liveimap.window.factoryRecord
import org.dlang.liveimap.window.fill
import org.dlang.liveimap.window.layoutRecordProblem
import org.dlang.liveimap.window.offeredIds
import org.dlang.liveimap.window.resolve
import org.dlang.liveimap.window.restoreDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneLayoutTest {
    private val stack = WindowStack.drawer("account")
        .pushFolderTree("root")
        .pushMailboxIndex("INBOX")
        .pushMessage("4", WindowLayerKind.FolderIndex)

    @Test
    fun factoriesRoundTripAndAreTheSameKindAsCustom() {
        assertEquals(5, factoryLayoutIds.size)
        for (id in factoryLayoutIds) {
            val record = factoryRecord(id)!!
            val catalog = LayoutCatalog(custom = listOf(org.dlang.liveimap.window.CustomLayout("c1", record)))
            val decoded = decodeLayoutCatalog(encodeLayoutCatalog(catalog))
            assertEquals(record, decoded.custom.single().record)
            assertNull(layoutRecordProblem(record))
        }
    }

    @Test
    fun messageWithTheIndexOffScreenIsValidAndTwoPanelsOfOneLayerAreNot() {
        val messageOnly = LayoutRecord(
            LayoutNode.Panel(LayoutPanel("message", WindowLayerKind.Message)),
            UnusedPanel.Collapse,
            coverPanels = 0,
            unpinnedKeep = 0,
        )
        assertNull(layoutRecordProblem(messageOnly))
        val filled = fill(messageOnly, stack)
        assertEquals(WindowLayerKind.Message, filled.slots.single().layer?.kind)
        assertTrue(filled.slots.none { it.layer?.kind == WindowLayerKind.FolderIndex })

        val duplicate = LayoutRecord(
            LayoutNode.Split(
                SplitDirection.Beside,
                0.5f,
                LayoutNode.Panel(LayoutPanel("a", WindowLayerKind.Message)),
                LayoutNode.Panel(LayoutPanel("b", WindowLayerKind.Message)),
            ),
            UnusedPanel.StayEmpty,
            coverPanels = 0,
            unpinnedKeep = 0,
        )
        assertEquals(LayoutRecordProblem.DuplicateLayer, layoutRecordProblem(duplicate))
    }

    @Test
    fun catalogKeepsCustomRecordsAndOneSelectedId() {
        val empty = LayoutCatalog()
        assertEquals(factoryRecord(LayoutAutomatic), empty.resolve())
        val top = fill(empty.resolve(), stack).slots.single()
        assertEquals(stack.layers.last(), top.layer)
        assertEquals(false, top.collapsed)

        val selected = LayoutCatalog(selectedId = LayoutListAboveMessage)
        assertEquals(factoryRecord(LayoutListAboveMessage), selected.resolve())
        assertEquals(selected.resolve(), LayoutCatalog(selectedId = LayoutListAboveMessage).resolve())

        val edited = LayoutCatalog().editFactory(LayoutIndexBesideMessage)
        assertEquals(factoryRecord(LayoutIndexBesideMessage), factoryRecord(LayoutIndexBesideMessage))
        assertEquals(factoryRecord(LayoutIndexBesideMessage), edited.custom.single().record)
        assertNotEquals(LayoutIndexBesideMessage, edited.selectedId)
        assertTrue(LayoutIndexBesideMessage in edited.offeredIds())

        val removed = edited.delete(LayoutIndexBesideMessage)
        assertEquals("", removed.delete(edited.selectedId).selectedId)
        val deletedSelected = LayoutCatalog(selectedId = LayoutDrawerPinned).delete(LayoutDrawerPinned)
        assertEquals("", deletedSelected.selectedId)
        assertEquals(factoryRecord(LayoutAutomatic), deletedSelected.resolve())
        assertTrue(LayoutDrawerPinned in deletedSelected.removedFactories)

        val custom = deletedSelected.editFactory(LayoutFolderTreePinned)
        val restored = custom.restoreDefaults()
        assertTrue(restored.removedFactories.isEmpty())
        assertEquals(custom.custom, restored.custom)
        assertTrue(LayoutDrawerPinned in restored.offeredIds())

        assertEquals(factoryRecord(LayoutAutomatic), LayoutCatalog(selectedId = "missing").resolve())
    }
}
