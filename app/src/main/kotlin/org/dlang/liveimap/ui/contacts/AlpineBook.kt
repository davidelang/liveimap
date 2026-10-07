package org.dlang.liveimap.ui.contacts

import org.dlang.liveimap.session.IndexMode
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.SelectedAddress
import org.dlang.liveimap.ui.compose.addrSpec
import org.dlang.liveimap.ui.compose.splitAddresses

private const val PlaintextMark = "[plaintext]"

data class AlpineEntry(
    val nickname: String,
    val fullname: String,
    val address: String,
    val fcc: String,
    val comments: String,
)

data class AlpineBookLoad(
    val notice: String?,
    val entries: List<AlpineEntry>,
)

suspend fun loadAlpineBook(session: MailSession, mailbox: String): AlpineBookLoad {
    if (mailbox.isEmpty()) {
        return AlpineBookLoad("Address book mailbox is not set", emptyList())
    }
    session.select(mailbox)
    val oldest = session.fetchIndex(
        IndexRequest(
            mailbox = mailbox,
            mode = IndexMode.ArrivalOldest,
            limit = 1,
            prefetch = 0,
        ),
    )
    val newest = session.fetchIndex(
        IndexRequest(
            mailbox = mailbox,
            mode = IndexMode.ArrivalNewest,
            limit = 1,
            prefetch = 0,
        ),
    )
    val first = earliest(oldest)
    val last = latest(newest)
    if (first == null || last == null) {
        return AlpineBookLoad("This mailbox is not an Alpine address book", emptyList())
    }
    val firstText = session.fetchRfc822(first.uid)
    val lastText = if (last.uid == first.uid) firstText else session.fetchRfc822(last.uid)
    if (!hasPineAddrbookHeader(firstText)) {
        return AlpineBookLoad("This mailbox is not an Alpine address book", emptyList())
    }
    val body = rfc822BodyText(lastText)
        ?: return AlpineBookLoad("This mailbox is not an Alpine address book", emptyList())
    return AlpineBookLoad(null, parseAlpineBook(body))
}

fun parseAlpineBook(body: String): List<AlpineEntry> {
    val out = ArrayList<AlpineEntry>()
    for (line in logicalLines(body)) {
        if (line.isEmpty()) continue
        val parts = line.split('\t', limit = 5)
        val rawAddress = parts.getOrElse(2) { "" }
        val rawComments = parts.getOrElse(4) { "" }
        val (address, comments) = keepPlaintextInComment(rawAddress, rawComments)
        out.add(
            AlpineEntry(
                nickname = parts.getOrElse(0) { "" },
                fullname = parts.getOrElse(1) { "" },
                address = address,
                fcc = parts.getOrElse(3) { "" },
                comments = comments,
            ),
        )
    }
    return out
}

fun formatAlpineBook(entries: List<AlpineEntry>): String = buildString {
    for (entry in entries) {
        val addressHadPlaintext = entry.address.contains(PlaintextMark)
        val address = entry.address.replace(PlaintextMark, "").trim()
        var comments = entry.comments
        if (addressHadPlaintext && !comments.contains(PlaintextMark)) {
            comments = if (comments.isEmpty()) PlaintextMark else "$comments $PlaintextMark"
        }
        append(alpineField(entry.nickname))
        append('\t')
        append(alpineField(entry.fullname))
        append('\t')
        append(alpineField(address))
        append('\t')
        append(alpineField(entry.fcc))
        append('\t')
        append(alpineField(comments))
        append('\n')
    }
}

fun revisionsToExpunge(
    uids: List<Long>,
    headerUid: Long,
    history: Int,
    neverTrim: Boolean,
    uidPlus: Boolean,
): List<Long> {
    if (neverTrim || !uidPlus) return emptyList()
    val revisions = ArrayList<Long>()
    for (uid in uids) {
        if (uid != headerUid) revisions.add(uid)
    }
    if (revisions.size <= 1) return emptyList()
    val older = revisions.subList(0, revisions.size - 1)
    val keep = if (history < 0) 0 else history
    if (older.size <= keep) return emptyList()
    return older.subList(0, older.size - keep).toList()
}

private fun alpineField(value: String): String =
    value.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

data class PineBookState(
    val headerUid: Long,
    val lastUid: Long,
    val headerBytes: ByteArray,
    val separator: ByteArray,
    val entries: List<AlpineEntry>,
    val uids: List<Long>,
)

sealed class PineRead {
    data class Ready(val state: PineBookState) : PineRead()
    data class NotBook(val notice: String) : PineRead()
}

sealed class PineWriteResult {
    data class Stale(val state: PineBookState) : PineWriteResult()
    data class Wrote(val expunged: List<Long>) : PineWriteResult()
    data class NotBook(val notice: String) : PineWriteResult()
}

