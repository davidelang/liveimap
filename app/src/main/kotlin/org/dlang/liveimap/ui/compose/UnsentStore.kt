package org.dlang.liveimap.ui.compose

import android.content.Context
import java.io.File
import kotlinx.coroutines.CancellationException

internal class DeviceCopy(
    val id: String,
    val appendOnly: Boolean,
    val mailbox: String,
    val recipients: List<String>,
    val bytes: ByteArray,
    val modifiedMillis: Long = 0,
)

internal fun unsentSubject(bytes: ByteArray, missingSubject: String): String {
    val text = bytes.toString(Charsets.ISO_8859_1)
    val logical = ArrayList<String>()
    for (raw in text.split('\n')) {
        val line = raw.trimEnd('\r')
        if (line.isEmpty()) break
        if ((line[0] == ' ' || line[0] == '\t') && logical.isNotEmpty()) {
            logical[logical.lastIndex] = logical.last() + " " + line.trim()
        } else {
            logical.add(line)
        }
    }
    for (line in logical) {
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val name = line.substring(0, colon).trim()
        if (!name.equals("Subject", ignoreCase = true)) continue
        return decodeHeaderWords(line.substring(colon + 1).trim())
    }
    return missingSubject
}

internal fun copyDir(context: Context): File = File(context.filesDir, "unsent")

internal fun readCopies(context: Context): List<DeviceCopy> {
    val dir = copyDir(context)
    if (!dir.isDirectory) return emptyList()
    val metas = dir.listFiles { file -> file.isFile && file.name.endsWith(".meta") } ?: return emptyList()
    return metas.sortedBy { it.name }.mapNotNull { meta ->
        try {
            val lines = meta.readLines(Charsets.UTF_8)
            if (lines.size < 2) return@mapNotNull null
            val id = meta.name.removeSuffix(".meta")
            val bytesFile = File(dir, "$id.rfc822")
            if (!bytesFile.isFile) return@mapNotNull null
            DeviceCopy(
                id = id,
                appendOnly = lines[0] == "append-only",
                mailbox = lines[1],
                recipients = lines.drop(2).filter { it.isNotEmpty() },
                bytes = bytesFile.readBytes(),
                modifiedMillis = meta.lastModified(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }
}

internal fun writeCopy(context: Context, copy: DeviceCopy) {
    try {
        val dir = copyDir(context)
        dir.mkdirs()
        File(dir, "${copy.id}.rfc822").writeBytes(copy.bytes)
        val text = buildString {
            appendLine(if (copy.appendOnly) "append-only" else "unsent")
            appendLine(copy.mailbox.replace("\n", "").replace("\r", ""))
            for (recipient in copy.recipients) {
                appendLine(recipient.replace("\n", "").replace("\r", ""))
            }
        }
        File(dir, "${copy.id}.meta").writeText(text, Charsets.UTF_8)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // The screen keeps the same bytes in memory when the file write fails.
    }
}

internal fun deleteCopy(context: Context, id: String) {
    val dir = copyDir(context)
    File(dir, "$id.rfc822").delete()
    File(dir, "$id.meta").delete()
}
