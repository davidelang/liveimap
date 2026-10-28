package org.dlang.liveimap.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRefTest {
    @Test
    fun sameMailboxFoldsOnlyInbox() {
        assertTrue(sameMailbox("INBOX", "inbox"))
        assertTrue(sameMailbox("Spam", "Spam"))
        assertTrue(!sameMailbox("Spam", "spam"))
    }

    @Test
    fun chooserHidesTheCurrentFolder() {
        val rows = listOf("INBOX", "Archive", "Spam")
        org.junit.Assert.assertEquals(listOf("Archive", "Spam"), org.dlang.liveimap.ui.folder.chooserRows(rows, "inbox") { it })
        org.junit.Assert.assertEquals(rows, org.dlang.liveimap.ui.folder.chooserRows(rows, "") { it })
    }

    @Test
    fun composeSeedCarriesRefs() {
        val seed = ComposeSeed(ComposeKind.Reply, "Archive", listOf(4L), uidValidity = 9L)
        org.junit.Assert.assertEquals(listOf(MessageRef("a", "Archive", 9L, 4L)), seed.refs("a"))
        assertTrue(ComposeSeed(ComposeKind.Reply, "Archive", listOf(4L)).refs("a").isEmpty())
    }

    @Test
    fun twoAccountsWithTheSameUserAndHostGetDifferentKeys() {
        val userHost = "dlang@lang.hm"
        val a = MessageRef("11111111-1111-1111-1111-111111111111", "INBOX", 7L, 5L)
        val b = MessageRef("22222222-2222-2222-2222-222222222222", "INBOX", 7L, 5L)
        assertTrue(a.key != b.key)
        assertTrue(!a.key.contains(userHost))
        assertTrue(!b.key.contains(userHost))
        assertEquals("11111111-1111-1111-1111-111111111111", a.accountId)
    }
}
