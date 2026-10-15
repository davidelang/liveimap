package org.dlang.liveimap.ui.compose

import org.dlang.liveimap.session.MaxMimeDepth
import org.dlang.liveimap.session.MimePart
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
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
)

class BuiltMail(
    val rfc822: ByteArray,
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
        if (chunk.isEmpty()) break
        out.write(chunk)
        offset += chunk.size
    }
    return out.toByteArray()
}

fun parseRfc822(bytes: ByteArray): ParsedRfc822 {
    val (headerBytes, body) = splitHeaderBody(bytes)
    val headers = ArrayList<HeaderField>()
    for (line in unfoldHeaderLines(headerBytes)) {
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val name = line.substring(0, colon).trim()
        val raw = line.substring(colon + 1).trim()
        if (name.isNotEmpty()) headers.add(HeaderField(name, decodeHeaderWords(raw), raw))
    }
    return ParsedRfc822(headers, body)
}

fun headerValues(message: ParsedRfc822, name: String): List<String> =
    message.headers.filter { it.name.equals(name, ignoreCase = true) }.map { it.value }

/**
 * Address headers are split on the text as sent; encoded words are then decoded only in the display name,
 * never in the addr-spec, so a decoded CR/LF, ',' or '<' cannot add or change a recipient (F24, RFC 2047 §5).
 */
fun addresses(message: ParsedRfc822, name: String): List<String> =
    message.headers.filter { it.name.equals(name, ignoreCase = true) }
        .flatMap { splitAddresses(it.raw) }
        .map(::decodeAddressDisplay)

