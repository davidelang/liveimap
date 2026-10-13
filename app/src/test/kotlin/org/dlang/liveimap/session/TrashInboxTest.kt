package org.dlang.liveimap.session

import org.junit.Assert.assertEquals
import org.junit.Test

/** F90: INBOX is never treated as Trash. */
class TrashInboxTest {
    @Test fun inboxIsNeverTrash() {
        assertEquals("", usableTrashName("INBOX"))
        assertEquals("", usableTrashName("Inbox"))
        assertEquals("Trash", usableTrashName("Trash"))
        val entries = listOf(
            FolderEntry("INBOX", '/', specialUse = "\\Trash"),
            FolderEntry("Deleted", '/', specialUse = "\\Trash"),
        )
        assertEquals("Deleted", mailboxMarkedTrash(entries))
    }
}
