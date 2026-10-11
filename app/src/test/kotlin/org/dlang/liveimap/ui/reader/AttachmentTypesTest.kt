package org.dlang.liveimap.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentTypesTest {
    @Test fun passiveTypesPassThroughLowercased() {
        assertEquals("application/pdf", safeAttachmentMime("APPLICATION", "PDF"))
        assertEquals("image/jpeg", safeAttachmentMime("image", "jpeg"))
        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            safeAttachmentMime("application", "vnd.openxmlformats-officedocument.wordprocessingml.document"),
        )
    }

    @Test fun activeOrInstallableTypesBecomeOctetStream() {
        for ((t, s) in listOf(
            "application" to "vnd.android.package-archive",
            "text" to "html",
            "application" to "xhtml+xml",
            "image" to "svg+xml",
            "application" to "javascript",
            "text" to "javascript",
            "application" to "x-sh",
            "multipart" to "mixed",
            "application" to "x-liveimap-too-deep",
        )) {
            assertEquals("$t/$s", FallbackAttachmentMime, safeAttachmentMime(t, s))
        }
    }

    @Test fun malformedTypesBecomeOctetStream() {
        assertEquals(FallbackAttachmentMime, safeAttachmentMime("", "pdf"))
        assertEquals(FallbackAttachmentMime, safeAttachmentMime("application", "pdf;x=1"))
        assertEquals(FallbackAttachmentMime, safeAttachmentMime("image/png", "x"))
        assertEquals(FallbackAttachmentMime, safeAttachmentMime("application", "pdf\u0000"))
    }
}
