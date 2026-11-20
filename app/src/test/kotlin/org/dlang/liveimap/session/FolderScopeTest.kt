package org.dlang.liveimap.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Pass 5 (David, 2026-10-12): every folder operation names its folder and runs as one lowest-layer call. */
class FolderScopeTest {
    @Test
    fun epochMismatchRefusesTheOldUids() {
        epochAfterReconnect(4L, 4L, "INBOX")
        epochAfterReconnect(0L, 9L, "INBOX")
        val changed = assertThrows(EpochChanged::class.java) {
            epochAfterReconnect(4L, 9L, "INBOX")
        }
        assertEquals("INBOX", changed.mailbox)
    }
}

