package org.dlang.liveimap.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/** A summary line before Android resolves the resource names. */
sealed class SummaryPiece {
    data class Text(val name: String, val args: List<SummaryPiece> = emptyList()) : SummaryPiece()
    data class Plural(val name: String, val count: Int) : SummaryPiece()
    data class Literal(val text: String) : SummaryPiece()
    data class Number(val value: Long) : SummaryPiece()
}

fun summaryPiece(
    cases: List<JsonValue>,
    settings: Settings,
    accounts: Int,
    sdk: Int,
): SummaryPiece? {
    if (cases.isEmpty()) return null
    for (raw in cases) {
        val obj = raw as? JsonValue.Obj ?: continue
        val whenRaw = obj.fields["when"]
        val holds = whenRaw == null || parseCondition(whenRaw, "summary").holds({ settings.getSetting(it) }, sdk)
        if (holds) return pieceOf(obj, settings, accounts)
    }
    return null
}

fun renderSummary(
    piece: SummaryPiece,
    text: (name: String, args: List<Any>) -> String,
    plural: (name: String, count: Int) -> String,
): String = when (piece) {
    is SummaryPiece.Literal -> piece.text
    is SummaryPiece.Number -> piece.value.toString()
    is SummaryPiece.Plural -> plural(piece.name, piece.count)
    is SummaryPiece.Text -> text(piece.name, piece.args.map { summaryArg(it, text, plural) })
}

@Composable
fun summaryText(piece: SummaryPiece): String = when (piece) {
    is SummaryPiece.Literal -> piece.text
    is SummaryPiece.Number -> piece.value.toString()
    is SummaryPiece.Plural -> {
        val id = SettingsTreeGenerated.resource(piece.name)
        if (id == 0) piece.count.toString() else pluralStringResource(id, piece.count, piece.count)
    }
    is SummaryPiece.Text -> {
        val id = SettingsTreeGenerated.resource(piece.name)
        if (id == 0) {
            piece.name
        } else {
            val args = piece.args.map { summaryArgText(it) }
            if (args.isEmpty()) stringResource(id) else stringResource(id, *args.toTypedArray())
        }
    }
}

@Composable
private fun summaryArgText(piece: SummaryPiece): Any = when (piece) {
    is SummaryPiece.Number -> if (piece.value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        piece.value.toInt()
    } else {
        piece.value
    }
    is SummaryPiece.Literal -> piece.text
    is SummaryPiece.Plural -> piece.count
    is SummaryPiece.Text -> summaryText(piece)
}

private fun summaryArg(
    piece: SummaryPiece,
    text: (name: String, args: List<Any>) -> String,
    plural: (name: String, count: Int) -> String,
): Any = when (piece) {
    is SummaryPiece.Number -> if (piece.value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
        piece.value.toInt()
    } else {
        piece.value
    }
    is SummaryPiece.Literal -> piece.text
    is SummaryPiece.Plural -> piece.count
    is SummaryPiece.Text -> renderSummary(piece, text, plural)
}

private fun pieceOf(obj: JsonValue.Obj, settings: Settings, accounts: Int): SummaryPiece {
    val plural = (obj.fields["plural"] as? JsonValue.Str)?.value
    if (plural != null) {
        val countKey = (obj.fields["count"] as? JsonValue.Str)?.value ?: return SummaryPiece.Literal("")
        return SummaryPiece.Plural(plural, countOf(settings, countKey, accounts))
    }
    val text = (obj.fields["text"] as? JsonValue.Str)?.value
    if (text != null) {
        val args = (obj.fields["args"] as? JsonValue.Arr)?.items.orEmpty().map { argPiece(it, settings, accounts) }
        return SummaryPiece.Text(text, args)
    }
    val value = obj.fields["value"] ?: return SummaryPiece.Literal("")
    return argPiece(value, settings, accounts)
}

