package org.dlang.liveimap.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficFilesTest {
    private fun dir(): File = Files.createTempDirectory("traffic").toFile()

    private fun writer(root: File, roll: Long = 0): TrafficWriter {
        return TrafficLog.writer(root, "acct-1", roll) ?: error("writer")
    }

    @Test
    fun oneActorPerAccount() {
        val root = dir()
        val first = writer(root)
        val second = writer(root, 99)
        assertTrue(first === second)
    }

    @Test
    fun twoRecordsMatchHeaderAndBytes() {
        val root = dir()
        val w = writer(root)
        val a = "hello".toByteArray()
        val b = "world".toByteArray()
        w.record("app", '>', a)
        w.record("app", '<', b)
        w.sync()
        val records = TrafficLog.recordsIn(File(File(root, "traffic"), "acct-1"))
        assertEquals(2, records.size)
        assertEquals(">", records[0].dir.toString())
        assertEquals("<", records[1].dir.toString())
        assertTrue(a.contentEquals(records[0].bytes))
        assertTrue(b.contentEquals(records[1].bytes))
        val raw = File(File(File(root, "traffic"), "acct-1"), records[0].name).readBytes()
        val header = String(raw, 0, raw.indexOf('\n'.code.toByte()), Charsets.US_ASCII)
        assertEquals(a.size.toString(), header.substringAfterLast(' '))
    }

    @Test
    fun writerRollsAndTheNextNameSortsAfter() {
        val root = dir()
        val w = writer(root, 1)
        w.record("app", '>', "aa".toByteArray())
        w.record("app", '>', "bb".toByteArray())
        w.sync()
        val names = File(File(root, "traffic"), "acct-1").list()?.sorted() ?: emptyList()
        assertEquals(2, names.size)
        assertTrue(names[0] < names[1])
    }

    @Test
    fun unlinkedFileOpensTheNextName() {
        val root = dir()
        val w = writer(root)
        w.record("app", '>', "one".toByteArray())
        w.sync()
        val folder = File(File(root, "traffic"), "acct-1")
        val first = folder.listFiles()?.single() ?: error("file")
        TrafficLog.sweep(folder, 0, System.currentTimeMillis())
        assertTrue(!first.exists())
        w.record("app", '>', "two".toByteArray())
        w.sync()
        val left = folder.listFiles()?.filter { it.isFile } ?: emptyList()
        assertEquals(1, left.size)
        assertTrue(left[0].name != first.name)
        assertTrue(TrafficLog.readRecords(left[0]).single().bytes.contentEquals("two".toByteArray()))
    }

    @Test
    fun sweepKeepsTheCapAndRemovesOldFiles() {
        val root = dir()
        val folder = File(File(root, "traffic"), "acct-1").apply { mkdirs() }
        val old = File(folder, "1-app-000001.log").apply { writeBytes(recordBytes(1, "app", '>', "old".toByteArray())) }
        old.setLastModified(System.currentTimeMillis() - 31L * 24L * 60L * 60L * 1000L)
        val keep = File(folder, "2-app-000002.log").apply { writeBytes(ByteArray(50)) }
        val drop = File(folder, "3-app-000003.log").apply { writeBytes(ByteArray(80)) }
        TrafficLog.sweep(folder, 60, System.currentTimeMillis())
        assertTrue(!old.exists())
        assertTrue(folder.listFiles()?.sumOf { it.length() } ?: 0L <= 60L)
        assertTrue(!keep.exists() || !drop.exists())
    }

    @Test
    fun mergeOrdersThreeFilesByEpoch() {
        val root = dir()
        val folder = File(File(root, "traffic"), "acct-1").apply { mkdirs() }
        File(folder, "300-app-000003.log").writeBytes(recordBytes(30, "app", '>', "c".toByteArray()))
        File(folder, "100-app-000001.log").writeBytes(recordBytes(10, "app", '>', "a".toByteArray()))
        File(folder, "200-app-000002.log").writeBytes(recordBytes(20, "app", '>', "b".toByteArray()))
        val ordered = TrafficLog.orderedRecords(folder)
        assertEquals(listOf("a", "b", "c"), ordered.map { String(it.bytes) })
        val out = File(root, "merged.log")
        TrafficLog.writeOrdered(out, ordered)
        assertEquals(listOf("a", "b", "c"), TrafficLog.readRecords(out).map { String(it.bytes) })
    }

    @Test
    fun twoThreadsLeaveTwoIntactRecords() {
        val root = dir()
        val w = writer(root)
        val a = "aaaa".toByteArray()
        val b = "bbbb".toByteArray()
        val first = Thread { w.record("app", '>', a) }
        val second = Thread { w.record("app", '<', b) }
        first.start()
        second.start()
        first.join()
        second.join()
        w.sync()
        val records = TrafficLog.recordsIn(File(File(root, "traffic"), "acct-1"))
        assertEquals(2, records.size)
        assertEquals(listOf("aaaa", "bbbb"), records.map { String(it.bytes) }.sorted())
    }

    @Test
    fun privateMarkerEighteenIsEighteenBytes() {
        val marker = TrafficLog.privateMarker(18)
        assertEquals("<private 18 bytes>", String(marker, Charsets.US_ASCII))
        assertEquals(18, marker.size)
        val raw = recordBytes(1, "app", '>', marker)
        val header = String(raw, 0, raw.indexOf('\n'.code.toByte()), Charsets.US_ASCII)
        assertEquals("18", header.substringAfterLast(' '))
    }
}
