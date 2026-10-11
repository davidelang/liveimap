package org.dlang.liveimap.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleFallbackTest {
    @Test
    fun droppedConnectionFallsBackToPolling() {
        assertTrue(idleDroppedByServer(IdleDroppedText))
        assertFalse(idleDroppedByServer("idle failed"))
        assertFalse(idleDroppedByServer("NO [LIMIT] too many IDLE sessions"))
        assertFalse(idleDroppedByServer(null))
    }

    @Test
    fun fallbackIntervalUsesPollSettingOrSixtySeconds() {
        assertEquals(300, idleFallbackSeconds(pollForNewMail = true, pollSeconds = 300))
        assertEquals(60, idleFallbackSeconds(pollForNewMail = false, pollSeconds = 300))
    }
}
