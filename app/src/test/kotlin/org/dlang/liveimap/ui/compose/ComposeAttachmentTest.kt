package org.dlang.liveimap.ui.compose

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeAttachmentTest {
    @Test
    fun fileAttachmentRowAndHold() {
        val named = fileAttachmentRow("k-named", "note.txt", "text/plain", "hi".encodeToByteArray())
        assertEquals("k-named", named.key)
        assertEquals("note.txt", named.filename)
        assertEquals("text/plain", named.mediaType)
        assertEquals(2, named.size)
        assertTrue(named.included)
        assertEquals("", named.section)
        assertFalse(named.wireBase64)
        assertNull(named.encoding)
        assertEquals("hi".encodeToByteArray().toList(), named.bytes!!.toList())

        val empty = fileAttachmentRow("k-empty", "", "", byteArrayOf(1, 2, 3))
        assertEquals("attachment", empty.filename)
        assertEquals("application/octet-stream", empty.mediaType)
        assertEquals(3, empty.size)
        assertTrue(empty.included)
        assertEquals("", empty.section)
        assertFalse(empty.wireBase64)
        assertNull(empty.encoding)

        PickedAttachmentHold.put(named)
        try {
            assertEquals("hi".encodeToByteArray().toList(), PickedAttachmentHold.get("k-named")!!.bytes!!.toList())
            PickedAttachmentHold.remove("k-named")
            assertNull(PickedAttachmentHold.get("k-named"))
        } finally {
            PickedAttachmentHold.remove("k-named")
        }
    }

    @Test
    fun heldRowWithAPathHasNullBytes() {
        val file = File(System.getProperty("java.io.tmpdir"), "liveimap-pick-" + System.nanoTime())
        try {
            file.writeBytes("hi".encodeToByteArray())
            val row = fileAttachmentRow("k-path", "", "text/plain", null, file.path)
            assertEquals("k-path", row.key)
            assertEquals("attachment", row.filename)
            assertEquals("text/plain", row.mediaType)
            assertEquals(2, row.size)
            assertNull(row.bytes)
            assertEquals(file.path, row.path)
            assertEquals(file.path, row.toggle().path)
            assertFalse(row.toggle().included)
            PickedAttachmentHold.put(row)
            try {
                val held = PickedAttachmentHold.get("k-path")
                assertNull(held!!.bytes)
                assertEquals(file.path, held.path)
            } finally {
                PickedAttachmentHold.remove("k-path")
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun pickedRowsRoundTripInOrder() {
        val first = fileAttachmentRow("k-one", "one.txt", "text/plain", "AAA".encodeToByteArray())
        val second = fileAttachmentRow("k-two", "two.bin", "application/octet-stream", byteArrayOf(9, 8))
        val built = buildPlain(
            PlainMessage(
                fromName = "Me",
                fromEmail = "me@example.com",
                to = listOf("ann@example.com"),
                cc = emptyList(),
                bcc = emptyList(),
                subject = "Hi",
                body = "Body",
                messageId = "<picked@example.com>",
                date = "Tue, 21 Oct 2026 00:00:00 +0000",
                inReplyTo = "",
                references = "",
                attachments = listOf(
                    OutgoingPart(first.filename, first.mediaType, first.bytes!!, first.wireBase64, first.encoding),
                    OutgoingPart(second.filename, second.mediaType, second.bytes!!, second.wireBase64, second.encoding),
                ),
            ),
        )
        val loaded = loadEditor(built.rfc822)
        assertEquals(listOf("one.txt", "two.bin"), loaded.attachments.map { it.filename })
        assertEquals("AAA".encodeToByteArray().toList(), loaded.attachments[0].bytes.toList())
        assertEquals(listOf(9.toByte(), 8.toByte()), loaded.attachments[1].bytes.toList())
    }

    @Test
    fun storedCopyDoesNotRestoreTheSamePickedFile() {
        val loadedRow = fileAttachmentRow("resume:0", "one.txt", "text/plain", "AAA".encodeToByteArray())
        val heldRow = fileAttachmentRow("pick-1", "two.txt", "text/plain", "BBB".encodeToByteArray())
        val loaded = listOf(loadedRow)
        val held = listOf(heldRow)
        assertEquals(listOf(loadedRow), restoreHeldPickedRows(true, loaded, held))
        assertEquals(listOf(loadedRow, heldRow), restoreHeldPickedRows(false, loaded, held))
        val sameKey = fileAttachmentRow("resume:0", "one.txt", "text/plain", "AAA".encodeToByteArray())
        assertEquals(listOf(loadedRow), restoreHeldPickedRows(false, loaded, listOf(sameKey)))
    }

    @Test
    fun failedWriteKeepsTheOldCopyAndAWrittenCopyReloads() {
        val failed = assembledCopyNote(false, "new", "old")
        assertEquals("old", failed.first)
        assertFalse(failed.second)
        val wrote = assembledCopyNote(true, "new", "old")
        assertEquals("new", wrote.first)
        assertTrue(wrote.second)
        assertNull(reloadCopyId("", null, false))
        assertEquals("unsent", reloadCopyId("", "unsent", false))
        assertEquals("written", reloadCopyId("written", null, false))
        assertEquals("written", reloadCopyId("written", "unsent", false))
        assertNull(reloadCopyId("written", "unsent", true))
        assertEquals("", copyIdAfterDeleted(true, "written"))
        assertEquals("written", copyIdAfterDeleted(false, "written"))
    }

    @Test
    fun attachmentLifeFollowsOneTable() {
        val written = "written"
        val current = "current"
        val unsent = "unsent"
        var cells = 0
        val newOrigin = ComposeCopyOrigin.New
        val replyOrigin = ComposeCopyOrigin.Reply
        for (action in ComposeCopyAction.entries) {
            for (appendOnly in listOf(false, true)) {
                assertEquals(
                    composeAttachmentLife(newOrigin, action, written, null, current, appendOnly),
                    composeAttachmentLife(replyOrigin, action, written, null, current, appendOnly),
                )
            }
        }
        for (origin in listOf(ComposeCopyOrigin.New, ComposeCopyOrigin.Reply, ComposeCopyOrigin.Unsent)) {
            val unsentId = if (origin == ComposeCopyOrigin.Unsent) unsent else null
            val success = composeAttachmentLife(origin, ComposeCopyAction.SendSuccess, written, unsentId, current, false)
            assertCell(success, ComposeCopyDisk.Delete, "", true, null, true)
            cells += 1
            val wrote = composeAttachmentLife(origin, ComposeCopyAction.SendFailureWritten, written, unsentId, current, false)
            assertCell(wrote, ComposeCopyDisk.Keep, written, true, written, false)
            cells += 1
            val noWrite = composeAttachmentLife(origin, ComposeCopyAction.SendFailureNoWrite, written, unsentId, current, false)
            if (origin == ComposeCopyOrigin.Unsent) {
                val fresh = composeAttachmentLife(origin, ComposeCopyAction.SendFailureNoWrite, written, unsentId, "", false)
                assertCell(fresh, ComposeCopyDisk.Keep, "", false, unsent, false)
            } else {
                assertCell(noWrite, ComposeCopyDisk.None, current, false, current, false)
                val heldOnly = composeAttachmentLife(origin, ComposeCopyAction.SendFailureNoWrite, "", null, "", false)
                assertCell(heldOnly, ComposeCopyDisk.None, "", false, null, false)
            }
            cells += 1
            val postponeDelete = composeAttachmentLife(origin, ComposeCopyAction.PostponeSuccess, written, unsentId, current, false)
            assertCell(postponeDelete, ComposeCopyDisk.Delete, "", true, null, true)
            val postponeKeep = composeAttachmentLife(origin, ComposeCopyAction.PostponeSuccess, written, unsentId, current, true)
            assertCell(postponeKeep, ComposeCopyDisk.Keep, current, true, null, true)
            cells += 1
            val drop = composeAttachmentLife(origin, ComposeCopyAction.SendWithoutCopyDrop, written, unsentId, current, true)
            assertCell(drop, ComposeCopyDisk.Delete, "", true, null, false)
            cells += 1
            assertEquals(success, composeAttachmentLife(origin, ComposeCopyAction.SendSuccess, written, unsentId, current, false))
            assertEquals(wrote, composeAttachmentLife(origin, ComposeCopyAction.SendFailureWritten, written, unsentId, current, false))
            assertEquals(noWrite, composeAttachmentLife(origin, ComposeCopyAction.SendFailureNoWrite, written, unsentId, current, false))
            assertFalse(drop.closes)
            cells += 1
            val cancel = composeAttachmentLife(origin, ComposeCopyAction.Cancel, written, unsentId, current, false)
            val cancelDisk = if (origin == ComposeCopyOrigin.Unsent) ComposeCopyDisk.Keep else ComposeCopyDisk.None
            assertCell(cancel, cancelDisk, current, true, null, true)
            cells += 1
        }
        assertEquals(21, cells)
        assertEquals(6, ComposeCopyAction.entries.size)
    }

    @Test
    fun lifeIsAppliedOnTheTerminalPaths() {
        val src = listOf(
            "src/main/kotlin/org/dlang/liveimap/ui/compose/ComposeScreen.kt",
            "app/src/main/kotlin/org/dlang/liveimap/ui/compose/ComposeScreen.kt",
        ).map(::File).first { it.exists() }.readText()
        fun slice(start: String, end: String, from: Int = 0): String {
            val at = src.indexOf(start, from)
            val stop = src.indexOf(end, at + start.length)
            assertTrue(start, at >= 0 && stop > at)
            return src.substring(at, stop)
        }
        val smtp = slice(
            "deleteCopy(appContext, id, accountId)",
            "status = sentStatus(statusLeaf(account.sentMailbox))",
        )
        assertTrue(smtp.contains("composeAttachmentLife"))
        assertTrue(smtp.contains("ComposeCopyAction.SendSuccess"))
        val jmap = slice("keepCopyUntilSubmitted(sending,", "jmapSendFailed(sending")
        assertTrue(jmap.contains("composeAttachmentLife"))
        assertTrue(jmap.contains("ComposeCopyAction.SendSuccess"))
        val postpone = slice("fun postponeDraft()", "fun loadStoredCopy")
        assertTrue(postpone.contains("composeAttachmentLife"))
        assertTrue(postpone.contains("ComposeCopyAction.PostponeSuccess"))
        val drop = slice("if (kept != null && kept.appendOnly)", "else if (kept != null && noCopyRetry)")
        assertTrue(drop.contains("composeAttachmentLife"))
        assertTrue(drop.contains("ComposeCopyAction.SendWithoutCopyDrop"))
        assertFalse(drop.contains("onDone"))
        val discard = slice("compose_discard_title", "compose_keep_editing")
        assertTrue(discard.contains("composeAttachmentLife"))
        assertTrue(discard.contains("ComposeCopyAction.Cancel"))
        assertTrue(discard.contains("onDone()"))
        val back = slice("fun requestClose()", "fun postponeDraft()")
        assertTrue(back.contains("composeAttachmentLife"))
        assertTrue(back.contains("ComposeCopyAction.Cancel"))
        assertTrue(back.contains("onDone()"))
    }

    private fun assertCell(
        life: ComposeAttachmentLife,
        disk: ComposeCopyDisk,
        copyId: String,
        releaseHold: Boolean,
        reloadId: String?,
        closes: Boolean,
    ) {
        assertEquals(disk, life.disk)
        assertEquals(copyId, life.copyId)
        assertEquals(releaseHold, life.releaseHold)
        assertEquals(reloadId, life.reloadId)
        assertEquals(closes, life.closes)
        assertFalse(life.unsentGone)
    }
}
