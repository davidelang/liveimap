package org.dlang.liveimap.engine

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

class MailCharconv(val code: Int, val bytes: ByteArray)

fun mailCharconv(toCode: String, fromCode: String, bytes: ByteArray): MailCharconv {
    val to = charsetOrNull(toCode) ?: return MailCharconv(1, ByteArray(0))
    val from = charsetOrNull(fromCode) ?: return MailCharconv(1, ByteArray(0))
    if (bytes.isEmpty()) return MailCharconv(0, ByteArray(0))
    val decoded = from.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
        .decode(ByteBuffer.wrap(bytes))
    val encoded = to.newEncoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
        .encode(decoded)
    val out = ByteArray(encoded.remaining())
    encoded.get(out)
    return MailCharconv(0, out)
}

private fun charsetOrNull(name: String): Charset? {
    if (name.isBlank()) return null
    return try {
        Charset.forName(name)
    } catch (_: IllegalArgumentException) {
        null
    }
}
