package org.dlang.liveimap.ui.contacts

import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.decodeSettings
import org.dlang.liveimap.settings.encode
import org.dlang.liveimap.settings.previewPinerc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlpineBookWriteTest {
    @Test
    fun formatRoundTripsEmptyNicknamePlaintextAndList() {
        val entries = listOf(
            AlpineEntry("", "Ada Lovelace", "ada@example.com", "", ""),
            AlpineEntry("ada", "Ada", "ada@example.com", "Sent", "keep [plaintext]"),
            AlpineEntry("team", "Team", "(ada@example.com, bo@example.com)", "", "list note"),
        )
        val parsed = parseAlpineBook(formatAlpineBook(entries))
        assertEquals(entries, parsed)
        assertEquals("", parsed[0].nickname)
        assertTrue(parsed[1].comments.contains("[plaintext]"))
        assertFalse(parsed[1].address.contains("[plaintext]"))
        assertEquals("(ada@example.com, bo@example.com)", parsed[2].address)
    }

    @Test
    fun tabAndNewlineInAFieldBecomeSpaces() {
        val entry = parseAlpineBook(
            formatAlpineBook(listOf(AlpineEntry("a\tb", "Line\nName", "ada@example.com", "", "c\r\nd"))),
        ).single()
        assertEquals("a b", entry.nickname)
        assertEquals("Line Name", entry.fullname)
        assertEquals("c  d", entry.comments)
    }

    @Test
    fun historyWindowDropsOnlyOlderRevisions() {
        val uids = listOf(1L, 2L, 3L, 4L, 5L, 6L)
        assertEquals(listOf(2L), revisionsToExpunge(uids, headerUid = 1L, history = 3, neverTrim = false, uidPlus = true))
        assertEquals(emptyList<Long>(), revisionsToExpunge(uids, 1L, 3, neverTrim = true, uidPlus = true))
        assertEquals(emptyList<Long>(), revisionsToExpunge(uids, 1L, 3, neverTrim = false, uidPlus = false))
        val samples = listOf(
            revisionsToExpunge(uids, 1L, 3, false, true),
            revisionsToExpunge(uids, 1L, 0, false, true),
            revisionsToExpunge(listOf(5L, 1L, 2L), 1L, 0, false, true),
            revisionsToExpunge(uids, 1L, 3, true, true),
            revisionsToExpunge(uids, 1L, 3, false, false),
        )
        for (sample in samples) assertFalse(sample.contains(1L))
    }

    @Test
    fun missingHistoryKeysStayAtDefaults() {
        val text = Settings().encode()
        assertTrue(text.contains("addressBookHistory="))
        assertTrue(text.contains("addressBookNeverTrim="))
        val decoded = decodeSettings(text)
        assertEquals(3, decoded.addressBookHistory)
        assertFalse(decoded.addressBookNeverTrim)
        val stripped = Settings(addressBookHistory = 5, addressBookNeverTrim = true).encode()
            .lineSequence()
            .filterNot { it.startsWith("addressBookHistory=") || it.startsWith("addressBookNeverTrim=") }
            .joinToString("\n")
        assertEquals(3, decodeSettings(stripped).addressBookHistory)
        assertFalse(decodeSettings(stripped).addressBookNeverTrim)
        val round = Settings(addressBookHistory = 5, addressBookNeverTrim = true)
        assertEquals(round, decodeSettings(round.encode()))
    }

    @Test
    fun pinercHistoryIsDigitsOnlyAndDoesNotTrim() {
        val ok = previewPinerc("remote-abook-history=4\n", Settings())
        assertEquals(4, ok.next.addressBookHistory)
        assertFalse(ok.next.addressBookNeverTrim)
        assertFalse(ok.skipped.contains("Address book history is not a number."))
        val bad = previewPinerc("remote-abook-history=nope\n", Settings())
        assertEquals(3, bad.next.addressBookHistory)
        assertFalse(bad.next.addressBookNeverTrim)
        assertTrue(bad.skipped.contains("Address book history is not a number."))
    }
}
