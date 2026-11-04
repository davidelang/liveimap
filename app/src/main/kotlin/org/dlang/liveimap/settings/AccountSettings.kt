package org.dlang.liveimap.settings

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.dlang.liveimap.session.ClientSortCollation
import org.dlang.liveimap.engine.sieve.InboundRule
import org.dlang.liveimap.engine.sieve.RuleCriterion
import org.dlang.liveimap.engine.sieve.RuleField
import org.dlang.liveimap.engine.sieve.SystemFlag
import org.dlang.liveimap.jmap.JmapMaxBody
import org.dlang.liveimap.ui.index.SavedAdvanced
import org.dlang.liveimap.ui.index.SavedSearch
import org.dlang.liveimap.ui.index.SearchScope
import org.dlang.liveimap.ui.index.SimpleSearchField
import org.dlang.liveimap.ui.toolbar.ComposeBarLayout
import org.dlang.liveimap.ui.toolbar.FolderBarLayout
import org.dlang.liveimap.ui.toolbar.IndexBarLayout
import org.dlang.liveimap.ui.toolbar.ReaderToolbarAction
import org.dlang.liveimap.ui.toolbar.ReaderToolbarLayout
import org.dlang.liveimap.ui.toolbar.SelectionBarLayout
import org.dlang.liveimap.ui.toolbar.defaultComposeBar
import org.dlang.liveimap.ui.toolbar.defaultFolderBar
import org.dlang.liveimap.ui.toolbar.defaultIndexBar
import org.dlang.liveimap.ui.toolbar.defaultSelectionBar
import org.dlang.liveimap.ui.toolbar.encodeComposeBar
import org.dlang.liveimap.ui.toolbar.encodeFolderBar
import org.dlang.liveimap.ui.toolbar.encodeIndexBar
import org.dlang.liveimap.ui.toolbar.encodeReaderToolbar
import org.dlang.liveimap.ui.toolbar.encodeSelectionBar
import org.dlang.liveimap.ui.toolbar.parseComposeBar
import org.dlang.liveimap.ui.toolbar.parseFolderBar
import org.dlang.liveimap.ui.toolbar.parseIndexBar
import org.dlang.liveimap.ui.toolbar.parseReaderToolbar
import org.dlang.liveimap.ui.toolbar.parseSelectionBar

enum class Density {
    Compact,
    Medium,
    Large,
}

enum class SortKey {
    Arrival,
    Date,
    From,
    Subject,
    To,
    Cc,
    Size,
    ThreadReferences,
    ThreadOrderedSubject,
}

fun sortKeyLabel(key: SortKey): String = when (key) {
    SortKey.Arrival -> "Arrival"
    SortKey.Date -> "Date"
    SortKey.From -> "From"
    SortKey.Subject -> "Subject"
    SortKey.To -> "To"
    SortKey.Cc -> "Cc"
    SortKey.Size -> "Size"
    SortKey.ThreadReferences -> "Thread"
    SortKey.ThreadOrderedSubject -> "Ordered subject"
}

enum class StartRule {
    FirstUnseen,
    FirstRecent,
    FirstImportant,
    FirstImportantOrUnseen,
    FirstImportantOrRecent,
    First,
    Last,
    Newest,
}

const val recentRuleNote = "\\Recent belongs to whichever app opens the folder first"

fun startRuleLabel(rule: StartRule): String = when (rule) {
    StartRule.FirstUnseen -> "First unread"
    StartRule.FirstRecent -> "First recent"
    StartRule.FirstImportant -> "First important"
    StartRule.FirstImportantOrUnseen -> "First important or unread"
    StartRule.FirstImportantOrRecent -> "First important or recent"
    StartRule.First -> "Top of the list"
    StartRule.Last -> "Bottom of the list"
    StartRule.Newest -> "Newest message"
}

fun startRuleIsRecent(rule: StartRule): Boolean =
    rule == StartRule.FirstRecent || rule == StartRule.FirstImportantOrRecent

fun startRuleChoices(showRecent: Boolean, selected: StartRule): List<StartRule> {
    if (showRecent) return StartRule.entries
    return StartRule.entries.filter { rule ->
        rule == selected || !startRuleIsRecent(rule)
    }
}

fun startRuleFor(mailbox: String, settings: Settings): StartRule {
    settings.folderStarts[mailbox]?.let { return it }
    if (mailbox.equals("INBOX", ignoreCase = true)) return settings.inboxStart
    return settings.folderStart
}

enum class StartAfterChange {
    RerunRule,
    KeepTopVisible,
}

enum class PinercStartDefault {
    LeaveUnchanged,
    AlpineDefault,
}

