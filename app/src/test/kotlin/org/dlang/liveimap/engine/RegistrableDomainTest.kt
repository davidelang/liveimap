package org.dlang.liveimap.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F52 reviewer condition: provider match by PSL registrable domain. */
class RegistrableDomainTest {
    @Test fun registrable() {
        assertEquals("example.com", PublicSuffix.registrableDomain("smtp.example.com"))
        assertEquals("example.co.uk", PublicSuffix.registrableDomain("smtp.example.co.uk"))
        assertEquals("evil.co.uk", PublicSuffix.registrableDomain("evil.co.uk"))
        assertNull(PublicSuffix.registrableDomain("co.uk"))
        assertNull(PublicSuffix.registrableDomain("192.168.1.5"))
        assertEquals("someone.github.io", PublicSuffix.registrableDomain("mail.someone.github.io"))
    }

    @Test fun reuse() {
        assertTrue(smtpMayReuseImapLogin("imap.example.com", "smtp.example.com"))
        assertTrue(smtpMayReuseImapLogin("imap.example.co.uk", "smtp.example.co.uk"))
        assertFalse(smtpMayReuseImapLogin("smtp.example.co.uk", "evil.co.uk"))
        assertFalse(smtpMayReuseImapLogin("a.co.uk", "b.co.uk"))
        assertFalse(smtpMayReuseImapLogin("imap.example.com", "relay.other.net"))
        assertFalse(smtpMayReuseImapLogin("alice.github.io", "mallory.github.io"))
        assertTrue(smtpMayReuseImapLogin("10.0.0.5", "10.0.0.5"))
        assertFalse(smtpMayReuseImapLogin("10.0.0.5", "10.0.0.6"))
    }
}
