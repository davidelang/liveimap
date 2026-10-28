package org.dlang.liveimap.ui.contacts

import org.dlang.liveimap.session.SelectedAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlpineBookTest {


    @Test
    fun plaintextIsNotPartOfTheEmail() {
        val entry = parseAlpineBook(
            "ada\tAda Lovelace\tada@example.com\tSent\tkeep [plaintext]\n",
        ).single()
        assertEquals("ada", entry.nickname)
        assertEquals("Ada Lovelace", entry.fullname)
        assertEquals("ada@example.com", entry.address)
        assertEquals("Sent", entry.fcc)
        assertEquals("keep [plaintext]", entry.comments)
        val picked = pickedAddresses(entry).single()
        assertEquals(SelectedAddress("Ada Lovelace", "ada@example.com"), picked)
        assertFalse(picked.email.contains("[plaintext]"))
        assertTrue(entry.comments.contains("[plaintext]"))
        val glued = parseAlpineBook("ada\tAda\tada@example.com[plaintext]\t\t\n").single()
        assertEquals("ada@example.com", glued.address)
        assertEquals("[plaintext]", glued.comments)
        assertEquals("ada@example.com", pickedAddresses(glued).single().email)
        assertFalse(pickedAddresses(glued).single().email.contains("[plaintext]"))
    }

    @Test
    fun emptyNicknameStillParses() {
        val entry = parseAlpineBook("\tAda Lovelace\tada@example.com\n").single()
        assertEquals("", entry.nickname)
        assertEquals("Ada Lovelace", entry.fullname)
        assertEquals("ada@example.com", entry.address)
        assertEquals("", entry.fcc)
        assertEquals("", entry.comments)
        assertEquals(listOf(SelectedAddress("Ada Lovelace", "ada@example.com")), pickedAddresses(entry))
    }

    @Test
    fun spaceContinuationStaysOneEntry() {
        val entry = parseAlpineBook(
            "ada\tAda Lovelace\tada@example.com\t\tnote\n continues\n",
        ).single()
        assertEquals("note continues", entry.comments)
        assertEquals("ada@example.com", entry.address)
    }

    @Test
    fun distributionListSkipsEmptyAddresses() {
        val entry = parseAlpineBook(
            "team\tTeam\t(ada@example.com,\n \"Bo\" <bo@example.com>, )\tSent\t\n",
        ).single()
        assertEquals("(ada@example.com, \"Bo\" <bo@example.com>, )", entry.address)
        assertEquals("Sent", entry.fcc)
        assertEquals(
            listOf(
                // F189: each member keeps its own name, never the list's.
                SelectedAddress("", "ada@example.com"),
                SelectedAddress("Bo", "bo@example.com"),
            ),
            pickedAddresses(entry),
        )
        val blank = parseAlpineBook("nick\tName\t\t\tcomment\n").single()
        assertTrue(pickedAddresses(blank).isEmpty())
    }




    @Test
    fun bookHasAddressMatchesEmailWithoutCase() {
        val entries = listOf(AlpineEntry("ada", "Ada", "Ada@Example.com", "", ""))
        assertTrue(bookHasAddress(entries, "ada@example.com"))
        assertFalse(bookHasAddress(entries, "other@example.com"))
        val named = listOf(AlpineEntry("ada", "Ada", "Ada Lovelace <Ada@Example.com>", "", ""))
        assertTrue(bookHasAddress(named, "ada@example.com"))
        assertFalse(bookHasAddress(named, "other@example.com"))
    }
    @Test
    fun listNicknamesExpandAgainstTheBookAndUnknownOnesAreDropped() {
        val book = parseAlpineBook(
            "team\tTeam\t(alice@x.example, bob, team2, ghost)\t\t\n" +
                "bob\tBob Builder\tbob@y.example\t\t\n" +
                "team2\tTeam Two\t(carol@z.example, team)\t\t\n",
        )
        assertEquals(
            listOf(
                SelectedAddress("", "alice@x.example"),
                SelectedAddress("Bob Builder", "bob@y.example"),
                SelectedAddress("", "carol@z.example"),
            ),
            pickedAddresses(book[0], book),
        )
        // Without the book, bare nicknames are never sent as local names.
        assertEquals(listOf(SelectedAddress("", "alice@x.example")), pickedAddresses(book[0]))
    }
}
