package org.dlang.liveimap.jmap

import org.junit.Assert.assertEquals
import org.junit.Test

class JmapOpenFolderTest {
    @Test
    fun lineUsesFromAndSubject() {
        assertEquals("Ada  Hello", jmapMessageLine(openMessage("Ada", "Hello")))
        assertEquals("Hello", jmapMessageLine(openMessage("", "Hello")))
        assertEquals("Ada  No subject", jmapMessageLine(openMessage("Ada", "")))
        assertEquals("Ada  No subject", jmapMessageLine(openMessage("Ada", "   ")))
        assertEquals("No subject", jmapMessageLine(openMessage("", "")))
        assertEquals("No subject", jmapMessageLine(openMessage("  ", " ")))
        val unread = openMessage("Ada", "Hello").copy(unread = true)
        assertEquals("Ada  Hello", jmapMessageLine(unread))
    }
}

private fun openMessage(from: String, subject: String): JmapMessage {
    return JmapMessage(
        id = "e1",
        threadId = "t1",
        subject = subject,
        from = from,
        receivedAt = "",
        preview = "",
        unread = false,
        size = 0L,
    )
}
