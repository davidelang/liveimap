package org.dlang.liveimap.ui.reader

import java.nio.charset.Charset
import java.util.Base64
import org.dlang.liveimap.ui.compose.MimeDecodeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PartTextTest {
    @Test
    fun sixCharsetsDecode() {
        assertEquals("é", decodeNamed("ISO-8859-1", "é"))
        assertEquals("é", decodeNamed("windows-1252", "é"))
        assertEquals("é", decodeNamed("GB18030", "é"))
        assertEquals("€", decodePart(byteArrayOf(0x80.toByte()), "windows-1252", "", "ISO-8859-1").text)
        assertEquals("あ", decodeNamed("Shift_JIS", "あ"))
        assertEquals("あ", decodeNamed("ISO-2022-JP", "あ"))
        assertEquals("Ж", decodeNamed("KOI8-R", "Ж"))
    }

    @Test
    fun binaryPeekSkipsASecondQuotedPrintablePass() {
        val wire = "lang=3D\"en\"".toByteArray(Charsets.US_ASCII)
        assertEquals("lang=\"en\"", decodePart(wire, "utf-8", "quoted-printable", "ISO-8859-1").text)
        assertEquals("", fetchedTransferEncoding("BinaryPeek", "1", "quoted-printable"))
        assertEquals("", fetchedTransferEncoding("BinaryPeek", "1.2", "base64"))
        assertFalse(fetchedTransferEncoding("BinaryPeek", "1", "base64").equals("base64", ignoreCase = true))
        val already = "lang=\"en\"".toByteArray(Charsets.US_ASCII)
        val identity = fetchedTransferEncoding("BinaryPeek", "1", "quoted-printable")
        assertEquals("lang=\"en\"", decodePart(already, "utf-8", identity, "ISO-8859-1").text)
        assertThrows(MimeDecodeException::class.java) {
            decodePart(already, "utf-8", "quoted-printable", "ISO-8859-1")
        }
        assertEquals("quoted-printable", fetchedTransferEncoding("BodyPeek", "1", "quoted-printable"))
        assertEquals("quoted-printable", fetchedTransferEncoding("BinaryPeek", "HEADER", "quoted-printable"))
        assertEquals("base64", fetchedTransferEncoding("BinaryPeek", "*", "base64"))
        assertEquals("", fetchedTransferEncoding("BodyPeek", "1", ""))
        assertFalse(serverStrippedTransferEncoding("BodyPeek", "1"))
        assertTrue(serverStrippedTransferEncoding("BinaryPeek", "2.1"))
    }

    @Test
    fun quotedPrintableIso88591() {
        val decoded = decodePart("=E9".toByteArray(Charsets.US_ASCII), "ISO-8859-1", "quoted-printable", "ISO-8859-1")
        assertEquals("é", decoded.text)
        assertFalse(decoded.unknownCharset)
    }

    @Test
    fun unknownCharsetIsIso88591() {
        val bytes = byteArrayOf(0xE9.toByte())
        val named = decodePart(bytes, "no-such-charset", "", "ISO-8859-1")
        assertEquals("é", named.text)
        assertTrue(named.unknownCharset)
        val empty = decodePart(bytes, "", "", "ISO-8859-1")
        assertEquals("é", empty.text)
        assertTrue(empty.unknownCharset)
        assertEquals("ISO-8859-1", empty.charsetUsed)
        val win = decodePart(byteArrayOf(0x80.toByte()), "no-such-charset", "", "windows-1252")
        assertTrue(win.unknownCharset)
        assertEquals("windows-1252", win.charsetUsed)
        assertFalse(decodePart(bytes, "ISO-8859-1", "", "ISO-8859-1").unknownCharset)
    }

    @Test
    fun splitChunksFinish() {
        val base64 = WireTextDecoder("ISO-8859-1", "base64", "ISO-8859-1")
        val base64Text = base64.take("6Q".toByteArray(Charsets.US_ASCII)) +
            base64.take("==".toByteArray(Charsets.US_ASCII)) +
            base64.finish()
        assertEquals("é", base64Text)

        val quoted = WireTextDecoder("ISO-8859-1", "quoted-printable", "ISO-8859-1")
        val quotedText = quoted.take("=".toByteArray(Charsets.US_ASCII)) +
            quoted.take("E9".toByteArray(Charsets.US_ASCII)) +
            quoted.finish()
        assertEquals("é", quotedText)

        val shift = "あ".toByteArray(Charset.forName("Shift_JIS"))
        val stream = WireTextDecoder("Shift_JIS", "", "ISO-8859-1")
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
            val text = WireTextDecoder("utf-8", "base64", "ISO-8859-1").take(cut.toByteArray(Charsets.US_ASCII))
            assertTrue(text.contains("hello"))
        }
    }

    @Test
    fun unfinishedCharsetBytes() {
        assertDecoded("GB18030", raw(0x95, 0x32, 0x82, 0x36), "𠀀")
        assertDecoded("GB18030", raw(0x81, 0x30, 0x81, 0x30), "\u0080")
        assertDecoded("EUC-JP", raw(0x8F, 0xB0, 0xA1), "丂")
        val invalid = decodePart(raw(0xFF), "GB18030", "", "ISO-8859-1")
        assertTrue(invalid.unknown)
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
        val decoded = decodePart(bytes, charset, "", "ISO-8859-1")
        assertEquals(text, decoded.text)
        assertFalse(decoded.unknownCharset)
    }

    private fun decodeNamed(charset: String, text: String): String {
        val bytes = text.toByteArray(Charset.forName(charset))
        val decoded = decodePart(bytes, charset, "", "ISO-8859-1")
        assertFalse(decoded.unknownCharset)
        return decoded.text
    }
}
