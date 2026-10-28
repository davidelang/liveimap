package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GlobalSettingsAccessTest {
    @Before
    fun tree() {
        installSettingsTree(settingsTreeText())
    }

    @Test
    fun saveGlobalIsTheOnlyLineWriter() {
        val next = updateGlobalRecord("", "indexPageSize", "80")
        assertEquals("80", readUiLine(next, "indexPageSize"))
        assertEquals("90", readUiLine(withGlobalLine(next, "swipeThresholdPercent", "90"), "swipeThresholdPercent"))
        assertEquals("80", ui_indexPageSize(next))
    }
}
