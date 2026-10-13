package org.dlang.liveimap.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F54: APK markers past the first 4 KB, risky extensions, and "MZ" text. */
class SnifferGapsTest {
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private val plainZipHead = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(26) + ascii("assets/a.png") + ByteArray(64)

    @Test fun apkFoundInTheCentralDirectory() {
        val tail = ascii("PK\u0001\u0002....AndroidManifest.xml....PK\u0005\u0006")
        assertEquals("application/vnd.android.package-archive", sniffContent(plainZipHead, tail)?.mime)
        val m = typeMismatch("application/zip", "photos.zip", plainZipHead, tail)
        assertNotNull(m); assertTrue(m!!.detected.dangerous)
        assertNull(typeMismatch("application/zip", "photos.zip", plainZipHead))
    }

    @Test fun riskyExtensionsWarnEvenWhenNothingDisagrees() {
        for (name in listOf("run.js", "x.vbs", "a.bat", "a.CMD", "p.ps1", "h.hta", "s.lnk", "i.msi", "d.iso", "d.img", "x.exe.")) {
            val m = typeMismatch("application/octet-stream", name, ascii("plain words here\n"))
            assertNotNull(name, m); assertTrue(name, m!!.detected.dangerous)
        }
        assertNull(typeMismatch("text/plain", "notes.txt", ascii("plain words here\n")))
    }

    @Test fun textStartingWithMzIsNotAnExe() {
        val text = ascii("MZ is how this memo starts; it is just text and long enough to pass sixty-four bytes.")
        assertNull(sniffContent(text))
    }
}
