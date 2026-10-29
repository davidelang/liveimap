package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contacts phases 3 and 5: extra and global Alpine books, and per-account book source ids. */
class ExtraBooksTest {
    private val host = Settings(imapHost = "imap.example.com")

    @Test fun settingsRoundTrip() {
        val s = Settings(addressBookMailbox = "ab", extraAddressBooks = listOf("work, x", "b|c"), globalAddressBooks = listOf("global"))
        val back = decodeSettings(s.encode())
        assertEquals(s.extraAddressBooks, back.extraAddressBooks)
        assertEquals(s.globalAddressBooks, back.globalAddressBooks)
        assertEquals(emptyList<String>(), decodeSettings(Settings().encode()).extraAddressBooks)
    }

    @Test fun booksListPrimaryFirstGlobalsReadOnly() {
        val s = Settings(addressBookMailbox = "ab", extraAddressBooks = listOf("ab", "two"), globalAddressBooks = listOf("g", "two"))
        assertEquals(listOf("ab" to false, "two" to false, "g" to true), s.alpineBooks())
        assertEquals(emptyList<Pair<String, Boolean>>(), Settings().alpineBooks())
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
        assertEquals("ab", p.next.addressBookMailbox)
        assertEquals(listOf("work"), p.next.extraAddressBooks)
        assertEquals(listOf("company"), p.next.globalAddressBooks)
        assertTrue(p.skipped.contains("other.example.com is a different server"))
    }

    @Test fun pinercExportWritesAllBooks() {
        val text = encodePinerc(host.copy(addressBookMailbox = "ab", extraAddressBooks = listOf("work"), globalAddressBooks = listOf("company")))
        assertTrue(text, text.contains("address-book={imap.example.com}ab"))
        assertTrue(text, text.contains("{imap.example.com}work"))
        assertTrue(text, text.contains("global-address-book={imap.example.com}company"))
    }
}
