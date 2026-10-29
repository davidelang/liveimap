package org.dlang.liveimap.ui.compose

import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MaxMimeDepth
import org.dlang.liveimap.session.MimePart
import org.dlang.liveimap.ui.reader.MimeDecode
import org.dlang.liveimap.ui.reader.decodePart
import org.dlang.liveimap.ui.reader.htmlAsText
import java.io.ByteArrayOutputStream
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID

/** [value] has encoded words decoded (display); [raw] is the unfolded text as sent (structure, F24). */
class HeaderField(val name: String, val value: String, val raw: String = value)

class ParsedRfc822(
    val headers: List<HeaderField>,
    val body: ByteArray,
)

class ReplyDraft(
    val to: List<String>,
    val cc: List<String>,
    val subject: String,
    val inReplyTo: String,
    val references: String,
    val body: String,
)

class OutgoingPart(
    val filename: String,
    val mediaType: String,
    val bytes: ByteArray,
    val wireBase64: Boolean,
    /** Server transfer encoding of a forwarded part. Null keeps the older base64 choice. */
    val transferEncoding: String? = null,
)

class PlainMessage(
    val fromName: String,
    val fromEmail: String,
    val to: List<String>,
    val cc: List<String>,
    val bcc: List<String>,
    val subject: String,
    val body: String,
    val messageId: String,
    val date: String,
    val inReplyTo: String = "",
    val references: String = "",
    val attachments: List<OutgoingPart> = emptyList(),
    val wrapColumn: Int = 74,
    val flowed: Boolean = true,
    /** Reply-To and user-chosen composer headers, in order. */
    val extraHeaders: List<Pair<String, String>> = emptyList(),
    /** When true, [body] is text/html and the plain part is [htmlAsText] of it. */
    val html: Boolean = false,
)

class BuiltMail(
    val recipients: List<String>,
)

class EditorLoad(
    val to: String,
    val cc: String,
    val subject: String,
    val body: String,
    val inReplyTo: String,
    val references: String,
    val attachments: List<OutgoingPart>,
    /** True when [body] is the text/html part of a multipart/alternative. */
    val html: Boolean = false,
)

fun missingPartText(html: Boolean): String =
    if (html) "No text/html part" else "No text/plain part"

fun textPart(root: MimePart, subtype: String, depth: Int = 0): MimePart? {
    if (depth >= MaxMimeDepth) return null
    if (root.type.equals("multipart", ignoreCase = true) ||
        (root.type.equals("message", ignoreCase = true) && root.children.isNotEmpty())
    ) {
        for (child in root.children) {
            val found = textPart(child, subtype, depth + 1)
            if (found != null) return found
        }
        return null
    }
    if (root.type.equals("text", ignoreCase = true) && root.subtype.equals(subtype, ignoreCase = true)) {
        return root
    }
    for (child in root.children) {
        val found = textPart(child, subtype, depth + 1)
        if (found != null) return found
    }
    return null
}

/** Every leaf of an opened "nested part" subtree, text parts included, in order (iterative). */
fun nestedLeafParts(root: MimePart): List<MimePart> {
    val out = ArrayList<MimePart>()
    val stack = ArrayDeque<Pair<MimePart, Int>>()
    stack.addLast(root to 0)
    while (stack.isNotEmpty()) {
        val (part, depth) = stack.removeLast()
        val container = part.type.equals("multipart", ignoreCase = true) ||
            (part.type.equals("message", ignoreCase = true) && part.children.isNotEmpty())
        if (!container || depth >= MaxMimeDepth) {
            out.add(part)
            continue
        }
        for (i in part.children.indices.reversed()) stack.addLast(part.children[i] to depth + 1)
    }
    return out
}

fun attachmentParts(root: MimePart): List<MimePart> {
    val out = ArrayList<MimePart>()
    fun walk(part: MimePart, depth: Int) {
        if (depth >= MaxMimeDepth) {
            out.add(part)
            return
        }
        if (part.type.equals("multipart", ignoreCase = true)) {
            part.children.forEach { walk(it, depth + 1) }
            return
        }
        if (part.type.equals("message", ignoreCase = true) && part.children.isNotEmpty()) {
            part.children.forEach { walk(it, depth + 1) }
            return
        }
        val attachment = part.disposition.equals("attachment", ignoreCase = true)
        val textBody = part.type.equals("text", ignoreCase = true) &&
            (part.subtype.equals("plain", ignoreCase = true) || part.subtype.equals("html", ignoreCase = true)) &&
            !attachment
        if (!textBody) out.add(part)
    }
    walk(root, 0)
    return out
}

/** Base64 wire fetches stay on a 4-octet boundary until the final partial. */
fun nextWireCount(offset: Int, size: Int, step: Int, base64: Boolean): Int {
    if (offset < 0 || step <= 0 || offset >= size) return 0
    var length = step.coerceAtMost(size - offset)
    if (base64 && offset + length < size && length % 4 != 0) {
        length -= length % 4
        if (length <= 0) length = 4.coerceAtMost(size - offset)
    }
    return length
}

