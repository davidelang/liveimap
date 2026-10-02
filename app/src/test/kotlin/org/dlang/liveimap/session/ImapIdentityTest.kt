package org.dlang.liveimap.session

import org.dlang.liveimap.settings.AccountSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImapIdentityTest {
    @Test
    fun sameImapIdentity() {
        val base = AccountSettings(
            imapHost = "imap.example",
            imapPort = 143,
            username = "ada",
            smtpHost = "smtp.example",
            smtpPort = 25,
            displayName = "Ada",
        )
        assertTrue(sameImapIdentity(base, base.copy()))
        assertFalse(sameImapIdentity(base, base.copy(imapHost = "other.example")))
        assertFalse(sameImapIdentity(base, base.copy(imapPort = 993)))
        assertFalse(sameImapIdentity(base, base.copy(username = "bob")))
        assertFalse(sameImapIdentity(base, base.copy(smtpHost = "other.example")))
        assertFalse(sameImapIdentity(base, base.copy(smtpPort = 587)))
        assertTrue(sameImapIdentity(base, base.copy(displayName = "Augusta")))
    }
}