fun withFolderStart(settings: Settings, mailbox: String, rule: StartRule?): Settings {
    val starts = if (rule == null) {
        settings.folderStarts - mailbox
    } else {
        settings.folderStarts + (mailbox to rule)
    }
    return settings.copy(folderStarts = starts)
}

data class FolderView(
    val key: SortKey,
    val newestFirst: Boolean,
)

enum class SwipeAction {
    Delete,
    Move,
    Reply,
    ReplyAll,
    SetFlag,
    ClearFlag,
    FlagScreen,
    Next,
    Previous,
}

enum class ThemeMode {
    Dark,
    Light,
    FollowSystem,
}

enum class DateFormat {
    Local,
    Short,
    Relative,
    Custom,
}

enum class ThreadIndexStyle {
    Expanded,
    Collapsed,
}

fun dateFormatLabel(format: DateFormat): String = when (format) {
    DateFormat.Local -> "Local"
    DateFormat.Short -> "Short"
    DateFormat.Relative -> "Relative"
    DateFormat.Custom -> "Custom"
}

/** How plain text is laid out in the reader (the Text/Flowed/Monospaced positions of the display selector). */
enum class TextLayout {
    /** Original line breaks, proportional font. */
    Text,
    /** Re-wrapped to the window: format=flowed honoured, ordinary paragraphs reflowed; quote levels kept. */
    Flowed,
    /** Original line breaks, monospaced, no wrapping (scroll sideways) so text tables line up. */
    Monospaced,
}

enum class BodyView {
    PlainOrHtml,
    PlainOrText,
    PlainOrError,
    Headers,
    Raw,
}

fun bodyViewLabel(view: BodyView): String = when (view) {
    BodyView.PlainOrError -> "Plain text"
    BodyView.PlainOrHtml -> "HTML"
    BodyView.PlainOrText -> "HTML as text"
    BodyView.Headers -> "Headers"
    BodyView.Raw -> "Raw source"
}

data class SwipeBinding(
    val action: SwipeAction,
    val moveMailbox: String = "",
    val flag: String = "",
)

data class FolderFavorite(
    val node: Boolean,
    val mailbox: String,
    val delimiter: Char,
    val label: String = "",
)

/** One address book. `kind` is `pine` or `android`. Order in the list is completion order. */
data class AddressBookEntry(
    val kind: String,
    val mailbox: String = "",
    val readOnly: Boolean = false,
    val saveTarget: Boolean = false,
    val accountType: String = "",
    val accountName: String = "",
)

fun favoriteLabel(node: Boolean, mailbox: String, delimiter: Char): String {
    if (mailbox.isEmpty()) {
        return if (node) "(empty prefix)[]" else "(empty prefix)"
    }
    return if (node) "$mailbox$delimiter[]" else mailbox
}

const val pineSourceId: String = "pine"

/** Auto: chosen from the screen's size in dp and inches ([classifyScreen]); the default for new accounts. */
enum class MultiPane {
    Off,
    Wide,
    Auto,
}

enum class LayoutChoice {
    Off,
    On,
    /** Folder pane only: shown when [classifyScreen] gives three panes. Elsewhere it counts as Off. */
    Auto,
}

enum class ArrangementChoice { Auto, SideBySide, Stacked }

/** Density = preview lines follow the Density setting (stored accounts); Auto = from [planLayout]. */
enum class PreviewChoice { Auto, Density, None, Small, Large }

enum class FolderPositionChoice { Auto, Beside, AboveList }

/** Off, Auto, Beside, or Above the list. Replaces the separate pane and position settings. */
enum class FolderPlace { Off, Auto, Beside, AboveList }

fun layoutChoiceOf(place: FolderPlace): LayoutChoice = when (place) {
    FolderPlace.Off -> LayoutChoice.Off
    FolderPlace.Auto -> LayoutChoice.Auto
    FolderPlace.Beside, FolderPlace.AboveList -> LayoutChoice.On
}

fun positionOf(place: FolderPlace): FolderPositionChoice = when (place) {
    FolderPlace.AboveList -> FolderPositionChoice.AboveList
    FolderPlace.Beside -> FolderPositionChoice.Beside
    FolderPlace.Off, FolderPlace.Auto -> FolderPositionChoice.Auto
}

/** Anti-phishing display of non-ASCII in folder names, From and subjects (see ui/NonAsciiText). */
enum class NonAsciiDisplay { Normal, HighlightSuspicious, HighlightAll, Warn, Escape }

enum class DeletePolicy {
    MarkDeleted,
    MoveToTrash,
    DeletePermanently,
}

fun moveCommandKind(disableImapMove: Boolean, moveAdvertised: Boolean): String =
    if (!disableImapMove && moveAdvertised) "Move" else "CopyThenDelete"

enum class SaveNameRule {
    DefaultFolder,
    ByFrom,
    BySender,
    ByRecipient,
    LastFolderUsed,
}

