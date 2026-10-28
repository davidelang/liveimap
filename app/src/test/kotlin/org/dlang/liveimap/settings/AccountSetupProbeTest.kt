package org.dlang.liveimap.settings

import org.dlang.liveimap.session.OpenResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSetupProbeTest {
    @Test
    fun localSaslFailureIsNotAuthRejected() {
        val local = OpenResult.Failed(
            "login failed before contacting the server: SASL PLAIN failed locally",
        )
        val kept = openResultAfterPlusFallback(local, local)
        assertTrue(kept is OpenResult.Failed)
        assertTrue(kept !is OpenResult.AuthRejected)
        val other = OpenResult.AuthRejected("other session")
        assertEquals("other session", other.text)
        assertTrue(other != kept)
    }
}
