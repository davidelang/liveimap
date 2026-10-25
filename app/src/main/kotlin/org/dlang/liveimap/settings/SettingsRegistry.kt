package org.dlang.liveimap.settings

import android.content.Context
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

/**
 * One registry entry per setting. The tree file holds defaults and leaf constraints.
 * [hardLimit] in the tree is documentation; enforcement uses this registry.
 */
const val registrySpec: String = """
friendlyName|String|account|text|Friendly name||
email|String|account|text|Email||
displayName|String|account|text|Display name||
altAddresses|StringList|account|text|Alternate addresses||
jmapFolders|Bool|account|bool|Mail access||
maxJmapResponseBytes|Int|account|number|JMAP response bytes||
imapHost|String|server|text|IMAP host||
imapPort|Int|server|number|IMAP port||1..65535
username|String|account|text|Username||
tlsMode|Enum|server|choice|TLS|None,StartTls,Implicit|
allowPlaintextAuth|Bool|account|bool|Allow plaintext authentication||
acceptInvalidCert|Bool|account|bool|Accept invalid certificate||
allowOldTls|Bool|account|bool|Allow old TLS||
authMechanism|String|account|text|Authentication mechanism||
clientCert|String|server|text|Client certificate||
clientCertAlias|String|server|text|Client certificate alias||
certPin|String|server|text|Certificate pin||
jmapCertPin|String|server|text|JMAP certificate pin||
pipelineCommands|Bool|account|bool|Pipeline commands||
emailSubmission|Bool|account|bool|Email submission||
smtpHost|String|server|text|SMTP host||
smtpPort|Int|server|number|SMTP port||1..65535
smtpTlsMode|Enum|server|choice|SMTP TLS|None,StartTls,Implicit|
smtpUsername|String|server|text|SMTP username||
smtpSameAsImap|Bool|account|bool|SMTP same as IMAP||
smtpNoAuth|Enum|account|choice|SMTP authentication|Auto,On,Off|
sieveHost|String|server|text|Sieve host||
sievePort|Int|server|number|Sieve port||1..65535
sieveTlsMode|Enum|server|choice|Sieve TLS|None,StartTls,Implicit|
sieveCertPin|String|server|text|Sieve certificate pin||
watchedFolders|StringList|account|subscreen|Watched folders||
extraIdleBudget|Int|account|number|Extra IDLE budget||0..8
maxConnections|Int|account|number|Most connections||2..20
sendingRules|Encoded|account|subscreen|Sending rules||
signature|String|account|text|Signature||
sigDashes|Bool|account|bool|Signature dashes||
sigAtBottom|Bool|account|bool|Signature at bottom||
quotePrefix|String|account|text|Quote prefix||
replyLeadIn|String|account|text|Reply lead-in||
dsnMode|Enum|account|choice|DSN|Off,Failure,FailureDelay,All|
composerHeaders|String|account|text|Composer headers||
inboundRules|Encoded|account|subscreen|Inbound rules||
sentMailbox|String|account|mailbox|Sent mailbox||
postponedMailbox|String|account|mailbox|Postponed mailbox||
addressBookMailbox|String|account|mailbox|Address book mailbox||
spamMailbox|String|account|mailbox|Spam mailbox||
trashMailbox|String|account|mailbox|Trash mailbox||
showUnreadCounts|Bool|account|bool|Show unread counts||
expandedFolders|StringList|account|subscreen|Expanded folders||
defaultView|Encoded|account|subscreen|Default view||
folderViews|Encoded|account|subscreen|Folder views||
threadIndexStyle|Enum|account|choice|Thread index style|Expanded,Collapsed|
inboxStart|Enum|account|choice|Inbox opens at|FirstUnseen,FirstRecent,FirstImportant,FirstImportantOrUnseen,FirstImportantOrRecent,First,Last,Newest|
folderStart|Enum|account|choice|Folder opens at|FirstUnseen,FirstRecent,FirstImportant,FirstImportantOrUnseen,FirstImportantOrRecent,First,Last,Newest|
folderStarts|Encoded|account|subscreen|Per-folder start||
startAfterChange|Enum|account|choice|After a change|RerunRule,KeepTopVisible|
showRecentRules|Bool|account|bool|Show recent rules||
openAtInIndexMenu|Bool|account|bool|Open-at in the index menu||
pinercStartDefault|Enum|account|choice|pinerc start default|LeaveUnchanged,AlpineDefault|
askSort|Int|account|number|Ask before sorting||
askThread|Int|account|number|Ask before threading||
askSearch|Int|account|number|Ask before search||
askFallback|Int|account|number|Ask before fallback||
sortAskSkip|StringList|account|subscreen|Sort ask skip||
threadAskSkip|StringList|account|subscreen|Thread ask skip||
searchAskSkip|StringList|account|subscreen|Search ask skip||
fallbackAskSkip|StringList|account|subscreen|Fallback ask skip||
savedSearches|Encoded|account|subscreen|Saved searches||
savedAdvanced|Encoded|account|subscreen|Saved advanced searches||
maxResultUids|Int|account|number|Result UIDs||
markSeenOnOpen|Bool|account|bool|Mark seen on open||
bodyView|Enum|account|choice|Body view|PlainOrHtml,PlainOrText,PlainOrError,Headers,Raw|
preferHtml|Bool|account|bool|Prefer HTML||
readerTextLayout|Enum|account|choice|Reader text layout|Text,Flowed,Monospaced|
readerTextZoom|Int|account|number|Reader text size||50..300
viewerHeaders|String|account|text|Viewer headers||
nonAsciiDisplay|Enum|account|choice|Non-ASCII display|Normal,HighlightSuspicious,HighlightAll,Warn,Escape|
showDeleted|Bool|account|bool|Show deleted||
autoExpunge|Bool|account|bool|Auto-expunge||
askBeforeExpunge|Bool|account|bool|Ask before expunge||
deletePolicy|Enum|account|choice|Delete policy|MarkDeleted,MoveToTrash,DeletePermanently|
moveMethod|Enum|account|choice|Move method|CopyThenMarkDeleted,ImapMove|
moveAllIncludesDeleted|Bool|account|bool|Move all includes deleted||
compression|Enum|account|choice|Compression|On,Off,Auto|
swipeTrailing|Encoded|account|subscreen|Swipe trailing||
swipeLeading|Encoded|account|subscreen|Swipe leading||
readerSwipeTrailing|Encoded|account|subscreen|Reader swipe trailing||
readerSwipeLeading|Encoded|account|subscreen|Reader swipe leading||
bounceFcc|Bool|account|bool|Bounce Fcc||
composerWrapColumn|Int|account|number|Wrap column||0..998
quellFlowed|Bool|account|bool|Quell flowed text||
replyAboveQuote|Bool|account|bool|Reply above quote||
includeForwardAttachments|Bool|account|bool|Include forward attachments||
forwardAsAttachment|Bool|account|bool|Forward as attachment||
savedMailbox|String|account|mailbox|Saved mailbox||
saveNameRule|Enum|account|choice|Save name rule|DefaultFolder,ByFrom,BySender,ByRecipient,LastFolderUsed|
lastSaveMailbox|String|account|mailbox|Last save mailbox||
completionSources|StringList|account|subscreen|Completion sources||
addressBookHistory|Int|account|number|Address book history||
addressBookNeverTrim|Bool|account|bool|Never trim the address book||
extraAddressBooks|StringList|account|subscreen|Extra address books||
globalAddressBooks|StringList|account|subscreen|Global address books||
theme|Enum|account|choice|Theme|Dark,Light,FollowSystem|
dynamicColor|Bool|account|bool|Dynamic color||
density|Enum|account|choice|Density|Compact,Medium,Large|
dateFormat|Enum|account|choice|Date format|Local,Short,Relative,Custom|
datePattern|String|account|text|Date pattern||
multiPane|Enum|account|choice|Multiple panes|Off,Wide,Auto|
folderPane|Enum|account|choice|Folder pane|Off,On,Auto|
foldPosture|Enum|account|choice|Fold posture|Off,On,Auto|
fullScreen|Bool|account|bool|Full screen||
paneArrangement|Enum|account|choice|Pane arrangement|Auto,SideBySide,Stacked|
previewSize|Enum|account|choice|Preview size|Auto,Density,None,Small,Large|
folderPosition|Enum|account|choice|Folder position|Auto,Beside,AboveList|
customLayout|Encoded|account|subscreen|Custom layout||
clientSort|Bool|account|bool|Client sort||
clientThread|Bool|account|bool|Client thread||
pollForNewMail|Bool|account|bool|Poll for new mail||
pollSeconds|Int|account|number|Poll interval||15..600
idleFallbackSeconds|Int|account|number|IDLE fallback||
keepAliveMinutes|Int|account|number|Keep-alive||
idleRenewMinutes|Int|account|number|IDLE renew||
longSearchProgressSec|Int|account|number|Long search progress||
longSearchQuietSec|Int|account|number|Long search quiet||
statusVisibleCounts|Bool|account|bool|Status visible counts||
forceSlowerFallbacks|Bool|account|bool|Force slower fallbacks||
logImapTraffic|Bool|account|bool|Log IMAP traffic||
showUserInDebugReport|Bool|account|bool|Show user in the debug report||
idleWakeSeconds|Int|account|number|IDLE wake|1,2,5,10,30,60|1..60
hiddenCapabilities|StringList|account|subscreen|Hidden capabilities||
indexBar|Encoded|account|subscreen|Index toolbar||
selectionBar|Encoded|account|subscreen|Selection toolbar||
folderBar|Encoded|account|subscreen|Folder toolbar||
readerToolbar|Encoded|account|subscreen|Reader toolbar|nullable|
composeBar|Encoded|account|subscreen|Compose toolbar||
toolbarRows|Int|account|number|Toolbar rows||1..4
favorites|Encoded|account|subscreen|Favorites||
indexPageSize|Int|global|number|Index page size||
swipeThresholdPercent|Int|global|number|Swipe threshold||1..99
reconnectDelaysSec|IntList|global|text|Reconnect delays||list:1::1:86400
maxRawViewBytes|Long|global|number|Raw view bytes||
maxImageTrustBytes|Long|global|number|Image trust bytes||
moveChunk|Int|global|number|Move chunk||1..2147483647
clientSortChunk|Int|global|number|Client sort chunk||1..2147483647
"""

