package org.dlang.liveimap.engine

import java.io.File

/**
 * IMAP traffic file. Native code hands each chunk here.
 * Lines are `<epoch-ms> <connection-id> <text>`.
 */
class TrafficLog(
    val file: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    recording: Boolean = true,
    private val maxBytes: Long = MAX_BYTES.toLong(),
) {
    private var recordingEnabled = recording
    private val lines = ArrayDeque<String>()
    private var storedBytes = 0L
    private val conns = HashMap<String, Conn>()

    fun setRecording(on: Boolean) {
        synchronized(this) {
            recordingEnabled = on
        }
    }

    fun accept(connectionId: String, logType: Int, data: ByteArray) {
        synchronized(this) {
            if (!recordingEnabled || data.isEmpty()) return
            val conn = conns.getOrPut(connectionId) { Conn() }
            when (logType) {
                LOG_PRIVATE -> store(connectionId, "C <redacted ${data.size} bytes>")
                LOG_SENT -> take(connectionId, conn, conn.sent, "C", data)
                LOG_RECEIVED -> take(connectionId, conn, conn.received, "S", data)
            }
        }
    }

    fun note(connectionId: String, text: String) {
        synchronized(this) {
            if (!recordingEnabled || text.isEmpty()) return
            store(connectionId, text)
        }
    }

    fun flush() {
        synchronized(this) {
            if (!recordingEnabled) return
            for ((id, conn) in conns) {
                flushHalf(id, conn, conn.sent, "C")
                flushHalf(id, conn, conn.received, "S")
            }
        }
    }

    private fun flushHalf(connectionId: String, conn: Conn, half: Half, prefix: String) {
        if (half.literalLeft > 0 || half.pending.isEmpty()) return
        val line = half.pending.toString()
        half.pending.setLength(0)
        onLine(connectionId, conn, half, prefix, line)
    }

    private fun take(
        connectionId: String,
        conn: Conn,
        half: Half,
        prefix: String,
        data: ByteArray,
    ) {
        var i = 0
        while (i < data.size) {
            if (half.literalLeft > 0) {
                val skip = minOf(half.literalLeft, (data.size - i).toLong()).toInt()
                half.literalLeft -= skip
                i += skip
                if (half.literalLeft == 0L) {
                    store(connectionId, "$prefix <literal ${half.literalTotal} bytes>")
                }
                continue
            }
            val b = data[i].toInt() and 0xFF
            i++
            if (b == '\n'.code) {
                if (half.pending.isNotEmpty() && half.pending[half.pending.length - 1] == '\r') {
                    half.pending.setLength(half.pending.length - 1)
                }
                val line = half.pending.toString()
                half.pending.setLength(0)
                onLine(connectionId, conn, half, prefix, line)
            } else {
                half.pending.append(b.toChar())
            }
        }
    }

    private fun onLine(
        connectionId: String,
        conn: Conn,
        half: Half,
        prefix: String,
        raw: String,
    ) {
        val shown = printable(raw)
        if (prefix == "S" && (shown == "+" || shown.startsWith("+ "))) {
            conn.saslArmed = true
        }
        val text = if (prefix == "C") redactSent(conn, shown) else shown
        if (text.isNotEmpty()) {
            store(connectionId, "$prefix $text")
        }
        if (prefix == "C" && text.startsWith("<redacted ")) return
        val literal = literalSize(text)
        if (literal != null) {
            half.literalTotal = literal
            half.literalLeft = literal
            if (literal == 0L) {
                store(connectionId, "$prefix <literal 0 bytes>")
            }
        }
    }

    private fun redactSent(conn: Conn, line: String): String {
        val tokens = imapTokens(line)
        val command = tokens.size >= 2 && tokens[1].all { it.isLetter() }
        if (tokens.size >= 2 && tokens[1].equals("AUTHENTICATE", ignoreCase = true)) {
            if (tokens.size >= 4) {
                conn.saslArmed = false
                return redactTail(line, tokens.subList(3, tokens.size).joinToString(" "))
            }
            conn.saslArmed = true
            return line
        }
        if (conn.saslArmed && !command) {
            conn.saslArmed = false
            return "<redacted ${line.length} bytes>"
        }
        if (command) conn.saslArmed = false
        if (tokens.size >= 4 && tokens[1].equals("LOGIN", ignoreCase = true)) {
            return redactTail(line, tokens.subList(3, tokens.size).joinToString(" "))
        }
        return line
    }

    private fun redactTail(line: String, secret: String): String {
        if (secret.isEmpty()) return line
        val at = line.lastIndexOf(secret)
        if (at < 0) return line
        return line.substring(0, at).trimEnd() + " <redacted ${secret.length} bytes>"
    }

    private fun store(connectionId: String, text: String) {
        val probe = "0 $connectionId $text"
        if (lines.isNotEmpty() && bodyOf(lines.last()) == bodyOf(probe)) {
            val kept = lines.last()
            val count = repeatsOf(kept) + 1
            val stamp = kept.substringBefore(' ')
            val id = kept.substringAfter(' ').substringBefore(' ')
            val updated = "$stamp $id $text (repeated $count times)"
            storedBytes -= utf8Line(kept)
            lines.removeLast()
            lines.addLast(updated)
            storedBytes += utf8Line(updated)
            persist(rewriteLast = true)
            return
        }
        val line = "${clock()} $connectionId $text"
        lines.addLast(line)
        storedBytes += utf8Line(line)
        persist(rewriteLast = false)
    }

    private fun persist(rewriteLast: Boolean) {
        if (storedBytes > maxBytes) {
            val target = if (maxBytes > 64 * 1024) maxBytes - 64 * 1024 else 0L
            while (lines.isNotEmpty() && storedBytes > target) {
                storedBytes -= utf8Line(lines.removeFirst())
            }
            writeAll()
            return
        }
        if (rewriteLast) {
            writeAll()
            return
        }
        appendLine(lines.last())
    }

    private fun appendLine(line: String) {
        file.parentFile?.mkdirs()
        file.appendBytes((line + "\n").toByteArray(Charsets.UTF_8))
    }

    private fun writeAll() {
        file.parentFile?.mkdirs()
        file.writeText(buildString {
            for (line in lines) {
                append(line)
                append('\n')
            }
        })
    }

    private class Half {
        val pending = StringBuilder()
        var literalLeft = 0L
        var literalTotal = 0L
    }

    private class Conn {
        val sent = Half()
        val received = Half()
        var saslArmed = false
    }

    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        const val LOG_RECEIVED = 5
        const val LOG_SENT = 6
        const val LOG_PRIVATE = 7

        @Volatile
        private var active: TrafficLog? = null

        fun install(file: File): TrafficLog {
            val current = active
            if (current != null && current.file.absolutePath == file.absolutePath) {
                deleteRotated(file)
                return current
            }
            val created = TrafficLog(file)
            active = created
            deleteRotated(file)
            return created
        }

        fun setRecording(on: Boolean) {
            active?.setRecording(on)
        }

        @JvmStatic
        fun acceptNative(connectionId: String, logType: Int, data: ByteArray) {
            active?.accept(connectionId, logType, data)
        }

        @JvmStatic
        fun flushNative() {
            active?.flush()
        }

        @JvmStatic
        fun noteNative(connectionId: String, text: String) {
            active?.note(connectionId, text)
        }

        private fun deleteRotated(file: File) {
            val parent = file.parentFile ?: return
            File(parent, file.name + ".1").delete()
        }
    }
}

