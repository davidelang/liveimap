package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun settingRoundTripsAndDefaultIsNotWritten() {
        assertFalse(AccountSettings().encode().contains("readerTextZoom"))
        val hostile = AccountSettings().encode() + "readerTextZoom=130\n"
        val decoded = decodeAccountSettings(hostile)
        assertFalse(decoded.encode().contains("readerTextZoom"))
        assertEquals(AccountSettings().encode(), decoded.encode())
    }
}