private fun argPiece(raw: JsonValue, settings: Settings, accounts: Int): SummaryPiece {
    val obj = raw as? JsonValue.Obj ?: return SummaryPiece.Literal("")
    val format = (obj.fields["format"] as? JsonValue.Str)?.value
    if (format == "rulesWho") return SummaryPiece.Literal(rulesSummaryWho(settings))
    val countKey = (obj.fields["count"] as? JsonValue.Str)?.value
    if (countKey != null) return SummaryPiece.Number(countOf(settings, countKey, accounts).toLong())
    val countSet = obj.fields["countSet"] as? JsonValue.Arr
    if (countSet != null) {
        val n = countSet.items.count { item ->
            val key = (item as? JsonValue.Str)?.value ?: return@count false
            settings.getSetting(key).isNotBlank()
        }
        return SummaryPiece.Number(n.toLong())
    }
    val key = (obj.fields["setting"] as? JsonValue.Str)?.value
        ?: (obj.fields["choiceTitle"] as? JsonValue.Str)?.value
        ?: return SummaryPiece.Literal("")
    val field = (obj.fields["field"] as? JsonValue.Str)?.value
    val rawText = fieldText(settings, key, field)
    val titles = obj.fields["titles"] as? JsonValue.Obj
    if (titles != null) {
        val name = (titles.fields[rawText] as? JsonValue.Str)?.value ?: return SummaryPiece.Literal(rawText)
        return SummaryPiece.Text(name)
    }
    if ("choiceTitle" in obj.fields) {
        return SummaryPiece.Text(choiceResource(key, rawText))
    }
    val number = rawText.toLongOrNull()
    return if (number != null && rawText == number.toString()) SummaryPiece.Number(number) else SummaryPiece.Literal(rawText)
}

private fun fieldText(settings: Settings, key: String, field: String?): String {
    if (field == null) return settings.getSetting(key)
    if (key == "defaultView") {
        val view = parseView(settings.getSetting(key))
        return when (field) {
            "key" -> view.key.name
            "newestFirst" -> view.newestFirst.toString()
            else -> settings.getSetting(key)
        }
    }
    if (key == "swipeTrailing" || key == "swipeLeading" || key == "readerSwipeTrailing" || key == "readerSwipeLeading") {
        val binding = parseSwipe(settings.getSetting(key))
        return when (field) {
            "action" -> binding.action.name
            "moveMailbox" -> binding.moveMailbox
            "flag" -> binding.flag
            else -> settings.getSetting(key)
        }
    }
    return settings.getSetting(key)
}

private fun choiceResource(key: String, value: String): String {
    val titled = installedLeaf(key)?.choiceTitles?.get(value)
    if (titled != null) return titled
    sortKeyResources[value]?.let { return it }
    swipeActionResources[value]?.let { return it }
    return value
}

private fun countOf(settings: Settings, key: String, accounts: Int): Int {
    if (key == "\$accounts") return accounts
    return when (key) {
        "watchedFolders" -> settings.watchedFolders.size
        "expandedFolders" -> settings.expandedFolders.size
        "favorites" -> settings.favorites.size
        "hiddenCapabilities" -> settings.hiddenCapabilities.size
        "completionSources" -> settings.completionSources.size
        "extraAddressBooks" -> settings.extraAddressBooks.size
        "globalAddressBooks" -> settings.globalAddressBooks.size
        "altAddresses" -> settings.altAddresses.size
        "sendingRules" -> org.dlang.liveimap.ui.compose.decodeRules(settings.sendingRules).size
        "inboundRules" -> settings.inboundRules.size
        "savedSearches" -> settings.savedSearches.size
        "savedAdvanced" -> settings.savedAdvanced.size
        else -> {
            val text = settings.getSetting(key)
            if (text.isEmpty()) 0 else decodeWatchedFolders(text).size
        }
    }
}

private val sortKeyResources = mapOf(
    "Arrival" to "label_arrival",
    "Date" to "label_date",
    "From" to "label_from",
    "Subject" to "compose_subject",
    "To" to "compose_to",
    "Cc" to "compose_cc",
    "Size" to "label_size",
    "ThreadReferences" to "label_thread",
    "ThreadOrderedSubject" to "label_ordered_subject",
)

private val swipeActionResources = mapOf(
    "Delete" to "drawer_delete",
    "Move" to "label_move",
    "Reply" to "compose_reply",
    "ReplyAll" to "compose_reply_all",
    "SetFlag" to "label_set_flag",
    "ClearFlag" to "label_clear_flag",
    "FlagScreen" to "label_flag_screen",
    "Next" to "reader_next",
    "Previous" to "reader_previous",
)
