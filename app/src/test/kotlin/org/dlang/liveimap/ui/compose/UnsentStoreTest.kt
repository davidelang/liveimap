package org.dlang.liveimap.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class UnsentStoreTest {
    @Test
    fun emptyIdUsesLegacyUnsentDir() {
        val filesDir = File("/tmp/liveimap-files")
        val legacy = File(filesDir, "unsent")
        assertEquals(legacy, unsentAccountDir(filesDir, ""))
        assertEquals(legacy, unsentAccountDir(filesDir, "   "))
    }

    @Test
    fun accountIdIsOneSegmentUnderUnsent() {
        val filesDir = File("/tmp/liveimap-files")
        assertEquals(File(File(filesDir, "unsent"), "acct-1"), unsentAccountDir(filesDir, "acct-1"))
    }

    @Test
    fun unsafeIdsStayOnLegacyDir() {
        val filesDir = File("/tmp/liveimap-files")
        val legacy = File(filesDir, "unsent")
        assertEquals(legacy, unsentAccountDir(filesDir, "."))
        assertEquals(legacy, unsentAccountDir(filesDir, ".."))
        assertEquals(legacy, unsentAccountDir(filesDir, "a/b"))
        assertEquals(legacy, unsentAccountDir(filesDir, "a\\b"))
    }

    @Test
    fun sentMailStaysOnePathSegment() {
        val filesDir = File("/tmp/liveimap-files")
        assertEquals(File(File(filesDir, "unsent"), "Sent Mail"), unsentAccountDir(filesDir, "Sent Mail"))
    }
}
