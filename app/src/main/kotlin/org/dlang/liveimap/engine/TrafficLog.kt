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
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.LiveImapApplication
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.encode

/** One account's Kotlin traffic actor. C writers do not use this type. */
interface TrafficWriter {
    fun record(conn: String, dir: Char, bytes: ByteArray)
    fun note(conn: String, text: String)
    /** Returns after records issued so far are on disk. It does not queue. */
    fun sync()
}

/**
 * Per-account traffic files under cacheDir/traffic/<accountId>/.
 * A record is `<epochMs> <conn> <dir> <length>`, the bytes, and a newline.
 */
object TrafficLog {
    const val SHARE_AUTHORITY = "com.davidlang.liveimap.trafficlog"
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private val statusFlow = MutableStateFlow("")
    private val statusHistory = ArrayDeque<String>()
    val debugStatus: StateFlow<String> = statusFlow

    fun status(text: String) {
        if (text.isEmpty()) return
        DebugLog.event("status") { text }
        synchronized(statusHistory) {
            statusHistory.addLast(text)
            while (statusHistory.size > 100) statusHistory.removeFirst()
        }
        statusFlow.value = text
    }

    fun statusLines(): List<String> = synchronized(statusHistory) { statusHistory.toList() }

    fun privateMarker(n: Int): ByteArray = "<private $n bytes>".toByteArray(Charsets.US_ASCII)

    fun trafficLogDir(cacheDir: File, accountId: String): File? {
        if (!trafficIdOk(accountId)) return null
        return File(File(cacheDir, "traffic"), accountId)
    }

    fun trafficLogFile(cacheDir: File, accountId: String): File? {
        if (!trafficIdOk(accountId)) return null
        return File(File(cacheDir, "traffic"), "$accountId.log")
    }

    /** Never null. Logging off does not write. A closed or refused id reports and does not write. */
    fun writer(cacheDir: File, accountId: String): TrafficWriter {
        val id = org.dlang.liveimap.accounts.parseAccountId(accountId)
        if (id == null || id in closedIds) return refusedWriter(accountId)
        val account = org.dlang.liveimap.settings.SettingsHub.accountNow(id)
        if (account == null || !account.logImapTraffic) return silentWriter
        val roll = if (account.trafficLogMaxMb > 0) account.trafficLogMaxMb.toLong() * 1024L * 1024L else 0L
        val dir = trafficLogDir(cacheDir, id) ?: return refusedWriter(accountId)
        return openWriter(dir, roll)
    }

    /** Stops the Kotlin writer for [accountId]. Later records are refused. The traffic files stay. */
    fun close(accountId: String) {
        val id = org.dlang.liveimap.accounts.parseAccountId(accountId) ?: return
        closedIds.add(id)
        val marker = File.separator + "traffic" + File.separator + id
        for (key in actors.keys) {
            if (key.endsWith(marker)) actors.remove(key)?.closeStream()
        }
    }

    fun currentNames(accountId: String): Set<String> {
        val id = org.dlang.liveimap.accounts.parseAccountId(accountId) ?: return emptySet()
        val marker = File.separator + "traffic" + File.separator + id
        val names = HashSet<String>()
        for ((key, writer) in actors) {
            if (key.endsWith(marker)) writer.currentName()?.let { names.add(it) }
        }
        return names
    }

    /** Test and caller path that already decided the roll cap. 0 does not roll. */
    internal fun writer(cacheDir: File, accountId: String, rollBytes: Long): TrafficWriter {
        val id = org.dlang.liveimap.accounts.parseAccountId(accountId)
        if (id == null || id in closedIds) return refusedWriter(accountId)
        val dir = trafficLogDir(cacheDir, id) ?: return refusedWriter(accountId)
        return openWriter(dir, rollBytes)
    }

