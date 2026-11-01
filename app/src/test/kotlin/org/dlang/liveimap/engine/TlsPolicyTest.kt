package org.dlang.liveimap.engine

import org.dlang.liveimap.engine.sieve.sieveTlsProtocols
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.decodeSettings
import org.dlang.liveimap.settings.encode
import org.dlang.liveimap.settings.ioContext
import org.dlang.liveimap.settings.tlsPolicy
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsPolicyTest {
    @Test
    fun offByDefaultKeepsEveryVerdict() {
        val policy = Settings().tlsPolicy()
        assertFalse(policy.acceptInvalidCert)
        assertFalse(policy.allowOldTls)
        for (verdict in certificateVerdicts) assertEquals(verdict, applyTlsPolicy(verdict, policy))
    }

    @Test
    fun acceptInvalidOverridesOnlyCertificateVerdicts() {
        val policy = TlsPolicy(acceptInvalidCert = true)
        assertEquals("", applyTlsPolicy("name mismatch", policy))
        assertEquals("", applyTlsPolicy("certificate expired", policy))
        assertEquals("", applyTlsPolicy("certificate untrusted", policy))
        assertEquals("", applyTlsPolicy("certificate rejected", policy))
        assertEquals("certificate changed", applyTlsPolicy("certificate changed", policy))
        assertEquals("connection failed", applyTlsPolicy("connection failed", policy))
        assertEquals("TLS 1.2 required", applyTlsPolicy("TLS 1.2 required", policy))
        assertEquals("", applyTlsPolicy("", policy))
    }

    @Test
    fun sieveProtocolsWidenOnlyWithOldTls() {
        val supported = arrayOf("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3", "SSLv3")
        assertEquals(listOf("TLSv1.2", "TLSv1.3"), sieveTlsProtocols(supported))
        assertEquals(listOf("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3"), sieveTlsProtocols(supported, true))
    }

    @Test
    fun settingsRoundTripAndOmittedWhenOff() {
        val defaults = Settings().encode()
        assertTrue(defaults.contains("acceptInvalidCert=false"))
        assertTrue(defaults.contains("allowOldTls=false"))
        val text = Settings(acceptInvalidCert = true, allowOldTls = true).encode()
        assertTrue(text.contains("acceptInvalidCert=true"))
        assertTrue(text.contains("allowOldTls=true"))
        val back = decodeSettings(text)
        assertTrue(back.acceptInvalidCert)
        assertTrue(back.allowOldTls)
        assertEquals(TlsPolicy(acceptInvalidCert = true, allowOldTls = true), back.tlsPolicy())
    }

    @Test
    fun ioContextIsIoPlusThePolicy() {
        val policy = TlsPolicy(acceptInvalidCert = true, allowOldTls = true)
        val context = policy.ioContext()
        assertSame(Dispatchers.IO, context[ContinuationInterceptor])
        assertSame(policy, context[TlsPolicy])
        assertTrue(context[TlsPolicy]!!.acceptInvalidCert)
        assertTrue(context[TlsPolicy]!!.allowOldTls)
    }

    @Test
    fun authMechanismRoundTrip() {
        assertTrue(Settings().encode().contains("authMechanism="))
        val back = decodeSettings(Settings(authMechanism = "SCRAM-SHA-256").encode())
        assertEquals("SCRAM-SHA-256", back.authMechanism)
    }
}