enum class TlsMode {
    None,
    StartTls,
    Implicit,
}

enum class SmtpNoAuth {
    On,
    Off,
}

enum class PasswordStorageChoice {
    googleRestore,
    none,
    passwordManager,
}

fun defaultImapPort(mode: TlsMode): Int = if (mode == TlsMode.Implicit) 993 else 143

fun defaultSmtpPort(mode: TlsMode): Int = when (mode) {
    TlsMode.None -> 25
    TlsMode.StartTls -> 587
    TlsMode.Implicit -> 465
}

/** AccountStore still calls this. A new account is [Settings], whose defaults are the tree. */
fun newAccountSettings(): Settings = Settings()

fun Settings.withTlsMode(mode: TlsMode): Settings = copy(
    tlsMode = mode,
    imapPort = if (imapPort == defaultImapPort(tlsMode)) defaultImapPort(mode) else imapPort,
    smtpPort = if (smtpPort == defaultSmtpPort(tlsMode)) defaultSmtpPort(mode) else smtpPort,
)

internal val unlabeledCharsetNames = setOf(
    "utf-8",
    "utf8",
    "csutf8",
    "us-ascii",
    "ascii",
    "ansi-x3.4-1968",
    "csascii",
    "iso-8859-1",
    "iso8859-1",
    "latin1",
    "cp819",
    "csisolatin1",
    "iso-8859-8-i",
    "iso-8859-8-e",
    "cp1250",
    "cp1251",
    "cp1252",
    "cp1253",
    "cp1254",
    "cp1255",
    "cp1256",
    "cp1257",
    "cp1258",
    "iso-8859-2",
    "iso-8859-3",
    "iso-8859-4",
    "iso-8859-5",
    "iso-8859-6",
    "iso-8859-7",
    "iso-8859-8",
    "iso-8859-9",
    "iso-8859-10",
    "iso-8859-13",
    "iso-8859-14",
    "iso-8859-15",
    "iso-8859-16",
    "windows-1250",
    "windows-1251",
    "windows-1252",
    "windows-1253",
    "windows-1254",
    "windows-1255",
    "windows-1256",
    "windows-1257",
    "windows-1258",
    "koi8-r",
    "koi8-u",
    "shift-jis",
    "sjis",
    "x-sjis",
    "csshiftjis",
    "gb2312",
    "gb-2312-80",
    "csgb2312",
    "gbk",
    "cp936",
    "big5",
    "csbig5",
    "euc-kr",
    "euckr",
    "ks-c-5601-1987",
    "cseuckr",
    "ksc5601",
    "iso-2022-jp",
    "csiso2022jp",
)

/** Drop space, tab, CR, and LF, map A–Z to a–z, and map `_` to `-`. No JNI. */
internal fun unlabeledCharsetCanon(name: String): String = buildString(name.length) {
    for (ch in name) {
        when {
            ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n' -> Unit
            ch in 'A'..'Z' -> append(ch.lowercaseChar())
            ch == '_' -> append('-')
            else -> append(ch)
        }
    }
}

/** Known when the canon is in the decoder table, or [Charset.isSupported] accepts the trimmed text. */
internal fun knownUnlabeledCharset(name: String): Boolean {
    if (name.isBlank()) return false
    if (unlabeledCharsetCanon(name) in unlabeledCharsetNames) return true
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return false
    return try {
        Charset.isSupported(trimmed)
    } catch (_: IllegalArgumentException) {
        false
    }
}

internal fun clientSortCollationWord(value: ClientSortCollation): String = when (value) {
    ClientSortCollation.Locale -> "locale"
    ClientSortCollation.Plain -> "plain"
}

internal fun clientSortCollationOf(word: String): ClientSortCollation = when (word) {
    "locale" -> ClientSortCollation.Locale
    "plain" -> ClientSortCollation.Plain
    else -> throw IllegalArgumentException("clientSortCollation")
}

fun overAskThreshold(exists: Int, threshold: Long): Boolean =
    threshold >= 0 && exists > threshold

internal fun parseAskLimit(value: String): Int {
    val number = parseIntField(value)
    if (number < 0) return -1
    return number
}

class SieveTargetException(val kind: String) : Exception(kind)

class SieveLogin(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
)

fun sieveTarget(settings: Settings, inboundPassword: String?, sievePassword: String?): SieveLogin {
    val same = settings.sieveSameAsInbound
    val host = if (same) {
        if (settings.jmapFolders) {
            try {
                org.dlang.liveimap.jmap.jmapServerTarget(settings.imapHost).host
            } catch (_: org.dlang.liveimap.jmap.JmapFailure) {
                ""
            }
        } else {
            settings.imapHost
        }
    } else {
        settings.sieveHost
    }
    if (!same && settings.sieveHost.isBlank()) throw SieveTargetException("host")
    if (host.isBlank()) throw SieveTargetException("host")
    val password = if (same) inboundPassword else sievePassword
    if (password == null) throw SieveTargetException("password")
    val username = if (same) settings.username else settings.sieveUsername
    return SieveLogin(host, settings.sievePort, username, password)
}