suspend fun readPineBook(session: MailSession, mailbox: String): PineRead {
    if (mailbox.isEmpty()) return PineRead.NotBook("Address book mailbox is not set")
    val selected = session.select(mailbox)
    if (selected.exists <= 0) return PineRead.NotBook("This mailbox is not an Alpine address book")
    val rows = session.fetchIndex(
        IndexRequest(
            mailbox = mailbox,
            mode = IndexMode.ArrivalRange,
            firstSequence = 1,
            lastSequence = selected.exists,
            limit = selected.exists,
            prefetch = 0,
        ),
    )
    if (rows.isEmpty()) return PineRead.NotBook("This mailbox is not an Alpine address book")
    val ordered = rows.sortedBy { it.sequence }
    val header = ordered.first()
    val last = ordered.last()
    val headerMessage = session.fetchRfc822(header.uid)
    if (!hasPineAddrbookHeader(headerMessage)) {
        return PineRead.NotBook("This mailbox is not an Alpine address book")
    }
    val lastMessage = if (last.uid == header.uid) headerMessage else session.fetchRfc822(last.uid)
    val split = headerAndSeparator(lastMessage)
        ?: return PineRead.NotBook("This mailbox is not an Alpine address book")
    if (!looksLikeHeaders(split.header)) {
        return PineRead.NotBook("This mailbox is not an Alpine address book")
    }
    return PineRead.Ready(
        PineBookState(
            headerUid = header.uid,
            lastUid = last.uid,
            headerBytes = split.header,
            separator = split.separator,
            entries = parseAlpineBook(split.body.toString(Charsets.UTF_8)),
            uids = ordered.map { it.uid },
        ),
    )
}

suspend fun writePineBook(
    session: MailSession,
    mailbox: String,
    expectedLastUid: Long,
    entries: List<AlpineEntry>,
    history: Int,
    neverTrim: Boolean,
): PineWriteResult {
    val state = when (val read = readPineBook(session, mailbox)) {
        is PineRead.NotBook -> return PineWriteResult.NotBook(read.notice)
        is PineRead.Ready -> read.state
    }
    if (state.lastUid != expectedLastUid) return PineWriteResult.Stale(state)
    val body = formatAlpineBook(entries).toByteArray(Charsets.UTF_8)
    val message = ByteArray(state.headerBytes.size + state.separator.size + body.size)
    System.arraycopy(state.headerBytes, 0, message, 0, state.headerBytes.size)
    System.arraycopy(state.separator, 0, message, state.headerBytes.size, state.separator.size)
    System.arraycopy(body, 0, message, state.headerBytes.size + state.separator.size, body.size)
    val appended = session.appendReturningUid(mailbox, message, emptySet())
    val uids = when (val after = readPineBook(session, mailbox)) {
        is PineRead.Ready -> {
            val listed = after.state.uids
            if (appended > 0L && appended !in listed) listed + appended else listed
        }
        is PineRead.NotBook -> if (appended > 0L) state.uids + appended else state.uids
    }
    val uidPlus = session.featureCaps.uidPlus
    val expunge = revisionsToExpunge(uids, state.headerUid, history, neverTrim, uidPlus)
    if (expunge.isNotEmpty()) {
        session.storeFlags(expunge, setOf("\\Deleted"), emptySet())
        session.uidExpunge(expunge)
    }
    return PineWriteResult.Wrote(expunge)
}

private data class SplitMessage(val header: ByteArray, val separator: ByteArray, val body: ByteArray)

private fun headerAndSeparator(bytes: ByteArray): SplitMessage? {
    var i = 0
    while (i < bytes.size) {
        if (i + 3 < bytes.size &&
            bytes[i] == '\r'.code.toByte() &&
            bytes[i + 1] == '\n'.code.toByte() &&
            bytes[i + 2] == '\r'.code.toByte() &&
            bytes[i + 3] == '\n'.code.toByte()
        ) {
            return SplitMessage(
                bytes.copyOfRange(0, i),
                byteArrayOf(13, 10, 13, 10),
                bytes.copyOfRange(i + 4, bytes.size),
            )
        }
        if (i + 1 < bytes.size &&
            bytes[i] == '\n'.code.toByte() &&
            bytes[i + 1] == '\n'.code.toByte()
        ) {
            return SplitMessage(
                bytes.copyOfRange(0, i),
                byteArrayOf(10, 10),
                bytes.copyOfRange(i + 2, bytes.size),
            )
        }
        i++
    }
    return null
}

fun pickedAddresses(entry: AlpineEntry): List<SelectedAddress> {
    val address = entry.address.trim()
    if (isDistributionList(address)) {
        val inner = address.substring(1, address.length - 1)
        val out = ArrayList<SelectedAddress>()
        for (piece in splitAddresses(inner)) {
            val email = emailOf(piece)
            if (email.isEmpty()) continue
            out.add(SelectedAddress(entry.fullname, email))
        }
        return out
    }
    val email = emailOf(address)
    if (email.isEmpty()) return emptyList()
    return listOf(SelectedAddress(entry.fullname, email))
}

