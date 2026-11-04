package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressSaveTest {
    private fun pine(mailbox: String, readOnly: Boolean = false, saveTarget: Boolean = false) =
        AddressBookEntry(kind = "pine", mailbox = mailbox, readOnly = readOnly, saveTarget = saveTarget)

    private fun android(name: String, type: String = "com.google", saveTarget: Boolean = false) =
        AddressBookEntry(kind = "android", accountType = type, accountName = name, saveTarget = saveTarget)

    @Test fun readOnlyPineIsNotWritable() {
        assertFalse(pine("ab", readOnly = true).isWritableBook())
        assertTrue(pine("ab").isWritableBook())
        assertFalse(pine("").isWritableBook())
        assertTrue(android("Ada").isWritableBook())
    }

    @Test fun noneWritesNothing() {
        assertEquals(AddressSave.None, Settings().addressSave())
        assertEquals(AddressSave.None, Settings(addressBooks = listOf(pine("ab", readOnly = true))).addressSave())
    }

    @Test fun oneBookIsUsed() {
        val book = pine("ab")
        assertEquals(AddressSave.One(book), Settings(addressBooks = listOf(pine("ro", readOnly = true), book)).addressSave())
        val account = android("Ada")
        assertEquals(AddressSave.One(account), Settings(addressBooks = listOf(account)).addressSave())
        assertEquals(
            AddressSave.One(account),
            Settings(addressBooks = listOf(pine("ro", readOnly = true, saveTarget = true), account)).addressSave(),
        )
    }

    @Test fun severalAskAndKeepOrder() {
        val books = listOf(pine("ab"), android("Ada"), pine("cd"))
        assertEquals(AddressSave.Ask, Settings(addressBooks = books).addressSave())
        assertEquals(books, saveBookChoices(books))
        assertEquals(listOf(pine("ab"), android("Ada")), saveBookChoices(listOf(pine("ab"), pine("ab"), android("Ada"), pine("ro", readOnly = true))))
    }

    @Test fun markedTargetSkipsTheQuestion() {
        val markedAndroid = android("Ada", saveTarget = true)
        val books = listOf(pine("ab"), markedAndroid, pine("cd"))
        assertEquals(AddressSave.One(markedAndroid), Settings(addressBooks = books).addressSave())
        val markedPine = markAddressSaveTarget(books, 2)
        assertEquals(listOf(false, false, true), markedPine.map { it.saveTarget })
        assertEquals(listOf("ab", "Ada", "cd"), markedPine.map { if (it.kind == "pine") it.mailbox else it.accountName })
        assertEquals(AddressSave.One(markedPine[2]), Settings(addressBooks = markedPine).addressSave())
    }

    @Test fun readOnlyCannotBeMarked() {
        val books = listOf(pine("ro", readOnly = true, saveTarget = true), pine("ab"), android("Ada"))
        assertEquals(AddressSave.Ask, Settings(addressBooks = books).addressSave())
        val marked = markAddressSaveTarget(books, 0)
        assertTrue(marked.none { it.saveTarget })
        assertEquals(books.map { Triple(it.kind, it.mailbox, it.accountName) }, marked.map { Triple(it.kind, it.mailbox, it.accountName) })
    }

    @Test fun jsonKeepsOrderAndSaveTarget() {
        val books = listOf(android("Ada", saveTarget = true), pine("ab", readOnly = true), pine("cd"))
        assertEquals(books, readAddressBooks(addressBooksJson(books)))
    }
}
