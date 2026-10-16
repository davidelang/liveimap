package org.dlang.liveimap.ui.contacts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F85: stray messages in the book folder are neither the book nor trimmed. */
class BookRevisionTest {
    private fun b(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    @Test fun onlyRevisionsCount() {
        assertTrue(isBookRevision(b("X-Pine-Addrbook: 1\r\n\r\nx\ty\tz\r\n"), ""))
        assertFalse(isBookRevision(b("Subject: Pine Remote Data Container\r\n\r\nx\r\n"), "Pine Remote Data Container"))
        assertFalse(isBookRevision(b("From: evil@x\r\nSubject: hi\r\n\r\nboss\tBoss\tattacker@evil\r\n"), "Pine Remote Data Container"))
        assertFalse(isBookRevision(b("Subject: \r\n\r\nx\r\n"), ""))
    }
}
