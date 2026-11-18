package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contacts phases 3 and 5: extra and global Alpine books, and per-account book source ids. */
class ExtraBooksTest {
    private val host = Settings(imapHost = "imap.example.com")

    @Test fun settingsRoundTrip() {
        val s = Settings(addressBooks = listOf(
            pine("ab"),
            pine("work, x"),
            pine("b|c"),
            pine("global", readOnly = true),
        ))
        val back = decodeSettings(s.encode())
        assertEquals(s.addressBooks, back.addressBooks)
        assertEquals(Settings().addressBooks, decodeSettings(Settings().encode()).addressBooks)
    }

    @Test fun booksListPrimaryFirstGlobalsReadOnly() {
        val s = Settings(addressBooks = listOf(
            pine("ab"),
            pine("two"),
            pine("g", readOnly = true),
            pine("two", readOnly = true),
        ))
        assertEquals(listOf("ab" to false, "two" to false, "g" to true), s.alpineBooks())
        assertEquals(emptyList<Pair<String, Boolean>>(), Settings(addressBooks = emptyList()).alpineBooks())
    }

    @Test fun sourceIdRoundTrip() {
        val id = alpineBookSourceId("acct|1", "INBOX.a|b c")
        assertEquals("acct|1" to "INBOX.a|b c", alpineBookOf(id))
        assertNull(alpineBookOf("pine"))
        assertNull(alpineBookOf(androidSourceId("t", "n")))
    }

    @Test fun pinercImportsFurtherAndGlobalBooks() {
        val p = previewPinerc(
            "address-book={imap.example.com}ab, {imap.example.com}work, {other.example.com}x\n" +
                "global-address-book={imap.example.com}company\n",
            host,
        )
        assertEquals(listOf(pine("ab"), pine("work"), pine("company", readOnly = true)), p.next.addressBooks)
        assertTrue(p.skipped.contains("other.example.com is a different server"))
    }

    @Test fun pinercExportWritesAllBooks() {
        val text = encodePinerc(host.copy(addressBooks = listOf(pine("ab"), pine("work"), pine("company", readOnly = true))))
        assertTrue(text, text.contains("address-book={imap.example.com}ab"))
        assertTrue(text, text.contains("{imap.example.com}work"))
        assertTrue(text, text.contains("global-address-book={imap.example.com}company"))
    }

    private fun pine(mailbox: String, readOnly: Boolean = false) =
        AddressBookEntry(kind = "pine", mailbox = mailbox, readOnly = readOnly)
}
