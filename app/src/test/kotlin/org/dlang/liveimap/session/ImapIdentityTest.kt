package org.dlang.liveimap.session

import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.TlsMode
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
        assertTrue(sameImapIdentity(base, base.copy(), "secret", "secret"))
        assertFalse(sameImapIdentity(base, base.copy(imapHost = "other.example"), "secret", "secret"))
        assertFalse(sameImapIdentity(base, base.copy(imapPort = 993), "secret", "secret"))
        assertFalse(sameImapIdentity(base, base.copy(tlsMode = TlsMode.StartTls), "secret", "secret"))
        assertFalse(sameImapIdentity(base, base.copy(certPin = "ab"), "secret", "secret"))
        assertFalse(sameImapIdentity(base, base.copy(username = "bob"), "secret", "secret"))
        assertFalse(sameImapIdentity(base, base.copy(smtpHost = "other.example"), "secret", "secret"))
        assertFalse(sameImapIdentity(base, base.copy(smtpPort = 587), "secret", "secret"))
        assertTrue(sameImapIdentity(base, base.copy(displayName = "Augusta"), "secret", "secret"))
        assertFalse(sameImapIdentity(base, base.copy(), "secret", "changed"))
    }
}
