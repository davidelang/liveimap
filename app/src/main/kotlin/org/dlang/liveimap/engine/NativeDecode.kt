package org.dlang.liveimap.engine

import org.dlang.liveimap.session.FolderEntry
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.MessageRef
import org.dlang.liveimap.session.MimePart
import org.dlang.liveimap.session.Namespace
import org.dlang.liveimap.session.NamespaceKind

internal class DecodedWatchNote(
    val kind: Int,
    val exists: Int,
    val uid: Long,
    val flags: Array<String>,
)

/** Big-endian notes, oldest first. One note: kind u8, exists i32, uid i64, flag count u16, then each flag as u16 length plus UTF-8. */
internal fun decodeWatchNotes(bytes: ByteArray): List<DecodedWatchNote> {
    val out = ArrayList<DecodedWatchNote>()
    var i = 0
    fun need(n: Int) {
        if (n < 0 || i > bytes.size - n) throw MailFailure("decode failed")
    }
    fun u8(): Int {
        need(1)
        return bytes[i++].toInt() and 0xFF
    }
    fun u16(): Int {
        need(2)
        val v = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
        i += 2
        return v
    }
    fun i32(): Int {
        need(4)
        val v = ((bytes[i].toInt() and 0xFF) shl 24) or
            ((bytes[i + 1].toInt() and 0xFF) shl 16) or
            ((bytes[i + 2].toInt() and 0xFF) shl 8) or
            (bytes[i + 3].toInt() and 0xFF)
        i += 4
        return v
    }
    fun i64(): Long {
        need(8)
        var v = 0L
        for (b in 0 until 8) v = (v shl 8) or (bytes[i + b].toLong() and 0xFF)
        i += 8
        return v
    }
    while (i < bytes.size) {
        val kind = u8()
        val exists = i32()
        val uid = i64()
        val nflags = u16()
        val flags = Array(nflags) {
            val len = u16()
            need(len)
            val text = String(bytes, i, len, Charsets.UTF_8)
            i += len
            text
        }
        out.add(DecodedWatchNote(kind, exists, uid, flags))
    }
    return out
}

internal fun watchChangeOf(kind: Int, exists: Int, uid: Long, flags: Array<String>): MailboxChange = when (kind) {
    0 -> MailboxChange.Exists(exists)
    1 -> MailboxChange.Expunge(exists)
    2 -> MailboxChange.Flags(uid, flags.toSet())
    5 -> MailboxChange.ShownFlags
    4 -> MailboxChange.WatchLost
    else -> MailboxChange.UidValidityReset
}

private fun longCol(columns: Array<Any?>, index: Int, n: Int? = null): LongArray {
    val v = columns.getOrNull(index) as? LongArray ?: throw MailFailure("decode failed")
    if (n != null && v.size != n) throw MailFailure("decode failed")
    return v
}

private fun intCol(columns: Array<Any?>, index: Int, n: Int): IntArray {
    val v = columns.getOrNull(index) as? IntArray ?: throw MailFailure("decode failed")
    if (v.size != n) throw MailFailure("decode failed")
    return v
}

private fun stringCol(columns: Array<Any?>, index: Int, n: Int? = null): Array<*> {
    val v = columns.getOrNull(index) as? Array<*> ?: throw MailFailure("decode failed")
    if (n != null && v.size != n) throw MailFailure("decode failed")
    return v
}

private fun textAt(col: Array<*>, index: Int): String {
    val v = col[index] ?: throw MailFailure("decode failed")
    if (v !is String) throw MailFailure("decode failed")
    return v
}

/**
 * Columns: 0 uid, 1 uidValidity, 2 epoch (LongArray); 3 sequence, 4 size, 5 toMe, 6 hasAttachment,
 * 7 previewFailed (IntArray); 8 accountId, 9 mailbox, 10 from, 11 subject, 12 envelopeDate,
 * 13 preview (null if absent), 14 recipients, 15 headerNote, 16 flags (NUL-separated) (Array<String?>).
 */
internal fun indexRowsOf(columns: Array<Any?>): List<IndexRow> {
    if (columns.size != 17) throw MailFailure("decode failed")
    val uid = longCol(columns, 0)
    val n = uid.size
    val uidValidity = longCol(columns, 1, n)
    val epoch = longCol(columns, 2, n)
    val sequence = intCol(columns, 3, n)
    val size = intCol(columns, 4, n)
    val toMe = intCol(columns, 5, n)
    val attachment = intCol(columns, 6, n)
    val previewFailed = intCol(columns, 7, n)
    val accountId = stringCol(columns, 8, n)
    val mailbox = stringCol(columns, 9, n)
    val from = stringCol(columns, 10, n)
    val subject = stringCol(columns, 11, n)
    val date = stringCol(columns, 12, n)
    val preview = stringCol(columns, 13, n)
    val recipients = stringCol(columns, 14, n)
    val headerNote = stringCol(columns, 15, n)
    val flags = stringCol(columns, 16, n)
    return List(n) { i ->
        val flagText = textAt(flags, i)
        val flagSet = if (flagText.isEmpty()) emptySet() else flagText.split('\u0000').filter { it.isNotEmpty() }.toSet()
        val previewText = preview[i] as? String
        if (preview[i] != null && previewText == null) throw MailFailure("decode failed")
        IndexRow(
            ref = MessageRef(textAt(accountId, i), textAt(mailbox, i), uidValidity[i], uid[i]),
            sequence = sequence[i],
            flags = flagSet,
            internalDateEpoch = epoch[i],
            size = size[i],
            from = textAt(from, i),
            subject = textAt(subject, i),
            envelopeDate = textAt(date, i),
            preview = previewText,
            toMe = toMe[i] != 0,
            hasAttachment = attachment[i] != 0,
            recipients = textAt(recipients, i),
            headerNote = textAt(headerNote, i),
            previewFailed = previewFailed[i] != 0,
        )
    }
}