internal fun parseSievePort(value: String): Int {
    val port = parseIntField(value)
    return if (port in 1..65535) port else 4190
}

fun slowerClientSort(settings: Settings): Boolean =
    settings.forceSlowerFallbacks || settings.clientSort

fun slowerClientThread(settings: Settings): Boolean =
    settings.forceSlowerFallbacks || settings.clientThread

fun slowerPoll(settings: Settings): Boolean =
    settings.forceSlowerFallbacks || settings.pollForNewMail

fun slowerStatusCounts(settings: Settings): Boolean =
    settings.forceSlowerFallbacks || settings.statusVisibleCounts

fun pollIntervalSeconds(settings: Settings): Int = settings.pollSeconds

internal fun encodeHiddenCapabilities(hidden: Set<String>): String =
    hidden.map { it.uppercase() }.distinct().sorted().joinToString(",") { percentEncode(it) }

internal fun decodeHiddenCapabilities(value: String): Set<String> {
    if (value.isEmpty()) return emptySet()
    val out = linkedSetOf<String>()
    for (part in value.split(',')) {
        val token = percentDecode(part)
        if (token.isNotEmpty()) out.add(token.uppercase())
    }
    return out
}

internal fun encodeWatchedFolders(values: List<String>): String =
    values.joinToString(",") { percentEncode(it) }

internal fun decodeWatchedFolders(value: String): List<String> {
    if (value.isEmpty()) return emptyList()
    return value.split(',').map { percentDecode(it) }
}

internal fun encodeSavedSearches(values: List<SavedSearch>): String =
    values.joinToString(",") { search ->
        "${percentEncode(search.name)}|${percentEncode(search.query)}|${search.field.name}"
    }

internal fun decodeSavedSearches(value: String): List<SavedSearch> {
    if (value.isEmpty()) return emptyList()
    val out = ArrayList<SavedSearch>()
    for (record in value.split(',')) {
        val parts = record.split('|')
        if (parts.size != 3) continue
        val name = percentDecode(parts[0])
        val query = percentDecode(parts[1])
        if (name.isEmpty() || query.isEmpty()) continue
        val field = decodeSavedSearchField(parts[2]) ?: continue
        out.add(SavedSearch(name, query, field))
    }
    return out
}

internal fun encodeSavedAdvanced(values: List<SavedAdvanced>): String =
    values.joinToString(",") { item ->
        "${percentEncode(item.name)}|${percentEncode(item.text)}|${item.scope.name}"
    }

internal fun decodeSavedAdvanced(value: String): List<SavedAdvanced> {
    if (value.isEmpty()) return emptyList()
    val out = ArrayList<SavedAdvanced>()
    for (record in value.split(',')) {
        val parts = record.split('|')
        if (parts.size != 3) continue
        val name = percentDecode(parts[0])
        val text = percentDecode(parts[1])
        if (name.isEmpty() || text.isEmpty()) continue
        val scope = decodeSavedAdvancedScope(parts[2]) ?: continue
        out.add(SavedAdvanced(name, text, scope))
    }
    return out
}

internal fun decodeSavedAdvancedScope(name: String): SearchScope? = when (name) {
    "Current" -> SearchScope.Current
    "Subtree" -> SearchScope.Subtree
    "Subscribed" -> SearchScope.Subscribed
    "All" -> SearchScope.All
    else -> null
}

internal fun decodeSavedSearchField(name: String): SimpleSearchField? = when (name) {
    "Subject" -> SimpleSearchField.Subject
    "From" -> SimpleSearchField.From
    "To" -> SimpleSearchField.To
    "Cc" -> SimpleSearchField.Cc
    "Participant" -> SimpleSearchField.Participant
    else -> null
}

internal fun encodeAltAddresses(values: List<String>): String =
    values.joinToString(",") { percentEncode(it) }

internal fun decodeAltAddresses(value: String): List<String> {
    if (value.isEmpty()) return emptyList()
    return value.split(',').map { percentDecode(it) }.filter { it.isNotEmpty() }
}

internal fun encodeInboundRules(rules: List<InboundRule>): String =
    rules.joinToString("\n") { encodeInboundRule(it) }

