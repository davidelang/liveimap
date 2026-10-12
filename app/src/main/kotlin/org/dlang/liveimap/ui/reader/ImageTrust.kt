package org.dlang.liveimap.ui.reader

import java.io.File
import org.dlang.liveimap.ui.compose.addrSpec

/**
 * Senders and mailing lists whose HTML mail opens with remote images allowed ("Always show images from …").
 * Per account, one file of `sender <addr>` / `list <list-id>` lines. A From address can be forged, so this only
 * decides image loading (a tracking-privacy choice), never anything that runs content.
 */
data class ImageTrust(val senders: Set<String> = emptySet(), val lists: Set<String> = emptySet())

const val MaxTrustEntries = 5000
private const val MaxKeyLength = 255

/** Lower-cased addr-spec of a From value, or "" when it has none. */
fun trustSenderKey(from: String): String {
    val a = addrSpec(from).trim().lowercase()
    return if ('@' in a && a.length <= MaxKeyLength && a.none { it.isWhitespace() || it.code < 0x20 }) a else ""
}

/** RFC 2919 list identifier: the part in angle brackets (or the whole value), lower-cased; "" if unusable. */
fun listIdKey(value: String): String {
    val open = value.lastIndexOf('<')
    val close = if (open >= 0) value.indexOf('>', open + 1) else -1
    val id = (if (open >= 0 && close > open) value.substring(open + 1, close) else value).trim().lowercase()
    return if (id.isNotEmpty() && id.length <= MaxKeyLength && id.none { it.isWhitespace() || it.code < 0x20 }) id else ""
}

fun imagesTrusted(trust: ImageTrust, from: String, listId: String): Boolean {
    val list = listIdKey(listId)
    if (list.isNotEmpty() && list in trust.lists) return true
    val sender = trustSenderKey(from)
    return sender.isNotEmpty() && sender in trust.senders
}

fun encodeImageTrust(trust: ImageTrust): String = buildString {
    trust.senders.sorted().forEach { append("sender ").append(it).append('\n') }
    trust.lists.sorted().forEach { append("list ").append(it).append('\n') }
}

fun decodeImageTrust(text: String): ImageTrust {
    val senders = LinkedHashSet<String>()
    val lists = LinkedHashSet<String>()
    for (line in text.lineSequence()) {
        if (senders.size + lists.size >= MaxTrustEntries) break
        val sp = line.indexOf(' ')
        if (sp <= 0) continue
        val value = line.substring(sp + 1)
        when (line.substring(0, sp)) {
            "sender" -> trustSenderKey(value).takeIf { it.isNotEmpty() }?.let { senders.add(it) }
            "list" -> listIdKey(value).takeIf { it.isNotEmpty() }?.let { lists.add(it) }
        }
    }
    return ImageTrust(senders, lists)
}

fun withSender(trust: ImageTrust, from: String, on: Boolean): ImageTrust {
    val key = trustSenderKey(from)
    if (key.isEmpty()) return trust
    return trust.copy(senders = if (on) trust.senders + key else trust.senders - key)
}

fun withList(trust: ImageTrust, listId: String, on: Boolean): ImageTrust {
    val key = listIdKey(listId)
    if (key.isEmpty()) return trust
    return trust.copy(lists = if (on) trust.lists + key else trust.lists - key)
}

internal fun imageTrustFile(filesDir: File, accountId: String): File? {
    val id = accountId.trim()
    if (id.isEmpty() || id == "." || id == ".." || '/' in id || '\\' in id) return null
    return File(File(filesDir, "image-trust"), "$id.txt")
}

fun loadImageTrust(filesDir: File, accountId: String): ImageTrust {
    val f = imageTrustFile(filesDir, accountId) ?: return ImageTrust()
    return try {
        if (f.isFile && f.length() < 2_000_000) decodeImageTrust(f.readText()) else ImageTrust()
    } catch (_: java.io.IOException) {
        ImageTrust()
    }
}

fun saveImageTrust(filesDir: File, accountId: String, trust: ImageTrust): Boolean {
    val f = imageTrustFile(filesDir, accountId) ?: return false
    return try {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(encodeImageTrust(trust))
        tmp.renameTo(f)
    } catch (_: java.io.IOException) {
        false
    }
}
