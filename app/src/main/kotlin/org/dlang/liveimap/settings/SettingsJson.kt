package org.dlang.liveimap.settings

import org.dlang.liveimap.engine.sieve.InboundRule
import org.dlang.liveimap.ui.index.SavedAdvanced
import org.dlang.liveimap.ui.index.SavedSearch
import org.dlang.liveimap.ui.index.SearchScope
import org.dlang.liveimap.ui.index.SimpleSearchField
import org.dlang.liveimap.ui.toolbar.ComposeBarLayout
import org.dlang.liveimap.ui.toolbar.FolderBarLayout
import org.dlang.liveimap.ui.toolbar.IndexBarAction
import org.dlang.liveimap.ui.toolbar.IndexBarLayout
import org.dlang.liveimap.ui.toolbar.ReaderToolbarLayout
import org.dlang.liveimap.ui.toolbar.SelectionBarLayout
import org.dlang.liveimap.ui.toolbar.defaultComposeBar
import org.dlang.liveimap.ui.toolbar.defaultFolderBar
import org.dlang.liveimap.ui.toolbar.defaultIndexBar
import org.dlang.liveimap.ui.toolbar.defaultReaderToolbar
import org.dlang.liveimap.ui.toolbar.defaultSelectionBar

/** Keys that belong in an export, never in the stored document. */
val documentSecretKeys = setOf("imapPassword", "smtpPassword", "sievePassword", "includePasswords")

fun exportSettingsDocument(encoded: String, includePasswords: Boolean, imap: String, smtp: String, sieve: String): String {
    if (!includePasswords) return encoded
    val root = parseJson(encoded) as? JsonValue.Obj ?: return encoded
    val fields = LinkedHashMap(root.fields)
    fields["includePasswords"] = JsonValue.Bool(true)
    fields["imapPassword"] = JsonValue.Str(imap)
    fields["smtpPassword"] = JsonValue.Str(smtp)
    fields["sievePassword"] = JsonValue.Str(sieve)
    return encodeJson(JsonValue.Obj(fields))
}

fun Settings.withAccountFields(account: Settings): Settings {
    var next = this
    for ((key, spec) in settingsRegistry) {
        if (spec.scope == SettingScope.Ui || spec.leaf.derived) continue
        next = next.withSetting(key, SettingValue(account.getSetting(key)))
    }
    return next.copy(unknownSettings = account.unknownSettings, storedKeys = account.storedKeys)
}

class SettingsDocumentException(message: String) : IllegalArgumentException(message)

fun documentError(text: String): String? {
    if (text.isBlank()) return null
    return try {
        val root = parseJson(text)
        if (root !is JsonValue.Obj) "settings document is not an object" else null
    } catch (error: Exception) {
        error.message ?: "settings document"
    }
}

internal fun jsonString(value: JsonValue): String = (value as? JsonValue.Str)?.value ?: throw IllegalArgumentException("string")

internal fun jsonBool(value: JsonValue): Boolean = (value as? JsonValue.Bool)?.value ?: throw IllegalArgumentException("bool")

internal fun jsonLong(value: JsonValue): Long = (value as? JsonValue.Num)?.value ?: throw IllegalArgumentException("number")

internal fun jsonInt(value: JsonValue): Int {
    val number = jsonLong(value)
    if (number < Int.MIN_VALUE || number > Int.MAX_VALUE) throw IllegalArgumentException("number")
    return number.toInt()
}

internal fun stringListJson(values: List<String>): JsonValue = JsonValue.Arr(values.map { JsonValue.Str(it) })

internal fun readStringList(value: JsonValue): List<String> {
    val arr = value as? JsonValue.Arr ?: throw IllegalArgumentException("list")
    return arr.items.map { jsonString(it) }
}

internal fun intListJson(values: List<Int>): JsonValue = JsonValue.Arr(values.map { JsonValue.Num(it.toLong()) })

internal fun readIntList(value: JsonValue): List<Int> {
    val arr = value as? JsonValue.Arr ?: throw IllegalArgumentException("list")
    return arr.items.map { jsonInt(it) }
}

