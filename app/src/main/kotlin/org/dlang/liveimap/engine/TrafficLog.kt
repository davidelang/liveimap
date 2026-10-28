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
import org.dlang.liveimap.settings.AccountSettings
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

    /** Null when the id is refused or, once the application exists, logging is off for that account. */
    fun writer(cacheDir: File, accountId: String): TrafficWriter? {
        val dir = trafficLogDir(cacheDir, accountId) ?: return null
        val app = LiveImapApplication.context ?: return null
        val account = try {
            runBlocking { DataStoreSettingsStore(app, accountId).load() }
        } catch (_: Exception) {
            return null
        }
        if (!account.logImapTraffic) return null
        val roll = if (account.trafficLogSizeLimit) account.trafficLogMaxMb.toLong() * 1024L * 1024L / 4L else 0L
        return openWriter(dir, roll)
    }

    /** Test and caller path that already decided the roll cap. 0 does not roll. */
    internal fun writer(cacheDir: File, accountId: String, rollBytes: Long): TrafficWriter? {
        val dir = trafficLogDir(cacheDir, accountId) ?: return null
        return openWriter(dir, rollBytes)
    }

    fun sweep(dir: File, maxBytes: Long, now: Long) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        val cutoff = now - 30L * DAY_MS
        for (file in files) {
            if (file.lastModified() < cutoff) file.delete()
        }
        val left = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var total = left.sumOf { it.length() }
        for (file in left) {
            if (total <= maxBytes) break
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
        settings: AccountSettings,
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
        val maxMb = if (app != null) {
            try {
                runBlocking { DataStoreSettingsStore(app, accountId).load().trafficLogMaxMb }
            } catch (_: Exception) {
                4
            }
        } else {
            4
        }
        if (dir.isDirectory) sweep(dir, maxMb.toLong() * 1024L * 1024L, System.currentTimeMillis())
        writeOrdered(target, orderedRecords(dir))
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

    private val actors = ConcurrentHashMap<String, AccountTrafficWriter>()

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

private class AccountTrafficWriter(
    private val dir: File,
    rollBytes: Long,
) : TrafficWriter {
    private val rollLimit = AtomicLong(rollBytes)
    private val gate = Any()
    private var current: File? = null

    fun rollTo(n: Long) {
        rollLimit.set(n)
    }

    override fun record(conn: String, dir: Char, bytes: ByteArray) {
        val payload = recordBytes(System.currentTimeMillis(), conn, dir, bytes)
        synchronized(gate) {
            try {
                append(payload)
            } catch (_: Exception) {
            }
        }
    }

    override fun note(conn: String, text: String) {
        if (text.isEmpty()) return
        TrafficLog.status(text)
        record(conn, '!', text.toByteArray(Charsets.UTF_8))
    }

    override fun sync() {
        synchronized(gate) { }
    }

    private fun append(payload: ByteArray) {
        val file = fileFor(payload.size) ?: return
        file.appendBytes(payload)
    }

    /** A file that would pass the cap is not appended to. This record starts the next file. */
    private fun fileFor(add: Int): File? {
        val cap = rollLimit.get()
        val existing = current
        if (existing != null && existing.isFile && (cap <= 0L || existing.length() + add <= cap)) {
            return existing
        }
        repeat(8) {
            val name = System.currentTimeMillis().toString() + "-app-" +
                TrafficLogSerial.next().toString().padStart(6, '0') + ".log"
            val path = File(dir, name)
            if (path.exists()) return@repeat
            current = path
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
