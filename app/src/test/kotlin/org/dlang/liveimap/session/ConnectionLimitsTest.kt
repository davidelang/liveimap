package org.dlang.liveimap.session

import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.decodeAccountSettings
import org.dlang.liveimap.settings.encode
import org.junit.Assert.assertEquals
import org.junit.Test

/** David's per-account connection limit: sensible default, hard ceiling, never unlimited; spillover is STATUS. */
class ConnectionLimitsTest {
    @Test
    fun defaultLeavesRoomForSearchAndTwoExtraIdleFolders() {
        val limits = connectionLimits(AccountSettings())
        assertEquals(ConnectionLimits(DefaultMaxConnections, searchConnection = true, idleSlots = 2), limits)
        assertEquals(true, limits.total <= 10) // under Dovecot's default per-user/IP limit
        // A limit of 8 with the IDLE ceiling yields five slots. The stored budget still caps a new account at two.
        assertEquals(
            ConnectionLimits(DefaultMaxConnections, searchConnection = true, idleSlots = 5),
            connectionLimits(DefaultMaxConnections, MaxExtraIdle),
        )
    }

    @Test
    fun limitIsClampedAndNeverUnlimited() {
        assertEquals(MaxConnectionsCeiling, connectionLimits(Int.MAX_VALUE, MaxExtraIdle).total)
        assertEquals(MinConnections, connectionLimits(0, 8).total)
        assertEquals(MinConnections, connectionLimits(-1, 8).total)
        assertEquals(null, maxConnectionsFromText("0"))
        assertEquals(null, maxConnectionsFromText("1"))
        assertEquals(null, maxConnectionsFromText("21"))
        assertEquals(null, maxConnectionsFromText("unlimited"))
        assertEquals(null, maxConnectionsFromText("-1"))
        assertEquals(5, maxConnectionsFromText(" 5 "))
    }

    @Test
    fun smallLimitsDropSearchThenIdle() {
        assertEquals(ConnectionLimits(2, false, 0), connectionLimits(2, 8))
        assertEquals(ConnectionLimits(3, true, 0), connectionLimits(3, 8))
        assertEquals(ConnectionLimits(5, true, 2), connectionLimits(5, 8))
        assertEquals(ConnectionLimits(20, true, 8), connectionLimits(20, 8))
        assertEquals(ConnectionLimits(20, true, 3), connectionLimits(20, 3)) // the IDLE budget still caps
    }

    @Test
    fun foldersBeyondTheLimitFallBackToStatus() {
        val settings = AccountSettings(watchedFolders = listOf("A", "B", "C", "D"), maxConnections = 5, extraIdleBudget = 8)
        val plan = extraWatchPlan(settings)
        assertEquals(listOf("A", "B"), plan.idle)
        assertEquals(listOf("C", "D"), plan.status)
    }

    @Test
    fun storedValuesRoundTripAndClamp() {
        val text = AccountSettings(maxConnections = 4).encode()
        assertEquals(true, text.contains("maxConnections=4"))
        assertEquals(4, decodeAccountSettings(text).maxConnections)
        assertEquals(false, AccountSettings().encode().contains("maxConnections="))
        val huge = AccountSettings().encode().trimEnd() + "\nmaxConnections=9999\n"
        assertEquals(MaxConnectionsCeiling, decodeAccountSettings(huge).maxConnections)
    }
}
