package org.dlang.liveimap.settings

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

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
    BodyView.PlainOrHtml -> "Plain or HTML"
    BodyView.PlainOrText -> "Plain or text"
    BodyView.PlainOrError -> "Plain or error"
    BodyView.Headers -> "Headers"
    BodyView.Raw -> "Raw"
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
    val showUnreadCounts: Boolean = false,
    val dateFormat: DateFormat = DateFormat.Short,
    val datePattern: String = "",
    val favorites: List<FolderFavorite> = emptyList(),
    val includeForwardAttachments: Boolean = true,
    val askBeforeExpunge: Boolean = true,
    val pipelineCommands: Boolean = true,
    val logImapTraffic: Boolean = false,
    val readerBar: List<ReaderAction> = defaultReaderBar,
) {
    val preferHtml: Boolean
        get() = bodyView == BodyView.PlainOrHtml
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
    "askBeforeExpunge",
    "pipelineCommands",
    "logImapTraffic",
    "readerBar",
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
    appendLine("askBeforeExpunge=$askBeforeExpunge")
    appendLine("pipelineCommands=$pipelineCommands")
    appendLine("logImapTraffic=$logImapTraffic")
    appendLine("readerBar=${encodeReaderBar(readerBar)}")
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
            key == "askBeforeExpunge" ||
            key == "pipelineCommands" ||
            key == "logImapTraffic" ||
            key == "readerBar"
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
        askBeforeExpunge = values["askBeforeExpunge"]?.let { parseBoolean(it) } ?: true,
        pipelineCommands = values["pipelineCommands"]?.let { parseBoolean(it) } ?: true,
        logImapTraffic = values["logImapTraffic"]?.let { parseBoolean(it) } ?: false,
        readerBar = values["readerBar"]?.let { parseReaderBar(it) } ?: defaultReaderBar,
    )
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