    fun sweep(
        dir: File,
        maxBytes: Long,
        now: Long,
        retainDays: Int = 30,
        keepNames: Set<String> = emptySet(),
    ) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        val cutoff = now - retainDays.toLong() * DAY_MS
        for (file in files) {
            if (file.name in keepNames) continue
            if (file.lastModified() < cutoff) file.delete()
        }
        if (maxBytes <= 0L) return
        val left = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = left.sumOf { it.length() }
        for (file in left) {
            if (total <= maxBytes) break
            if (file.name in keepNames) continue
            val n = file.length()
            if (file.delete()) total -= n
        }
    }

    fun readRecords(file: File): List<TrafficRecord> {
        if (!file.isFile) return emptyList()
        val raw = file.readBytes()
        val out = ArrayList<TrafficRecord>()
        var i = 0
        while (i < raw.size) {
            val nl = nextByte(raw, i, '\n'.code.toByte())
            if (nl < 0) break
            val header = String(raw, i, nl - i, Charsets.US_ASCII)
            val parts = header.split(' ')
            if (parts.size != 4) break
            val t = parts[0].toLongOrNull() ?: break
            val len = parts[3].toIntOrNull() ?: break
            val start = nl + 1
            if (start + len > raw.size) break
            val bytes = raw.copyOfRange(start, start + len)
            val after = start + len
            if (after >= raw.size || raw[after] != '\n'.code.toByte()) break
            val dir = parts[2].singleOrNull() ?: break
            out.add(TrafficRecord(t, parts[1], dir, bytes, file.name))
            i = after + 1
        }
        return out
    }

    fun recordsIn(dir: File): List<TrafficRecord> {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".log") } ?: return emptyList()
        return files.sortedBy { it.name }.flatMap { readRecords(it) }
    }

    /** Share snapshot and the lab merge: epoch, then file name. */
    fun orderedRecords(dir: File, sinceMs: Long = 0L): List<TrafficRecord> {
        return recordsIn(dir).filter { it.t >= sinceMs }.sortedWith(compareBy<TrafficRecord> { it.t }.thenBy { it.name })
    }

    fun writeOrdered(target: File, records: List<TrafficRecord>) {
        target.parentFile?.mkdirs()
        val out = java.io.ByteArrayOutputStream()
        for (record in records) out.write(recordBytes(record.t, record.conn, record.dir, record.bytes))
        target.writeBytes(out.toByteArray())
    }

    fun debugReport(
        versionName: String,
        versionCode: Int,
        androidVersion: String,
        device: String,
        settings: Settings,
        traffic: String,
    ): String {
        return buildString {
            appendLine("LiveIMAP $versionName ($versionCode)")
            appendLine("Android $androidVersion $device")
            appendLine("Account")
            appendLine(accountBlock(settings))
            appendLine("Status")
            for (line in statusLines()) appendLine(line)
            appendLine("Traffic")
            append(trafficTail(traffic))
        }
    }

    fun share(context: Context, accountId: String) {
        val cache = context.cacheDir
        val dir = trafficLogDir(cache, accountId) ?: return
        val target = trafficLogFile(cache, accountId) ?: return
        val app = LiveImapApplication.context
        val account = org.dlang.liveimap.settings.SettingsHub.accountNow(accountId)
        val maxBytes = if (account != null && account.trafficLogMaxMb > 0) {
            account.trafficLogMaxMb.toLong() * 1024L * 1024L
        } else {
            0L
        }
        val days = org.dlang.liveimap.settings.SettingsHub.uiNow().trafficRetentionDays
        if (dir.isDirectory) sweep(dir, maxBytes, System.currentTimeMillis(), days, currentNames(accountId))
        writeOrdered(target, orderedRecords(dir))
        appendDebugLogs(target, File(context.filesDir, "logs"))
        val uri = Uri.parse("content://$SHARE_AUTHORITY/${target.name}")
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri(target.name, uri)
        }
        val chooser = Intent.createChooser(send, "Share traffic")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    /** The Debugging setting's logs (files/logs/debug-*.log) go out with the traffic log, oldest rotation first. */
    internal fun appendDebugLogs(target: File, logsDir: File) {
        val logs = logsDir.listFiles { f -> f.isFile && f.name.startsWith("debug-") && f.name.endsWith(".log") }.orEmpty()
        fun rotation(f: File) = f.name.removeSuffix(".log").substringAfterLast('.', "0").toIntOrNull() ?: 0
        for (log in logs.sortedWith(compareBy({ it.name.substringBefore('.') }, { -rotation(it) }))) {
            target.appendText("\n===== ${log.name} =====\n")
            target.appendBytes(log.readBytes())
        }
    }

    private val actors = ConcurrentHashMap<String, AccountTrafficWriter>()
    private val closedIds = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val silentWriter = SilentTrafficWriter()

    private fun refusedWriter(accountId: String): TrafficWriter = RefusedTrafficWriter(accountId)

    private fun openWriter(dir: File, rollBytes: Long): TrafficWriter {
        dir.mkdirs()
        val writer = actors.compute(dir.absolutePath) { _, current ->
            if (current != null) {
                current.rollTo(rollBytes)
                current
            } else {
                AccountTrafficWriter(dir, rollBytes)
            }
        }
        return writer ?: error("traffic actor")
    }
}

/** Report text. A refused id is empty and named once in the log. */
internal fun trafficText(cacheDir: File, accountId: String): String {
    val file = TrafficLog.trafficLogFile(cacheDir, accountId)
    if (file == null) {
        android.util.Log.w("LiveIMAP", "traffic directory refused for $accountId")
        return ""
    }
    return if (file.isFile) file.readText() else ""
}

