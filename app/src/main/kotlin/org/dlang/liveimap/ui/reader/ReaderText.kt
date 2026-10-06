package org.dlang.liveimap.ui.reader

import org.dlang.liveimap.ui.compose.decodeHeaderWords

data class HeaderFields(
    val from: String,
    val to: String,
    val cc: String,
)

fun headerFields(header: String): HeaderFields {
    var from = ""
    var to = ""
    var cc = ""
    for (line in unfoldedHeaderLines(header)) {
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        val name = line.substring(0, colon).trim()
        val value = decodeHeaderWords(line.substring(colon + 1).trim())
        when {
            name.equals("From", ignoreCase = true) -> if (from.isEmpty()) from = value
            name.equals("To", ignoreCase = true) -> to = joinHeader(to, value)
            name.equals("Cc", ignoreCase = true) -> cc = joinHeader(cc, value)
        }
    }
    return HeaderFields(from, to, cc)
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
