package org.dlang.liveimap.engine

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.BufferedInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.encode

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
    @Volatile private var recordingEnabled = recording
    private var stream: FileOutputStream? = null
    private var storedBytes = 0L
    private var lastDiskLine: String? = null
    private val repeatCounts = HashMap<String, Int>()
    private var pendingDrops = 0
    private val missed = AtomicInteger()
    private val conns = HashMap<String, Conn>()
    private val statusHistory = ArrayDeque<String>()
    private val capabilityLines = ArrayDeque<String>()

    fun setRecording(on: Boolean) {
        onWriter {
            recordingEnabled = on
            if (!on && active === this) statusFlow.value = ""
        }
    }

    fun accept(connectionId: String, logType: Int, data: ByteArray) {
        if (!recordingEnabled || data.isEmpty()) return
        if (tryWriter {
            if (!recordingEnabled) return@tryWriter
            val drops = pendingDrops + missed.getAndSet(0)
            pendingDrops = 0
            if (drops > 0) writeGap(connectionId, drops)
            acceptOnWriter(connectionId, logType, data)
        }) return
        if (!tryWriter { pendingDrops++ }) missed.incrementAndGet()
    }

    fun note(connectionId: String, text: String) {
        if (!recordingEnabled || text.isEmpty()) return
        if (tryWriter {
            if (!recordingEnabled) return@tryWriter
            val drops = pendingDrops + missed.getAndSet(0)
            pendingDrops = 0
            if (drops > 0) writeGap(connectionId, drops)
            store(connectionId, text)
        }) return
        if (!tryWriter { pendingDrops++ }) missed.incrementAndGet()
    }

    fun flush() {
        if (!recordingEnabled) return
        onWriter {
            if (!recordingEnabled) return@onWriter
            for ((id, conn) in conns) {
                flushHalf(id, conn, conn.sent, "C")
                flushHalf(id, conn, conn.received, "S")
            }
        }
    }

    fun noteStatus(text: String) {
        if (text.isEmpty()) return
        onWriter {
            if (!recordingEnabled) return@onWriter
            statusHistory.addLast(text)
            while (statusHistory.size > 100) statusHistory.removeFirst()
            if (active === this) statusFlow.value = text
        }
    }

    fun noteCapability(line: String) {
        if (line.isEmpty()) return
        onWriter {
            if (!recordingEnabled) return@onWriter
            capabilityLines.addLast(line)
            while (capabilityLines.size > 8) capabilityLines.removeFirst()
        }
    }

    fun shareFile(context: Context) {
        onWriter {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.writeText("")
            }
        }
        val uri = Uri.parse("content://$SHARE_AUTHORITY/imap-traffic.log")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("imap-traffic.log", uri)
        }
        val chooser = Intent.createChooser(send, "Share IMAP traffic")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    fun debugReport(
        versionName: String,
        versionCode: Int,
        androidVersion: String,
        device: String,
        settings: AccountSettings,
    ): String {
        val box = arrayOfNulls<String>(1)
        onWriter {
            val traffic = trafficForReport()
            val caps = capabilityLines.toList()
            val history = statusHistory.toList()
            val body = buildString {
                appendLine("LiveIMAP $versionName ($versionCode)")
                appendLine("Android $androidVersion $device")
                appendLine("Account")
                appendLine(accountBlock(settings))
                appendLine("CAPABILITY")
                for (line in caps) appendLine(line)
                appendLine("Status")
                for (line in history) appendLine(line)
                appendLine("Traffic")
                append(trafficTail(traffic))
            }
            box[0] = scrubReport(body)
        }
        return box[0] ?: ""
    }

    private fun acceptOnWriter(connectionId: String, logType: Int, data: ByteArray) {
        val conn = conns.getOrPut(connectionId) { Conn() }
        when (logType) {
            LOG_PRIVATE -> store(connectionId, "C <redacted ${data.size} bytes>")
            LOG_SENT -> take(connectionId, conn, conn.sent, "C", data)
            LOG_RECEIVED -> take(connectionId, conn, conn.received, "S", data)
        }
    }

    /** The next accepted record follows one gap line that counts lines the full queue dropped. */
    private fun writeGap(connectionId: String, drops: Int) {
        writeRaw("${clock()} $connectionId dropped $drops lines")
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
        if (prefix == "S" && saslChallenge(shown)) {
            conn.saslArmed = true // IMAP continuation, or SMTP AUTH challenge (F78)
        }
        val text = if (prefix == "C") redactSent(conn, shown) else shown
        if (text.isNotEmpty()) {
            store(connectionId, "$prefix $text")
        }
        if (prefix == "C" && text.startsWith("<redacted ")) return
        // The literal announced by the line as sent, not as redacted: "LOGIN user {6}" must still skip the
        // six password bytes instead of logging them as the next line (F10).
        val literal = literalSize(if (prefix == "C") shown else text)
        if (literal != null) {
            half.literalTotal = literal
            half.literalLeft = literal
            if (literal == 0L) {
                store(connectionId, "$prefix <literal 0 bytes>")
            }
        }
    }

    private fun redactSent(conn: Conn, line: String): String {
        // F78: SMTP "AUTH <mech> [initial-response]" (untagged). Redact the response; the server's 334 arms
        // redaction of the next client line.
        val smtp = line.trim().split(' ').filter { it.isNotEmpty() }
        if (smtp.isNotEmpty() && smtp[0].equals("AUTH", ignoreCase = true)) {
            conn.saslArmed = false
            return if (smtp.size >= 3) "AUTH ${smtp[1]} <redacted ${smtp.drop(2).joinToString(" ").length} bytes>" else line
        }
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
        val last = lastDiskLine
        if (last != null && bodyOf(last) == text) {
            repeatCounts[last] = (repeatCounts[last] ?: 1) + 1
            return
        }
        val line = "${clock()} $connectionId $text"
        lastDiskLine = line
        writeRaw(line)
    }

    /** Opens the file on the first write and keeps that stream. A trim replaces the file, then opens it again. */
    private fun opened(): FileOutputStream {
        stream?.let { return it }
        file.parentFile?.mkdirs()
        val created = FileOutputStream(file, true)
        stream = created
        storedBytes = file.length()
        return created
    }

    private fun writeRaw(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        val out = opened()
        out.write(bytes)
        out.flush()
        storedBytes += bytes.size.toLong()
        if (storedBytes > maxBytes) trimToCap()
    }

    private fun trimToCap() {
        val target = if (maxBytes > 64 * 1024) maxBytes - 64 * 1024 else 0L
        if (storedBytes <= target) return
        stream?.flush()
        stream?.close()
        stream = null
        val drop = storedBytes - target
        val tmp = File(file.parentFile, file.name + ".trim")
        var skipped = 0L
        var skipping = true
        BufferedInputStream(file.inputStream()).use { input ->
            FileOutputStream(tmp).use { dest ->
                val line = java.io.ByteArrayOutputStream()
                while (true) {
                    val b = input.read()
                    if (b < 0) {
                        if (line.size() > 0 && !skipping) dest.write(line.toByteArray())
                        break
                    }
                    line.write(b)
                    if (b != '\n'.code) continue
                    val bytes = line.toByteArray()
                    line.reset()
                    if (!skipping) {
                        dest.write(bytes)
                        continue
                    }
                    skipped += bytes.size
                    val text = String(bytes, Charsets.UTF_8).trimEnd('\n')
                    repeatCounts.remove(text)
                    if (lastDiskLine == text) lastDiskLine = null
                    if (skipped >= drop) skipping = false
                }
            }
        }
        java.nio.file.Files.move(
            tmp.toPath(),
            file.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        storedBytes = file.length()
        stream = FileOutputStream(file, true)
    }

    /** File text, with this session's repeat counts taken from memory. The file keeps the first copy. */
    private fun trafficForReport(): String {
        stream?.flush()
        val onDisk = if (file.isFile) file.readText() else ""
        if (repeatCounts.isEmpty() || onDisk.isEmpty()) return onDisk
        val parts = onDisk.split('\n')
        val body = if (parts.isNotEmpty() && parts.last().isEmpty()) parts.dropLast(1) else parts
        return buildString {
            for (line in body) {
                val n = repeatCounts[line]
                if (n != null && n > 1) {
                    append(line)
                    append(" (repeated ")
                    append(n)
                    append(" times)")
                } else {
                    append(line)
                }
                append('\n')
            }
        }
    }

    private fun tryWriter(block: () -> Unit): Boolean {
        val task = WriterTask(block, CountDownLatch(1))
        if (!jobs.trySend(task).isSuccess) return false
        task.latch.await()
        return true
    }

    private fun onWriter(block: () -> Unit) {
        if (tryWriter(block)) return
        val task = WriterTask(block, CountDownLatch(1))
        runBlocking { jobs.send(task) }
        task.latch.await()
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
        private class WriterTask(val block: () -> Unit, val latch: CountDownLatch)
        private val jobs = Channel<WriterTask>(32)

        init {
            Thread({
                runBlocking {
                    for (task in jobs) {
                        try {
                            task.block()
                        } finally {
                            task.latch.countDown()
                        }
                    }
                }
            }, "traffic-log").apply { isDaemon = true; start() }
        }

        const val MAX_BYTES = 4 * 1024 * 1024
        const val LOG_RECEIVED = 5
        const val LOG_SENT = 6
        const val LOG_PRIVATE = 7
        const val SHARE_AUTHORITY = "com.davidlang.liveimap.trafficlog"

        private val statusFlow = MutableStateFlow("")
        val debugStatus: StateFlow<String> = statusFlow

        @Volatile
        private var active: TrafficLog? = null

        fun install(file: File): TrafficLog {
            val current = active
            if (current != null && current.file.absolutePath == file.absolutePath) {
                deleteRotated(file)
                return current
            }
            val created = TrafficLog(file, recording = false)
            active = created
            deleteRotated(file)
            return created
        }

        fun setRecording(on: Boolean) {
            active?.setRecording(on)
        }

        fun noteStatus(text: String) {
            val log = active ?: return
            log.noteStatus(text)
        }

        fun noteCapability(line: String) {
            val log = active ?: return
            log.noteCapability(line)
        }

        @JvmStatic
        fun acceptNative(connectionId: String, logType: Int, data: ByteArray) {
            active?.accept(connectionId, logType, data)
            TrafficRecorder.active?.accept(connectionId, logType, data)
        }

        @JvmStatic
        fun flushNative() {
            active?.flush()
            TrafficRecorder.active?.flush()
        }

        @JvmStatic
        fun noteNative(connectionId: String, text: String) {
            active?.note(connectionId, text)
            TrafficRecorder.active?.event(connectionId, "note", text)
        }

        private fun deleteRotated(file: File) {
            val parent = file.parentFile ?: return
            File(parent, file.name + ".1").delete()
        }
    }
}

