package org.dlang.liveimap.ui.compose

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F80 (b)(c)(d). */
class HeaderSafetyTest {
    @Test fun controlsAndBidiNeverReachAHeader() {
        assertEquals("ab c", cleanHeader("a\r\nb\tc"))
        assertEquals("abc", cleanHeader("a\u0000b\u007Fc"))
        assertEquals("evilfdp.exe", cleanHeader("evil\u202Efdp.exe"))
        assertEquals("x", cleanHeader("\u2066x\u2069\u200F\uFEFF"))
        assertEquals("Grüße", cleanHeader("Grüße"))
    }

    @Test fun encodedWordsAreShortAndFolded() {
        val long = "Ünïcödé ".repeat(30)
        val folded = encodedWord(long)
        val words = folded.split("\r\n ")
        assertTrue(words.size > 1)
        for (w in words) {
            assertTrue(w.length <= 75)
            assertTrue(w.startsWith("=?UTF-8?B?") && w.endsWith("?="))
        }
        val back = words.joinToString("") { String(Base64.getDecoder().decode(it.removePrefix("=?UTF-8?B?").removeSuffix("?=")), Charsets.UTF_8) }
        assertEquals(long, back)
        assertFalse(encodedWord(long, fold = false).contains("\r\n"))
        // a 4-byte character is never split
        val emoji = "😀".repeat(40)
        val parts = encodedWord(emoji).split("\r\n ")
        assertEquals(emoji, parts.joinToString("") { String(Base64.getDecoder().decode(it.removePrefix("=?UTF-8?B?").removeSuffix("?=")), Charsets.UTF_8) })
    }

    @Test fun nonAsciiFilenameUsesRfc2231() {
        assertEquals("name=\"a b.txt\"" to "filename=\"a b.txt\"", filenameParams("a b.txt"))
        val (name, disposition) = filenameParams("résumé.pdf")
        assertEquals("filename*=UTF-8''r%C3%A9sum%C3%A9.pdf", disposition)
        assertTrue(name.startsWith("name=\"=?UTF-8?B?"))
    }

    @Test fun wireBase64IsRewrapped() {
        val data = ByteArray(300) { it.toByte() }
        val oneLine = Base64.getEncoder().encode(data)
        val out = String(rewrapBase64(oneLine), Charsets.US_ASCII)
        val lines = out.split("\r\n").filter { it.isNotEmpty() }
        assertTrue(lines.all { it.length <= 76 })
        assertArrayEquals(data, Base64.getMimeDecoder().decode(out))
        // undecodable input keeps only the alphabet, still wrapped
        val junk = String(rewrapBase64("AB\r\n.C$\u0000D".toByteArray(Charsets.ISO_8859_1)), Charsets.US_ASCII)
        assertTrue(junk.all { it.isLetterOrDigit() || it in "+/=\r\n" })
    }
}