internal fun favoritesJson(values: List<FolderFavorite>): JsonValue = JsonValue.Arr(values.map { favorite ->
    JsonValue.Obj(linkedMapOf(
        "mailbox" to JsonValue.Str(favorite.mailbox),
        "name" to JsonValue.Str(favorite.label),
        "container" to JsonValue.Bool(favorite.node),
        "delimiter" to JsonValue.Str(favorite.delimiter.toString()),
    ))
})

internal fun readFavorites(value: JsonValue): List<FolderFavorite> {
    val arr = value as? JsonValue.Arr ?: throw IllegalArgumentException("favorites")
    return arr.items.map { item ->
        val obj = item as? JsonValue.Obj ?: throw IllegalArgumentException("favorites")
        val mailbox = jsonString(obj.fields.getValue("mailbox"))
        val name = (obj.fields["name"] as? JsonValue.Str)?.value.orEmpty()
        val container = (obj.fields["container"] as? JsonValue.Bool)?.value == true
        val delimiter = jsonString(obj.fields.getValue("delimiter"))
        if (delimiter.length != 1) throw IllegalArgumentException("favorites")
        FolderFavorite(container, mailbox, delimiter[0], name)
    }
}

internal fun viewJson(view: FolderView): JsonValue = JsonValue.Obj(linkedMapOf(
    "key" to JsonValue.Str(view.key.name),
    "newestFirst" to JsonValue.Bool(view.newestFirst),
))

internal fun readView(value: JsonValue): FolderView {
    val obj = value as? JsonValue.Obj ?: throw IllegalArgumentException("view")
    return FolderView(enumValueOf(jsonString(obj.fields.getValue("key"))), jsonBool(obj.fields.getValue("newestFirst")))
}

internal fun folderViewsJson(views: Map<String, FolderView>): JsonValue =
    JsonValue.Obj(views.entries.associate { it.key to viewJson(it.value) })

internal fun readFolderViews(value: JsonValue): Map<String, FolderView> {
    val obj = value as? JsonValue.Obj ?: throw IllegalArgumentException("folderViews")
    return obj.fields.mapValues { (_, item) -> readView(item) }
}

internal fun folderStartsJson(starts: Map<String, StartRule>): JsonValue =
    JsonValue.Obj(starts.entries.associate { it.key to JsonValue.Str(it.value.name) })

internal fun readFolderStarts(value: JsonValue): Map<String, StartRule> {
    val obj = value as? JsonValue.Obj ?: throw IllegalArgumentException("folderStarts")
    return obj.fields.mapValues { (_, item) -> enumValueOf<StartRule>(jsonString(item)) }
}

internal fun swipeJson(binding: SwipeBinding): JsonValue = JsonValue.Obj(linkedMapOf(
    "action" to JsonValue.Str(binding.action.name),
    "moveMailbox" to JsonValue.Str(binding.moveMailbox),
    "flag" to JsonValue.Str(binding.flag),
))

internal fun readSwipe(value: JsonValue): SwipeBinding {
    val obj = value as? JsonValue.Obj ?: throw IllegalArgumentException("swipe")
    return SwipeBinding(
        action = enumValueOf(jsonString(obj.fields.getValue("action"))),
        moveMailbox = (obj.fields["moveMailbox"] as? JsonValue.Str)?.value.orEmpty(),
        flag = (obj.fields["flag"] as? JsonValue.Str)?.value.orEmpty(),
    )
}

internal fun addressBooksJson(books: List<AddressBookEntry>): JsonValue = JsonValue.Arr(books.map { book ->
    JsonValue.Obj(linkedMapOf(
        "kind" to JsonValue.Str(book.kind),
        "mailbox" to JsonValue.Str(book.mailbox),
        "readOnly" to JsonValue.Bool(book.readOnly),
        "saveTarget" to JsonValue.Bool(book.saveTarget),
        "accountType" to JsonValue.Str(book.accountType),
        "accountName" to JsonValue.Str(book.accountName),
    ))
})

