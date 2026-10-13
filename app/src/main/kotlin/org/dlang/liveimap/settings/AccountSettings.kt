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

fun densityLabel(density: Density): String = when (density) {
    Density.Compact -> "Compact"
    Density.Medium -> "Comfortable"
    Density.Large -> "Large"
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

fun startAfterChangeLabel(value: StartAfterChange): String = when (value) {
    StartAfterChange.RerunRule -> "Run the start rule again"
    StartAfterChange.KeepTopVisible -> "Keep the top visible message"
}

enum class PinercStartDefault {
    LeaveUnchanged,
    AlpineDefault,
}

fun pinercStartDefaultLabel(value: PinercStartDefault): String = when (value) {
    PinercStartDefault.LeaveUnchanged -> "Leave unchanged"
    PinercStartDefault.AlpineDefault -> "Use alpine's default (first unread)"
}

fun openAtMenuText(settings: AccountSettings): String? =
    if (settings.openAtInIndexMenu) "Open this folder at…" else null

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
}

fun swipeActionLabel(action: SwipeAction): String = when (action) {
    SwipeAction.Delete -> "Delete"
    SwipeAction.Move -> "Move"
    SwipeAction.Reply -> "Reply"
    SwipeAction.ReplyAll -> "Reply all"
    SwipeAction.SetFlag -> "Set flag"
    SwipeAction.ClearFlag -> "Clear flag"
    SwipeAction.FlagScreen -> "Flag screen"
}

enum class ThemeMode {
    Dark,
    Light,
    FollowSystem,
}

fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.Dark -> "Dark"
    ThemeMode.Light -> "Light"
    ThemeMode.FollowSystem -> "System default"
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

enum class ReaderAction {
    Reply,
    ReplyAll,
    Forward,
    Delete,
    Move,
    Spam,
    Bounce,
    /** Toggle the raw header view (Pine's H): BODY.PEEK[HEADER] shown unparsed. */
    Headers,
}

fun readerActionLabel(action: ReaderAction): String = when (action) {
    ReaderAction.Reply -> "Reply"
    ReaderAction.ReplyAll -> "Reply all"
    ReaderAction.Forward -> "Forward"
    ReaderAction.Delete -> "Delete"
    ReaderAction.Move -> "Move"
    ReaderAction.Spam -> "Spam"
    ReaderAction.Bounce -> "Bounce"
    ReaderAction.Headers -> "Headers"
}

val defaultReaderBar: List<ReaderAction> = listOf(
    ReaderAction.Reply,
    ReaderAction.ReplyAll,
    ReaderAction.Forward,
    ReaderAction.Delete,
    ReaderAction.Move,
)

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

fun defaultImapPort(mode: TlsMode): Int = if (mode == TlsMode.Implicit) 993 else 143

fun defaultSmtpPort(mode: TlsMode): Int = when (mode) {
    TlsMode.None -> 25
    TlsMode.StartTls -> 587
    TlsMode.Implicit -> 465
}

fun newAccountSettings(): AccountSettings = AccountSettings(
    imapPort = defaultImapPort(TlsMode.Implicit),
    smtpPort = defaultSmtpPort(TlsMode.Implicit),
    tlsMode = TlsMode.Implicit,
    multiPane = MultiPane.Auto,
    folderPane = LayoutChoice.Auto,
    paneArrangement = ArrangementChoice.Auto,
    previewSize = PreviewChoice.Auto,
    folderPosition = FolderPositionChoice.Auto,
)

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
    val imapPort: Int = 143,
    val smtpHost: String = "",
    val smtpPort: Int = 25,
    val smtpUsername: String = "",
    /** F52 (David): use the IMAP password for SMTP only when this is ticked; never assumed. */
    val smtpSameAsImap: Boolean = false,
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
    val readerBar: List<ReaderAction> = defaultReaderBar,
    val inboxStart: StartRule = StartRule.Newest,
    val folderStart: StartRule = StartRule.Newest,
    val folderStarts: Map<String, StartRule> = emptyMap(),
    val startAfterChange: StartAfterChange = StartAfterChange.RerunRule,
    val showRecentRules: Boolean = true,
    val openAtInIndexMenu: Boolean = false,
    val pinercStartDefault: PinercStartDefault = PinercStartDefault.LeaveUnchanged,
    val plainTextMonospace: Boolean = false,
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
    // Off here so stored accounts without the key keep Off; new accounts get Auto from [newAccountSettings].
    val multiPane: MultiPane = MultiPane.Off,
    val folderPane: LayoutChoice = LayoutChoice.Off,
    val foldPosture: LayoutChoice = LayoutChoice.Off,
    val fullScreen: Boolean = false,
    /** Auto = [planLayout]; stored accounts without the key keep SideBySide. */
    val paneArrangement: ArrangementChoice = ArrangementChoice.SideBySide,
    val previewSize: PreviewChoice = PreviewChoice.Density,
    val folderPosition: FolderPositionChoice = FolderPositionChoice.Beside,
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
    val tlsMode: TlsMode = TlsMode.None,
    val certPin: String = "",
    val sieveCertPin: String = "",
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
    }
}