internal fun imapTokens(line: String): List<String> {
    val out = ArrayList<String>()
    var i = 0
    while (i < line.length) {
        while (i < line.length && line[i] == ' ') i++
        if (i >= line.length) break
        if (line[i] == '"') {
            val start = i
            i++
            while (i < line.length) {
                if (line[i] == '\\' && i + 1 < line.length) {
                    i += 2
                    continue
                }
                if (line[i] == '"') {
                    i++
                    break
                }
                i++
            }
            out.add(line.substring(start, i))
        } else {
            val start = i
            while (i < line.length && line[i] != ' ') i++
            out.add(line.substring(start, i))
        }
    }
    return out
}

private fun printable(raw: String): String {
    val out = StringBuilder(raw.length)
    for (ch in raw) {
        val c = ch.code
        if (c == 9 || c in 0x20..0x7E) out.append(ch) else out.append('.')
    }
    return out.toString()
}

private val literalBrace = Regex("""\{(\d+)[+-]?\}$""")
private val literalTilde = Regex("""~\{(\d+)\}$""")

private fun literalSize(line: String): Long? {
    val match = literalBrace.find(line) ?: literalTilde.find(line) ?: return null
    return match.groupValues[1].toLongOrNull()
}

private fun utf8Line(line: String): Long = line.toByteArray(Charsets.UTF_8).size.toLong() + 1L

private val repeatSuffix = Regex(""" \(repeated (\d+) times\)$""")

private fun bodyOf(stored: String): String {
    val rest = stored.substringAfter(' ').substringAfter(' ')
    return rest.replace(repeatSuffix, "")
}

private fun repeatsOf(stored: String): Int {
    val match = repeatSuffix.find(stored) ?: return 1
    return match.groupValues[1].toIntOrNull() ?: 1
}
