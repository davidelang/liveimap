package org.dlang.liveimap.engine

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerTrustTest {
    @Test
    fun emptyChainIsRejected() {
        assertTrue(PeerTrust.check("imap.example", emptyList(), "").isNotEmpty())
    }

    @Test
    fun pinAllowsNamedLeaf() {
        val der = der("peer-trust-ok.pem")
        val offer = PeerTrust.offer(listOf(der))
        assertTrue(offer != null && offer.size == 5)
        val decorated = offer!![4].chunked(2).joinToString(":")
        assertEquals("", PeerTrust.check("imap.example", listOf(der), "  $decorated "))
    }

    @Test
    fun wrongNameIgnoresPin() {
        val der = der("peer-trust-ok.pem")
        val pin = PeerTrust.offer(listOf(der))!![4]
        assertEquals("name mismatch", PeerTrust.check("other.example", listOf(der), pin))
    }

    @Test
    fun expiredIgnoresPin() {
        val der = der("peer-trust-expired.pem")
        val pin = PeerTrust.offer(listOf(der))!![4]
        assertEquals("certificate expired", PeerTrust.check("imap.example", listOf(der), pin))
    }

    @Test
    fun changedPin() {
        val der = der("peer-trust-ok.pem")
        assertEquals("certificate changed", PeerTrust.check("imap.example", listOf(der), "00"))
    }

    @Test
    fun blankPinIsUntrusted() {
        val der = der("peer-trust-ok.pem")
        assertEquals("certificate untrusted", PeerTrust.check("imap.example", listOf(der), ""))
    }

    private fun der(name: String): ByteArray {
        val bytes = javaClass.classLoader.getResourceAsStream(name)?.use { it.readBytes() }
            ?: error("missing $name")
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        return cert.encoded
    }
}
