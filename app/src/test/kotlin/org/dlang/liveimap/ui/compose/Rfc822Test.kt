package org.dlang.liveimap.ui.compose

import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.session.MailFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Rfc822Test {
    @Test
    fun bounceRetainsTheOriginalMessageId() {
        val original = """
            From: Ann <ann@example.com>
            Message-ID: <orig@example.com>
            Subject: Hello

            Body
            """.trimIndent().replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
        val bounced = buildBounce(
            original,
            resentFrom = "Me <me@example.com>",
            resentTo = "Bo <bo@example.com>",
            resentDate = "Tue, 30 Sep 2026 00:00:00 +0000",
            resentMessageId = "<new@example.com>",
        )
        val text = bounced.toString(Charsets.UTF_8)
        assertTrue(text.startsWith("Resent-From: Me <me@example.com>\r\n"))
        assertTrue(text.contains("Resent-To: Bo <bo@example.com>\r\n"))
        assertTrue(text.contains("Resent-Date: Tue, 30 Sep 2026 00:00:00 +0000\r\n"))
        assertTrue(text.contains("Resent-Message-ID: <new@example.com>\r\n"))
        assertTrue(text.contains("From: Ann <ann@example.com>\r\n"))
        val ids = Regex("(?m)^Message-ID:.*").findAll(text).map { it.value.trimEnd('\r') }.toList()
        assertEquals(listOf("Message-ID: <orig@example.com>"), ids)
    }

    @Test
    fun wrapsAtColumnOnSpaces() {
        assertEquals("one two\nthree four", wrapPlain("one two three four", 10))
        assertEquals("one two\nthree four", wrapPlain("one\ttwo three four", 10))
        assertEquals("one two three four", wrapPlain("one two three four", 0))
        assertEquals("one two  \nthree  \nfour", flowPlain("one two three four", 10))
        assertEquals("one\ttwo  \nthree  \nfour", flowPlain("one\ttwo three four", 10))
        assertEquals("one two three four", flowPlain("one two three four", 0))
        assertEquals("abcdefghijk", flowPlain("abcdefghijk", 5))
        assertEquals(" > one  \n > two  \n > three", flowPlain("> one two three", 10))
        assertEquals("-- ", flowPlain("-- ", 10))
        assertEquals(
            " From the start of  \nthe line here",
            flowPlain("From the start of the line here", 20),
        )
    }

    @Test
    fun flowedLineThatFitsKeepsSpacesAndTabs() {
        assertEquals("    keep  me", flowPlain("    keep  me", 72))
        assertEquals("one\ttwo", flowPlain("one\ttwo", 72))
        assertEquals("-- ", flowPlain("-- ", 10))
        assertEquals("-- ", flowPlain("--", 10))
    }

    @Test
    fun quotedWrapKeepsPrefix() {
        assertEquals("> one two\n> three", wrapPlain("> one two three", 10))
    }

    @Test
    fun longWordIsNotSplit() {
        assertEquals("abcdefghijk", wrapPlain("abcdefghijk", 5))
    }

    @Test
    fun emptyChunkBeforeSizeEndsEarly() = runBlocking {
        try {
            peekWireBytes(size = 10, step = 4, base64 = false) { _, _ -> ByteArray(0) }
            fail("an empty chunk must raise")
        } catch (error: MailFailure) {
            assertEquals("part ended early", error.text)
        }
    }
}
