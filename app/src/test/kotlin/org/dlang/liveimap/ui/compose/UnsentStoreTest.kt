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
            writeCopy(root, DeviceCopy("a", false, "Sent", listOf("a@b"), src(root, "a")), "acct-1")
            writeCopy(root, DeviceCopy("b", false, "Sent", emptyList(), src(root, "b")), "acct-2")
            writeCopy(root, DeviceCopy("z", false, "Sent", emptyList(), src(root, "z")), "")
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
    fun replyRetryFromUnsentSetsAnswered() {
        val root = File(System.getProperty("java.io.tmpdir"), "liveimap-unsent-" + System.nanoTime())
        try {
            val copy = DeviceCopy(
                "r",
                false,
                "Sent",
                listOf("a@b"),
                src(root, "r"),
                sourceKind = "Reply",
                sourceMailbox = "INBOX",
                sourceEpoch = 9L,
                sourceUids = listOf(5L),
            )
            assertTrue(writeCopy(root, copy, "acct-1"))
            val read = readCopies(root, "acct-1").single()
            assertTrue("\\Answered" in unsentRetryFlags(read))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun uncertainCopyRoundTripsAtomicallyAndWriteFailureIsReported() {
        val root = File(System.getProperty("java.io.tmpdir"), "liveimap-unsent-" + System.nanoTime())
        try {
            val copy = DeviceCopy("u", false, "Sent", listOf("a@b"), src(root, "u"), uncertain = true)
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
        val copy = DeviceCopy("u", false, "Sent", listOf("a@b"), File("u.rfc822"))
        assertTrue(deviceCopyAfterSmtpFailure(copy, "smtp-uncertain: DATA: timeout").uncertain)
        assertFalse(deviceCopyAfterSmtpFailure(copy, "connection refused").uncertain)
    }

    @Test
    fun uncertainSmtpPrefix() {
        assertTrue(smtpUncertain("smtp-uncertain: DATA: timeout"))
        assertEquals("DATA: timeout", smtpFailureText("smtp-uncertain: DATA: timeout"))
        assertFalse(smtpUncertain("RCPT: 550 no"))
    }

    @Test
    fun secondCreateThrowsAndReadCopiesKeepsOpenAndListedDirs() {
        val root = File(System.getProperty("java.io.tmpdir"), "liveimap-unsent-" + System.nanoTime())
        val openId = "open-" + System.nanoTime()
        try {
            createMessageDir(root, "acct-1", "draft-1")
            var threw = false
            try {
                createMessageDir(root, "acct-1", "draft-1")
            } catch (error: java.nio.file.FileAlreadyExistsException) {
                threw = true
            }
            assertTrue(threw)
            assertTrue(messageDir(root, "acct-1", "draft-1").isDirectory)
            val accountDir = unsentAccountDir(root, "acct-1")
            assertTrue(writeCopy(root, DeviceCopy("listed", false, "Sent", emptyList(), src(root, "listed")), "acct-1"))
            val listedDir = messageDir(root, "acct-1", "listed")
            File(listedDir, "contents").mkdirs()
            File(messageDir(root, "acct-1", "orphan"), "contents").mkdirs()
            File(messageDir(root, "acct-1", openId), "contents").mkdirs()
            val stray = File(accountDir, "note.txt")
            stray.writeText("stay", Charsets.UTF_8)
            openCompose(openId)
            assertEquals(listOf("listed"), readCopies(root, "acct-1").map { it.id })
            assertTrue(listedDir.isDirectory)
            assertTrue(messageDir(root, "acct-1", openId).isDirectory)
            assertFalse(messageDir(root, "acct-1", "orphan").exists())
            assertFalse(messageDir(root, "acct-1", "draft-1").exists())
            assertTrue(stray.isFile)
            assertEquals("stay", stray.readText(Charsets.UTF_8))
            assertTrue(accountDir.isDirectory)
            deleteCopy(root, "listed", "acct-1")
            assertFalse(listedDir.exists())
            assertFalse(File(accountDir, "listed.meta").exists())
            assertFalse(File(accountDir, "listed.rfc822").exists())
            assertTrue(accountDir.isDirectory)
            assertTrue(messageDir(root, "acct-1", openId).isDirectory)
            assertTrue(stray.isFile)
        } finally {
            closeCompose(openId)
            root.deleteRecursively()
        }
    }

    @Test
    fun listDoesNotNeedABodyAndABadMetaIsVisible() {
        val root = File(System.getProperty("java.io.tmpdir"), "liveimap-unsent-" + System.nanoTime())
        try {
            val dir = unsentAccountDir(root, "acct-1")
            dir.mkdirs()
            File(dir, "old.meta").writeText("unsent\nSent\nx@y\n", Charsets.UTF_8)
            File(dir, "sub.meta").writeText("uncertain via=acct-2\nSent\nsubject:Hello\na@b\n", Charsets.UTF_8)
            File(dir, "bad.meta").writeText("x", Charsets.UTF_8)
            val rows = readCopies(root, "acct-1").associateBy { it.id }
            assertEquals(setOf("old", "sub", "bad"), rows.keys)
            assertFalse(File(dir, "old.rfc822").isFile)
            assertFalse(File(dir, "sub.rfc822").isFile)
            val old = rows.getValue("old")
            assertEquals(null, old.subject)
            assertEquals(null, old.error)
            assertEquals("Sent", old.mailbox)
            assertEquals(listOf("x@y"), old.recipients)
            val sub = rows.getValue("sub")
            assertEquals("Hello", sub.subject)
            assertEquals(listOf("a@b"), sub.recipients)
            assertTrue(sub.uncertain)
            assertEquals("acct-2", sub.via)
            assertEquals("bad meta", rows.getValue("bad").error)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun src(root: File, name: String): File {
        root.mkdirs()
        return File(root, "$name.src").also { it.writeBytes(name.toByteArray()) }
    }
}