internal fun encodeInboundRule(rule: InboundRule): String {
    val criteria = rule.criteria.joinToString("&") { criterion ->
        "${criterion.field.name}=${percentEncode(criterion.value)}"
    }
    val actions = ArrayList<String>(6)
    if (rule.id.isNotEmpty()) actions.add("id=${percentEncode(rule.id)}")
    if (rule.fileInto.isNotEmpty()) actions.add("file=${percentEncode(rule.fileInto)}")
    if (SystemFlag.Seen in rule.flags) actions.add("seen")
    if (SystemFlag.Flagged in rule.flags) actions.add("flagged")
    if (rule.redirectTo.isNotEmpty()) actions.add("redirect=${percentEncode(rule.redirectTo)}")
    if (rule.discard) actions.add("discard")
    return "$criteria\t${actions.joinToString(",")}"
}

internal fun decodeInboundRules(value: String): List<InboundRule> {
    if (value.isEmpty()) return emptyList()
    val out = ArrayList<InboundRule>()
    for (line in value.split('\n')) {
        val rule = try {
            decodeInboundRule(line)
        } catch (_: Exception) {
            continue
        }
        out.add(rule)
    }
    return out
}

internal fun decodeInboundRule(value: String): InboundRule {
    val tab = value.indexOf('\t')
    if (tab <= 0 || tab == value.lastIndex) throw IllegalArgumentException("broken rule")
    val criteriaPart = value.substring(0, tab)
    val actionPart = value.substring(tab + 1)
    if ('\t' in actionPart) throw IllegalArgumentException("broken rule")
    val criteria = criteriaPart.split('&').map { decodeInboundCriterion(it) }
    var fileInto = ""
    val flags = linkedSetOf<SystemFlag>()
    var redirectTo = ""
    var discard = false
    var id = ""
    var sawFile = false
    var sawRedirect = false
    var sawDiscard = false
    var sawId = false
    for (token in actionPart.split(',')) {
        when {
            token.startsWith("id=") -> {
                if (sawId) throw IllegalArgumentException("broken rule")
                sawId = true
                id = percentDecode(token.substring("id=".length))
                if (id.isEmpty()) throw IllegalArgumentException("broken rule")
            }
            token == "seen" -> {
                if (!flags.add(SystemFlag.Seen)) throw IllegalArgumentException("broken rule")
            }
            token == "flagged" -> {
                if (!flags.add(SystemFlag.Flagged)) throw IllegalArgumentException("broken rule")
            }
            token == "discard" -> {
                if (sawDiscard) throw IllegalArgumentException("broken rule")
                sawDiscard = true
                discard = true
            }
            token.startsWith("file=") -> {
                if (sawFile) throw IllegalArgumentException("broken rule")
                sawFile = true
                fileInto = percentDecode(token.substring("file=".length))
                if (fileInto.isEmpty()) throw IllegalArgumentException("broken rule")
            }
            token.startsWith("redirect=") -> {
                if (sawRedirect) throw IllegalArgumentException("broken rule")
                sawRedirect = true
                redirectTo = percentDecode(token.substring("redirect=".length))
                if (redirectTo.isEmpty()) throw IllegalArgumentException("broken rule")
            }
            else -> throw IllegalArgumentException("broken rule")
        }
    }
    if (!sawFile && flags.isEmpty() && !sawRedirect && !discard) {
        throw IllegalArgumentException("broken rule")
    }
    return InboundRule(
        criteria = criteria,
        fileInto = fileInto,
        flags = flags,
        redirectTo = redirectTo,
        discard = discard,
        id = id,
    )
}

internal fun decodeInboundCriterion(token: String): RuleCriterion {
    val eq = token.indexOf('=')
    if (eq <= 0) throw IllegalArgumentException("broken rule")
    val field = when (token.substring(0, eq)) {
        "From" -> RuleField.From
        "To" -> RuleField.To
        "ListId" -> RuleField.ListId
        "Subject" -> RuleField.Subject
        else -> throw IllegalArgumentException("broken rule")
    }
    val decoded = percentDecode(token.substring(eq + 1))
    if (decoded.isEmpty()) throw IllegalArgumentException("broken rule")
    return RuleCriterion(field, decoded)
}

internal fun encodeCompletionSources(sources: List<String>): String =
    sources.joinToString(",") { percentEncode(it) }

internal fun decodeCompletionSources(value: String?): List<String> {
    if (value == null) return listOf(pineSourceId)
    if (value.isEmpty()) return emptyList()
    return value.split(',').map { percentDecode(it) }.filter { it.isNotEmpty() }
}

/** Every Pine address book of this account: (mailbox, read-only), in list order, without duplicate mailboxes. */
fun Settings.alpineBooks(): List<Pair<String, Boolean>> {
    val out = ArrayList<Pair<String, Boolean>>()
    for (book in addressBooks) {
        if (book.kind != "pine" || book.mailbox.isEmpty()) continue
        if (out.none { it.first == book.mailbox }) out += book.mailbox to book.readOnly
    }
    return out
}

