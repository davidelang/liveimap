package org.dlang.liveimap.accounts

import java.io.File
import org.dlang.liveimap.settings.AccountSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F32/F87: a moved server never inherits the old server's credentials or pins. */
class RetargetCredentialsTest {
    private val old = AccountSettings(imapHost = "mail.lang.hm", smtpHost = "mail.lang.hm", certPin = "AA", sieveCertPin = "BB", clientCertAlias = "me")

    @Test
    fun imapHostChangeDropsPasswordPinsAndClientCert() {
        val r = retargetCredentials(old, old.copy(imapHost = "evil.example"))
        assertTrue(r.dropImapPassword)
        assertFalse(r.dropSmtpPassword)
        assertEquals("", r.settings.certPin)
        assertEquals("", r.settings.sieveCertPin)
        assertEquals("", r.settings.clientCertAlias)
    }

    @Test
    fun smtpHostChangeDropsSmtpPasswordOnly() {
        val r = retargetCredentials(old, old.copy(smtpHost = "relay.example"))
        assertFalse(r.dropImapPassword)
        assertTrue(r.dropSmtpPassword)
        assertEquals("AA", r.settings.certPin)
    }

    @Test
    fun sameHostOrCaseOrFirstSetupKeepsEverything() {
        assertFalse(retargetCredentials(old, old.copy(imapHost = "MAIL.lang.hm")).dropImapPassword)
        assertFalse(retargetCredentials(old, old.copy(displayName = "David")).dropSmtpPassword)
        assertFalse(retargetCredentials(AccountSettings(), old).dropImapPassword) // blank host before: first setup
        assertFalse(retargetCredentials(null, old).dropImapPassword)
    }

    @Test
    fun savePlanAppliesIt() {
        val moved = planAccountSave("id", old, old.copy(imapHost = "evil.example"), "", "slot")
        assertEquals("", moved.passwordSlot)
        assertEquals("", moved.settings.certPin)
        assertEquals("evil.example", moved.userData[userImapHost])
        val kept = planAccountSave("id", old, old.copy(displayName = "David"), "", "slot")
        assertEquals("slot", kept.passwordSlot)
        assertFalse(kept.dropSmtpPassword)
        assertTrue(planAccountSave("id", old, old.copy(smtpHost = "relay.example"), "", "slot").dropSmtpPassword)
    }

    @Test
    fun storeAndSettingsApplyIt() {
        val store = File("src/main/kotlin/org/dlang/liveimap/accounts/AccountStore.kt").readText()
        assertTrue(store.contains("planAccountSave(accountId, previous, settings, encoded, existing?.passwordSlot ?: \"\")"))
        assertTrue(store.contains("writeAccount(name, plan.userData, plan.passwordSlot)"))
        assertTrue(store.contains("if (plan.dropSmtpPassword) secretPrefs.edit().remove(smtpPasswordKeyFor(accountId)).commit()"))
        assertTrue(File("src/main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt").readText().contains("retargetCredentials(settings, next)"))
    }
}
