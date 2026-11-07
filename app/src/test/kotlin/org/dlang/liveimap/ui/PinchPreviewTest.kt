package org.dlang.liveimap.ui

import org.dlang.liveimap.settings.clampTextZoom
import org.junit.Assert.assertEquals
import org.junit.Test

class PinchPreviewTest {
    @Test fun previewTracksTheGestureAndMatchesTheCommit() {
        val s = AppZoomSession(100)
        assertEquals(100, s.preview())
        s.zoomBy(1.1f); s.zoomBy(1.1f)
        assertEquals(clampTextZoom(121), s.preview())
        assertEquals(s.preview(), s.commit(messageClaimed = false))
    }

    @Test fun previewIsClampedLikeTheCommit() {
        val s = AppZoomSession(100)
        repeat(40) { s.zoomBy(1.5f) }
        assertEquals(clampTextZoom(Int.MAX_VALUE / 2), s.preview())
    }

    @Test fun liveScaleIsPreviewOverLaidOut() {
        assertEquals(1f, livePreviewScale(120, null), 0f)
        assertEquals(1f, livePreviewScale(120, 120), 0f)
        assertEquals(1.5f, livePreviewScale(100, 150), 1e-6f)
        assertEquals(1f, livePreviewScale(0, 150), 0f)
    }
}
