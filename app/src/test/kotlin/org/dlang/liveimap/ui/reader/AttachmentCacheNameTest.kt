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
