package org.dlang.liveimap.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun emptyIdReturnsOnlyLegacyDir() {
        val filesDir = File("/tmp/liveimap-files")
        assertEquals(listOf(File(filesDir, "unsent")), unsentDirs(filesDir, ""))
    }

    @Test
    fun accountIdReturnsAccountDirThenLegacy() {
        val filesDir = File("/tmp/liveimap-files")
        assertEquals(
            listOf(File(File(filesDir, "unsent"), "acct-1"), File(filesDir, "unsent")),
            unsentDirs(filesDir, "acct-1"),
        )
    }

    @Test
    fun mergeKeepsAccountCopyThenNewLegacy() {
        val accountA = DeviceCopy("a", false, "acct", emptyList(), "A".toByteArray())
        val legacyA = DeviceCopy("a", true, "legacy", emptyList(), "L".toByteArray())
        val legacyC = DeviceCopy("c", false, "legacy", emptyList(), "C".toByteArray())
        val merged = mergedAccountCopies(listOf(accountA), listOf(legacyA, legacyC))
        assertEquals(listOf("a", "c"), merged.map { it.id })
        assertEquals("acct", merged[0].mailbox)
        assertEquals("A", merged[0].bytes.toString(Charsets.UTF_8))
        assertEquals("legacy", merged[1].mailbox)
    }

    @Test
    fun writeGoesToAccountDirAndDeleteRemovesBoth() {
        val root = File(System.getProperty("java.io.tmpdir"), "liveimap-unsent-" + System.nanoTime())
        try {
            val accountA = DeviceCopy("a", false, "acct", listOf("a@b"), "A".toByteArray())
            val legacyA = DeviceCopy("a", true, "legacy", listOf("a@b"), "L".toByteArray())
            val legacyC = DeviceCopy("c", false, "legacy", emptyList(), "C".toByteArray())
            val accountB = DeviceCopy("b", false, "acct", emptyList(), "B".toByteArray())
            writeCopy(root, accountA, "acct-1")
            writeCopy(root, legacyA, "")
            writeCopy(root, legacyC, "")
            writeCopy(root, accountB, "acct-1")
            val accountDir = File(File(root, "unsent"), "acct-1")
            val legacyDir = File(root, "unsent")
            assertTrue(File(accountDir, "a.rfc822").isFile)
            assertTrue(File(accountDir, "b.rfc822").isFile)
            assertFalse(File(legacyDir, "b.rfc822").isFile)
            val read = readCopies(root, "acct-1")
            assertEquals(listOf("a", "b", "c"), read.map { it.id })
            assertEquals("acct", read[0].mailbox)
            assertEquals("A", read[0].bytes.toString(Charsets.UTF_8))
            assertEquals("legacy", read.first { it.id == "c" }.mailbox)
            deleteCopy(root, "a", "acct-1")
            assertFalse(File(accountDir, "a.rfc822").isFile)
            assertFalse(File(accountDir, "a.meta").isFile)
            assertFalse(File(legacyDir, "a.rfc822").isFile)
            assertFalse(File(legacyDir, "a.meta").isFile)
            assertTrue(File(legacyDir, "c.rfc822").isFile)
            assertEquals(listOf("b", "c"), readCopies(root, "acct-1").map { it.id })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deleteRemovesOneAccountDirectory() {
        val root = File(System.getProperty("java.io.tmpdir"), "liveimap-unsent-" + System.nanoTime())
        try {
            val accountDir = File(File(root, "unsent"), "acct-1")
            val otherDir = File(File(root, "unsent"), "other")
            val legacyMeta = File(File(root, "unsent"), "old.meta")
            accountDir.mkdirs()
            otherDir.mkdirs()
            File(accountDir, "a.rfc822").writeText("A", Charsets.UTF_8)
            File(otherDir, "b.rfc822").writeText("B", Charsets.UTF_8)
            legacyMeta.writeText("old", Charsets.UTF_8)
            deleteUnsentAccount(root, "acct-1")
            assertFalse(accountDir.exists())
            assertTrue(otherDir.isDirectory)
            assertTrue(File(otherDir, "b.rfc822").isFile)
            assertTrue(legacyMeta.isFile)
            assertEquals("old", legacyMeta.readText(Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emptyAndSlashIdsLeaveLegacyUnsent() {
        val root = File(System.getProperty("java.io.tmpdir"), "liveimap-unsent-" + System.nanoTime())
        try {
            val legacy = File(root, "unsent")
            val kept = File(legacy, "kept.meta")
            legacy.mkdirs()
            kept.writeText("stay", Charsets.UTF_8)
            deleteUnsentAccount(root, "")
            deleteUnsentAccount(root, "a/b")
            assertTrue(legacy.isDirectory)
            assertTrue(kept.isFile)
            assertEquals("stay", kept.readText(Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }
}
