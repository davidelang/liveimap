package org.dlang.liveimap.ui.compose

import org.dlang.liveimap.session.MailFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
            charset = "ISO-8859-1",
        )
        assertEquals(listOf("Ann <ann@example.com>", "Bo <bo@example.com>"), draft.to)
        assertEquals(listOf("Cy <cy@example.com>"), draft.cc)
        val recipients = (draft.to + draft.cc).joinToString(" ")
        assertFalse(recipients.contains("me@example.com"))
        assertFalse(recipients.contains("hidden@example.com"))
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
            charset = "ISO-8859-1",
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
    }

    @Test
    fun resumeReadsFilenameStarAndNestedPlain() {
        val bytes = (
            "Content-Type: multipart/mixed; boundary=mix\r\n\r\n" +
                "--mix\r\n" +
                "Content-Type: multipart/alternative; boundary=alt\r\n\r\n" +
                "--alt\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n\r\n" +
                "hello\r\n" +
                "--alt\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n\r\n" +
                "<p>no</p>\r\n" +
                "--alt--\r\n" +
                "--mix\r\n" +
                "Content-Type: application/pdf; name=\"=?UTF-8?B?aGkudHh0?=\"\r\n" +
                "Content-Disposition: attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf\r\n\r\n" +
                "%PDF\r\n" +
                "--mix--\r\n"
            ).toByteArray(Charsets.UTF_8)
        val loaded = loadEditor(bytes, "ISO-8859-1")
        assertEquals("<p>no</p>", loaded.body)
        assertTrue(loaded.html)
        assertEquals("résumé.pdf", loaded.attachments.first { it.mediaType == "application/pdf" }.filename)
        assertFalse(loaded.attachments.any { it.mediaType.equals("text/html", ignoreCase = true) })
        val named = (
            "Content-Type: multipart/mixed; boundary=n\r\n\r\n" +
                "--n\r\n" +
                "Content-Type: text/plain\r\n\r\n" +
                "body\r\n" +
                "--n\r\n" +
                "Content-Type: application/octet-stream; name=\"=?UTF-8?B?aGkudHh0?=\"\r\n\r\n" +
                "x\r\n" +
                "--n--\r\n"
            ).toByteArray(Charsets.US_ASCII)
        assertEquals("hi.txt", loadEditor(named, "ISO-8859-1").attachments.single().filename)
    }

    @Test
    fun unparsableHeaderDoesNotReturnAnEditor() {
        val bytes = "Subject: hi\r\nNot a header\r\n\r\nbody\r\n".toByteArray(Charsets.US_ASCII)
        try {
            val loaded = loadEditor(bytes, "ISO-8859-1")
            fail("loadEditor returned an editor: ${loaded.subject}")
        } catch (error: MailFailure) {
            assertTrue(error.text.isNotEmpty())
        }
    }

    @Test
    fun replyAllUnlabeledEightBitFromUsesAccountCharset() {
        val head = "From: ".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x80.toByte()) +
            " <ann@example.com>\r\nTo: me@example.com\r\nSubject: Hello\r\n\r\n".toByteArray(Charsets.US_ASCII)
        val parsed = parseRfc822(head, "windows-1252")
        val draft = replyDraft(
            replyAll = true,
            message = parsed,
            accountEmail = "me@example.com",
            peekedText = "",
            charset = "windows-1252",
        )
        assertEquals(listOf("€ <ann@example.com>"), draft.to)
    }

    @Test
    fun multipartWithoutBoundaryThrows() {
        val bytes = "Content-Type: multipart/mixed\r\n\r\nhello\r\n".toByteArray(Charsets.US_ASCII)
        try {
            loadEditor(bytes, "UTF-8")
            fail("a multipart with no boundary must throw")
        } catch (error: MimeDecodeException) {
            assertTrue(error.message?.isNotEmpty() == true)
        }
    }

    @Test
    fun boundaryLookalikeAtLineStartDoesNotSplit() {
        val bytes = (
            "Content-Type: text/plain; charset=utf-8\r\n" +
                "\r\n" +
                "hello\r\n" +
                "--boundaryX\r\n" +
                "not a part\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val loaded = loadEditor(bytes, "UTF-8")
        assertTrue(loaded.body.contains("--boundaryX"))
        assertTrue(loaded.body.contains("not a part"))
        assertTrue(loaded.attachments.isEmpty())
    }

    private fun rfc(text: String): ParsedRfc822 {
        val raw = text.trimIndent().replace("\n", "\r\n").toByteArray(Charsets.UTF_8)
        return parseRfc822(raw, "ISO-8859-1")
    }
}
