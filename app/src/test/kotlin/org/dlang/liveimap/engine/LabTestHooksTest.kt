package org.dlang.liveimap.engine

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabTestHooksTest {
    private val caDer = Base64.getDecoder().decode("MIIBfDCCASGgAwIBAgIUK77noAjSA5LihKSopbnUnlXboPowCgYIKoZIzj0EAwIwEjEQMA4GA1UEAwwHdGVzdCBDQTAgFw0yNjEwMTAxMDI1NThaGA8yMTI2MDkxNjEwMjU1OFowEjEQMA4GA1UEAwwHdGVzdCBDQTBZMBMGByqGSM49AgEGCCqGSM49AwEHA0IABIAUTfI6gIom/tz4RBlxu6rLcxkZ9qTWcf2CXHVPjOMIYBc+UBDrUMVRPbYz6GZi8x9U5cceYEeQCWmPMNsgobCjUzBRMB0GA1UdDgQWBBToYfpszrK4k7NugqgtAHn6ohboLjAfBgNVHSMEGDAWgBToYfpszrK4k7NugqgtAHn6ohboLjAPBgNVHRMBAf8EBTADAQH/MAoGCCqGSM49BAMCA0kAMEYCIQDfF6AiQFonecfJ23hgcF7EVxSndFDnRNc4G2wnalDG5AIhAJGj1quQOsUoLj9KLBd8F65CNDfw3yVuFTLyBbK5x2+O")
    private val leaf = cert("MIIBazCCARKgAwIBAgIUCUYWL3BveyOzLhyQo6O1+dhzN9kwCgYIKoZIzj0EAwIwEjEQMA4GA1UEAwwHdGVzdCBDQTAgFw0yNjEwMTAxMDI1NThaGA8yMTI2MDkxNjEwMjU1OFowFDESMBAGA1UEAwwJMTAuMC4wLjEwMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEFtrG3KbL6eQkx3/U8IE76ElqfwjdoqmS8Pq9SV1NeBHPc7ZsGJVtH4/vO2UaXeasZad/Lb0gHBtPMFs8F+dl0KNCMEAwHQYDVR0OBBYEFL4Cq6jSwdIeMNWzpS6IH3I+Z+XDMB8GA1UdIwQYMBaAFOhh+mzOsriTs26CqC0AefqiFuguMAoGCCqGSM49BAMCA0cAMEQCIAxgF4iLRmrh7GXyG0l5gC+/I42rTC+FOAHLUXyuKCYqAiBXBlyu0WWWgH989LKzIS4T4Sqv2f63vNu3WxlamLfqcg==")
    private val other = cert("MIIBfzCCASWgAwIBAgIUVqqar4hW2YcbbhVSQKXjzjl0NuowCgYIKoZIzj0EAwIwFDESMBAGA1UEAwwJMTAuMC4wLjEwMCAXDTI2MTAxMDEwMjU1OFoYDzIxMjYwOTE2MTAyNTU4WjAUMRIwEAYDVQQDDAkxMC4wLjAuMTAwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAATseQhTtCwQC+xMkWd7Sq7tlIbofPLk8oSWDd3vLipIFppsaXs5LDbJlWU76Spe418sLNYm00diwt5jor4hCw4eo1MwUTAdBgNVHQ4EFgQUuc/Y6KB+gaGXcTDB9qHFJbk5o/4wHwYDVR0jBBgwFoAUuc/Y6KB+gaGXcTDB9qHFJbk5o/4wDwYDVR0TAQH/BAUwAwEB/zAKBggqhkjOPQQDAgNIADBFAiEA9j5CWrO43ta4fpQM85t8xenehzVPfkLDn4pyBSYl7fsCIHuiUR4ivaR0NJ3z1BO9ehCuZ5eQGAwbWDDHTb0BUTnK")

    private fun cert(b64: String) = CertificateFactory.getInstance("X.509")
        .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(b64))) as X509Certificate

    @After
    fun reset() = LabTestHooks.clear()

    @Test
    fun unsetGivesNothing() {
        assertNull(LabTestHooks.passwordFor("10.0.0.10", true))
        assertFalse(LabTestHooks.trustsLabLeaf("10.0.0.10", leaf, true))
    }

    @Test
    fun onlyDebugAndOnlyTheLabHost() {
        LabTestHooks.set("10.0.0.10", "pw")
        assertEquals("pw", LabTestHooks.passwordFor("10.0.0.10", true))
        assertNull(LabTestHooks.passwordFor("10.0.0.10", false))
        assertNull(LabTestHooks.passwordFor("imap.example.com", true))
        LabTestHooks.clear()
        assertNull(LabTestHooks.passwordFor("10.0.0.10", true))
    }

    @Test
    fun labCaTrustsOnlyItsLeavesUnderTheGate() {
        LabTestHooks.set("10.0.0.10", "pw")
        LabTestHooks.setLabCa(caDer)
        assertTrue(LabTestHooks.trustsLabLeaf("10.0.0.10", leaf, true))
        assertFalse(LabTestHooks.trustsLabLeaf("10.0.0.10", other, true))
        assertFalse(LabTestHooks.trustsLabLeaf("10.0.0.10", leaf, false))
        assertFalse(LabTestHooks.trustsLabLeaf("imap.example.com", leaf, true))
    }
}
