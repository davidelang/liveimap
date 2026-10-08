package org.dlang.liveimap.engine

import java.nio.charset.Charset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MailCharsetsTest {
    @Test
    fun latin1EAcuteBecomesUtf8() {
        val converted = mailCharconv("UTF-8", "ISO-8859-1", byteArrayOf(0xE9.toByte()))
        assertEquals(0, converted.code)
        assertArrayEquals(byteArrayOf(0xC3.toByte(), 0xA9.toByte()), converted.bytes)
    }

    @Test
    fun windows1252EuroBecomesUtf8() {
        val converted = mailCharconv("UTF-8", "windows-1252", byteArrayOf(0x80.toByte()))
        assertEquals(0, converted.code)
        assertArrayEquals(byteArrayOf(0xE2.toByte(), 0x82.toByte(), 0xAC.toByte()), converted.bytes)
    }

    @Test
    fun utf8EAcuteBecomesLatin1() {
        val converted = mailCharconv(
            "ISO-8859-1",
            "UTF-8",
            byteArrayOf(0xC3.toByte(), 0xA9.toByte()),
        )
        assertEquals(0, converted.code)
        assertArrayEquals(byteArrayOf(0xE9.toByte()), converted.bytes)
    }

    @Test
    fun utf8EuroBecomesLatin1Replacement() {
        val converted = mailCharconv(
            "ISO-8859-1",
            "UTF-8",
            byteArrayOf(0xE2.toByte(), 0x82.toByte(), 0xAC.toByte()),
        )
        assertEquals(0, converted.code)
        assertArrayEquals(byteArrayOf(0x3F.toByte()), converted.bytes)
    }

    @Test
    fun emptyInputWithKnownNames() {
        val converted = mailCharconv("UTF-8", "ISO-8859-1", ByteArray(0))
        assertEquals(0, converted.code)
        assertEquals(0, converted.bytes.size)
    }

    @Test
    fun knownNames() {
        val names = listOf(
            "UTF-8",
            "ISO-8859-1",
            "ISO-8859-2",
            "ISO-8859-15",
            "windows-1250",
            "windows-1251",
            "windows-1252",
            "KOI8-R",
            "Shift_JIS",
            "ISO-2022-JP",
            "EUC-JP",
            "GB2312",
            "GBK",
            "GB18030",
            "Big5",
            "EUC-KR",
        )
        for (name in names) {
            Charset.forName(name)
            val fromUtf8 = mailCharconv(name, "UTF-8", byteArrayOf(0x41))
            assertEquals(0, fromUtf8.code)
            assertArrayEquals(name, byteArrayOf(0x41), fromUtf8.bytes)
            val toUtf8 = mailCharconv("UTF-8", name, byteArrayOf(0x41))
            assertEquals(0, toUtf8.code)
            assertArrayEquals(name, byteArrayOf(0x41), toUtf8.bytes)
        }
    }

    @Test
    fun unknownName() {
        val converted = mailCharconv("UTF-8", "x-not-a-charset", byteArrayOf(0x41))
        assertEquals(1, converted.code)
        assertEquals(0, converted.bytes.size)
        val other = mailCharconv("x-not-a-charset", "ISO-8859-1", byteArrayOf(0xE9.toByte()))
        assertEquals(1, other.code)
        assertEquals(0, other.bytes.size)
    }

    @Test
    fun blankName() {
        val blankTo = mailCharconv("", "UTF-8", byteArrayOf(0xE9.toByte()))
        assertEquals(1, blankTo.code)
        assertEquals(0, blankTo.bytes.size)
        val blankFrom = mailCharconv("UTF-8", " ", byteArrayOf(0xE9.toByte()))
        assertEquals(1, blankFrom.code)
        assertEquals(0, blankFrom.bytes.size)
    }
}
