package org.dlang.liveimap.engine.sieve

import java.nio.charset.StandardCharsets

/** One ManageSieve line. The transport does not include a trailing CR or LF. */
interface SieveLineTransport {
    suspend fun readLine(): String
    suspend fun writeLine(line: String)

    suspend fun readBytes(count: Int): ByteArray {
        if (count < 0) throw SieveFailure("literal")
        throw SieveFailure("literal")
    }

    suspend fun writeBytes(bytes: ByteArray) {
        throw SieveFailure("literal")
    }
}

data class SieveCapabilities(
    val implementation: String = "",
    val version: String = "",
    val sasl: List<String> = emptyList(),
    val extensions: List<String> = emptyList(),
    val startTls: Boolean = false,
)

class SieveFailure(val text: String) : Exception(text)

data class ListedScript(
    val name: String,
    val active: Boolean,
)

/** Greeting only. Does not open a socket. */
suspend fun readGreeting(transport: SieveLineTransport): SieveCapabilities {
    return readUntilResponse(transport, recordCapabilities = true)
}

/** Writes LOGOUT and waits for OK. Does not open a socket. */
suspend fun logout(transport: SieveLineTransport) {
    transport.writeLine("LOGOUT")
    readUntilResponse(transport, recordCapabilities = false)
}

/** Lists script names in server order. Does not open a socket. */
suspend fun listScripts(transport: SieveLineTransport): List<ListedScript> {
    transport.writeLine("LISTSCRIPTS")
    val scripts = ArrayList<ListedScript>()
    while (true) {
        val line = transport.readLine()
        if (line.isEmpty()) throw SieveFailure("empty")
        if (hasUnquotedBrace(line)) throw SieveFailure("literal")
        val token = responseToken(line)
        if (token != null) {
            if (token == "OK") {
                responseHumanText(line)
                return scripts
            }
            throw SieveFailure(responseHumanText(line) ?: line)
        }
        scripts.add(listedScript(line))
    }
}

/** Reads one script literal. Does not open a socket. */
suspend fun getScript(transport: SieveLineTransport, name: String): String {
    transport.writeLine("GETSCRIPT ${quoteScriptName(name)}")
    val header = transport.readLine()
    if (header.isEmpty()) throw SieveFailure("empty")
    val token = responseToken(header)
    if (token == "NO" || token == "BYE") {
        throw SieveFailure(commandFailureText(transport, header))
    }
    val count = wholeLineLiteral(header) ?: throw SieveFailure("literal")
    val text = String(transport.readBytes(count), StandardCharsets.UTF_8)
    commandResult(transport, transport.readLine())
    return text
}

/** Uploads one script. Plain OK returns an empty warnings string. */
suspend fun putScript(transport: SieveLineTransport, name: String, script: String): String {
    return writeScriptCommand(transport, "PUTSCRIPT ${quoteScriptName(name)}", script)
}

/** Checks one script. Plain OK returns an empty warnings string. */
suspend fun checkScript(transport: SieveLineTransport, script: String): String {
    return writeScriptCommand(transport, "CHECKSCRIPT", script)
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

private suspend fun writeScriptCommand(
    transport: SieveLineTransport,
    command: String,
    script: String,
): String {
    val bytes = script.toByteArray(StandardCharsets.UTF_8)
    transport.writeLine("$command {${bytes.size}+}")
    transport.writeBytes(bytes)
    return commandResult(transport, transport.readLine())
}

private suspend fun commandResult(transport: SieveLineTransport, line: String): String {
    if (line.isEmpty()) throw SieveFailure("empty")
    val token = responseToken(line) ?: throw SieveFailure("bad capability")
    if (token == "OK") {
        if (responseLiteralCount(line) != null) throw SieveFailure("literal")
        return responseHumanText(line) ?: ""
    }
    throw SieveFailure(commandFailureText(transport, line))
}

private suspend fun commandFailureText(transport: SieveLineTransport, line: String): String {
    val count = responseLiteralCount(line)
    if (count != null) {
        return String(transport.readBytes(count), StandardCharsets.UTF_8)
    }
    return responseHumanText(line) ?: line
}

private fun listedScript(line: String): ListedScript {
    if (line.isEmpty() || line[0] != '"') throw SieveFailure("bad capability")
    val quoted = readQuoted(line, 0)
    var index = skipBlank(line, quoted.second)
    if (index >= line.length) return ListedScript(quoted.first, active = false)
    val tokenStart = index
    while (index < line.length && line[index] != ' ' && line[index] != '\t') index++
    val token = line.substring(tokenStart, index)
    if (skipBlank(line, index) != line.length) throw SieveFailure("bad capability")
    if (!token.equals("ACTIVE", ignoreCase = true)) throw SieveFailure("bad capability")
    return ListedScript(quoted.first, active = true)
}

/** Same quoting as the Sieve emitter: wrap in quotes and escape \ and ". */
private fun quoteScriptName(raw: String): String {
    val cleaned = raw.filter { it != '\r' && it != '\n' && it != '\u0000' }
    val escaped = StringBuilder(cleaned.length + 2)
    for (ch in cleaned) {
        when (ch) {
            '\\' -> escaped.append("\\\\")
            '"' -> escaped.append("\\\"")
            else -> escaped.append(ch)
        }
    }
    return "\"$escaped\""
}

private fun wholeLineLiteral(line: String): Int? {
    if (line.length < 3 || line[0] != '{' || line[line.length - 1] != '}') return null
    var body = line.substring(1, line.length - 1)
    if (body.endsWith("+")) body = body.dropLast(1)
    if (body.isEmpty() || body.any { it !in '0'..'9' }) return null
    val value = body.toLongOrNull() ?: return null
    if (value > Int.MAX_VALUE) throw SieveFailure("literal")
    return value.toInt()
}

private fun responseLiteralCount(line: String): Int? {
    val token = responseToken(line) ?: return null
    var index = skipBlank(line, token.length)
    if (index < line.length && line[index] == '(') {
        index = skipParenthesized(line, index)
        index = skipBlank(line, index)
    }
    if (index >= line.length || line[index] != '{') return null
    return wholeLineLiteral(line.substring(index)) ?: throw SieveFailure("literal")
}