/** A Pine book that is not read-only, or an Android account on the list. A read-only Pine book is not. */
fun AddressBookEntry.isWritableBook(): Boolean =
    kind == "android" || (kind == "pine" && !readOnly && mailbox.isNotEmpty())

/** Mark [index] as the only save target. A book that is not writable is left unmarked, and every other mark is cleared. */
fun markAddressSaveTarget(books: List<AddressBookEntry>, index: Int): List<AddressBookEntry> =
    books.mapIndexed { i, item -> item.copy(saveTarget = i == index && item.isWritableBook()) }

/** Writable books in list order. The same Pine mailbox is listed once. */
fun saveBookChoices(books: List<AddressBookEntry>): List<AddressBookEntry> {
    val seen = HashSet<String>()
    val out = ArrayList<AddressBookEntry>()
    for (book in books) {
        if (!book.isWritableBook()) continue
        val key = if (book.kind == "pine") {
            "pine\u0000${book.mailbox}"
        } else {
            "android\u0000${book.accountType}\u0000${book.accountName}"
        }
        if (!seen.add(key)) continue
        out += book
    }
    return out
}

/** Where a new contact is written. [AddressSave.None] writes nothing. [AddressSave.Ask] does not pick the first book. */
sealed class AddressSave {
    data object None : AddressSave()
    data object Ask : AddressSave()
    data class One(val book: AddressBookEntry) : AddressSave()
}

fun Settings.addressSave(): AddressSave {
    val target = addressBooks.firstOrNull { it.saveTarget && it.isWritableBook() }
    if (target != null) return AddressSave.One(target)
    val writable = addressBooks.filter { it.isWritableBook() }
    return when (writable.size) {
        0 -> AddressSave.None
        1 -> AddressSave.One(writable[0])
        else -> AddressSave.Ask
    }
}

/** Copy-screen id of one account's Alpine book (any account, not only the open one). */
fun alpineBookSourceId(accountId: String, mailbox: String): String =
    "alpine|${percentEncode(accountId)}|${percentEncode(mailbox)}"

fun alpineBookOf(id: String): Pair<String, String>? {
    if (!id.startsWith("alpine|")) return null
    val parts = id.removePrefix("alpine|").split('|', limit = 2)
    if (parts.size != 2) return null
    return try {
        percentDecode(parts[0]) to percentDecode(parts[1])
    } catch (_: IllegalArgumentException) {
        null
    }
}

fun androidSourceId(accountType: String, accountName: String): String =
    "android|${percentEncode(accountType)}|${percentEncode(accountName)}"

fun androidAccountOf(id: String): Pair<String, String>? {
    if (!id.startsWith("android|")) return null
    val parts = id.removePrefix("android|").split('|', limit = 2)
    if (parts.size != 2) return null
    return try {
        percentDecode(parts[0]) to percentDecode(parts[1])
    } catch (_: IllegalArgumentException) {
        null
    }
}

fun looksLikeEmail(value: String): Boolean {
    val trimmed = value.trim()
    if (trimmed.any { it.isWhitespace() }) return false
    val at = trimmed.indexOf('@')
    if (at <= 0 || trimmed.indexOf('@', at + 1) >= 0) return false
    val domain = trimmed.substring(at + 1)
    return domain.contains('.') && !domain.startsWith('.') && !domain.endsWith('.')
}

fun emailDefaultedFromUsername(username: String, email: String): String {
    if (email.isNotEmpty()) return email
    if (looksLikeEmail(username)) return username
    return email
}

internal fun delimiterText(value: String): String = try {
    percentDecode(value)
} catch (_: IllegalArgumentException) {
    if (value.length == 1) value else throw IllegalArgumentException("bad favorites")
}

internal fun encodeFavorite(favorite: FolderFavorite): String =
    "${if (favorite.node) "1" else "0"}|${percentEncode(favorite.mailbox)}|${percentEncode(favorite.delimiter.toString())}|${percentEncode(favorite.label)}"

internal fun encodeFavorites(favorites: List<FolderFavorite>): String =
    favorites.joinToString(";") { encodeFavorite(it) }

internal fun parseFavorites(value: String): List<FolderFavorite> {
    if (value.isEmpty()) return emptyList()
    val out = mutableListOf<FolderFavorite>()
    val seen = mutableSetOf<Pair<Boolean, String>>()
    for (part in value.split(';')) {
        val favorite = try {
            val bits = part.split('|')
            if (bits.size != 3 && bits.size != 4) throw IllegalArgumentException("bad favorites")
            val node = when (bits[0]) {
                "1" -> true
                "0" -> false
                else -> throw IllegalArgumentException("bad favorites")
            }
            val mailbox = percentDecode(bits[1])
            val delimText = delimiterText(bits[2])
            if (delimText.length != 1) throw IllegalArgumentException("bad favorites")
            val label = if (bits.size == 4) percentDecode(bits[3]) else ""
            FolderFavorite(node, mailbox, delimText[0], label)
        } catch (_: Exception) {
            continue
        }
        if (!seen.add(favorite.node to favorite.mailbox)) continue
        out += favorite
    }
    return out
}