suspend fun peekWireBytes(
    size: Int,
    step: Int,
    base64: Boolean,
    read: suspend (offset: Int, length: Int) -> ByteArray,
): ByteArray {
    if (size <= 0) return read(0, step)
    val out = ByteArrayOutputStream()
    var offset = 0
    while (offset < size) {
        val length = nextWireCount(offset, size, step, base64)
        if (length <= 0) break
        val chunk = read(offset, length)
        if (chunk.isEmpty()) {
            if (offset < size) throw MailFailure("part ended early")
            break
        }
        out.write(chunk)
        offset += chunk.size
    }
    return out.toByteArray()
}

fun parseRfc822(bytes: ByteArray, fallback: String): ParsedRfc822 {
    val (headerBytes, body) = splitHeaderBody(bytes)
    val rows = MimeDecode.headers(headerBytes, fallback)
    val headers = ArrayList<HeaderField>(rows.size / 3)
    var i = 0
    while (i + 2 < rows.size) {
        val name = rows[i]
        if (name.isNotEmpty()) headers.add(HeaderField(name, rows[i + 1], rows[i + 2]))
        i += 3
    }
    return ParsedRfc822(headers, body)
}

fun headerValues(message: ParsedRfc822, name: String): List<String> =
    message.headers.filter { it.name.equals(name, ignoreCase = true) }.map { it.value }

/**
 * Address headers are split on the text as sent; encoded words are then decoded only in the display name,
 * never in the addr-spec, so a decoded CR/LF, ',' or '<' cannot add or change a recipient (F24, RFC 2047 §5).
 */
fun addresses(message: ParsedRfc822, name: String, fallback: String): List<String> =
    message.headers.filter { it.name.equals(name, ignoreCase = true) }
        .flatMap { splitAddresses(it.raw) }
        .map { decodeAddressDisplay(it, fallback) }

fun decodeAddressDisplay(address: String, fallback: String): String {
    val lt = address.lastIndexOf('<')
    if (lt <= 0) return address
    val phrase = address.substring(0, lt).trim()
    val eightBit = phrase.any { it.code > 127 }
    if (phrase.isEmpty() || (!phrase.contains("=?") && !eightBit)) return address
    val decodedRaw = if (phrase.contains("=?")) {
        decodeHeaderWords(phrase, fallback)
    } else {
        val bytes = ByteArray(phrase.length) { phrase[it].code.and(0xFF).toByte() }
        decodePart(bytes, fallback, "", fallback).text
    }
    val decoded = decodedRaw
        .map { if (it < ' ' || it == '\u007f') ' ' else it }
        .joinToString("")
        .trim()
    val safe = if (decoded.any { it in "\"<>,;:@()[]\\" }) {
        "\"" + decoded.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    } else {
        decoded
    }
    return safe + " " + address.substring(lt)
}

fun splitAddresses(value: String): List<String> {
    val out = ArrayList<String>()
    val current = StringBuilder()
    var quoted = false
    var angle = 0
    var escaped = false
    for (ch in value) {
        if (escaped) {
            current.append(ch)
            escaped = false
            continue
        }
        if (ch == '\\' && quoted) {
            current.append(ch)
            escaped = true
            continue
        }
        if (ch == '"') {
            quoted = !quoted
            current.append(ch)
            continue
        }
        if (!quoted && ch == '<') angle++
        if (!quoted && ch == '>' && angle > 0) angle--
        if (!quoted && angle == 0 && ch == ',') {
            val piece = current.toString().trim()
            if (piece.isNotEmpty()) out.add(piece)
            current.clear()
            continue
        }
        current.append(ch)
    }
    val piece = current.toString().trim()
    if (piece.isNotEmpty()) out.add(piece)
    return out
}

fun addrSpec(value: String): String {
    val open = value.lastIndexOf('<')
    val close = value.lastIndexOf('>')
    val raw = if (open >= 0 && close > open) value.substring(open + 1, close) else value
    return raw.trim().trim('<', '>')
}

fun markedSubject(subject: String, marker: String): String {
    val trimmed = subject.trim()
    val token = marker.trim()
    if (trimmed.startsWith(token, ignoreCase = true)) return trimmed
    if (trimmed.isEmpty()) return token
    return "$token $trimmed"
}

fun replyRecipients(
    replyAll: Boolean,
    replyTo: List<String>,
    from: List<String>,
    to: List<String>,
    cc: List<String>,
    accountEmail: String,
    altAddresses: List<String> = emptyList(),
    useReplyTo: Boolean = true,
): Pair<List<String>, List<String>> {
    val primary = if (useReplyTo && replyTo.isNotEmpty()) replyTo else from
    if (!replyAll) return primary to emptyList()
    val drop = HashSet<String>()
    val self = accountEmail.trim().lowercase()
    if (self.isNotEmpty()) drop.add(self)
    for (alt in altAddresses) {
        val spec = addrSpec(alt).trim().lowercase()
        if (spec.isNotEmpty()) drop.add(spec)
    }
    val seen = LinkedHashSet<String>()
    val toOut = ArrayList<String>()
    val ccOut = ArrayList<String>()
    fun accept(raw: String, into: MutableList<String>) {
        val spec = addrSpec(raw).trim()
        if (spec.isEmpty()) return
        val key = spec.lowercase()
        if (key in drop) return
        if (!seen.add(key)) return
        into.add(raw.trim())
    }
    for (raw in primary) accept(raw, toOut)
    for (raw in to) accept(raw, toOut)
    for (raw in cc) accept(raw, ccOut)
    return toOut to ccOut
}

