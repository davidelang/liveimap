package org.dlang.liveimap.ui.reader

import org.dlang.liveimap.settings.TextLayout
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.BodyView
import org.dlang.liveimap.settings.SaveNameRule
import org.dlang.liveimap.settings.bodyViewLabel
import org.dlang.liveimap.settings.decodeSettings
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
    fun readerTextLayoutRoundTripAndMissingKey() {
        assertEquals(TextLayout.Text, Settings().readerTextLayout)
        for (layout in TextLayout.entries) {
            assertEquals(layout, decodeSettings(Settings(readerTextLayout = layout).encode()).readerTextLayout)
        }
        val absent = Settings(readerTextLayout = TextLayout.Flowed).encode().lineSequence()
            .filter { it.isNotEmpty() && !it.startsWith("readerTextLayout=") }
            .joinToString("\n")
        assertEquals(TextLayout.Text, decodeSettings(absent).readerTextLayout)
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
