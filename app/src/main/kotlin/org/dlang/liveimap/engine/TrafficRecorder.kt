package org.dlang.liveimap.engine

import java.io.File
import java.util.Base64
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

/**
 * Lab recording. [start] notes the time. [stop] merges this run's events with traffic
 * files written at or after that time.
 */
class TrafficRecorder(
    val file: File,
    header: RunHeader,
    private val trafficDir: File?,
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

    private val started = System.currentTimeMillis()
    private val events = Channel<String>(Channel.UNLIMITED)
    private val lines = ArrayList<Pair<Long, String>>()
    private val thread: Thread

    init {
        file.parentFile?.mkdirs()
        val h = header
        file.writeText(
            "{\"type\":\"run\",\"format\":1,\"app\":${jq(h.app)},\"commit\":${jq(h.commit)}," +
                "\"tool\":${jq(h.tool)},\"server\":{\"name\":${jq(h.serverName)},\"host\":${jq(h.host)}," +
                "\"port\":${h.port},\"tls\":${jq(h.tls)}},\"protocol\":${jq(h.protocol)}," +
                "\"started\":$started,\"suite\":${jq(h.suite)}}\n",
        )
        thread = Thread({
            runBlocking {
                for (line in events) {
                    val t = line.substringAfter("\"t\":").substringBefore(',').toLongOrNull() ?: started
                    synchronized(lines) { lines.add(t to line) }
                }
            }
        }, "traffic-recorder").apply { isDaemon = true; start() }
    }

    /** Connection events: connect, tls, starttls, close, error, or a native note. */
    fun event(conn: String, kind: String, detail: String = "") {
        val t = System.currentTimeMillis()
        events.trySend(
            "{\"t\":$t,\"conn\":${jq(conn)},\"dir\":\"E\",\"event\":${jq(kind)},\"line\":${jq(detail)}}",
        )
    }

    fun finish() {
        events.close()
        thread.join()
        val merged = ArrayList<Pair<Long, String>>()
        synchronized(lines) { merged.addAll(lines) }
        val dir = trafficDir
        if (dir != null && dir.isDirectory) {
            for (record in TrafficLog.orderedRecords(dir, started)) {
                val b64 = Base64.getEncoder().encodeToString(record.bytes)
                merged.add(
                    record.t to
                        "{\"t\":${record.t},\"conn\":${jq(record.conn)},\"dir\":${jq(record.dir.toString())},\"b64\":${jq(b64)}}",
                )
            }
        }
        merged.sortBy { it.first }
        for ((_, line) in merged) file.appendText(line + "\n")
    }

    companion object {
        @Volatile
        var active: TrafficRecorder? = null
            private set

        fun start(file: File, header: RunHeader, cacheDir: File, accountId: String): TrafficRecorder {
            val r = TrafficRecorder(file, header, TrafficLog.trafficLogDir(cacheDir, accountId))
            active = r
            return r
        }

        fun stop() {
            active?.finish()
            active = null
        }

        fun event(conn: String, kind: String, detail: String = "") {
            active?.event(conn, kind, detail)
        }
    }
}

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