fun replyDraft(
    replyAll: Boolean,
    message: ParsedRfc822,
    accountEmail: String,
    peekedText: String,
    charset: String,
    quotePrefix: String = DefaultQuotePrefix,
    leadIn: String = DefaultLeadIn,
): ReplyDraft {
    val (to, cc) = replyRecipients(
        replyAll = replyAll,
        replyTo = addresses(message, "Reply-To", charset),
        from = addresses(message, "From", charset),
        to = addresses(message, "To", charset),
        cc = addresses(message, "Cc", charset),
        accountEmail = accountEmail,
    )
    val messageId = headerValues(message, "Message-ID").firstOrNull().orEmpty().trim()
    val references = appendMessageId(
        headerValues(message, "References").joinToString(" ").trim(),
        messageId,
    )
    val subject = markedSubject(headerValues(message, "Subject").firstOrNull().orEmpty(), "Re:")
    val body = quotePart(
        attributionLine(
            headerValues(message, "Date").firstOrNull().orEmpty(),
            headerValues(message, "From").firstOrNull().orEmpty(),
            leadIn,
            headerValues(message, "Subject").firstOrNull().orEmpty(),
        ),
        peekedText,
        quotePrefix,
    )
    return ReplyDraft(to, cc, subject, messageId, references, body)
}

fun forwardDraft(
    message: ParsedRfc822,
    peekedText: String,
    quotePrefix: String = DefaultQuotePrefix,
    leadIn: String = DefaultLeadIn,
): ReplyDraft {
    val subject = markedSubject(headerValues(message, "Subject").firstOrNull().orEmpty(), "Fwd:")
    val body = quotePart(
        attributionLine(
            headerValues(message, "Date").firstOrNull().orEmpty(),
            headerValues(message, "From").firstOrNull().orEmpty(),
            leadIn,
            headerValues(message, "Subject").firstOrNull().orEmpty(),
        ),
        peekedText,
        quotePrefix,
    )
    return ReplyDraft(emptyList(), emptyList(), subject, "", "", body)
}

/** Headers the composer builds itself; a custom header list can't override them. */
private val reservedHeaders = setOf(
    "date", "from", "to", "cc", "bcc", "subject", "message-id", "in-reply-to", "references",
    "mime-version", "content-type", "content-transfer-encoding",
)

/** Lead-in tokens: {date}, {from} (as written), {name} (display name or address), {subject}. */
fun attributionLine(date: String, from: String, leadIn: String = DefaultLeadIn, subject: String = ""): String {
    val name = from.substringBefore('<').trim().trim('"').ifEmpty { addrSpec(from) }
    return leadIn.replace("{date}", date.trim()).replace("{from}", from.trim())
        .replace("{name}", name).replace("{subject}", subject.trim())
}

fun quotePart(attribution: String, partText: String, prefix: String = DefaultQuotePrefix): String {
    val normalized = partText.replace("\r\n", "\n").replace('\r', '\n')
    val quoted = normalized.split('\n').joinToString("\n") { line -> "$prefix$line" }
    if (attribution.isEmpty()) return quoted
    return attribution + "\n" + quoted
}

private val referenceSplit = Regex("\\s+")

fun appendMessageId(references: String, messageId: String): String {
    val id = messageId.trim()
    val parent = references.trim()
    if (id.isEmpty()) return parent
    if (parent.isEmpty()) return id
    val tokens = parent.split(referenceSplit)
    if (tokens.any { it.equals(id, ignoreCase = true) }) return parent
    return "$parent $id"
}

fun formatMailbox(name: String, email: String): String {
    val box = cleanHeader(email.trim())
    val display = cleanHeader(name.trim())
    if (display.isEmpty()) return box
    if (display.any { it.code > 126 }) return "${encodedWord(display, fold = false)} <$box>"
    val rendered = if (isAtom(display)) {
        display
    } else {
        "\"${display.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }
    return "$rendered <$box>"
}

fun newMessageId(email: String): String {
    val domain = email.substringAfter('@', missingDelimiterValue = "local").trim().ifEmpty { "local" }
    val clean = domain.filter { it != '<' && it != '>' && it != ' ' && it != '\r' && it != '\n' }
    val host = clean.ifEmpty { "local" }
    return "<${UUID.randomUUID()}@$host>"
}

fun rfc822Date(): String {
    val format = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
    return ZonedDateTime.now().format(format)
}

fun looksLikeBase64(bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) return false
    var count = 0
    for (byte in bytes) {
        val c = byte.toInt() and 0xFF
        if (c == '\r'.code || c == '\n'.code || c == ' '.code || c == '\t'.code) continue
        val ok = c in 'A'.code..'Z'.code ||
            c in 'a'.code..'z'.code ||
            c in '0'.code..'9'.code ||
            c == '+'.code ||
            c == '/'.code ||
            c == '='.code
        if (!ok) return false
        count++
    }
    return count > 0 && count % 4 == 0
}

