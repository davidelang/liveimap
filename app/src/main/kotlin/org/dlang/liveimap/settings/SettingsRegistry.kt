package org.dlang.liveimap.settings

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.dlang.liveimap.ui.toolbar.ToolbarStore

private val Context.globalSettingsDataStore by preferencesDataStore(
    name = "global_settings",
    corruptionHandler = ReplaceFileCorruptionHandler<Preferences> {
        throw IllegalStateException("global_settings corrupt")
    },
)

private val globalSettingsValue = stringPreferencesKey("value")

sealed class JsonValue {
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()
    data class Str(val value: String) : JsonValue()
    data class Num(val value: Long) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data object Null : JsonValue()
}

class SettingsTreeParseException(message: String) : IllegalArgumentException(message)

private class JsonParser(private val text: String) {
    private var i = 0

    fun parse(): JsonValue {
        skip()
        val value = value()
        skip()
        if (i != text.length) throw SettingsTreeParseException("settings-tree.json trailing text")
        return value
    }

    private fun value(): JsonValue {
        skip()
        if (i >= text.length) throw SettingsTreeParseException("settings-tree.json truncated")
        return when (text[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> JsonValue.Str(str())
            't' -> lit("true", JsonValue.Bool(true))
            'f' -> lit("false", JsonValue.Bool(false))
            'n' -> lit("null", JsonValue.Null)
            else -> num()
        }
    }

    private fun obj(): JsonValue.Obj {
        expect('{')
        val fields = linkedMapOf<String, JsonValue>()
        skip()
        if (peek('}')) {
            i++
            return JsonValue.Obj(fields)
        }
        while (true) {
            skip()
            if (i >= text.length || text[i] != '"') throw SettingsTreeParseException("settings-tree.json object key")
            val key = str()
            skip()
            expect(':')
            if (key in fields) throw SettingsTreeParseException("settings-tree.json duplicate key $key")
            fields[key] = value()
            skip()
            when {
                peek(',') -> {
                    i++
                    skip()
                    if (peek('}')) {
                        i++
                        return JsonValue.Obj(fields)
                    }
                }
                peek('}') -> {
                    i++
                    return JsonValue.Obj(fields)
                }
                else -> throw SettingsTreeParseException("settings-tree.json object")
            }
        }
    }

    private fun arr(): JsonValue.Arr {
        expect('[')
        val items = ArrayList<JsonValue>()
        skip()
        if (peek(']')) {
            i++
            return JsonValue.Arr(items)
        }
        while (true) {
            items.add(value())
            skip()
            when {
                peek(',') -> {
                    i++
                    skip()
                    if (peek(']')) {
                        i++
                        return JsonValue.Arr(items)
                    }
                }
                peek(']') -> {
                    i++
                    return JsonValue.Arr(items)
                }
                else -> throw SettingsTreeParseException("settings-tree.json array")
            }
        }
    }

    private fun str(): String {
        expect('"')
        val out = StringBuilder()
        while (i < text.length) {
            val c = text[i++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> {
                    if (i >= text.length) throw SettingsTreeParseException("settings-tree.json string")
                    when (val e = text[i++]) {
                        '"', '\\', '/' -> out.append(e)
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            if (i + 4 > text.length) throw SettingsTreeParseException("settings-tree.json unicode")
                            val hex = text.substring(i, i + 4)
                            i += 4
                            out.append(hex.toInt(16).toChar())
                        }
                        else -> throw SettingsTreeParseException("settings-tree.json escape")
                    }
                }
                else -> out.append(c)
            }
        }
        throw SettingsTreeParseException("settings-tree.json string")
    }

