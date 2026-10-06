package org.dlang.liveimap.ui.reader

import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.BodyView
import org.dlang.liveimap.settings.bodyViewLabel
import org.dlang.liveimap.settings.decodeAccountSettings
import org.dlang.liveimap.settings.encode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTextTest {
    @Test
    fun fiveBodyViewLabels() {
        assertEquals("Plain text", bodyViewLabel(BodyView.PlainOrError))
        assertEquals("HTML", bodyViewLabel(BodyView.PlainOrHtml))
        assertEquals("HTML as text", bodyViewLabel(BodyView.PlainOrText))
        assertEquals("Headers", bodyViewLabel(BodyView.Headers))
        assertEquals("Raw source", bodyViewLabel(BodyView.Raw))
    }

    @Test
    fun quotedReaderLineOnlyWhenLineStartsWithAngle() {
        assertTrue(quotedReaderLine(">"))
        assertTrue(quotedReaderLine("> quoted"))
        assertTrue(quotedReaderLine(">> nested"))
        assertFalse(quotedReaderLine(""))
        assertFalse(quotedReaderLine("not quoted"))
        assertFalse(quotedReaderLine(" > indented"))
        assertFalse(quotedReaderLine("a > b"))
    }

    @Test
    fun headerFieldsReturnsUnfoldedToAndCc() {
        val header = "From: Ada <ada@example.com>\r\n" +
            "To: one@example.com,\r\n" +
            " two@example.com\r\n" +
            "Cc: first@example.com\r\n" +
            "\tsecond@example.com\r\n" +
            "Subject: Hi\r\n"
        val fields = headerFields(header)
        assertEquals("one@example.com, two@example.com", fields.to)
        assertEquals("first@example.com second@example.com", fields.cc)
        assertEquals("Ada <ada@example.com>", fields.from)
    }

    @Test
    fun plainTextMonospaceRoundTripAndMissingKey() {
        assertFalse(AccountSettings().plainTextMonospace)
        val on = AccountSettings(plainTextMonospace = true)
        assertTrue(decodeAccountSettings(on.encode()).plainTextMonospace)
        val absent = on.encode().lineSequence()
            .filter { it.isNotEmpty() && !it.startsWith("plainTextMonospace=") }
            .joinToString("\n")
        assertFalse(decodeAccountSettings(absent).plainTextMonospace)
    }
}