fun decodeAddressDisplay(address: String): String {
    val lt = address.lastIndexOf('<')
    if (lt <= 0 || !address.contains("=?")) return address
    val phrase = address.substring(0, lt).trim()
    if (!phrase.contains("=?")) return address
    val decoded = decodeHeaderWords(phrase)
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
    quotePrefix: String = DefaultQuotePrefix,
    leadIn: String = DefaultLeadIn,
): ReplyDraft {
    val (to, cc) = replyRecipients(
        replyAll = replyAll,
        replyTo = addresses(message, "Reply-To"),
        from = addresses(message, "From"),
        to = addresses(message, "To"),
        cc = addresses(message, "Cc"),
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

fun appendMessageId(references: String, messageId: String): String {
    val id = messageId.trim()
    val parent = references.trim()
    if (id.isEmpty()) return parent
    if (parent.isEmpty()) return id
    val tokens = parent.split(Regex("\\s+"))
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

private fun wrapPlainLine(line: String, column: Int): String {
    if (line.codePointCount(0, line.length) <= column) return line
    val prefix = quotePrefix(line)
    val prefixCount = prefix.codePointCount(0, prefix.length)
    if (prefixCount >= column) return line
    val words = line.substring(prefix.length).split(Regex("[ \t]+")).filter { it.isNotEmpty() }
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

/** A forwarded part keeps the server's transfer encoding. [looksLikeBase64] is not the decision. */
internal fun forwardWire(encoding: String, bytes: ByteArray): Pair<String, ByteArray> {
    val token = encoding.substringBefore(';').trim().lowercase()
    return when (token) {
        "quoted-printable" -> "quoted-printable" to bytes
        "7bit" -> "7bit" to bytes
        "base64" -> "base64" to rewrapBase64(bytes)
        "binary" -> "binary" to bytes
        "x-uuencode" -> "x-uuencode" to bytes
        "" -> {
            val eight = bytes.any { (it.toInt() and 0xFF) > 127 }
            (if (eight) "8bit" else "7bit") to bytes
        }
        else -> token to bytes
    }
}

/** Bcc is not a header. Those addresses are only SMTP envelope recipients. */
fun buildPlain(message: PlainMessage): BuiltMail {
    val recipients = envelopeRecipients(message.to, message.cc, message.bcc)
    val flowed = message.flowed && message.wrapColumn > 0
    val plain = if (flowed) {
        flowPlain(message.body, message.wrapColumn)
    } else {
        wrapPlain(message.body, message.wrapColumn)
    }
    val (encoding, payload) = textPart(plain, allowEightBit = true)
    val plainType = if (flowed) {
        "text/plain; charset=utf-8; format=flowed; delsp=yes"
    } else {
        "text/plain; charset=utf-8"
    }
    val bytes = if (message.attachments.isEmpty()) {
        val headers = baseHeaders(message, plainType, encoding)
        val out = ByteArrayOutputStream()
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
        out.write(payload)
        out.toByteArray()
    } else {
        val token = message.messageId.filter { it.isLetterOrDigit() }.ifEmpty { "part" }
        val boundary = "liveimap_$token"
        val headers = baseHeaders(message, "multipart/mixed; boundary=$boundary", null)
        val out = ByteArrayOutputStream()
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
        writePart(
            out,
            boundary,
            plainType,
            encoding,
            null,
            payload,
        )
        for (part in message.attachments) {
            // F80(d): base64 already on the wire is re-wrapped at 76, never copied as it came.
            val (cte, encoded) = if (part.transferEncoding != null) {
                forwardWire(part.transferEncoding, part.bytes)
            } else if (part.wireBase64) {
                "base64" to rewrapBase64(part.bytes)
            } else {
                "base64" to mimeBase64(part.bytes)
            }
            val type = cleanHeader(part.mediaType).ifEmpty { "application/octet-stream" }
            val file = cleanHeader(part.filename).ifEmpty { "attachment" }
            val (nameParam, dispositionParam) = filenameParams(file)
            writePart(
                out,
                boundary,
                "$type; $nameParam",
                cte,
                "attachment; $dispositionParam",
                encoded,
            )
        }
        out.write("--$boundary--\r\n".toByteArray(Charsets.US_ASCII))
        out.toByteArray()
    }
    return BuiltMail(bytes, recipients)
}

/** Prepend Resent-* and leave the original headers, including Message-ID, unchanged. */
fun buildBounce(
    original: ByteArray,
    resentFrom: String,
    resentTo: String,
    resentDate: String,
    resentMessageId: String,
): ByteArray {
    val prefix = buildString {
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
    val out = ByteArray(prefix.size + original.size)
    prefix.copyInto(out)
    original.copyInto(out, prefix.size)
    return out
}

fun loadEditor(bytes: ByteArray): EditorLoad {
    val parsed = parseRfc822(bytes)
    val contentType = headerValues(parsed, "Content-Type").firstOrNull().orEmpty()
    val encoding = headerValues(parsed, "Content-Transfer-Encoding").firstOrNull().orEmpty()
    val text: String
    val parts: List<OutgoingPart>
    if (contentType.trim().lowercase().startsWith("multipart/")) {
        val collected = readMultipart(parsed.body, contentType)
        text = collected.first
        parts = collected.second
    } else {
        val decoded = decodeTransfer(parsed.body, encoding)
        text = decoded.toString(charsetOf(contentType)).replace("\r\n", "\n").replace('\r', '\n')
        parts = emptyList()
    }
    return EditorLoad(
        to = addresses(parsed, "To").joinToString(", "),
        cc = addresses(parsed, "Cc").joinToString(", "),
        subject = headerValues(parsed, "Subject").firstOrNull().orEmpty(),
        body = text,
        inReplyTo = headerValues(parsed, "In-Reply-To").firstOrNull().orEmpty().trim(),
        references = headerValues(parsed, "References").joinToString(" ").trim(),
        attachments = parts,
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

private fun baseHeaders(message: PlainMessage, contentType: String, encoding: String?): String =
    buildString {
        append("Date: ")
        append(cleanHeader(message.date))
        append("\r\n")
        append("From: ")
        append(cleanHeader(formatMailbox(message.fromName, message.fromEmail)))
        append("\r\n")
        appendMailboxHeader("To", message.to)
        appendMailboxHeader("Cc", message.cc)
        append("Subject: ")
        append(headerText(message.subject))
        append("\r\n")
        append("Message-ID: ")
        append(cleanHeader(message.messageId))
        append("\r\n")
        if (message.inReplyTo.isNotBlank()) {
            append("In-Reply-To: ")
            append(cleanHeader(message.inReplyTo.trim()))
            append("\r\n")
        }
        if (message.references.isNotBlank()) {
            append("References: ")
            append(cleanHeader(message.references.trim()))
            append("\r\n")
        }
        for ((name, value) in message.extraHeaders) {
            val clean = name.filter { it.isLetterOrDigit() || it == '-' }
            if (clean.isEmpty() || value.isBlank() || clean.lowercase() in reservedHeaders) continue
            append(clean)
            append(": ")
            append(headerText(value.trim()))
            append("\r\n")
        }
        append("MIME-Version: 1.0\r\n")
        append("Content-Type: ")
        append(cleanHeader(contentType))
        append("\r\n")
        if (encoding != null) {
            append("Content-Transfer-Encoding: ")
            append(encoding)
            append("\r\n")
        }
    }

private fun StringBuilder.appendMailboxHeader(name: String, values: List<String>) {
    val rendered = values.map { cleanHeader(it.trim()) }.filter { it.isNotEmpty() }
    if (rendered.isEmpty()) return
    append(name)
    append(": ")
    append(rendered.joinToString(", "))
    append("\r\n")
}

private fun writePart(
    out: ByteArrayOutputStream,
    boundary: String,
    contentType: String,
    encoding: String,
    disposition: String?,
    body: ByteArray,
) {
    out.write("--$boundary\r\n".toByteArray(Charsets.US_ASCII))
    out.write("Content-Type: ${cleanHeader(contentType)}\r\n".toByteArray(Charsets.UTF_8))
    out.write("Content-Transfer-Encoding: $encoding\r\n".toByteArray(Charsets.US_ASCII))
    if (disposition != null) {
        out.write("Content-Disposition: ${cleanHeader(disposition)}\r\n".toByteArray(Charsets.UTF_8))
    }
    out.write("\r\n".toByteArray(Charsets.US_ASCII))
    out.write(body)
    if (body.isEmpty() || body[body.size - 1] != '\n'.code.toByte()) {
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
    }
}

private fun normalizeNewlines(body: String): String {
    val unified = body.replace("\r\n", "\n").replace('\r', '\n').replace("\n", "\r\n")
    return if (unified.endsWith("\r\n")) unified else "$unified\r\n"
}

/** Optimistic 8-bit only when allowed and every line is at most 998 octets. */
internal fun textPart(body: String, allowEightBit: Boolean): Pair<String, ByteArray> {
    val text = normalizeNewlines(body)
    val raw = text.toByteArray(Charsets.UTF_8)
    if (allowEightBit && !lineExceeds(raw, 998)) {
        val encoding = if (raw.any { (it.toInt() and 0xFF) > 127 }) "8bit" else "7bit"
        return encoding to raw
    }
    return "quoted-printable" to quotedPrintable(raw)
}

internal fun quotedPrintable(bytes: ByteArray): ByteArray {
    val hex = "0123456789ABCDEF".toByteArray(Charsets.US_ASCII)
    val out = ByteArrayOutputStream()
    var column = 0
    var i = 0
    while (i < bytes.size) {
        val b = bytes[i].toInt() and 0xFF
        if (b == '\r'.code && i + 1 < bytes.size && (bytes[i + 1].toInt() and 0xFF) == '\n'.code) {
            out.write('\r'.code)
            out.write('\n'.code)
            column = 0
            i += 2
            continue
        }
        val literal = b in 33..60 || b in 62..126
        val need = if (literal) 1 else 3
        if (column + need > 73) {
            out.write('='.code)
            out.write('\r'.code)
            out.write('\n'.code)
            column = 0
        }
        if (literal) {
            out.write(b)
            column += 1
        } else {
            out.write('='.code)
            out.write(hex[b ushr 4].toInt())
            out.write(hex[b and 0x0F].toInt())
            column += 3
        }
        i++
    }
    return out.toByteArray()
}

fun withoutEightBit(rfc822: ByteArray): ByteArray {
    if (!subtreeHas8bitHeader(rfc822)) return rfc822
    val sep = indexOfCrlfCrlf(rfc822)
    if (sep < 0) return rfc822
    val header = rfc822.copyOfRange(0, sep)
    val body = rfc822.copyOfRange(sep + 4, rfc822.size)
    val contentType = unfoldedHeader(header, "Content-Type")
    if (!contentType.trim().lowercase().startsWith("multipart/")) {
        if (!headerHas8bitCte(header)) return rfc822
        return joinHeaderBody(replace8bitCte(header), quotedPrintable(body))
    }
    val boundary = headerParam(contentType, "boundary")
    if (boundary.isEmpty()) return rfc822
    val head = if (headerHas8bitCte(header)) replace8bitCte(header) else header
    return joinHeaderBody(head, rewriteMultipartBody(body, boundary))
}

private fun lineExceeds(bytes: ByteArray, limit: Int): Boolean {
    var count = 0
    var i = 0
    while (i < bytes.size) {
        val b = bytes[i].toInt() and 0xFF
        if (b == '\n'.code) {
            if (count > limit) return true
            count = 0
        } else if (b != '\r'.code) {
            count++
            if (count > limit) return true
        }
        i++
    }
    return count > limit
}

private fun messageHas8bitCte(bytes: ByteArray): Boolean {
    var found = false
    eachLine(bytes) { line ->
        if (is8bitCteLine(line)) found = true
    }
    return found
}

private fun headerHas8bitCte(header: ByteArray): Boolean = messageHas8bitCte(header)

private fun is8bitCteLine(line: String): Boolean {
    val prefix = "content-transfer-encoding:"
    if (line.length < prefix.length) return false
    if (!line.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return false
    return line.substring(prefix.length).trim().equals("8bit", ignoreCase = true)
}

private fun eachLine(bytes: ByteArray, block: (String) -> Unit) {
    var start = 0
    var i = 0
    while (i <= bytes.size) {
        if (i == bytes.size || bytes[i] == '\n'.code.toByte()) {
            var end = i
            if (end > start && bytes[end - 1] == '\r'.code.toByte()) end--
            if (end > start || i < bytes.size) {
                block(bytes.copyOfRange(start, end).toString(Charsets.ISO_8859_1))
            }
            start = i + 1
        }
        i++
    }
}

private fun indexOfCrlfCrlf(bytes: ByteArray): Int {
    var i = 0
    while (i + 3 < bytes.size) {
        if (bytes[i] == '\r'.code.toByte() &&
            bytes[i + 1] == '\n'.code.toByte() &&
            bytes[i + 2] == '\r'.code.toByte() &&
            bytes[i + 3] == '\n'.code.toByte()
        ) {
            return i
        }
        i++
    }
    return -1
}

private fun unfoldedHeader(header: ByteArray, name: String): String {
    val prefix = "$name:"
    for (line in unfoldHeaderLines(header)) {
        if (line.length >= prefix.length && line.startsWith(prefix, ignoreCase = true)) {
            return line.substring(prefix.length).trim()
        }
    }
    return ""
}

private fun replace8bitCte(header: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    var start = 0
    var i = 0
    while (i <= header.size) {
        if (i == header.size || header[i] == '\n'.code.toByte()) {
            var end = i
            if (end > start && header[end - 1] == '\r'.code.toByte()) end--
            val line = header.copyOfRange(start, end).toString(Charsets.ISO_8859_1)
            if (is8bitCteLine(line)) {
                val colon = line.indexOf(':')
                out.write(line.substring(0, colon + 1).toByteArray(Charsets.ISO_8859_1))
                out.write(" quoted-printable".toByteArray(Charsets.US_ASCII))
            } else if (end > start) {
                out.write(header, start, end - start)
            }
            if (i < header.size) out.write(header, end, i - end + 1)
            start = i + 1
        }
        i++
    }
    return out.toByteArray()
}

private fun joinHeaderBody(header: ByteArray, body: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(header)
    out.write("\r\n\r\n".toByteArray(Charsets.US_ASCII))
    out.write(body)
    return out.toByteArray()
}

private fun rewriteMultipartBody(body: ByteArray, boundary: String): ByteArray {
    val marker = "--$boundary".toByteArray(Charsets.US_ASCII)
    val starts = ArrayList<Int>()
    var i = 0
    while (i + marker.size <= body.size) {
        if (matchesAt(body, i, marker) && atLineStart(body, i) && boundaryEnds(body, i + marker.size)) {
            starts.add(i)
            i += marker.size
        } else {
            i++
        }
    }
    if (starts.isEmpty()) return body
    val out = ByteArrayOutputStream()
    if (starts[0] > 0) out.write(body, 0, starts[0])
    for (index in starts.indices) {
        val at = starts[index]
        var cursor = at + marker.size
        val closing = cursor + 1 < body.size &&
            body[cursor] == '-'.code.toByte() &&
            body[cursor + 1] == '-'.code.toByte()
        if (closing) {
            out.write(body, at, body.size - at)
            break
        }
        val lineEnd = lineEndAfter(body, cursor)
        val next = if (index + 1 < starts.size) starts[index + 1] else body.size
        out.write(body, at, lineEnd - at)
        out.write(rewriteOnePart(body.copyOfRange(lineEnd, next)))
    }
    return out.toByteArray()
}

private fun boundaryEnds(body: ByteArray, after: Int): Boolean {
    if (after >= body.size) return true
    val c = body[after]
    return c == '\r'.code.toByte() ||
        c == '\n'.code.toByte() ||
        c == ' '.code.toByte() ||
        c == '\t'.code.toByte() ||
        c == '-'.code.toByte()
}

private fun lineEndAfter(body: ByteArray, from: Int): Int {
    var i = from
    while (i < body.size && body[i] != '\n'.code.toByte()) i++
    if (i < body.size) i++
    return i
}

private fun subtreeHas8bitHeader(bytes: ByteArray): Boolean {
    val sep = indexOfCrlfCrlf(bytes)
    if (sep < 0) return false
    val header = bytes.copyOfRange(0, sep)
    if (headerHas8bitCte(header)) return true
    val contentType = unfoldedHeader(header, "Content-Type")
    if (!contentType.trim().lowercase().startsWith("multipart/")) return false
    val boundary = headerParam(contentType, "boundary")
    if (boundary.isEmpty()) return false
    val body = bytes.copyOfRange(sep + 4, bytes.size)
    for (chunk in splitMultipartChunks(body, boundary)) {
        if (subtreeHas8bitHeader(chunk)) return true
    }
    return false
}

private fun rewriteOnePart(part: ByteArray): ByteArray {
    val sep = indexOfCrlfCrlf(part)
    if (sep < 0) return part
    val header = part.copyOfRange(0, sep)
    val contentType = unfoldedHeader(header, "Content-Type")
    if (contentType.trim().lowercase().startsWith("multipart/")) {
        if (!subtreeHas8bitHeader(part)) return part
        val boundary = headerParam(contentType, "boundary")
        if (boundary.isEmpty()) return part
        val rawBody = part.copyOfRange(sep + 4, part.size)
        val keepBreak = rawBody.size >= 2 &&
            rawBody[rawBody.size - 2] == '\r'.code.toByte() &&
            rawBody[rawBody.size - 1] == '\n'.code.toByte()
        val content = if (keepBreak) rawBody.copyOfRange(0, rawBody.size - 2) else rawBody
        val head = if (headerHas8bitCte(header)) replace8bitCte(header) else header
        val out = ByteArrayOutputStream()
        out.write(head)
        out.write("\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.write(rewriteMultipartBody(content, boundary))
        if (keepBreak) out.write("\r\n".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }
    if (!headerHas8bitCte(header)) return part
    val rawBody = part.copyOfRange(sep + 4, part.size)
    val keepBreak = rawBody.size >= 2 &&
        rawBody[rawBody.size - 2] == '\r'.code.toByte() &&
        rawBody[rawBody.size - 1] == '\n'.code.toByte()
    val content = if (keepBreak) rawBody.copyOfRange(0, rawBody.size - 2) else rawBody
    val out = ByteArrayOutputStream()
    out.write(replace8bitCte(header))
    out.write("\r\n\r\n".toByteArray(Charsets.US_ASCII))
    out.write(quotedPrintable(content))
    if (keepBreak) out.write("\r\n".toByteArray(Charsets.US_ASCII))
    return out.toByteArray()
}

private fun mimeBase64(bytes: ByteArray): ByteArray {
    val encoder = Base64.getMimeEncoder(76, byteArrayOf('\r'.code.toByte(), '\n'.code.toByte()))
    return encoder.encode(bytes)
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

/** F80(d): decode leniently and re-encode in 76-column lines; when it does not decode, keep only the base64 alphabet. */
internal fun rewrapBase64(wire: ByteArray): ByteArray {
    val decoded = try {
        Base64.getMimeDecoder().decode(wire)
    } catch (_: IllegalArgumentException) {
        null
    }
    if (decoded != null) return mimeBase64(decoded)
    val alphabet = wire.filter { b ->
        val c = b.toInt().toChar()
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '='
    }.toByteArray()
    return alphabet.toList().chunked(76).joinToString("") { line -> String(line.toByteArray(), Charsets.US_ASCII) + "\r\n" }
        .toByteArray(Charsets.US_ASCII)
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

private fun unfoldHeaderLines(headerBytes: ByteArray): List<String> {
    val text = headerBytes.toString(Charsets.ISO_8859_1)
    val raw = text.split('\n').map { it.trimEnd('\r') }
    val out = ArrayList<String>()
    for (line in raw) {
        if (line.isEmpty()) continue
        if ((line[0] == ' ' || line[0] == '\t') && out.isNotEmpty()) {
            out[out.lastIndex] = out.last() + " " + line.trim()
        } else {
            out.add(line)
        }
    }
    return out
}

private class EncodedWord(val end: Int, val text: String)

/** Linear whitespace between two encoded-words is removed. A word that fails stays intact. */
internal fun decodeHeaderWords(value: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < value.length) {
        val word = scanEncodedWord(value, i)
        if (word == null) {
            out.append(value[i])
            i++
            continue
        }
        out.append(word.text)
        i = word.end
        val gap = linearWhitespaceEnd(value, i)
        if (gap > i && scanEncodedWord(value, gap) != null) i = gap
    }
    return out.toString()
}

private fun scanEncodedWord(value: String, start: Int): EncodedWord? {
    if (!value.startsWith("=?", start)) return null
    var i = start + 2
    val charsetStart = i
    while (i < value.length && value[i] != '?' && !isLinearWhitespace(value[i])) i++
    if (i >= value.length || value[i] != '?' || i == charsetStart) return null
    val charsetName = value.substring(charsetStart, i)
    i++
    if (i >= value.length) return null
    val encoding = value[i]
    if (encoding != 'B' && encoding != 'b' && encoding != 'Q' && encoding != 'q') return null
    i++
    if (i >= value.length || value[i] != '?') return null
    i++
    val payloadStart = i
    while (i < value.length && value[i] != '?' && !isLinearWhitespace(value[i])) i++
    if (i >= value.length || value[i] != '?' || i == payloadStart) return null
    if (i + 1 >= value.length || value[i + 1] != '=') return null
    val end = i + 2
    val raw = value.substring(start, end)
    val decoded = decodeEncodedWord(charsetName, encoding, value.substring(payloadStart, i))
    return EncodedWord(end, decoded ?: raw)
}

private fun decodeEncodedWord(charsetName: String, encoding: Char, payload: String): String? {
    val charset = try {
        Charset.forName(charsetName)
    } catch (_: Exception) {
        return null
    }
    return try {
        if (encoding == 'B' || encoding == 'b') {
            Base64.getDecoder().decode(payload).toString(charset)
        } else {
            decodeQEncoding(payload, charset)
        }
    } catch (_: Exception) {
        null
    }
}

private fun isLinearWhitespace(ch: Char): Boolean =
    ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n'

private fun linearWhitespaceEnd(value: String, start: Int): Int {
    var i = start
    while (i < value.length && isLinearWhitespace(value[i])) i++
    return i
}

private fun decodeQEncoding(data: String, charset: Charset): String {
    val out = ByteArrayOutputStream()
    var i = 0
    while (i < data.length) {
        val ch = data[i]
        if (ch == '_') {
            out.write(' '.code)
            i++
        } else if (ch == '=' && i + 2 < data.length) {
            val hex = data.substring(i + 1, i + 3)
            val value = hexByteOrNull(hex)
            if (value != null) {
                out.write(value)
                i += 3
            } else {
                out.write(ch.code)
                i++
            }
        } else {
            out.write(ch.code)
            i++
        }
    }
    return out.toByteArray().toString(charset)
}

internal fun decodeTransfer(bytes: ByteArray, encoding: String): ByteArray =
    decodeTransferOrNull(bytes, encoding) ?: bytes

internal fun decodeTransferOrNull(bytes: ByteArray, encoding: String): ByteArray? {
    val token = encoding.substringBefore(';').trim().lowercase()
    return try {
        when (token) {
            "base64" -> Base64.getMimeDecoder().decode(bytes)
            "quoted-printable" -> decodeQuotedPrintable(bytes)
            else -> bytes
        }
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun decodeQuotedPrintable(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream()
    var i = 0
    while (i < bytes.size) {
        val c = bytes[i]
        if (c == '='.code.toByte() && i + 1 < bytes.size) {
            val next = bytes[i + 1]
            if (next == '\n'.code.toByte()) {
                i += 2
                continue
            }
            if (next == '\r'.code.toByte()) {
                i += if (i + 2 < bytes.size && bytes[i + 2] == '\n'.code.toByte()) 3 else 2
                continue
            }
            if (i + 2 < bytes.size) {
                val hex = bytes.copyOfRange(i + 1, i + 3).toString(Charsets.US_ASCII)
                val value = hexByteOrNull(hex)
                if (value != null) {
                    out.write(value)
                    i += 3
                    continue
                }
            }
        }
        out.write(c.toInt())
        i++
    }
    return out.toByteArray()
}

internal fun charsetOf(contentType: String): Charset {
    val name = headerParam(contentType, "charset")
    if (name.isEmpty()) return Charsets.ISO_8859_1
    return try {
        Charset.forName(name)
    } catch (_: IllegalArgumentException) {
        Charsets.ISO_8859_1
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

private fun readMultipart(body: ByteArray, contentType: String): Pair<String, List<OutgoingPart>> {
    if (headerParam(contentType, "boundary").isEmpty()) return body.toString(Charsets.UTF_8) to emptyList()
    val found = readMultipartFound(body, contentType)
    return (found.first ?: "") to found.second
}

private fun readMultipartFound(body: ByteArray, contentType: String): Pair<String?, List<OutgoingPart>> {
    val boundary = headerParam(contentType, "boundary")
    if (boundary.isEmpty()) return null to emptyList()
    var text: String? = null
    val parts = ArrayList<OutgoingPart>()
    for (chunk in splitMultipartChunks(body, boundary)) {
        val part = parseRfc822(chunk)
        val type = rawHeader(part, "Content-Type")
        val cte = headerValues(part, "Content-Transfer-Encoding").firstOrNull().orEmpty()
        val media = type.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
        if (media.startsWith("multipart/", ignoreCase = true)) {
            val nested = readMultipartFound(part.body, type)
            if (text == null && nested.first != null) text = nested.first
            parts.addAll(nested.second)
        } else if (text == null && media.equals("text/plain", ignoreCase = true)) {
            val decoded = decodeTransfer(part.body, cte)
            text = decoded.toString(charsetOf(type)).replace("\r\n", "\n").replace('\r', '\n')
        } else {
            val decoded = decodeTransfer(part.body, cte)
            parts.add(OutgoingPart(filenameOf(part), media, decoded, wireBase64 = false))
        }
    }
    return text to parts
}

private fun rawHeader(part: ParsedRfc822, name: String): String =
    part.headers.firstOrNull { it.name.equals(name, ignoreCase = true) }?.raw.orEmpty()

private fun filenameOf(part: ParsedRfc822): String {
    val disposition = rawHeader(part, "Content-Disposition")
    val star = readParam(disposition, "filename", star = true)
    if (star != null) {
        val decoded = decode2231(star)
        if (decoded.isNotEmpty()) return decoded
    }
    val fromDisposition = decodeHeaderWords(headerParam(disposition, "filename"))
    if (fromDisposition.isNotEmpty()) return fromDisposition
    val fromType = decodeHeaderWords(headerParam(rawHeader(part, "Content-Type"), "name"))
    if (fromType.isNotEmpty()) return fromType
    return "attachment"
}

private fun decode2231(raw: String): String {
    val first = raw.indexOf('\'')
    val second = if (first >= 0) raw.indexOf('\'', first + 1) else -1
    val charsetName: String
    val encoded: String
    if (first >= 0 && second > first) {
        charsetName = raw.substring(0, first)
        encoded = raw.substring(second + 1)
    } else {
        charsetName = "UTF-8"
        encoded = raw
    }
    val charset = try {
        if (charsetName.isEmpty()) Charsets.UTF_8 else Charset.forName(charsetName)
    } catch (_: Exception) {
        Charsets.UTF_8
    }
    val out = ByteArrayOutputStream()
    var i = 0
    while (i < encoded.length) {
        val c = encoded[i]
        if (c == '%' && i + 2 < encoded.length) {
            val value = hexByteOrNull(encoded.substring(i + 1, i + 3))
            if (value != null) {
                out.write(value)
                i += 3
                continue
            }
        }
        out.write(c.toString().toByteArray(Charsets.UTF_8))
        i++
    }
    return out.toByteArray().toString(charset)
}

private fun splitMultipartChunks(body: ByteArray, boundary: String): List<ByteArray> {
    val marker = "--$boundary".toByteArray(Charsets.US_ASCII)
    val starts = ArrayList<Int>()
    var i = 0
    while (i + marker.size <= body.size) {
        if (matchesAt(body, i, marker) && atLineStart(body, i)) {
            starts.add(i)
            i += marker.size
        } else {
            i++
        }
    }
    val chunks = ArrayList<ByteArray>()
    for (index in starts.indices) {
        var begin = starts[index] + marker.size
        if (begin + 1 < body.size &&
            body[begin] == '-'.code.toByte() &&
            body[begin + 1] == '-'.code.toByte()
        ) {
            break
        }
        if (begin < body.size && body[begin] == '\r'.code.toByte()) begin++
        if (begin < body.size && body[begin] == '\n'.code.toByte()) begin++
        val end = if (index + 1 < starts.size) starts[index + 1] else body.size
        var finish = end
        if (finish >= 2 &&
            body[finish - 2] == '\r'.code.toByte() &&
            body[finish - 1] == '\n'.code.toByte()
        ) {
            finish -= 2
        } else if (finish >= 1 && body[finish - 1] == '\n'.code.toByte()) {
            finish -= 1
        }
        if (begin < finish) chunks.add(body.copyOfRange(begin, finish))
    }
    return chunks
}

private fun matchesAt(data: ByteArray, offset: Int, needle: ByteArray): Boolean {
    if (offset < 0 || offset + needle.size > data.size) return false
    for (i in needle.indices) {
        if (data[offset + i] != needle[i]) return false
    }
    return true
}

private fun atLineStart(data: ByteArray, offset: Int): Boolean {
    if (offset <= 0) return true
    return data[offset - 1] == '\n'.code.toByte()
}

/** decodeHeaderWords for the reader's header view (one header line at a time). */
fun decodeHeaderWordsForView(line: String): String = decodeHeaderWords(line)

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
