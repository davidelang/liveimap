package org.dlang.liveimap.ui.reader

import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.BodyView
import org.dlang.liveimap.settings.SaveNameRule
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
    fun headerFieldsKeepsFirstSenderAndLastResentTo() {
        val header = "From: Ada <ada@example.com>\r\n" +
            "To: one@example.com\r\n" +
            "Cc: cc@example.com\r\n" +
            "Sender: First <first@example.com>\r\n" +
            "Sender: Second <second@example.com>\r\n" +
            "Resent-To: early@example.com\r\n" +
            "Resent-To: late@example.com\r\n"
        val fields = headerFields(header)
        assertEquals("Ada <ada@example.com>", fields.from)
        assertEquals("one@example.com", fields.to)
        assertEquals("cc@example.com", fields.cc)
        assertEquals("First <first@example.com>", fields.sender)
        assertEquals("late@example.com", fields.resentTo)
    }

    @Test
    fun saveFolderNameRules() {
        assertEquals(
            "Saved",
            saveFolderName(SaveNameRule.DefaultFolder, "Saved", "Archive", "Ada <Ada@Example.com>", "", "", ""),
        )
        assertEquals(
            "Archive",
            saveFolderName(SaveNameRule.LastFolderUsed, "Saved", "Archive", "", "", "", ""),
        )
        assertEquals(
            "Saved",
            saveFolderName(SaveNameRule.LastFolderUsed, "Saved", "", "", "", "", ""),
        )
        assertEquals(
            "ada",
            saveFolderName(
                SaveNameRule.ByFrom,
                "Saved",
                "",
                "Ada <Ada@Example.com>",
                "Bob <bob@example.com>",
                "",
                "",
            ),
        )
        assertEquals(
            "bob",
            saveFolderName(SaveNameRule.ByFrom, "Saved", "", "", "Bob <bob@example.com>", "", ""),
        )
        assertEquals(
            "Saved",
            saveFolderName(SaveNameRule.ByFrom, "Saved", "", "", "", "", ""),
        )
        assertEquals(
            "bob",
            saveFolderName(
                SaveNameRule.BySender,
                "Saved",
                "",
                "Ada <Ada@Example.com>",
                "Bob <bob@example.com>",
                "",
                "",
            ),
        )
        assertEquals(
            "ada",
            saveFolderName(SaveNameRule.BySender, "Saved", "", "Ada <Ada@Example.com>", "", "", ""),
        )
        assertEquals(
            "Saved",
            saveFolderName(SaveNameRule.BySender, "Saved", "", "", "", "", ""),
        )
        assertEquals(
            "cara",
            saveFolderName(
                SaveNameRule.ByRecipient,
                "Saved",
                "",
                "",
                "",
                "Ada <Ada@Example.com>",
                "Cara <Cara@Example.com>",
            ),
        )
        assertEquals(
            "ada",
            saveFolderName(SaveNameRule.ByRecipient, "Saved", "", "", "", "Ada <Ada@Example.com>", ""),
        )
        assertEquals(
            "Saved",
            saveFolderName(SaveNameRule.ByRecipient, "Saved", "", "", "", "", ""),
        )
        assertEquals(
            "ann",
            saveFolderName(SaveNameRule.ByFrom, "Saved", "", "bob!ann%extra@host", "", "", ""),
        )
        assertEquals(
            "a",
            saveFolderName(SaveNameRule.ByFrom, "Saved", "", "a:b@host", "", "", ""),
        )
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

    @Test
    fun takeAddressesKeepsFirstCopyThenLaterHeaders() {
        assertEquals(
            listOf(
                TakeAddress("Ada Lovelace", "Ada@Example.com"),
                TakeAddress("Bob", "bob@example.com"),
                TakeAddress("Cara", "cara@example.com"),
                TakeAddress("Dee", "dee@example.com"),
                TakeAddress("Eve", "eve@example.com"),
            ),
            takeAddresses(
                "Ada Lovelace <Ada@Example.com>, Bob <bob@example.com>",
                "Ada@Example.com",
                "Cara <cara@example.com>",
                "Dee <dee@example.com>",
                "Eve <eve@example.com>",
            ),
        )
        assertEquals(
            listOf(TakeAddress("", "ann@example.com")),
            takeAddresses("", "", "ann@example.com", "", ""),
        )
        assertEquals(
            emptyList<TakeAddress>(),
            takeAddresses("undisclosed-recipients:;", "", "", "", ""),
        )
        assertEquals(
            listOf(TakeAddress("Ada Lovelace", "ada@example.com")),
            takeAddresses("\"Ada Lovelace\" <ada@example.com>", "", "", "", ""),
        )
    }
}
