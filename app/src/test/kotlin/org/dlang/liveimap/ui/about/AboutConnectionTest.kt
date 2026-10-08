package org.dlang.liveimap.ui.about

import org.dlang.liveimap.R
import org.dlang.liveimap.settings.TlsMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AboutConnectionTest {
    @Test
    fun blankHostIsNull() {
        assertNull(aboutConnectionNotice("", TlsMode.None))
        assertNull(aboutConnectionNotice(" ", TlsMode.StartTls))
        assertNull(aboutConnectionNotice("\t", TlsMode.Implicit))
    }

    @Test
    fun eachModeReturnsItsString() {
        assertEquals(
            R.string.about_plaintext,
            aboutConnectionNotice("imap.example.com", TlsMode.None),
        )
        assertEquals(
            R.string.about_starttls,
            aboutConnectionNotice("imap.example.com", TlsMode.StartTls),
        )
        assertEquals(
            R.string.about_implicit_tls,
            aboutConnectionNotice("imap.example.com", TlsMode.Implicit),
        )
    }
}
