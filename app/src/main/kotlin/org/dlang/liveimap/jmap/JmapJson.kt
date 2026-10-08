package org.dlang.liveimap.jmap

internal sealed class Json {
    data class Str(val text: String) : Json()
    data class Obj(val fields: Map<String, Json>) : Json()
    data class Arr(val values: List<Json>) : Json()
    data class Num(val text: String) : Json()
    data class Bool(val value: Boolean) : Json()
    data object Null : Json()
}

internal class JsonBroken : Exception()

internal class JsonParser(private val text: String) {
    private var i = 0

    fun parseDocument(): Json {
        skipWs()
        if (i >= text.length) broken()
        val value = parseValue()
        skipWs()
        if (i != text.length) broken()
        return value
    }

    private fun parseObject(): Map<String, Json> {
        expect('{')
        skipWs()
        val fields = mutableMapOf<String, Json>()
        if (consume('}')) return fields
        while (true) {
            skipWs()
            if (i >= text.length || text[i] != '"') broken()
            val key = parseString()
            skipWs()
            expect(':')
            fields[key] = parseValue()
            skipWs()
            when {
                consume('}') -> return fields
                consume(',') -> Unit
                else -> broken()
            }
        }
    }

    private fun parseArray(): List<Json> {
        expect('[')
        skipWs()
        if (consume(']')) return emptyList()
        val values = mutableListOf<Json>()
        while (true) {
            values.add(parseValue())
            skipWs()
            when {
                consume(']') -> return values
                consume(',') -> Unit
                else -> broken()
            }
        }
    }

    private fun parseValue(): Json {
        skipWs()
        if (i >= text.length) broken()
        val c = text[i]
        return when {
            c == '"' -> Json.Str(parseString())
            c == '{' -> Json.Obj(parseObject())
            c == '[' -> Json.Arr(parseArray())
            c == 't' -> {
                literal("true")
                Json.Bool(true)
            }
            c == 'f' -> {
                literal("false")
                Json.Bool(false)
            }
            c == 'n' -> {
                literal("null")
                Json.Null
            }
            c == '-' || c in '0'..'9' -> Json.Num(parseNumber())
            else -> broken()
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
                c.code < 0x20 -> broken()
                else -> out.append(c)
            }
        }
        broken()
    }

    private fun parseEscape(): Char {
        if (i >= text.length) broken()
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
            else -> broken()
        }
    }

    // One \uXXXX is one UTF-16 code unit, not a whole scalar.
    private fun parseHexUnit(): Char {
        if (i + 4 > text.length) broken()
        var code = 0
        repeat(4) {
            val h = text[i++]
            val digit = when (h) {
                in '0'..'9' -> h - '0'
                in 'a'..'f' -> h - 'a' + 10
                in 'A'..'F' -> h - 'A' + 10
                else -> broken()
            }
            code = (code shl 4) or digit
        }
        return code.toChar()
    }

    private fun parseNumber(): String {
        val start = i
        if (consume('-') && i >= text.length) broken()
        if (i >= text.length) broken()
        if (text[i] == '0') {
            i++
        } else if (text[i] in '1'..'9') {
            while (i < text.length && text[i] in '0'..'9') i++
        } else {
            broken()
        }
        if (i < text.length && text[i] == '.') {
            i++
            if (i >= text.length || text[i] !in '0'..'9') broken()
            while (i < text.length && text[i] in '0'..'9') i++
        }
        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
            i++
            if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
            if (i >= text.length || text[i] !in '0'..'9') broken()
            while (i < text.length && text[i] in '0'..'9') i++
        }
        return text.substring(start, i)
    }

    private fun literal(word: String) {
        if (!text.startsWith(word, i)) broken()
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
        if (!consume(c)) broken()
    }

    private fun consume(c: Char): Boolean {
        if (i < text.length && text[i] == c) {
            i++
            return true
        }
        return false
    }

    private fun broken(): Nothing = throw JsonBroken()
}