/** Break plain text on spaces at the column. Column 0 leaves the body unchanged. */
internal fun wrapPlain(body: String, column: Int): String {
    if (column <= 0) return body
    val normalized = body.replace("\r\n", "\n").replace('\r', '\n')
    return normalized.split('\n').joinToString("\n") { wrapPlainLine(it, column) }
}

private val wrapSpaces = Regex("[ \t]+")

private fun wrapPlainLine(line: String, column: Int): String {
    if (line.codePointCount(0, line.length) <= column) return line
    val prefix = quotePrefix(line)
    val prefixCount = prefix.codePointCount(0, prefix.length)
    if (prefixCount >= column) return line
    val words = line.substring(prefix.length).split(wrapSpaces).filter { it.isNotEmpty() }
    if (words.isEmpty()) return line
    val lines = ArrayList<String>()
    val current = StringBuilder(prefix)
    var count = prefixCount
    var hasWord = false
    for (word in words) {
        val wordCount = word.codePointCount(0, word.length)
        if (hasWord && count + 1 + wordCount > column) {
            lines.add(current.toString())
            current.setLength(0)
            current.append(prefix)
            count = prefixCount
            hasWord = false
        }
        if (!hasWord && prefixCount + wordCount > column) {
            lines.add(prefix + word)
            continue
        }
        if (hasWord) {
            current.append(' ')
            count += 1
        }
        current.append(word)
        count += wordCount
        hasWord = true
    }
    if (hasWord) lines.add(current.toString())
    return lines.joinToString("\n")
}

private fun quotePrefix(line: String): String {
    var end = 0
    while (end < line.length && line[end] == '>') end++
    if (end < line.length && line[end] == ' ') end++
    return line.substring(0, end)
}

/** Soft-break plain text for format=flowed. Column 0 leaves the body unchanged. */
internal fun flowPlain(body: String, column: Int): String {
    if (column <= 0) return body
    val normalized = body.replace("\r\n", "\n").replace('\r', '\n')
    return normalized.split('\n').joinToString("\n") { flowPlainLine(it, column) }
}

private fun flowPlainLine(line: String, column: Int): String {
    if (line == "--" || line == "-- ") return "-- "
    val trimmed = line.trimEnd(' ')
    val stuffed = flowStuff(trimmed)
    if (stuffed.codePointCount(0, stuffed.length) <= column) return stuffed
    val prefix = quotePrefix(trimmed)
    val tokens = flowTokens(trimmed.substring(prefix.length))
    if (tokens.isEmpty()) return prefix
    val pieces = ArrayList<String>()
    var index = 0
    while (index < tokens.size) {
        val piece = chooseFlowPiece(prefix, tokens, index, column)
        if (piece.count <= 0) break
        pieces.add(piece.text)
        index += piece.count
    }
    return pieces.mapIndexed { at, text ->
        val lead = if (needsFlowStuff(text)) " " else ""
        val soft = if (at < pieces.lastIndex) "  " else ""
        lead + text + soft
    }.joinToString("\n")
}

private class FlowTok(val sep: String, val word: String)

private class FlowPiece(val count: Int, val text: String)

private fun flowStuff(text: String): String {
    if (text.isEmpty()) return text
    if (text[0] == '>' || text.startsWith("From ")) return " $text"
    return text
}

private fun flowTokens(rest: String): List<FlowTok> {
    val out = ArrayList<FlowTok>()
    var i = 0
    while (i < rest.length) {
        val sepStart = i
        while (i < rest.length && (rest[i] == ' ' || rest[i] == '\t')) i++
        val sep = rest.substring(sepStart, i)
        if (i >= rest.length) break
        val wordStart = i
        while (i < rest.length && rest[i] != ' ' && rest[i] != '\t') i++
        out.add(FlowTok(sep, rest.substring(wordStart, i)))
    }
    return out
}

private fun chooseFlowPiece(prefix: String, tokens: List<FlowTok>, start: Int, column: Int): FlowPiece {
    val wide = (column - 2).coerceAtLeast(1)
    val wideCount = takeFlowTokens(prefix, tokens, start, wide)
    val wideText = joinFlowTokens(prefix, tokens, start, wideCount)
    if (!needsFlowStuff(wideText)) return FlowPiece(wideCount, wideText)
    val narrow = (column - 3).coerceAtLeast(1)
    val narrowCount = takeFlowTokens(prefix, tokens, start, narrow)
    return FlowPiece(narrowCount, joinFlowTokens(prefix, tokens, start, narrowCount))
}

private fun needsFlowStuff(text: String): Boolean {
    if (text.isEmpty()) return false
    if (text[0] == '>') return true
    return text.startsWith("From ")
}

