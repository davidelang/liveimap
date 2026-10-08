package org.dlang.liveimap.jmap

const val JMAP_MAIL = "urn:ietf:params:jmap:mail"

class JmapFailure(val text: String) : Exception(text)

data class JmapSession(
    val username: String,
    val apiUrl: String,
    val downloadUrl: String,
    val uploadUrl: String,
    val eventSourceUrl: String,
    val state: String,
    val capabilityIds: Set<String>,
    val primaryMailAccountId: String?,
) {
    fun offersMail(): Boolean = JMAP_MAIL in capabilityIds
}

fun parseJmapSession(text: String): JmapSession {
    if (text.isBlank()) throw JmapFailure("jmap session is empty")
    val root = JsonParser(text).parseRoot()
    val capabilities = requireObject(root, "capabilities")
    requireObject(root, "accounts")
    val primary = requireObject(root, "primaryAccounts")
    val mailAccount = primary[JMAP_MAIL]
    val primaryMailAccountId = when (mailAccount) {
        null -> null
        is Json.Str -> mailAccount.text
        else -> throw JmapFailure("jmap session primary mail account is not text")
    }
    return JmapSession(
        username = requireText(root, "username"),
        apiUrl = requireText(root, "apiUrl"),
        downloadUrl = requireText(root, "downloadUrl"),
        uploadUrl = requireText(root, "uploadUrl"),
        eventSourceUrl = requireText(root, "eventSourceUrl"),
        state = requireText(root, "state"),
        capabilityIds = capabilities.keys.toSet(),
        primaryMailAccountId = primaryMailAccountId,
    )
}

private fun requireText(fields: Map<String, Json>, name: String): String {
    val value = fields[name] ?: throw JmapFailure("jmap session lacks $name")
    if (value !is Json.Str) throw JmapFailure("jmap session $name is not text")
    return value.text
}

private fun requireObject(fields: Map<String, Json>, name: String): Map<String, Json> {
    val value = fields[name] ?: throw JmapFailure("jmap session lacks $name")
    if (value !is Json.Obj) throw JmapFailure("jmap session $name is not an object")
    return value.fields
}

private sealed class Json {
    data class Str(val text: String) : Json()
    data class Obj(val fields: Map<String, Json>) : Json()
    data object Arr : Json()
    data object Num : Json()
    data object Bool : Json()
    data object Null : Json()
}

private class JsonParser(private val text: String) {
    private var i = 0

    fun parseRoot(): Map<String, Json> {
        skipWs()
        if (i >= text.length || text[i] != '{') notObject()
        val fields = parseObject()
        skipWs()
        if (i != text.length) notObject()
        return fields
    }

    private fun parseObject(): Map<String, Json> {
        expect('{')
        skipWs()
        val fields = mutableMapOf<String, Json>()
        if (consume('}')) return fields
        while (true) {
            skipWs()
            if (i >= text.length || text[i] != '"') notObject()
            val key = parseString()
            skipWs()
            expect(':')
            fields[key] = parseValue()
            skipWs()
            when {
                consume('}') -> return fields
                consume(',') -> Unit
                else -> notObject()
            }
        }
    }

    private fun parseArray() {
        expect('[')
        skipWs()
        if (consume(']')) return
        while (true) {
            parseValue()
            skipWs()
            when {
                consume(']') -> return
                consume(',') -> Unit
                else -> notObject()
            }
        }
    }

    private fun parseValue(): Json {
        skipWs()
        if (i >= text.length) notObject()
        val c = text[i]
        return when {
            c == '"' -> Json.Str(parseString())
            c == '{' -> Json.Obj(parseObject())
            c == '[' -> {
                parseArray()
                Json.Arr
            }
            c == 't' -> {
                literal("true")
                Json.Bool
            }
            c == 'f' -> {
                literal("false")
                Json.Bool
            }
            c == 'n' -> {
                literal("null")
                Json.Null
            }
            c == '-' || c in '0'..'9' -> {
                parseNumber()
                Json.Num
            }
            else -> notObject()
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (i < text.length) {
            val c = text[i++]
            when {
                c == '"' -> return out.toString()
                c == '\\' -> out.append(parseEscape())
                c.code < 0x20 -> notObject()
                else -> out.append(c)
            }
        }
        notObject()
    }

    private fun parseEscape(): Char {
        if (i >= text.length) notObject()
        return when (val c = text[i++]) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseHexUnit()
            else -> notObject()
        }
    }

    // One \uXXXX is one UTF-16 code unit, not a whole scalar.
    private fun parseHexUnit(): Char {
        if (i + 4 > text.length) notObject()
        var code = 0
        repeat(4) {
            val h = text[i++]
            val digit = when (h) {
                in '0'..'9' -> h - '0'
                in 'a'..'f' -> h - 'a' + 10
                in 'A'..'F' -> h - 'A' + 10
                else -> notObject()
            }
            code = (code shl 4) or digit
        }
        return code.toChar()
    }

    private fun parseNumber() {
        if (consume('-') && i >= text.length) notObject()
        if (i >= text.length) notObject()
        if (text[i] == '0') {
            i++
        } else if (text[i] in '1'..'9') {
            while (i < text.length && text[i] in '0'..'9') i++
        } else {
            notObject()
        }
        if (i < text.length && text[i] == '.') {
            i++
            if (i >= text.length || text[i] !in '0'..'9') notObject()
            while (i < text.length && text[i] in '0'..'9') i++
        }
        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
            i++
            if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
            if (i >= text.length || text[i] !in '0'..'9') notObject()
            while (i < text.length && text[i] in '0'..'9') i++
        }
    }

    private fun literal(word: String) {
        if (!text.startsWith(word, i)) notObject()
        i += word.length
    }

    private fun skipWs() {
        while (i < text.length) {
            when (text[i]) {
                ' ', '\t', '\n', '\r' -> i++
                else -> return
            }
        }
    }

    private fun expect(c: Char) {
        if (!consume(c)) notObject()
    }

    private fun consume(c: Char): Boolean {
        if (i < text.length && text[i] == c) {
            i++
            return true
        }
        return false
    }

    private fun notObject(): Nothing = throw JmapFailure("jmap session is not an object")
}
