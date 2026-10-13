package org.dlang.liveimap.accounts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupCallerTest {
    private val own = "org.dlang.liveimap"

    @Test fun plainStartActivityIsRefused() {
        // getCallingPackage() is null for a plain startActivity: the activity finishes before sendResult.
        assertFalse(setupCallerDecision(null, own, hasResponse = false) { true })
    }

    @Test fun settingsAddAccountWithResponseAllowed() {
        // Settings > Add account: plain startActivity (null caller) carrying the AccountManager response.
        assertTrue(setupCallerDecision(null, own, hasResponse = true) { false })
    }

    @Test fun foreignAppIsRefused() {
        assertFalse(setupCallerDecision("com.evil.app", own, hasResponse = true) { false })
    }

    @Test fun settingsAndSelfAllowed() {
        assertTrue(setupCallerDecision("com.android.settings", own, hasResponse = false) { it == "com.android.settings" })
        assertTrue(setupCallerDecision(own, own, hasResponse = false) { false })
    }
}
