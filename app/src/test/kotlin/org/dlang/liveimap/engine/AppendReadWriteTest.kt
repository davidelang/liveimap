package org.dlang.liveimap.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Courier 5 refuses APPEND into the box it has selected read-only; the session re-SELECTs it read-write first. */
class AppendReadWriteTest {
    @Test
    fun readOnlySameBoxNeedsReadWrite() {
        assertTrue(appendNeedsReadWrite("INBOX.LiveIMAP-Test", false, "INBOX.LiveIMAP-Test"))
    }

    @Test
    fun otherBoxOrWritableOrNothingSelectedDoesNot() {
        assertFalse(appendNeedsReadWrite("INBOX", false, "INBOX.LiveIMAP-Test"))
        assertFalse(appendNeedsReadWrite("INBOX.LiveIMAP-Test", true, "INBOX.LiveIMAP-Test"))
        assertFalse(appendNeedsReadWrite(null, false, "INBOX.LiveIMAP-Test"))
    }
}
