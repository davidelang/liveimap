package org.dlang.liveimap.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnsentStoreTest {
    @Test
    fun oddIdsGetTheirOwnHashedDirNeverTheSharedOne() {
        // F51: no id falls back to the shared legacy directory
        val filesDir = File("/tmp/liveimap-files")
        val legacy = File(filesDir, "unsent")
        val dirs = listOf("", "   ", ".", "..", "a/b", "a\\b").map { unsentAccountDir(filesDir, it) }
        for (d in dirs) {
            assertEquals(legacy, d.parentFile)
            assertTrue(d.name.startsWith(".id-"))
        }
        assertEquals(dirs.size, dirs.toSet().size)
    }

    @Test
    fun accountIdIsOneSegmentUnderUnsent() {
        val filesDir = File("/tmp/liveimap-files")
        assertEquals(File(File(filesDir, "unsent"), "acct-1"), unsentAccountDir(filesDir, "acct-1"))
    }

    @Test
    fun sentMailStaysOnePathSegment() {
        val filesDir = File("/tmp/liveimap-files")
        assertEquals(File(File(filesDir, "unsent"), "Sent Mail"), unsentAccountDir(filesDir, "Sent Mail"))
    }

    @Test
    fun accountIdReturnsOnlyItsOwnDir() {
        val filesDir = File("/tmp/liveimap-files")
        assertEquals(listOf(File(File(filesDir, "unsent"), "acct-1")), unsentDirs(filesDir, "acct-1"))
    }

    @Test
    fun accountsNeverSeeEachOthersOrLegacyCopies() {
        val root = File(System.getProperty("java.io.tmpdir"), "liveimap-unsent-" + System.nanoTime())
        try {
            writeCopy(root, DeviceCopy("a", false, "Sent", listOf("a@b"), "A".toByteArray()), "acct-1")
            writeCopy(root, DeviceCopy("b", false, "Sent", emptyList(), "B".toByteArray()), "acct-2")
            writeCopy(root, DeviceCopy("z", false, "Sent", emptyList(), "Z".toByteArray()), "")
            val legacy = File(root, "unsent")
            File(legacy, "old.rfc822").writeText("O")
            File(legacy, "old.meta").writeText("unsent\nSent\nx@y\n")
            assertEquals(listOf("a"), readCopies(root, "acct-1").map { it.id })
            assertEquals(listOf("b"), readCopies(root, "acct-2").map { it.id })
            deleteCopy(root, "old", "acct-1")
            assertTrue(File(legacy, "old.meta").isFile)  // discard under one account never touches shared files
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

    @Test
    fun uncertainCopyRoundTripsAtomicallyAndWriteFailureIsReported() {
        val root = File(System.getProperty("java.io.tmpdir"), "liveimap-unsent-" + System.nanoTime())
        try {
            val copy = DeviceCopy("u", false, "Sent", listOf("a@b"), "U".toByteArray(), uncertain = true)
            assertTrue(writeCopy(root, copy, "acct-1"))
            val read = readCopies(root, "acct-1").single()
            assertTrue(read.uncertain)
            assertFalse(read.appendOnly)
            val dir = unsentAccountDir(root, "acct-1")
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
            assertTrue(writeCopy(root, read.copyWith(uncertain = false), "acct-1"))
            assertFalse(readCopies(root, "acct-1").single().uncertain)
            // F50: a failed write is reported, not swallowed
            val blocked = File(root, "blocked")
            blocked.writeText("not a directory")
            assertFalse(writeCopy(blocked, copy, "acct-1"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun deviceCopyAfterSmtpFailureKeepsAnUncertainOutcome() {
        val copy = DeviceCopy("u", false, "Sent", listOf("a@b"), "U".toByteArray())
        assertTrue(deviceCopyAfterSmtpFailure(copy, "smtp-uncertain: DATA: timeout").uncertain)
        assertFalse(deviceCopyAfterSmtpFailure(copy, "connection refused").uncertain)
    }

    @Test
    fun uncertainSmtpPrefix() {
        assertTrue(smtpUncertain("smtp-uncertain: DATA: timeout"))
        assertEquals("DATA: timeout", smtpFailureText("smtp-uncertain: DATA: timeout"))
        assertFalse(smtpUncertain("RCPT: 550 no"))
    }
}
