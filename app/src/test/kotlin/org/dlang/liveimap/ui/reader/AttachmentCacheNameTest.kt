package org.dlang.liveimap.ui.reader

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentCacheNameTest {
    @Test fun tokensDifferPerOpen() {
        assertNotEquals(attachmentCacheName(readerCacheToken(), 7, "2"), attachmentCacheName(readerCacheToken(), 7, "2"))
    }

    @Test fun providerAcceptsTheName() {
        val name = attachmentCacheName(readerCacheToken(), 7, "1.2/x")
        assertTrue(name, AttachmentFileProvider.ATTACHMENT_NAME.matches(name))
    }
}

class StaleAttachmentTest {
    @Test fun leftoversGoOthersStay() {
        val dir = java.nio.file.Files.createTempDirectory("cache").toFile()
        val old = java.io.File(dir, attachmentCacheName(readerCacheToken(), 3, "2")).apply { writeText("x") }
        val part = java.io.File(dir, old.name + ".part").apply { writeText("x") }
        val other = java.io.File(dir, "imap-traffic.log").apply { writeText("x") }
        deleteStaleAttachments(dir)
        org.junit.Assert.assertFalse(old.exists() || part.exists())
        assertTrue(other.exists())
    }
}
