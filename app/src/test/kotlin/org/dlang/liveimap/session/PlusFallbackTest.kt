package org.dlang.liveimap.session

import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.TlsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlusFallbackTest {
    private val tls = AccountSettings(imapHost = "lab.test", tlsMode = TlsMode.StartTls)
    private val failed = "IMAP lab.test:143 (lab.test) user liveimap: login failed: bad protocol / cancel " +
        "(SASL SCRAM-SHA-256-PLUS failed locally; client mechanisms: PLAIN SCRAM-SHA-256)"

    @Test
    fun plusFailureRetriesSameScramWithoutBinding() {
        val retry = plusFallback(tls, failed)
        assertEquals("SCRAM-SHA-256", retry?.authMechanism)
        assertEquals(TlsMode.StartTls, retry?.tlsMode)
        assertEquals("SCRAM-SHA-1", plusFallback(tls.copy(tlsMode = TlsMode.Implicit), failed.replace("256", "1"))?.authMechanism)
    }

    @Test
    fun noRetryForForcedPlaintextOrOtherFailures() {
        assertNull(plusFallback(tls.copy(authMechanism = "SCRAM-SHA-256-PLUS"), failed))
        assertNull(plusFallback(tls.copy(tlsMode = TlsMode.None), failed))
        assertNull(plusFallback(tls, "login failed: [AUTHENTICATIONFAILED] Authentication failed."))
        assertNull(plusFallback(tls, "login failed (SASL SCRAM-SHA-256 failed locally)"))
    }
}

class PlusFallbackTrustTest {
    private val base = org.dlang.liveimap.settings.AccountSettings(imapHost = "h", tlsMode = org.dlang.liveimap.settings.TlsMode.Implicit)

    @org.junit.Test fun noFallbackWhenTrustCameFromTheUser() {
        val f = "SASL SCRAM-SHA-256-PLUS failed"
        org.junit.Assert.assertNotNull(plusFallback(base, f))
        org.junit.Assert.assertNull(plusFallback(base.copy(acceptInvalidCert = true), f))
        org.junit.Assert.assertNull(plusFallback(base.copy(certPin = "AB:CD"), f))
    }
}
