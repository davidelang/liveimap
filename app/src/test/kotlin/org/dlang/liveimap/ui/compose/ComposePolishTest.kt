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
}
