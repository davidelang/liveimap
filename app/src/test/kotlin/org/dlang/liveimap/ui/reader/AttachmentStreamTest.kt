package org.dlang.liveimap.ui.reader

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

class AttachmentStreamTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun byteAt(i: Long) = ((i * 31 + 7) % 251).toByte()

    @Test(timeout = 60_000)
    fun largeAttachmentStreamsWithBoundedChunks() = runBlocking {
        // 64 MB declared size: far more than a test JVM should hold twice; every fetch is one bounded chunk.
        val size = 64 * 1024 * 1024
        val chunk = 65536
        var maxRequested = 0
        val sent = MessageDigest.getInstance("SHA-256")
        val target = File(tmp.root, "liveimap-7-2")
        val written = streamAttachment(target, size, chunk, fetch = { offset, length ->
            maxRequested = maxOf(maxRequested, length)
            val n = minOf(length, size - offset)
            ByteArray(n) { byteAt((offset + it).toLong()) }.also { sent.update(it) }
        })
        assertEquals(size, written)
        assertEquals(size.toLong(), target.length())
        assertTrue("chunk $maxRequested", maxRequested <= chunk + 4)
        val got = MessageDigest.getInstance("SHA-256")
        target.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) { val r = input.read(buf); if (r < 0) break; got.update(buf, 0, r) }
        }
        assertArrayEquals(sent.digest(), got.digest())
        assertFalse(File(tmp.root, "liveimap-7-2.part").exists())
    }

    @Test fun failureLeavesNoPartialFileAndKeepsOldTarget() = runBlocking {
        val target = File(tmp.root, "liveimap-7-3").apply { writeText("old") }
        try {
            streamAttachment(target, 300_000, 65536, fetch = { offset, length ->
                if (offset > 100_000) throw java.io.IOException("dropped") else ByteArray(length)
            })
            fail("expected failure")
        } catch (_: java.io.IOException) {
        }
        assertEquals("old", target.readText())
        assertFalse(File(tmp.root, "liveimap-7-3.part").exists())
    }

    @Test fun unknownSizeFetchesOneChunk() = runBlocking {
        val target = File(tmp.root, "liveimap-7-4")
        assertEquals(10, streamAttachment(target, 0, 65536, fetch = { _, _ -> ByteArray(10) { 1 } }))
        assertEquals(10L, target.length())
    }
}
