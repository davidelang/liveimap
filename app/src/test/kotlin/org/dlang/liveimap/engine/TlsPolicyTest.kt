package org.dlang.liveimap.engine

import org.dlang.liveimap.engine.sieve.sieveTlsProtocols
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.decodeAccountSettings
import org.dlang.liveimap.settings.encode
import org.dlang.liveimap.settings.tlsPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TlsPolicyTest {
    @Test
    fun offByDefaultKeepsEveryVerdict() {
        val policy = AccountSettings().tlsPolicy()
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
        val defaults = AccountSettings().encode()
        assertFalse(defaults.contains("acceptInvalidCert="))
        assertFalse(defaults.contains("allowOldTls="))
        val text = AccountSettings(acceptInvalidCert = true, allowOldTls = true).encode()
        assertTrue(text.contains("acceptInvalidCert=true"))
        assertTrue(text.contains("allowOldTls=true"))
        val back = decodeAccountSettings(text)
        assertTrue(back.acceptInvalidCert)
        assertTrue(back.allowOldTls)
        assertEquals(TlsPolicy(acceptInvalidCert = true, allowOldTls = true), back.tlsPolicy())
    }
}
