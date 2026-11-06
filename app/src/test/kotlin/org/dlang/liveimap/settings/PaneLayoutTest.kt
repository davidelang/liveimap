package org.dlang.liveimap.settings

import org.dlang.liveimap.window.LayoutAutomatic
import org.dlang.liveimap.window.LayoutCatalog
import org.dlang.liveimap.window.LayoutCriterionExternal
import org.dlang.liveimap.window.LayoutCriterionLandscape
import org.dlang.liveimap.window.LayoutCriterionLeft
import org.dlang.liveimap.window.LayoutCriterionPortrait
import org.dlang.liveimap.window.LayoutDrawerPinned
import org.dlang.liveimap.window.LayoutFolderTreePinned
import org.dlang.liveimap.window.LayoutIndexBesideMessage
import org.dlang.liveimap.window.LayoutListAboveMessage
import org.dlang.liveimap.window.LayoutNode
import org.dlang.liveimap.window.LayoutRotationLeft
import org.dlang.liveimap.window.LayoutRotationRight
import org.dlang.liveimap.window.LayoutRotationUpright
import org.dlang.liveimap.window.LayoutRotationUpside
import org.dlang.liveimap.window.LayoutRule
import org.dlang.liveimap.window.LayoutSituation
import org.dlang.liveimap.window.LayoutPanel
import org.dlang.liveimap.window.LayoutRecord
import org.dlang.liveimap.window.LayoutRecordProblem
import org.dlang.liveimap.window.SplitDirection
import org.dlang.liveimap.window.UnusedPanel
import org.dlang.liveimap.window.WindowLayerKind
import org.dlang.liveimap.window.WindowStack
import org.dlang.liveimap.window.addRule
import org.dlang.liveimap.window.decodeLayoutCatalog
import org.dlang.liveimap.window.delete
import org.dlang.liveimap.window.editFactory
import org.dlang.liveimap.window.encodeLayoutCatalog
import org.dlang.liveimap.window.factoryLayoutIds
import org.dlang.liveimap.window.factoryRecord
import org.dlang.liveimap.window.moveRuleEarlier
import org.dlang.liveimap.window.removeRule
import org.dlang.liveimap.window.fill
import org.dlang.liveimap.window.layoutRecordProblem
import org.dlang.liveimap.window.offeredIds
import org.dlang.liveimap.window.resolve
import org.dlang.liveimap.window.restoreDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun noRulesUseTheSelectedLayoutEverywhereAndEncodeAsBefore() {
        val selected = LayoutCatalog(selectedId = LayoutListAboveMessage)
        val empty = LayoutCatalog()
        for (situation in everySituation()) {
            assertEquals(factoryRecord(LayoutListAboveMessage), selected.resolve(situation))
            assertEquals(factoryRecord(LayoutAutomatic), empty.resolve(situation))
        }
        val stored = LayoutCatalog(
            selectedId = LayoutListAboveMessage,
            removedFactories = setOf(LayoutDrawerPinned),
            custom = listOf(org.dlang.liveimap.window.CustomLayout("c1", factoryRecord(LayoutAutomatic)!!)),
        )
        val text = encodeLayoutCatalog(stored)
        assertFalse(text.lineSequence().any { it.startsWith("r ") })
        assertEquals(stored, decodeLayoutCatalog(text))
    }

    @Test
    fun theMostSpecificEarlierRuleWinsAndADeletedLayoutIsSkipped() {
        val upright = LayoutSituation(LayoutRotationUpright, portrait = true, builtIn = true)
        val left = LayoutSituation(LayoutRotationLeft, portrait = false, builtIn = true)
        val right = LayoutSituation(LayoutRotationRight, portrait = false, builtIn = true)
        val upside = LayoutSituation(LayoutRotationUpside, portrait = true, builtIn = true)
        val externalLeft = LayoutSituation(LayoutRotationLeft, portrait = false, builtIn = false)

        val portrait = LayoutCatalog(selectedId = LayoutDrawerPinned)
            .addRule(LayoutCriterionPortrait, LayoutListAboveMessage)
        assertEquals(factoryRecord(LayoutListAboveMessage), portrait.resolve(upright))
        assertEquals(factoryRecord(LayoutDrawerPinned), portrait.resolve(left))

        val turned = portrait.addRule(LayoutCriterionLandscape, LayoutIndexBesideMessage)
            .addRule(LayoutCriterionLeft, LayoutFolderTreePinned)
        assertEquals(factoryRecord(LayoutFolderTreePinned), turned.resolve(left))
        assertEquals(factoryRecord(LayoutIndexBesideMessage), turned.resolve(right))
        assertEquals(factoryRecord(LayoutListAboveMessage), turned.resolve(upside))

        val external = turned.addRule(LayoutCriterionExternal, LayoutAutomatic)
        assertEquals(factoryRecord(LayoutAutomatic), external.resolve(externalLeft))
        assertEquals(factoryRecord(LayoutFolderTreePinned), external.resolve(left))

        var sideways = LayoutCatalog(selectedId = LayoutAutomatic)
            .addRule(LayoutCriterionLandscape, LayoutListAboveMessage)
            .addRule(LayoutCriterionLandscape, LayoutIndexBesideMessage)
        assertEquals(factoryRecord(LayoutListAboveMessage), sideways.resolve(right))
        sideways = sideways.moveRuleEarlier(1)
        assertEquals(factoryRecord(LayoutIndexBesideMessage), sideways.resolve(right))
        sideways = sideways.removeRule(0)
        assertEquals(factoryRecord(LayoutListAboveMessage), sideways.resolve(left))
        sideways = sideways.removeRule(0)
        assertEquals(factoryRecord(LayoutAutomatic), sideways.resolve(right))

        val gone = LayoutCatalog(
            selectedId = LayoutAutomatic,
            removedFactories = setOf(LayoutListAboveMessage),
            rules = listOf(
                LayoutRule(LayoutCriterionPortrait, LayoutListAboveMessage),
                LayoutRule(LayoutCriterionPortrait, LayoutIndexBesideMessage),
            ),
        )
        assertEquals(factoryRecord(LayoutIndexBesideMessage), gone.resolve(upright))
        val onlyGone = gone.removeRule(1)
        assertEquals(factoryRecord(LayoutAutomatic), onlyGone.resolve(upright))
    }

    @Test
    fun anUnknownCriterionRoundTripsAndDoesNotWin() {
        val catalog = LayoutCatalog(
            selectedId = LayoutIndexBesideMessage,
            rules = listOf(
                LayoutRule("folded", LayoutListAboveMessage),
                LayoutRule(LayoutCriterionPortrait, LayoutFolderTreePinned),
            ),
        )
        val decoded = decodeLayoutCatalog(encodeLayoutCatalog(catalog))
        assertEquals(catalog, decoded)
        val upright = LayoutSituation(LayoutRotationUpright, portrait = true, builtIn = true)
        val left = LayoutSituation(LayoutRotationLeft, portrait = false, builtIn = true)
        assertEquals(factoryRecord(LayoutFolderTreePinned), decoded.resolve(upright))
        assertEquals(factoryRecord(LayoutIndexBesideMessage), decoded.resolve(left))
        val onlyUnknown = catalog.removeRule(1)
        assertEquals(factoryRecord(LayoutIndexBesideMessage), onlyUnknown.resolve(upright))
        assertEquals(listOf(LayoutRule("folded", LayoutListAboveMessage)), decodeLayoutCatalog(encodeLayoutCatalog(onlyUnknown)).rules)
    }

    private fun everySituation(): List<LayoutSituation> = listOf(
        LayoutSituation(LayoutRotationUpright, true, true),
        LayoutSituation(LayoutRotationLeft, false, true),
        LayoutSituation(LayoutRotationUpside, true, true),
        LayoutSituation(LayoutRotationRight, false, true),
        LayoutSituation(LayoutRotationLeft, false, false),
        LayoutSituation(LayoutRotationUpright, null, false),
    )
}
