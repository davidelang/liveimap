package org.dlang.liveimap.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** F101/F115: CLOSE (auto-expunge) happens only when the user leaves a folder, and only on that folder. */
class CloseOnlyOnLeaveTest {
    private val src = File("src/main/kotlin/org/dlang/liveimap/engine/LibetpanMailSession.kt").readText()

    private fun body(name: String): String {
        val start = src.indexOf(name)
        assertTrue(name, start >= 0)
        var depth = 0
        var i = src.indexOf('{', start)
        val open = i
        while (true) {
            when (src[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return src.substring(open, i)
            }
            i++
        }
    }

    @Test
    fun onlyTheLeavePathCloses() {
        val callers = Regex("""closeSelectedMailbox\(\)|nativeCloseMailbox\(""").findAll(src).count()
        // closeSelectedMailbox's definition and its one native call, the external declaration, one caller (leave)
        assertEquals(4, callers)
        assertTrue(body("override suspend fun expungeOnLeave(mailbox: String)").contains("closeSelectedMailbox()"))
        assertFalse(body("private fun openMailbox(").contains("closeSelectedMailbox"))
        assertFalse(body("private fun closeLiveSession(").contains("nativeCloseMailbox"))
        assertFalse(body("override suspend fun unselect()").contains("closeSelectedMailbox"))
    }

    @Test
    fun leaveClosesTheNamedFolder() {
        val leave = body("override suspend fun expungeOnLeave(mailbox: String)")
        assertTrue(leave.contains("openMailbox(wire(mailbox), write = true)"))
        assertTrue(leave.contains("keeper.write("))
    }
}