private val fieldNames = listOf(
    "imapHost",
    "imapPort",
    "smtpHost",
    "smtpPort",
    "smtpUsername",
    "smtpSameAsImap",
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
    "plainTextMonospace",
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
    "tlsMode",
    "certPin",
    "sieveCertPin",
    "allowPlaintextAuth",
    "acceptInvalidCert",
    "allowOldTls",
    "authMechanism",
    "clientCert",
    "clientCertAlias",
    "inboundRules",
    "watchedFolders",
    "extraIdleBudget",
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

fun AccountSettings.encode(): String = buildString {
    appendLine("imapHost=${percentEncode(imapHost)}")
    appendLine("imapPort=$imapPort")
    appendLine("smtpHost=${percentEncode(smtpHost)}")
    appendLine("smtpPort=$smtpPort")
    if (smtpUsername.isNotEmpty()) appendLine("smtpUsername=${percentEncode(smtpUsername)}")
    if (smtpSameAsImap) appendLine("smtpSameAsImap=true")
    appendLine("username=${percentEncode(username)}")
    appendLine("displayName=${percentEncode(displayName)}")
    appendLine("email=${percentEncode(email)}")
    appendLine("sentMailbox=${percentEncode(sentMailbox)}")
    appendLine("postponedMailbox=${percentEncode(postponedMailbox)}")
    appendLine("addressBookMailbox=${percentEncode(addressBookMailbox)}")
    appendLine("spamMailbox=${percentEncode(spamMailbox)}")
    appendLine("markSeenOnOpen=$markSeenOnOpen")
    appendLine("showDeleted=$showDeleted")
    appendLine("preferHtml=$preferHtml")
    appendLine("bodyView=${bodyView.name}")
    appendLine("density=${density.name}")
    appendLine("defaultView=${encodeView(defaultView)}")
    appendLine("folderViews=${encodeFolderViews(folderViews)}")
    appendLine("expandedFolders=${expandedFolders.joinToString(";") { percentEncode(it) }}")
    appendLine("swipeTrailing=${encodeSwipe(swipeTrailing)}")
    appendLine("swipeLeading=${encodeSwipe(swipeLeading)}")
    appendLine("bounceFcc=$bounceFcc")
    appendLine("friendlyName=${percentEncode(friendlyName)}")
    appendLine("theme=${theme.name}")
    appendLine("showUnreadCounts=$showUnreadCounts")
    appendLine("dateFormat=${dateFormat.name}")
    appendLine("datePattern=${percentEncode(datePattern)}")
    appendLine("favorites=${encodeFavorites(favorites)}")
    appendLine("includeForwardAttachments=$includeForwardAttachments")
    appendLine("forwardAsAttachment=$forwardAsAttachment")
    appendLine("replyAboveQuote=$replyAboveQuote")
    appendLine("askBeforeExpunge=$askBeforeExpunge")
    appendLine("pipelineCommands=$pipelineCommands")
    appendLine("logImapTraffic=$logImapTraffic")
    appendLine("showUserInDebugReport=$showUserInDebugReport")
    appendLine("readerBar=${encodeReaderBar(readerBar)}")
    appendLine("dynamicColor=$dynamicColor")
    appendLine("inboxStart=${inboxStart.name}")
    appendLine("folderStart=${folderStart.name}")
    appendLine("folderStarts=${encodeFolderStarts(folderStarts)}")
    appendLine("startAfterChange=${startAfterChange.name}")
    appendLine("showRecentRules=$showRecentRules")
    appendLine("openAtInIndexMenu=$openAtInIndexMenu")
    appendLine("pinercStartDefault=${pinercStartDefault.name}")
    appendLine("plainTextMonospace=$plainTextMonospace")
    if (readerTextZoom != 100) appendLine("readerTextZoom=$readerTextZoom")
    if (idleWakeSeconds != 5) appendLine("idleWakeSeconds=$idleWakeSeconds")
    // Always written (still optional on decode), so each account stores its look-alike choice explicitly.
    appendLine("nonAsciiDisplay=${nonAsciiDisplay.name}")
    appendLine("altAddresses=${encodeAltAddresses(altAddresses)}")
    // Defaults omitted: smtpUsername when empty, completionSources, addressBookHistory, addressBookNeverTrim, the slower-fallback fields, the delete fields, savedMailbox when empty, saveNameRule when DefaultFolder, lastSaveMailbox when empty, threadIndexStyle when Expanded, indexBar when it is the default, selectionBar when it is the default, folderBar when it is the default, readerToolbar while it is null, composeBar when it is the default, toolbarRows when it is 2, composerWrapColumn when it is 74, quellFlowed when false, multiPane when Off, folderPane when Off, foldPosture when Off, full screen when false, sieve host when empty, sieve port when 4190, tls mode when None, cert pin when blank, allowPlaintextAuth when false, inboundRules when empty, watchedFolders when empty, extraIdleBudget when 2, savedSearches when empty, savedAdvanced when empty, emailSubmission when false, ask thresholds when -1, and ask skip sets when empty.
    if (completionSources != listOf(pineSourceId)) {
        appendLine("completionSources=${encodeCompletionSources(completionSources)}")
    }
    if (extraAddressBooks.isNotEmpty()) appendLine("extraAddressBooks=${encodeCompletionSources(extraAddressBooks)}")
    if (globalAddressBooks.isNotEmpty()) appendLine("globalAddressBooks=${encodeCompletionSources(globalAddressBooks)}")
    if (addressBookHistory != 3) appendLine("addressBookHistory=$addressBookHistory")
    if (addressBookNeverTrim) appendLine("addressBookNeverTrim=true")
    if (clientSort) appendLine("clientSort=true")
    if (clientThread) appendLine("clientThread=true")
    if (pollForNewMail) appendLine("pollForNewMail=true")
    if (pollSeconds != 60) appendLine("pollSeconds=$pollSeconds")
    if (statusVisibleCounts) appendLine("statusVisibleCounts=true")
    if (forceSlowerFallbacks) appendLine("forceSlowerFallbacks=true")
    if (hiddenCapabilities.isNotEmpty()) {
        appendLine("hiddenCapabilities=${encodeHiddenCapabilities(hiddenCapabilities)}")
    }
    if (autoExpunge) appendLine("autoExpunge=true")
    if (deletePolicy != DeletePolicy.MarkDeleted) appendLine("deletePolicy=${deletePolicy.name}")
    if (moveMethod != MoveMethod.CopyThenMarkDeleted) appendLine("moveMethod=${moveMethod.name}")
    if (compression != Compression.On) appendLine("compression=${compression.name}")
    if (moveAllIncludesDeleted) appendLine("moveAllIncludesDeleted=true")
    if (trashMailbox.isNotEmpty()) appendLine("trashMailbox=${percentEncode(trashMailbox)}")
    if (savedMailbox.isNotEmpty()) appendLine("savedMailbox=${percentEncode(savedMailbox)}")
    if (saveNameRule != SaveNameRule.DefaultFolder) appendLine("saveNameRule=${saveNameRule.name}")
    if (lastSaveMailbox.isNotEmpty()) appendLine("lastSaveMailbox=${percentEncode(lastSaveMailbox)}")
    if (threadIndexStyle != ThreadIndexStyle.Expanded) {
        appendLine("threadIndexStyle=${threadIndexStyle.name}")
    }
    if (indexBar != defaultIndexBar()) {
        appendLine("indexBar=${encodeIndexBar(indexBar)}")
    }
    if (selectionBar != defaultSelectionBar()) {
        appendLine("selectionBar=${encodeSelectionBar(selectionBar)}")
    }
    if (folderBar != defaultFolderBar()) {
        appendLine("folderBar=${encodeFolderBar(folderBar)}")
    }
    if (readerToolbar != null) {
        appendLine("readerToolbar=${encodeReaderToolbar(readerToolbar)}")
    }
    if (composeBar != defaultComposeBar()) {
        appendLine("composeBar=${encodeComposeBar(composeBar)}")
    }
    if (toolbarRows != 2) appendLine("toolbarRows=$toolbarRows")
    if (composerWrapColumn != 74) appendLine("composerWrapColumn=$composerWrapColumn")
    if (quellFlowed) appendLine("quellFlowed=true")
    if (multiPane != MultiPane.Off) appendLine("multiPane=${multiPane.name}")
    if (folderPane != LayoutChoice.Off) appendLine("folderPane=${folderPane.name}")
    if (foldPosture != LayoutChoice.Off) appendLine("foldPosture=${foldPosture.name}")
    if (fullScreen) appendLine("fullScreen=true")
    if (paneArrangement != ArrangementChoice.SideBySide) appendLine("paneArrangement=${paneArrangement.name}")
    if (previewSize != PreviewChoice.Density) appendLine("previewSize=${previewSize.name}")
    if (folderPosition != FolderPositionChoice.Beside) appendLine("folderPosition=${folderPosition.name}")
    if (customLayout.isNotEmpty()) appendLine("customLayout=${percentEncode(customLayout)}")
    if (sendingRules.isNotEmpty()) appendLine("sendingRules=${percentEncode(sendingRules)}")
    if (signature.isNotEmpty()) appendLine("signature=${percentEncode(signature)}")
    if (!sigDashes) appendLine("sigDashes=${sigDashes}")
    if (sigAtBottom) appendLine("sigAtBottom=${sigAtBottom}")
    if (quotePrefix != org.dlang.liveimap.ui.compose.DefaultQuotePrefix) appendLine("quotePrefix=${percentEncode(quotePrefix)}")
    if (replyLeadIn != org.dlang.liveimap.ui.compose.DefaultLeadIn) appendLine("replyLeadIn=${percentEncode(replyLeadIn)}")
    if (dsnMode != org.dlang.liveimap.ui.compose.DsnMode.Off) appendLine("dsnMode=${dsnMode.name}")
    if (composerHeaders.isNotEmpty()) appendLine("composerHeaders=${percentEncode(composerHeaders)}")
    if (viewerHeaders.isNotEmpty()) appendLine("viewerHeaders=${percentEncode(viewerHeaders)}")
    if (sieveHost.isNotEmpty()) appendLine("sieveHost=${percentEncode(sieveHost)}")
    if (sievePort != 4190) appendLine("sievePort=$sievePort")
    if (tlsMode != TlsMode.None) appendLine("tlsMode=${tlsMode.name}")
    if (certPin.isNotBlank()) appendLine("certPin=${percentEncode(certPin)}")
    if (sieveCertPin.isNotBlank()) appendLine("sieveCertPin=${percentEncode(sieveCertPin)}")
    if (allowPlaintextAuth) appendLine("allowPlaintextAuth=true")
    if (acceptInvalidCert) appendLine("acceptInvalidCert=true")
    if (allowOldTls) appendLine("allowOldTls=true")
    if (authMechanism.isNotBlank()) appendLine("authMechanism=${percentEncode(authMechanism)}")
    if (clientCert.isNotBlank()) appendLine("clientCert=${percentEncode(clientCert)}")
    if (clientCertAlias.isNotBlank()) appendLine("clientCertAlias=${percentEncode(clientCertAlias)}")
    if (inboundRules.isNotEmpty()) appendLine("inboundRules=${encodeInboundRules(inboundRules)}")
    if (watchedFolders.isNotEmpty()) {
        appendLine("watchedFolders=${encodeWatchedFolders(watchedFolders)}")
    }
    if (extraIdleBudget != 2) appendLine("extraIdleBudget=$extraIdleBudget")
    if (savedSearches.isNotEmpty()) {
        appendLine("savedSearches=${encodeSavedSearches(savedSearches)}")
    }
    if (savedAdvanced.isNotEmpty()) {
        appendLine("savedAdvanced=${encodeSavedAdvanced(savedAdvanced)}")
    }
    if (emailSubmission) appendLine("emailSubmission=true")
    if (jmapFolders) appendLine("jmapFolders=true")
    if (askSort != DefaultAskLimit) appendLine("askSort=$askSort")
    if (askThread != DefaultAskLimit) appendLine("askThread=$askThread")
    if (askSearch != DefaultAskLimit) appendLine("askSearch=$askSearch")
    if (askFallback != DefaultAskLimit) appendLine("askFallback=$askFallback")
    if (sortAskSkip.isNotEmpty()) appendLine("sortAskSkip=${encodeWatchedFolders(sortAskSkip.sorted())}")
    if (threadAskSkip.isNotEmpty()) appendLine("threadAskSkip=${encodeWatchedFolders(threadAskSkip.sorted())}")
    if (searchAskSkip.isNotEmpty()) appendLine("searchAskSkip=${encodeWatchedFolders(searchAskSkip.sorted())}")
    if (fallbackAskSkip.isNotEmpty()) {
        appendLine("fallbackAskSkip=${encodeWatchedFolders(fallbackAskSkip.sorted())}")
    }
    for (line in unknownSettings) appendLine(line)
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
            key == "smtpSameAsImap" ||
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
            key == "plainTextMonospace" ||
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
            key == "tlsMode" ||
            key == "certPin" ||
            key == "sieveCertPin" ||
            key == "allowPlaintextAuth" ||
            key == "acceptInvalidCert" ||
            key == "allowOldTls" ||
            key == "authMechanism" ||
            key == "clientCert" ||
            key == "clientCertAlias" ||
            key == "inboundRules" ||
            key == "watchedFolders" ||
            key == "extraIdleBudget" ||
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
    // F69: each field falls back to its default when its stored value can't be read (version skew, hand edits)
    fun <T> field(default: T, read: () -> T): T = try {
        read()
    } catch (_: Exception) {
        default
    }
    return AccountSettings(
        imapHost = field(d.imapHost) { percentDecode(values.getValue("imapHost")) },
        imapPort = field(d.imapPort) { parseIntField(values.getValue("imapPort")) },
        smtpHost = field(d.smtpHost) { percentDecode(values.getValue("smtpHost")) },
        smtpPort = field(d.smtpPort) { parseIntField(values.getValue("smtpPort")) },
        smtpUsername = field(d.smtpUsername) { values["smtpUsername"]?.let { percentDecode(it) } ?: "" },
        smtpSameAsImap = field(d.smtpSameAsImap) { values["smtpSameAsImap"] == "true" },
        username = field(d.username) { percentDecode(values.getValue("username")) },
        displayName = field(d.displayName) { percentDecode(values.getValue("displayName")) },
        email = field(d.email) { percentDecode(values.getValue("email")) },
        sentMailbox = field(d.sentMailbox) { percentDecode(values.getValue("sentMailbox")) },
        postponedMailbox = field(d.postponedMailbox) { percentDecode(values.getValue("postponedMailbox")) },
        addressBookMailbox = field(d.addressBookMailbox) { percentDecode(values.getValue("addressBookMailbox")) },
        spamMailbox = field(d.spamMailbox) { values["spamMailbox"]?.let { percentDecode(it) } ?: "" },
        markSeenOnOpen = field(d.markSeenOnOpen) { parseBoolean(values.getValue("markSeenOnOpen")) },
        showDeleted = field(d.showDeleted) { parseBoolean(values.getValue("showDeleted")) },
        bodyView = field(d.bodyView) { decodeBodyView(values) },
        density = field(d.density) { enumValueOf(values.getValue("density")) },
        defaultView = field(d.defaultView) { parseView(values.getValue("defaultView")) },
        folderViews = field(d.folderViews) { parseFolderViews(values.getValue("folderViews")) },
        expandedFolders = field(d.expandedFolders) { parseExpanded(values.getValue("expandedFolders")) },
        swipeTrailing = field(d.swipeTrailing) { parseSwipe(values.getValue("swipeTrailing")) },
        swipeLeading = field(d.swipeLeading) { parseSwipe(values.getValue("swipeLeading")) },
        bounceFcc = field(d.bounceFcc) { parseBoolean(values.getValue("bounceFcc")) },
        friendlyName = field(d.friendlyName) { values["friendlyName"]?.let { percentDecode(it) } ?: "" },
        theme = field(d.theme) { values["theme"]?.let { enumValueOf<ThemeMode>(it) } ?: ThemeMode.FollowSystem },
        showUnreadCounts = field(d.showUnreadCounts) { values["showUnreadCounts"]?.let { parseBoolean(it) } ?: false },
        dateFormat = field(d.dateFormat) { values["dateFormat"]?.let { enumValueOf<DateFormat>(it) } ?: DateFormat.Short },
        datePattern = field(d.datePattern) { values["datePattern"]?.let { percentDecode(it) } ?: "" },
        favorites = field(d.favorites) { values["favorites"]?.let { parseFavorites(it) } ?: emptyList() },
        includeForwardAttachments = field(d.includeForwardAttachments) { values["includeForwardAttachments"]?.let { parseBoolean(it) } ?: true },
        forwardAsAttachment = field(d.forwardAsAttachment) { values["forwardAsAttachment"]?.let { parseBoolean(it) } ?: false },
        replyAboveQuote = field(d.replyAboveQuote) { values["replyAboveQuote"]?.let { parseBoolean(it) } ?: false },
        askBeforeExpunge = field(d.askBeforeExpunge) { values["askBeforeExpunge"]?.let { parseBoolean(it) } ?: true },
        pipelineCommands = field(d.pipelineCommands) { values["pipelineCommands"]?.let { parseBoolean(it) } ?: true },
        logImapTraffic = field(d.logImapTraffic) { values["logImapTraffic"]?.let { parseBoolean(it) } ?: false },
        showUserInDebugReport = field(d.showUserInDebugReport) { values["showUserInDebugReport"]?.let { parseBoolean(it) } ?: false },
        readerBar = field(d.readerBar) { values["readerBar"]?.let { parseReaderBar(it) } ?: defaultReaderBar },
        dynamicColor = field(d.dynamicColor) { values["dynamicColor"]?.let { parseBoolean(it) } ?: true },
        inboxStart = field(d.inboxStart) { values["inboxStart"]?.let { enumValueOf<StartRule>(it) } ?: StartRule.Newest },
        folderStart = field(d.folderStart) { values["folderStart"]?.let { enumValueOf<StartRule>(it) } ?: StartRule.Newest },
        folderStarts = field(d.folderStarts) { values["folderStarts"]?.let { parseFolderStarts(it) } ?: emptyMap() },
        startAfterChange = field(d.startAfterChange) { values["startAfterChange"]?.let { enumValueOf<StartAfterChange>(it) }
            ?: StartAfterChange.RerunRule },
        showRecentRules = field(d.showRecentRules) { values["showRecentRules"]?.let { parseBoolean(it) } ?: true },
        openAtInIndexMenu = field(d.openAtInIndexMenu) { values["openAtInIndexMenu"]?.let { parseBoolean(it) } ?: false },
        pinercStartDefault = field(d.pinercStartDefault) { values["pinercStartDefault"]?.let { enumValueOf<PinercStartDefault>(it) }
            ?: PinercStartDefault.LeaveUnchanged },
        plainTextMonospace = field(d.plainTextMonospace) { values["plainTextMonospace"]?.let { parseBoolean(it) } ?: false },
        readerTextZoom = field(d.readerTextZoom) { values["readerTextZoom"]?.toIntOrNull()?.let { clampTextZoom(it) } ?: 100 },
        idleWakeSeconds = field(d.idleWakeSeconds) { values["idleWakeSeconds"]?.toIntOrNull()?.takeIf { it in IdleWakeSeconds } ?: 5 },
        altAddresses = field(d.altAddresses) { values["altAddresses"]?.let { decodeAltAddresses(it) } ?: emptyList() },
        completionSources = field(d.completionSources) { decodeCompletionSources(values["completionSources"]) },
        extraAddressBooks = field(d.extraAddressBooks) { values["extraAddressBooks"]?.let { decodeCompletionSources(it) } ?: emptyList() },
        globalAddressBooks = field(d.globalAddressBooks) { values["globalAddressBooks"]?.let { decodeCompletionSources(it) } ?: emptyList() },
        addressBookHistory = field(d.addressBookHistory) { values["addressBookHistory"]?.let { parseIntField(it) } ?: 3 },
        addressBookNeverTrim = field(d.addressBookNeverTrim) { values["addressBookNeverTrim"]?.let { parseBoolean(it) } ?: false },
        clientSort = field(d.clientSort) { values["clientSort"]?.let { parseBoolean(it) } ?: false },
        clientThread = field(d.clientThread) { values["clientThread"]?.let { parseBoolean(it) } ?: false },
        pollForNewMail = field(d.pollForNewMail) { values["pollForNewMail"]?.let { parseBoolean(it) } ?: false },
        pollSeconds = field(d.pollSeconds) { values["pollSeconds"]?.let { clampPollSeconds(parseIntField(it)) } ?: 60 },
        statusVisibleCounts = field(d.statusVisibleCounts) { values["statusVisibleCounts"]?.let { parseBoolean(it) } ?: false },
        forceSlowerFallbacks = field(d.forceSlowerFallbacks) { values["forceSlowerFallbacks"]?.let { parseBoolean(it) } ?: false },
        hiddenCapabilities = field(d.hiddenCapabilities) { values["hiddenCapabilities"]?.let { decodeHiddenCapabilities(it) } ?: emptySet() },
        autoExpunge = field(d.autoExpunge) { values["autoExpunge"]?.let { parseBoolean(it) } ?: false },
        deletePolicy = field(d.deletePolicy) { values["deletePolicy"]?.let { enumValueOf<DeletePolicy>(it) } ?: DeletePolicy.MarkDeleted },
        moveMethod = field(d.moveMethod) { values["moveMethod"]?.let { enumValueOf<MoveMethod>(it) } ?: MoveMethod.CopyThenMarkDeleted },
        compression = field(d.compression) { values["compression"]?.let { v -> Compression.entries.firstOrNull { it.name == v } }
            ?: Compression.On },
        moveAllIncludesDeleted = field(d.moveAllIncludesDeleted) { values["moveAllIncludesDeleted"]?.let { parseBoolean(it) } ?: false },
        trashMailbox = field(d.trashMailbox) { values["trashMailbox"]?.let { percentDecode(it) } ?: "" },
        savedMailbox = field(d.savedMailbox) { values["savedMailbox"]?.let { percentDecode(it) } ?: "" },
        saveNameRule = field(d.saveNameRule) { values["saveNameRule"]?.let { enumValueOf<SaveNameRule>(it) } ?: SaveNameRule.DefaultFolder },
        lastSaveMailbox = field(d.lastSaveMailbox) { values["lastSaveMailbox"]?.let { percentDecode(it) } ?: "" },
        threadIndexStyle = field(d.threadIndexStyle) { values["threadIndexStyle"]?.let { enumValueOf<ThreadIndexStyle>(it) }
            ?: ThreadIndexStyle.Expanded },
        indexBar = field(d.indexBar) { values["indexBar"]?.let { parseIndexBar(it) } ?: defaultIndexBar() },
        selectionBar = field(d.selectionBar) { values["selectionBar"]?.let { parseSelectionBar(it) } ?: defaultSelectionBar() },
        folderBar = field(d.folderBar) { values["folderBar"]?.let { parseFolderBar(it) } ?: defaultFolderBar() },
        readerToolbar = field(d.readerToolbar) { values["readerToolbar"]?.let { parseReaderToolbar(it) } },
        composeBar = field(d.composeBar) { values["composeBar"]?.let { parseComposeBar(it) } ?: defaultComposeBar() },
        toolbarRows = field(d.toolbarRows) { (values["toolbarRows"]?.let { parseIntField(it) } ?: 2).takeIf { it in 1..4 } ?: d.toolbarRows },
        composerWrapColumn = field(d.composerWrapColumn) { values["composerWrapColumn"]?.let { parseIntField(it).coerceIn(0, 998) } ?: 74 },
        quellFlowed = field(d.quellFlowed) { values["quellFlowed"]?.let { parseBoolean(it) } ?: false },
        multiPane = field(d.multiPane) { values["multiPane"]?.let { enumValueOf<MultiPane>(it) } ?: MultiPane.Off },
        folderPane = field(d.folderPane) { values["folderPane"]?.let { enumValueOf<LayoutChoice>(it) } ?: LayoutChoice.Off },
        foldPosture = field(d.foldPosture) { values["foldPosture"]?.let { enumValueOf<LayoutChoice>(it) } ?: LayoutChoice.Off },
        fullScreen = field(d.fullScreen) { values["fullScreen"]?.let { parseBoolean(it) } ?: false },
        paneArrangement = field(d.paneArrangement) { values["paneArrangement"]?.let { runCatching { enumValueOf<ArrangementChoice>(it) }.getOrNull() } ?: ArrangementChoice.SideBySide },
        folderPosition = field(d.folderPosition) { values["folderPosition"]?.let { runCatching { enumValueOf<FolderPositionChoice>(it) }.getOrNull() } ?: FolderPositionChoice.Beside },
        customLayout = field(d.customLayout) { values["customLayout"]?.let { percentDecode(it) }?.takeIf { decodePaneLayout(it) != null }.orEmpty() },
        sendingRules = field(d.sendingRules) { values["sendingRules"]?.let { percentDecode(it) } ?: "" },
        signature = field(d.signature) { values["signature"]?.let { percentDecode(it) } ?: "" },
        sigDashes = field(d.sigDashes) { values["sigDashes"]?.let { it == "true" } ?: true },
        sigAtBottom = field(d.sigAtBottom) { values["sigAtBottom"]?.let { it == "true" } ?: false },
        quotePrefix = field(d.quotePrefix) { values["quotePrefix"]?.let { percentDecode(it) } ?: org.dlang.liveimap.ui.compose.DefaultQuotePrefix },
        replyLeadIn = field(d.replyLeadIn) { values["replyLeadIn"]?.let { percentDecode(it) } ?: org.dlang.liveimap.ui.compose.DefaultLeadIn },
        dsnMode = field(d.dsnMode) { values["dsnMode"]?.let { runCatching { enumValueOf<org.dlang.liveimap.ui.compose.DsnMode>(it) }.getOrNull() } ?: org.dlang.liveimap.ui.compose.DsnMode.Off },
        composerHeaders = field(d.composerHeaders) { values["composerHeaders"]?.let { percentDecode(it) } ?: "" },
        viewerHeaders = field(d.viewerHeaders) { values["viewerHeaders"]?.let { percentDecode(it) } ?: "" },
        nonAsciiDisplay = field(d.nonAsciiDisplay) { values["nonAsciiDisplay"]?.let { runCatching { enumValueOf<NonAsciiDisplay>(it) }.getOrNull() } ?: NonAsciiDisplay.HighlightSuspicious },
        previewSize = field(d.previewSize) { values["previewSize"]?.let { runCatching { enumValueOf<PreviewChoice>(it) }.getOrNull() } ?: PreviewChoice.Density },
        sieveHost = field(d.sieveHost) { values["sieveHost"]?.let { percentDecode(it) } ?: "" },
        sievePort = field(d.sievePort) { values["sievePort"]?.let { parseSievePort(it) } ?: 4190 },
        tlsMode = field(d.tlsMode) { values["tlsMode"]?.let { enumValueOf<TlsMode>(it) } ?: TlsMode.None },
        certPin = field(d.certPin) { values["certPin"]?.let { percentDecode(it) } ?: "" },
        sieveCertPin = field(d.sieveCertPin) { values["sieveCertPin"]?.let { percentDecode(it) } ?: "" },
        allowPlaintextAuth = field(d.allowPlaintextAuth) { values["allowPlaintextAuth"]?.let { parseBoolean(it) } ?: false },
        acceptInvalidCert = field(d.acceptInvalidCert) { values["acceptInvalidCert"]?.let { parseBoolean(it) } ?: false },
        allowOldTls = field(d.allowOldTls) { values["allowOldTls"]?.let { parseBoolean(it) } ?: false },
        authMechanism = field(d.authMechanism) { values["authMechanism"]?.let { percentDecode(it).trim().uppercase() } ?: "" },
        clientCert = field(d.clientCert) { values["clientCert"]?.let { percentDecode(it) } ?: "" },
        clientCertAlias = field(d.clientCertAlias) { values["clientCertAlias"]?.let { percentDecode(it) } ?: "" },
        inboundRules = field(d.inboundRules) { values["inboundRules"]?.let { decodeInboundRules(it) } ?: emptyList() },
        watchedFolders = field(d.watchedFolders) { values["watchedFolders"]?.let { decodeWatchedFolders(it) } ?: emptyList() },
        extraIdleBudget = field(d.extraIdleBudget) { values["extraIdleBudget"]?.let { parseIntField(it) } ?: 2 },
        savedSearches = field(d.savedSearches) { values["savedSearches"]?.let { decodeSavedSearches(it) } ?: emptyList() },
        savedAdvanced = field(d.savedAdvanced) { values["savedAdvanced"]?.let { decodeSavedAdvanced(it) } ?: emptyList() },
        emailSubmission = field(d.emailSubmission) { values["emailSubmission"]?.let { parseBoolean(it) } ?: false },
        jmapFolders = field(d.jmapFolders) { values["jmapFolders"]?.let { parseBoolean(it) } ?: false },
        askSort = field(d.askSort) { values["askSort"]?.let { parseAskLimit(it) } ?: DefaultAskLimit },
        askThread = field(d.askThread) { values["askThread"]?.let { parseAskLimit(it) } ?: DefaultAskLimit },
        askSearch = field(d.askSearch) { values["askSearch"]?.let { parseAskLimit(it) } ?: DefaultAskLimit },
        askFallback = field(d.askFallback) { values["askFallback"]?.let { parseAskLimit(it) } ?: DefaultAskLimit },
        sortAskSkip = field(d.sortAskSkip) { values["sortAskSkip"]?.let { decodeWatchedFolders(it).toSet() } ?: emptySet() },
        threadAskSkip = field(d.threadAskSkip) { values["threadAskSkip"]?.let { decodeWatchedFolders(it).toSet() } ?: emptySet() },
        searchAskSkip = field(d.searchAskSkip) { values["searchAskSkip"]?.let { decodeWatchedFolders(it).toSet() } ?: emptySet() },
        fallbackAskSkip = field(d.fallbackAskSkip) { values["fallbackAskSkip"]?.let { decodeWatchedFolders(it).toSet() } ?: emptySet() },
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

fun pollIntervalSeconds(settings: AccountSettings): Int = clampPollSeconds(settings.pollSeconds)

private fun clampPollSeconds(value: Int): Int = value.coerceIn(15, 600)

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
    val actions = ArrayList<String>(5)
    if (rule.fileInto.isNotEmpty()) actions.add("file=${percentEncode(rule.fileInto)}")
    if (SystemFlag.Seen in rule.flags) actions.add("seen")
    if (SystemFlag.Flagged in rule.flags) actions.add("flagged")
    if (rule.redirectTo.isNotEmpty()) actions.add("redirect=${percentEncode(rule.redirectTo)}")
    if (rule.discard) actions.add("discard")
    return "$criteria\t${actions.joinToString(",")}"
}

private fun decodeInboundRules(value: String): List<InboundRule> {
    if (value.isEmpty()) return emptyList()
    return value.split('\n').map { decodeInboundRule(it) }
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
    var sawFile = false
    var sawRedirect = false
    var sawDiscard = false
    for (token in actionPart.split(',')) {
        when {
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

private fun encodeFavorite(favorite: FolderFavorite): String =
    "${if (favorite.node) "1" else "0"}|${percentEncode(favorite.mailbox)}|${percentEncode(favorite.delimiter.toString())}|${percentEncode(favorite.label)}"

private fun encodeFavorites(favorites: List<FolderFavorite>): String =
    favorites.joinToString(";") { encodeFavorite(it) }

private fun parseFavorites(value: String): List<FolderFavorite> {
    if (value.isEmpty()) return emptyList()
    val out = mutableListOf<FolderFavorite>()
    val seen = mutableSetOf<Pair<Boolean, String>>()
    for (part in value.split(';')) {
        val bits = part.split('|')
        if (bits.size != 3 && bits.size != 4) throw IllegalArgumentException("bad favorites")
        val node = when (bits[0]) {
            "1" -> true
            "0" -> false
            else -> throw IllegalArgumentException("bad favorites")
        }
        val mailbox = percentDecode(bits[1])
        val delimText = percentDecode(bits[2])
        if (delimText.length != 1) throw IllegalArgumentException("bad favorites")
        val label = if (bits.size == 4) percentDecode(bits[3]) else ""
        if (!seen.add(node to mailbox)) throw IllegalArgumentException("duplicate favorite")
        out += FolderFavorite(node, mailbox, delimText[0], label)
    }
    return out
}

private fun encodeReaderBar(actions: List<ReaderAction>): String =
    actions.joinToString(";") { it.name }

private fun parseReaderBar(value: String): List<ReaderAction> {
    if (value.isEmpty()) return emptyList()
    val out = mutableListOf<ReaderAction>()
    val seen = mutableSetOf<ReaderAction>()
    for (part in value.split(';')) {
        val action = try {
            enumValueOf<ReaderAction>(part)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("bad readerBar")
        }
        if (!seen.add(action)) throw IllegalArgumentException("bad readerBar")
        out += action
    }
    return out
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
        val bits = part.split('|')
        if (bits.size != 2) throw IllegalArgumentException("bad folderStarts")
        val mailbox = percentDecode(bits[0])
        if (out.containsKey(mailbox)) throw IllegalArgumentException("duplicate folder start")
        val rule = try {
            enumValueOf<StartRule>(bits[1])
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("bad folderStarts")
        }
        out[mailbox] = rule
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
        val bits = part.split('|')
        if (bits.size != 3) throw IllegalArgumentException("bad folderViews")
        val mailbox = percentDecode(bits[0])
        if (out.containsKey(mailbox)) throw IllegalArgumentException("duplicate folder view")
        out[mailbox] = FolderView(enumValueOf(bits[1]), parseNewest(bits[2]))
    }
    return out
}

private fun parseExpanded(value: String): Set<String> {
    if (value.isEmpty()) return emptySet()
    val out = linkedSetOf<String>()
    for (part in value.split(';')) {
        val mailbox = percentDecode(part)
        if (!out.add(mailbox)) throw IllegalArgumentException("duplicate expanded folder")
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
    settings.allowPlaintextAuth -> org.dlang.liveimap.R.string.settings_no_tls_password_warning
    else -> org.dlang.liveimap.R.string.settings_no_tls_warning
}

