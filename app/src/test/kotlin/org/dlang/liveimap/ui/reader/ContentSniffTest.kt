package org.dlang.liveimap.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSniffTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun ascii(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun zipWith(name: String) = bytes(0x50, 0x4B, 0x03, 0x04) + ByteArray(26) + ascii(name) + ByteArray(64)
    private val pdf = ascii("%PDF-1.7\n%\u00e2\u00e3\n1 0 obj")
    private val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13)
    private val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 16)
    private val apk = zipWith("AndroidManifest.xml")
    private val elf = bytes(0x7F, 0x45, 0x4C, 0x46, 2, 1, 1)
    private val exe = ascii("MZ") + ByteArray(62)
    private val html = bytes(0xEF, 0xBB, 0xBF) + ascii("  <!DOCTYPE html><html><body onload=x()>")
    private val svg = ascii("<?xml version=\"1.0\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\"><script>")

    @Test fun magicTableRecognisesTheCommonFormats() {
        assertEquals("application/pdf", sniffContent(pdf)?.mime)
        assertEquals("image/png", sniffContent(png)?.mime)
        assertEquals("image/jpeg", sniffContent(jpeg)?.mime)
        assertEquals("application/vnd.android.package-archive", sniffContent(apk)?.mime)
        assertEquals("application/java-archive", sniffContent(zipWith("META-INF/MANIFEST.MF"))?.mime)
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", sniffContent(zipWith("word/document.xml"))?.mime)
        assertEquals("application/zip", sniffContent(zipWith("notes.txt"))?.mime)
        assertEquals("application/x-executable", sniffContent(elf)?.mime)
        assertEquals("application/vnd.microsoft.portable-executable", sniffContent(exe)?.mime)
        assertEquals("text/html", sniffContent(ascii("<html>"))?.mime)
        assertEquals("image/svg+xml", sniffContent(svg)?.mime)
        assertEquals("application/x-ole-storage", sniffContent(bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1))?.mime)
        assertEquals("application/x-7z-compressed", sniffContent(bytes(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C))?.mime)
        assertEquals("text/x-script", sniffContent(ascii("#!/bin/sh\nrm -rf"))?.mime)
        assertNull(sniffContent(ascii("Just some plain text.\n")))
        assertNull(sniffContent(ByteArray(0)))
    }

    @Test fun honestAttachmentsDoNotWarn() {
        assertNull(typeMismatch("application/pdf", "report.pdf", pdf))
        assertNull(typeMismatch("image/jpg", "photo.JPG", jpeg))
        assertNull(typeMismatch("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "a.docx", zipWith("[Content_Types].xml")))
        assertNull(typeMismatch("application/octet-stream", "report.pdf", pdf))
        assertNull(typeMismatch("application/msword", "old.doc", bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)))
        assertNull(typeMismatch("text/plain", "notes.txt", ascii("hello\n")))
    }

    @Test fun spoofedAttachmentsWarnWithBothTypes() {
        val apkAsPdf = typeMismatch("application/pdf", "invoice.pdf", apk)
        assertNotNull(apkAsPdf)
        assertEquals("application/pdf", apkAsPdf!!.claimed)
        assertEquals("application/pdf", apkAsPdf.extension)
        assertEquals("application/vnd.android.package-archive", apkAsPdf.detected.mime)
        assertTrue(apkAsPdf.detected.dangerous)

        assertNotNull(typeMismatch("image/jpeg", "cat.jpg", html))            // HTML posing as a photo
        assertNotNull(typeMismatch("image/png", "logo.png", svg))             // SVG with script as PNG
        assertNotNull(typeMismatch("application/zip", "photos.zip", apk))     // APK as a plain ZIP
        assertNotNull(typeMismatch("application/pdf", "statement.pdf", exe))  // Windows program
        assertNotNull(typeMismatch("application/pdf", "scan.pdf", png))       // harmless, but still not a PDF
        assertNotNull(typeMismatch("application/octet-stream", "readme.txt", elf)) // extension says text
        assertNotNull(typeMismatch("application/octet-stream", null, apk))    // uninformative type hides an APK
    }

    @Test fun extensionWinsOnlyWhenKnown() {
        assertEquals("application/pdf", extensionMime("A.PDF"))
        assertNull(extensionMime("noext"))
        assertNull(extensionMime("trailing."))
        assertNull(extensionMime(null))
    }
}