private fun takeFlowTokens(prefix: String, tokens: List<FlowTok>, start: Int, width: Int): Int {
    val remaining = tokens.size - start
    if (remaining <= 0) return 0
    val prefixCount = prefix.codePointCount(0, prefix.length)
    if (prefixCount >= width) return remaining
    var count = prefixCount
    var taken = 0
    var index = start
    while (index < tokens.size) {
        val sepCount = if (taken == 0 && start > 0) 0 else tokens[index].sep.codePointCount(0, tokens[index].sep.length)
        val extra = sepCount + tokens[index].word.codePointCount(0, tokens[index].word.length)
        if (taken > 0 && count + extra > width) break
        if (taken == 0 && prefixCount + extra > width) return 1
        count += extra
        taken++
        index++
    }
    return taken
}

private fun joinFlowTokens(prefix: String, tokens: List<FlowTok>, start: Int, count: Int): String {
    val out = StringBuilder(prefix)
    for (index in start until start + count) {
        if (index != start || start == 0) out.append(tokens[index].sep)
        out.append(tokens[index].word)
    }
    return out.toString()
}

internal external fun writeOutgoingMessage(
    path: String,
    headerNames: Array<String>,
    headerValues: Array<String>,
    body: String,
    htmlBody: String,
    flowed: Boolean,
    eightBit: Boolean,
    partPaths: Array<String>,
    partTypes: Array<String>,
    partDispositions: Array<String>,
    partNames: Array<String>,
    partEncodings: Array<String>,
): String

/** Headers the writer emits, from the same fields assemble already collected. Bcc is not one of them. */
internal fun outgoingHeaders(message: PlainMessage): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    out.add("Date" to cleanHeader(message.date))
    out.add("From" to cleanHeader(formatMailbox(message.fromName, message.fromEmail)))
    fun boxes(name: String, values: List<String>) {
        val rendered = values.map { cleanHeader(it.trim()) }.filter { it.isNotEmpty() }
        if (rendered.isNotEmpty()) out.add(name to rendered.joinToString(", "))
    }
    boxes("To", message.to)
    boxes("Cc", message.cc)
    out.add("Subject" to headerText(message.subject))
    out.add("Message-ID" to cleanHeader(message.messageId))
    if (message.inReplyTo.isNotBlank()) out.add("In-Reply-To" to cleanHeader(message.inReplyTo.trim()))
    if (message.references.isNotBlank()) out.add("References" to cleanHeader(message.references.trim()))
    for ((name, value) in message.extraHeaders) {
        val clean = name.filter { it.isLetterOrDigit() || it == '-' }
        if (clean.isEmpty() || value.isBlank() || clean.lowercase() in reservedHeaders) continue
        out.add(clean to headerText(value.trim()))
    }
    return out
}

/** Plain text the writer encodes, and the HTML source when [PlainMessage.html] is set. */
internal fun outgoingBodies(message: PlainMessage): Pair<String, String> {
    if (message.html) return htmlAsText(message.body) to message.body
    val flowed = message.flowed && message.wrapColumn > 0
    val plain = if (flowed) flowPlain(message.body, message.wrapColumn) else wrapPlain(message.body, message.wrapColumn)
    return plain to ""
}

/** Empty means the file is raw and the writer base64-encodes it. "auto" is an unencoded forward. */
internal fun outgoingPartEncoding(encoding: String?, wireBase64: Boolean): String {
    if (encoding != null) {
        val token = encoding.substringBefore(';').trim().lowercase()
        return token.ifEmpty { "auto" }
    }
    if (wireBase64) return "base64"
    return "raw"
}

/** Resent-* lines prepended to a bounced message. The original headers, including Message-ID, stay unchanged. */
internal fun bouncePrefix(
    resentFrom: String,
    resentTo: String,
    resentDate: String,
    resentMessageId: String,
): ByteArray = buildString {
    append("Resent-From: ")
    append(cleanHeader(resentFrom))
    append("\r\n")
    append("Resent-To: ")
    append(cleanHeader(resentTo))
    append("\r\n")
    append("Resent-Date: ")
    append(cleanHeader(resentDate))
    append("\r\n")
    append("Resent-Message-ID: ")
    append(cleanHeader(resentMessageId))
    append("\r\n")
}.toByteArray(Charsets.UTF_8)

/** Prepend Resent-* and leave the original headers, including Message-ID, unchanged. */
fun buildBounce(
    original: ByteArray,
    resentFrom: String,
    resentTo: String,
    resentDate: String,
    resentMessageId: String,
): ByteArray {
    val prefix = bouncePrefix(resentFrom, resentTo, resentDate, resentMessageId)
    val out = ByteArray(prefix.size + original.size)
    prefix.copyInto(out)
    original.copyInto(out, prefix.size)
    return out
}

