package org.dlang.liveimap.engine.sieve

/** One ManageSieve line. The transport does not include a trailing CR or LF. */
interface SieveLineTransport {
    suspend fun readLine(): String
    suspend fun writeLine(line: String)
}

data class SieveCapabilities(
    val implementation: String = "",
    val version: String = "",
    val sasl: List<String> = emptyList(),
    val extensions: List<String> = emptyList(),
    val startTls: Boolean = false,
)

class SieveFailure(val text: String) : Exception(text)

/** Greeting only. Does not open a socket. */
suspend fun readGreeting(transport: SieveLineTransport): SieveCapabilities {
    return readUntilResponse(transport, recordCapabilities = true)
}

/** Writes LOGOUT and waits for OK. Does not open a socket. */
suspend fun logout(transport: SieveLineTransport) {
    transport.writeLine("LOGOUT")
    readUntilResponse(transport, recordCapabilities = false)
}

private class CapabilityBuilder {
    var implementation: String = ""
    var version: String = ""
    val sasl = ArrayList<String>()
    val extensions = ArrayList<String>()
    var startTls: Boolean = false

    fun build(): SieveCapabilities {
        return SieveCapabilities(
            implementation = implementation,
            version = version,
            sasl = sasl.toList(),
            extensions = extensions.toList(),
            startTls = startTls,
        )
    }
}

private suspend fun readUntilResponse(
    transport: SieveLineTransport,
    recordCapabilities: Boolean,
): SieveCapabilities {
    val builder = CapabilityBuilder()
    while (true) {
        val line = transport.readLine()
        if (line.isEmpty()) throw SieveFailure("empty")
        if (hasUnquotedBrace(line)) throw SieveFailure("literal")
        val token = responseToken(line)
        if (token != null) {
            if (token == "OK") {
                responseHumanText(line)
                return builder.build()
            }
            throw SieveFailure(responseHumanText(line) ?: line)
        }
        if (line.startsWith("\"")) {
            val parts = quotedStrings(line)
            if (recordCapabilities) applyCapability(builder, parts)
            continue
        }
        throw SieveFailure("bad capability")
    }
}

private fun applyCapability(builder: CapabilityBuilder, parts: List<String>) {
    if (parts.isEmpty()) throw SieveFailure("bad capability")
    val name = parts[0]
    val args = parts.subList(1, parts.size)
    when {
        name.equals("STARTTLS", ignoreCase = true) -> builder.startTls = true
        name.equals("SASL", ignoreCase = true) -> {
            for (arg in args) builder.sasl.addAll(splitTokens(arg))
        }
        name.equals("SIEVE", ignoreCase = true) -> {
            for (arg in args) builder.extensions.addAll(splitTokens(arg))
        }
        name.equals("IMPLEMENTATION", ignoreCase = true) -> {
            builder.implementation = args.joinToString(" ")
        }
        name.equals("VERSION", ignoreCase = true) -> {
            if (args.isNotEmpty()) builder.version = args[0]
        }
    }
}

private fun responseToken(line: String): String? {
    for (token in arrayOf("OK", "NO", "BYE")) {
        if (line.length < token.length) continue
        if (!line.regionMatches(0, token, 0, token.length, ignoreCase = true)) continue
        if (line.length == token.length || line[token.length] == ' ') return token
    }
    return null
}

private fun responseHumanText(line: String): String? {
    val token = responseToken(line) ?: return null
    var index = skipBlank(line, token.length)
    if (index < line.length && line[index] == '(') {
        index = skipParenthesized(line, index)
        index = skipBlank(line, index)
    }
    var text: String? = null
    if (index < line.length && line[index] == '"') {
        val quoted = readQuoted(line, index)
        text = quoted.first
        index = quoted.second
    }
    while (index < line.length) {
        val ch = line[index]
        if (ch == ' ' || ch == '\t') {
            index++
            continue
        }
        if (ch == '"') {
            index = readQuoted(line, index).second
            continue
        }
        if (ch == '{') throw SieveFailure("literal")
        index++
    }
    return text
}

private fun skipParenthesized(line: String, open: Int): Int {
    var depth = 1
    var index = open + 1
    while (index < line.length && depth > 0) {
        val ch = line[index]
        if (ch == '"') {
            index = readQuoted(line, index).second
            continue
        }
        if (ch == '{') throw SieveFailure("literal")
        if (ch == '(') depth++
        if (ch == ')') depth--
        index++
    }
    if (depth != 0) throw SieveFailure("bad capability")
    return index
}

private fun quotedStrings(line: String): List<String> {
    val parts = ArrayList<String>()
    var index = 0
    while (index < line.length) {
        val ch = line[index]
        if (ch == ' ' || ch == '\t') {
            index++
            continue
        }
        if (ch == '"') {
            val quoted = readQuoted(line, index)
            parts.add(quoted.first)
            index = quoted.second
            continue
        }
        if (ch == '{') throw SieveFailure("literal")
        throw SieveFailure("bad capability")
    }
    return parts
}

private fun readQuoted(line: String, open: Int): Pair<String, Int> {
    val out = StringBuilder()
    var index = open + 1
    while (index < line.length) {
        val ch = line[index]
        if (ch == '\\') {
            if (index + 1 >= line.length) throw SieveFailure("bad capability")
            val next = line[index + 1]
            if (next == '\\' || next == '"') {
                out.append(next)
                index += 2
                continue
            }
            out.append(ch)
            index++
            continue
        }
        if (ch == '"') return out.toString() to (index + 1)
        out.append(ch)
        index++
    }
    throw SieveFailure("bad capability")
}

private fun splitTokens(value: String): List<String> {
    val tokens = ArrayList<String>()
    val current = StringBuilder()
    for (ch in value) {
        if (ch == ' ' || ch == '\t') {
            if (current.isNotEmpty()) {
                tokens.add(current.toString())
                current.clear()
            }
        } else {
            current.append(ch)
        }
    }
    if (current.isNotEmpty()) tokens.add(current.toString())
    return tokens
}

private fun skipBlank(line: String, start: Int): Int {
    var index = start
    while (index < line.length && (line[index] == ' ' || line[index] == '\t')) index++
    return index
}

private fun hasUnquotedBrace(line: String): Boolean {
    var quoted = false
    var index = 0
    while (index < line.length) {
        val ch = line[index]
        if (quoted) {
            if (ch == '\\') {
                if (index + 1 >= line.length) return false
                index += 2
                continue
            }
            if (ch == '"') quoted = false
            index++
            continue
        }
        if (ch == '"') {
            quoted = true
            index++
            continue
        }
        if (ch == '{') return true
        index++
    }
    return false
}
