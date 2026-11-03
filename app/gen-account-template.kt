data class Settings(
/* @gen-fields */
    val unknownSettings: List<String> = emptyList(),
    /** Keys present in the JSON document this value was decoded from. Absent keys are not a warning. */
    val storedKeys: Set<String> = emptySet(),
) {
    val preferHtml: Boolean
        get() = bodyView == BodyView.PlainOrHtml

    /** tlsMode, then deletePolicy, when that raw value is still kept. */
    fun preservedSecurityKey(): String? {
        val present = preservedKeys()
        if ("tlsMode" in present) return "tlsMode"
        if ("deletePolicy" in present) return "deletePolicy"
        return null
    }

    /** The raw sieveTlsMode line, when it is still kept. Not a preservedSecurityKey. */
    fun preservedSieveTlsKey(): String? =
        if ("sieveTlsMode" in preservedKeys()) "sieveTlsMode" else null

    fun preservedKeys(): Set<String> = unknownSettings.map { it.substringBefore('\n') }.toSet()

    /** A chosen value, including the default, drops every kept value for [key]. */
    fun withoutPreserved(key: String): Settings {
        val kept = ArrayList<String>()
        for (entry in unknownSettings) {
            if (entry.substringBefore('\n') == key) continue
            kept.add(entry)
        }
        return copy(unknownSettings = kept)
    }
}

private val fieldNames = listOf(
/* @gen-names */
)

internal fun decodeIntList(text: String): List<Int> {
    if (text.isEmpty()) return emptyList()
    return text.split(',').map { it.trim().toInt() }
}

private val freshSettings: Settings by lazy { Settings() }

private fun Settings.writeDocument(uiOnly: Boolean, dropForeign: Set<String>): String {
    val fields = linkedMapOf<String, JsonValue>()
    val written = HashSet<String>()
    val fresh = freshSettings
    fun put(key: String, value: JsonValue) {
        val raw = preservedJson(key)
        if (raw != null && getSetting(key) == fresh.getSetting(key)) {
            fields[key] = raw
        } else {
            fields[key] = value
        }
        written.add(key)
    }
    if (uiOnly) {
/* @gen-ui-encode */
    } else {
/* @gen-encode */
    }
    for (key in storedKeys) {
        if (key in written || key in documentSecretKeys || key in dropForeign) continue
        val spec = settingsRegistry[key] ?: continue
        val foreign = (spec.scope == SettingScope.Ui) != uiOnly
        if (!foreign) continue
        val raw = preservedJson(key)
        val value = raw ?: try {
            parseJson(getSetting(key))
        } catch (_: Exception) {
            continue
        }
        fields[key] = value
        written.add(key)
    }
    for (entry in unknownSettings) {
        val key = entry.substringBefore('\n')
        if (key in written || key in documentSecretKeys) continue
        val parsed = try {
            parseJson(entry.substringAfter('\n'))
        } catch (_: Exception) {
            continue
        }
        fields[key] = parsed
    }
    return encodeJson(JsonValue.Obj(fields))
}

fun Settings.encode(dropForeign: Set<String> = emptySet()): String = writeDocument(uiOnly = false, dropForeign = dropForeign)

fun Settings.encodeUi(dropForeign: Set<String> = emptySet()): String = writeDocument(uiOnly = true, dropForeign = dropForeign)

fun Settings.withUiOf(ui: Settings): Settings = copy(
/* @gen-ui-copy */
)

fun decodeSettings(text: String): Settings {
    if (text.isBlank()) return Settings()
    val root = try {
        parseJson(text)
    } catch (error: Exception) {
        throw SettingsDocumentException(error.message ?: "settings document")
    }
    val obj = root as? JsonValue.Obj ?: throw SettingsDocumentException("settings document is not an object")
    val values = obj.fields
    val unknown = ArrayList<String>()
    val d = Settings()
    fun <T> field(key: String, default: T, read: (JsonValue) -> T): T {
        val raw = values[key] ?: return default
        return try {
            read(raw)
        } catch (_: Exception) {
            unknown += key + "\n" + encodeJson(raw)
            default
        }
    }
    for ((key, raw) in values) {
        if (key in fieldNames || key in documentSecretKeys) continue
        val lower = key.lowercase()
        if ("password" in lower || "secret" in lower || "token" in lower) continue
        unknown += key + "\n" + encodeJson(raw)
    }
    return Settings(
/* @gen-decode */
        unknownSettings = unknown,
        storedKeys = values.keys.filter { it !in documentSecretKeys }.toSet(),
    )
}

