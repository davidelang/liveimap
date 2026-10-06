package org.dlang.liveimap.ui.compose

import org.dlang.liveimap.session.MimePart
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID

class HeaderField(val name: String, val value: String)

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

fun textPart(root: MimePart, subtype: String): MimePart? {
    if (root.type.equals("multipart", ignoreCase = true) ||
        (root.type.equals("message", ignoreCase = true) && root.children.isNotEmpty())
    ) {
        for (child in root.children) {
            val found = textPart(child, subtype)
            if (found != null) return found
        }
        return null
    }
    if (root.type.equals("text", ignoreCase = true) && root.subtype.equals(subtype, ignoreCase = true)) {
        return root
    }
    for (child in root.children) {
        val found = textPart(child, subtype)
        if (found != null) return found
    }
    return null
}

fun attachmentParts(root: MimePart): List<MimePart> {
    val out = ArrayList<MimePart>()
    fun walk(part: MimePart) {
        if (part.type.equals("multipart", ignoreCase = true)) {
            part.children.forEach { walk(it) }
            return
        }
        if (part.type.equals("message", ignoreCase = true) && part.children.isNotEmpty()) {
            part.children.forEach { walk(it) }
            return
        }
        val attachment = part.disposition.equals("attachment", ignoreCase = true)
        val textBody = part.type.equals("text", ignoreCase = true) &&
            (part.subtype.equals("plain", ignoreCase = true) || part.subtype.equals("html", ignoreCase = true)) &&
            !attachment
        if (!textBody) out.add(part)
    }
    walk(root)
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
        val value = decodeHeaderWords(line.substring(colon + 1).trim())
        if (name.isNotEmpty()) headers.add(HeaderField(name, value))
    }
    return ParsedRfc822(headers, body)
}

fun headerValues(message: ParsedRfc822, name: String): List<String> =
    message.headers.filter { it.name.equals(name, ignoreCase = true) }.map { it.value }

fun addresses(message: ParsedRfc822, name: String): List<String> =
    headerValues(message, name).flatMap { splitAddresses(it) }

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
        ),
        peekedText,
    )
    return ReplyDraft(to, cc, subject, messageId, references, body)
}

fun forwardDraft(message: ParsedRfc822, peekedText: String): ReplyDraft {
    val subject = markedSubject(headerValues(message, "Subject").firstOrNull().orEmpty(), "Fwd:")
    val body = quotePart(
        attributionLine(
            headerValues(message, "Date").firstOrNull().orEmpty(),
            headerValues(message, "From").firstOrNull().orEmpty(),
        ),
        peekedText,
    )
    return ReplyDraft(emptyList(), emptyList(), subject, "", "", body)
}

fun attributionLine(date: String, from: String): String =
    "On ${date.trim()}, ${from.trim()} wrote:"

fun quotePart(attribution: String, partText: String): String {
    val normalized = partText.replace("\r\n", "\n").replace('\r', '\n')
    val quoted = normalized.split('\n').joinToString("\n") { line -> "> $line" }
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
    if (display.any { it.code > 126 }) return "${encodedWord(display)} <$box>"
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

/** Bcc is not a header. Those addresses are only SMTP envelope recipients. */
fun buildPlain(message: PlainMessage): BuiltMail {
    val recipients = envelopeRecipients(message.to, message.cc, message.bcc)
    val body = normalizeNewlines(message.body)
    val bytes = if (message.attachments.isEmpty()) {
        val headers = baseHeaders(message, "text/plain; charset=utf-8", transferEncoding(body))
        val out = ByteArrayOutputStream()
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
        out.write(body.toByteArray(Charsets.UTF_8))
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
            "text/plain; charset=utf-8",
            transferEncoding(body),
            null,
            body.toByteArray(Charsets.UTF_8),
        )
        for (part in message.attachments) {
            val encoded = if (part.wireBase64) part.bytes else mimeBase64(part.bytes)
            val type = cleanHeader(part.mediaType).ifEmpty { "application/octet-stream" }
            val file = cleanHeader(part.filename).ifEmpty { "attachment" }
            val quoted = escapeQuotes(file)
            writePart(
                out,
                boundary,
                "$type; name=\"$quoted\"",
                "base64",
                "attachment; filename=\"$quoted\"",
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

private fun transferEncoding(body: String): String {
    val bytes = body.toByteArray(Charsets.UTF_8)
    return if (bytes.any { (it.toInt() and 0xFF) > 127 }) "8bit" else "7bit"
}

private fun mimeBase64(bytes: ByteArray): ByteArray {
    val encoder = Base64.getMimeEncoder(76, byteArrayOf('\r'.code.toByte(), '\n'.code.toByte()))
    return encoder.encode(bytes)
}

private fun escapeQuotes(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

private fun cleanHeader(value: String): String =
    value.replace("\r", "").replace("\n", "")

private fun headerText(value: String): String {
    val clean = cleanHeader(value)
    if (clean.all { it.code in 32..126 }) return clean
    return encodedWord(clean)
}

private fun encodedWord(value: String): String {
    val b64 = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
    return "=?UTF-8?B?$b64?="
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
            val value = hex.toIntOrNull(16)
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

internal fun decodeTransfer(bytes: ByteArray, encoding: String): ByteArray {
    val token = encoding.substringBefore(';').trim().lowercase()
    return try {
        when (token) {
            "base64" -> Base64.getMimeDecoder().decode(bytes)
            "quoted-printable" -> decodeQuotedPrintable(bytes)
            else -> bytes
        }
    } catch (_: IllegalArgumentException) {
        bytes
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
                val value = hex.toIntOrNull(16)
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

fun headerParam(header: String, name: String): String {
    val pattern = Regex("(?i)(?:^|;)\\s*" + Regex.escape(name) + "\\s*=\\s*(\"([^\"]*)\"|([^;\\s]+))")
    val match = pattern.find(header) ?: return ""
    if (match.groupValues[1].startsWith("\"")) return match.groupValues[2]
    return match.groupValues[3]
}

private fun readMultipart(body: ByteArray, contentType: String): Pair<String, List<OutgoingPart>> {
    val boundary = headerParam(contentType, "boundary")
    if (boundary.isEmpty()) return body.toString(Charsets.UTF_8) to emptyList()
    var text = ""
    var haveText = false
    val parts = ArrayList<OutgoingPart>()
    for (chunk in splitMultipartChunks(body, boundary)) {
        val part = parseRfc822(chunk)
        val type = headerValues(part, "Content-Type").firstOrNull().orEmpty()
        val cte = headerValues(part, "Content-Transfer-Encoding").firstOrNull().orEmpty()
        val decoded = decodeTransfer(part.body, cte)
        val media = type.substringBefore(';').trim().ifEmpty { "application/octet-stream" }
        if (!haveText && media.equals("text/plain", ignoreCase = true)) {
            text = decoded.toString(charsetOf(type)).replace("\r\n", "\n").replace('\r', '\n')
            haveText = true
        } else if (!media.startsWith("multipart/", ignoreCase = true)) {
            parts.add(OutgoingPart(filenameOf(part, type), media, decoded, wireBase64 = false))
        }
    }
    return text to parts
}

private fun filenameOf(part: ParsedRfc822, contentType: String): String {
    val disposition = headerValues(part, "Content-Disposition").firstOrNull().orEmpty()
    val fromDisposition = headerParam(disposition, "filename")
    if (fromDisposition.isNotEmpty()) return fromDisposition
    val fromType = headerParam(contentType, "name")
    if (fromType.isNotEmpty()) return fromType
    return "attachment"
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