fun loadEditor(bytes: ByteArray, fallback: String): EditorLoad {
    val parsed = parseRfc822(bytes, fallback)
    val contentType = headerValues(parsed, "Content-Type").firstOrNull().orEmpty()
    val encoding = headerValues(parsed, "Content-Transfer-Encoding").firstOrNull().orEmpty()
    val text: String
    val parts: List<OutgoingPart>
    val html: Boolean
    if (contentType.trim().lowercase().startsWith("multipart/")) {
        if (headerParam(contentType, "boundary").isEmpty()) {
            throw MimeDecodeException("multipart has no boundary")
        }
        val collected = collectEditorParts(MimeDecode.multipartParts(bytes), contentType, fallback)
        text = collected.text ?: ""
        parts = collected.parts
        html = collected.html
    } else {
        text = decodePart(parsed.body, headerParam(contentType, "charset"), encoding, fallback).text
            .replace("\r\n", "\n").replace('\r', '\n')
        parts = emptyList()
        html = false
    }
    return EditorLoad(
        to = addresses(parsed, "To", fallback).joinToString(", "),
        cc = addresses(parsed, "Cc", fallback).joinToString(", "),
        subject = headerValues(parsed, "Subject").firstOrNull().orEmpty(),
        body = text,
        inReplyTo = headerValues(parsed, "In-Reply-To").firstOrNull().orEmpty().trim(),
        references = headerValues(parsed, "References").joinToString(" ").trim(),
        attachments = parts,
        html = html,
    )
}

/**
 * True when [address] can go raw into SMTP MAIL FROM / RCPT TO: no controls (CR/LF would inject commands),
 * no DEL, no angle brackets, spaces only inside a quoted local part, and the quotes balanced.
 */
fun envelopeAddressOk(address: String): Boolean {
    if (address.isEmpty()) return false
    var quoted = false
    var i = 0
    while (i < address.length) {
        val c = address[i]
        if (c < ' ' || c == '\u007f' || c == '<' || c == '>') return false
        when {
            c == '"' -> quoted = !quoted
            c == ' ' && !quoted -> return false
            c == '\\' && quoted && i + 1 < address.length -> i++
        }
        i++
    }
    return !quoted
}

fun envelopeRecipients(to: List<String>, cc: List<String>, bcc: List<String>): List<String> {
    val seen = LinkedHashSet<String>()
    val out = ArrayList<String>()
    for (raw in to + cc + bcc) {
        val spec = addrSpec(raw).trim()
        if (spec.isEmpty()) continue
        if (seen.add(spec.lowercase())) out.add(spec)
    }
    return out
}

private fun escapeQuotes(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

/** F80(b): no CR/LF, NUL, other C0 controls, DEL or bidi/format controls reach a header; a tab becomes a space. */
internal fun cleanHeader(value: String): String {
    val out = StringBuilder(value.length)
    for (ch in value) {
        when {
            ch == '\t' -> out.append(' ')
            ch.code < 32 || ch.code == 127 -> Unit
            ch in '\u0080'..'\u009F' -> Unit
            ch == '\u200E' || ch == '\u200F' || ch in '\u202A'..'\u202E' || ch in '\u2066'..'\u2069' -> Unit
            ch == '\u061C' || ch == '\uFEFF' -> Unit
            else -> out.append(ch)
        }
    }
    return out.toString()
}

/**
 * F80(c): Content-Type `name` and Content-Disposition `filename` for an attachment. ASCII names are quoted as is;
 * others get RFC 2231 `filename*=UTF-8''…` plus an encoded-word `name` for older readers.
 */
internal fun filenameParams(file: String): Pair<String, String> {
    if (file.all { it.code in 32..126 }) {
        val quoted = escapeQuotes(file)
        return "name=\"$quoted\"" to "filename=\"$quoted\""
    }
    val pct = StringBuilder()
    for (b in file.toByteArray(Charsets.UTF_8)) {
        val c = b.toInt() and 0xff
        val ch = c.toChar()
        if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "!#$&+-.^_`|~") pct.append(ch)
        else pct.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
    }
    return "name=\"${encodedWord(file, fold = false)}\"" to "filename*=UTF-8''$pct"
}

private fun headerText(value: String): String {
    val clean = cleanHeader(value)
    if (clean.all { it.code in 32..126 }) return clean
    return encodedWord(clean)
}

/**
 * F80(c): RFC 2047 words of at most 75 characters (45 UTF-8 bytes each, never splitting a character), folded
 * onto continuation lines.
 */
internal fun encodedWord(value: String, fold: Boolean = true): String {
    val words = ArrayList<String>()
    val chunk = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < value.length) {
        val cp = value.codePointAt(i)
        val bytes = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8)
        if (chunk.size() + bytes.size > 45) {
            words.add("=?UTF-8?B?" + Base64.getEncoder().encodeToString(chunk.toByteArray()) + "?=")
            chunk.reset()
        }
        chunk.write(bytes)
        i += Character.charCount(cp)
    }
    if (chunk.size() > 0 || words.isEmpty()) {
        words.add("=?UTF-8?B?" + Base64.getEncoder().encodeToString(chunk.toByteArray()) + "?=")
    }
    return words.joinToString(if (fold) "\r\n " else " ")
}

private fun isAtom(value: String): Boolean =
    value.isNotEmpty() && value.all { ch ->
        ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "!#$%&'*+-/=?^_`{|}~."
    }

