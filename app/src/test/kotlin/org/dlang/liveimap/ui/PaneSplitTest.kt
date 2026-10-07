package org.dlang.liveimap.ui

import org.dlang.liveimap.settings.MultiPane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneSplitTest {
    @Test
    fun wideOnlyWhenExpanded() {
        assertTrue(useMultiPane(MultiPane.Wide, true))
        assertFalse(useMultiPane(MultiPane.Wide, false))
        assertFalse(useMultiPane(MultiPane.Off, true))
    }

    @Test
    fun phoneReaderStartsDrawerClosed() {
        assertEquals(0, initialDrawerDp(880, readerOpen = true))
        assertEquals(360, initialDrawerDp(1200, readerOpen = true))
        assertEquals(360, initialDrawerDp(880, readerOpen = false))
    }
}
