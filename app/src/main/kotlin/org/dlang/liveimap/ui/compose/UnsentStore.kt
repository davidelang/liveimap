package org.dlang.liveimap.ui.compose

import android.content.Context
import android.util.Log
import java.io.File
import org.dlang.liveimap.session.ComposeKind
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException

internal class DeviceCopy(
    val id: String,
    val appendOnly: Boolean,
    val mailbox: String,
    val recipients: List<String>,
    /** The `.rfc822` path. The unsent list does not read it. */
    val file: File,
    val modifiedMillis: Long = 0,
    /** Account whose servers send this copy (a sending rule's "send through"); empty = this account. */
    val via: String = "",
    /** F50: sending started (or the server's answer after DATA was lost): it may already be sent; check Sent. */
    val uncertain: Boolean = false,
    /** Decoded subject from the meta line. Null when an old meta has no subject line. */
    val subject: String? = null,
    /** The meta threw. The unsent row shows this text and is not dropped. */
    val error: String? = null,
    /** Reply/forward seed so an Unsent retry sets the same flags as the original send. */
    val sourceKind: String = "",
    val sourceMailbox: String = "",
    val sourceEpoch: Long = 0L,
    val sourceUids: List<Long> = emptyList(),
)

/** F50: native prefix on an SMTP failure that happened after the message was handed to the server. */
internal const val SmtpUncertainPrefix = "smtp-uncertain: "

/** Meta line after the mailbox. Absent on old copies, so a recipient line is not read as a subject. */
private const val SubjectMeta = "subject:"

/** Kind, folder, UIDVALIDITY, and UIDs of the message a reply or forward answered. */
private const val SourceMeta = "source:"

/** F150: the same copy, sent with no Sent copy (the user chose "send without copy" on retry). */
internal fun DeviceCopy.withoutSentCopy() =
    DeviceCopy(
        id, appendOnly, "", recipients, file, modifiedMillis, via, uncertain, subject, error,
        sourceKind, sourceMailbox, sourceEpoch, sourceUids,
    )

internal fun DeviceCopy.copyWith(appendOnly: Boolean = this.appendOnly, uncertain: Boolean = this.uncertain) =
    DeviceCopy(
        id, appendOnly, mailbox, recipients, file, modifiedMillis, via, uncertain, subject, error,
        sourceKind, sourceMailbox, sourceEpoch, sourceUids,
    )

internal fun DeviceCopy.withMessage(
    file: File = this.file,
    appendOnly: Boolean = this.appendOnly,
    mailbox: String = this.mailbox,
    uncertain: Boolean = this.uncertain,
) = DeviceCopy(
    id, appendOnly, mailbox, recipients, file, modifiedMillis, via, uncertain, subject, error,
    sourceKind, sourceMailbox, sourceEpoch, sourceUids,
)

/** Flags a retry should store on the source messages. Empty when the copy has no reply seed. */
internal fun unsentRetryFlags(copy: DeviceCopy): Set<String> {
    if (copy.sourceEpoch == 0L || copy.sourceMailbox.isEmpty() || copy.sourceUids.isEmpty()) return emptySet()
    val kind = try {
        ComposeKind.valueOf(copy.sourceKind)
    } catch (_: IllegalArgumentException) {
        return emptySet()
    }
    return smtpAcceptFlags(kind)
}

internal fun smtpUncertain(text: String): Boolean = text.startsWith(SmtpUncertainPrefix)

/** The device copy left after an SMTP failure: uncertain only when the error is `smtp-uncertain:`. */
internal fun deviceCopyAfterSmtpFailure(copy: DeviceCopy, text: String): DeviceCopy =
    copy.copyWith(uncertain = smtpUncertain(text))

internal fun smtpFailureText(text: String): String = text.removePrefix(SmtpUncertainPrefix)

internal fun unsentSubject(bytes: ByteArray, missingSubject: String, charset: String): String {
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
        return decodeHeaderWords(line.substring(colon + 1).trim(), charset)
    }
    return missingSubject
}

/** Header bytes only, through the first blank line. The body stays in the file. */
internal fun headerThroughBlankLine(file: File): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    file.inputStream().use { input ->
        var atLineStart = true
        while (true) {
            val b = input.read()
            if (b < 0) break
            out.write(b)
            if (b == '\n'.code) {
                if (atLineStart) break
                atLineStart = true
            } else if (b != '\r'.code) {
                atLineStart = false
            }
        }
    }
    return out.toByteArray()
}

