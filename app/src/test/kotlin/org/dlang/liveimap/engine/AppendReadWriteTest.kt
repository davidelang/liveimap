package org.dlang.liveimap.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Courier 5 refuses APPEND into the box it has selected read-only; the session re-SELECTs it read-write first. */
class AppendReadWriteTest {
    private val src = File("src/main/kotlin/org/dlang/liveimap/engine/LibetpanMailSession.kt").readText()

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

    @Test
    fun bothAppendPathsPrepareBeforeNativeAppend() {
        val prepared = Regex("""writableForAppend\(wire\(mailbox\)\)\s*\n\s*nativeAppend\(""").findAll(src).count()
        assertEquals(2, prepared)
        assertEquals(2, Regex("""nativeAppend\(requireHandle\(\)""").findAll(src).count())
        assertTrue(src.contains("openMailbox(name, write = true)"))
    }
}
