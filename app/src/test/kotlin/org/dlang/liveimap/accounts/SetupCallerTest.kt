package org.dlang.liveimap.accounts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F03 (David, 2026-10-12): setup only from our own app or a verified system AccountManager flow. */
class SetupCallerTest {
    @Test fun ownAppAlwaysAllowed() {
        assertTrue(setupCallerDecision("org.dlang.liveimap", "org.dlang.liveimap", verifiedToken = false))
    }

    @Test fun extrasAloneNeverEnough() {
        assertFalse(setupCallerDecision(null, "org.dlang.liveimap", verifiedToken = false))
        assertFalse(setupCallerDecision("com.android.settings", "org.dlang.liveimap", verifiedToken = false))
        assertFalse(setupCallerDecision("evil.app", "org.dlang.liveimap", verifiedToken = false))
    }

    @Test fun verifiedSystemFlowAllowed() {
        assertTrue(setupCallerDecision(null, "org.dlang.liveimap", verifiedToken = true))
    }

    @Test fun tokensOnlyForOwnOrSystemCallers() {
        assertTrue(setupTokenAllowed(10216, 10216, callerIsSystem = false))
        assertTrue(setupTokenAllowed(1000, 10216, callerIsSystem = true))
        assertFalse(setupTokenAllowed(10300, 10216, callerIsSystem = false))
        assertFalse(setupTokenAllowed(-1, 10216, callerIsSystem = true))
    }

    @Test fun tokensAreOneTimeAndExpire() {
        var now = 1_000L
        SetupTokens.clock = { now }
        val t = SetupTokens.issue()
        assertTrue(SetupTokens.consume(t))
        assertFalse(SetupTokens.consume(t))
        assertFalse(SetupTokens.consume("forged"))
        val late = SetupTokens.issue()
        now += 11 * 60 * 1000L
        assertFalse(SetupTokens.consume(late))
        SetupTokens.clock = { System.currentTimeMillis() }
    }
}