private val Context.globalSettingsDataStore by preferencesDataStore(
    name = "global_settings",
    corruptionHandler = ReplaceFileCorruptionHandler<Preferences> {
        throw IllegalStateException("global_settings corrupt")
    },
)

private val globalSettingsValue = stringPreferencesKey("value")

enum class SettingScope { Global, Account, Server }

enum class SettingType { Bool, Int, Long, String, Enum, StringList, IntList, Encoded }

enum class ControlKind { Text, Number, Bool, Choice, Mailbox, Subscreen }

data class NumberLimit(
    val min: Long? = null,
    val max: Long? = null,
    val minItems: Long? = null,
    val maxItems: Long? = null,
    val itemMin: Long? = null,
    val itemMax: Long? = null,
) {
    fun isEmpty(): Boolean =
        min == null && max == null && minItems == null && maxItems == null && itemMin == null && itemMax == null
}

data class SettingSpec(
    val key: String,
    val type: SettingType,
    val scope: SettingScope,
    val control: ControlKind,
    val title: String,
    val enumValues: List<String>,
    val intChoices: List<Long>,
    val nullable: Boolean,
    val hard: NumberLimit?,
)

fun hardLimitText(limit: NumberLimit?): String {
    if (limit == null || limit.isEmpty()) return "none"
    val parts = ArrayList<String>()
    if (limit.min != null) parts.add("\"min\": ${limit.min}")
    if (limit.max != null) parts.add("\"max\": ${limit.max}")
    if (limit.minItems != null) parts.add("\"minItems\": ${limit.minItems}")
    if (limit.maxItems != null) parts.add("\"maxItems\": ${limit.maxItems}")
    if (limit.itemMin != null) parts.add("\"itemMin\": ${limit.itemMin}")
    if (limit.itemMax != null) parts.add("\"itemMax\": ${limit.itemMax}")
    return "{" + parts.joinToString(", ") + "}"
}

