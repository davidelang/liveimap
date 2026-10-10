package org.dlang.liveimap.engine

import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.TlsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCheckTest {
    private val base = AccountSettings(imapHost = "lab.test", imapPort = 143, tlsMode = TlsMode.StartTls)
    private val good = "IMAP4rev1 AUTH=PLAIN AUTH=SCRAM-SHA-256"

    @Test
    fun parsesProbeLines() {
        val probe = ServerProbe.parse(arrayOf("pre=IMAP4rev1 STARTTLS", "tls=dh key too small", "starttls=absent"))
        assertEquals("IMAP4rev1 STARTTLS", probe.pre)
        assertEquals("dh key too small", probe.tls)
        assertTrue(probe.startTlsAbsent)
        assertEquals("probe failed", ServerProbe.parse(null).error)
    }

    @Test
    fun cleanServerHasNoConflicts() {
        val check = checkServer(base, ServerProbe(pre = "IMAP4rev1 STARTTLS LOGINDISABLED", post = good))
        assertTrue(check.reachable)
        assertEquals(emptyList<String>(), check.conflicts)
    }

    @Test
    fun unreachableOffersSaveAnyway() {
        val check = checkServer(base, ServerProbe(error = "connection refused"))
        assertFalse(check.reachable)
        assertEquals(listOf("server not reachable: connection refused"), check.conflicts)
    }

    @Test
    fun startTlsNotOffered() {
        val check = checkServer(base, ServerProbe(pre = "IMAP4rev1 AUTH=PLAIN", startTlsAbsent = true))
        assertTrue(check.conflicts.first().startsWith("server does not offer STARTTLS"))
    }

    @Test
    fun oldTlsNeededIsNamed() {
        val probe = ServerProbe(pre = "IMAP4rev1 STARTTLS", tls = "dh key too small")
        assertEquals(
            listOf("server needs Allow old TLS and weak keys (dh key too small)"),
            checkServer(base, probe, oldTlsWorks = true).conflicts,
        )
        assertEquals(listOf("TLS failed: dh key too small"), checkServer(base, probe, oldTlsWorks = false).conflicts)
    }

    @Test
    fun certificateVerdicts() {
        val bad = checkServer(base, ServerProbe(post = good, trust = "name mismatch"))
        assertEquals(listOf("certificate fails validation: name mismatch"), bad.conflicts)
        val accepted = checkServer(base.copy(acceptInvalidCert = true), ServerProbe(post = good, accepted = "name mismatch"))
        assertTrue(accepted.conflicts.isEmpty())
        assertEquals(1, accepted.notes.size)
    }

    @Test
    fun plaintextRules() {
        val plain = base.copy(tlsMode = TlsMode.None)
        assertEquals(
            listOf("plaintext login disabled by the server; use STARTTLS or implicit TLS"),
            checkServer(plain, ServerProbe(pre = "IMAP4rev1 STARTTLS LOGINDISABLED")).conflicts,
        )
        assertEquals(1, checkServer(plain, ServerProbe(pre = "IMAP4rev1 AUTH=PLAIN")).conflicts.size)
        assertTrue(checkServer(plain, ServerProbe(pre = "IMAP4rev1 AUTH=CRAM-MD5")).conflicts.isEmpty())
        assertTrue(checkServer(plain.copy(allowPlaintextAuth = true), ServerProbe(pre = "IMAP4rev1")).conflicts.isEmpty())
    }

    @Test
    fun noSupportedMechanism() {
        val check = checkServer(base, ServerProbe(post = "IMAP4rev1 LOGINDISABLED AUTH=GSSAPI"))
        assertEquals(listOf("no supported login method (server offers: GSSAPI)"), check.conflicts)
    }
}
