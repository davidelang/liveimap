package org.dlang.liveimap.ui.contacts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlpineEditTest {
    private fun e(n: String, a: String, f: String = "") = AlpineEntry(n, f, a, "", "")
    private val book = listOf(e("ada", "ada@x.org", "Lovelace, Ada"), e("bob", "bob@x.org"), e("team", "(ada, carol@x.org)"))

    private fun done(o: EditOutcome) = (o as EditOutcome.Done)

    @Test fun addValidates() {
        assertTrue(applyBookEdit(book, BookEdit.Add(e("ADA", "z@x"))) is EditOutcome.Refused)
        assertTrue(applyBookEdit(book, BookEdit.Add(e("a b", "z@x"))) is EditOutcome.Refused)
        assertTrue(applyBookEdit(book, BookEdit.Add(e("z", ""))) is EditOutcome.Refused)
        assertEquals(4, done(applyBookEdit(book, BookEdit.Add(e("zed", "z@x\t")))).entries.size)
        assertEquals("z@x", done(applyBookEdit(book, BookEdit.Add(e("zed", "z@x\t")))).entries.last().address)
    }

    @Test fun renameUpdatesLists() {
        val out = done(applyBookEdit(book, BookEdit.Update("ada", e("lovelace", "ada@x.org"))))
        assertEquals("(lovelace, carol@x.org)", out.entries.first { it.nickname == "team" }.address)
        assertTrue(out.notes.single().contains("team"))
    }

    @Test fun editListMembers() {
        val out = done(applyBookEdit(book, BookEdit.Update("team", e("team", "(ada, bob, d@x.org)"))))
        assertEquals(listOf("ada", "bob", "d@x.org"), listMembersOf(out.entries.first { it.nickname == "team" }))
    }

    @Test fun deleteWarnsAboutLists() {
        val out = done(applyBookEdit(book, BookEdit.Delete("ada")))
        assertEquals(2, out.entries.size)
        assertTrue(out.notes.single().contains("team"))
        assertTrue(applyBookEdit(book, BookEdit.Delete("nobody")) is EditOutcome.Refused)
    }

    @Test fun rebaseCleanWhenOtherEntryChanged() {
        val latest = book.map { if (it.nickname == "bob") it.copy(fullname = "Bob B") else it }
        val rb = rebaseEdit(book, latest, BookEdit.Update("ada", e("ada", "new@x.org")))
        val o = done((rb as Rebase.Clean).outcome)
        assertEquals("Bob B", o.entries.first { it.nickname == "bob" }.fullname)
        assertEquals("new@x.org", o.entries.first { it.nickname == "ada" }.address)
    }

    @Test fun rebaseConflictWhenSameEntryChanged() {
        val latest = book.map { if (it.nickname == "ada") it.copy(address = "other@x.org") else it }
        val rb = rebaseEdit(book, latest, BookEdit.Update("ada", e("ada", "new@x.org")))
        assertTrue(rb is Rebase.Conflict)
        assertEquals("other@x.org", (rb as Rebase.Conflict).theirs?.address)
        val gone = rebaseEdit(book, book.filter { it.nickname != "ada" }, BookEdit.Delete("ada"))
        assertTrue(gone is Rebase.Conflict)
    }
}