private fun splitHeaderBody(bytes: ByteArray): Pair<ByteArray, ByteArray> {
    var i = 0
    while (i + 1 < bytes.size) {
        if (bytes[i] == '\r'.code.toByte() &&
            i + 3 < bytes.size &&
            bytes[i + 1] == '\n'.code.toByte() &&
            bytes[i + 2] == '\r'.code.toByte() &&
            bytes[i + 3] == '\n'.code.toByte()
        ) {
            return bytes.copyOfRange(0, i) to bytes.copyOfRange(i + 4, bytes.size)
        }
        if (bytes[i] == '\n'.code.toByte() && bytes[i + 1] == '\n'.code.toByte()) {
            return bytes.copyOfRange(0, i) to bytes.copyOfRange(i + 2, bytes.size)
        }
        i++
    }
    return bytes to ByteArray(0)
}

internal fun decodeHeaderWords(value: String, fallback: String): String =
    MimeDecode.phrase(value, fallback)[1]

internal fun decodeHeaderPhrases(values: Array<String>, fallback: String): Array<String> {
    if (values.isEmpty()) return emptyArray()
    return MimeDecode.phrases(values, fallback)
}

internal class MimeDecodeException(message: String) : MailFailure(message)

internal fun decodeTransfer(bytes: ByteArray, encoding: String): ByteArray =
    decodeTransferOrNull(bytes, encoding) ?: throw MimeDecodeException("could not decode this part")

internal fun decodeTransferOrNull(bytes: ByteArray, encoding: String): ByteArray? {
    val handle = MimeDecode.xferOpen(encoding)
    try {
        return MimeDecode.xferTake(handle, bytes, true)
    } finally {
        MimeDecode.xferClose(handle)
    }
}

fun headerParam(header: String, name: String): String = readParam(header, name, star = false).orEmpty()

private fun isParamSpace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C' || c == '\u000B'

/** Quoted value, else the token. No [Regex]: the name is different on each call. */
private fun readParam(header: String, name: String, star: Boolean): String? {
    var searchFrom = 0
    var first = true
    while (true) {
        val marker = if (first) -1 else header.indexOf(';', searchFrom)
        if (!first && marker < 0) return null
        var j = if (first) 0 else marker + 1
        while (j < header.length && isParamSpace(header[j])) j++
        val nameAt = j + name.length <= header.length &&
            header.regionMatches(j, name, 0, name.length, ignoreCase = true)
        if (nameAt) {
            var k = j + name.length
            val starred = k < header.length && header[k] == '*'
            if (starred) k++
            if (starred == star) {
                while (k < header.length && isParamSpace(header[k])) k++
                if (k < header.length && header[k] == '=') {
                    k++
                    while (k < header.length && isParamSpace(header[k])) k++
                    if (k < header.length && header[k] == '"') {
                        val end = header.indexOf('"', k + 1)
                        if (end >= 0) return header.substring(k + 1, end)
                    }
                    val start = k
                    while (k < header.length && header[k] != ';' && !isParamSpace(header[k])) k++
                    if (k > start) return header.substring(start, k)
                }
            }
        }
        if (first) {
            first = false
            searchFrom = 0
        } else {
            searchFrom = marker + 1
        }
    }
}

private class MultipartBody(
    val text: String?,
    val html: Boolean,
    val parts: List<OutgoingPart>,
    /** HTML part kept out of [parts] so a parent that does not adopt it can still attach it. */
    val heldHtml: OutgoingPart? = null,
)

private fun collectEditorParts(chunks: Array<ByteArray>, contentType: String, fallback: String): MultipartBody {
    if (contentType.substringBefore(';').trim().equals("multipart/alternative", ignoreCase = true)) {
        return readAlternative(chunks, fallback)
    }
    var text: String? = null
    var html = false
    var heldHtml: OutgoingPart? = null
    val parts = ArrayList<OutgoingPart>()
    for (chunk in chunks) {
        val part = parseRfc822(chunk, fallback)
        val type = rawHeader(part, "Content-Type")
        val cte = headerValues(part, "Content-Transfer-Encoding").firstOrNull().orEmpty()
        val media = type.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
        if (media.startsWith("multipart/", ignoreCase = true)) {
            if (headerParam(type, "boundary").isEmpty()) throw MimeDecodeException("multipart has no boundary")
            val nested = collectEditorParts(MimeDecode.multipartParts(chunk), type, fallback)
            if (text == null && nested.text != null) {
                text = nested.text
                html = nested.html
                if (nested.html) heldHtml = nested.heldHtml
            } else if (nested.heldHtml != null) {
                parts.add(nested.heldHtml)
            }
            parts.addAll(nested.parts)
        } else if (text == null && media.equals("text/plain", ignoreCase = true)) {
            text = partText(part, type, cte, fallback)
        } else {
            parts.add(outgoingPart(part, media, type, cte, fallback))
        }
    }
    return MultipartBody(text, html, parts, heldHtml)
}

