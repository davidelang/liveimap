package org.dlang.liveimap.engine

import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.NamespaceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDecodeTest {
    @Test fun watchNotesStayInOrderAndKeepANonAsciiFlag() {
        val bytes = note(0, 3, 0) + note(2, 3, 9, "déjà", "\\Seen") + note(1, 2, 0)
        val notes = decodeWatchNotes(bytes)
        assertEquals(3, notes.size)
        assertEquals(0, notes[0].kind)
        assertEquals(3, notes[0].exists)
        assertEquals(2, notes[1].kind)
        assertEquals(9L, notes[1].uid)
        assertEquals(listOf("déjà", "\\Seen"), notes[1].flags.toList())
        assertEquals(1, notes[2].kind)
        assertEquals(2, notes[2].exists)
    }

    @Test fun kind3IsUidValidityReset() {
        val notes = decodeWatchNotes(note(3, 4, 0))
        assertEquals(1, notes.size)
        assertTrue(watchChangeOf(notes[0].kind, notes[0].exists, notes[0].uid, notes[0].flags) is MailboxChange.UidValidityReset)
    }

    @Test fun unequalIndexColumnsFail() {
        val columns = indexColumns(1)
        columns[0] = LongArray(2)
        assertDecodeFails { indexRowsOf(columns) }
    }

    @Test fun indexRowRebuildsTheFieldsCUsedToConstruct() {
        val columns = indexColumns(1)
        (columns[0] as LongArray)[0] = 7
        (columns[1] as LongArray)[0] = 8
        (columns[2] as LongArray)[0] = 9
        (columns[3] as IntArray)[0] = 2
        (columns[4] as IntArray)[0] = 100
        (columns[5] as IntArray)[0] = 1
        (columns[6] as IntArray)[0] = 1
        (columns[7] as IntArray)[0] = 1
        (columns[8] as Array<String?>)[0] = "acct"
        (columns[9] as Array<String?>)[0] = "INBOX"
        (columns[10] as Array<String?>)[0] = "from"
        (columns[11] as Array<String?>)[0] = "subject"
        (columns[12] as Array<String?>)[0] = "date"
        (columns[13] as Array<String?>)[0] = null
        (columns[14] as Array<String?>)[0] = "to"
        (columns[15] as Array<String?>)[0] = "note"
        (columns[16] as Array<String?>)[0] = "\\Seen\u0000\\Flagged"
        val row = indexRowsOf(columns).single()
        assertEquals(7L, row.uid)
        assertEquals(8L, row.ref.uidValidity)
        assertEquals("acct", row.ref.accountId)
        assertEquals("INBOX", row.mailbox)
        assertEquals(2, row.sequence)
        assertEquals(setOf("\\Seen", "\\Flagged"), row.flags)
        assertEquals(9L, row.internalDateEpoch)
        assertEquals(100, row.size)
        assertEquals("from", row.from)
        assertEquals("subject", row.subject)
        assertEquals("date", row.envelopeDate)
        assertNull(row.preview)
        assertTrue(row.toMe)
        assertTrue(row.hasAttachment)
        assertEquals("to", row.recipients)
        assertEquals("note", row.headerNote)
        assertTrue(row.previewFailed)
    }

    @Test fun unequalFolderColumnsFail() {
        val columns = folderColumns(1)
        columns[3] = IntArray(0)
        assertDecodeFails { foldersOf(columns) }
    }

    @Test fun folderRebuildsNullableCounts() {
        val columns = folderColumns(1)
        (columns[0] as Array<String?>)[0] = "INBOX"
        (columns[1] as Array<String?>)[0] = "INBOX"
        (columns[2] as Array<String?>)[0] = "\\Inbox"
        (columns[3] as IntArray)[0] = 1
        (columns[4] as IntArray)[0] = '/'.code
        (columns[5] as IntArray)[0] = 4
        (columns[6] as IntArray)[0] = -1
        val folder = foldersOf(columns).single()
        assertEquals("INBOX", folder.mailbox)
        assertEquals("INBOX", folder.leaf)
        assertTrue(folder.hasChildren)
        assertEquals('/', folder.delimiter)
        assertEquals("\\Inbox", folder.specialUse)
        assertEquals(4, folder.messages)
        assertNull(folder.unseen)
    }

    @Test fun unequalNamespaceColumnsFail() {
        val columns = arrayOf<Any?>(arrayOf<String?>(""), IntArray(1), IntArray(2))
        assertDecodeFails { namespacesOf(columns) }
    }

    @Test fun namespaceKindRebuilds() {
        val columns = arrayOf<Any?>(arrayOf<String?>("Other/"), intArrayOf('/'.code), intArrayOf(1))
        val ns = namespacesOf(columns).single()
        assertEquals("Other/", ns.prefix)
        assertEquals('/', ns.delimiter)
        assertEquals(NamespaceKind.Other, ns.kind)
    }

    @Test fun unequalStatusColumnsFail() {
        assertDecodeFails { statusCountsOf(arrayOf(arrayOf<String?>("INBOX"), intArrayOf(1, 2))) }
    }

    @Test fun statusCountsRebuild() {
        val counts = statusCountsOf(arrayOf(arrayOf<String?>("INBOX", "Sent"), intArrayOf(3, 4)))
        assertEquals(3, counts["INBOX"])
        assertEquals(4, counts["Sent"])
    }

    @Test fun truncatedMimePartFails() {
        assertDecodeFails { mimePartOf(byteArrayOf(0, 1, 'a'.code.toByte())) }
    }

    @Test fun mimePartRebuildsAChild() {
        val bytes = part("1", "multipart", "mixed", children = listOf(part("1.1", "text", "plain", filename = null, size = 5)))
        val tree = mimePartOf(bytes)
        assertEquals("multipart", tree.type)
        assertEquals("mixed", tree.subtype)
        assertEquals(1, tree.children.size)
        assertEquals("1.1", tree.children[0].section)
        assertEquals("plain", tree.children[0].subtype)
        assertNull(tree.children[0].filename)
        assertEquals(5, tree.children[0].size)
    }

    @Test fun unequalMessageRefColumnsFail() {
        val columns = arrayOf<Any?>(arrayOf<String?>("a"), arrayOf<String?>("INBOX"), LongArray(1), LongArray(2))
        assertDecodeFails { messageRefsOf(columns) }
    }

    private fun assertDecodeFails(body: () -> Unit) {
        try {
            body()
        } catch (failure: MailFailure) {
            assertEquals("decode failed", failure.text)
            return
        }
        throw AssertionError("expected MailFailure")
    }

    private fun indexColumns(n: Int): Array<Any?> {
        fun longs() = LongArray(n)
        fun ints() = IntArray(n)
        fun strs() = Array<String?>(n) { "" }
        return arrayOf(longs(), longs(), longs(), ints(), ints(), ints(), ints(), ints(), strs(), strs(), strs(), strs(), strs(), Array<String?>(n) { null }, strs(), strs(), strs())
    }

    private fun folderColumns(n: Int): Array<Any?> {
        fun strs() = Array<String?>(n) { "" }
        return arrayOf(strs(), strs(), Array<String?>(n) { null }, IntArray(n), IntArray(n), IntArray(n) { -1 }, IntArray(n) { -1 })
    }

    private fun note(kind: Int, exists: Int, uid: Long, vararg flags: String): ByteArray {
        val out = ArrayList<Byte>()
        out.add(kind.toByte())
        i32(out, exists)
        i64(out, uid)
        u16(out, flags.size)
        for (flag in flags) {
            val raw = flag.toByteArray(Charsets.UTF_8)
            u16(out, raw.size)
            for (b in raw) out.add(b)
        }
        return ByteArray(out.size) { out[it] }
    }

    private fun part(
        section: String,
        type: String,
        subtype: String,
        filename: String? = null,
        size: Int = 0,
        children: List<ByteArray> = emptyList(),
    ): ByteArray {
        val out = ArrayList<Byte>()
        str(out, section)
        str(out, type)
        str(out, subtype)
        str(out, "")
        if (filename == null) u16(out, 0xFFFF) else str(out, filename)
        i32(out, size)
        str(out, "")
        str(out, "")
        str(out, "")
        out.add(0)
        u16(out, children.size)
        for (child in children) for (b in child) out.add(b)
        return ByteArray(out.size) { out[it] }
    }

    private fun str(out: ArrayList<Byte>, text: String) {
        val raw = text.toByteArray(Charsets.UTF_8)
        u16(out, raw.size)
        for (b in raw) out.add(b)
    }

    private fun u16(out: ArrayList<Byte>, v: Int) {
        out.add((v shr 8).toByte())
        out.add(v.toByte())
    }

    private fun i32(out: ArrayList<Byte>, v: Int) {
        out.add((v shr 24).toByte())
        out.add((v shr 16).toByte())
        out.add((v shr 8).toByte())
        out.add(v.toByte())
    }

    private fun i64(out: ArrayList<Byte>, v: Long) {
        for (shift in 56 downTo 0 step 8) out.add((v shr shift).toByte())
    }
}
