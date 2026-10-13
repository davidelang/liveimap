package org.dlang.liveimap.ui.folder

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F66: INBOX matched case-insensitively; the next-unseen walk has a LIST budget. */
class FolderInboxCaseTest {
    @Test
    fun inboxIsCaseInsensitive() {
        assertTrue(isInboxName("Inbox"))
        assertTrue(isInboxName("INBOX"))
        assertFalse(isInboxName("INBOX.lists"))
        val src = File("src/main/kotlin/org/dlang/liveimap/ui/folder/FolderListModel.kt").readText()
        assertFalse(src.contains("entry.mailbox == \"INBOX\""))
        assertTrue(src.contains("if (unseenListBudget <= 0) return emptyList()"))
    }
}
