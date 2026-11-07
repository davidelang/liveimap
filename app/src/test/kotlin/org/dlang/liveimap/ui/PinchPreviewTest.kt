package org.dlang.liveimap.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PinchPreviewTest {
    /** What the user sees while pinching is what lands when the fingers lift, at every step and at the limits. */
    @Test fun thePreviewIsWhatTheLiftCommits() {
        val s = AppZoomSession(100)
        for (step in listOf(1.2f, 1.2f, 0.5f, 3f, 3f, 3f, 0.01f)) {
            s.zoomBy(step)
            assertEquals(s.preview(), s.commit(messageClaimed = false))
        }
    }

    @Test fun theLiveScaleTimesTheLaidOutSizeIsThePreview() {
        val s = AppZoomSession(120)
        s.zoomBy(1.5f)
        assertEquals(s.preview().toFloat(), 120 * livePreviewScale(120, s.preview()), 0.01f)
        assertEquals(1f, livePreviewScale(120, null), 0f)
    }

    @Test fun aMessagePinchCommitsNothing() {
        val s = AppZoomSession(100)
        s.zoomBy(2f)
        assertNull(s.commit(messageClaimed = true))
    }
}
