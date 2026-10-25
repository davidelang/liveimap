package org.dlang.liveimap.ui.reader

const val unknownCharsetNote = "Unknown charset; shown as ISO-8859-1."

data class DecodedPart(
    val text: String,
    val unknownCharset: Boolean,
    val note: String? = null,
)

internal object MimeDecode {
    init {
        System.loadLibrary("liveimap")
    }

    @JvmStatic
    external fun text(bytes: ByteArray, declared: String, fallback: String, encoding: String): Array<String>

    @JvmStatic
    external fun streamOpen(declared: String, fallback: String, encoding: String): Long

    @JvmStatic
    external fun streamTake(handle: Long, chunk: ByteArray, finalChunk: Boolean): Array<String>

    @JvmStatic
    external fun streamClose(handle: Long)

    @JvmStatic
    external fun xferOpen(encoding: String): Long

    @JvmStatic
    external fun xferTake(handle: Long, chunk: ByteArray, finalChunk: Boolean): ByteArray?

    @JvmStatic
    external fun xferClose(handle: Long)

    @JvmStatic
    external fun phrase(value: String, fallback: String = "ISO-8859-1"): Array<String>

    @JvmStatic
    external fun parameter2231(raw: String, fallback: String): String

    @JvmStatic
    external fun headers(bytes: ByteArray, fallback: String = "ISO-8859-1"): Array<String>
}

fun decodePart(bytes: ByteArray, charset: String, encoding: String, fallback: String = "ISO-8859-1"): DecodedPart {
    val row = MimeDecode.text(bytes, charset, fallback, encoding)
    if (row[0] != "ok") {
        throw org.dlang.liveimap.ui.compose.MimeDecodeException(row[2].ifEmpty { row[1] })
    }
    val note = row[2].ifEmpty { null }
    return DecodedPart(row[1], note?.contains("shown as") == true, note)
}

class WireTextDecoder(charsetName: String, encoding: String, fallback: String = "ISO-8859-1") {
    private val handle = MimeDecode.streamOpen(charsetName, fallback, encoding)
    var unknownCharset: Boolean = false
        private set
    var note: String? = null
        private set
    private var closed = false

    fun take(chunk: ByteArray): String = pull(chunk, end = false)

    fun finish(): String = pull(ByteArray(0), end = true)

    fun close() {
        if (closed) return
        closed = true
        MimeDecode.streamClose(handle)
    }

    protected fun finalize() {
        close()
    }

    private fun pull(chunk: ByteArray, end: Boolean): String {
        val row = MimeDecode.streamTake(handle, chunk, end)
        if (row[2] != "ok") {
            throw org.dlang.liveimap.ui.compose.MimeDecodeException(row[0].ifEmpty { "could not decode this part" })
        }
        if (row[1].isNotEmpty()) {
            note = row[1]
            unknownCharset = row[1].contains("shown as")
        }
        if (end) close()
        return row[0]
    }
}
