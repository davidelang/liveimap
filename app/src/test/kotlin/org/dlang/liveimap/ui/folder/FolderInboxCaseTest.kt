package org.dlang.liveimap.ui.folder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F66: INBOX matched case-insensitively. */
class FolderInboxCaseTest {
    @Test
    fun inboxIsCaseInsensitive() {
        assertTrue(isInboxName("Inbox"))
        assertTrue(isInboxName("INBOX"))
        assertFalse(isInboxName("INBOX.lists"))
    }
}
