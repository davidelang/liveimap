package org.dlang.liveimap.ui.reader

private val lineBreaks = setOf(
    "p",
    "div",
    "br",
    "tr",
    "li",
    "blockquote",
    "h1",
    "h2",
    "h3",
    "h4",
    "h5",
    "h6",
)

private class TagMatch(
    val name: String,
    val closing: Boolean,
    val selfClosing: Boolean,
    val attrs: Map<String, String>,
    val next: Int,
)

fun htmlAsText(html: String): String {
    val out = StringBuilder()
    val hrefs = ArrayDeque<String>()
    var skipping: String? = null
    var i = 0
    while (i < html.length) {
        if (html[i] == '<') {
            val tag = parseTagAt(html, i)
            if (tag == null) {
                if (skipping == null) out.append('<')
                i += 1
                continue
            }
            if (skipping != null) {
                if (tag.closing && tag.name == skipping) skipping = null
                i = tag.next
                continue
            }
            if (!tag.closing && !tag.selfClosing && (tag.name == "script" || tag.name == "style")) {
                skipping = tag.name
                i = tag.next
                continue
            }
            if (lineBreaks.contains(tag.name)) breakLine(out)
            if (!tag.closing && tag.name == "li") out.append("* ")
            if (!tag.closing && tag.name == "img") {
                val alt = tag.attrs["alt"]
                out.append(if (alt.isNullOrEmpty()) "[image]" else alt)
            }
            if (!tag.closing && tag.name == "a") hrefs.addLast(tag.attrs["href"].orEmpty())
            if (tag.closing && tag.name == "a") {
                val href = if (hrefs.isEmpty()) "" else hrefs.removeLast()
                if (href.isNotEmpty()) {
                    out.append(' ')
                    out.append('<')
                    out.append(href)
                    out.append('>')
                }
            }
            i = tag.next
            continue
        }
        if (skipping != null) {
            i += 1
            continue
        }
        if (html[i] == '&') {
            val decoded = decodeEntity(html, i)
            if (decoded == null) {
                out.append(html[i])
                i += 1
            } else {
                out.append(decoded.first)
                i = decoded.second
            }
        } else {
            out.append(html[i])
            i += 1
        }
    }
    return collapseBlankLines(out.toString())
}

private fun breakLine(out: StringBuilder) {
    if (out.isEmpty() || out.last() != '\n') out.append('\n')
}

private fun parseTagAt(html: String, start: Int): TagMatch? {
    if (start >= html.length || html[start] != '<') return null
    var i = start + 1
    val closing = i < html.length && html[i] == '/'
    if (closing) i += 1
    if (i >= html.length || !html[i].isLetter()) return null
    val nameStart = i
    i += 1
    while (i < html.length && html[i].isLetterOrDigit()) i += 1
    val name = html.substring(nameStart, i).lowercase()
    val end = tagEnd(html, start)
    if (end < 0) return null
    val inside = html.substring(i, end)
    return TagMatch(name, closing, inside.trimEnd().endsWith("/"), parseAttrs(inside), end + 1)
}

private fun tagEnd(html: String, start: Int): Int {
    var quote = '\u0000'
    var i = start + 1
    while (i < html.length) {
        val c = html[i]
        if (quote != '\u0000') {
            if (c == quote) quote = '\u0000'
        } else if (c == '"' || c == '\'') {
            quote = c
        } else if (c == '>') {
            return i
        }
        i += 1
    }
    return -1
}

private fun parseAttrs(text: String): Map<String, String> {
    val attrs = linkedMapOf<String, String>()
    var i = 0
    while (i < text.length) {
        while (i < text.length && text[i].isWhitespace()) i += 1
        if (i >= text.length || text[i] == '/') break
        val nameStart = i
        while (i < text.length && !text[i].isWhitespace() && text[i] != '=' && text[i] != '/') i += 1
        val name = text.substring(nameStart, i).lowercase()
        if (name.isEmpty()) {
            i += 1
            continue
        }
        while (i < text.length && text[i].isWhitespace()) i += 1
        if (i >= text.length || text[i] != '=') {
            attrs[name] = ""
            continue
        }
        i += 1
        while (i < text.length && text[i].isWhitespace()) i += 1
        if (i >= text.length) {
            attrs[name] = ""
            break
        }
        val value = if (text[i] == '"' || text[i] == '\'') {
            val quote = text[i]
            i += 1
            val valueStart = i
            while (i < text.length && text[i] != quote) i += 1
            val raw = text.substring(valueStart, i)
            if (i < text.length) i += 1
            raw
        } else {
            val valueStart = i
            while (i < text.length && !text[i].isWhitespace()) i += 1
            text.substring(valueStart, i)
        }
        attrs[name] = decodeEntities(value)
    }
    return attrs
}

private fun decodeEntities(value: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < value.length) {
        if (value[i] == '&') {
            val decoded = decodeEntity(value, i)
            if (decoded == null) {
                out.append(value[i])
                i += 1
            } else {
                out.append(decoded.first)
                i = decoded.second
            }
        } else {
            out.append(value[i])
            i += 1
        }
    }
    return out.toString()
}

private fun decodeEntity(html: String, start: Int): Pair<String, Int>? {
    if (start >= html.length || html[start] != '&') return null
    val semi = html.indexOf(';', start + 1)
    if (semi < 0 || semi - start > 16) return null
    val body = html.substring(start + 1, semi)
    val text = when {
        body == "amp" -> "&"
        body == "lt" -> "<"
        body == "gt" -> ">"
        body == "quot" -> "\""
        body == "nbsp" -> "\u00A0"
        body.length > 2 && body[0] == '#' && (body[1] == 'x' || body[1] == 'X') ->
            codePoint(body.substring(2), 16)
        body.length > 1 && body[0] == '#' -> codePoint(body.substring(1), 10)
        else -> null
    } ?: return null
    return text to (semi + 1)
}

private fun codePoint(digits: String, radix: Int): String? {
    if (digits.isEmpty()) return null
    val cp = digits.toIntOrNull(radix) ?: return null
    if (cp < 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return null
    return String(Character.toChars(cp))
}

private fun collapseBlankLines(text: String): String {
    val normalized = buildString(text.length) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\r') {
                append('\n')
                if (i + 1 < text.length && text[i + 1] == '\n') i += 1
            } else {
                append(c)
            }
            i += 1
        }
    }
    return Regex("\n{3,}").replace(normalized, "\n\n").trim('\n')
}
