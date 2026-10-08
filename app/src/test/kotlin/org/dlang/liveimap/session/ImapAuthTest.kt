package org.dlang.liveimap.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImapAuthTest {
    @Test
    fun scramSha256BeatsLaterAndPlus() {
        val line = "IMAP4rev1 AUTH=SCRAM-SHA-256-PLUS AUTH=PLAIN AUTH=CRAM-MD5 " +
            "AUTH=SCRAM-SHA-1 auth=scram-sha-256"
        assertEquals(
            "SCRAM-SHA-256",
            chooseImapAuth(line, tls = false, plaintextOk = false, channelBinding = false),
        )
    }

    @Test
    fun scramSha256PlusWithCramMd5ReturnsCramMd5() {
        assertEquals(
            "CRAM-MD5",
            chooseImapAuth(
                "AUTH=SCRAM-SHA-256-PLUS AUTH=CRAM-MD5",
                tls = false,
                plaintextOk = false,
                channelBinding = false,
            ),
        )
    }

    @Test
    fun digestMd5WithPlainOnTlsReturnsPlain() {
        assertEquals(
            "PLAIN",
            chooseImapAuth(
                "AUTH=DIGEST-MD5 AUTH=PLAIN",
                tls = true,
                plaintextOk = false,
                channelBinding = false,
            ),
        )
    }

    @Test
    fun plaintextPlainIsNullUntilPlaintextOk() {
        val line = "AUTH=PLAIN"
        assertNull(chooseImapAuth(line, tls = false, plaintextOk = false, channelBinding = false))
        assertEquals(
            "PLAIN",
            chooseImapAuth(line, tls = false, plaintextOk = true, channelBinding = false),
        )
    }

    @Test
    fun authLoginReturnsLoginOnTlsWhenPlainAbsent() {
        assertEquals(
            "LOGIN",
            chooseImapAuth("AUTH=login", tls = true, plaintextOk = false, channelBinding = false),
        )
    }

    @Test
    fun refusedMechanismsAreNeverReturned() {
        val line = "AUTH=DIGEST-MD5 AUTH=NTLM AUTH=GSSAPI AUTH=XOAUTH2 AUTH=OAUTHBEARER AUTH=EXTERNAL"
        assertNull(chooseImapAuth(line, tls = true, plaintextOk = true, channelBinding = false))
    }

    @Test
    fun scramSha256PlusBeatsScramWhenBound() {
        assertEquals(
            "SCRAM-SHA-256-PLUS",
            chooseImapAuth(
                "AUTH=SCRAM-SHA-256-PLUS AUTH=SCRAM-SHA-256 AUTH=PLAIN",
                tls = true,
                plaintextOk = false,
                channelBinding = true,
            ),
        )
    }

    @Test
    fun scramSha1PlusBeatsCramWhenBound() {
        assertEquals(
            "SCRAM-SHA-1-PLUS",
            chooseImapAuth(
                "AUTH=SCRAM-SHA-1-PLUS AUTH=CRAM-MD5",
                tls = true,
                plaintextOk = false,
                channelBinding = true,
            ),
        )
    }

    @Test
    fun plusSkippedWithoutTlsEvenWhenBound() {
        assertEquals(
            "SCRAM-SHA-256",
            chooseImapAuth(
                "AUTH=SCRAM-SHA-256-PLUS AUTH=SCRAM-SHA-256",
                tls = false,
                plaintextOk = false,
                channelBinding = true,
            ),
        )
    }

    @Test
    fun unknownPlusSkippedWhenBound() {
        assertEquals(
            "CRAM-MD5",
            chooseImapAuth(
                "AUTH=FOO-PLUS AUTH=CRAM-MD5",
                tls = true,
                plaintextOk = false,
                channelBinding = true,
            ),
        )
    }
}
