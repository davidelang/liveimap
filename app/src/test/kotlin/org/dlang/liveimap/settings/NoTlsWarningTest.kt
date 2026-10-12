package org.dlang.liveimap.settings

import org.dlang.liveimap.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoTlsWarningTest {
    @Test fun warnsOnlyWithoutTlsAndNeverChangesSettings() {
        val implicit = newAccountSettings()
        assertNull(noTlsWarning(implicit))
        assertNull(noTlsWarning(implicit.copy(tlsMode = TlsMode.StartTls)))
        val none = implicit.copy(tlsMode = TlsMode.None)
        assertEquals(R.string.settings_no_tls_warning, noTlsWarning(none))
        val clear = none.copy(allowPlaintextAuth = true)
        assertEquals(R.string.settings_no_tls_password_warning, noTlsWarning(clear))
        assertEquals(TlsMode.None, clear.tlsMode)
        assertEquals(true, clear.allowPlaintextAuth)
    }
}