internal fun encodeView(view: FolderView): String =
    "${view.key.name}|${if (view.newestFirst) "newest" else "oldest"}"

internal fun encodeFolderViews(views: Map<String, FolderView>): String =
    views.entries.joinToString(";") { (mailbox, view) ->
        "${percentEncode(mailbox)}|${encodeView(view)}"
    }

internal fun encodeFolderStarts(starts: Map<String, StartRule>): String =
    starts.entries.joinToString(";") { (mailbox, rule) ->
        "${percentEncode(mailbox)}|${rule.name}"
    }

internal fun parseFolderStarts(value: String): Map<String, StartRule> {
    if (value.isEmpty()) return emptyMap()
    val out = linkedMapOf<String, StartRule>()
    for (part in value.split(';')) {
        val parsed = try {
            val bits = part.split('|')
            if (bits.size != 2) throw IllegalArgumentException("bad folderStarts")
            val mailbox = percentDecode(bits[0])
            val rule = enumValueOf<StartRule>(bits[1])
            mailbox to rule
        } catch (_: Exception) {
            continue
        }
        if (out.containsKey(parsed.first)) continue
        out[parsed.first] = parsed.second
    }
    return out
}

internal fun encodeSwipe(binding: SwipeBinding): String =
    "${binding.action.name}|${percentEncode(binding.moveMailbox)}|${percentEncode(binding.flag)}"

internal fun parseView(value: String): FolderView {
    val bits = value.split('|')
    if (bits.size != 2) throw IllegalArgumentException("bad defaultView")
    return FolderView(enumValueOf(bits[0]), parseNewest(bits[1]))
}

internal fun parseFolderViews(value: String): Map<String, FolderView> {
    if (value.isEmpty()) return emptyMap()
    val out = linkedMapOf<String, FolderView>()
    for (part in value.split(';')) {
        val parsed = try {
            val bits = part.split('|')
            if (bits.size != 3) throw IllegalArgumentException("bad folderViews")
            val mailbox = percentDecode(bits[0])
            mailbox to FolderView(enumValueOf(bits[1]), parseNewest(bits[2]))
        } catch (_: Exception) {
            continue
        }
        if (out.containsKey(parsed.first)) continue
        out[parsed.first] = parsed.second
    }
    return out
}

internal fun parseExpanded(value: String): Set<String> {
    if (value.isEmpty()) return emptySet()
    val out = linkedSetOf<String>()
    for (part in value.split(';')) {
        val mailbox = try {
            percentDecode(part)
        } catch (_: Exception) {
            continue
        }
        out.add(mailbox)
    }
    return out
}

internal fun parseSwipe(value: String): SwipeBinding {
    val bits = value.split('|')
    if (bits.size != 3) throw IllegalArgumentException("bad swipe")
    return SwipeBinding(
        action = enumValueOf(bits[0]),
        moveMailbox = percentDecode(bits[1]),
        flag = percentDecode(bits[2]),
    )
}

internal fun parseNewest(value: String): Boolean = when (value) {
    "newest" -> true
    "oldest" -> false
    else -> throw IllegalArgumentException("bad enum")
}

internal fun decodeBodyView(values: Map<String, String>): BodyView {
    val named = values["bodyView"]
    if (named != null) return enumValueOf(named)
    if (values["preferHtml"]?.let { parseBoolean(it) } == true) return BodyView.PlainOrHtml
    return BodyView.PlainOrError
}

internal fun parseBoolean(value: String): Boolean = when (value) {
    "true" -> true
    "false" -> false
    else -> throw IllegalArgumentException("bad boolean")
}

internal fun parseIntField(value: String): Int {
    if (value.isEmpty() || value == "-" || value.any { it != '-' && !it.isDigit() }) {
        throw IllegalArgumentException("bad number")
    }
    if (value[0] == '-' && value.drop(1).any { !it.isDigit() }) {
        throw IllegalArgumentException("bad number")
    }
    if (value[0] != '-' && value.any { !it.isDigit() }) {
        throw IllegalArgumentException("bad number")
    }
    try {
        return value.toInt()
    } catch (e: NumberFormatException) {
        throw IllegalArgumentException("bad number")
    }
}

private const val HEX = "0123456789ABCDEF"

internal fun Int.isUnreserved(): Boolean =
    this in 'A'.code..'Z'.code ||
        this in 'a'.code..'z'.code ||
        this in '0'.code..'9'.code ||
        this == '-'.code ||
        this == '.'.code ||
        this == '_'.code ||
        this == '~'.code

