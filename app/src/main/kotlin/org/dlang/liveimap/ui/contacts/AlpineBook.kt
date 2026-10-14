package org.dlang.liveimap.ui.contacts

import org.dlang.liveimap.session.FolderAt
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
    return session.inFolder(FolderAt(mailbox)) { _, s ->
        val oldest = s.fetchIndex(
            IndexRequest(
                mailbox = mailbox,
                mode = IndexMode.ArrivalOldest,
                limit = 1,
                prefetch = 0,
            ),
        )
        val newest = s.fetchIndex(
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
            return@inFolder AlpineBookLoad("This mailbox is not an Alpine address book", emptyList())
        }
        val firstText = s.fetchRfc822(first.uid)
        val lastText = if (last.uid == first.uid) firstText else s.fetchRfc822(last.uid)
        if (!hasPineAddrbookHeader(firstText)) {
            return@inFolder AlpineBookLoad("This mailbox is not an Alpine address book", emptyList())
        }
        if (!isBookRevision(lastText, headerSubject(firstText))) {
            // F85: the newest message is not a revision; take the newest one that is.
            return@inFolder when (val read = readPineBook(s, mailbox)) {
                is PineRead.NotBook -> AlpineBookLoad(read.notice, emptyList())
                is PineRead.Ready -> AlpineBookLoad(null, read.state.entries)
            }
        }
        val body = rfc822BodyText(lastText)
            ?: return@inFolder AlpineBookLoad("This mailbox is not an Alpine address book", emptyList())
        return@inFolder AlpineBookLoad(null, parseAlpineBook(body))
    }
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

fun bookHasAddress(entries: List<AlpineEntry>, email: String): Boolean {
    for (entry in entries) {
        if (entry.address.equals(email, ignoreCase = true)) return true
        if (addrSpec(entry.address).equals(email, ignoreCase = true)) return true
    }
    return false
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
    val uidValidity: Long = 0L,
    val bookSubject: String = "",
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
    // Fresh view: SELECT of the mailbox already selected keeps the count from before our own APPEND (seen on
    // lab dovecot-245), so the new revision was invisible; inFolder(fresh) re-reads it in the same call.
    return session.inFolder(FolderAt(mailbox), fresh = true) { selected, s ->
        if (selected.exists <= 0) return@inFolder PineRead.NotBook("This mailbox is not an Alpine address book")
        val rows = s.fetchIndex(
            IndexRequest(
                mailbox = mailbox,
                mode = IndexMode.ArrivalRange,
                firstSequence = 1,
                lastSequence = selected.exists,
                limit = selected.exists,
                prefetch = 0,
            ),
        )
        if (rows.isEmpty()) return@inFolder PineRead.NotBook("This mailbox is not an Alpine address book")
        val ordered = rows.sortedBy { it.sequence }
        val header = ordered.first()
        val headerMessage = s.fetchRfc822(header.uid)
        if (!hasPineAddrbookHeader(headerMessage)) {
            return@inFolder PineRead.NotBook("This mailbox is not an Alpine address book")
        }
        val bookSubject = headerSubject(headerMessage)
        // F85: the newest message that is a real revision (scan back a little past stray messages).
        var last = header
        var lastMessage = headerMessage
        for (row in ordered.asReversed().take(RevisionScan)) {
            if (row.uid == header.uid) break
            val bytes = s.fetchRfc822(row.uid)
            if (isBookRevision(bytes, bookSubject)) {
                last = row
                lastMessage = bytes
                break
            }
        }
        val split = headerAndSeparator(lastMessage)
            ?: return@inFolder PineRead.NotBook("This mailbox is not an Alpine address book")
        if (!looksLikeHeaders(split.header)) {
            return@inFolder PineRead.NotBook("This mailbox is not an Alpine address book")
        }
        return@inFolder PineRead.Ready(
            PineBookState(
                headerUid = header.uid,
                lastUid = last.uid,
                headerBytes = split.header,
                separator = split.separator,
                entries = parseAlpineBook(split.body.toString(Charsets.UTF_8)),
                uids = ordered.map { it.uid },
                uidValidity = selected.uidValidity,
                bookSubject = bookSubject,
            ),
        )
    }
}