/** text/html that is not an attachment becomes the body. Its text/plain sibling is not an attachment. */
private fun readAlternative(chunks: Array<ByteArray>, fallback: String): MultipartBody {
    var htmlBody: String? = null
    var plainBody: String? = null
    var heldHtml: OutgoingPart? = null
    var attachedPlain: OutgoingPart? = null
    val rest = ArrayList<OutgoingPart>()
    for (chunk in chunks) {
        val part = parseRfc822(chunk, fallback)
        val type = rawHeader(part, "Content-Type")
        val cte = headerValues(part, "Content-Transfer-Encoding").firstOrNull().orEmpty()
        val media = type.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
        if (media.startsWith("multipart/", ignoreCase = true)) {
            if (headerParam(type, "boundary").isEmpty()) throw MimeDecodeException("multipart has no boundary")
            val nested = collectEditorParts(MimeDecode.multipartParts(chunk), type, fallback)
            if (htmlBody == null && nested.html && nested.text != null) {
                htmlBody = nested.text
                heldHtml = nested.heldHtml
            } else if (plainBody == null && nested.text != null && !nested.html) {
                plainBody = nested.text
            } else if (nested.heldHtml != null) {
                rest.add(nested.heldHtml)
            }
            rest.addAll(nested.parts)
            continue
        }
        val attached = isAttachment(part)
        if (htmlBody == null && !attached && media.equals("text/html", ignoreCase = true)) {
            htmlBody = partText(part, type, cte, fallback)
            heldHtml = outgoingPart(part, media, type, cte, fallback)
            continue
        }
        if (plainBody == null && media.equals("text/plain", ignoreCase = true)) {
            plainBody = partText(part, type, cte, fallback)
            if (attached) attachedPlain = outgoingPart(part, media, type, cte, fallback)
            continue
        }
        rest.add(outgoingPart(part, media, type, cte, fallback))
    }
    if (htmlBody != null) {
        attachedPlain?.let { rest.add(it) }
        return MultipartBody(htmlBody, true, rest, heldHtml)
    }
    return MultipartBody(plainBody, false, rest, null)
}

private fun partText(part: ParsedRfc822, type: String, cte: String, fallback: String): String {
    return decodePart(part.body, headerParam(type, "charset"), cte, fallback).text
        .replace("\r\n", "\n").replace('\r', '\n')
}

private fun outgoingPart(part: ParsedRfc822, media: String, type: String, cte: String, fallback: String): OutgoingPart {
    val decoded = decodeTransfer(part.body, cte)
    return OutgoingPart(filenameOf(part, fallback), media, decoded, wireBase64 = false)
}

private fun isAttachment(part: ParsedRfc822): Boolean {
    val disposition = rawHeader(part, "Content-Disposition")
    return disposition.substringBefore(';').trim().equals("attachment", ignoreCase = true)
}

private fun rawHeader(part: ParsedRfc822, name: String): String =
    part.headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.raw.orEmpty()

private fun filenameOf(part: ParsedRfc822, fallback: String): String {
    val disposition = rawHeader(part, "Content-Disposition")
    val star = readParam(disposition, "filename", star = true)
    if (star != null) {
        val decoded = decode2231(star, fallback)
        if (decoded.isNotEmpty()) return decoded
    }
    val fromDisposition = decodeHeaderWords(headerParam(disposition, "filename"), fallback)
    if (fromDisposition.isNotEmpty()) return fromDisposition
    val fromType = decodeHeaderWords(headerParam(rawHeader(part, "Content-Type"), "name"), fallback)
    if (fromType.isNotEmpty()) return fromType
    return "attachment"
}

private fun decode2231(raw: String, fallback: String): String = MimeDecode.parameter2231(raw, fallback)

/** decodeHeaderWords for the reader's header view (one header line at a time). */
fun decodeHeaderWordsForView(line: String, fallback: String): String = decodeHeaderWords(line, fallback)

/** The signature as inserted: "-- " line first when [dashes]. Empty for no signature. */
fun signatureBlock(signature: String, dashes: Boolean): String {
    val text = signature.replace("\r\n", "\n").trimEnd()
    if (text.isEmpty()) return ""
    return if (dashes) "-- \n$text" else text
}

/**
 * Body with the signature: at the end when [atBottom] or the cursor is at the end (reply below the quote,
 * forward, new); else right after the cursor block at the top (reply above the quote). Never twice.
 */
fun withSignature(body: String, block: String, atBottom: Boolean, cursorAtTop: Boolean): String {
    if (block.isEmpty() || body.contains(block)) return body
    if (atBottom || !cursorAtTop) return if (body.isEmpty()) "\n\n$block" else body.trimEnd('\n') + "\n\n" + block
    return "\n\n$block\n\n" + body.trimStart('\n')
}

/** F80: exactly two hex digits; toIntOrNull(16) would also take "-1" (0xFF) or "+F" as an escape. */
fun hexByteOrNull(hex: String): Int? {
    if (hex.length != 2) return null
    fun d(c: Char) = when (c) { in '0'..'9' -> c - '0'; in 'a'..'f' -> c - 'a' + 10; in 'A'..'F' -> c - 'A' + 10; else -> -1 }
    val hi = d(hex[0]); val lo = d(hex[1])
    return if (hi < 0 || lo < 0) null else hi * 16 + lo
}
