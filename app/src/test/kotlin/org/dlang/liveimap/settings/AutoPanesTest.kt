package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoPanesTest {
    private fun inch(w: Double, h: Double, font: Float = 1f) = planLayout((w * 160).toInt(), (h * 160).toInt(), font)

    @Test fun previewComesFromSize() {
        assertEquals(PreviewSize.None, planLayout(227, 227, 1f).preview)
        val wide = inch(8.0, 6.0)
        assertTrue(wide.columns > 0)
        assertTrue(wide.lines > 0)
        assertTrue(wide.preview == PreviewSize.Small || wide.preview == PreviewSize.Large)
    }

    @Test fun absentLayoutKeysStayOutOfTheDocument() {
        val stored = Settings().encode()
        for (key in listOf("multiPane", "folderPlace", "foldPosture", "paneArrangement", "customLayout")) {
            assertFalse(stored.contains("\"$key\""))
        }
        assertEquals("", decodeSettings(stored).layoutCatalog)
        assertEquals("", newAccountSettings().layoutCatalog)
    }
}
