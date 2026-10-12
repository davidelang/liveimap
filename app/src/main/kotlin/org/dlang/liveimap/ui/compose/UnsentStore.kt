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
    /** Account whose servers send this copy (a sending rule's "send through"); empty = this account. */
    val via: String = "",
    /** F50: sending started (or the server's answer after DATA was lost): it may already be sent; check Sent. */
    val uncertain: Boolean = false,
)

/** F50: native prefix on an SMTP failure that happened after the message was handed to the server. */
internal const val SmtpUncertainPrefix = "smtp-uncertain: "

internal fun DeviceCopy.copyWith(appendOnly: Boolean = this.appendOnly, uncertain: Boolean = this.uncertain) =
    DeviceCopy(id, appendOnly, mailbox, recipients, bytes, modifiedMillis, via, uncertain)

internal fun smtpUncertain(text: String): Boolean = text.startsWith(SmtpUncertainPrefix)

internal fun smtpFailureText(text: String): String = text.removePrefix(SmtpUncertainPrefix)

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

/**
 * F51: each account has its own directory and nothing is shared. An id that is not a safe file name (blank, ".",
 * "..", a slash) gets a hashed directory of its own instead of the shared legacy one, so its copies never show up
 * (and can never be retried) under another account.
 */
internal fun unsentAccountDir(filesDir: File, accountId: String): File {
    val id = accountId.trim()
    val legacy = File(filesDir, "unsent")
    if (id.isEmpty() || id == "." || id == ".." || '/' in id || '\\' in id || id.startsWith(".")) {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(accountId.toByteArray(Charsets.UTF_8))
        return File(legacy, ".id-" + digest.joinToString("") { "%02x".format(it) }.take(32))
    }
    return File(legacy, id)
}

internal fun unsentDirs(filesDir: File, accountId: String): List<File> = listOf(unsentAccountDir(filesDir, accountId))

internal fun mergedAccountCopies(account: List<DeviceCopy>, legacy: List<DeviceCopy>): List<DeviceCopy> {
    val seen = HashSet<String>()
    for (copy in account) seen.add(copy.id)
    val merged = ArrayList<DeviceCopy>(account.size + legacy.size)
    merged.addAll(account)
    for (copy in legacy) {
        if (seen.add(copy.id)) merged.add(copy)
    }
    return merged
}

private fun copiesIn(dir: File): List<DeviceCopy> {
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
                appendOnly = lines[0].substringBefore(' ') == "append-only",
                uncertain = lines[0].substringBefore(' ') == "uncertain",
                via = lines[0].substringAfter(" via=", "").trim(),
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

internal fun readCopies(filesDir: File, accountId: String): List<DeviceCopy> {
    var merged = emptyList<DeviceCopy>()
    for (dir in unsentDirs(filesDir, accountId)) {
        merged = mergedAccountCopies(merged, copiesIn(dir))
    }
    return merged
}

internal fun readCopies(context: Context, accountId: String = ""): List<DeviceCopy> =
    readCopies(context.filesDir, accountId)

/**
 * Writes the copy atomically (temp file + rename, bytes before meta, so a crash never leaves a meta pointing at
 * half a message). Returns false when it could not be written; the caller tells the user (F50).
 */
internal fun writeCopy(filesDir: File, copy: DeviceCopy, accountId: String): Boolean {
    val dir = unsentDirs(filesDir, accountId).firstOrNull() ?: return false
    try {
        dir.mkdirs()
        fun atomic(name: String, data: ByteArray) {
            val tmp = File(dir, "$name.tmp")
            java.io.FileOutputStream(tmp).use { out ->
                out.write(data)
                out.fd.sync()
            }
            if (!tmp.renameTo(File(dir, name))) {
                tmp.delete()
                throw java.io.IOException("rename failed")
            }
        }
        atomic("${copy.id}.rfc822", copy.bytes)
        val text = buildString {
            append(
                when {
                    copy.appendOnly -> "append-only"
                    copy.uncertain -> "uncertain"
                    else -> "unsent"
                },
            )
            if (copy.via.isNotEmpty()) append(" via=").append(copy.via.filter { it.isLetterOrDigit() || it == '-' })
            append('\n')
            appendLine(copy.mailbox.replace("\n", "").replace("\r", ""))
            for (recipient in copy.recipients) {
                appendLine(recipient.replace("\n", "").replace("\r", ""))
            }
        }
        atomic("${copy.id}.meta", text.toByteArray(Charsets.UTF_8))
        return true
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        // The screen keeps the same bytes in memory; the caller reports that the device copy is missing.
        return false
    }
}

internal fun writeCopy(context: Context, copy: DeviceCopy, accountId: String = ""): Boolean =
    writeCopy(context.filesDir, copy, accountId)

internal fun deleteCopy(filesDir: File, id: String, accountId: String) {
    for (dir in unsentDirs(filesDir, accountId)) {
        File(dir, "$id.rfc822").delete()
        File(dir, "$id.meta").delete()
    }
}

internal fun deleteCopy(context: Context, id: String, accountId: String = "") {
    deleteCopy(context.filesDir, id, accountId)
}

internal fun deleteUnsentAccount(filesDir: File, accountId: String) {
    unsentAccountDir(filesDir, accountId).deleteRecursively()
}
