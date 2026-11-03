package org.dlang.liveimap.settings

/** One evaluator for a settings-tree condition. [read] returns the stored text of a setting id. */
sealed class Condition {
    data class Equals(val setting: String, val expected: JsonValue) : Condition()
    data class NotEquals(val setting: String, val expected: JsonValue) : Condition()
    data class In(val setting: String, val expected: List<String>) : Condition()
    data class SdkAtLeast(val sdk: Int) : Condition()
    data class All(val parts: List<Condition>) : Condition()
    data class AnyOf(val parts: List<Condition>) : Condition()
    data class Empty(val setting: String) : Condition()
    data class Not(val part: Condition) : Condition()
}

fun Condition.holds(read: (String) -> String, sdk: Int): Boolean = when (this) {
    is Condition.All -> parts.all { it.holds(read, sdk) }
    is Condition.AnyOf -> parts.any { it.holds(read, sdk) }
    is Condition.SdkAtLeast -> sdk >= this.sdk
    is Condition.Empty -> isEmptyText(read(setting))
    is Condition.Equals -> sameValue(expected, read(setting))
    is Condition.NotEquals -> !sameValue(expected, read(setting))
    is Condition.In -> read(setting) in expected
    is Condition.Not -> !part.holds(read, sdk)
}

fun parseCondition(value: JsonValue, path: String): Condition {
    val obj = value as? JsonValue.Obj ?: throw SettingsTreeParseException("$path condition")
    val keys = obj.fields.keys
    return when {
        "all" in keys -> {
            requireConditionKeys(keys, setOf("all"), path)
            Condition.All(conditionList(obj.fields.getValue("all"), path))
        }
        "any" in keys -> {
            requireConditionKeys(keys, setOf("any"), path)
            Condition.AnyOf(conditionList(obj.fields.getValue("any"), path))
        }
        "sdkAtLeast" in keys -> {
            requireConditionKeys(keys, setOf("sdkAtLeast"), path)
            val n = obj.fields.getValue("sdkAtLeast") as? JsonValue.Num
                ?: throw SettingsTreeParseException("$path sdkAtLeast")
            Condition.SdkAtLeast(n.value.toInt())
        }
        "setting" in keys -> parseSettingCondition(obj, path)
        "not" in keys -> {
            requireConditionKeys(keys, setOf("not"), path)
            Condition.Not(parseCondition(obj.fields.getValue("not"), path))
        }
        else -> throw SettingsTreeParseException("$path condition")
    }
}

private fun parseSettingCondition(obj: JsonValue.Obj, path: String): Condition {
    val setting = (obj.fields["setting"] as? JsonValue.Str)?.value?.takeIf { it.isNotEmpty() }
        ?: throw SettingsTreeParseException("$path setting")
    val form = obj.fields.keys - "setting"
    return when {
        form == setOf("equals") -> Condition.Equals(setting, obj.fields.getValue("equals"))
        form == setOf("notEquals") -> Condition.NotEquals(setting, obj.fields.getValue("notEquals"))
        form == setOf("in") -> {
            val arr = obj.fields.getValue("in") as? JsonValue.Arr ?: throw SettingsTreeParseException("$path in")
            Condition.In(setting, arr.items.map {
                (it as? JsonValue.Str)?.value ?: throw SettingsTreeParseException("$path in")
            })
        }
        form == setOf("empty") -> {
            val flag = obj.fields.getValue("empty") as? JsonValue.Bool
                ?: throw SettingsTreeParseException("$path empty")
            if (!flag.value) throw SettingsTreeParseException("$path empty")
            Condition.Empty(setting)
        }
        else -> throw SettingsTreeParseException("$path condition")
    }
}

private fun conditionList(value: JsonValue, path: String): List<Condition> {
    val arr = value as? JsonValue.Arr ?: throw SettingsTreeParseException("$path list")
    if (arr.items.isEmpty()) throw SettingsTreeParseException("$path list")
    return arr.items.map { parseCondition(it, path) }
}

private fun requireConditionKeys(keys: Set<String>, allowed: Set<String>, path: String) {
    if (keys != allowed) throw SettingsTreeParseException("$path condition")
}

/** Stored text, not a percent-encoded list. `@` and spaces are not empty and must not be decoded. */
private fun isEmptyText(actual: String): Boolean = actual.isEmpty()

private fun sameValue(expected: JsonValue, actual: String): Boolean = when (expected) {
    is JsonValue.Bool -> actual == expected.value.toString()
    is JsonValue.Str -> actual == expected.value
    is JsonValue.Num -> actual == expected.value.toString()
    is JsonValue.Arr -> {
        val want = expected.items.map {
            when (it) {
                is JsonValue.Str -> it.value
                is JsonValue.Num -> it.value.toString()
                else -> return false
            }
        }
        decodeWatchedFolders(actual) == want || decodeCompletionSources(actual) == want
    }
    else -> false
}
