package org.dlang.liveimap.settings

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
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
}

fun readerActionLabel(action: ReaderAction): String = when (action) {
    ReaderAction.Reply -> "Reply"
    ReaderAction.ReplyAll -> "Reply all"
    ReaderAction.Forward -> "Forward"
    ReaderAction.Delete -> "Delete"
    ReaderAction.Move -> "Move"
    ReaderAction.Spam -> "Spam"
    ReaderAction.Bounce -> "Bounce"
}

val defaultReaderBar: List<ReaderAction> = listOf(
    ReaderAction.Reply,
    ReaderAction.ReplyAll,
    ReaderAction.Forward,
    ReaderAction.Delete,
    ReaderAction.Move,
)

const val pineSourceId: String = "pine"

enum class MultiPane {
    Off,
    Wide,
}

enum class DeletePolicy {
    MarkDeleted,
    MoveToTrash,
    DeletePermanently,
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

data class AccountSettings(
    val imapHost: String = "",
    val imapPort: Int = 143,
    val smtpHost: String = "",
    val smtpPort: Int = 25,
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
    val altAddresses: List<String> = emptyList(),
    val completionSources: List<String> = listOf(pineSourceId),
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
    val multiPane: MultiPane = MultiPane.Wide,
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
    "altAddresses",
    "completionSources",
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
    "multiPane",
)

private const val HEX = "0123456789ABCDEF"

fun AccountSettings.encode(): String = buildString {
    appendLine("imapHost=${percentEncode(imapHost)}")
    appendLine("imapPort=$imapPort")
    appendLine("smtpHost=${percentEncode(smtpHost)}")
    appendLine("smtpPort=$smtpPort")
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
    appendLine("altAddresses=${encodeAltAddresses(altAddresses)}")
    // Defaults omitted: completionSources, addressBookHistory, addressBookNeverTrim, the slower-fallback fields, the delete fields, savedMailbox when empty, saveNameRule when DefaultFolder, lastSaveMailbox when empty, threadIndexStyle when Expanded, indexBar when it is the default, selectionBar when it is the default, folderBar when it is the default, readerToolbar while it is null, composeBar when it is the default, toolbarRows when it is 2, composerWrapColumn when it is 74, and multiPane when Wide.
    if (completionSources != listOf(pineSourceId)) {
        appendLine("completionSources=${encodeCompletionSources(completionSources)}")
    }
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
    if (multiPane != MultiPane.Wide) appendLine("multiPane=${multiPane.name}")
}

fun decodeAccountSettings(text: String): AccountSettings {
    val values = linkedMapOf<String, String>()
    for (line in text.lines()) {
        if (line.isEmpty()) continue
        val eq = line.indexOf('=')
        if (eq <= 0) throw IllegalArgumentException("bad line")
        val key = line.substring(0, eq)
        if (key !in fieldNames) throw IllegalArgumentException("unknown key")
        if (values.containsKey(key)) throw IllegalArgumentException("duplicate key")
        values[key] = line.substring(eq + 1)
    }
    for (key in fieldNames) {
        if (
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
            key == "altAddresses" ||
            key == "completionSources" ||
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
            key == "multiPane"
        ) {
            continue
        }
        if (key !in values) throw IllegalArgumentException("missing key")
    }
    return AccountSettings(
        imapHost = percentDecode(values.getValue("imapHost")),
        imapPort = parseIntField(values.getValue("imapPort")),
        smtpHost = percentDecode(values.getValue("smtpHost")),
        smtpPort = parseIntField(values.getValue("smtpPort")),
        username = percentDecode(values.getValue("username")),
        displayName = percentDecode(values.getValue("displayName")),
        email = percentDecode(values.getValue("email")),
        sentMailbox = percentDecode(values.getValue("sentMailbox")),
        postponedMailbox = percentDecode(values.getValue("postponedMailbox")),
        addressBookMailbox = percentDecode(values.getValue("addressBookMailbox")),
        spamMailbox = values["spamMailbox"]?.let { percentDecode(it) } ?: "",
        markSeenOnOpen = parseBoolean(values.getValue("markSeenOnOpen")),
        showDeleted = parseBoolean(values.getValue("showDeleted")),
        bodyView = decodeBodyView(values),
        density = enumValueOf(values.getValue("density")),
        defaultView = parseView(values.getValue("defaultView")),
        folderViews = parseFolderViews(values.getValue("folderViews")),
        expandedFolders = parseExpanded(values.getValue("expandedFolders")),
        swipeTrailing = parseSwipe(values.getValue("swipeTrailing")),
        swipeLeading = parseSwipe(values.getValue("swipeLeading")),
        bounceFcc = parseBoolean(values.getValue("bounceFcc")),
        friendlyName = values["friendlyName"]?.let { percentDecode(it) } ?: "",
        theme = values["theme"]?.let { enumValueOf<ThemeMode>(it) } ?: ThemeMode.FollowSystem,
        showUnreadCounts = values["showUnreadCounts"]?.let { parseBoolean(it) } ?: false,
        dateFormat = values["dateFormat"]?.let { enumValueOf<DateFormat>(it) } ?: DateFormat.Short,
        datePattern = values["datePattern"]?.let { percentDecode(it) } ?: "",
        favorites = values["favorites"]?.let { parseFavorites(it) } ?: emptyList(),
        includeForwardAttachments = values["includeForwardAttachments"]?.let { parseBoolean(it) } ?: true,
        forwardAsAttachment = values["forwardAsAttachment"]?.let { parseBoolean(it) } ?: false,
        replyAboveQuote = values["replyAboveQuote"]?.let { parseBoolean(it) } ?: false,
        askBeforeExpunge = values["askBeforeExpunge"]?.let { parseBoolean(it) } ?: true,
        pipelineCommands = values["pipelineCommands"]?.let { parseBoolean(it) } ?: true,
        logImapTraffic = values["logImapTraffic"]?.let { parseBoolean(it) } ?: false,
        showUserInDebugReport = values["showUserInDebugReport"]?.let { parseBoolean(it) } ?: false,
        readerBar = values["readerBar"]?.let { parseReaderBar(it) } ?: defaultReaderBar,
        dynamicColor = values["dynamicColor"]?.let { parseBoolean(it) } ?: true,
        inboxStart = values["inboxStart"]?.let { enumValueOf<StartRule>(it) } ?: StartRule.Newest,
        folderStart = values["folderStart"]?.let { enumValueOf<StartRule>(it) } ?: StartRule.Newest,
        folderStarts = values["folderStarts"]?.let { parseFolderStarts(it) } ?: emptyMap(),
        startAfterChange = values["startAfterChange"]?.let { enumValueOf<StartAfterChange>(it) }
            ?: StartAfterChange.RerunRule,
        showRecentRules = values["showRecentRules"]?.let { parseBoolean(it) } ?: true,
        openAtInIndexMenu = values["openAtInIndexMenu"]?.let { parseBoolean(it) } ?: false,
        pinercStartDefault = values["pinercStartDefault"]?.let { enumValueOf<PinercStartDefault>(it) }
            ?: PinercStartDefault.LeaveUnchanged,
        plainTextMonospace = values["plainTextMonospace"]?.let { parseBoolean(it) } ?: false,
        altAddresses = values["altAddresses"]?.let { decodeAltAddresses(it) } ?: emptyList(),
        completionSources = decodeCompletionSources(values["completionSources"]),
        addressBookHistory = values["addressBookHistory"]?.let { parseIntField(it) } ?: 3,
        addressBookNeverTrim = values["addressBookNeverTrim"]?.let { parseBoolean(it) } ?: false,
        clientSort = values["clientSort"]?.let { parseBoolean(it) } ?: false,
        clientThread = values["clientThread"]?.let { parseBoolean(it) } ?: false,
        pollForNewMail = values["pollForNewMail"]?.let { parseBoolean(it) } ?: false,
        pollSeconds = values["pollSeconds"]?.let { clampPollSeconds(parseIntField(it)) } ?: 60,
        statusVisibleCounts = values["statusVisibleCounts"]?.let { parseBoolean(it) } ?: false,
        forceSlowerFallbacks = values["forceSlowerFallbacks"]?.let { parseBoolean(it) } ?: false,
        hiddenCapabilities = values["hiddenCapabilities"]?.let { decodeHiddenCapabilities(it) } ?: emptySet(),
        autoExpunge = values["autoExpunge"]?.let { parseBoolean(it) } ?: false,
        deletePolicy = values["deletePolicy"]?.let { enumValueOf<DeletePolicy>(it) } ?: DeletePolicy.MarkDeleted,
        moveMethod = values["moveMethod"]?.let { enumValueOf<MoveMethod>(it) } ?: MoveMethod.CopyThenMarkDeleted,
        trashMailbox = values["trashMailbox"]?.let { percentDecode(it) } ?: "",
        savedMailbox = values["savedMailbox"]?.let { percentDecode(it) } ?: "",
        saveNameRule = values["saveNameRule"]?.let { enumValueOf<SaveNameRule>(it) } ?: SaveNameRule.DefaultFolder,
        lastSaveMailbox = values["lastSaveMailbox"]?.let { percentDecode(it) } ?: "",
        threadIndexStyle = values["threadIndexStyle"]?.let { enumValueOf<ThreadIndexStyle>(it) }
            ?: ThreadIndexStyle.Expanded,
        indexBar = values["indexBar"]?.let { parseIndexBar(it) } ?: defaultIndexBar(),
        selectionBar = values["selectionBar"]?.let { parseSelectionBar(it) } ?: defaultSelectionBar(),
        folderBar = values["folderBar"]?.let { parseFolderBar(it) } ?: defaultFolderBar(),
        readerToolbar = values["readerToolbar"]?.let { parseReaderToolbar(it) },
        composeBar = values["composeBar"]?.let { parseComposeBar(it) } ?: defaultComposeBar(),
        toolbarRows = values["toolbarRows"]?.let { parseIntField(it) } ?: 2,
        composerWrapColumn = values["composerWrapColumn"]?.let { parseIntField(it).coerceIn(0, 998) } ?: 74,
        multiPane = values["multiPane"]?.let { enumValueOf<MultiPane>(it) } ?: MultiPane.Wide,
    )
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

private fun encodeAltAddresses(values: List<String>): String =
    values.joinToString(",") { percentEncode(it) }

private fun decodeAltAddresses(value: String): List<String> {
    if (value.isEmpty()) return emptyList()
    return value.split(',').map { percentDecode(it) }.filter { it.isNotEmpty() }
}

private fun encodeCompletionSources(sources: List<String>): String =
    sources.joinToString(",") { percentEncode(it) }

private fun decodeCompletionSources(value: String?): List<String> {
    if (value == null) return listOf(pineSourceId)
    if (value.isEmpty()) return emptyList()
    return value.split(',').map { percentDecode(it) }.filter { it.isNotEmpty() }
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
            val byte = hex.toIntOrNull(16) ?: throw IllegalArgumentException("bad encoding")
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
