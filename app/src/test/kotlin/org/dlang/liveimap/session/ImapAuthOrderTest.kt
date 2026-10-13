package org.dlang.liveimap.session

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** David, 2026-10-12: asgard (Cyrus, AUTH=CRAM-MD5 AUTH=PLAIN) rejected an automatically chosen CRAM-MD5. */
class ImapAuthOrderTest {
    private val asgard = "IMAP4rev1 IMAP4rev2 AUTH=CRAM-MD5 AUTH=PLAIN ENABLE ID LITERAL+ SASL-IR STARTTLS"
    private val client = "ANONYMOUS PLAIN CRAM-MD5 OTP EXTERNAL DIGEST-MD5 SCRAM-SHA-1 SCRAM-SHA-224 SCRAM-SHA-256 SCRAM-SHA-384 SCRAM-SHA-512"

    @Test fun tlsPrefersPlainOverCramMd5() {
        assertEquals(listOf("PLAIN", "CRAM-MD5"), imapAuthOrder(asgard, tls = true, plaintextOk = false, channelBinding = true, clientMechanisms = client))
        assertEquals("PLAIN", chooseImapAuth(asgard, tls = true, plaintextOk = false, channelBinding = false, clientMechanisms = client))
    }

    @Test fun scramStillFirst() {
        val caps = "AUTH=PLAIN AUTH=CRAM-MD5 AUTH=SCRAM-SHA-1 AUTH=SCRAM-SHA-256"
        assertEquals(listOf("SCRAM-SHA-256", "SCRAM-SHA-1", "PLAIN", "CRAM-MD5"), imapAuthOrder(caps, true, false, false, client))
    }

    @Test fun withoutTlsCramBeforeCleartext() {
        assertEquals(listOf("CRAM-MD5", "PLAIN"), imapAuthOrder(asgard, tls = false, plaintextOk = true, channelBinding = false, clientMechanisms = client))
        assertEquals(listOf("CRAM-MD5"), imapAuthOrder(asgard, tls = false, plaintextOk = false, channelBinding = false, clientMechanisms = client))
    }

    @Test fun orderLineIsSpaceJoined() {
        assertEquals("PLAIN CRAM-MD5", imapAuthOrderLine(asgard, true, false, false, client, false))
    }

    @Test fun plusFallbackStillSeesServerRejection() {
        val tls = org.dlang.liveimap.settings.AccountSettings(imapHost = "h", tlsMode = org.dlang.liveimap.settings.TlsMode.StartTls)
        assertNotNull(plusFallback(tls, "login failed: no (server rejected SASL SCRAM-SHA-256-PLUS)"))
    }

    @Test fun nativeTriesNextOnlyOnLocalFailureAndSaysWhichSideFailed() {
        val jni = listOf("src/main/cpp/liveimap_jni.cpp", "app/src/main/cpp/liveimap_jni.cpp").map(::File).first { it.exists() }.readText()
        assertTrue(jni.contains("\"imapAuthOrderLine\""))
        assertTrue(jni.contains("imap->imap_tag == tagBefore"))
        assertTrue(jni.contains("server rejected SASL "))
        assertTrue(jni.contains("login failed before contacting the server"))
    }
}

class ImapAuthSupportedListTest {
    private val jni = listOf("src/main/cpp/liveimap_jni.cpp", "app/src/main/cpp/liveimap_jni.cpp").map(::File).first { it.exists() }.readText()

    @Test fun onlySupportedMechanismsAreListed() {
        val list = Regex("kSupportedSasl\\[\\] = \\{([^}]*)\\}").find(jni)!!.groupValues[1]
        for (never in listOf("ANONYMOUS", "OTP", "DIGEST-MD5", "SCRAM-SHA-224", "SCRAM-SHA-384", "SCRAM-SHA-512")) {
            assertTrue(never, !list.contains("\"$never\""))
        }
        assertTrue(jni.contains("saslClientMechs(havePassword)"))
    }

    @Test fun localFailuresEndInImapLogin() {
        assertTrue(jni.contains("order.push_back(std::string());"))
    }

    @Test fun tlsOffWithPlaintextAllowedKeepsPlainAndLogin() {
        val order = imapAuthOrder("AUTH=CRAM-MD5 AUTH=PLAIN AUTH=LOGIN", tls = false, plaintextOk = true, channelBinding = false,
            clientMechanisms = "SCRAM-SHA-256 SCRAM-SHA-1 PLAIN LOGIN CRAM-MD5 EXTERNAL")
        assertEquals(listOf("CRAM-MD5", "PLAIN", "LOGIN"), order)
    }

    @Test fun noPasswordMeansNoPasswordMechanisms() {
        assertTrue(imapAuthOrder("AUTH=CRAM-MD5 AUTH=PLAIN", tls = true, plaintextOk = true, channelBinding = false, clientMechanisms = "EXTERNAL").isEmpty())
    }
}

/** David, 2026-10-12: strongest first; "plaintext allowed" only adds weaker fallbacks at the end. */
class ImapAuthStrongestFirstTest {
    private val all = "AUTH=PLAIN AUTH=LOGIN AUTH=CRAM-MD5 AUTH=SCRAM-SHA-1 AUTH=SCRAM-SHA-256 AUTH=SCRAM-SHA-256-PLUS AUTH=EXTERNAL"
    private val client = "SCRAM-SHA-256 SCRAM-SHA-1 PLAIN LOGIN CRAM-MD5 EXTERNAL"

    @Test fun tlsWithCertAndBinding() {
        assertEquals(
            listOf("EXTERNAL", "SCRAM-SHA-256-PLUS", "SCRAM-SHA-256", "SCRAM-SHA-1", "PLAIN", "LOGIN", "CRAM-MD5"),
            imapAuthOrder(all, tls = true, plaintextOk = false, channelBinding = true, clientMechanisms = client, clientCert = true),
        )
    }

    @Test fun cleartextWithPlaintextAllowedStillStartsStrongest() {
        assertEquals(
            listOf("SCRAM-SHA-256", "SCRAM-SHA-1", "CRAM-MD5", "PLAIN", "LOGIN"),
            imapAuthOrder(all, tls = false, plaintextOk = true, channelBinding = false, clientMechanisms = client),
        )
        assertEquals(
            listOf("SCRAM-SHA-256", "SCRAM-SHA-1", "CRAM-MD5"),
            imapAuthOrder(all, tls = false, plaintextOk = false, channelBinding = false, clientMechanisms = client),
        )
    }
}
