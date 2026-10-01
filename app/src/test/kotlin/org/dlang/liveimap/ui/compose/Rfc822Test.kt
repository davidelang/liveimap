package org.dlang.liveimap.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Rfc822Test {
    @Test
    fun replyAllDropsTheAccountAddress() {
        val parsed = rfc(
            """
            From: Ann <ann@example.com>
            To: Ann <ann@example.com>, Me <me@example.com>, Bo <bo@example.com>
            Cc: me@example.com, Cy <cy@example.com>
            Bcc: hidden@example.com
            Subject: Hello
            Message-ID: <orig@example.com>

            Secret body
            """,
        )
        val draft = replyDraft(
            replyAll = true,
            message = parsed,
            accountEmail = "me@example.com",
            peekedText = "peeked line",
        )
        assertEquals(listOf("Ann <ann@example.com>", "Bo <bo@example.com>"), draft.to)
        assertEquals(listOf("Cy <cy@example.com>"), draft.cc)
        val recipients = (draft.to + draft.cc).joinToString(" ")
        assertFalse(recipients.contains("me@example.com"))
        assertFalse(recipients.contains("hidden@example.com"))
        val built = buildPlain(
            PlainMessage(
                fromName = "Self",
                fromEmail = "self@example.com",
                to = draft.to,
                cc = draft.cc,
                bcc = emptyList(),
                subject = draft.subject,
                body = draft.body,
                messageId = "<new@example.com>",
                date = "Tue, 30 Sep 2026 00:00:00 +0000",
                inReplyTo = draft.inReplyTo,
                references = draft.references,
            ),
        )
        val text = built.rfc822.toString(Charsets.UTF_8)
        assertFalse(text.contains("me@example.com"))
        assertFalse(text.contains("hidden@example.com"))
        assertTrue(text.contains("To: Ann <ann@example.com>, Bo <bo@example.com>"))
        assertTrue(text.contains("Cc: Cy <cy@example.com>"))
    }

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
    fun bccIsNotInTheHeaderBlock() {
        val built = buildPlain(
            PlainMessage(
                fromName = "Me",
                fromEmail = "me@example.com",
                to = listOf("ann@example.com"),
                cc = listOf("cy@example.com"),
                bcc = listOf("secret@example.com"),
                subject = "Hi",
                body = "Hello",
                messageId = "<new@example.com>",
                date = "Tue, 30 Sep 2026 00:00:00 +0000",
            ),
        )
        val text = built.rfc822.toString(Charsets.UTF_8)
        val header = text.substringBefore("\r\n\r\n")
        assertTrue(header.contains("From: Me <me@example.com>"))
        assertTrue(header.contains("To: ann@example.com"))
        assertTrue(header.contains("Cc: cy@example.com"))
        assertTrue(header.contains("Content-Type: text/plain; charset=utf-8"))
        assertTrue(header.lineSequence().none { it.startsWith("Bcc:", ignoreCase = true) })
        assertFalse(header.contains("secret@example.com"))
        assertFalse(text.contains("secret@example.com"))
        assertFalse(text.contains("Bcc:"))
        assertEquals(
            listOf("ann@example.com", "cy@example.com", "secret@example.com"),
            built.recipients,
        )
        assertTrue(text.substringAfter("\r\n\r\n").contains("Hello"))
    }

    @Test
    fun replyInReplyToComesFromTheFetchedHeader() {
        val parsed = rfc(
            """
            From: Ann <ann@example.com>
            Reply-To: List <list@example.com>
            To: me@example.com
            Cc: cy@example.com
            Subject: Hello
            Message-ID: <orig@example.com>
            References: <a@example.com>
            Date: Tue, 30 Sep 2026 00:00:00 +0000

            Secret body
            """,
        )
        val draft = replyDraft(
            replyAll = false,
            message = parsed,
            accountEmail = "me@example.com",
            peekedText = "peeked line",
        )
        assertEquals(listOf("List <list@example.com>"), draft.to)
        assertTrue(draft.cc.isEmpty())
        assertEquals("<orig@example.com>", draft.inReplyTo)
        assertEquals("<a@example.com> <orig@example.com>", draft.references)
        assertEquals("Re: Hello", draft.subject)
        assertTrue(draft.body.contains("> peeked line"))
        assertFalse(draft.body.contains("Secret body"))
        assertEquals("Re: Hello", markedSubject("Hello", "Re:"))
        assertEquals("Re: Hello", markedSubject("Re: Hello", "Re:"))
        assertEquals("re: Hello", markedSubject("re: Hello", "Re:"))
        assertEquals("Fwd: Hello", markedSubject("Hello", "Fwd:"))
        assertEquals("Fwd: Hello", markedSubject("Fwd: Hello", "Fwd:"))
        val built = buildPlain(
            PlainMessage(
                fromName = "Me",
                fromEmail = "me@example.com",
                to = draft.to,
                cc = draft.cc,
                bcc = emptyList(),
                subject = draft.subject,
                body = draft.body,
                messageId = "<new@example.com>",
                date = "Tue, 30 Sep 2026 01:00:00 +0000",
                inReplyTo = draft.inReplyTo,
                references = draft.references,
            ),
        )
        val header = built.rfc822.toString(Charsets.UTF_8).substringBefore("\r\n\r\n")
        assertTrue(header.contains("In-Reply-To: <orig@example.com>"))
        assertTrue(header.contains("References: <a@example.com> <orig@example.com>"))
    }

    private fun rfc(text: String): ParsedRfc822 {
        val raw = text.trimIndent().replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
        return parseRfc822(raw)
    }
}
