package org.dlang.liveimap.ui.reader

import java.nio.charset.Charset
import java.util.Base64
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

    @Test
    fun cutBase64StillShowsHello() {
        val encoded = Base64.getMimeEncoder().encodeToString("<p>hello</p>".toByteArray(Charsets.UTF_8))
        assertEquals(0, encoded.length % 4)
        for (extra in 1..3) {
            val cut = encoded.take(encoded.length - 4 + extra)
            assertEquals(extra, cut.length % 4)
            val text = WireTextDecoder("utf-8", "base64").take(cut.toByteArray(Charsets.US_ASCII))
            assertTrue(text.contains("hello"))
        }
    }

    @Test
    fun unfinishedCharsetBytes() {
        assertDecoded("GB18030", raw(0x95, 0x32, 0x82, 0x36), "𠀀")
        assertDecoded("EUC-JP", raw(0x8F, 0xB0, 0xA1), "丂")
        assertDecoded("ISO-2022-JP-2", raw(0x1B, 0x24, 0x28, 0x44, 0x22, 0x42, 0x1B, 0x28, 0x42), "¡")
        assertDecoded("windows-874", raw(0xA1), "ก")
        assertDecoded("UTF-16", raw(0xFE, 0xFF, 0x00, 0x41), "A")
        assertDecoded("UTF-7", raw(0x2B, 0x41, 0x4F, 0x6B, 0x2D), "é")
        assertDecoded("Big5-HKSCS", raw(0x9E, 0xB3), "丄")
        assertDecoded("ISO-2022-KR", raw(0x1B, 0x24, 0x29, 0x43, 0x0E, 0x30, 0x21, 0x0F), "가")
        assertDecoded("IBM866", raw(0x80), "А")
        assertDecoded("macintosh", raw(0x80), "Ä")
    }

    private fun raw(vararg bytes: Int): ByteArray = ByteArray(bytes.size) { bytes[it].toByte() }

    private fun assertDecoded(charset: String, bytes: ByteArray, text: String) {
        val decoded = decodePart(bytes, charset, "")
        assertEquals(text, decoded.text)
        assertFalse(decoded.unknownCharset)
    }

    private fun decodeNamed(charset: String, text: String): String {
        val bytes = text.toByteArray(Charset.forName(charset))
        val decoded = decodePart(bytes, charset, "")
        assertFalse(decoded.unknownCharset)
        return decoded.text
    }
}
