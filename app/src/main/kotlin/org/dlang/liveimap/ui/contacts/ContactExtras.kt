package org.dlang.liveimap.ui.contacts

/**
 * The structured extras block (docs/contacts-plan.md): fields the destination cannot hold travel in its free-text
 * field (Alpine comment, Android note) as one fenced block, so a reverse copy restores them.
 *
 *   [liveimap-extra v1]
 *   phone.Mobile=+1 555 0100
 *   fcc=sent-friends
 *   [/liveimap-extra]
 *
 * Keys and values are percent-encoded (%, tab, CR, LF, space, =, [, ]). In Alpine's one-line comment the lines are
 * separated by single spaces. Unknown keys are kept; a block of another version is kept verbatim.
 */
const val ExtraOpen = "[liveimap-extra v1]"
const val ExtraClose = "[/liveimap-extra]"
private const val ExtraOpenPrefix = "[liveimap-extra "

data class ExtraParse(
    /** The user's own text, with the block(s) removed. */
    val text: String,
    val entries: List<Pair<String, String>>,
    /** Blocks of an unknown version, verbatim. */
    val foreign: List<String>,
)

private const val ExtraEscaped = "%\t\r\n =[]"

fun extraEscape(value: String): String = buildString {
    for (ch in value) {
        if (ch in ExtraEscaped) append('%').append("%02X".format(ch.code)) else append(ch)
    }
}

fun extraUnescape(value: String): String {
    val out = StringBuilder()
    var i = 0
    while (i < value.length) {
        val ch = value[i]
        if (ch == '%' && i + 2 < value.length) {
            val code = value.substring(i + 1, i + 3).toIntOrNull(16)
            if (code != null) {
                out.append(code.toChar())
                i += 3
                continue
            }
        }
        out.append(ch)
        i += 1
    }
    return out.toString()
}

fun encodeExtraBlock(entries: List<Pair<String, String>>, oneLine: Boolean): String {
    if (entries.isEmpty()) return ""
    val sep = if (oneLine) " " else "\n"
    return buildString {
        append(ExtraOpen)
        for ((k, v) in entries) append(sep).append(extraEscape(k)).append('=').append(extraEscape(v))
        append(sep).append(ExtraClose)
    }
}

fun parseExtraBlock(text: String): ExtraParse {
    val entries = ArrayList<Pair<String, String>>()
    val foreign = ArrayList<String>()
    val user = StringBuilder()
    var rest = text
    while (true) {
        val start = rest.indexOf(ExtraOpenPrefix)
        if (start < 0) break
        val end = rest.indexOf(ExtraClose, start)
        if (end < 0) break
        user.append(rest, 0, start)
        val block = rest.substring(start, end + ExtraClose.length)
        val headerEnd = block.indexOf(']')
        val header = block.substring(0, headerEnd + 1)
        if (header != ExtraOpen) {
            foreign.add(block)
        } else {
            val body = block.substring(headerEnd + 1, block.length - ExtraClose.length)
            for (token in body.split(' ', '\t', '\n', '\r')) {
                if (token.isEmpty()) continue
                val eq = token.indexOf('=')
                if (eq <= 0) continue
                entries.add(extraUnescape(token.substring(0, eq)) to extraUnescape(token.substring(eq + 1)))
            }
        }
        rest = rest.substring(end + ExtraClose.length)
    }
    user.append(rest)
    val cleaned = user.toString().replace(Regex("[ \t]+\n"), "\n").replace(Regex("\n{3,}"), "\n\n").trim()
    return ExtraParse(cleaned, entries, foreign)
}

/** The fields of [contact] that only Android holds, as block entries. */
fun nativeExtraEntries(contact: CopyContact): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    for (p in contact.phones) if (p.value.isNotEmpty()) out += "phone.${p.label}" to p.value
    for (p in contact.postal) if (p.value.isNotEmpty()) out += "postal.${p.label}" to p.value
    if (contact.organization.isNotEmpty()) out += "org" to contact.organization
    if (contact.birthday.isNotEmpty()) out += "birthday" to contact.birthday
    for (p in contact.websites) if (p.value.isNotEmpty()) out += "website.${p.label}" to p.value
    for (p in contact.im) if (p.value.isNotEmpty()) out += "im.${p.label}" to p.value
    for (p in contact.custom) if (p.value.isNotEmpty()) out += "custom.${p.label}" to p.value
    if (contact.photo.isNotEmpty()) out += "photo" to "present"
    return out
}

/**
 * Restores block entries onto native Android fields. fcc and plaintext are returned separately; custom rows, the
 * photo note and unknown keys cannot be recreated and come back as leftovers (kept in the next block).
 */
data class RestoredExtras(
    val contact: CopyContact,
    val fcc: String,
    val plaintext: Boolean,
    val leftovers: List<Pair<String, String>>,
)

fun restoreExtras(contact: CopyContact, entries: List<Pair<String, String>>): RestoredExtras {
    val phones = contact.phones.toMutableList()
    val postal = contact.postal.toMutableList()
    val websites = contact.websites.toMutableList()
    val im = contact.im.toMutableList()
    var org = contact.organization
    var birthday = contact.birthday
    var fcc = ""
    var plain = false
    val left = ArrayList<Pair<String, String>>()
    fun add(list: MutableList<CopyField>, label: String, value: String) {
        if (list.none { it.value == value }) list += CopyField(label, value)
    }
    for ((k, v) in entries) {
        val kind = k.substringBefore('.')
        val label = if ('.' in k) k.substringAfter('.') else ""
        when {
            kind == "phone" -> add(phones, label, v)
            kind == "postal" -> add(postal, label, v)
            kind == "website" -> add(websites, label, v)
            kind == "im" -> add(im, label, v)
            k == "org" && org.isEmpty() -> org = v
            k == "birthday" && birthday.isEmpty() -> birthday = v
            k == "fcc" -> fcc = v
            k == "plaintext" -> plain = v.equals("yes", ignoreCase = true)
            k == "org" || k == "birthday" -> if (v != org && v != birthday) left += k to v
            else -> left += k to v
        }
    }
    val restored = contact.copy(phones = phones, postal = postal, websites = websites, im = im, organization = org, birthday = birthday)
    return RestoredExtras(restored, fcc, plain, left)
}
