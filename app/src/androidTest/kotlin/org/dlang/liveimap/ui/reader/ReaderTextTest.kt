package org.dlang.liveimap.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTextTest {
    @Test
    fun headerFieldsReturnsUnfoldedToAndCc() {
        val header = "From: Ada <ada@example.com>\r\n" +
            "To: one@example.com,\r\n" +
            " two@example.com\r\n" +
            "Cc: first@example.com\r\n" +
            "\tsecond@example.com\r\n" +
            "Subject: Hi\r\n"
        val fields = headerFields(header, "ISO-8859-1")
        assertEquals("one@example.com, two@example.com", fields.to)
        assertEquals("first@example.com second@example.com", fields.cc)
        assertEquals("Ada <ada@example.com>", fields.from)
    }

    @Test
    fun headerFieldsKeepsFirstSenderAndLastResentTo() {
        val header = "From: Ada <ada@example.com>\r\n" +
            "To: one@example.com\r\n" +
            "Cc: cc@example.com\r\n" +
            "Sender: First <first@example.com>\r\n" +
            "Sender: Second <second@example.com>\r\n" +
            "Resent-To: early@example.com\r\n" +
            "Resent-To: late@example.com\r\n"
        val fields = headerFields(header, "ISO-8859-1")
        assertEquals("Ada <ada@example.com>", fields.from)
        assertEquals("one@example.com", fields.to)
        assertEquals("cc@example.com", fields.cc)
        assertEquals("First <first@example.com>", fields.sender)
        assertEquals("late@example.com", fields.resentTo)
    }
}