suspend fun writePineBook(
    session: MailSession,
    mailbox: String,
    expectedLastUid: Long,
    entries: List<AlpineEntry>,
    history: Int,
    neverTrim: Boolean,
): PineWriteResult {
    forgetCompletionCaches() // F62: the next completion reads the new revision
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
    // IMAP APPEND needs CRLF lines; Cyrus refuses bare newlines ("Message contains bare newlines").
    val appended = session.appendReturningUid(mailbox, crlfLines(message), emptySet())
    // F127: trimming deletes by UID, so only within the epoch the UIDs were listed in, after reselecting it
    val after = readPineBook(session, mailbox)
    if (after !is PineRead.Ready || state.uidValidity == 0L || after.state.uidValidity != state.uidValidity) {
        return PineWriteResult.Wrote(emptyList())
    }
    val listed = after.state.uids
    val uids = if (appended > 0L && appended !in listed) listed + appended else listed
    val uidPlus = session.featureCaps.uidPlus
    val candidates = revisionsToExpunge(uids, state.headerUid, history, neverTrim, uidPlus)
    // F85: only messages that are themselves book revisions are ever trimmed.
    val expunge = session.inFolder(FolderAt(mailbox, state.uidValidity)) { _, s ->
        val revisions = candidates.filter { uid -> isBookRevision(s.fetchRfc822(uid), after.state.bookSubject) }
        if (revisions.isNotEmpty()) {
            s.storeFlags(revisions, setOf("\\Deleted"), emptySet())
            s.uidExpunge(revisions)
        }
        revisions
    }
    return PineWriteResult.Wrote(expunge)
}

/** Every bare LF becomes CRLF; existing CRLF pairs are kept as they are. */
internal fun crlfLines(bytes: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream(bytes.size + bytes.size / 16 + 2)
    for (i in bytes.indices) {
        val b = bytes[i]
        if (b == '\n'.code.toByte() && (i == 0 || bytes[i - 1] != '\r'.code.toByte())) out.write(13)
        out.write(b.toInt())
    }
    return out.toByteArray()
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

/**
 * The recipients a picked or completed entry stands for. F189: list members that are nicknames are expanded
 * against [book] (recursively, like CopyMap.expandPieces); an unknown nickname is dropped, never sent as a bare
 * local name. Each member keeps its own name ("Bo" <bo@x>, or the nickname entry's full name), not the list's.
 */
fun pickedAddresses(entry: AlpineEntry, book: List<AlpineEntry> = emptyList()): List<SelectedAddress> {
    val address = entry.address.trim()
    if (isDistributionList(address)) {
        val out = ArrayList<SelectedAddress>()
        expandListMembers(address, book, hashSetOf(entry.nickname.lowercase()), out)
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

private fun expandListMembers(list: String, book: List<AlpineEntry>, seen: MutableSet<String>, out: MutableList<SelectedAddress>) {
    for (piece in splitAddresses(list.substring(1, list.length - 1))) {
        val email = emailOf(piece)
        if (email.isEmpty()) continue
        if ('@' in email) {
            if (out.none { it.email.equals(email, ignoreCase = true) }) out.add(SelectedAddress(memberName(piece), email))
            continue
        }
        if (!seen.add(email.lowercase())) continue
        val target = book.firstOrNull { it.nickname.equals(email, ignoreCase = true) } ?: continue
        val inner = target.address.trim()
        if (isDistributionList(inner)) {
            expandListMembers(inner, book, seen, out)
        } else {
            val spec = emailOf(inner)
            if ('@' in spec && out.none { it.email.equals(spec, ignoreCase = true) }) {
                out.add(SelectedAddress(memberName(inner).ifEmpty { target.fullname }, spec))
            }
        }
    }
}

/** The display name written in a member ("Bo" <bo@x> gives Bo); empty for a bare address. */
private fun memberName(piece: String): String {
    val lt = piece.indexOf('<')
    if (lt <= 0) return ""
    return piece.substring(0, lt).trim().removeSurrounding("\"").trim()
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

private fun headerSubject(rfc822: ByteArray): String {
    val split = splitRfc822(rfc822) ?: return ""
    val header = split.first.toString(Charsets.ISO_8859_1)
    return Regex("(?im)^subject[ \\t]*:[ \\t]*(.*)$").find(header)?.groupValues?.get(1)?.trim().orEmpty()
}

/**
 * F85: a message counts as a book revision only when it carries the Pine address-book header, or the same
 * (non-empty) Subject as the book's header message. Anything else that lands in the folder is not the book
 * and is never expunged by a trim.
 */
internal fun isBookRevision(rfc822: ByteArray, bookSubject: String): Boolean {
    if (hasPineAddrbookHeader(rfc822)) return true
    if (bookSubject.isEmpty()) return false
    return headerSubject(rfc822) == bookSubject
}

private const val RevisionScan = 12

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
