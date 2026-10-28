package org.dlang.liveimap.engine

import org.dlang.liveimap.settings.SmtpNoAuth
import org.junit.Assert.assertEquals
import org.junit.Test

/** F30: QRESYNC answers are not processed, so QRESYNC is never enabled; CONDSTORE is used instead. */
class ResyncEnableTest {
    @Test fun qresyncServersGetCondstore() {
        assertEquals("CONDSTORE", enableForResync("Qresync"))
        assertEquals("CONDSTORE", enableForResync("Condstore"))
        assertEquals("", enableForResync("None"))
    }

    /** F52 (David): no implicit IMAP-to-SMTP fallback, even for the same host. */
    @Test fun smtpPasswordNeverFallsBackSilently() {
        assertEquals("", smtpPasswordFor(false, "", "imap-secret", SmtpNoAuth.Off))
        assertEquals("", smtpPasswordFor(false, "smtp-secret", "imap-secret", SmtpNoAuth.On))
        assertEquals("smtp-secret", smtpPasswordFor(false, "smtp-secret", "imap-secret", SmtpNoAuth.Off))
        assertEquals("imap-secret", smtpPasswordFor(true, "", "imap-secret", SmtpNoAuth.Off))
        assertEquals("imap-secret", smtpPasswordFor(true, "stale", "imap-secret", SmtpNoAuth.Off))
        assertEquals("", smtpPasswordFor(true, "", "", SmtpNoAuth.Off))
    }
}
