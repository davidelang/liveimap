package org.dlang.liveimap.ui.reader

import java.util.concurrent.atomic.AtomicBoolean

/** One decode from [MimeDecode.text] or [MimeDecode.streamTake]. */
@androidx.annotation.Keep
class MimeTextResult(
    val ok: Boolean,
    val text: String,
    val charsetUsed: String,
    val unknownCharset: Boolean,
    val unknown: Boolean,
)

data class DecodedPart(
    val text: String,
    val unknownCharset: Boolean,
    val charsetUsed: String,
    val unknown: Boolean = false,
)

internal object MimeDecode {
    init {
        System.loadLibrary("liveimap")
    }

    @JvmStatic
    external fun text(bytes: ByteArray, declared: String, fallback: String, encoding: String): MimeTextResult

    @JvmStatic
    external fun streamOpen(declared: String, fallback: String, encoding: String): Long

    @JvmStatic
    external fun streamTake(handle: Long, chunk: ByteArray, finalChunk: Boolean): MimeTextResult

    @JvmStatic
    external fun streamClose(handle: Long)

    @JvmStatic
    external fun xferOpen(encoding: String): Long

    @JvmStatic
    external fun xferTake(handle: Long, chunk: ByteArray, finalChunk: Boolean): ByteArray?

    @JvmStatic
    external fun xferClose(handle: Long)

    @JvmStatic
    external fun phrase(value: String, fallback: String): Array<String>

    @JvmStatic
    external fun phrases(values: Array<String>, fallback: String): Array<String>

    @JvmStatic
    external fun parameter2231(raw: String, fallback: String): String

    @JvmStatic
    external fun headers(bytes: ByteArray, fallback: String): Array<String>

    /** Child parts of a multipart message, each the part's own headers and body. Parsed by libetpan. */
    @JvmStatic
    external fun multipartParts(bytes: ByteArray): Array<ByteArray>
}

/** Digits and dots, matching nativePeekPart: HEADER and * are not numeric sections. */
internal fun imapNumericSection(section: String): Boolean {
    if (section.isEmpty()) return false
    var digit = false
    for (c in section) {
        if (c in '0'..'9') digit = true
        else if (c != '.') return false
    }
    return digit
}

/** BINARY.PEEK of a numeric section already removed the transfer encoding. */
internal fun serverStrippedTransferEncoding(fetchKind: String, section: String): Boolean =
    fetchKind == "BinaryPeek" && imapNumericSection(section)

/** Encoding to hand to [WireTextDecoder] or [MimeDecode.xferOpen]. Empty is identity. */
internal fun fetchedTransferEncoding(fetchKind: String, section: String, partEncoding: String): String =
    if (serverStrippedTransferEncoding(fetchKind, section)) "" else partEncoding

/**
 * A BINARY.PEEK that ended under [cap] is the whole decoded part. [partSize] is the encoded
 * BODYSTRUCTURE size and does not truncate that part.
 */
internal fun transferStrippedTruncated(stripped: Boolean, partSize: Int, cap: Int, hitCap: Boolean): Boolean {
    if (hitCap) return true
    if (stripped) return false
    return partSize > cap
}

/** More bytes only while the decoded read is short of its end. A finished BINARY.PEEK is not more. */
internal fun transferStrippedHasMore(
    stripped: Boolean,
    partSize: Int,
    decodedOffset: Int,
    cap: Int,
    truncated: Boolean,
): Boolean {
    if (decodedOffset >= cap) return false
    if (stripped) return truncated
    return partSize > 0 && decodedOffset < partSize
}

fun decodePart(bytes: ByteArray, charset: String, encoding: String, fallback: String): DecodedPart {
    val row = MimeDecode.text(bytes, charset, fallback, encoding)
    if (!row.ok) {
        throw org.dlang.liveimap.ui.compose.MimeDecodeException(row.text.ifEmpty { "could not decode this part" })
    }
    return DecodedPart(row.text, row.unknownCharset, row.charsetUsed, row.unknown)
}

class WireTextDecoder(charsetName: String, encoding: String, fallback: String) : AutoCloseable {
    private val handle = MimeDecode.streamOpen(charsetName, fallback, encoding)
    var unknownCharset: Boolean = false
        private set
    var charsetUsed: String = ""
        private set
    var unknown: Boolean = false
        private set
    private val closed = AtomicBoolean(false)

    fun take(chunk: ByteArray): String = pull(chunk, end = false)

    fun finish(): String = pull(ByteArray(0), end = true)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        MimeDecode.streamClose(handle)
    }

    private fun pull(chunk: ByteArray, end: Boolean): String {
        val row = MimeDecode.streamTake(handle, chunk, end)
        if (!row.ok) {
            throw org.dlang.liveimap.ui.compose.MimeDecodeException(row.text.ifEmpty { "could not decode this part" })
        }
        if (row.charsetUsed.isNotEmpty()) charsetUsed = row.charsetUsed
        if (row.unknownCharset) unknownCharset = true
        if (row.unknown) unknown = true
        if (end) close()
        return row.text
    }
}
