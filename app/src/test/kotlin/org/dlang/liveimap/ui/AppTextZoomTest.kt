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
        assertEquals(240, session.commit(messageClaimed = false))
        val high = AppZoomSession(200)
        high.zoomBy(4f)
        assertEquals(MaxTextZoom, high.commit(messageClaimed = false))
        val low = AppZoomSession(80)
        low.zoomBy(0.2f)
        assertEquals(MinTextZoom, low.commit(messageClaimed = false))
    }

    @Test
    fun consumedReaderPinchWritesNothing() {
        val session = AppZoomSession(100)
        session.zoomBy(1.5f)
        assertNull(session.commit(messageClaimed = true))
    }

    @Test
    fun scrollConsumedPinchStillWritesClamped() {
        val session = AppZoomSession(100)
        session.zoomBy(1.5f)
        assertEquals(150, session.commit(messageClaimed = false))
        val high = AppZoomSession(250)
        high.zoomBy(2f)
        assertEquals(MaxTextZoom, high.commit(messageClaimed = false))
        val low = AppZoomSession(60)
        low.zoomBy(0.5f)
        assertEquals(MinTextZoom, low.commit(messageClaimed = false))
    }

    @Test
    fun messageClaimIsSeparateFromAScrollConsume() {
        MessagePinchClaim.clear()
        try {
            val claimed = AppZoomSession(100)
            claimed.zoomBy(1.4f)
            MessagePinchClaim.claim()
            assertNull(claimed.commit(MessagePinchClaim.read()))
        } finally {
            MessagePinchClaim.clear()
        }
        val scrolled = AppZoomSession(100)
        scrolled.zoomBy(1.4f)
        assertEquals(140, scrolled.commit(MessagePinchClaim.read()))
    }
}
