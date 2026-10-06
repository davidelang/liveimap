package org.dlang.liveimap.ui.reader

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartTextTest {
    @Test
    fun sixCharsetsDecode() {
        assertEquals("é", decodeNamed("ISO-8859-1", "é"))
        assertEquals("é", decodeNamed("windows-1252", "é"))
        assertEquals("é", decodeNamed("GB18030", "é"))
        assertEquals("€", decodePart(byteArrayOf(0x80.toByte()), "windows-1252", "").text)
        assertEquals("あ", decodeNamed("Shift_JIS", "あ"))
        assertEquals("あ", decodeNamed("ISO-2022-JP", "あ"))
        assertEquals("Ж", decodeNamed("KOI8-R", "Ж"))
    }

    @Test
    fun quotedPrintableIso88591() {
        val decoded = decodePart("=E9".toByteArray(Charsets.US_ASCII), "ISO-8859-1", "quoted-printable")
        assertEquals("é", decoded.text)
        assertFalse(decoded.unknownCharset)
    }

    @Test
    fun unknownCharsetIsIso88591() {
        val bytes = byteArrayOf(0xE9.toByte())
        val named = decodePart(bytes, "no-such-charset", "")
        assertEquals("é", named.text)
        assertTrue(named.unknownCharset)
        val empty = decodePart(bytes, "", "")
        assertEquals("é", empty.text)
        assertTrue(empty.unknownCharset)
        assertEquals(unknownCharsetNote, "Unknown charset; shown as ISO-8859-1.")
        assertFalse(decodePart(bytes, "ISO-8859-1", "").unknownCharset)
    }

    @Test
    fun splitChunksFinish() {
        val base64 = WireTextDecoder("ISO-8859-1", "base64")
        val base64Text = base64.take("6Q".toByteArray(Charsets.US_ASCII)) +
            base64.take("==".toByteArray(Charsets.US_ASCII)) +
            base64.finish()
        assertEquals("é", base64Text)

        val quoted = WireTextDecoder("ISO-8859-1", "quoted-printable")
        val quotedText = quoted.take("=".toByteArray(Charsets.US_ASCII)) +
            quoted.take("E9".toByteArray(Charsets.US_ASCII)) +
            quoted.finish()
        assertEquals("é", quotedText)

        val shift = "あ".toByteArray(Charset.forName("Shift_JIS"))
        val stream = WireTextDecoder("Shift_JIS", "")
        val streamed = stream.take(byteArrayOf(shift[0])) +
            stream.take(byteArrayOf(shift[1])) +
            stream.finish()
        assertEquals("あ", streamed)
    }

    private fun decodeNamed(charset: String, text: String): String {
        val bytes = text.toByteArray(Charset.forName(charset))
        val decoded = decodePart(bytes, charset, "")
        assertFalse(decoded.unknownCharset)
        return decoded.text
    }
}