internal fun recordingWanted(flags: List<Boolean>): Boolean = flags.any { it }

internal fun saslChallenge(line: String): Boolean =
    line == "+" || line.startsWith("+ ") || line == "334" || line.startsWith("334 ")

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

private val repeatSuffix = Regex(""" \(repeated (\d+) times\)$""")

private fun bodyOf(stored: String): String {
    val rest = stored.substringAfter(' ').substringAfter(' ')
    return rest.replace(repeatSuffix, "")
}

private fun accountBlock(settings: AccountSettings): String {
    return settings.encode().lineSequence().joinToString("\n") { line ->
        if (!settings.showUserInDebugReport && line.startsWith("username=")) {
            "username=<user>"
        } else {
            line
        }
    }
}

private fun trafficTail(text: String): String {
    val cap = 64 * 1024
    if (text.length <= cap) return text
    val cut = text.substring(text.length - cap)
    val nl = cut.indexOf('\n')
    return if (nl >= 0 && nl < cut.length - 1) cut.substring(nl + 1) else cut
}

private val assignedSecret = Regex("""(?i)(\b(?:password|token)\s*[:=]\s*)(\S+)""")
private val base64Blob = Regex("""(?<![A-Za-z0-9+/])[A-Za-z0-9+/]{16,}={0,2}(?![A-Za-z0-9+/=])""")