fun hardLimitMessage(key: String, leafText: String, registryText: String): String =
    "settings-tree.json leaf `$key` has hardLimit $leafText but SettingsRegistry limits it to $registryText. " +
        "hardLimit is documentation of the registry limit and the build never writes it. " +
        "The fix is to edit the JSON or the registry. " +
        "If the registry limit is right, set the leaf's hardLimit to match it; if the limit should change, " +
        "change SettingsRegistry.kt and settings-tree.json together, and keep the leaf's min/max and default inside the new limit."

val settingsRegistry: Map<String, SettingSpec> by lazy { parseRegistrySpec(registrySpec) }

private fun parseRegistrySpec(text: String): Map<String, SettingSpec> {
    val out = linkedMapOf<String, SettingSpec>()
    for (raw in text.lines()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        val parts = line.split("|")
        require(parts.size == 7) { "bad registry line $line" }
        val key = parts[0]
        val type = when (parts[1]) {
            "Bool" -> SettingType.Bool
            "Int" -> SettingType.Int
            "Long" -> SettingType.Long
            "String" -> SettingType.String
            "Enum" -> SettingType.Enum
            "StringList" -> SettingType.StringList
            "IntList" -> SettingType.IntList
            "Encoded" -> SettingType.Encoded
            else -> error("bad registry type ${parts[1]}")
        }
        val scope = when (parts[2]) {
            "global" -> SettingScope.Global
            "account" -> SettingScope.Account
            "server" -> SettingScope.Server
            else -> error("bad registry scope ${parts[2]}")
        }
        val control = when (parts[3]) {
            "text" -> ControlKind.Text
            "number" -> ControlKind.Number
            "bool" -> ControlKind.Bool
            "choice" -> ControlKind.Choice
            "mailbox" -> ControlKind.Mailbox
            "subscreen" -> ControlKind.Subscreen
            else -> error("bad registry control ${parts[3]}")
        }
        val extra = parts[5]
        val nullable = extra == "nullable"
        val enumValues = if (type == SettingType.Enum && extra.isNotEmpty()) extra.split(",") else emptyList()
        val intChoices = if (type == SettingType.Int && extra.isNotEmpty() && extra.all { it.isDigit() || it == ',' }) {
            extra.split(",").map { it.toLong() }
        } else {
            emptyList()
        }
        val hard = parseHard(parts[6])
        require(key !in out) { "duplicate registry key $key" }
        out[key] = SettingSpec(key, type, scope, control, parts[4], enumValues, intChoices, nullable, hard)
    }
    return out
}