internal fun readAddressBooks(value: JsonValue): List<AddressBookEntry> {
    val arr = value as? JsonValue.Arr ?: throw IllegalArgumentException("addressBooks")
    return arr.items.map { item ->
        val obj = item as? JsonValue.Obj ?: throw IllegalArgumentException("addressBooks")
        AddressBookEntry(
            kind = jsonString(obj.fields.getValue("kind")),
            mailbox = (obj.fields["mailbox"] as? JsonValue.Str)?.value.orEmpty(),
            readOnly = (obj.fields["readOnly"] as? JsonValue.Bool)?.value == true,
            saveTarget = (obj.fields["saveTarget"] as? JsonValue.Bool)?.value == true,
            accountType = (obj.fields["accountType"] as? JsonValue.Str)?.value.orEmpty(),
            accountName = (obj.fields["accountName"] as? JsonValue.Str)?.value.orEmpty(),
        )
    }
}

internal fun savedSearchesJson(values: List<SavedSearch>): JsonValue = JsonValue.Arr(values.map { search ->
    JsonValue.Obj(linkedMapOf(
        "name" to JsonValue.Str(search.name),
        "query" to JsonValue.Str(search.query),
        "field" to JsonValue.Str(search.field.name),
    ))
})

internal fun readSavedSearches(value: JsonValue): List<SavedSearch> {
    val arr = value as? JsonValue.Arr ?: throw IllegalArgumentException("savedSearches")
    return arr.items.map { item ->
        val obj = item as? JsonValue.Obj ?: throw IllegalArgumentException("savedSearches")
        val field = decodeSavedSearchField(jsonString(obj.fields.getValue("field"))) ?: throw IllegalArgumentException("savedSearches")
        SavedSearch(jsonString(obj.fields.getValue("name")), jsonString(obj.fields.getValue("query")), field)
    }
}

internal fun savedAdvancedJson(values: List<SavedAdvanced>): JsonValue = JsonValue.Arr(values.map { item ->
    JsonValue.Obj(linkedMapOf(
        "name" to JsonValue.Str(item.name),
        "text" to JsonValue.Str(item.text),
        "scope" to JsonValue.Str(item.scope.name),
    ))
})

internal fun readSavedAdvanced(value: JsonValue): List<SavedAdvanced> {
    val arr = value as? JsonValue.Arr ?: throw IllegalArgumentException("savedAdvanced")
    return arr.items.map { item ->
        val obj = item as? JsonValue.Obj ?: throw IllegalArgumentException("savedAdvanced")
        val scope = decodeSavedAdvancedScope(jsonString(obj.fields.getValue("scope"))) ?: throw IllegalArgumentException("savedAdvanced")
        SavedAdvanced(jsonString(obj.fields.getValue("name")), jsonString(obj.fields.getValue("text")), scope)
    }
}

internal fun inboundRulesJson(rules: List<InboundRule>): JsonValue {
    val lines = encodeInboundRules(rules).lines().filter { it.isNotEmpty() }
    return JsonValue.Arr(lines.map { JsonValue.Str(it) })
}

internal fun readInboundRules(value: JsonValue): List<InboundRule> {
    val arr = value as? JsonValue.Arr ?: throw IllegalArgumentException("inboundRules")
    return decodeInboundRules(arr.items.joinToString("\n") { jsonString(it) })
}

private fun namesJson(names: List<String>): JsonValue = stringListJson(names)

private fun layoutObject(rows: Int, toolbar: List<String>, overflow: List<String>, hidden: List<String>): JsonValue =
    JsonValue.Obj(linkedMapOf(
        "rows" to JsonValue.Num(rows.toLong()),
        "toolbar" to namesJson(toolbar),
        "overflow" to namesJson(overflow),
        "hidden" to namesJson(hidden),
    ))

