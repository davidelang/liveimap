package org.dlang.liveimap.settings

import org.dlang.liveimap.engine.sieve.InboundRule
import org.dlang.liveimap.settings.editors.existingSetupReady
import org.dlang.liveimap.settings.editors.wizardOntoLoaded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