fun percentEncode(value: String): String {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    val out = StringBuilder(bytes.size)
    for (byte in bytes) {
        val c = byte.toInt() and 0xFF
        if (c.isUnreserved()) {
            out.append(c.toChar())
        } else {
            out.append('%')
            out.append(HEX[c ushr 4])
            out.append(HEX[c and 0x0F])
        }
    }
    return out.toString()
}

fun percentDecode(value: String): String {
    val out = ArrayList<Byte>(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%') {
            if (i + 2 >= value.length) throw IllegalArgumentException("bad encoding")
            val hex = value.substring(i + 1, i + 3)
            val byte = org.dlang.liveimap.ui.compose.hexByteOrNull(hex) ?: throw IllegalArgumentException("bad encoding")
            out.add(byte.toByte())
            i += 3
        } else if (c.code.isUnreserved()) {
            out.add(c.code.toByte())
            i += 1
        } else {
            throw IllegalArgumentException("bad encoding")
        }
    }
    val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    try {
        return decoder.decode(ByteBuffer.wrap(out.toByteArray())).toString()
    } catch (e: CharacterCodingException) {
        throw IllegalArgumentException("bad encoding")
    }
}

/** This account's TLS relaxations, for the native session and ManageSieve. */
fun Settings.tlsPolicy(): org.dlang.liveimap.engine.TlsPolicy =
    org.dlang.liveimap.engine.TlsPolicy(acceptInvalidCert = acceptInvalidCert, allowOldTls = allowOldTls)

/** Dispatchers.IO plus this policy, so ManageSieve network leaves the main thread. */
fun org.dlang.liveimap.engine.TlsPolicy.ioContext(): kotlin.coroutines.CoroutineContext =
    kotlinx.coroutines.Dispatchers.IO + this

/** True when the fields the server check probes are unchanged. */
/** What the server-settings check does after a settings change (see [serverCheckStep]). */
enum class ServerCheckStep { Rebase, Wait, Check, Nothing }

/**
 * The reachability/conflict check runs only for a server change the user saved: switching accounts, loading,
 * Revert, or opening and leaving settings unchanged just adopt the current settings as the baseline, and while
 * a server text field still has focus (host typed, port not yet) the check waits.
 */
fun serverCheckStep(
    baseline: Settings?,
    baselineAccount: String?,
    current: Settings,
    currentAccount: String?,
    userSaved: Boolean,
    editing: Boolean,
): ServerCheckStep = when {
    baseline == null || baselineAccount != currentAccount || current.imapHost.isBlank() -> ServerCheckStep.Rebase
    sameServerSettings(baseline, current) -> ServerCheckStep.Nothing
    !userSaved -> ServerCheckStep.Rebase
    editing -> ServerCheckStep.Wait
    else -> ServerCheckStep.Check
}

fun sameServerSettings(a: Settings, b: Settings): Boolean =
    a.imapHost == b.imapHost && a.imapPort == b.imapPort && a.tlsMode == b.tlsMode &&
        a.acceptInvalidCert == b.acceptInvalidCert && a.allowOldTls == b.allowOldTls &&
        a.allowPlaintextAuth == b.allowPlaintextAuth

/** This account with the probed server fields taken from base (Revert in the server check). */
fun Settings.withServerOf(base: Settings): Settings = copy(
    imapHost = base.imapHost,
    imapPort = base.imapPort,
    tlsMode = base.tlsMode,
    acceptInvalidCert = base.acceptInvalidCert,
    allowOldTls = base.allowOldTls,
    allowPlaintextAuth = base.allowPlaintextAuth,
)

val IdleWakeSeconds = listOf(1, 2, 5, 10, 30, 60)

val ReaderTextZooms = listOf(75, 90, 100, 115, 130, 150, 175, 200, 250)
const val MinTextZoom = 50
const val MaxTextZoom = 300

fun clampTextZoom(percent: Int): Int = percent.coerceIn(MinTextZoom, MaxTextZoom)

/** Setting times the per-message pinch factor, clamped; what the reader shows. */
fun effectiveTextZoom(setting: Int, pinch: Float): Int {
    val p = if (pinch.isNaN() || pinch <= 0f) 1f else pinch
    return clampTextZoom(Math.round(clampTextZoom(setting) * p))
}


/** Warning text for an account without TLS (null when TLS is on); stronger when the password goes in clear. */
fun noTlsWarning(settings: Settings): Int? = when {
    settings.tlsMode != TlsMode.None -> null
    settings.allowPlaintextAuth -> com.davidlang.liveimap.R.string.settings_no_tls_password_warning
    else -> com.davidlang.liveimap.R.string.settings_no_tls_warning
}

