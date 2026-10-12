package org.dlang.liveimap.ui.reader

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PinchZoomTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun twoFingerPinchZoomsOneFingerScrolls() {
        var zoom = 1f
        val scroll = androidx.compose.foundation.ScrollState(0)
        rule.setContent {
            Box(Modifier.size(300.dp).testTag("t").pinchToZoom { zoom *= it }) {
                Column(Modifier.verticalScroll(scroll)) { repeat(80) { Text("line $it") } }
            }
        }
        rule.onNodeWithTag("t").performTouchInput { swipeUp(startY = bottom - 10f, endY = top + 10f, durationMillis = 300) }
        rule.waitForIdle()
        assertEquals(1f, zoom)
        assertTrue("one-finger drag scrolls", scroll.value > 0)
        rule.onNodeWithTag("t").performTouchInput {
            pinch(center - Offset(20f, 0f), center - Offset(200f, 0f), center + Offset(20f, 0f), center + Offset(200f, 0f))
        }
        rule.waitForIdle()
        assertTrue("pinch out zooms in: $zoom", zoom > 2f)
    }
}
