package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerCheckStepTest {
    private val a = AccountSettings(imapHost = "10.0.0.1", imapPort = 143)
    private val b = AccountSettings(imapHost = "10.2.3.133", imapPort = 33550)

    @Test fun switchingAccountsNeverChecks() {
        assertEquals(ServerCheckStep.Rebase, serverCheckStep(a, "acct-a", b, "acct-b", userSaved = false, editing = false))
        assertEquals(ServerCheckStep.Rebase, serverCheckStep(a, "acct-a", b, "acct-b", userSaved = true, editing = false))
    }

    @Test fun openingOrLoadingNeverChecks() {
        assertEquals(ServerCheckStep.Rebase, serverCheckStep(null, null, b, "acct-b", userSaved = false, editing = false))
        assertEquals(ServerCheckStep.Nothing, serverCheckStep(b, "acct-b", b, "acct-b", userSaved = false, editing = false))
        assertEquals(ServerCheckStep.Nothing, serverCheckStep(b, "acct-b", b.copy(displayName = "x"), "acct-b", userSaved = true, editing = false))
    }

    @Test fun nonUserChangeIsAdopted() {
        // e.g. Revert, or the store reloading the same account
        assertEquals(ServerCheckStep.Rebase, serverCheckStep(a, "acct-a", a.copy(imapPort = 993), "acct-a", userSaved = false, editing = false))
    }

    @Test fun waitsWhileStillTyping() {
        val hostTyped = a.copy(imapHost = "127.0.0.1")
        assertEquals(ServerCheckStep.Wait, serverCheckStep(a, "acct-a", hostTyped, "acct-a", userSaved = true, editing = true))
        assertEquals(ServerCheckStep.Check, serverCheckStep(a, "acct-a", hostTyped.copy(imapPort = 28918), "acct-a", userSaved = true, editing = false))
    }

    @Test fun blankHostNeverChecks() {
        assertEquals(ServerCheckStep.Rebase, serverCheckStep(a, "acct-a", a.copy(imapHost = ""), "acct-a", userSaved = true, editing = false))
    }
}