private fun headerThroughBlankLine(bytes: ByteArray): ByteArray {
    var atLineStart = true
    for (i in bytes.indices) {
        val b = bytes[i].toInt() and 0xff
        if (b == '\n'.code) {
            if (atLineStart) return bytes.copyOf(i + 1)
            atLineStart = true
        } else if (b != '\r'.code) {
            atLineStart = false
        }
    }
    return bytes
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

/** Draft ids with a compose screen open. Rotation does not remove an id. */
internal val openComposeIds = AtomicReference<Set<String>>(emptySet())

internal fun messageDir(filesDir: File, accountId: String, draftId: String): File =
    File(unsentAccountDir(filesDir, accountId), draftId)

internal fun contentsDir(filesDir: File, accountId: String, draftId: String): File =
    File(messageDir(filesDir, accountId, draftId), "contents")

/**
 * Creates the message directory. The caller does not call this when the directory already exists.
 * A second call for one new id throws [java.nio.file.FileAlreadyExistsException].
 */
internal fun createMessageDir(filesDir: File, accountId: String, draftId: String) {
    val dir = messageDir(filesDir, accountId, draftId)
    val parent = dir.parentFile ?: throw java.io.IOException("missing parent")
    Files.createDirectories(parent.toPath())
    Files.createDirectory(dir.toPath())
}

/** Adds [id] before the caller creates the directory. */
internal fun openCompose(id: String) {
    openComposeIds.updateAndGet { current -> if (id in current) current else current + id }
}

internal fun closeCompose(id: String) {
    openComposeIds.updateAndGet { current -> if (id !in current) current else current - id }
}

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
    return metas.sortedBy { it.name }.map { meta ->
        val id = meta.name.removeSuffix(".meta")
        val message = File(dir, "$id.rfc822")
        try {
            val lines = meta.readLines(Charsets.UTF_8)
            if (lines.size < 2) throw IllegalArgumentException("bad meta")
            val subjectLine = lines.getOrNull(2)
            val subject = if (subjectLine != null && subjectLine.startsWith(SubjectMeta)) {
                subjectLine.removePrefix(SubjectMeta)
            } else {
                null
            }
            val body = if (subject != null) lines.drop(3) else lines.drop(2)
            var sourceKind = ""
            var sourceMailbox = ""
            var sourceEpoch = 0L
            var sourceUids = emptyList<Long>()
            val recipients = ArrayList<String>()
            for (line in body) {
                if (line.startsWith(SourceMeta)) {
                    val parts = line.removePrefix(SourceMeta).split('\t')
                    sourceKind = parts.getOrElse(0) { "" }
                    sourceMailbox = parts.getOrElse(1) { "" }
                    sourceEpoch = parts.getOrElse(2) { "0" }.toLongOrNull() ?: 0L
                    sourceUids = parts.getOrElse(3) { "" }.split(',').mapNotNull { it.toLongOrNull() }
                    continue
                }
                if (line.isNotEmpty()) recipients.add(line)
            }
            DeviceCopy(
                id = id,
                appendOnly = lines[0].substringBefore(' ') == "append-only",
                uncertain = lines[0].substringBefore(' ') == "uncertain",
                via = lines[0].substringAfter(" via=", "").trim(),
                mailbox = lines[1],
                recipients = recipients,
                file = message,
                modifiedMillis = meta.lastModified(),
                subject = subject,
                sourceKind = sourceKind,
                sourceMailbox = sourceMailbox,
                sourceEpoch = sourceEpoch,
                sourceUids = sourceUids,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            DeviceCopy(
                id = id,
                appendOnly = false,
                mailbox = "",
                recipients = emptyList(),
                file = message,
                modifiedMillis = meta.lastModified(),
                error = error.message ?: error.toString(),
            )
        }
    }
}

internal fun readCopies(filesDir: File, accountId: String): List<DeviceCopy> {
    var merged = emptyList<DeviceCopy>()
    val dirs = unsentDirs(filesDir, accountId)
    for (dir in dirs) {
        merged = mergedAccountCopies(merged, copiesIn(dir))
    }
    val kept = HashSet<String>()
    for (copy in merged) kept.add(copy.id)
    for (dir in dirs) sweepOrphanMessageDirs(dir, kept)
    return merged
}

/** Deletes child message directories that are neither a listed copy nor open. Never the account directory or a file. */
private fun sweepOrphanMessageDirs(dir: File, kept: Set<String>) {
    if (!dir.isDirectory) return
    val children = dir.listFiles() ?: return
    for (child in children) {
        if (!child.isDirectory) continue
        if (child.name in kept) continue
        // Re-read immediately before each delete so a draft that opens during the sweep is kept.
        if (child.name in openComposeIds.get()) continue
        try {
            if (!child.deleteRecursively()) {
                Log.w("LiveIMAP", "unsent message directory not deleted: ${child.path}")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("LiveIMAP", "unsent message directory not deleted: ${child.path}", error)
        }
    }
}

internal fun readCopies(context: Context, accountId: String = ""): List<DeviceCopy> =
    readCopies(context.filesDir, accountId)

/**
 * Writes the copy atomically (temp file + rename, message file before meta, so a crash never leaves a meta pointing at
 * half a message). Returns false when it could not be written; the caller tells the user (F50).
 */
internal fun writeCopy(filesDir: File, copy: DeviceCopy, accountId: String, charset: String = ""): Boolean {
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
                Files.delete(tmp.toPath())
                throw java.io.IOException("rename failed")
            }
        }
        val message = copy.file.readBytes()
        atomic("${copy.id}.rfc822", message)
        val subject = unsentSubject(headerThroughBlankLine(message), "", charset)
            .replace("\n", "")
            .replace("\r", "")
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
            append(SubjectMeta)
            appendLine(subject)
            if (copy.sourceKind.isNotEmpty()) {
                append(SourceMeta)
                append(copy.sourceKind.replace("\n", "").replace("\r", "").replace("\t", ""))
                append('\t')
                append(copy.sourceMailbox.replace("\n", "").replace("\r", "").replace("\t", ""))
                append('\t')
                append(copy.sourceEpoch)
                append('\t')
                append(copy.sourceUids.joinToString(","))
                append('\n')
            }
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

internal fun writeCopy(context: Context, copy: DeviceCopy, accountId: String = "", charset: String = ""): Boolean =
    writeCopy(context.filesDir, copy, accountId, charset)

private fun deletePresent(file: File) {
    if (!file.exists()) return
    Files.delete(file.toPath())
}

internal fun deleteCopy(filesDir: File, id: String, accountId: String) {
    for (dir in unsentDirs(filesDir, accountId)) {
        deletePresent(File(dir, "$id.rfc822"))
        deletePresent(File(dir, "$id.meta"))
        val message = File(dir, id)
        if (message.isDirectory) message.deleteRecursively()
    }
}

internal fun deleteCopy(context: Context, id: String, accountId: String = "") {
    deleteCopy(context.filesDir, id, accountId)
}

internal fun deleteUnsentAccount(filesDir: File, accountId: String) {
    unsentAccountDir(filesDir, accountId).deleteRecursively()
}

/**
 * David's JMAP rule: every JMAP send keeps a local copy in app-private storage until EmailSubmission is confirmed.
 * Written "may be sent" before [send] starts (so a process death keeps it); deleted on success; on failure kept as
 * a plain unsent copy (the Unsent list shows it like any failed send) and the failure is rethrown.
 * A false [write] throws [DeviceCopyNotSaved] and does not call [send] or [delete].
 * An [java.io.IOException] from [delete] after [send] returns is reported and not rethrown: the send already completed.
 */
internal class DeviceCopyNotSaved : Exception()

internal fun jmapMaybeSent(failure: Throwable): Boolean = failure is org.dlang.liveimap.jmap.JmapMaybeSent

/** File name from a delete [java.io.IOException], or [fallback] when the throw has no path. */
internal fun ioFailureName(error: java.io.IOException, fallback: String): String {
    val path = (error as? java.nio.file.FileSystemException)?.file
    if (path.isNullOrEmpty()) return fallback
    val cut = path.lastIndexOf('/')
    return if (cut >= 0 && cut < path.length - 1) path.substring(cut + 1) else path
}

/** Non-empty notice lines, in order, without dropping an earlier line or repeating one. */
internal fun joinedNotice(vararg parts: String?): String? {
    val lines = ArrayList<String>()
    for (part in parts) {
        if (part.isNullOrEmpty()) continue
        for (line in part.split('\n')) {
            if (line.isNotEmpty() && line !in lines) lines.add(line)
        }
    }
    return if (lines.isEmpty()) null else lines.joinToString("\n")
}

internal inline fun <T> keepCopyUntilSubmitted(
    copy: DeviceCopy,
    write: (DeviceCopy) -> Boolean,
    delete: (String) -> Unit,
    onDeleteFailed: (java.io.IOException) -> Unit = {},
    send: () -> T,
): T {
    if (!write(copy.copyWith(uncertain = true))) throw DeviceCopyNotSaved()
    val result = try {
        send()
    } catch (failure: Throwable) {
        // F146: a lost answer to the submission keeps "may be sent", so the Unsent list says to check Sent first.
        if (failure !is kotlinx.coroutines.CancellationException) write(copy.copyWith(uncertain = jmapMaybeSent(failure)))
        throw failure
    }
    try {
        delete(copy.id)
    } catch (error: java.io.IOException) {
        onDeleteFailed(error)
    }
    return result
}
