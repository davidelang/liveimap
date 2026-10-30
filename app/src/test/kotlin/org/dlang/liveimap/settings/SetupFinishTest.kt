package org.dlang.liveimap.settings

import org.dlang.liveimap.accounts.overlayAccountFields
import org.dlang.liveimap.accounts.userEmail
import org.dlang.liveimap.accounts.userImapHost
import org.dlang.liveimap.accounts.userImapPort
import org.dlang.liveimap.accounts.userSmtpHost
import org.dlang.liveimap.accounts.userSmtpPort
import org.dlang.liveimap.accounts.userUsername
import org.dlang.liveimap.engine.sieve.InboundRule
import org.dlang.liveimap.settings.editors.SetupFinishKind
import org.dlang.liveimap.settings.editors.existingSetupReady
import org.dlang.liveimap.settings.editors.planSetupFinish
import org.dlang.liveimap.settings.editors.wizardOntoLoaded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupFinishTest {
    @Test
    fun finishBeforeLoadWritesNothing() {
        assertFalse(existingSetupReady("account", null))
        assertTrue(existingSetupReady("account", newAccountSettings()))
        assertTrue(existingSetupReady(null, null))
    }

    @Test
    fun finishWritesWizardFieldsOntoTheLoadedRecord() {
        val rule = InboundRule(id = "rule-1", criteria = emptyList(), fileInto = "Archive")
        val loaded = newAccountSettings().copy(
            friendlyName = "Kept",
            email = "old@x",
            username = "old",
            jmapFolders = false,
            imapHost = "old-imap",
            imapPort = 143,
            smtpHost = "old-smtp",
            smtpPort = 25,
            smtpSameAsImap = false,
            clientCertAlias = "lab-kept",
            passwordStorage = PasswordStorageChoice.passwordManager,
            inboundRules = listOf(rule),
            signature = "sig",
        )
        val draft = newAccountSettings().copy(
            email = "new@host",
            username = "new",
            jmapFolders = true,
            imapHost = "imap.host",
            imapPort = 993,
            smtpHost = "smtp.host",
            smtpPort = 587,
            smtpSameAsImap = true,
            clientCertAlias = "wiped",
            passwordStorage = PasswordStorageChoice.none,
            signature = "draft",
        )
        val written = wizardOntoLoaded(loaded, draft)
        assertEquals(
            loaded.copy(
                email = "new@host",
                username = "new",
                jmapFolders = true,
                imapHost = "imap.host",
                imapPort = 993,
                smtpHost = "smtp.host",
                smtpPort = 587,
                smtpSameAsImap = true,
            ),
            written,
        )
        assertEquals(listOf(rule), written.inboundRules)
        assertEquals("lab-kept", written.clientCertAlias)
        assertEquals(PasswordStorageChoice.passwordManager, written.passwordStorage)
    }

    @Test
    fun finishDecisions() {
        val draft = newAccountSettings().copy(email = "new@host", username = "new", imapHost = "imap.host", imapPort = 993)
        val waiting = planSetupFinish("account", null, draft, false)
        assertEquals(SetupFinishKind.Nothing, waiting.kind)
        assertFalse(existingSetupReady("account", null))
        assertEquals(SetupFinishKind.Nothing, planSetupFinish("account", null, draft, true).kind)

        val overlay = overlayAccountFields(
            newAccountSettings(),
            mapOf(
                userImapHost to "imap.example",
                userImapPort to "993",
                userSmtpHost to "smtp.example",
                userSmtpPort to "587",
                userUsername to "ada",
                userEmail to "ada@example",
            ),
        )
        val missing = planSetupFinish("account", overlay, draft, true)
        assertEquals(SetupFinishKind.SaveOverlaid, missing.kind)
        assertEquals("account", missing.accountId)
        assertEquals(overlay, missing.settings)
        assertFalse(missing.rewritePasswords)

        val loaded = newAccountSettings().copy(friendlyName = "Kept", signature = "sig")
        val kept = planSetupFinish("account", loaded, draft, false)
        assertEquals(SetupFinishKind.SaveLoaded, kept.kind)
        assertEquals("account", kept.accountId)
        assertEquals(wizardOntoLoaded(loaded, draft), kept.settings)
        assertTrue(kept.rewritePasswords)

        val created = planSetupFinish(null, null, draft, false)
        assertEquals(SetupFinishKind.Create, created.kind)
        assertNull(created.accountId)
        assertEquals(draft, created.settings)
        assertTrue(created.rewritePasswords)
    }
}
