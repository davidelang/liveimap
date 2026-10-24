package org.dlang.liveimap.ui.reader

import org.dlang.liveimap.ui.compose.decodeHeaderWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
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
}
