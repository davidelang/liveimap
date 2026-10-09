package org.dlang.liveimap.ui

import org.dlang.liveimap.settings.LayoutChoice
import org.dlang.liveimap.settings.MultiPane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneSplitTest {
    @Test
    fun wideOnlyWhenExpanded() {
        assertTrue(useMultiPane(MultiPane.Wide))
        assertFalse(useMultiPane(MultiPane.Off))
    }

    @Test
    fun phoneReaderStartsDrawerClosed() {
        assertEquals(0, initialDrawerDp(880, readerOpen = true))
        assertEquals(360, initialDrawerDp(1200, readerOpen = true))
        assertEquals(360, initialDrawerDp(880, readerOpen = false))
    }

    @Test
    fun folderPaneOnlyWhenOn() {
        assertTrue(useFolderPane(LayoutChoice.On))
        assertFalse(useFolderPane(LayoutChoice.Off))
    }

    @Test
    fun foldPostureOnlyWhenOn() {
        assertTrue(useFoldPosture(LayoutChoice.On))
        assertFalse(useFoldPosture(LayoutChoice.Off))
    }

    @Test
    fun folderListStaysForIndexAndReaderOnly() {
        assertFalse(showFolderPane(LayoutChoice.Off, "index/{mailbox}"))
        assertFalse(showFolderPane(LayoutChoice.On, "folders"))
        assertTrue(showFolderPane(LayoutChoice.On, "index/{mailbox}"))
        assertTrue(showFolderPane(LayoutChoice.On, "reader/{mailbox}/{uid}/{sequence}"))
        assertFalse(showFolderPane(LayoutChoice.On, "settings"))
    }

    @Test
    fun horizontalHingeStacksOnlyWhenFoldIsOn() {
        assertEquals(PaneAxis.SideBySide, paneAxis(false, false, true))
        assertEquals(PaneAxis.SideBySide, paneAxis(true, true, false))
        assertEquals(PaneAxis.SideBySide, paneAxis(true, true, true))
        assertEquals(PaneAxis.TopBottom, paneAxis(true, false, true))
    }
}
