package org.dlang.liveimap.ui.compose

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
        assertNull(reloadCopyId("", null))
        assertEquals("unsent", reloadCopyId("", "unsent"))
        assertEquals("written", reloadCopyId("written", null))
        assertEquals("written", reloadCopyId("written", "unsent"))
        assertEquals("", copyIdAfterDeleted(true, "written"))
        assertEquals("written", copyIdAfterDeleted(false, "written"))
    }
}
