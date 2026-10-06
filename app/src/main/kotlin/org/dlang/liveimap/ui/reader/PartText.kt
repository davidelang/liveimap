package org.dlang.liveimap.ui.reader

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.util.Base64
import org.dlang.liveimap.ui.compose.decodeTransfer

const val unknownCharsetNote = "Unknown charset; shown as ISO-8859-1."

data class DecodedPart(
    val text: String,
    val unknownCharset: Boolean,
)

fun decodePart(bytes: ByteArray, charset: String, encoding: String): DecodedPart {
    val binary = decodeTransfer(bytes, encoding)
    val resolved = resolveCharset(charset)
    return DecodedPart(decodeReplacing(binary, resolved.charset, end = true), resolved.unknown)
}

class WireTextDecoder(charsetName: String, encoding: String) {
    private val token = transferToken(encoding)
    private val resolved = resolveCharset(charsetName)
    private val decoder: CharsetDecoder = resolved.charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    val unknownCharset: Boolean = resolved.unknown
    private var wire = ByteArray(0)
    private var pendingBinary = ByteArray(0)

    fun take(chunk: ByteArray): String {
        if (chunk.isNotEmpty()) wire = concat(wire, chunk)
        val ready = takeReady(end = false)
        return decodeReady(ready, end = false)
    }

    fun finish(): String {
        val ready = takeReady(end = true)
        return decodeReady(ready, end = true)
    }

    private fun takeReady(end: Boolean): ByteArray {
        if (!end) {
            val (ready, rest) = when (token) {
                "base64" -> splitBase64(wire)
                "quoted-printable" -> splitQuotedPrintable(wire)
                else -> wire to ByteArray(0)
            }
            wire = rest
            if (ready.isEmpty()) return ByteArray(0)
            return if (token == "base64" || token == "quoted-printable") decodeTransfer(ready, token) else ready
        }
        val rest = wire
        wire = ByteArray(0)
        if (rest.isEmpty()) return ByteArray(0)
        if (token == "base64") {
            return try {
                Base64.getMimeDecoder().decode(rest)
            } catch (_: IllegalArgumentException) {
                ByteArray(0)
            }
        }
        if (token == "quoted-printable") return decodeTransfer(rest, token)
        return rest
    }

    private fun decodeReady(binary: ByteArray, end: Boolean): String {
        val all = concat(pendingBinary, binary)
        if (all.isEmpty() && !end) return ""
        val input = ByteBuffer.wrap(all)
        val text = StringBuilder()
        val output = CharBuffer.allocate((all.size + 8).coerceAtLeast(16))
        while (true) {
            val result = decoder.decode(input, output, end)
            output.flip()
            text.append(output)
            output.clear()
            if (!result.isOverflow) break
        }
        if (end) {
            while (true) {
                val result = decoder.flush(output)
                output.flip()
                text.append(output)
                output.clear()
                if (!result.isOverflow) break
            }
            pendingBinary = ByteArray(0)
        } else {
            val rest = ByteArray(input.remaining())
            input.get(rest)
            pendingBinary = rest
        }
        return text.toString()
    }
}

private data class ResolvedCharset(val charset: Charset, val unknown: Boolean)

private fun resolveCharset(name: String): ResolvedCharset {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return ResolvedCharset(Charsets.ISO_8859_1, true)
    return try {
        ResolvedCharset(Charset.forName(trimmed), false)
    } catch (_: IllegalArgumentException) {
        ResolvedCharset(Charsets.ISO_8859_1, true)
    }
}

private fun decodeReplacing(bytes: ByteArray, charset: Charset, end: Boolean): String {
    if (bytes.isEmpty()) return ""
    val decoder = charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    val input = ByteBuffer.wrap(bytes)
    val output = CharBuffer.allocate((bytes.size + 8).coerceAtLeast(16))
    val text = StringBuilder()
    while (true) {
        val result = decoder.decode(input, output, end)
        output.flip()
        text.append(output)
        output.clear()
        if (!result.isOverflow) break
    }
    while (true) {
        val result = decoder.flush(output)
        output.flip()
        text.append(output)
        output.clear()
        if (!result.isOverflow) break
    }
    return text.toString()
}

private fun transferToken(encoding: String): String =
    encoding.substringBefore(';').trim().lowercase()

private fun concat(left: ByteArray, right: ByteArray): ByteArray {
    if (left.isEmpty()) return right
    if (right.isEmpty()) return left
    val out = ByteArray(left.size + right.size)
    left.copyInto(out)
    right.copyInto(out, left.size)
    return out
}

private fun isBase64Char(c: Int): Boolean {
    return c == '='.code ||
        c in 'A'.code..'Z'.code ||
        c in 'a'.code..'z'.code ||
        c in '0'.code..'9'.code ||
        c == '+'.code ||
        c == '/'.code
}

private fun splitBase64(bytes: ByteArray): Pair<ByteArray, ByteArray> {
    var count = 0
    var safe = 0
    for (i in bytes.indices) {
        val c = bytes[i].toInt() and 0xFF
        val whitespace = c == ' '.code || c == '\t'.code || c == '\r'.code || c == '\n'.code
        if (whitespace || !isBase64Char(c)) {
            if (count % 4 == 0) safe = i + 1
            continue
        }
        count++
        if (count % 4 == 0) safe = i + 1
    }
    return bytes.copyOfRange(0, safe) to bytes.copyOfRange(safe, bytes.size)
}

private fun splitQuotedPrintable(bytes: ByteArray): Pair<ByteArray, ByteArray> {
    var i = 0
    var safe = 0
    while (i < bytes.size) {
        if (bytes[i] != '='.code.toByte()) {
            i++
            safe = i
            continue
        }
        if (i + 1 >= bytes.size) break
        val next = bytes[i + 1]
        if (next == '\n'.code.toByte()) {
            i += 2
            safe = i
            continue
        }
        if (next == '\r'.code.toByte()) {
            if (i + 2 >= bytes.size) break
            i += if (bytes[i + 2] == '\n'.code.toByte()) 3 else 2
            safe = i
            continue
        }
        if (i + 2 >= bytes.size) break
        val hex = bytes.copyOfRange(i + 1, i + 3).toString(Charsets.US_ASCII)
        if (hex.toIntOrNull(16) != null) {
            i += 3
            safe = i
        } else {
            i += 1
            safe = i
        }
    }
    return bytes.copyOfRange(0, safe) to bytes.copyOfRange(safe, bytes.size)
}
