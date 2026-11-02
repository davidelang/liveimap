package org.dlang.liveimap.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposeChromeTest {
    @Test
    fun unsentSubjectAndLabel() {
        assertEquals("Hello", unsentSubject("Subject: Hello\r\n\r\nBody".encodeToByteArray(), "No subject", "ISO-8859-1"))
        assertEquals(
            "No subject",
            unsentSubject("From: a@example.com\r\n\r\nHi".encodeToByteArray(), "No subject", "ISO-8859-1"),
        )
    }
}
