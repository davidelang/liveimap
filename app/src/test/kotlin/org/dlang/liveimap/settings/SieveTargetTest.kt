package org.dlang.liveimap.settings

import org.dlang.liveimap.accounts.retargetCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SieveTargetTest {
    @Test
    fun sameAsInboundUsesImapLogin() {
        val settings = AccountSettings(
            imapHost = "imap.example.com",
            username = "ada",
            sievePort = 4190,
            sieveSameAsInbound = true,
            sieveHost = "ignored.example",
            sieveUsername = "ignored",
        )
        val login = sieveTarget(settings, "inbound-secret", null)
        assertEquals("imap.example.com", login.host)
        assertEquals(4190, login.port)
        assertEquals("ada", login.username)
        assertEquals("inbound-secret", login.password)
    }

    @Test
    fun jmapSameAsInboundUsesSessionHost() {
        val settings = AccountSettings(
            jmapFolders = true,
            imapHost = "https://mail.example/jmap",
            username = "ada",
            sieveSameAsInbound = true,
        )
        val login = sieveTarget(settings, "inbound-secret", null)
        assertEquals("mail.example", login.host)
        assertEquals("ada", login.username)
        assertEquals("inbound-secret", login.password)
    }

    @Test
    fun jmapFailureIsAMissingHost() {
        val settings = AccountSettings(jmapFolders = true, imapHost = "", sieveSameAsInbound = true)
        try {
            sieveTarget(settings, "pw", null)
            error("expected host")
        } catch (e: SieveTargetException) {
            assertEquals("host", e.kind)
        }
    }

    @Test
    fun ownHostKeepsTheSievePassword() {
        val settings = AccountSettings(
            sieveSameAsInbound = false,
            sieveHost = "sieve.example.com",
            sievePort = 4199,
            sieveUsername = "sue",
        )
        val login = sieveTarget(settings, null, "sieve-secret")
        assertEquals("sieve.example.com", login.host)
        assertEquals(4199, login.port)
        assertEquals("sue", login.username)
        assertEquals("sieve-secret", login.password)
        assertEquals("", sieveTarget(settings, null, "").password)
    }

    @Test
    fun blankHostOrMissingPassword() {
        val settings = AccountSettings(sieveSameAsInbound = false, sieveHost = "  ")
        try {
            sieveTarget(settings, "x", "y")
            error("expected host")
        } catch (e: SieveTargetException) {
            assertEquals("host", e.kind)
        }
        val set = settings.copy(sieveHost = "sieve.example.com")
        try {
            sieveTarget(set, "x", null)
            error("expected password")
        } catch (e: SieveTargetException) {
            assertEquals("password", e.kind)
        }
    }

    @Test
    fun sieveHostChangeDropsTheSievePassword() {
        val previous = AccountSettings(imapHost = "imap.example.com", sieveHost = "sieve.example.com", sieveSameAsInbound = false)
        val moved = retargetCredentials(previous, previous.copy(sieveHost = "other.example"))
        assertTrue(moved.dropSievePassword)
        assertFalse(moved.dropImapPassword)
        val imapMoved = retargetCredentials(previous, previous.copy(imapHost = "evil.example"))
        assertFalse(imapMoved.dropSievePassword)
        assertTrue(imapMoved.dropImapPassword)
    }
}
