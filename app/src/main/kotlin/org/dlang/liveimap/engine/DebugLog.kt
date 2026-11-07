package org.dlang.liveimap.engine

import java.io.File

/**
 * The "Debugging (detailed logs)" setting. Off: nothing is built or written. On: every event goes to
 * logcat under `liveimap-<area>` and to the persistent debug log (files/logs/debug-<process>.log).
 * Callers pass summaries only: layer kinds and keys, setting names, connection states.
 * Never a password, token, or message body. The raw file is kept; masking belongs to exported copies.
 */
object DebugLog {
    @Volatile
    var enabled: Boolean = false

    /** Replaced in tests. Production: logcat plus the persistent file. */
    @Volatile
    internal var sink: (tag: String, line: String) -> Unit = ::writeOut

    @Volatile
    private var file: DebugLogFile? = null

    /** Called once per process with its files dir and process name. */
    fun install(filesDir: File, process: String) {
        file = DebugLogFile(File(filesDir, "logs"), "debug-${safeName(process)}", maxBytes = 256L * 1024L, keep = 3)
    }

    fun event(area: String, text: () -> String) {
        if (!enabled) return
        sink("liveimap-$area", text())
    }

    private fun writeOut(tag: String, line: String) {
        android.util.Log.d(tag, line)
        file?.append("${System.currentTimeMillis()} $tag $line")
    }

    internal fun safeName(process: String): String {
        val tail = process.substringAfterLast(':', "main")
        return tail.filter { it.isLetterOrDigit() }.ifEmpty { "main" }
    }
}

/** Line log with size rotation: name.log, then name.1.log .. name.(keep-1).log. */
internal class DebugLogFile(private val dir: File, private val name: String, private val maxBytes: Long, private val keep: Int) {
    private val current get() = File(dir, "$name.log")

    @Synchronized
    fun append(line: String) {
        check(dir.isDirectory || dir.mkdirs()) { "debug log directory unavailable" }
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (current.length() + bytes.size > maxBytes) rotate()
        current.appendBytes(bytes)
    }

    private fun rotate() {
        val last = File(dir, "$name.${keep - 1}.log")
        if (last.exists()) check(last.delete()) { "debug log rotation" }
        for (i in keep - 2 downTo 1) {
            val from = File(dir, "$name.$i.log")
            if (from.exists()) check(from.renameTo(File(dir, "$name.${i + 1}.log"))) { "debug log rotation" }
        }
        if (keep > 1 && current.exists()) check(current.renameTo(File(dir, "$name.1.log"))) { "debug log rotation" }
        else if (current.exists()) check(current.delete()) { "debug log rotation" }
    }

    fun files(): List<File> = (listOf(current) + (1 until keep).map { File(dir, "$name.$it.log") }).filter { it.exists() }
}
