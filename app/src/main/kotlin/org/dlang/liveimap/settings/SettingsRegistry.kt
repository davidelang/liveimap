package org.dlang.liveimap.settings

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.first
import org.dlang.liveimap.ui.toolbar.ToolbarStore

private val Context.globalSettingsDataStore by preferencesDataStore(
    name = "global_settings",
    corruptionHandler = ReplaceFileCorruptionHandler<Preferences> {
        throw IllegalStateException("global_settings corrupt")
    },
)

private val globalSettingsValue = stringPreferencesKey("value")

object InstalledGlobals {
    val maxRawViewBytes = AtomicInteger(SettingsDefaults.maxRawViewBytes.toInt())
    val maxImageTrustBytes = AtomicInteger(SettingsDefaults.maxImageTrustBytes.toInt())
    val indexPageSize = AtomicInteger(SettingsDefaults.indexPageSize)
    val swipeThresholdPercent = AtomicInteger(SettingsDefaults.swipeThresholdPercent)
    val moveChunk = AtomicInteger(SettingsDefaults.moveChunk)
    val clientSortChunk = AtomicInteger(SettingsDefaults.clientSortChunk)
    val reconnectDelaysSec = AtomicReference(listOf(2, 10, 60))
}


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
                peek(',') -> i++
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
                peek(',') -> i++
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
    val bytes = context.assets.open("settings-tree.json").use { it.readBytes() }
    val hash = sha256Hex(bytes)
    if (hash != SettingsTreeGenerated.fingerprint) {
        return "settings-tree.json sha256 $hash does not match the built tree ${SettingsTreeGenerated.fingerprint}. Changing the tree takes a rebuild."
    }
    val text = bytes.toString(Charsets.UTF_8)
    val errors = validateSettingsTree(text)
    if (errors.isNotEmpty()) return errors.joinToString("\n")
    val titles = groupTitles(parseSettingsTree(text))
    if (titles.size != SettingsTreeGenerated.groupIds.size) {
        return "settings tree group count ${titles.size} does not match the build. Changing the tree takes a rebuild."
    }
    installSettingsTree(text)
    SettingsTreeGenerated.warm()
    return try {
        val record = context.globalSettingsDataStore.data.first()[globalSettingsValue].orEmpty()
        val error = globalRecordError(record)
        if (error == null) {
            installGlobals(record)
            ToolbarStore.install(record)
        }
        error
    } catch (e: Exception) {
        e.message ?: "global_settings corrupt"
    }
}

internal fun installGlobals(record: String) {
    val defaults = mapOf(
        "maxRawViewBytes" to SettingsDefaults.maxRawViewBytes.toInt(),
        "maxImageTrustBytes" to SettingsDefaults.maxImageTrustBytes.toInt(),
        "indexPageSize" to SettingsDefaults.indexPageSize,
        "swipeThresholdPercent" to SettingsDefaults.swipeThresholdPercent,
        "moveChunk" to SettingsDefaults.moveChunk,
        "clientSortChunk" to SettingsDefaults.clientSortChunk,
    )
    val values = defaults.toMutableMap()
    var delays = listOf(2, 10, 60)
    for (line in record.lines()) {
        if (line.isEmpty()) continue
        val eq = line.indexOf('=')
        if (eq <= 0) continue
        val key = line.substring(0, eq)
        if (key !in uiKeysInstalled()) continue
        val text = line.substring(eq + 1)
        if (key == "reconnectDelaysSec") {
            val parsed = ArrayList<Int>()
            var bad = false
            for (part in text.split(",")) {
                val number = part.toIntOrNull()
                if (number == null) {
                    bad = true
                    break
                }
                parsed.add(number)
            }
            if (bad || parsed.isEmpty()) {
                android.util.Log.w("LiveIMAP", "unparseable global $line")
                continue
            }
            delays = parsed
            continue
        }
        val number = text.toIntOrNull()
        if (number == null) {
            android.util.Log.w("LiveIMAP", "unparseable global $line")
            continue
        }
        if (key in values) values[key] = number
    }
    InstalledGlobals.maxRawViewBytes.set(values.getValue("maxRawViewBytes"))
    InstalledGlobals.maxImageTrustBytes.set(values.getValue("maxImageTrustBytes"))
    InstalledGlobals.indexPageSize.set(values.getValue("indexPageSize"))
    InstalledGlobals.swipeThresholdPercent.set(values.getValue("swipeThresholdPercent"))
    InstalledGlobals.moveChunk.set(values.getValue("moveChunk"))
    InstalledGlobals.clientSortChunk.set(values.getValue("clientSortChunk"))
    InstalledGlobals.reconnectDelaysSec.set(delays)
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

suspend fun globalSettingsStoredKeys(context: Context): Set<String> =
    context.globalSettingsDataStore.data.first().asMap().keys.map { it.name }.toSet()

suspend fun readGlobalSettings(context: Context): String = readGlobalRecord(context)

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

