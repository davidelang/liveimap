package org.dlang.liveimap.ui.reader

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** F40: AttachmentFileProvider path handling (not only the name pattern). */
class AttachmentProviderPathTest {
    private val ok = "liveimap-0123456789abcdef-7-report.pdf"

    @Test fun ownNameResolvesInsideTheCache() {
        val dir = Files.createTempDirectory("cache").toFile()
        assertEquals(File(dir, ok), attachmentCacheFile(dir, ok))
    }

    @Test fun traversalAndForeignNamesAreRefused() {
        val dir = Files.createTempDirectory("cache").toFile()
        for (name in listOf(
            null, "", "imap-traffic.log", "../$ok", "sub/$ok",
            "liveimap-0123456789abcdef-7-a/../../x", "liveimap-0123456789ABCDEF-7-x", "liveimap-0123456789abcdef-x-y",
            "$ok\u0000", "liveimap-0123456789abcdef-7-a b",
        )) {
            assertNull(name, attachmentCacheFile(dir, name))
        }
    }

    @Test fun linkToAFileOutsideTheCacheIsRefused() {
        val dir = Files.createTempDirectory("cache").toFile()
        val secret = File(Files.createTempDirectory("outside").toFile(), "secret").apply { writeText("x") }
        Files.createSymbolicLink(File(dir, ok).toPath(), secret.toPath())
        assertNull(attachmentCacheFile(dir, ok))
    }
}