data class TrafficRecord(
    val t: Long,
    val conn: String,
    val dir: Char,
    val bytes: ByteArray,
    val name: String,
)

private fun nextByte(raw: ByteArray, from: Int, b: Byte): Int {
    var i = from
    while (i < raw.size) {
        if (raw[i] == b) return i
        i++
    }
    return -1
}

internal fun trafficIdOk(id: String): Boolean {
    if (id.isBlank() || id == "." || id == "..") return false
    if (id.indexOf('/') >= 0 || id.indexOf('\\') >= 0) return false
    return true
}

private class SilentTrafficWriter : TrafficWriter {
    override fun record(conn: String, dir: Char, bytes: ByteArray) = Unit
    override fun note(conn: String, text: String) {
        if (text.isNotEmpty()) TrafficLog.status(text)
    }
    override fun sync() = Unit
}

private class RefusedTrafficWriter(private val accountId: String) : TrafficWriter {
    override fun record(conn: String, dir: Char, bytes: ByteArray) {
        TrafficLog.status("traffic refused for $accountId")
    }
    override fun note(conn: String, text: String) {
        TrafficLog.status("traffic refused for $accountId")
    }
    override fun sync() = Unit
}

private class AccountTrafficWriter(
    private val dir: File,
    rollBytes: Long,
) : TrafficWriter {
    private val rollLimit = AtomicLong(rollBytes)
    private val gate = Any()
    private var current: File? = null
    private var stream: java.io.FileOutputStream? = null
    private var size = 0L

    fun rollTo(n: Long) {
        rollLimit.set(n)
    }

    fun currentName(): String? = synchronized(gate) { current?.name }

    fun closeStream() {
        synchronized(gate) {
            try {
                stream?.close()
            } catch (error: Exception) {
                TrafficLog.status("traffic close failed: ${error.message ?: error.javaClass.simpleName}")
            }
            stream = null
        }
    }

    override fun record(conn: String, dir: Char, bytes: ByteArray) {
        val payload = recordBytes(System.currentTimeMillis(), conn, dir, bytes)
        synchronized(gate) {
            try {
                val file = fileFor(payload.size) ?: run {
                    TrafficLog.status("traffic file name collision")
                    return
                }
                val out = stream ?: java.io.FileOutputStream(file, true).also {
                    stream = it
                    size = file.length()
                }
                out.write(payload)
                size += payload.size
            } catch (error: Exception) {
                TrafficLog.status("traffic write failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    override fun note(conn: String, text: String) {
        if (text.isEmpty()) return
        TrafficLog.status(text)
        record(conn, '!', text.toByteArray(Charsets.UTF_8))
    }

    override fun sync() {
        synchronized(gate) {
            try {
                stream?.fd?.sync()
            } catch (error: Exception) {
                TrafficLog.status("traffic sync failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    /** A file that would pass the cap is not appended to. This record starts the next file. */
    private fun fileFor(add: Int): File? {
        val cap = rollLimit.get()
        val existing = current
        if (existing != null && (cap <= 0L || size + add <= cap)) return existing
        stream?.let {
            try {
                it.fd.sync()
                it.close()
            } catch (error: Exception) {
                TrafficLog.status("traffic roll failed: ${error.message ?: error.javaClass.simpleName}")
            }
            stream = null
        }
        repeat(8) {
            val name = System.currentTimeMillis().toString() + "-app-" +
                TrafficLogSerial.next().toString().padStart(6, '0') + ".log"
            val path = File(dir, name)
            if (!path.createNewFile()) return@repeat
            current = path
            size = 0L
            return path
        }
        return null
    }
}

private object TrafficLogSerial {
    private val serial = AtomicLong(1)
    fun next(): Long = serial.getAndIncrement()
}

internal fun recordBytes(t: Long, conn: String, dir: Char, bytes: ByteArray): ByteArray {
    val header = "$t $conn $dir ${bytes.size}\n".toByteArray(Charsets.US_ASCII)
    val out = ByteArray(header.size + bytes.size + 1)
    header.copyInto(out)
    bytes.copyInto(out, header.size)
    out[out.size - 1] = '\n'.code.toByte()
    return out
}

private fun accountBlock(settings: Settings): String {
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
        val name = uri.lastPathSegment ?: return null
        if (!name.endsWith(".log")) return null
        val id = name.removeSuffix(".log")
        val dir = context?.cacheDir ?: return null
        return TrafficLog.trafficLogFile(dir, id)
    }
}
