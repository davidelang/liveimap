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

enum class ThemeMode {
    Dark,
    Light,
    FollowSystem,
}

data class SwipeBinding(
    val action: SwipeAction,
    val moveMailbox: String = "",
    val flag: String = "",
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
    val markSeenOnOpen: Boolean = true,
    val showDeleted: Boolean = true,
    val preferHtml: Boolean = false,
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
)

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
    "markSeenOnOpen",
    "showDeleted",
    "preferHtml",
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
    appendLine("markSeenOnOpen=$markSeenOnOpen")
    appendLine("showDeleted=$showDeleted")
    appendLine("preferHtml=$preferHtml")
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
        if (key == "friendlyName" || key == "theme" || key == "showUnreadCounts") continue
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
        markSeenOnOpen = parseBoolean(values.getValue("markSeenOnOpen")),
        showDeleted = parseBoolean(values.getValue("showDeleted")),
        preferHtml = parseBoolean(values.getValue("preferHtml")),
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
