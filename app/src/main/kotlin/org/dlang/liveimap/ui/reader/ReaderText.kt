package org.dlang.liveimap.ui.reader

import org.dlang.liveimap.settings.SaveNameRule
import org.dlang.liveimap.ui.compose.decodeHeaderWords

data class HeaderFields(
    val from: String,
    val to: String,
    val cc: String,
    val sender: String,
    val resentTo: String,
)

fun headerFields(header: String): HeaderFields {
    var from = ""
    var to = ""
    var cc = ""
    var sender = ""
    var resentTo = ""
    for (line in unfoldedHeaderLines(header)) {
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val name = line.substring(0, colon).trim()
        val value = decodeHeaderWords(line.substring(colon + 1).trim())
        when {
            name.equals("From", ignoreCase = true) -> if (from.isEmpty()) from = value
            name.equals("To", ignoreCase = true) -> to = joinHeader(to, value)
            name.equals("Cc", ignoreCase = true) -> cc = joinHeader(cc, value)
            name.equals("Sender", ignoreCase = true) -> if (sender.isEmpty()) sender = value
            name.equals("Resent-To", ignoreCase = true) -> resentTo = value
        }
    }
    return HeaderFields(from, to, cc, sender, resentTo)
}

fun saveFolderName(
    rule: SaveNameRule,
    savedMailbox: String,
    lastSaveMailbox: String,
    from: String,
    sender: String,
    to: String,
    resentTo: String,
): String = when (rule) {
    SaveNameRule.DefaultFolder -> savedMailbox
    SaveNameRule.LastFolderUsed -> lastSaveMailbox.ifEmpty { savedMailbox }
    SaveNameRule.ByFrom -> saveNameLocal(from).ifEmpty { saveNameLocal(sender) }.ifEmpty { savedMailbox }
    SaveNameRule.BySender -> saveNameLocal(sender).ifEmpty { saveNameLocal(from) }.ifEmpty { savedMailbox }
    SaveNameRule.ByRecipient -> saveNameLocal(resentTo).ifEmpty { saveNameLocal(to) }.ifEmpty { savedMailbox }
}

private fun saveNameLocal(header: String): String {
    val open = header.indexOf('<')
    val close = if (open >= 0) header.indexOf('>', open + 1) else -1
    val extracted = if (open >= 0 && close >= 0) {
        header.substring(open + 1, close)
    } else {
        val comma = header.indexOf(',')
        if (comma >= 0) header.substring(0, comma) else header
    }
    val trimmed = extracted.trim()
    val bang = trimmed.lastIndexOf('!')
    val fromBang = if (bang >= 0) trimmed.substring(bang + 1) else trimmed
    val end = fromBang.indexOfAny(charArrayOf('%', ':', '@'))
    val slice = if (end >= 0) fromBang.substring(0, end) else fromBang
    if (slice.isEmpty()) return ""
    return slice.lowercase()
}

fun quotedReaderLine(line: String): Boolean = line.startsWith(">")

private fun joinHeader(current: String, value: String): String {
    if (value.isEmpty()) return current
    if (current.isEmpty()) return value
    return "$current, $value"
}

private fun unfoldedHeaderLines(header: String): List<String> {
    val raw = header.split('\n').map { it.trimEnd('\r') }
    val out = ArrayList<String>()
    for (line in raw) {
        if (line.isEmpty()) continue
        if ((line[0] == ' ' || line[0] == '\t') && out.isNotEmpty()) {
            out[out.lastIndex] = out.last() + " " + line.trim()
        } else {
            out.add(line)
        }
    }
    return out
}
