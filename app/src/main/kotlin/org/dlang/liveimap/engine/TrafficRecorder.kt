package org.dlang.liveimap.engine

import java.io.File
import java.util.Base64

/**
 * Recording mode for protocol traffic. One JSON-lines file per run.
 * Line 1 is the run header; every other line is one event.
 * Literals are kept in full (base64). Credentials are redacted.
 * Nothing is truncated or collapsed. tools/test-servers/record.py reads the same format.
 */
class TrafficRecorder(
    val file: File,
    header: RunHeader,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    data class RunHeader(
        val app: String,
        val commit: String,
        val tool: String,
        val serverName: String,
        val host: String,
        val port: Int,
        val tls: String,
        val protocol: String,
        val suite: String,
    )

    private val halves = HashMap<String, Half>()
    private val saslArmed = HashSet<String>()

    init {
        file.parentFile?.mkdirs()
        val h = header
        write(
            "{\"type\":\"run\",\"format\":1,\"app\":${jq(h.app)},\"commit\":${jq(h.commit)}," +
                "\"tool\":${jq(h.tool)},\"server\":{\"name\":${jq(h.serverName)},\"host\":${jq(h.host)}," +
                "\"port\":${h.port},\"tls\":${jq(h.tls)}},\"protocol\":${jq(h.protocol)}," +
                "\"started\":${clock()},\"suite\":${jq(h.suite)}}",
            truncate = true,
        )
    }

    @Synchronized
    fun accept(conn: String, logType: Int, data: ByteArray) {
        if (data.isEmpty()) return
        when (logType) {
            TrafficLog.LOG_PRIVATE -> line(conn, "C", null, data.size)
            TrafficLog.LOG_SENT -> take(conn, "C", data)
            TrafficLog.LOG_RECEIVED -> take(conn, "S", data)
        }
    }

    /** Connection events: connect, tls, starttls, close, error, or a native note. */
    @Synchronized
    fun event(conn: String, kind: String, detail: String = "") {
        write("{\"t\":${clock()},\"conn\":${jq(conn)},\"dir\":\"E\",\"event\":${jq(kind)},\"line\":${jq(detail)}}")
    }

    @Synchronized
    fun flush() {
        for ((key, half) in halves) {
            if (half.literalLeft == 0L && half.pending.size() > 0) {
                val (conn, dir) = key.split('\u0000')
                endLine(conn, dir, half)
            }
        }
    }

    private fun take(conn: String, dir: String, data: ByteArray) {
        val half = halves.getOrPut(conn + '\u0000' + dir) { Half() }
        var i = 0
        while (i < data.size) {
            if (half.literalLeft > 0) {
                val n = minOf(half.literalLeft, (data.size - i).toLong()).toInt()
                half.literal.write(data, i, n)
                half.literalLeft -= n
                i += n
                if (half.literalLeft == 0L) literal(conn, dir, half)
                continue
            }
            val b = data[i]
            i++
            if (b == '\n'.code.toByte()) {
                endLine(conn, dir, half)
            } else {
                half.pending.write(b.toInt())
            }
        }
    }

    private fun endLine(conn: String, dir: String, half: Half) {
        var bytes = half.pending.toByteArray()
        half.pending.reset()
        if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes = bytes.copyOf(bytes.size - 1)
        val text = String(bytes, Charsets.ISO_8859_1)
        val key = conn
        if (dir == "S" && (text == "+" || text.startsWith("+ "))) saslArmed.add(key)
        if (dir == "C") {
            val red = redact(key, text)
            if (red != null) {
                line(conn, dir, red.first, red.second)
                return
            }
        }
        line(conn, dir, text, 0)
        val size = literalSize(text)
        if (size != null) {
            half.literalLeft = size
            half.literal.reset()
            if (size == 0L) literal(conn, dir, half)
        }
    }

    /** Returns (kept text, redacted bytes) when the line carries a credential. */
    private fun redact(key: String, text: String): Pair<String?, Int>? {
        val tokens = imapTokens(text)
        val cmd = tokens.getOrNull(1)?.uppercase()
        val isCommand = tokens.size >= 2 && tokens[1].all { it.isLetter() }
        if (cmd == "AUTHENTICATE") {
            if (tokens.size >= 4) {
                saslArmed.remove(key)
                return tokens.subList(0, 3).joinToString(" ") to (text.length - tokens.subList(0, 3).joinToString(" ").length)
            }
            saslArmed.add(key)
            return null
        }
        if (saslArmed.contains(key) && !isCommand) {
            saslArmed.remove(key)
            return null to text.length
        }
        if (isCommand) saslArmed.remove(key)
        if (cmd == "LOGIN" && tokens.size >= 3) {
            val kept = tokens.subList(0, 2).joinToString(" ")
            return kept to (text.length - kept.length)
        }
        if (tokens.size >= 3 && tokens[0].equals("AUTH", ignoreCase = true)) { // SMTP AUTH
            val kept = tokens.subList(0, 2).joinToString(" ")
            return kept to (text.length - kept.length)
        }
        return null
    }

    private fun line(conn: String, dir: String, text: String?, redacted: Int) {
        val body = if (text == null) "\"redacted\":$redacted" else {
            val r = if (redacted > 0) ",\"redacted\":$redacted" else ""
            "\"line\":${jq(text)}$r"
        }
        write("{\"t\":${clock()},\"conn\":${jq(conn)},\"dir\":${jq(dir)},$body}")
    }

    private fun literal(conn: String, dir: String, half: Half) {
        val b64 = Base64.getEncoder().encodeToString(half.literal.toByteArray())
        half.literal.reset()
        write("{\"t\":${clock()},\"conn\":${jq(conn)},\"dir\":${jq(dir)},\"literal_b64\":${jq(b64)}}")
    }

    private fun write(json: String, truncate: Boolean = false) {
        val bytes = (json + "\n").toByteArray(Charsets.UTF_8)
        if (truncate) file.writeBytes(bytes) else file.appendBytes(bytes)
    }

    private class Half {
        val pending = java.io.ByteArrayOutputStream()
        val literal = java.io.ByteArrayOutputStream()
        var literalLeft = 0L
    }

    companion object {
        @Volatile
        var active: TrafficRecorder? = null
            private set

        fun start(file: File, header: RunHeader): TrafficRecorder {
            val r = TrafficRecorder(file, header)
            active = r
            return r
        }

        fun stop() {
            active?.flush()
            active = null
        }

        fun event(conn: String, kind: String, detail: String = "") {
            active?.event(conn, kind, detail)
        }
    }
}

private val recLiteral = Regex("""~?\{(\d+)[+-]?\}$""")

private fun literalSize(line: String): Long? =
    recLiteral.find(line)?.groupValues?.get(1)?.toLongOrNull()

internal fun jq(s: String): String {
    val out = StringBuilder(s.length + 2)
    out.append('"')
    for (ch in s) {
        when {
            ch == '"' -> out.append("\\\"")
            ch == '\\' -> out.append("\\\\")
            ch == '\n' -> out.append("\\n")
            ch == '\r' -> out.append("\\r")
            ch == '\t' -> out.append("\\t")
            ch.code < 0x20 || ch.code in 0x7F..0xFF -> out.append(String.format("\\u%04x", ch.code))
            else -> out.append(ch)
        }
    }
    out.append('"')
    return out.toString()
}
