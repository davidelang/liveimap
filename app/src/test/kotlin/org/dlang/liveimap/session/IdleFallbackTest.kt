package org.dlang.liveimap.session

import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.pollIntervalSeconds
import org.dlang.liveimap.settings.slowerPoll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        assertEquals(300, idleFallbackSeconds(pollForNewMail = true, pollSeconds = 300, idleFallbackSeconds = 15))
        assertEquals(15, idleFallbackSeconds(pollForNewMail = false, pollSeconds = 600, idleFallbackSeconds = 15))
    }

    @Test
    fun neitherFlagFallsBackToSixtySeconds() {
        val settings = AccountSettings(
            pollForNewMail = false,
            forceSlowerFallbacks = false,
            pollSeconds = 600,
            idleFallbackSeconds = 60,
        )
        assertFalse(slowerPoll(settings))
        assertEquals(
            60,
            idleFallbackSeconds(slowerPoll(settings), pollIntervalSeconds(settings), settings.idleFallbackSeconds),
        )
    }

    @Test
    fun forcedFallbackUsesTheAccountPollInterval() {
        val polling = AccountSettings(pollForNewMail = true, pollSeconds = 300)
        assertTrue(slowerPoll(polling))
        assertEquals(300, idleFallbackSeconds(slowerPoll(polling), pollIntervalSeconds(polling), 60))
        val forced = AccountSettings(
            forceSlowerFallbacks = true,
            pollForNewMail = false,
            pollSeconds = 300,
        )
        assertTrue(slowerPoll(forced))
        assertEquals(300, idleFallbackSeconds(slowerPoll(forced), pollIntervalSeconds(forced), 60))
    }

    @Test
    fun indexWatchPassesSlowerPoll() {
        val index = listOf(
            "src/main/kotlin/org/dlang/liveimap/ui/index/MessageIndexScreen.kt",
            "app/src/main/kotlin/org/dlang/liveimap/ui/index/MessageIndexScreen.kt",
        ).map(::File).first { it.exists() }.readText()
        val call = index.substringAfter("idleFallbackSeconds(")
        assertTrue(call.trimStart().startsWith("slowerPoll(settings)"))
        assertTrue(call.contains("pollIntervalSeconds(settings)"))
    }
}