/** True when [recipient] matches a book entry whose address or comments contain [PlaintextMark]. */
fun addressMarkedPlaintext(entries: List<AlpineEntry>, recipient: String): Boolean {
    val wanted = emailOf(recipient)
    if (wanted.isEmpty()) return false
    for (entry in entries) {
        if (!entry.address.contains(PlaintextMark) && !entry.comments.contains(PlaintextMark)) continue
        for (email in emailsOf(entry)) {
            if (email.equals(wanted, ignoreCase = true)) return true
        }
    }
    return false
}

private fun emailsOf(entry: AlpineEntry): List<String> {
    val picked = ArrayList<String>()
    for (address in pickedAddresses(entry)) {
        val email = emailOf(address.email)
        if (email.isNotEmpty()) picked.add(email)
    }
    if (picked.isNotEmpty()) return picked
    if (isDistributionList(entry.address.trim())) return emptyList()
    val direct = emailOf(entry.address)
    if (direct.isEmpty()) return emptyList()
    return listOf(direct)
}

private fun emailOf(value: String): String =
    addrSpec(value).replace(PlaintextMark, "").trim()

private fun isDistributionList(address: String): Boolean =
    address.length >= 2 && address.startsWith("(") && address.endsWith(")")

private fun keepPlaintextInComment(address: String, comments: String): Pair<String, String> {
    if (!address.contains(PlaintextMark)) return address to comments
    val cleaned = address.replace(PlaintextMark, "").trim()
    if (comments.contains(PlaintextMark)) return cleaned to comments
    val kept = if (comments.isEmpty()) PlaintextMark else "$comments $PlaintextMark"
    return cleaned to kept
}

private fun logicalLines(body: String): List<String> {
    val normalized = body.replace("\r\n", "\n").replace('\r', '\n')
    val lines = ArrayList<String>()
    val current = StringBuilder()
    var open = false
    for (line in normalized.split('\n')) {
        if (line.isEmpty()) {
            if (open) {
                lines.add(current.toString())
                current.clear()
                open = false
            }
            continue
        }
        if (open && line[0] == ' ') {
            current.append(line)
            continue
        }
        if (open) {
            lines.add(current.toString())
            current.clear()
        }
        current.append(line)
        open = true
    }
    if (open) lines.add(current.toString())
    return lines
}

private fun hasPineAddrbookHeader(rfc822: ByteArray): Boolean {
    val split = splitRfc822(rfc822) ?: return false
    val header = split.first.toString(Charsets.ISO_8859_1)
    return Regex("(?im)^[ \\t]*x-pine-addrbook\\s*:").containsMatchIn(header)
}

private fun rfc822BodyText(rfc822: ByteArray): String? {
    val split = splitRfc822(rfc822) ?: return null
    if (!looksLikeHeaders(split.first)) return null
    return split.second.toString(Charsets.UTF_8)
}

private fun looksLikeHeaders(header: ByteArray): Boolean {
    if (header.isEmpty()) return false
    val text = header.toString(Charsets.ISO_8859_1).replace("\r\n", "\n").replace('\r', '\n')
    var sawHeader = false
    for (line in text.split('\n')) {
        if (line.isEmpty()) continue
        if (line[0] == ' ' || line[0] == '\t') continue
        val colon = line.indexOf(':')
        if (colon <= 0) return false
        if (line.substring(0, colon).indexOf('\t') >= 0) return false
        sawHeader = true
    }
    return sawHeader
}

private fun splitRfc822(bytes: ByteArray): Pair<ByteArray, ByteArray>? {
    var i = 0
    while (i < bytes.size) {
        if (i + 3 < bytes.size &&
            bytes[i] == '\r'.code.toByte() &&
            bytes[i + 1] == '\n'.code.toByte() &&
            bytes[i + 2] == '\r'.code.toByte() &&
            bytes[i + 3] == '\n'.code.toByte()
        ) {
            return bytes.copyOfRange(0, i) to bytes.copyOfRange(i + 4, bytes.size)
        }
        if (i + 1 < bytes.size &&
            bytes[i] == '\n'.code.toByte() &&
            bytes[i + 1] == '\n'.code.toByte()
        ) {
            return bytes.copyOfRange(0, i) to bytes.copyOfRange(i + 2, bytes.size)
        }
        i++
    }
    return null
}

private fun earliest(rows: List<IndexRow>): IndexRow? {
    var best: IndexRow? = null
    for (row in rows) {
        val current = best
        if (current == null || row.sequence < current.sequence) best = row
    }
    return best
}

private fun latest(rows: List<IndexRow>): IndexRow? {
    var best: IndexRow? = null
    for (row in rows) {
        val current = best
        if (current == null || row.sequence > current.sequence) best = row
    }
    return best
}
