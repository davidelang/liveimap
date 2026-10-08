package org.dlang.liveimap.ui.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    @Test
    fun shortAsciiBodyIs7bit() {
        val built = plain("Hello")
        assertEquals("7bit", cte(built.rfc822))
        assertTrue(built.rfc822.toString(Charsets.UTF_8).contains("Hello"))
        assertSame(built.rfc822, withoutEightBit(built.rfc822))
    }

    @Test
    fun shortNonAsciiBodyIs8bitUntilRewritten() {
        val built = plain("caf\u00e9")
        assertEquals("8bit", cte(built.rfc822))
        val rewritten = withoutEightBit(built.rfc822)
        val text = rewritten.toString(Charsets.ISO_8859_1)
        assertEquals("quoted-printable", cte(rewritten))
        assertFalse(has8bitCte(rewritten))
        assertTrue(text.contains("=C3=A9"))
        assertFalse(text.contains("=c3"))
        assertLinesAtMost76(rewritten)
        assertEquals(loadEditor(built.rfc822).body, loadEditor(rewritten).body)
    }

    @Test
    fun longAsciiLineIsQuotedPrintable() {
        val built = plain("a".repeat(1200))
        assertEquals("quoted-printable", cte(built.rfc822))
        assertFalse(has8bitCte(built.rfc822))
        assertLinesAtMost76(built.rfc822)
        assertEquals("a".repeat(1200), loadEditor(built.rfc822).body.trimEnd('\n'))
    }

    @Test
    fun lineOf998Stays7bitAnd999IsQuotedPrintable() {
        assertEquals("7bit", cte(plain("a".repeat(998)).rfc822))
        assertEquals("8bit", cte(plain("\u00e9".repeat(499)).rfc822))
        val over = plain("a".repeat(999))
        assertEquals("quoted-printable", cte(over.rfc822))
        assertLinesAtMost76(over.rfc822)
        val wide = plain("\u00e9".repeat(500))
        assertEquals("quoted-printable", cte(wide.rfc822))
        assertLinesAtMost76(wide.rfc822)
    }

    @Test
    fun quotedPrintableRoundTripsTrailingSpaceAndSoftBreaks() {
        val source = "hello  \n" + "x".repeat(90) + "\na=b\n\u00e9\n"
        val (encoding, payload) = textPart(source, allowEightBit = false)
        assertEquals("quoted-printable", encoding)
        assertLinesAtMost76(payload)
        val text = payload.toString(Charsets.US_ASCII)
        assertTrue(text.contains("hello=20=20\r\n"))
        assertTrue(text.contains("a=3Db\r\n"))
        assertTrue(text.contains("=C3=A9\r\n"))
        val wrapped = (
            "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Transfer-Encoding: quoted-printable\r\n\r\n"
            ).toByteArray(Charsets.US_ASCII) + payload
        assertEquals(source, loadEditor(wrapped).body)
    }

    @Test
    fun multipart8bitTextBecomesQuotedPrintableAndBase64Stays() {
        val file = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
        val built = buildPlain(
            PlainMessage(
                fromName = "Me",
                fromEmail = "me@example.com",
                to = listOf("ann@example.com"),
                cc = emptyList(),
                bcc = emptyList(),
                subject = "Hi",
                body = "caf\u00e9",
                messageId = "<part@example.com>",
                date = "Tue, 30 Sep 2026 00:00:00 +0000",
                attachments = listOf(
                    OutgoingPart("a.bin", "application/octet-stream", file, wireBase64 = false),
                ),
            ),
        )
        val before = built.rfc822
        assertTrue(has8bitCte(before))
        val after = withoutEightBit(before)
        assertFalse(has8bitCte(after))
        assertTrue(after.toString(Charsets.ISO_8859_1).contains("--liveimap_partexamplecom--"))
        assertEquals(base64Slice(before), base64Slice(after))
        assertEquals(loadEditor(before).body, loadEditor(after).body)
        assertEquals(file.toList(), loadEditor(after).attachments.single().bytes.toList())
    }

    @Test
    fun wrapsAtColumnOnSpaces() {
        assertEquals("one two\nthree four", wrapPlain("one two three four", 10))
        assertEquals("one two\nthree four", wrapPlain("one\ttwo three four", 10))
        assertEquals("one two three four", wrapPlain("one two three four", 0))
        assertEquals("one two  \nthree  \nfour", flowPlain("one two three four", 10))
        assertEquals("one two  \nthree  \nfour", flowPlain("one\ttwo three four", 10))
        assertEquals("one two three four", flowPlain("one two three four", 0))
        assertEquals("abcdefghijk", flowPlain("abcdefghijk", 5))
        assertEquals(" > one  \n > two  \n > three", flowPlain("> one two three", 10))
        assertEquals("-- ", flowPlain("-- ", 10))
        assertEquals(
            " From the start of  \nthe line here",
            flowPlain("From the start of the line here", 20),
        )
        val built = buildPlain(
            PlainMessage(
                fromName = "Me",
                fromEmail = "me@example.com",
                to = listOf("ann@example.com"),
                cc = emptyList(),
                bcc = emptyList(),
                subject = "Hi",
                body = "one two three four",
                messageId = "<new@example.com>",
                date = "Tue, 30 Sep 2026 00:00:00 +0000",
                wrapColumn = 10,
            ),
        )
        val text = built.rfc822.toString(Charsets.UTF_8)
        assertEquals("7bit", cte(built.rfc822))
        assertTrue(text.contains("text/plain; charset=utf-8; format=flowed; delsp=yes"))
        assertTrue(text.contains("one two  \r\nthree  \r\nfour"))
        val fixed = buildPlain(
            PlainMessage(
                fromName = "Me",
                fromEmail = "me@example.com",
                to = listOf("ann@example.com"),
                cc = emptyList(),
                bcc = emptyList(),
                subject = "Hi",
                body = "one two three four",
                messageId = "<new@example.com>",
                date = "Tue, 30 Sep 2026 00:00:00 +0000",
                wrapColumn = 10,
                flowed = false,
            ),
        )
        val fixedText = fixed.rfc822.toString(Charsets.UTF_8)
        assertTrue(fixedText.contains("one two\r\nthree four"))
        assertFalse(fixedText.contains("flowed"))
    }

    @Test
    fun quotedWrapKeepsPrefix() {
        assertEquals("> one two\n> three", wrapPlain("> one two three", 10))
    }

    @Test
    fun longWordIsNotSplit() {
        assertEquals("abcdefghijk", wrapPlain("abcdefghijk", 5))
    }

    private fun plain(body: String): BuiltMail = buildPlain(
        PlainMessage(
            fromName = "Me",
            fromEmail = "me@example.com",
            to = listOf("ann@example.com"),
            cc = emptyList(),
            bcc = listOf("secret@example.com"),
            subject = "Hi",
            body = body,
            messageId = "<new@example.com>",
            date = "Tue, 30 Sep 2026 00:00:00 +0000",
        ),
    )

    private fun cte(bytes: ByteArray): String {
        val header = bytes.toString(Charsets.ISO_8859_1).substringBefore("\r\n\r\n")
        val line = header.lineSequence().first { it.startsWith("Content-Transfer-Encoding:", ignoreCase = true) }
        return line.substringAfter(':').trim()
    }

    private fun has8bitCte(bytes: ByteArray): Boolean {
        val text = bytes.toString(Charsets.ISO_8859_1)
        return text.split('\n').any { line ->
            val bare = line.trimEnd('\r')
            bare.length >= "content-transfer-encoding:".length &&
                bare.regionMatches(0, "content-transfer-encoding:", 0, "content-transfer-encoding:".length, ignoreCase = true) &&
                bare.substringAfter(':').trim().equals("8bit", ignoreCase = true)
        }
    }

    private fun assertLinesAtMost76(bytes: ByteArray) {
        var start = 0
        for (i in bytes.indices) {
            if (bytes[i] == '\n'.code.toByte()) {
                assertTrue(i - start + 1 <= 76)
                start = i + 1
            }
        }
        if (start < bytes.size) assertTrue(bytes.size - start <= 76)
    }

    private fun base64Slice(message: ByteArray): String {
        val text = message.toString(Charsets.ISO_8859_1)
        val marker = "Content-Transfer-Encoding: base64\r\n"
        val at = text.indexOf(marker)
        assertTrue(at >= 0)
        return text.substring(at, text.indexOf("\r\n--liveimap_", at))
    }

    private fun rfc(text: String): ParsedRfc822 {
        val raw = text.trimIndent().replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
        return parseRfc822(raw)
    }
}
