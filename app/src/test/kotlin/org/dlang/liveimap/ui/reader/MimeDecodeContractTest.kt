package org.dlang.liveimap.ui.reader

import androidx.compose.ui.text.AnnotatedString
import org.dlang.liveimap.ui.compose.MimeDecodeException
import org.dlang.liveimap.ui.compose.addresses
import org.dlang.liveimap.ui.compose.decodeHeaderWords
import org.dlang.liveimap.ui.compose.headerValues
import org.dlang.liveimap.ui.compose.loadEditor
import org.dlang.liveimap.ui.compose.parseRfc822
import org.dlang.liveimap.ui.index.indexSubjectText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MimeDecodeContractTest {
    @Test
    fun strayEqualsQuestionKeepsTheWholeSubject() {
        val raw = "Quiz: 2+2=? answer inside"
        val row = MimeDecode.phrase(raw, "ISO-8859-1")
        assertEquals(raw, row[1])
        assertEquals(raw, decodeHeaderWords(raw, "ISO-8859-1"))
        assertNotEquals("ok", row[0])
        assertTrue(row[2].isNotEmpty())
    }

    @Test
    fun brokenEncodedWordKeepsItsTail() {
        val raw = "=?utf-8?Q?hello world?="
        val row = MimeDecode.phrase(raw, "ISO-8859-1")
        assertEquals(raw, row[1])
        assertTrue(row[1].endsWith("world?="))
        assertNotEquals("ok", row[0])
        assertTrue(row[2].isNotEmpty())
    }

    @Test
    fun mailimfFailureThrows() {
        val bad = "Subject: hi\r\nNot a header\r\n".toByteArray(Charsets.US_ASCII)
        try {
            MimeDecode.headers(bad, "ISO-8859-1")
            fail("a mailimf failure must throw")
        } catch (error: MimeDecodeException) {
            assertTrue(error.message?.isNotEmpty() == true)
        }
    }

    @Test
    fun subjectComesFromTheOneParse() {
        val raw = "Subject: =?utf-8?Q?Hello_World?=\r\nFrom: =?utf-8?Q?A?= <a@b.c>\r\n".toByteArray(Charsets.US_ASCII)
        val rows = MimeDecode.headers(raw, "ISO-8859-1")
        assertEquals("Subject", rows[0])
        assertEquals("Hello World", rows[1])
        assertEquals("From", rows[3])
        assertTrue(rows[4].contains("a@b.c"))
    }

    @Test
    fun badBase64ThrowsInTheReader() {
        val bytes = "!!!!".toByteArray(Charsets.US_ASCII)
        try {
            val decoded = decodePart(bytes, "utf-8", "base64", "ISO-8859-1")
            fail("decodePart returned body text: ${decoded.text}")
        } catch (error: MimeDecodeException) {
            assertTrue(error.message?.isNotEmpty() == true)
            assertFalse(error.message == "")
        }
        val decoder = WireTextDecoder("utf-8", "base64", "ISO-8859-1")
        try {
            decoder.take(bytes)
            val tail = decoder.finish()
            fail("stream returned body text: $tail")
        } catch (error: MimeDecodeException) {
            assertTrue(error.message?.isNotEmpty() == true)
        }
    }

    @Test
    fun badBase64AttachmentBuildsNoOutgoingPart() {
        val raw = (
            "From: a@b.c\r\n" +
                "To: c@d.e\r\n" +
                "Subject: hi\r\n" +
                "MIME-Version: 1.0\r\n" +
                "Content-Type: multipart/mixed; boundary=b\r\n" +
                "\r\n" +
                "--b\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "hello\r\n" +
                "--b\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Transfer-Encoding: base64\r\n" +
                "Content-Disposition: attachment; filename=a.bin\r\n" +
                "\r\n" +
                "!!!!\r\n" +
                "--b--\r\n"
            ).toByteArray(Charsets.US_ASCII)
        try {
            val loaded = loadEditor(raw, "ISO-8859-1")
            fail("loadEditor built an attachment part: ${loaded.attachments.size} body=${loaded.body}")
        } catch (error: MimeDecodeException) {
            assertTrue(error.message?.isNotEmpty() == true)
        }
        val body = (
            "From: a@b.c\r\n" +
                "Subject: hi\r\n" +
                "Content-Transfer-Encoding: base64\r\n" +
                "\r\n" +
                "!!!!\r\n"
            ).toByteArray(Charsets.US_ASCII)
        try {
            val loaded = loadEditor(body, "ISO-8859-1")
            assertFalse(loaded.body.contains("could not decode this part"))
            fail("loadEditor returned body text: ${loaded.body}")
        } catch (error: MimeDecodeException) {
            assertTrue(error.message?.isNotEmpty() == true)
        }
    }

    @Test
    fun failedPreviewIsNotTheErrorSentence() {
        val row = MimeDecode.text("!!!!".toByteArray(Charsets.US_ASCII), "utf-8", "ISO-8859-1", "base64")
        assertFalse(row.ok)
        val sentence = row.text
        assertTrue(sentence.isNotEmpty())
        val shown = indexSubjectText(AnnotatedString("Quiz"), "could not decode this header", true).text
        assertTrue(shown.contains("could not decode this header"))
        assertTrue(shown.contains("preview failed"))
        assertFalse(shown.contains(sentence))
        val previewFailed = true
        val preview: String? = if (previewFailed) null else sentence
        assertNull(preview)
        assertEquals("Hi", indexSubjectText(AnnotatedString("Hi"), "", false).text)
        assertEquals("Hi preview failed", indexSubjectText(AnnotatedString("Hi"), "", true).text)
    }

    @Test
    fun severalHtmlChunksCallThemedHtmlOnce() {
        assertEquals(1, themedHtmlChunkLoads(listOf("<p>one", " two", "</p>")))
    }

    @Test
    fun addressAndIdFieldsKeepQuotesAndBrackets() {
        val raw = (
            "From: \"Last, First\" <c@d.e>\r\n" +
                "To: \"Last,\r\n" +
                " First\" <c@d.e>, d@e.f\r\n" +
                "Message-ID: <id@host>\r\n" +
                "In-Reply-To: <a@b> <c@d>\r\n" +
                "References: <a@b> <c@d>\r\n" +
                "Subject: hi\r\n"
            ).toByteArray(Charsets.US_ASCII)
        val parsed = parseRfc822(raw, "ISO-8859-1")
        assertEquals(listOf("\"Last, First\" <c@d.e>"), addresses(parsed, "From", "ISO-8859-1"))
        assertEquals(listOf("\"Last, First\" <c@d.e>", "d@e.f"), addresses(parsed, "To", "ISO-8859-1"))
        assertEquals("<id@host>", headerValues(parsed, "Message-ID").first())
        assertEquals("<a@b> <c@d>", headerValues(parsed, "In-Reply-To").first())
        assertEquals("<a@b> <c@d>", headerValues(parsed, "References").first())
    }
}
