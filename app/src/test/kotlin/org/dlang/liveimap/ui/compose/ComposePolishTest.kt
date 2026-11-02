package org.dlang.liveimap.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class ComposePolishTest {
    @Test
    fun commaCommitsAddressAndClearsBuffer() {
        val next = commitChip("", "ada@example.com,")
        assertEquals("ada@example.com", next.stored)
        assertEquals("", next.buffer)
    }

    @Test
    fun bufferWithoutCommaOrNewlineDoesNotCommit() {
        val next = commitChip("", "ada@example.com")
        assertEquals("", next.stored)
        assertEquals("ada@example.com", next.buffer)
    }

    @Test
    fun newlineCommitsAddressAndClearsBuffer() {
        val next = commitChip("", "ada@example.com\n")
        assertEquals("ada@example.com", next.stored)
        assertEquals("", next.buffer)
    }

    @Test
    fun removeDropsOneAddress() {
        assertEquals("c@d.com", removeChipAddress("a@b.com, c@d.com", "a@b.com"))
        assertEquals("a@b.com", removeChipAddress("a@b.com, c@d.com", "c@d.com"))
    }

    @Test
    fun replyAllDropsAlternateAndAccount() {
        val (to, cc) = replyRecipients(
            replyAll = true,
            replyTo = listOf("List <list@example.com>"),
            from = listOf("Ann <ann@example.com>"),
            to = listOf("Me <me@example.com>", "Alt <alt@example.com>", "Bo <bo@example.com>"),
            cc = listOf("alt@example.com", "Cy <cy@example.com>"),
            accountEmail = "me@example.com",
            altAddresses = listOf("alt@example.com"),
            useReplyTo = false,
        )
        assertEquals(listOf("Ann <ann@example.com>", "Bo <bo@example.com>"), to)
        assertEquals(listOf("Cy <cy@example.com>"), cc)
    }

    @Test
    fun replyAllDropsFoldedAccountAndAlternate() {
        val (to, cc) = replyRecipients(
            replyAll = true,
            replyTo = emptyList(),
            from = listOf("Ann <ann@example.com>"),
            to = listOf(
                "Me <d.a.v.i.d.l.a.n.g+x@googlemail.com>",
                "Alt <a.l.t+z@gmail.com>",
                "Bo <bo@example.com>",
                "Dots <a.b@x.com>",
            ),
            cc = listOf("ab+z@x.com", "ab@x.com"),
            accountEmail = "davidlang@gmail.com",
            altAddresses = listOf("Alt Name <alt@googlemail.com>"),
            useReplyTo = false,
        )
        assertEquals(
            listOf("Ann <ann@example.com>", "Bo <bo@example.com>", "Dots <a.b@x.com>"),
            to,
        )
        assertEquals(listOf("ab+z@x.com", "ab@x.com"), cc)
    }

    @Test
    fun useReplyToFalseKeepsFrom() {
        val (to, cc) = replyRecipients(
            replyAll = false,
            replyTo = listOf("List <list@example.com>"),
            from = listOf("Ann <ann@example.com>"),
            to = listOf("me@example.com"),
            cc = emptyList(),
            accountEmail = "me@example.com",
            altAddresses = emptyList(),
            useReplyTo = false,
        )
        assertEquals(listOf("Ann <ann@example.com>"), to)
        assertEquals(emptyList<String>(), cc)
    }

    @Test
    fun useReplyToTrueUsesReplyTo() {
        val (to, cc) = replyRecipients(
            replyAll = false,
            replyTo = listOf("List <list@example.com>"),
            from = listOf("Ann <ann@example.com>"),
            to = listOf("me@example.com"),
            cc = emptyList(),
            accountEmail = "me@example.com",
            useReplyTo = true,
        )
        assertEquals(listOf("List <list@example.com>"), to)
        assertEquals(emptyList<String>(), cc)
    }

    @Test
    fun appendUidReturnsTheSecondNumber() {
        assertEquals(3955L, appendUidFromOk("[APPENDUID 38505 3955]"))
    }

    @Test
    fun missingAppendUidReturnsZero() {
        assertEquals(0L, appendUidFromOk("a001 OK APPEND completed"))
    }
}
