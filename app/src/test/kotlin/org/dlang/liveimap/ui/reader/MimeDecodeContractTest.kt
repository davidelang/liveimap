package org.dlang.liveimap.ui.reader

import org.dlang.liveimap.ui.compose.MimeDecodeException
import org.dlang.liveimap.ui.compose.decodeHeaderWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MimeDecodeContractTest {
    @Test
    fun strayEqualsQuestionKeepsTheWholeSubject() {
        val raw = "Quiz: 2+2=? answer inside"
        val row = MimeDecode.phrase(raw)
        assertEquals(raw, row[1])
        assertEquals(raw, decodeHeaderWords(raw))
        assertNotEquals("ok", row[0])
        assertTrue(row[2].isNotEmpty())
    }

    @Test
    fun brokenEncodedWordKeepsItsTail() {
        val raw = "=?utf-8?Q?hello world?="
        val row = MimeDecode.phrase(raw)
        assertEquals(raw, row[1])
        assertTrue(row[1].endsWith("world?="))
        assertNotEquals("ok", row[0])
        assertTrue(row[2].isNotEmpty())
    }

    @Test
    fun mailimfFailureThrows() {
        val bad = "Subject: hi\r\nNot a header\r\n".toByteArray(Charsets.US_ASCII)
        try {
            MimeDecode.headers(bad)
            fail("a mailimf failure must throw")
        } catch (error: MimeDecodeException) {
            assertTrue(error.message?.isNotEmpty() == true)
        }
    }

    @Test
    fun subjectComesFromTheOneParse() {
        val raw = "Subject: =?utf-8?Q?Hello_World?=\r\nFrom: =?utf-8?Q?A?= <a@b.c>\r\n".toByteArray(Charsets.US_ASCII)
        val rows = MimeDecode.headers(raw)
        assertEquals("Subject", rows[0])
        assertEquals("Hello World", rows[1])
        assertEquals("From", rows[3])
        assertTrue(rows[4].contains("a@b.c"))
    }
}
