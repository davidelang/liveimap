package org.dlang.liveimap.session

import org.junit.Assert.assertEquals
import org.junit.Test

class ImapAuthExternalTest {
    private val caps = "IMAP4rev1 AUTH=PLAIN AUTH=SCRAM-SHA-256 AUTH=SCRAM-SHA-256-PLUS AUTH=EXTERNAL"

    @Test
    fun externalFirstWhenCertPresentedOverTls() {
        assertEquals("EXTERNAL", chooseImapAuth(caps, tls = true, plaintextOk = false, channelBinding = true, clientCert = true))
    }

    @Test
    fun noCertKeepsScram() {
        assertEquals("SCRAM-SHA-256-PLUS", chooseImapAuth(caps, tls = true, plaintextOk = false, channelBinding = true))
    }

    @Test
    fun certWithoutTlsNeverExternal() {
        assertEquals("SCRAM-SHA-256", chooseImapAuth(caps, tls = false, plaintextOk = false, channelBinding = false, clientCert = true))
    }

    @Test
    fun certButServerWithoutExternalUsesPassword() {
        assertEquals(
            "SCRAM-SHA-256",
            chooseImapAuth("AUTH=PLAIN AUTH=SCRAM-SHA-256", tls = true, plaintextOk = false, channelBinding = false, clientCert = true),
        )
    }

    @Test
    fun externalNeedsClientLibrary() {
        assertEquals(
            "SCRAM-SHA-256",
            chooseImapAuth(caps, tls = true, plaintextOk = false, channelBinding = false, clientMechanisms = "PLAIN SCRAM-SHA-256", clientCert = true),
        )
    }
}