/**
 * One MimePart. Each part is big-endian: u16 length + UTF-8 for section, type, subtype, disposition;
 * filename u16 0xFFFF if null, else length + UTF-8; size i32; charset, encoding, format as u16 length + UTF-8;
 * delSp u8; child count u16; then that many children.
 */
internal fun mimePartOf(bytes: ByteArray): MimePart = MimeCursor(bytes).part()

private class MimeCursor(private val bytes: ByteArray) {
    private var i = 0

    fun part(): MimePart {
        val section = str()
        val type = str()
        val subtype = str()
        val disposition = str()
        val filename = strOrNull()
        val size = i32()
        val charset = str()
        val encoding = str()
        val format = str()
        val delSp = u8() != 0
        val n = u16()
        val children = ArrayList<MimePart>(n)
        for (c in 0 until n) children.add(part())
        return MimePart(section, type, subtype, disposition, filename, size, children, charset, encoding, format, delSp)
    }

    private fun need(n: Int) {
        if (n < 0 || i > bytes.size - n) throw MailFailure("decode failed")
    }

    private fun u8(): Int {
        need(1)
        return bytes[i++].toInt() and 0xFF
    }

    private fun u16(): Int {
        need(2)
        val v = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
        i += 2
        return v
    }

    private fun i32(): Int {
        need(4)
        val v = ((bytes[i].toInt() and 0xFF) shl 24) or
            ((bytes[i + 1].toInt() and 0xFF) shl 16) or
            ((bytes[i + 2].toInt() and 0xFF) shl 8) or
            (bytes[i + 3].toInt() and 0xFF)
        i += 4
        return v
    }

    private fun str(): String {
        val n = u16()
        if (n == 0xFFFF) throw MailFailure("decode failed")
        need(n)
        val text = String(bytes, i, n, Charsets.UTF_8)
        i += n
        return text
    }

    private fun strOrNull(): String? {
        val n = u16()
        if (n == 0xFFFF) return null
        need(n)
        val text = String(bytes, i, n, Charsets.UTF_8)
        i += n
        return text
    }
}

/** Columns: 0 mailbox, 1 leaf, 2 specialUse (null if none); 3 hasChildren, 4 delimiter, 5 messages (-1 if absent), 6 unseen (-1 if absent). */
internal fun foldersOf(columns: Array<Any?>): List<FolderEntry> {
    if (columns.size != 7) throw MailFailure("decode failed")
    val mailbox = stringCol(columns, 0, stringCol(columns, 0).size)
    val n = mailbox.size
    val leaf = stringCol(columns, 1, n)
    val use = stringCol(columns, 2, n)
    val children = intCol(columns, 3, n)
    val delim = intCol(columns, 4, n)
    val messages = intCol(columns, 5, n)
    val unseen = intCol(columns, 6, n)
    return List(n) { i ->
        val special = use[i] as? String
        if (use[i] != null && special == null) throw MailFailure("decode failed")
        FolderEntry(
            textAt(mailbox, i),
            textAt(leaf, i),
            children[i] != 0,
            delim[i].toChar(),
            special,
            if (messages[i] < 0) null else messages[i],
            if (unseen[i] < 0) null else unseen[i],
        )
    }
}

/** Columns: 0 prefix, 1 delimiter, 2 kind (0 Personal, 1 Other, 2 Shared). */
internal fun namespacesOf(columns: Array<Any?>): List<Namespace> {
    if (columns.size != 3) throw MailFailure("decode failed")
    val prefix = stringCol(columns, 0, stringCol(columns, 0).size)
    val n = prefix.size
    val delim = intCol(columns, 1, n)
    val kind = intCol(columns, 2, n)
    return List(n) { i ->
        val which = when (kind[i]) {
            0 -> NamespaceKind.Personal
            1 -> NamespaceKind.Other
            2 -> NamespaceKind.Shared
            else -> throw MailFailure("decode failed")
        }
        Namespace(textAt(prefix, i), delim[i].toChar(), which)
    }
}

/** Columns: 0 mailbox, 1 messages. */
internal fun statusCountsOf(columns: Array<Any?>): Map<String, Int> {
    if (columns.size != 2) throw MailFailure("decode failed")
    val names = stringCol(columns, 0, stringCol(columns, 0).size)
    val n = names.size
    val counts = intCol(columns, 1, n)
    val out = LinkedHashMap<String, Int>(n)
    for (i in 0 until n) out[textAt(names, i)] = counts[i]
    return out
}

/** Columns: 0 accountId, 1 mailbox, 2 uidValidity, 3 uid. */
internal fun messageRefsOf(columns: Array<Any?>): List<MessageRef> {
    if (columns.size != 4) throw MailFailure("decode failed")
    val account = stringCol(columns, 0, stringCol(columns, 0).size)
    val n = account.size
    val mailbox = stringCol(columns, 1, n)
    val epoch = longCol(columns, 2, n)
    val uid = longCol(columns, 3, n)
    return List(n) { i -> MessageRef(textAt(account, i), textAt(mailbox, i), epoch[i], uid[i]) }
}
