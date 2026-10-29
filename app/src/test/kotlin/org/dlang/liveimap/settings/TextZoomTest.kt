package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextZoomTest {
    @Test
    fun effectiveZoomClamps() {
        assertEquals(100, effectiveTextZoom(100, 1f))
        assertEquals(150, effectiveTextZoom(100, 1.5f))
        assertEquals(MaxTextZoom, effectiveTextZoom(250, 4f))
        assertEquals(MinTextZoom, effectiveTextZoom(75, 0.1f))
        assertEquals(100, effectiveTextZoom(100, Float.NaN))
        assertEquals(MaxTextZoom, effectiveTextZoom(10_000, 1f))
    }

    @Test
    fun settingRoundTripsAndOldKeyStaysUnknown() {
        val text = Settings(appTextZoom = 130).encodeUi()
        assertTrue(text.contains("appTextZoom=130"))
        assertEquals(130, decodeSettings(text).appTextZoom)
        val hostile = Settings().encode() + "readerTextZoom=130\n"
        val decoded = decodeSettings(hostile)
        assertEquals(Settings().appTextZoom, decoded.appTextZoom)
        assertTrue(decoded.unknownSettings.any { it.startsWith("readerTextZoom=") })
        assertFalse(decoded.encode().contains("appTextZoom=130"))
    }
}