private fun scrubReport(text: String): String {
    return text.lines().joinToString("\n") { line -> scrubReportLine(line) }
}

private fun scrubReportLine(line: String): String {
    var next = assignedSecret.replace(line) { match ->
        match.groupValues[1] + "<redacted ${match.groupValues[2].length} bytes>"
    }
    next = scrubLogin(next)
    next = base64Blob.replace(next) { match ->
        "<redacted ${match.value.length} bytes>"
    }
    return next
}

private fun scrubLogin(line: String): String {
    val tokens = imapTokens(line)
    val at = tokens.indexOfFirst { it.equals("LOGIN", ignoreCase = true) }
    if (at < 0 || at + 2 >= tokens.size) return line
    val secret = tokens.subList(at + 2, tokens.size).joinToString(" ")
    if (secret.startsWith("<redacted ")) return line
    val found = line.lastIndexOf(secret)
    if (found < 0) return line
    return line.substring(0, found).trimEnd() + " <redacted ${secret.length} bytes>"
}

class TrafficFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val file = cacheFile(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        cursor.addRow(cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        }.toTypedArray())
        return cursor
    }

    override fun getType(uri: Uri): String = "text/plain"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = cacheFile(uri) ?: throw FileNotFoundException(uri.toString())
        if (!file.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun cacheFile(uri: Uri): File? {
        if (uri.lastPathSegment != "imap-traffic.log") return null
        val dir = context?.cacheDir ?: return null
        return File(dir, "imap-traffic.log")
    }
}