private fun layoutParts(value: JsonValue): LayoutParts {
    val obj = value as? JsonValue.Obj ?: throw IllegalArgumentException("toolbar")
    val rows = (obj.fields["rows"] as? JsonValue.Num)?.value?.toInt() ?: 2
    if (rows !in 1..4) throw IllegalArgumentException("toolbar rows")
    return LayoutParts(rows, readStringList(obj.fields.getValue("toolbar")), readStringList(obj.fields.getValue("overflow")), readStringList(obj.fields.getValue("hidden")))
}

private data class LayoutParts(val rows: Int, val toolbar: List<String>, val overflow: List<String>, val hidden: List<String>)

internal fun indexBarJson(layout: IndexBarLayout): JsonValue =
    layoutObject(layout.rows, layout.toolbar.map { it.name }, layout.overflow.map { it.name }, layout.hidden.map { it.name })

internal fun readIndexBar(value: JsonValue): IndexBarLayout {
    val parts = layoutParts(value)
    val base = defaultIndexBar()
    return base.copy(
        toolbar = parts.toolbar.map { enumValueOf<IndexBarAction>(it) },
        overflow = parts.overflow.map { enumValueOf<IndexBarAction>(it) },
        hidden = parts.hidden.map { enumValueOf<IndexBarAction>(it) },
        rows = parts.rows,
    )
}

internal fun selectionBarJson(layout: org.dlang.liveimap.ui.toolbar.SelectionBarLayout): JsonValue =
    layoutObject(layout.rows, layout.toolbar.map { it.name }, layout.overflow.map { it.name }, layout.hidden.map { it.name })

internal fun readSelectionBar(value: JsonValue): org.dlang.liveimap.ui.toolbar.SelectionBarLayout {
    val parts = layoutParts(value)
    return defaultSelectionBar().copy(
        toolbar = parts.toolbar.map { enumValueOf(it) },
        overflow = parts.overflow.map { enumValueOf(it) },
        hidden = parts.hidden.map { enumValueOf(it) },
        rows = parts.rows,
    )
}

internal fun folderBarJson(layout: FolderBarLayout): JsonValue =
    layoutObject(layout.rows, layout.toolbar.map { it.name }, layout.overflow.map { it.name }, layout.hidden.map { it.name })

internal fun readFolderBar(value: JsonValue): FolderBarLayout {
    val parts = layoutParts(value)
    return defaultFolderBar().copy(
        toolbar = parts.toolbar.map { enumValueOf(it) },
        overflow = parts.overflow.map { enumValueOf(it) },
        hidden = parts.hidden.map { enumValueOf(it) },
        rows = parts.rows,
    )
}

internal fun readerToolbarJson(layout: ReaderToolbarLayout): JsonValue =
    layoutObject(layout.rows, layout.toolbar.map { it.name }, layout.overflow.map { it.name }, layout.hidden.map { it.name })

internal fun readReaderToolbar(value: JsonValue): ReaderToolbarLayout {
    val parts = layoutParts(value)
    return defaultReaderToolbar().copy(
        toolbar = parts.toolbar.map { enumValueOf(it) },
        overflow = parts.overflow.map { enumValueOf(it) },
        hidden = parts.hidden.map { enumValueOf(it) },
        rows = parts.rows,
    )
}

internal fun composeBarJson(layout: ComposeBarLayout): JsonValue =
    layoutObject(layout.rows, layout.toolbar.map { it.name }, layout.overflow.map { it.name }, layout.hidden.map { it.name })

internal fun readComposeBar(value: JsonValue): ComposeBarLayout {
    val parts = layoutParts(value)
    return defaultComposeBar().copy(
        toolbar = parts.toolbar.map { enumValueOf(it) },
        overflow = parts.overflow.map { enumValueOf(it) },
        hidden = parts.hidden.map { enumValueOf(it) },
        rows = parts.rows,
    )
}

internal fun Settings.preservedJson(key: String): JsonValue? {
    for (entry in unknownSettings) {
        if (entry.substringBefore('\n') != key) continue
        return try {
            parseJson(entry.substringAfter('\n'))
        } catch (_: Exception) {
            null
        }
    }
    return null
}