    private fun num(): JsonValue.Num {
        val start = i
        if (peek('-')) i++
        if (i >= text.length || !text[i].isDigit()) throw SettingsTreeParseException("settings-tree.json number")
        if (text[i] == '0') {
            i++
        } else {
            while (i < text.length && text[i].isDigit()) i++
        }
        if (i < text.length && (text[i] == '.' || text[i] == 'e' || text[i] == 'E')) {
            throw SettingsTreeParseException("settings-tree.json number")
        }
        val raw = text.substring(start, i)
        val value = raw.toLongOrNull() ?: throw SettingsTreeParseException("settings-tree.json number")
        return JsonValue.Num(value)
    }

    private fun lit(word: String, value: JsonValue): JsonValue {
        if (!text.startsWith(word, i)) throw SettingsTreeParseException("settings-tree.json literal")
        i += word.length
        return value
    }

    private fun expect(c: Char) {
        if (i >= text.length || text[i] != c) throw SettingsTreeParseException("settings-tree.json expected $c")
        i++
    }

    private fun peek(c: Char) = i < text.length && text[i] == c

    private fun skip() {
        while (i < text.length && text[i].isWhitespace()) i++
    }
}

fun parseJson(text: String): JsonValue = JsonParser(text).parse()


fun sha256Hex(bytes: ByteArray): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

suspend fun startupSettingsError(context: Context): String? {
    installGeneratedSettings()
    SettingsTreeGenerated.warm()
    return try {
        SettingsHub.prepare(context)
        ToolbarStore.install(SettingsHub.uiNow().toolbars)
        null
    } catch (e: Exception) {
        e.message ?: "global_settings corrupt"
    }
}

internal fun installGlobals(record: String) {
    SettingsHub.adoptRecord(record)
}

fun readUiLine(record: String, key: String): String? {
    for (line in record.lines()) {
        val eq = line.indexOf('=')
        if (eq > 0 && line.substring(0, eq) == key) return line.substring(eq + 1)
    }
    return null
}

internal fun withGlobalLine(record: String, key: String, value: String): String {
    val next = "$key=$value"
    if (record.isEmpty()) return next
    val parts = record.split('\n').toMutableList()
    val index = parts.indexOfFirst { line ->
        val eq = line.indexOf('=')
        eq > 0 && line.substring(0, eq) == key
    }
    if (index >= 0) {
        parts[index] = next
    } else if (parts.last().isEmpty()) {
        parts.add(parts.lastIndex, next)
    } else {
        parts.add(next)
    }
    return parts.joinToString("\n")
}

fun updateGlobalRecord(record: String, key: String, text: String): String {
    val spec = settingsRegistry[key] ?: throw IllegalArgumentException(key)
    if (spec.scope != SettingScope.Ui) throw IllegalArgumentException(key)
    if (!globalValueOk(spec, text)) throw IllegalArgumentException(key)
    return withGlobalLine(record, key, text)
}

internal suspend fun readGlobalRecord(context: Context): String =
    context.globalSettingsDataStore.data.first()[globalSettingsValue].orEmpty()

internal suspend fun writeGlobalRecord(context: Context, record: String) {
    context.globalSettingsDataStore.edit { prefs ->
        prefs[globalSettingsValue] = record
    }
}

suspend fun globalSettingsStoredKeys(context: Context): Set<String> =
    context.globalSettingsDataStore.data.first().asMap().keys.map { it.name }.toSet()

suspend fun readGlobalSettings(context: Context): String = readGlobalRecord(context)

/** The stored UI record, including later edits to appTextZoom. */
fun globalSettingsRecord(context: Context): Flow<String> =
    context.applicationContext.globalSettingsDataStore.data.map { prefs ->
        prefs[globalSettingsValue].orEmpty()
    }

suspend fun saveGlobal(context: Context, key: String, text: String) {
    var stored = ""
    context.globalSettingsDataStore.edit { prefs ->
        val record = prefs[globalSettingsValue].orEmpty()
        stored = updateGlobalRecord(record, key, text)
        prefs[globalSettingsValue] = stored
    }
    installGlobals(stored)
    if (key == "toolbars") ToolbarStore.install(stored)
}

