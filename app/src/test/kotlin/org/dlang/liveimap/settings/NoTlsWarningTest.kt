package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoTlsWarningTest {
    @Test fun warnsOnlyWithoutTlsAndNeverChangesSettings() {
        val implicit = newAccountSettings()
        assertNull(noTlsWarning(implicit))
        assertNull(noTlsWarning(implicit.copy(tlsMode = TlsMode.StartTls)))
        val none = implicit.copy(tlsMode = TlsMode.None)
        assertEquals(
            "No TLS: mail and folder names travel unencrypted, and anyone on the network can read or change them.",
            noTlsWarning(none),
        )
        val clear = none.copy(allowPlaintextAuth = true)
        assertEquals(
            "No TLS and plaintext login allowed: your password, mail and folder names travel unencrypted. Use STARTTLS or implicit TLS if the server offers it.",
            noTlsWarning(clear),
        )
        assertEquals(TlsMode.None, clear.tlsMode)
        assertEquals(true, clear.allowPlaintextAuth)
    }
}