private fun parseHard(text: String): NumberLimit? {
    if (text.isEmpty()) return null
    if (text.startsWith("list:")) {
        val bits = text.removePrefix("list:").split(":")
        require(bits.size == 4) { "bad list limit $text" }
        return NumberLimit(
            minItems = bits[0].toLongOrNull(),
            maxItems = bits[1].toLongOrNull(),
            itemMin = bits[2].toLongOrNull(),
            itemMax = bits[3].toLongOrNull(),
        )
    }
    val dots = text.indexOf("..")
    require(dots > 0) { "bad hard limit $text" }
    return NumberLimit(min = text.substring(0, dots).toLong(), max = text.substring(dots + 2).toLong())
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

data class TreeLeaf(
    val setting: String,
    val default: JsonValue,
    val min: Long? = null,
    val max: Long? = null,
    val choices: List<String>? = null,
    val minItems: Long? = null,
    val maxItems: Long? = null,
    val itemMin: Long? = null,
    val itemMax: Long? = null,
    val order: String? = null,
    val hardLimit: NumberLimit? = null,
    val hardPresent: Boolean = false,
)

sealed class TreeNode {
    data class Group(val title: String, val description: String?, val children: List<TreeNode>) : TreeNode()
    data class Leaf(val leaf: TreeLeaf) : TreeNode()
}

fun parseSettingsTree(text: String): List<TreeNode> {
    val root = parseJson(text)
    if (root !is JsonValue.Arr) throw SettingsTreeParseException("settings-tree.json root")
    return root.items.map { node(it) }
}

private fun node(value: JsonValue): TreeNode {
    val obj = value as? JsonValue.Obj ?: throw SettingsTreeParseException("settings-tree.json node")
    val setting = obj.fields["setting"]
    val children = obj.fields["children"]
    if (setting != null && children != null) {
        throw SettingsTreeParseException("settings-tree.json node has setting and children")
    }
    if (children != null) {
        val title = (obj.fields["title"] as? JsonValue.Str)?.value
            ?: throw SettingsTreeParseException("settings-tree.json group title")
        if (title.isEmpty()) throw SettingsTreeParseException("settings-tree.json group title")
        val arr = children as? JsonValue.Arr ?: throw SettingsTreeParseException("settings-tree.json children")
        if (arr.items.isEmpty()) throw SettingsTreeParseException("settings-tree.json group $title has no children")
        val description = (obj.fields["description"] as? JsonValue.Str)?.value
        return TreeNode.Group(title, description, arr.items.map { node(it) })
    }
    if (setting !is JsonValue.Str || setting.value.isEmpty()) {
        throw SettingsTreeParseException("settings-tree.json leaf setting")
    }
    if ("default" !in obj.fields) throw SettingsTreeParseException("settings-tree.json leaf ${setting.value} default")
    return TreeNode.Leaf(readLeaf(setting.value, obj))
}

private fun readLeaf(key: String, obj: JsonValue.Obj): TreeLeaf {
    fun longField(name: String): Long? = when (val v = obj.fields[name]) {
        null -> null
        is JsonValue.Num -> v.value
        else -> throw SettingsTreeParseException("settings-tree.json leaf $key $name")
    }
    val choices = when (val v = obj.fields["choices"]) {
        null -> null
        is JsonValue.Arr -> v.items.map {
            when (it) {
                is JsonValue.Str -> it.value
                is JsonValue.Num -> it.value.toString()
                else -> throw SettingsTreeParseException("settings-tree.json leaf $key choices")
            }
        }
        else -> throw SettingsTreeParseException("settings-tree.json leaf $key choices")
    }
    val order = when (val v = obj.fields["order"]) {
        null -> null
        is JsonValue.Str -> v.value
        else -> throw SettingsTreeParseException("settings-tree.json leaf $key order")
    }
    val hardValue = obj.fields["hardLimit"]
    val hard = when (hardValue) {
        null -> null
        is JsonValue.Obj -> limitFromObject(key, hardValue)
        else -> throw SettingsTreeParseException("settings-tree.json leaf $key hardLimit")
    }
    return TreeLeaf(
        setting = key,
        default = obj.fields.getValue("default"),
        min = longField("min"),
        max = longField("max"),
        choices = choices,
        minItems = longField("minItems"),
        maxItems = longField("maxItems"),
        itemMin = longField("itemMin"),
        itemMax = longField("itemMax"),
        order = order,
        hardLimit = hard,
        hardPresent = hardValue != null,
    )
}

private fun limitFromObject(key: String, obj: JsonValue.Obj): NumberLimit {
    fun one(name: String): Long? = when (val v = obj.fields[name]) {
        null -> null
        is JsonValue.Num -> v.value
        else -> throw SettingsTreeParseException("settings-tree.json leaf $key hardLimit")
    }
    return NumberLimit(
        min = one("min"),
        max = one("max"),
        minItems = one("minItems"),
        maxItems = one("maxItems"),
        itemMin = one("itemMin"),
        itemMax = one("itemMax"),
    )
}

fun groupTitles(nodes: List<TreeNode>): List<String> {
    val out = ArrayList<String>()
    fun walk(node: TreeNode) {
        if (node is TreeNode.Group) {
            out.add(node.title)
            node.children.forEach { walk(it) }
        }
    }
    nodes.forEach { walk(it) }
    return out
}

fun validateSettingsTree(text: String, complete: Boolean = true): List<String> {
    val registry = settingsRegistry
    val errors = ArrayList<String>()
    val nodes = try {
        parseSettingsTree(text)
    } catch (e: SettingsTreeParseException) {
        return listOf(e.message ?: "settings-tree.json malformed")
    }
    val seen = HashSet<String>()
    fun walk(node: TreeNode) {
        when (node) {
            is TreeNode.Group -> node.children.forEach { walk(it) }
            is TreeNode.Leaf -> {
                val key = node.leaf.setting
                val spec = registry[key]
                if (spec == null) {
                    errors.add("settings-tree.json unknown setting $key")
                    return
                }
                if (!seen.add(key)) errors.add("settings-tree.json duplicate setting $key")
                errors.addAll(checkLeaf(spec, node.leaf))
            }
        }
    }
    nodes.forEach { walk(it) }
    if (complete) {
        for ((key, spec) in registry) {
            if (spec.scope == SettingScope.Global) continue
            if (key !in seen) errors.add("settings-tree.json missing setting $key")
        }
    }
    return errors
}

private fun checkLeaf(spec: SettingSpec, leaf: TreeLeaf): List<String> {
    val errors = ArrayList<String>()
    val key = spec.key
    val numeric = spec.type == SettingType.Int || spec.type == SettingType.Long
    val list = spec.type == SettingType.StringList || spec.type == SettingType.IntList
    if (!numeric && (leaf.min != null || leaf.max != null)) {
        errors.add("settings-tree.json leaf $key has min or max")
    }
    if (spec.type != SettingType.Enum && spec.type != SettingType.Int && leaf.choices != null) {
        errors.add("settings-tree.json leaf $key has choices")
    }
    if (!list && (leaf.minItems != null || leaf.maxItems != null || leaf.itemMin != null || leaf.itemMax != null || leaf.order != null)) {
        errors.add("settings-tree.json leaf $key has a list constraint")
    }
    if (spec.type == SettingType.StringList && (leaf.itemMin != null || leaf.itemMax != null || leaf.order != null)) {
        errors.add("settings-tree.json leaf $key has a list constraint")
    }
    if (leaf.min != null && leaf.max != null && leaf.min > leaf.max) {
        errors.add("settings-tree.json leaf $key min above max")
    }
    if (leaf.minItems != null && leaf.maxItems != null && leaf.minItems > leaf.maxItems) {
        errors.add("settings-tree.json leaf $key minItems above maxItems")
    }
    if (leaf.choices != null && leaf.choices.isEmpty()) errors.add("settings-tree.json leaf $key empty choices")
    if (leaf.order != null && leaf.order !in setOf("nondecreasing", "increasing", "any")) {
        errors.add("settings-tree.json leaf $key order")
    }
    val registryLimit = spec.hard
    val leafLimit = if (leaf.hardPresent) leaf.hardLimit ?: NumberLimit() else null
    if (hardLimitText(registryLimit) != hardLimitText(leafLimit)) {
        errors.add(hardLimitMessage(key, hardLimitText(leafLimit), hardLimitText(registryLimit)))
    }
    val hard = spec.hard
    if (hard != null) {
        if (leaf.min != null && hard.min != null && leaf.min < hard.min) errors.add("settings-tree.json leaf $key min outside hard limit")
        if (leaf.max != null && hard.max != null && leaf.max > hard.max) errors.add("settings-tree.json leaf $key max outside hard limit")
        if (leaf.minItems != null && hard.minItems != null && leaf.minItems < hard.minItems) {
            errors.add("settings-tree.json leaf $key minItems outside hard limit")
        }
        if (leaf.maxItems != null && hard.maxItems != null && leaf.maxItems > hard.maxItems) {
            errors.add("settings-tree.json leaf $key maxItems outside hard limit")
        }
        if (leaf.itemMin != null && hard.itemMin != null && leaf.itemMin < hard.itemMin) {
            errors.add("settings-tree.json leaf $key itemMin outside hard limit")
        }
        if (leaf.itemMax != null && hard.itemMax != null && leaf.itemMax > hard.itemMax) {
            errors.add("settings-tree.json leaf $key itemMax outside hard limit")
        }
    }
    if (spec.type == SettingType.Enum && leaf.choices != null) {
        for (choice in leaf.choices) {
            if (choice !in spec.enumValues) errors.add("settings-tree.json leaf $key choice $choice")
        }
    }
    if (spec.type == SettingType.Int && leaf.choices != null) {
        if (spec.intChoices.isEmpty()) errors.add("settings-tree.json leaf $key has choices")
        val want = spec.intChoices.map { it.toString() }
        if (leaf.choices != want) errors.add("settings-tree.json leaf $key choices")
        for (choice in leaf.choices) {
            val n = choice.toLongOrNull()
            if (n == null || (hard?.min != null && n < hard.min) || (hard?.max != null && n > hard.max)) {
                errors.add("settings-tree.json leaf $key choice $choice")
            }
        }
    }
    errors.addAll(checkDefault(spec, leaf))
    return errors
}

private fun checkDefault(spec: SettingSpec, leaf: TreeLeaf): List<String> {
    val key = spec.key
    val value = leaf.default
    fun bad() = listOf("settings-tree.json leaf $key default")
    return when (spec.type) {
        SettingType.Bool -> if (value is JsonValue.Bool) emptyList() else bad()
        SettingType.Int, SettingType.Long -> {
            if (value !is JsonValue.Num) return bad()
            val why = numberRejection(key, leaf, value.value)
            if (why != null) listOf("settings-tree.json leaf $key default") else emptyList()
        }
        SettingType.String, SettingType.Encoded -> if (value is JsonValue.Str) emptyList() else bad()
        SettingType.Enum -> {
            if (value !is JsonValue.Str) return bad()
            if (value.value !in spec.enumValues) return bad()
            if (leaf.choices != null && value.value !in leaf.choices) return bad()
            emptyList()
        }
        SettingType.StringList -> checkStringListDefault(key, leaf, value)
        SettingType.IntList -> checkIntListDefault(key, leaf, value)
    }
}

private fun checkStringListDefault(key: String, leaf: TreeLeaf, value: JsonValue): List<String> {
    if (value !is JsonValue.Arr || value.items.any { it !is JsonValue.Str }) {
        return listOf("settings-tree.json leaf $key default")
    }
    val n = value.items.size.toLong()
    if (leaf.minItems != null && n < leaf.minItems) return listOf("settings-tree.json leaf $key default")
    if (leaf.maxItems != null && n > leaf.maxItems) return listOf("settings-tree.json leaf $key default")
    val hard = settingsRegistry.getValue(key).hard
    if (hard?.minItems != null && n < hard.minItems) return listOf("settings-tree.json leaf $key default")
    if (hard?.maxItems != null && n > hard.maxItems) return listOf("settings-tree.json leaf $key default")
    return emptyList()
}

private fun checkIntListDefault(key: String, leaf: TreeLeaf, value: JsonValue): List<String> {
    if (value !is JsonValue.Arr || value.items.any { it !is JsonValue.Num }) {
        return listOf("settings-tree.json leaf $key default")
    }
    val nums = value.items.map { (it as JsonValue.Num).value }
    val n = nums.size.toLong()
    val hard = settingsRegistry.getValue(key).hard
    fun outside(min: Long?, max: Long?, countMin: Long?, countMax: Long?): Boolean {
        if (countMin != null && n < countMin) return true
        if (countMax != null && n > countMax) return true
        if (nums.any { (min != null && it < min) || (max != null && it > max) }) return true
        return false
    }
    if (outside(leaf.itemMin, leaf.itemMax, leaf.minItems, leaf.maxItems)) {
        return listOf("settings-tree.json leaf $key default")
    }
    if (hard != null && outside(hard.itemMin, hard.itemMax, hard.minItems, hard.maxItems)) {
        return listOf("settings-tree.json leaf $key default")
    }
    if (!ordered(nums, leaf.order)) return listOf("settings-tree.json leaf $key default")
    return emptyList()
}

private fun ordered(nums: List<Long>, order: String?): Boolean = when (order) {
    null, "any" -> true
    "nondecreasing" -> nums.zipWithNext().all { (a, b) -> a <= b }
    "increasing" -> nums.zipWithNext().all { (a, b) -> a < b }
    else -> false
}

fun numberRejection(key: String, leaf: TreeLeaf, value: Long): String? {
    val spec = settingsRegistry[key] ?: return "unknown setting $key"
    if (spec.type != SettingType.Int && spec.type != SettingType.Long) return "$key rejected $value"
    if (spec.type == SettingType.Int && (value < Int.MIN_VALUE || value > Int.MAX_VALUE)) return "$key rejected $value"
    val hard = spec.hard
    if (hard?.min != null && value < hard.min) return "$key rejected $value"
    if (hard?.max != null && value > hard.max) return "$key rejected $value"
    if (leaf.min != null && value < leaf.min) return "$key rejected $value"
    if (leaf.max != null && value > leaf.max) return "$key rejected $value"
    if (spec.type == SettingType.Int && leaf.choices != null && value.toString() !in leaf.choices) {
        return "$key rejected $value"
    }
    if (spec.intChoices.isNotEmpty() && leaf.choices == null && value !in spec.intChoices) {
        return "$key rejected $value"
    }
    return null
}

fun findLeaf(nodes: List<TreeNode>, key: String): TreeLeaf? {
    for (node in nodes) {
        when (node) {
            is TreeNode.Group -> findLeaf(node.children, key)?.let { return it }
            is TreeNode.Leaf -> if (node.leaf.setting == key) return node.leaf
        }
    }
    return null
}

fun rejectedNumber(treeJson: String, key: String, value: Long): String? {
    val leaf = findLeaf(parseSettingsTree(treeJson), key) ?: return "unknown setting $key"
    return numberRejection(key, leaf, value)
}

private object InstalledTree {
    var leaves: Map<String, TreeLeaf> = emptyMap()
    var roots: List<TreeNode> = emptyList()
}

fun installedSettingsRoots(): List<TreeNode> = InstalledTree.roots

object SettingsTreeIndex {
    private val ids = HashMap<String, String>()

    fun install(pairs: List<Pair<String, String>>) {
        ids.clear()
        for ((title, id) in pairs) ids[title] = id
    }

    fun id(title: String): String = ids[title] ?: error("settings tree has no group $title")
}

fun installSettingsTree(text: String) {
    val nodes = parseSettingsTree(text)
    val leaves = linkedMapOf<String, TreeLeaf>()
    fun walk(node: TreeNode) {
        when (node) {
            is TreeNode.Group -> node.children.forEach { walk(it) }
            is TreeNode.Leaf -> leaves[node.leaf.setting] = node.leaf
        }
    }
    nodes.forEach { walk(it) }
    InstalledTree.leaves = leaves
    InstalledTree.roots = nodes
    val titles = groupTitles(nodes)
    val ids = SettingsTreeGenerated.groupIds
    if (titles.size == ids.size) SettingsTreeIndex.install(titles.zip(ids))
}

fun rejectedAccountSettings(settings: AccountSettings): String? {
    val leaves = InstalledTree.leaves
    if (leaves.isEmpty()) return null
    val values = listOf(
        "imapPort" to settings.imapPort.toLong(),
        "smtpPort" to settings.smtpPort.toLong(),
        "sievePort" to settings.sievePort.toLong(),
        "extraIdleBudget" to settings.extraIdleBudget.toLong(),
        "maxConnections" to settings.maxConnections.toLong(),
        "readerTextZoom" to settings.readerTextZoom.toLong(),
        "composerWrapColumn" to settings.composerWrapColumn.toLong(),
        "toolbarRows" to settings.toolbarRows.toLong(),
        "idleWakeSeconds" to settings.idleWakeSeconds.toLong(),
        "pollSeconds" to settings.pollSeconds.toLong(),
        "addressBookHistory" to settings.addressBookHistory.toLong(),
        "askSort" to settings.askSort.toLong(),
        "askThread" to settings.askThread.toLong(),
        "askSearch" to settings.askSearch.toLong(),
        "askFallback" to settings.askFallback.toLong(),
    )
    for ((key, value) in values) {
        val leaf = leaves[key] ?: continue
        val reason = numberRejection(key, leaf, value) ?: continue
        return reason
    }
    return null
}

fun globalRecordError(text: String): String? {
    if (text.isBlank()) return null
    for (line in text.lines()) {
        if (line.isEmpty()) continue
        val eq = line.indexOf('=')
        if (eq <= 0) return "global_settings undecodable record"
        val key = line.substring(0, eq)
        val spec = settingsRegistry[key] ?: return "global_settings undecodable key $key"
        if (spec.scope != SettingScope.Global) return "global_settings undecodable key $key"
        val value = line.substring(eq + 1)
        if (!globalValueOk(spec, value)) return "global_settings undecodable key $key"
    }
    return null
}

private fun globalValueOk(spec: SettingSpec, value: String): Boolean {
    return when (spec.type) {
        SettingType.Bool -> value == "true" || value == "false"
        SettingType.Int, SettingType.Long -> value.toLongOrNull()?.let { numberRejection(spec.key, syntheticLeaf(spec), it) == null } == true
        SettingType.String, SettingType.Encoded -> true
        SettingType.Enum -> value in spec.enumValues
        SettingType.IntList -> {
            if (value.isEmpty()) {
                val minItems = spec.hard?.minItems
                minItems == null || minItems <= 0
            } else {
                val nums = value.split(",").map { it.toLongOrNull() ?: return false }
                val hard = spec.hard
                if (hard?.minItems != null && nums.size < hard.minItems) return false
                if (hard?.maxItems != null && nums.size > hard.maxItems) return false
                val itemMin = hard?.itemMin
                val itemMax = hard?.itemMax
                nums.all { (itemMin == null || it >= itemMin) && (itemMax == null || it <= itemMax) }
            }
        }
        SettingType.StringList -> true
    }
}

private fun syntheticLeaf(spec: SettingSpec) = TreeLeaf(
    setting = spec.key,
    default = JsonValue.Null,
    choices = spec.intChoices.takeIf { it.isNotEmpty() }?.map { it.toString() },
)

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
        globalRecordError(record)
    } catch (e: Exception) {
        e.message ?: "global_settings corrupt"
    }
}
