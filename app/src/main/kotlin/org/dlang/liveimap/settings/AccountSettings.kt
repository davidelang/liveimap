package org.dlang.liveimap.settings

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import org.dlang.liveimap.engine.sieve.InboundRule
import org.dlang.liveimap.engine.sieve.RuleCriterion
import org.dlang.liveimap.engine.sieve.RuleField
import org.dlang.liveimap.engine.sieve.SystemFlag
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

fun startRuleFor(mailbox: String, settings: AccountSettings): StartRule {
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

fun withFolderStart(settings: AccountSettings, mailbox: String, rule: StartRule?): AccountSettings {
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

/** Anti-phishing display of non-ASCII in folder names, From and subjects (see ui/NonAsciiText). */
enum class NonAsciiDisplay { Normal, HighlightSuspicious, HighlightAll, Warn, Escape }

enum class DeletePolicy {
    MarkDeleted,
    MoveToTrash,
    DeletePermanently,
}

/** COMPRESS=DEFLATE use: On (default), Off, or Auto = on over mobile data, off on Wi-Fi/Ethernet. */
enum class Compression {
    On,
    Off,
    Auto,
}

/** Whether to send COMPRESS DEFLATE for [mode] when the active network is cellular ([cellular]). */
fun compressionWanted(mode: Compression, cellular: Boolean): Boolean = when (mode) {
    Compression.On -> true
    Compression.Off -> false
    Compression.Auto -> cellular
}

enum class MoveMethod {
    CopyThenMarkDeleted,
    ImapMove,
}

fun moveCommandKind(method: MoveMethod, moveAdvertised: Boolean): String =
    if (method == MoveMethod.ImapMove && moveAdvertised) "Move" else "CopyThenDelete"

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
    Auto,
    On,
    Off,
}

fun defaultImapPort(mode: TlsMode): Int = if (mode == TlsMode.Implicit) 993 else 143

fun defaultSmtpPort(mode: TlsMode): Int = when (mode) {
    TlsMode.None -> 25
    TlsMode.StartTls -> 587
    TlsMode.Implicit -> 465
}

/** AccountStore still calls this. A new account is [AccountSettings], whose defaults are the tree. */
fun newAccountSettings(): AccountSettings = AccountSettings()

fun AccountSettings.withTlsMode(mode: TlsMode): AccountSettings = copy(
    tlsMode = mode,
    imapPort = if (imapPort == defaultImapPort(tlsMode)) defaultImapPort(mode) else imapPort,
    smtpPort = if (smtpPort == defaultSmtpPort(tlsMode)) defaultSmtpPort(mode) else smtpPort,
)

const val DefaultAskLimit = -1

fun overAskThreshold(exists: Int, threshold: Int): Boolean =
    threshold >= 0 && exists > threshold

data class AccountSettings(
    val imapHost: String = "",
    val imapPort: Int = SettingsDefaults.imapPort,
    val smtpHost: String = "",
    val smtpPort: Int = SettingsDefaults.smtpPort,
    val smtpUsername: String = "",
    /** F52 (David): use the IMAP password for SMTP only when this is ticked; never assumed. */
    val smtpSameAsImap: Boolean = false,
    /** Missing stored line copies [tlsMode]. Not Implicit by itself. */
    val smtpTlsMode: TlsMode = enumValueOf(SettingsDefaults.tlsMode),
    val smtpNoAuth: SmtpNoAuth = SmtpNoAuth.Auto,
    val username: String = "",
    val displayName: String = "",
    val email: String = "",
    val sentMailbox: String = "",
    val postponedMailbox: String = "",
    val addressBookMailbox: String = "",
    val addressBookHistory: Int = 3,
    val addressBookNeverTrim: Boolean = false,
    val spamMailbox: String = "",
    val markSeenOnOpen: Boolean = true,
    val showDeleted: Boolean = true,
    val bodyView: BodyView = BodyView.PlainOrError,
    val density: Density = Density.Compact,
    val defaultView: FolderView = FolderView(SortKey.Arrival, newestFirst = true),
    val folderViews: Map<String, FolderView> = emptyMap(),
    val expandedFolders: Set<String> = emptySet(),
    val swipeTrailing: SwipeBinding = SwipeBinding(SwipeAction.Delete),
    val swipeLeading: SwipeBinding = SwipeBinding(SwipeAction.ReplyAll),
    val readerSwipeTrailing: SwipeBinding = SwipeBinding(SwipeAction.Next),
    val readerSwipeLeading: SwipeBinding = SwipeBinding(SwipeAction.Previous),
    val bounceFcc: Boolean = false,
    val friendlyName: String = "",
    val theme: ThemeMode = ThemeMode.FollowSystem,
    val dynamicColor: Boolean = true,
    val showUnreadCounts: Boolean = false,
    val dateFormat: DateFormat = DateFormat.Short,
    val datePattern: String = "",
    val favorites: List<FolderFavorite> = emptyList(),
    val includeForwardAttachments: Boolean = true,
    val forwardAsAttachment: Boolean = false,
    val replyAboveQuote: Boolean = false,
    val askBeforeExpunge: Boolean = true,
    val pipelineCommands: Boolean = true,
    val logImapTraffic: Boolean = false,
    val showUserInDebugReport: Boolean = false,
    val inboxStart: StartRule = StartRule.Newest,
    val folderStart: StartRule = StartRule.Newest,
    val folderStarts: Map<String, StartRule> = emptyMap(),
    val startAfterChange: StartAfterChange = StartAfterChange.RerunRule,
    val showRecentRules: Boolean = true,
    val openAtInIndexMenu: Boolean = false,
    val pinercStartDefault: PinercStartDefault = PinercStartDefault.LeaveUnchanged,
    val readerTextLayout: TextLayout = TextLayout.Text,
    /** Reader text size in percent (plain text and HTML); pinch-zoom adjusts it per message on top. */
    val readerTextZoom: Int = 100,
    /** F38: how often the IDLE wait wakes when nothing arrives (stop requests wake it at once). */
    val idleWakeSeconds: Int = 5,
    val altAddresses: List<String> = emptyList(),
    val completionSources: List<String> = listOf(pineSourceId),
    /** Further Alpine address books on this server (pinerc's later `address-book` entries); read and written. */
    val extraAddressBooks: List<String> = emptyList(),
    /** Alpine global address books on this server (`global-address-book`); read only. */
    val globalAddressBooks: List<String> = emptyList(),
    val clientSort: Boolean = false,
    val clientThread: Boolean = false,
    val pollForNewMail: Boolean = false,
    val pollSeconds: Int = 60,
    val statusVisibleCounts: Boolean = false,
    val forceSlowerFallbacks: Boolean = false,
    val hiddenCapabilities: Set<String> = emptySet(),
    val autoExpunge: Boolean = false,
    val deletePolicy: DeletePolicy = DeletePolicy.MarkDeleted,
    val moveMethod: MoveMethod = MoveMethod.CopyThenMarkDeleted,
    val compression: Compression = Compression.On,
    /** "Move entire folder" also moves messages already marked \Deleted (default: skip them, UID SEARCH UNDELETED). */
    val moveAllIncludesDeleted: Boolean = false,
    val trashMailbox: String = "",
    val savedMailbox: String = "",
    val saveNameRule: SaveNameRule = SaveNameRule.DefaultFolder,
    val lastSaveMailbox: String = "",
    val threadIndexStyle: ThreadIndexStyle = ThreadIndexStyle.Expanded,
    val indexBar: IndexBarLayout = defaultIndexBar(),
    val selectionBar: SelectionBarLayout = defaultSelectionBar(),
    val folderBar: FolderBarLayout = defaultFolderBar(),
    val readerToolbar: ReaderToolbarLayout? = null,
    val composeBar: ComposeBarLayout = defaultComposeBar(),
    val toolbarRows: Int = 2,
    val composerWrapColumn: Int = 74,
    val quellFlowed: Boolean = false,
    val multiPane: MultiPane = enumValueOf(SettingsDefaults.multiPane),
    val folderPane: LayoutChoice = enumValueOf(SettingsDefaults.folderPane),
    val foldPosture: LayoutChoice = LayoutChoice.Off,
    val fullScreen: Boolean = false,
    /** Auto = [planLayout]. */
    val paneArrangement: ArrangementChoice = enumValueOf(SettingsDefaults.paneArrangement),
    val previewSize: PreviewChoice = enumValueOf(SettingsDefaults.previewSize),
    val folderPosition: FolderPositionChoice = enumValueOf(SettingsDefaults.folderPosition),
    /** Custom panel layout (PaneLayout.kt text form); empty = Auto. */
    val customLayout: String = "",
    // Account-default sending fields (design-decisions.md "Sending rules and roles").
    val sendingRules: String = "",
    val signature: String = "",
    val sigDashes: Boolean = true,
    val sigAtBottom: Boolean = false,
    val quotePrefix: String = org.dlang.liveimap.ui.compose.DefaultQuotePrefix,
    val replyLeadIn: String = org.dlang.liveimap.ui.compose.DefaultLeadIn,
    val dsnMode: org.dlang.liveimap.ui.compose.DsnMode = org.dlang.liveimap.ui.compose.DsnMode.Off,
    val composerHeaders: String = "",
    val viewerHeaders: String = "",
    val nonAsciiDisplay: NonAsciiDisplay = NonAsciiDisplay.HighlightSuspicious,
    val sieveHost: String = "",
    val sievePort: Int = 4190,
    val sieveTlsMode: TlsMode = TlsMode.StartTls,
    val tlsMode: TlsMode = enumValueOf(SettingsDefaults.tlsMode),
    val certPin: String = "",
    val sieveCertPin: String = "",
    /** F63: the JMAP (HTTPS) server's own pin; never the IMAP one. */
    val jmapCertPin: String = "",
    val allowPlaintextAuth: Boolean = false,
    /** Advanced, off by default: accept a certificate that fails validation (like pine /novalidate-cert). */
    val acceptInvalidCert: Boolean = false,
    /** Advanced, off by default: allow TLS 1.0/1.1, small DH keys and legacy ciphers for this account only. */
    val allowOldTls: Boolean = false,
    /** Advanced: force the IMAP login method. "" = automatic, "IMAP-LOGIN" = the LOGIN command, else a SASL name. */
    val authMechanism: String = "",
    /**
     * Advanced: TLS client certificate for IMAP and SMTP, the path of an imported PKCS#12 file in app-private
     * storage (see ClientCertStore; its password sits beside it, not in these settings). "" = none.
     */
    val clientCert: String = "",
    /**
     * Advanced: TLS client certificate from Android KeyChain (KeyChain.choosePrivateKeyAlias), the alias. The
     * key never leaves the system keystore; OpenSSL signs through it (JNI callback). Per account, exclusive
     * with [clientCert]: the UI sets one and clears the other. "" = none.
     */
    val clientCertAlias: String = "",
    val inboundRules: List<InboundRule> = emptyList(),
    val watchedFolders: List<String> = emptyList(),
    val extraIdleBudget: Int = 2,
    /** David's rule: most connections this account opens to its server (main, watch, search, extra IDLE). */
    val maxConnections: Int = org.dlang.liveimap.session.DefaultMaxConnections,
    val savedSearches: List<SavedSearch> = emptyList(),
    val savedAdvanced: List<SavedAdvanced> = emptyList(),
    val emailSubmission: Boolean = false,
    /** F68: browse folders over JMAP only when the user turned it on (discovery alone never switches routes). */
    val jmapFolders: Boolean = false,
    val askSort: Int = DefaultAskLimit,
    val askThread: Int = DefaultAskLimit,
    val askSearch: Int = DefaultAskLimit,
    val askFallback: Int = DefaultAskLimit,
    val sortAskSkip: Set<String> = emptySet(),
    val threadAskSkip: Set<String> = emptySet(),
    val searchAskSkip: Set<String> = emptySet(),
    val fallbackAskSkip: Set<String> = emptySet(),
    /** F69: lines with keys this version does not know (newer builds), kept verbatim so saving keeps them. */
    val unknownSettings: List<String> = emptyList(),
) {
    val preferHtml: Boolean
        get() = bodyView == BodyView.PlainOrHtml

    init {
        if (toolbarRows !in 1..4) throw IllegalArgumentException("bad toolbarRows")
        if (composerWrapColumn !in 0..998) throw IllegalArgumentException("bad composerWrapColumn")
        if (pollSeconds !in 15..600) throw IllegalArgumentException("pollSeconds")
    }

    /** tlsMode, then deletePolicy, then moveMethod, when that raw line is still kept. */
    fun preservedSecurityKey(): String? {
        val present = HashSet<String>()
        for (entry in unknownSettings) {
            val first = entry.substringBefore('\n')
            val eq = first.indexOf('=')
            if (eq > 0) present.add(first.substring(0, eq))
        }
        if ("tlsMode" in present) return "tlsMode"
        if ("deletePolicy" in present) return "deletePolicy"
        if ("moveMethod" in present) return "moveMethod"
        return null
    }

    /** The raw sieveTlsMode line, when it is still kept. Not a preservedSecurityKey. */
    fun preservedSieveTlsKey(): String? {
        for (entry in unknownSettings) {
            val first = entry.substringBefore('\n')
            val eq = first.indexOf('=')
            if (eq > 0 && first.substring(0, eq) == "sieveTlsMode") return "sieveTlsMode"
        }
        return null
    }

    /** A chosen value, including the default, drops every kept line for [key]. */
    fun withoutPreserved(key: String): AccountSettings {
        val kept = ArrayList<String>()
        for (entry in unknownSettings) {
            val first = entry.substringBefore('\n')
            val eq = first.indexOf('=')
            if (eq > 0 && first.substring(0, eq) == key) continue
            kept.add(entry)
        }
        return copy(unknownSettings = kept)
    }
}

private val fieldNames = listOf(
    "imapHost",
    "imapPort",
    "smtpHost",
    "smtpPort",
    "smtpTlsMode",
    "smtpUsername",
    "smtpSameAsImap",
    "smtpNoAuth",
    "username",
    "displayName",
    "email",
    "sentMailbox",
    "postponedMailbox",
    "addressBookMailbox",
    "spamMailbox",
    "markSeenOnOpen",
    "showDeleted",
    "preferHtml",
    "bodyView",
    "density",
    "defaultView",
    "folderViews",
    "expandedFolders",
    "swipeTrailing",
    "swipeLeading",
    "bounceFcc",
    "friendlyName",
    "theme",
    "showUnreadCounts",
    "dateFormat",
    "datePattern",
    "favorites",
    "includeForwardAttachments",
    "forwardAsAttachment",
    "replyAboveQuote",
    "askBeforeExpunge",
    "pipelineCommands",
    "logImapTraffic",
    "showUserInDebugReport",
    "readerBar",
    "dynamicColor",
    "inboxStart",
    "folderStart",
    "folderStarts",
    "startAfterChange",
    "showRecentRules",
    "openAtInIndexMenu",
    "pinercStartDefault",
    "readerTextLayout",
    "readerSwipeTrailing",
    "readerSwipeLeading",
    "readerTextZoom",
    "idleWakeSeconds",
    "altAddresses",
    "completionSources",
    "extraAddressBooks",
    "globalAddressBooks",
    "addressBookHistory",
    "addressBookNeverTrim",
    "clientSort",
    "clientThread",
    "pollForNewMail",
    "pollSeconds",
    "statusVisibleCounts",
    "forceSlowerFallbacks",
    "hiddenCapabilities",
    "autoExpunge",
    "deletePolicy",
    "moveMethod",
    "compression",
    "moveAllIncludesDeleted",
    "trashMailbox",
    "savedMailbox",
    "saveNameRule",
    "lastSaveMailbox",
    "threadIndexStyle",
    "indexBar",
    "selectionBar",
    "folderBar",
    "readerToolbar",
    "composeBar",
    "toolbarRows",
    "composerWrapColumn",
    "quellFlowed",
    "multiPane",
    "folderPane",
    "foldPosture",
    "fullScreen",
    "paneArrangement",
    "previewSize",
    "folderPosition",
    "customLayout",
    "sendingRules",
    "signature",
    "sigDashes",
    "sigAtBottom",
    "quotePrefix",
    "replyLeadIn",
    "dsnMode",
    "composerHeaders",
    "viewerHeaders",
    "nonAsciiDisplay",
    "sieveHost",
    "sievePort",
    "sieveTlsMode",
    "tlsMode",
    "certPin",
    "sieveCertPin",
    "jmapCertPin",
    "allowPlaintextAuth",
    "acceptInvalidCert",
    "allowOldTls",
    "authMechanism",
    "clientCert",
    "clientCertAlias",
    "inboundRules",
    "watchedFolders",
    "extraIdleBudget",
    "maxConnections",
    "savedSearches",
    "savedAdvanced",
    "emailSubmission",
    "jmapFolders",
    "askSort",
    "askThread",
    "askSearch",
    "askFallback",
    "sortAskSkip",
    "threadAskSkip",
    "searchAskSkip",
    "fallbackAskSkip",
)

private const val HEX = "0123456789ABCDEF"

// Lines a fresh account writes. A preserved key still at that line stays omitted.
private fun failureEncodedLine(key: String): String? = if (key == "tlsMode") "tlsMode=None" else null

private val defaultEncodedLines: Map<String, String> by lazy {
    AccountSettings().encode().lineSequence().filter { it.isNotEmpty() }.associate { line ->
        val eq = line.indexOf('=')
        line.substring(0, eq) to line
    }
}

fun AccountSettings.encode(): String = buildString {
    val preserved = HashSet<String>()
    for (entry in unknownSettings) {
        val first = entry.substringBefore('\n')
        val eq = first.indexOf('=')
        if (eq > 0) preserved.add(first.substring(0, eq))
    }
    val written = HashSet<String>()
    fun put(line: String) {
        val first = line.substringBefore('\n')
        val eq = first.indexOf('=')
        if (eq > 0) {
            val key = first.substring(0, eq)
            // A later edit replaces the raw line. The failed-decode default does not.
            if (key in preserved && (defaultEncodedLines[key] == first || failureEncodedLine(key) == first)) return
            written.add(key)
        }
        appendLine(line)
    }
    put("imapHost=${percentEncode(imapHost)}")
    put("imapPort=$imapPort")
    put("smtpHost=${percentEncode(smtpHost)}")
    put("smtpPort=$smtpPort")
    put("smtpTlsMode=${smtpTlsMode.name}")
    put("smtpUsername=${percentEncode(smtpUsername)}")
    put("smtpSameAsImap=$smtpSameAsImap")
    put("smtpNoAuth=${smtpNoAuth.name}")
    put("username=${percentEncode(username)}")
    put("displayName=${percentEncode(displayName)}")
    put("email=${percentEncode(email)}")
    put("sentMailbox=${percentEncode(sentMailbox)}")
    put("postponedMailbox=${percentEncode(postponedMailbox)}")
    put("addressBookMailbox=${percentEncode(addressBookMailbox)}")
    put("spamMailbox=${percentEncode(spamMailbox)}")
    put("markSeenOnOpen=$markSeenOnOpen")
    put("showDeleted=$showDeleted")
    put("preferHtml=$preferHtml")
    put("bodyView=${bodyView.name}")
    put("density=${density.name}")
    put("defaultView=${encodeView(defaultView)}")
    put("folderViews=${encodeFolderViews(folderViews)}")
    put("expandedFolders=${expandedFolders.joinToString(";") { percentEncode(it) }}")
    put("swipeTrailing=${encodeSwipe(swipeTrailing)}")
    put("swipeLeading=${encodeSwipe(swipeLeading)}")
    put("bounceFcc=$bounceFcc")
    put("friendlyName=${percentEncode(friendlyName)}")
    put("theme=${theme.name}")
    put("showUnreadCounts=$showUnreadCounts")
    put("dateFormat=${dateFormat.name}")
    put("datePattern=${percentEncode(datePattern)}")
    put("favorites=${encodeFavorites(favorites)}")
    put("includeForwardAttachments=$includeForwardAttachments")
    put("forwardAsAttachment=$forwardAsAttachment")
    put("replyAboveQuote=$replyAboveQuote")
    put("askBeforeExpunge=$askBeforeExpunge")
    put("pipelineCommands=$pipelineCommands")
    put("logImapTraffic=$logImapTraffic")
    put("showUserInDebugReport=$showUserInDebugReport")
    put("dynamicColor=$dynamicColor")
    put("inboxStart=${inboxStart.name}")
    put("folderStart=${folderStart.name}")
    put("folderStarts=${encodeFolderStarts(folderStarts)}")
    put("startAfterChange=${startAfterChange.name}")
    put("showRecentRules=$showRecentRules")
    put("openAtInIndexMenu=$openAtInIndexMenu")
    put("pinercStartDefault=${pinercStartDefault.name}")
    put("readerTextLayout=${readerTextLayout.name}")
    put("readerSwipeTrailing=${encodeSwipe(readerSwipeTrailing)}")
    put("readerSwipeLeading=${encodeSwipe(readerSwipeLeading)}")
    put("readerTextZoom=$readerTextZoom")
    put("idleWakeSeconds=$idleWakeSeconds")
    put("nonAsciiDisplay=${nonAsciiDisplay.name}")
    put("altAddresses=${encodeAltAddresses(altAddresses)}")
    put("completionSources=${encodeCompletionSources(completionSources)}")
    put("extraAddressBooks=${encodeCompletionSources(extraAddressBooks)}")
    put("globalAddressBooks=${encodeCompletionSources(globalAddressBooks)}")
    put("addressBookHistory=$addressBookHistory")
    put("addressBookNeverTrim=$addressBookNeverTrim")
    put("clientSort=$clientSort")
    put("clientThread=$clientThread")
    put("pollForNewMail=$pollForNewMail")
    put("pollSeconds=$pollSeconds")
    put("statusVisibleCounts=$statusVisibleCounts")
    put("forceSlowerFallbacks=$forceSlowerFallbacks")
    put("hiddenCapabilities=${encodeHiddenCapabilities(hiddenCapabilities)}")
    put("autoExpunge=$autoExpunge")
    put("deletePolicy=${deletePolicy.name}")
    put("moveMethod=${moveMethod.name}")
    put("compression=${compression.name}")
    put("moveAllIncludesDeleted=$moveAllIncludesDeleted")
    put("trashMailbox=${percentEncode(trashMailbox)}")
    put("savedMailbox=${percentEncode(savedMailbox)}")
    put("saveNameRule=${saveNameRule.name}")
    put("lastSaveMailbox=${percentEncode(lastSaveMailbox)}")
    put("threadIndexStyle=${threadIndexStyle.name}")
    put("indexBar=${encodeIndexBar(indexBar)}")
    put("selectionBar=${encodeSelectionBar(selectionBar)}")
    put("folderBar=${encodeFolderBar(folderBar)}")
    put("readerToolbar=${readerToolbar?.let { encodeReaderToolbar(it) }.orEmpty()}")
    put("composeBar=${encodeComposeBar(composeBar)}")
    put("toolbarRows=$toolbarRows")
    put("composerWrapColumn=$composerWrapColumn")
    put("quellFlowed=$quellFlowed")
    put("multiPane=${multiPane.name}")
    put("folderPane=${folderPane.name}")
    put("foldPosture=${foldPosture.name}")
    put("fullScreen=$fullScreen")
    put("paneArrangement=${paneArrangement.name}")
    put("previewSize=${previewSize.name}")
    put("folderPosition=${folderPosition.name}")
    put("customLayout=${percentEncode(customLayout)}")
    put("sendingRules=${percentEncode(sendingRules)}")
    put("signature=${percentEncode(signature)}")
    put("sigDashes=$sigDashes")
    put("sigAtBottom=$sigAtBottom")
    put("quotePrefix=${percentEncode(quotePrefix)}")
    put("replyLeadIn=${percentEncode(replyLeadIn)}")
    put("dsnMode=${dsnMode.name}")
    put("composerHeaders=${percentEncode(composerHeaders)}")
    put("viewerHeaders=${percentEncode(viewerHeaders)}")
    put("sieveHost=${percentEncode(sieveHost)}")
    put("sievePort=$sievePort")
    put("sieveTlsMode=${sieveTlsMode.name}")
    put("tlsMode=${tlsMode.name}")
    put("certPin=${percentEncode(certPin)}")
    put("sieveCertPin=${percentEncode(sieveCertPin)}")
    put("jmapCertPin=${percentEncode(jmapCertPin)}")
    put("allowPlaintextAuth=$allowPlaintextAuth")
    put("acceptInvalidCert=$acceptInvalidCert")
    put("allowOldTls=$allowOldTls")
    put("authMechanism=${percentEncode(authMechanism)}")
    put("clientCert=${percentEncode(clientCert)}")
    put("clientCertAlias=${percentEncode(clientCertAlias)}")
    put("inboundRules=${encodeInboundRules(inboundRules)}")
    put("watchedFolders=${encodeWatchedFolders(watchedFolders)}")
    put("extraIdleBudget=$extraIdleBudget")
    put("maxConnections=$maxConnections")
    put("savedSearches=${encodeSavedSearches(savedSearches)}")
    put("savedAdvanced=${encodeSavedAdvanced(savedAdvanced)}")
    put("emailSubmission=$emailSubmission")
    put("jmapFolders=$jmapFolders")
    put("askSort=$askSort")
    put("askThread=$askThread")
    put("askSearch=$askSearch")
    put("askFallback=$askFallback")
    put("sortAskSkip=${encodeWatchedFolders(sortAskSkip.sorted())}")
    put("threadAskSkip=${encodeWatchedFolders(threadAskSkip.sorted())}")
    put("searchAskSkip=${encodeWatchedFolders(searchAskSkip.sorted())}")
    put("fallbackAskSkip=${encodeWatchedFolders(fallbackAskSkip.sorted())}")
    for (entry in unknownSettings) {
        val first = entry.substringBefore('\n')
        val eq = first.indexOf('=')
        if (eq > 0 && first.substring(0, eq) in written) continue
        appendLine(entry)
    }
}

fun decodeAccountSettings(text: String): AccountSettings {
    val values = linkedMapOf<String, String>()
    val unknown = ArrayList<String>()
    var openKey: String? = null
    for (line in text.lines()) {
        if (line.isEmpty()) continue
        val eq = line.indexOf('=')
        val key = if (eq > 0) line.substring(0, eq) else ""
        if (eq > 0 && key in fieldNames) {
            values[key] = line.substring(eq + 1) // F69: a duplicate key: the last one wins
            openKey = key
        } else if (openKey == "inboundRules" && line.contains('\t')) {
            values[openKey] = values.getValue(openKey) + "\n" + line // rule lines always carry a TAB
        } else if (eq > 0) {
            // F69: a newer build's key: kept for round-tripping, not an error. Never keep anything credential-like
            // (secrets live in the keystore; a stray one here must not be carried along and re-saved).
            val lower = key.lowercase()
            if ("password" !in lower && "secret" !in lower && "token" !in lower) unknown += line
            openKey = null
        } else {
            openKey = null // F69: a malformed line is skipped
        }
    }
    for (key in fieldNames) {
        if (
            key == "smtpUsername" ||
            key == "smtpTlsMode" ||
            key == "smtpSameAsImap" ||
            key == "smtpNoAuth" ||
            key == "friendlyName" ||
            key == "theme" ||
            key == "showUnreadCounts" ||
            key == "dateFormat" ||
            key == "datePattern" ||
            key == "favorites" ||
            key == "spamMailbox" ||
            key == "bodyView" ||
            key == "preferHtml" ||
            key == "includeForwardAttachments" ||
            key == "forwardAsAttachment" ||
            key == "replyAboveQuote" ||
            key == "askBeforeExpunge" ||
            key == "pipelineCommands" ||
            key == "logImapTraffic" ||
            key == "showUserInDebugReport" ||
            key == "readerBar" ||
            key == "dynamicColor" ||
            key == "inboxStart" ||
            key == "folderStart" ||
            key == "folderStarts" ||
            key == "startAfterChange" ||
            key == "showRecentRules" ||
            key == "openAtInIndexMenu" ||
            key == "pinercStartDefault" ||
            key == "readerTextLayout" ||
            key == "readerSwipeTrailing" ||
            key == "readerSwipeLeading" ||
            key == "readerTextZoom" ||
            key == "idleWakeSeconds" ||
            key == "altAddresses" ||
            key == "completionSources" ||
            key == "extraAddressBooks" ||
            key == "globalAddressBooks" ||
            key == "addressBookHistory" ||
            key == "addressBookNeverTrim" ||
            key == "clientSort" ||
            key == "clientThread" ||
            key == "pollForNewMail" ||
            key == "pollSeconds" ||
            key == "statusVisibleCounts" ||
            key == "forceSlowerFallbacks" ||
            key == "hiddenCapabilities" ||
            key == "autoExpunge" ||
            key == "deletePolicy" ||
            key == "moveMethod" ||
            key == "compression" ||
            key == "moveAllIncludesDeleted" ||
            key == "trashMailbox" ||
            key == "savedMailbox" ||
            key == "saveNameRule" ||
            key == "lastSaveMailbox" ||
            key == "threadIndexStyle" ||
            key == "indexBar" ||
            key == "selectionBar" ||
            key == "folderBar" ||
            key == "readerToolbar" ||
            key == "composeBar" ||
            key == "toolbarRows" ||
            key == "composerWrapColumn" ||
            key == "quellFlowed" ||
            key == "multiPane" ||
            key == "folderPane" ||
            key == "foldPosture" ||
            key == "fullScreen" ||
            key == "paneArrangement" ||
            key == "previewSize" ||
            key == "folderPosition" ||
            key == "customLayout" ||
            key == "sendingRules" ||
            key == "signature" ||
            key == "sigDashes" ||
            key == "sigAtBottom" ||
            key == "quotePrefix" ||
            key == "replyLeadIn" ||
            key == "dsnMode" ||
            key == "composerHeaders" ||
            key == "viewerHeaders" ||
            key == "nonAsciiDisplay" ||
            key == "sieveHost" ||
            key == "sievePort" ||
            key == "sieveTlsMode" ||
            key == "tlsMode" ||
            key == "certPin" ||
            key == "sieveCertPin" ||
            key == "jmapCertPin" ||
            key == "allowPlaintextAuth" ||
            key == "acceptInvalidCert" ||
            key == "allowOldTls" ||
            key == "authMechanism" ||
            key == "clientCert" ||
            key == "clientCertAlias" ||
            key == "inboundRules" ||
            key == "watchedFolders" ||
            key == "extraIdleBudget" ||
            key == "maxConnections" ||
            key == "savedSearches" ||
            key == "savedAdvanced" ||
            key == "emailSubmission" ||
            key == "jmapFolders" ||
            key == "askSort" ||
            key == "askThread" ||
            key == "askSearch" ||
            key == "askFallback" ||
            key == "sortAskSkip" ||
            key == "threadAskSkip" ||
            key == "searchAskSkip" ||
            key == "fallbackAskSkip"
        ) {
            continue
        }
        // F69: a missing key takes its default (below) instead of failing the whole account
    }
    val d = AccountSettings()
    fun listDroppedAll(key: String, value: Any?): Boolean = when (key) {
        "inboundRules", "favorites" -> value is List<*> && value.isEmpty()
        "folderViews", "folderStarts" -> value is Map<*, *> && value.isEmpty()
        "expandedFolders" -> value is Set<*> && value.isEmpty()
        else -> false
    }
    // A throw keeps the raw line. A list that skips every element of a non-empty value does too.
    fun <T> field(key: String, default: T, read: () -> T): T {
        val raw = values[key]
        if (raw == null && key != "bodyView") return default
        return try {
            val value = read()
            if (raw != null && raw.isNotEmpty() && listDroppedAll(key, value)) unknown += "$key=$raw"
            value
        } catch (_: Exception) {
            if (raw != null) unknown += "$key=$raw"
            default
        }
    }
    val decodedTlsMode = field("tlsMode", TlsMode.None) { enumValueOf<TlsMode>(values.getValue("tlsMode")) }
    val decodedSmtpTls = if ("smtpTlsMode" !in values) {
        decodedTlsMode
    } else {
        field("smtpTlsMode", decodedTlsMode) { enumValueOf<TlsMode>(values.getValue("smtpTlsMode")) }
    }
    return AccountSettings(
        imapHost = field("imapHost", d.imapHost) { percentDecode(values.getValue("imapHost")) },
        imapPort = field("imapPort", d.imapPort) { parseIntField(values.getValue("imapPort")) },
        smtpHost = field("smtpHost", d.smtpHost) { percentDecode(values.getValue("smtpHost")) },
        smtpPort = field("smtpPort", d.smtpPort) { parseIntField(values.getValue("smtpPort")) },
        smtpUsername = field("smtpUsername", d.smtpUsername) { percentDecode(values.getValue("smtpUsername")) },
        smtpSameAsImap = field("smtpSameAsImap", d.smtpSameAsImap) { values["smtpSameAsImap"] == "true" },
        username = field("username", d.username) { percentDecode(values.getValue("username")) },
        displayName = field("displayName", d.displayName) { percentDecode(values.getValue("displayName")) },
        email = field("email", d.email) { percentDecode(values.getValue("email")) },
        sentMailbox = field("sentMailbox", d.sentMailbox) { percentDecode(values.getValue("sentMailbox")) },
        postponedMailbox = field("postponedMailbox", d.postponedMailbox) { percentDecode(values.getValue("postponedMailbox")) },
        addressBookMailbox = field("addressBookMailbox", d.addressBookMailbox) { percentDecode(values.getValue("addressBookMailbox")) },
        spamMailbox = field("spamMailbox", d.spamMailbox) { percentDecode(values.getValue("spamMailbox")) },
        markSeenOnOpen = field("markSeenOnOpen", d.markSeenOnOpen) { parseBoolean(values.getValue("markSeenOnOpen")) },
        showDeleted = field("showDeleted", d.showDeleted) { parseBoolean(values.getValue("showDeleted")) },
        bodyView = field("bodyView", d.bodyView) { decodeBodyView(values) },
        density = field("density", d.density) { enumValueOf(values.getValue("density")) },
        defaultView = field("defaultView", d.defaultView) { parseView(values.getValue("defaultView")) },
        folderViews = field("folderViews", d.folderViews) { parseFolderViews(values.getValue("folderViews")) },
        expandedFolders = field("expandedFolders", d.expandedFolders) { parseExpanded(values.getValue("expandedFolders")) },
        swipeTrailing = field("swipeTrailing", d.swipeTrailing) { parseSwipe(values.getValue("swipeTrailing")) },
        swipeLeading = field("swipeLeading", d.swipeLeading) { parseSwipe(values.getValue("swipeLeading")) },
        readerSwipeTrailing = field("readerSwipeTrailing", d.readerSwipeTrailing) { parseSwipe(values.getValue("readerSwipeTrailing")) },
        readerSwipeLeading = field("readerSwipeLeading", d.readerSwipeLeading) { parseSwipe(values.getValue("readerSwipeLeading")) },
        bounceFcc = field("bounceFcc", d.bounceFcc) { parseBoolean(values.getValue("bounceFcc")) },
        friendlyName = field("friendlyName", d.friendlyName) { percentDecode(values.getValue("friendlyName")) },
        theme = field("theme", d.theme) { enumValueOf<ThemeMode>(values.getValue("theme")) },
        showUnreadCounts = field("showUnreadCounts", d.showUnreadCounts) { parseBoolean(values.getValue("showUnreadCounts")) },
        dateFormat = field("dateFormat", d.dateFormat) { enumValueOf<DateFormat>(values.getValue("dateFormat")) },
        datePattern = field("datePattern", d.datePattern) { percentDecode(values.getValue("datePattern")) },
        favorites = field("favorites", d.favorites) { parseFavorites(values.getValue("favorites")) },
        includeForwardAttachments = field("includeForwardAttachments", d.includeForwardAttachments) { parseBoolean(values.getValue("includeForwardAttachments")) },
        forwardAsAttachment = field("forwardAsAttachment", d.forwardAsAttachment) { parseBoolean(values.getValue("forwardAsAttachment")) },
        replyAboveQuote = field("replyAboveQuote", d.replyAboveQuote) { parseBoolean(values.getValue("replyAboveQuote")) },
        askBeforeExpunge = field("askBeforeExpunge", d.askBeforeExpunge) { parseBoolean(values.getValue("askBeforeExpunge")) },
        pipelineCommands = field("pipelineCommands", d.pipelineCommands) { parseBoolean(values.getValue("pipelineCommands")) },
        logImapTraffic = field("logImapTraffic", d.logImapTraffic) { parseBoolean(values.getValue("logImapTraffic")) },
        showUserInDebugReport = field("showUserInDebugReport", d.showUserInDebugReport) { parseBoolean(values.getValue("showUserInDebugReport")) },
        dynamicColor = field("dynamicColor", d.dynamicColor) { parseBoolean(values.getValue("dynamicColor")) },
        inboxStart = field("inboxStart", d.inboxStart) { enumValueOf<StartRule>(values.getValue("inboxStart")) },
        folderStart = field("folderStart", d.folderStart) { enumValueOf<StartRule>(values.getValue("folderStart")) },
        folderStarts = field("folderStarts", d.folderStarts) { parseFolderStarts(values.getValue("folderStarts")) },
        startAfterChange = field("startAfterChange", d.startAfterChange) { enumValueOf<StartAfterChange>(values.getValue("startAfterChange")) },
        showRecentRules = field("showRecentRules", d.showRecentRules) { parseBoolean(values.getValue("showRecentRules")) },
        openAtInIndexMenu = field("openAtInIndexMenu", d.openAtInIndexMenu) { parseBoolean(values.getValue("openAtInIndexMenu")) },
        pinercStartDefault = field("pinercStartDefault", d.pinercStartDefault) { enumValueOf<PinercStartDefault>(values.getValue("pinercStartDefault")) },
        readerTextLayout = if ("readerTextLayout" !in values) d.readerTextLayout else enumValueOf<TextLayout>(values.getValue("readerTextLayout")),
        readerTextZoom = field("readerTextZoom", d.readerTextZoom) { clampTextZoom(parseIntField(values.getValue("readerTextZoom"))) },
        idleWakeSeconds = field("idleWakeSeconds", d.idleWakeSeconds) {
            val seconds = parseIntField(values.getValue("idleWakeSeconds"))
            if (seconds !in IdleWakeSeconds) throw IllegalArgumentException("bad idleWakeSeconds")
            seconds
        },
        altAddresses = field("altAddresses", d.altAddresses) { decodeAltAddresses(values.getValue("altAddresses")) },
        completionSources = field("completionSources", d.completionSources) { decodeCompletionSources(values["completionSources"]) },
        extraAddressBooks = field("extraAddressBooks", d.extraAddressBooks) { decodeCompletionSources(values.getValue("extraAddressBooks")) },
        globalAddressBooks = field("globalAddressBooks", d.globalAddressBooks) { decodeCompletionSources(values.getValue("globalAddressBooks")) },
        addressBookHistory = field("addressBookHistory", d.addressBookHistory) { parseIntField(values.getValue("addressBookHistory")) },
        addressBookNeverTrim = field("addressBookNeverTrim", d.addressBookNeverTrim) { parseBoolean(values.getValue("addressBookNeverTrim")) },
        clientSort = field("clientSort", d.clientSort) { parseBoolean(values.getValue("clientSort")) },
        clientThread = field("clientThread", d.clientThread) { parseBoolean(values.getValue("clientThread")) },
        pollForNewMail = field("pollForNewMail", d.pollForNewMail) { parseBoolean(values.getValue("pollForNewMail")) },
        pollSeconds = field("pollSeconds", d.pollSeconds) { parseIntField(values.getValue("pollSeconds")) },
        statusVisibleCounts = field("statusVisibleCounts", d.statusVisibleCounts) { parseBoolean(values.getValue("statusVisibleCounts")) },
        forceSlowerFallbacks = field("forceSlowerFallbacks", d.forceSlowerFallbacks) { parseBoolean(values.getValue("forceSlowerFallbacks")) },
        hiddenCapabilities = field("hiddenCapabilities", d.hiddenCapabilities) { decodeHiddenCapabilities(values.getValue("hiddenCapabilities")) },
        autoExpunge = field("autoExpunge", d.autoExpunge) { parseBoolean(values.getValue("autoExpunge")) },
        deletePolicy = field("deletePolicy", d.deletePolicy) { enumValueOf<DeletePolicy>(values.getValue("deletePolicy")) },
        moveMethod = field("moveMethod", d.moveMethod) { enumValueOf<MoveMethod>(values.getValue("moveMethod")) },
        compression = if ("compression" !in values) d.compression else enumValueOf<Compression>(values.getValue("compression")),
        moveAllIncludesDeleted = field("moveAllIncludesDeleted", d.moveAllIncludesDeleted) { parseBoolean(values.getValue("moveAllIncludesDeleted")) },
        trashMailbox = field("trashMailbox", d.trashMailbox) { percentDecode(values.getValue("trashMailbox")) },
        savedMailbox = field("savedMailbox", d.savedMailbox) { percentDecode(values.getValue("savedMailbox")) },
        saveNameRule = field("saveNameRule", d.saveNameRule) { enumValueOf<SaveNameRule>(values.getValue("saveNameRule")) },
        lastSaveMailbox = field("lastSaveMailbox", d.lastSaveMailbox) { percentDecode(values.getValue("lastSaveMailbox")) },
        threadIndexStyle = field("threadIndexStyle", d.threadIndexStyle) { enumValueOf<ThreadIndexStyle>(values.getValue("threadIndexStyle")) },
        indexBar = field("indexBar", d.indexBar) { parseIndexBar(values.getValue("indexBar")) },
        selectionBar = field("selectionBar", d.selectionBar) { parseSelectionBar(values.getValue("selectionBar")) },
        folderBar = field("folderBar", d.folderBar) { parseFolderBar(values.getValue("folderBar")) },
        readerToolbar = field("readerToolbar", d.readerToolbar) {
            val raw = values["readerToolbar"]
            if (raw.isNullOrEmpty()) null else parseReaderToolbar(raw)
        }.let { parsed ->
            // A stored readerBar is applied once, and only when readerToolbar is absent.
            // The historical default bar stays null so the default toolbar is used.
            if (values["readerToolbar"] != null) parsed
            else {
                val rawBar = values["readerBar"]
                if (rawBar == null || rawBar == "Reply;ReplyAll;Forward;Delete;Move") parsed
                else try {
                    readerToolbarFromStoredBar(rawBar)
                } catch (_: Exception) {
                    unknown += "readerBar=$rawBar"
                    parsed
                }
            }
        },
        composeBar = field("composeBar", d.composeBar) { parseComposeBar(values.getValue("composeBar")) },
        toolbarRows = field("toolbarRows", d.toolbarRows) {
            val rows = parseIntField(values.getValue("toolbarRows"))
            if (rows !in 1..4) throw IllegalArgumentException("bad toolbarRows")
            rows
        },
        composerWrapColumn = field("composerWrapColumn", d.composerWrapColumn) { parseIntField(values.getValue("composerWrapColumn")).coerceIn(0, 998) },
        quellFlowed = field("quellFlowed", d.quellFlowed) { parseBoolean(values.getValue("quellFlowed")) },
        multiPane = field("multiPane", d.multiPane) { enumValueOf<MultiPane>(values.getValue("multiPane")) },
        folderPane = field("folderPane", d.folderPane) { enumValueOf<LayoutChoice>(values.getValue("folderPane")) },
        foldPosture = field("foldPosture", d.foldPosture) { enumValueOf<LayoutChoice>(values.getValue("foldPosture")) },
        fullScreen = field("fullScreen", d.fullScreen) { parseBoolean(values.getValue("fullScreen")) },
        paneArrangement = if ("paneArrangement" !in values) d.paneArrangement else enumValueOf<ArrangementChoice>(values.getValue("paneArrangement")),
        folderPosition = if ("folderPosition" !in values) d.folderPosition else enumValueOf<FolderPositionChoice>(values.getValue("folderPosition")),
        customLayout = field("customLayout", d.customLayout) { values["customLayout"]?.let { percentDecode(it) }?.takeIf { decodePaneLayout(it) != null }.orEmpty() },
        sendingRules = field("sendingRules", d.sendingRules) { percentDecode(values.getValue("sendingRules")) },
        signature = field("signature", d.signature) { percentDecode(values.getValue("signature")) },
        sigDashes = field("sigDashes", d.sigDashes) { values.getValue("sigDashes") == "true" },
        sigAtBottom = field("sigAtBottom", d.sigAtBottom) { values.getValue("sigAtBottom") == "true" },
        quotePrefix = field("quotePrefix", d.quotePrefix) { percentDecode(values.getValue("quotePrefix")) },
        replyLeadIn = field("replyLeadIn", d.replyLeadIn) { percentDecode(values.getValue("replyLeadIn")) },
        dsnMode = if ("dsnMode" !in values) d.dsnMode else enumValueOf<org.dlang.liveimap.ui.compose.DsnMode>(values.getValue("dsnMode")),
        composerHeaders = field("composerHeaders", d.composerHeaders) { percentDecode(values.getValue("composerHeaders")) },
        viewerHeaders = field("viewerHeaders", d.viewerHeaders) { percentDecode(values.getValue("viewerHeaders")) },
        nonAsciiDisplay = if ("nonAsciiDisplay" !in values) d.nonAsciiDisplay else enumValueOf<NonAsciiDisplay>(values.getValue("nonAsciiDisplay")),
        previewSize = if ("previewSize" !in values) d.previewSize else enumValueOf<PreviewChoice>(values.getValue("previewSize")),
        sieveHost = field("sieveHost", d.sieveHost) { percentDecode(values.getValue("sieveHost")) },
        sievePort = field("sievePort", d.sievePort) { parseSievePort(values.getValue("sievePort")) },
        sieveTlsMode = field("sieveTlsMode", d.sieveTlsMode) { enumValueOf<TlsMode>(values.getValue("sieveTlsMode")) },
        tlsMode = decodedTlsMode,
        smtpTlsMode = decodedSmtpTls,
        smtpNoAuth = field("smtpNoAuth", SmtpNoAuth.Auto) { enumValueOf<SmtpNoAuth>(values.getValue("smtpNoAuth")) },
        certPin = field("certPin", d.certPin) { percentDecode(values.getValue("certPin")) },
        sieveCertPin = field("sieveCertPin", d.sieveCertPin) { percentDecode(values.getValue("sieveCertPin")) },
        jmapCertPin = field("jmapCertPin", d.jmapCertPin) { percentDecode(values.getValue("jmapCertPin")) },
        allowPlaintextAuth = field("allowPlaintextAuth", d.allowPlaintextAuth) { parseBoolean(values.getValue("allowPlaintextAuth")) },
        acceptInvalidCert = field("acceptInvalidCert", d.acceptInvalidCert) { parseBoolean(values.getValue("acceptInvalidCert")) },
        allowOldTls = field("allowOldTls", d.allowOldTls) { parseBoolean(values.getValue("allowOldTls")) },
        authMechanism = field("authMechanism", d.authMechanism) { percentDecode(values.getValue("authMechanism")).trim().uppercase() },
        clientCert = field("clientCert", d.clientCert) { percentDecode(values.getValue("clientCert")) },
        clientCertAlias = field("clientCertAlias", d.clientCertAlias) { percentDecode(values.getValue("clientCertAlias")) },
        inboundRules = field("inboundRules", d.inboundRules) { decodeInboundRules(values.getValue("inboundRules")) },
        watchedFolders = field("watchedFolders", d.watchedFolders) { decodeWatchedFolders(values.getValue("watchedFolders")) },
        extraIdleBudget = field("extraIdleBudget", d.extraIdleBudget) { parseIntField(values.getValue("extraIdleBudget")) },
        // Never unlimited: a stored value outside the range is clamped to it.
        maxConnections = field("maxConnections", d.maxConnections) {
            org.dlang.liveimap.session.clampMaxConnections(parseIntField(values.getValue("maxConnections")))
        },
        savedSearches = field("savedSearches", d.savedSearches) { decodeSavedSearches(values.getValue("savedSearches")) },
        savedAdvanced = field("savedAdvanced", d.savedAdvanced) { decodeSavedAdvanced(values.getValue("savedAdvanced")) },
        emailSubmission = field("emailSubmission", d.emailSubmission) { parseBoolean(values.getValue("emailSubmission")) },
        jmapFolders = field("jmapFolders", d.jmapFolders) { parseBoolean(values.getValue("jmapFolders")) },
        askSort = field("askSort", d.askSort) { parseAskLimit(values.getValue("askSort")) },
        askThread = field("askThread", d.askThread) { parseAskLimit(values.getValue("askThread")) },
        askSearch = field("askSearch", d.askSearch) { parseAskLimit(values.getValue("askSearch")) },
        askFallback = field("askFallback", d.askFallback) { parseAskLimit(values.getValue("askFallback")) },
        sortAskSkip = field("sortAskSkip", d.sortAskSkip) { decodeWatchedFolders(values.getValue("sortAskSkip")).toSet() },
        threadAskSkip = field("threadAskSkip", d.threadAskSkip) { decodeWatchedFolders(values.getValue("threadAskSkip")).toSet() },
        searchAskSkip = field("searchAskSkip", d.searchAskSkip) { decodeWatchedFolders(values.getValue("searchAskSkip")).toSet() },
        fallbackAskSkip = field("fallbackAskSkip", d.fallbackAskSkip) { decodeWatchedFolders(values.getValue("fallbackAskSkip")).toSet() },
        unknownSettings = unknown,
    )
}

private fun parseAskLimit(value: String): Int {
    val number = parseIntField(value)
    if (number < 0) return -1
    return number
}

fun sieveEndpoint(settings: AccountSettings): Pair<String, Int> {
    val host = if (settings.sieveHost.isBlank()) settings.imapHost else settings.sieveHost
    return host to settings.sievePort
}

private fun parseSievePort(value: String): Int {
    val port = parseIntField(value)
    return if (port in 1..65535) port else 4190
}

fun slowerClientSort(settings: AccountSettings): Boolean =
    settings.forceSlowerFallbacks || settings.clientSort

fun slowerClientThread(settings: AccountSettings): Boolean =
    settings.forceSlowerFallbacks || settings.clientThread

fun slowerPoll(settings: AccountSettings): Boolean =
    settings.forceSlowerFallbacks || settings.pollForNewMail

fun slowerStatusCounts(settings: AccountSettings): Boolean =
    settings.forceSlowerFallbacks || settings.statusVisibleCounts

fun pollIntervalSeconds(settings: AccountSettings): Int = settings.pollSeconds

private fun encodeHiddenCapabilities(hidden: Set<String>): String =
    hidden.map { it.uppercase() }.distinct().sorted().joinToString(",") { percentEncode(it) }

private fun decodeHiddenCapabilities(value: String): Set<String> {
    if (value.isEmpty()) return emptySet()
    val out = linkedSetOf<String>()
    for (part in value.split(',')) {
        val token = percentDecode(part)
        if (token.isNotEmpty()) out.add(token.uppercase())
    }
    return out
}

private fun encodeWatchedFolders(values: List<String>): String =
    values.joinToString(",") { percentEncode(it) }

private fun decodeWatchedFolders(value: String): List<String> {
    if (value.isEmpty()) return emptyList()
    return value.split(',').map { percentDecode(it) }
}

private fun encodeSavedSearches(values: List<SavedSearch>): String =
    values.joinToString(",") { search ->
        "${percentEncode(search.name)}|${percentEncode(search.query)}|${search.field.name}"
    }

private fun decodeSavedSearches(value: String): List<SavedSearch> {
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

private fun encodeSavedAdvanced(values: List<SavedAdvanced>): String =
    values.joinToString(",") { item ->
        "${percentEncode(item.name)}|${percentEncode(item.text)}|${item.scope.name}"
    }

private fun decodeSavedAdvanced(value: String): List<SavedAdvanced> {
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

private fun decodeSavedAdvancedScope(name: String): SearchScope? = when (name) {
    "Current" -> SearchScope.Current
    "Subtree" -> SearchScope.Subtree
    "Subscribed" -> SearchScope.Subscribed
    "All" -> SearchScope.All
    else -> null
}

private fun decodeSavedSearchField(name: String): SimpleSearchField? = when (name) {
    "Subject" -> SimpleSearchField.Subject
    "From" -> SimpleSearchField.From
    "To" -> SimpleSearchField.To
    "Cc" -> SimpleSearchField.Cc
    "Participant" -> SimpleSearchField.Participant
    else -> null
}

private fun encodeAltAddresses(values: List<String>): String =
    values.joinToString(",") { percentEncode(it) }

private fun decodeAltAddresses(value: String): List<String> {
    if (value.isEmpty()) return emptyList()
    return value.split(',').map { percentDecode(it) }.filter { it.isNotEmpty() }
}

private fun encodeInboundRules(rules: List<InboundRule>): String =
    rules.joinToString("\n") { encodeInboundRule(it) }

private fun encodeInboundRule(rule: InboundRule): String {
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

private fun decodeInboundRules(value: String): List<InboundRule> {
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

private fun decodeInboundRule(value: String): InboundRule {
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

private fun decodeInboundCriterion(token: String): RuleCriterion {
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

private fun encodeCompletionSources(sources: List<String>): String =
    sources.joinToString(",") { percentEncode(it) }

private fun decodeCompletionSources(value: String?): List<String> {
    if (value == null) return listOf(pineSourceId)
    if (value.isEmpty()) return emptyList()
    return value.split(',').map { percentDecode(it) }.filter { it.isNotEmpty() }
}

/** Every Alpine address book of this account: (mailbox, read-only), primary first, without duplicates. */
fun AccountSettings.alpineBooks(): List<Pair<String, Boolean>> {
    val out = ArrayList<Pair<String, Boolean>>()
    fun add(name: String, readOnly: Boolean) {
        if (name.isNotEmpty() && out.none { it.first == name }) out += name to readOnly
    }
    add(addressBookMailbox, false)
    for (b in extraAddressBooks) add(b, false)
    for (b in globalAddressBooks) add(b, true)
    return out
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

private fun delimiterText(value: String): String = try {
    percentDecode(value)
} catch (_: IllegalArgumentException) {
    if (value.length == 1) value else throw IllegalArgumentException("bad favorites")
}

private fun encodeFavorite(favorite: FolderFavorite): String =
    "${if (favorite.node) "1" else "0"}|${percentEncode(favorite.mailbox)}|${percentEncode(favorite.delimiter.toString())}|${percentEncode(favorite.label)}"

private fun encodeFavorites(favorites: List<FolderFavorite>): String =
    favorites.joinToString(";") { encodeFavorite(it) }

private fun parseFavorites(value: String): List<FolderFavorite> {
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

private val storedReaderBarOrder = listOf(
    "Reply",
    "ReplyAll",
    "Forward",
    "Delete",
    "Move",
    "Spam",
    "Bounce",
    "Headers",
)

private fun storedReaderAction(name: String): ReaderToolbarAction = when (name) {
    "Reply" -> ReaderToolbarAction.Reply
    "ReplyAll" -> ReaderToolbarAction.ReplyAll
    "Forward" -> ReaderToolbarAction.Forward
    "Delete" -> ReaderToolbarAction.Delete
    "Move" -> ReaderToolbarAction.Move
    "Spam" -> ReaderToolbarAction.Spam
    "Bounce" -> ReaderToolbarAction.Bounce
    "Headers" -> ReaderToolbarAction.Headers
    else -> throw IllegalArgumentException("bad readerBar")
}

/** Today's readerToolbarFrom: Refresh plus the first four saved actions, then the rest, then the missing ones. */
private fun readerToolbarFromStoredBar(value: String): ReaderToolbarLayout {
    val shown = if (value.isEmpty()) emptyList() else value.split(';')
    val seen = HashSet<String>()
    val mapped = ArrayList<ReaderToolbarAction>(shown.size)
    for (part in shown) {
        val action = storedReaderAction(part)
        if (!seen.add(part)) throw IllegalArgumentException("bad readerBar")
        mapped += action
    }
    val missing = storedReaderBarOrder.filter { it !in seen }.map { storedReaderAction(it) }
    return ReaderToolbarLayout(
        toolbar = listOf(ReaderToolbarAction.Refresh) + mapped.take(4),
        overflow = mapped.drop(4) + missing,
        hidden = emptyList(),
    )
}

private fun encodeView(view: FolderView): String =
    "${view.key.name}|${if (view.newestFirst) "newest" else "oldest"}"

private fun encodeFolderViews(views: Map<String, FolderView>): String =
    views.entries.joinToString(";") { (mailbox, view) ->
        "${percentEncode(mailbox)}|${encodeView(view)}"
    }

private fun encodeFolderStarts(starts: Map<String, StartRule>): String =
    starts.entries.joinToString(";") { (mailbox, rule) ->
        "${percentEncode(mailbox)}|${rule.name}"
    }

private fun parseFolderStarts(value: String): Map<String, StartRule> {
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

private fun encodeSwipe(binding: SwipeBinding): String =
    "${binding.action.name}|${percentEncode(binding.moveMailbox)}|${percentEncode(binding.flag)}"

private fun parseView(value: String): FolderView {
    val bits = value.split('|')
    if (bits.size != 2) throw IllegalArgumentException("bad defaultView")
    return FolderView(enumValueOf(bits[0]), parseNewest(bits[1]))
}

private fun parseFolderViews(value: String): Map<String, FolderView> {
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

private fun parseExpanded(value: String): Set<String> {
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

private fun parseSwipe(value: String): SwipeBinding {
    val bits = value.split('|')
    if (bits.size != 3) throw IllegalArgumentException("bad swipe")
    return SwipeBinding(
        action = enumValueOf(bits[0]),
        moveMailbox = percentDecode(bits[1]),
        flag = percentDecode(bits[2]),
    )
}

private fun parseNewest(value: String): Boolean = when (value) {
    "newest" -> true
    "oldest" -> false
    else -> throw IllegalArgumentException("bad enum")
}

private fun decodeBodyView(values: Map<String, String>): BodyView {
    val named = values["bodyView"]
    if (named != null) return enumValueOf(named)
    if (values["preferHtml"]?.let { parseBoolean(it) } == true) return BodyView.PlainOrHtml
    return BodyView.PlainOrError
}

private fun parseBoolean(value: String): Boolean = when (value) {
    "true" -> true
    "false" -> false
    else -> throw IllegalArgumentException("bad boolean")
}

private fun parseIntField(value: String): Int {
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

private fun Int.isUnreserved(): Boolean =
    this in 'A'.code..'Z'.code ||
        this in 'a'.code..'z'.code ||
        this in '0'.code..'9'.code ||
        this == '-'.code ||
        this == '.'.code ||
        this == '_'.code ||
        this == '~'.code

private fun percentEncode(value: String): String {
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

private fun percentDecode(value: String): String {
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
fun AccountSettings.tlsPolicy(): org.dlang.liveimap.engine.TlsPolicy =
    org.dlang.liveimap.engine.TlsPolicy(acceptInvalidCert = acceptInvalidCert, allowOldTls = allowOldTls)

/** True when the fields the server check probes are unchanged. */
/** What the server-settings check does after a settings change (see [serverCheckStep]). */
enum class ServerCheckStep { Rebase, Wait, Check, Nothing }

/**
 * The reachability/conflict check runs only for a server change the user saved: switching accounts, loading,
 * Revert, or opening and leaving settings unchanged just adopt the current settings as the baseline, and while
 * a server text field still has focus (host typed, port not yet) the check waits.
 */
fun serverCheckStep(
    baseline: AccountSettings?,
    baselineAccount: String?,
    current: AccountSettings,
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

fun sameServerSettings(a: AccountSettings, b: AccountSettings): Boolean =
    a.imapHost == b.imapHost && a.imapPort == b.imapPort && a.tlsMode == b.tlsMode &&
        a.acceptInvalidCert == b.acceptInvalidCert && a.allowOldTls == b.allowOldTls &&
        a.allowPlaintextAuth == b.allowPlaintextAuth

/** This account with the probed server fields taken from base (Revert in the server check). */
fun AccountSettings.withServerOf(base: AccountSettings): AccountSettings = copy(
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
fun noTlsWarning(settings: AccountSettings): Int? = when {
    settings.tlsMode != TlsMode.None -> null
    settings.allowPlaintextAuth -> com.davidlang.liveimap.R.string.settings_no_tls_password_warning
    else -> com.davidlang.liveimap.R.string.settings_no_tls_warning
}

