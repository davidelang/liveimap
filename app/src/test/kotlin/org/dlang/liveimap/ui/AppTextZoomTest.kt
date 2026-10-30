package org.dlang.liveimap.ui

import org.dlang.liveimap.settings.MaxTextZoom
import org.dlang.liveimap.settings.MinTextZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppTextZoomTest {
    @Test
    fun fontScaleIsSystemTimesAppZoom() {
        assertEquals(1.5f, scaledFontScale(1f, 150))
        assertEquals(2f, scaledFontScale(2f, 100))
        assertEquals(0.5f, scaledFontScale(1f, 50))
    }

    @Test
    fun pinchWritesOnceAtTheEndClamped() {
        val session = AppZoomSession(100)
        session.zoomBy(1.2f)
        session.zoomBy(2f)
        assertEquals(240, session.commit(consumed = false))
        val high = AppZoomSession(200)
        high.zoomBy(4f)
        assertEquals(MaxTextZoom, high.commit(consumed = false))
        val low = AppZoomSession(80)
        low.zoomBy(0.2f)
        assertEquals(MinTextZoom, low.commit(consumed = false))
    }

    @Test
    fun consumedReaderPinchWritesNothing() {
        val session = AppZoomSession(100)
        session.zoomBy(1.5f)
        assertNull(session.commit(consumed = true))
    }

    @Test
    fun settingsPinchWritesClampedWhenScrollConsumes() {
        val session = AppZoomSession(100)
        session.zoomBy(1.5f)
        assertNull(session.commit(consumed = true))
        assertEquals(150, session.commitForSettings(pinched = true))
        val high = AppZoomSession(250)
        high.zoomBy(2f)
        assertEquals(MaxTextZoom, high.commitForSettings(pinched = true))
        val low = AppZoomSession(60)
        low.zoomBy(0.5f)
        assertEquals(MinTextZoom, low.commitForSettings(pinched = true))
        val scroll = AppZoomSession(100)
        scroll.zoomBy(1.2f)
        assertNull(scroll.commitForSettings(pinched = false))
    }
}
