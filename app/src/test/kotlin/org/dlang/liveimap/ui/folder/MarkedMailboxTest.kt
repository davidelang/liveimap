package org.dlang.liveimap.ui.folder

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkedMailboxTest {
    private val rows = listOf("INBOX" to null, "Sent Items" to "\\HasNoChildren \\sent", "Drafts" to "\\Drafts")

    @Test
    fun theServerMarkOnlySuggestsAFolder() {
        assertEquals("Sent Items", markedMailbox(rows, "\\Sent", { it.first }, { it.second }))
        assertEquals("Drafts", markedMailbox(rows, "\\Drafts", { it.first }, { it.second }))
        assertEquals(null, markedMailbox(rows, "\\Trash", { it.first }, { it.second }))
        assertEquals(null, markedMailbox(rows, "", { it.first }, { it.second }))
    }
}
