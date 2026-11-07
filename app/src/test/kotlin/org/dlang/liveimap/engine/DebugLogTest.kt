package org.dlang.liveimap.engine

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugLogTest {
    private val original = DebugLog.sink

    @After fun restore() {
        DebugLog.sink = original
        DebugLog.enabled = false
    }

    @Test fun theToggleGatesEveryEvent() {
        val seen = ArrayList<Pair<String, String>>()
        DebugLog.sink = { tag, line -> seen.add(tag to line) }
        var built = 0
        DebugLog.enabled = false
        DebugLog.event("stack") { built++; "open screen settings" }
        assertTrue(seen.isEmpty())
        assertEquals("an off toggle does not even build the line", 0, built)
        DebugLog.enabled = true
        DebugLog.event("stack") { built++; "open screen settings" }
        TrafficLog.status("Reconnecting")
        assertEquals(1, built)
        assertEquals(listOf("liveimap-stack" to "open screen settings", "liveimap-status" to "Reconnecting"), seen)
        DebugLog.enabled = false
        TrafficLog.status("Closed (background)")
        assertEquals(2, seen.size)
    }

    @Test fun theFileRotatesAndKeepsRawLines() {
        val dir = Files.createTempDirectory("debug-log-test-").toFile()
        try {
            val log = DebugLogFile(File(dir, "logs"), "debug-w0", maxBytes = 64, keep = 3)
            for (i in 0 until 20) log.append("line $i LOGIN user <raw stays raw>")
            val files = log.files()
            assertEquals(3, files.size)
            assertTrue(files.all { it.length() <= 64 })
            assertTrue(files.first().readText().contains("line 19"))
            assertFalse(files.any { it.readText().contains("line 0 ") })
        } finally {
            check(dir.deleteRecursively())
        }
    }
}
