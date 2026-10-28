package org.dlang.liveimap.ui.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentCacheNameTest {
    private val account = "01234567-89ab-cdef-0123-456789abcdef"

    @Test fun tokensDifferPerOpen() {
        assertNotEquals(
            attachmentCacheName(account, readerCacheToken(), 7, 9, "2"),
            attachmentCacheName(account, readerCacheToken(), 7, 9, "2"),
        )
    }

    @Test fun providerAcceptsTheName() {
        val name = attachmentCacheName(account, readerCacheToken(), 7, 9, "INBOX/My Mail/ü\u0000")
        assertFalse(name.contains("\u0000"))
        assertFalse(name.contains("/"))
        assertTrue(name, AttachmentFileProvider.ATTACHMENT_NAME.matches(name))
        assertTrue(name.startsWith(attachmentPrefixFor(account)))
    }

    @Test fun accountIdMustBeAUuid() {
        for (id in listOf("user@host", "0123456789abcdef", "")) {
            for (call in listOf<() -> Unit>(
                { attachmentPrefixFor(id) },
                { attachmentCacheName(id, readerCacheToken(), 7, 9, "2") },
            )) {
                var threw = false
                try {
                    call()
                } catch (error: IllegalArgumentException) {
                    threw = true
                }
                assertTrue(id, threw)
            }
        }
    }
}

class StaleAttachmentTest {
    @Test fun leftoversGoOthersStay() {
        val account = "01234567-89ab-cdef-0123-456789abcdef"
        val dir = java.nio.file.Files.createTempDirectory("cache").toFile()
        val old = java.io.File(dir, attachmentCacheName(account, readerCacheToken(), 3, 2, "2")).apply { writeText("x") }
        val part = java.io.File(dir, old.name + ".part").apply { writeText("x") }
        val other = java.io.File(dir, "notes.log").apply { writeText("x") }
        deleteStaleAttachments(dir)
        org.junit.Assert.assertFalse(old.exists() || part.exists())
        assertTrue(other.exists())
    }
}
