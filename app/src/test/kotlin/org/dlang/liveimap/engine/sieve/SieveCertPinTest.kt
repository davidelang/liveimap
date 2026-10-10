package org.dlang.liveimap.engine.sieve

import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.engine.PeerTrust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SieveCertPinTest {
    @Test
    fun trustManagerUsesPeerTrust() {
        val leaf = leaf("peer-trust-ok.pem")
        val blank = SieveServerTrust("imap.example", "")
        try {
            blank.checkServerTrusted(arrayOf(leaf), "RSA")
            throw AssertionError("expected CertificateException")
        } catch (error: CertificateException) {
            assertEquals("certificate untrusted", error.message)
        }
        val fingerprint = PeerTrust.offer(listOf(leaf.encoded))!![4]
        SieveServerTrust("imap.example", fingerprint).checkServerTrusted(arrayOf(leaf), "RSA")
        val other = SieveServerTrust("other.example", fingerprint)
        try {
            other.checkServerTrusted(arrayOf(leaf), "RSA")
            throw AssertionError("expected CertificateException")
        } catch (error: CertificateException) {
            assertEquals("name mismatch", error.message)
        }
    }

    @Test
    fun offerPromptsOnlyForUntrustedAndChanged() {
        val ok = leaf("peer-trust-ok.pem")
        val ders = listOf(ok.encoded)
        val untrusted = sieveCertificateOffer("certificate untrusted", ders, "sieve.example", 4190)
        assertTrue(untrusted != null)
        val parts = PeerTrust.offer(ders)!!
        assertEquals(5, parts.size)
        assertEquals("sieve.example", untrusted!!.host)
        assertEquals(4190, untrusted.port)
        assertEquals("certificate untrusted", untrusted.reason)
        assertEquals(parts[0], untrusted.subject)
        assertEquals(parts[1], untrusted.issuer)
        assertEquals(parts[2], untrusted.notBefore)
        assertEquals(parts[3], untrusted.notAfter)
        assertEquals(parts[4], untrusted.fingerprint)
        val changed = sieveCertificateOffer("certificate changed", ders, "sieve.example", 4190)
        assertTrue(changed != null)
        assertEquals("certificate changed", changed!!.reason)
        assertEquals(parts[4], changed.fingerprint)
        assertTrue(sieveCertificateOffer("name mismatch", ders, "imap.example", 4190) == null)
        val expired = leaf("peer-trust-expired.pem")
        assertTrue(
            sieveCertificateOffer(
                "certificate expired",
                listOf(expired.encoded),
                "imap.example",
                4190,
            ) == null,
        )
    }

    @Test
    fun hostnameCheckIsHttps() {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(SieveServerTrust("imap.example", "")), null)
        val socket = context.socketFactory.createSocket() as SSLSocket
        enableSieveHostnameCheck(socket)
        assertEquals("HTTPS", socket.sslParameters.endpointIdentificationAlgorithm)
        socket.close()
    }

    @Test
    fun acceptSavesFingerprintAndRetriesOnce() = runBlocking {
        val offer = sampleOffer("certificate untrusted", "ab")
        var confirms = 0
        var saves = 0
        var calls = 0
        val result = withSieveCertificate(
            pin = "",
            confirm = { prompt ->
                confirms += 1
                assertEquals(offer.reason, prompt.reason)
                assertEquals(offer.fingerprint, prompt.fingerprint)
                assertEquals(offer.host, prompt.host)
                assertEquals(offer.port, prompt.port)
                true
            },
            save = { fingerprint ->
                saves += 1
                assertEquals("ab", fingerprint)
            },
        ) { pin ->
            calls += 1
            if (calls == 1) throw offer
            assertEquals("ab", pin)
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, confirms)
        assertEquals(1, saves)
        assertEquals(2, calls)
    }

    @Test
    fun declineDoesNotSave() = runBlocking {
        var saved = false
        try {
            withSieveCertificate(
                pin = "",
                confirm = { false },
                save = { saved = true },
            ) {
                throw sampleOffer("certificate untrusted", "ab")
            }
            throw AssertionError("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("certificate untrusted", failure.text)
        }
        assertFalse(saved)
    }

    @Test
    fun secondOfferDoesNotConfirmAgain() = runBlocking {
        var confirms = 0
        var saves = 0
        try {
            withSieveCertificate(
                pin = "",
                confirm = {
                    confirms += 1
                    true
                },
                save = { saves += 1 },
            ) {
                throw sampleOffer("certificate changed", "ab")
            }
            throw AssertionError("expected SieveFailure")
        } catch (failure: SieveFailure) {
            assertEquals("certificate changed", failure.text)
        }
        assertEquals(1, confirms)
        assertEquals(1, saves)
    }

    private fun sampleOffer(reason: String, fingerprint: String): SievePinOffer {
        return SievePinOffer("sieve.example", 4190, reason, "subject", "issuer", "before", "after", fingerprint)
    }

    private fun leaf(name: String): X509Certificate {
        val bytes = checkNotNull(javaClass.classLoader).getResourceAsStream(name)?.use { it.readBytes() }
            ?: error("missing $name")
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
    }
}
