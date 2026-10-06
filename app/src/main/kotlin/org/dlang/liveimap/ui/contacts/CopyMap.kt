package org.dlang.liveimap.ui.contacts

import org.dlang.liveimap.ui.compose.addrSpec
import org.dlang.liveimap.ui.compose.splitAddresses

data class CopyField(val label: String, val value: String)

data class CopyOptions(
    val mergeIntoExisting: Boolean = false,
    val oneEntryPerEmail: Boolean = false,
    val appendDroppedToComments: Boolean = false,
    val destinationKeepsNotes: Boolean = true,
    val destinationHasGroups: Boolean = true,
    val destinationKeepsNickname: Boolean = true,
)

data class CopyContact(
    val nickname: String = "",
    val nicknames: List<String> = emptyList(),
    val displayName: String = "",
    val givenName: String = "",
    val familyName: String = "",
    val emails: List<String> = emptyList(),
    val emailType: String = "",
    val address: String = "",
    val fcc: String = "",
    val comments: String = "",
    val note: String = "",
    val plaintext: Boolean = false,
    val phones: List<CopyField> = emptyList(),
    val postal: List<CopyField> = emptyList(),
    val organization: String = "",
    val birthday: String = "",
    val photo: String = "",
    val websites: List<CopyField> = emptyList(),
    val im: List<CopyField> = emptyList(),
    val custom: List<CopyField> = emptyList(),
    val starred: Boolean = false,
    val ringtone: String = "",
    val linked: Boolean = false,
    val group: Boolean = false,
    val members: List<String> = emptyList(),
    val rawId: Long = 0,
    val groupId: Long = 0,
    val changed: Boolean = false,
)

data class CopyOutcome(
    val preview: List<String>,
    val entries: List<CopyContact>,
)

fun isPineListAddress(address: String): Boolean {
    val trimmed = address.trim()
    return trimmed.length >= 2 && trimmed.startsWith("(") && trimmed.endsWith(")")
}

fun AlpineEntry.asCopyContact(): CopyContact {
    val list = isPineListAddress(address)
    return CopyContact(
        nickname = nickname,
        displayName = fullname,
        address = if (list) address else "",
        emails = if (list || address.isEmpty()) emptyList() else listOf(address),
        fcc = fcc,
        comments = comments,
        plaintext = comments.contains("[plaintext]"),
        group = list,
    )
}

fun CopyContact.asAlpineEntry(): AlpineEntry {
    val addr = when {
        address.isNotEmpty() -> address
        group && members.isNotEmpty() -> members.joinToString(", ", prefix = "(", postfix = ")")
        else -> emails.firstOrNull().orEmpty()
    }
    return AlpineEntry(
        nickname = nickname,
        fullname = displayName,
        address = addr,
        fcc = fcc,
        comments = comments,
    )
}

fun copyContacts(
    source: List<CopyContact>,
    destination: List<CopyContact>,
    intoPine: Boolean,
    options: CopyOptions = CopyOptions(),
): CopyOutcome {
    val preview = ArrayList<String>()
    val result = ArrayList<CopyContact>(destination.size + source.size)
    for (entry in destination) result.add(entry.copy(changed = false))
    val taken = HashSet<String>()
    for (entry in result) {
        val nick = entry.nickname.filter { !it.isWhitespace() }
        if (nick.isNotEmpty()) taken.add(nick)
    }
    for (contact in source) {
        if (intoPine) copyIntoPine(contact, source, result, taken, options, preview)
        else copyIntoAndroid(contact, source, result, options, preview)
    }
    return CopyOutcome(preview, result)
}

private fun copyIntoPine(
    contact: CopyContact,
    book: List<CopyContact>,
    result: MutableList<CopyContact>,
    taken: MutableSet<String>,
    options: CopyOptions,
    preview: MutableList<String>,
) {
    if (contact.group || isPineListAddress(contact.address)) {
        val emails = listMembers(contact, book)
        val nick = assignNickname(pineBase(contact), taken, current = null)
        val fields = pineFields(contact, options)
        preview.add("Group becomes a list of primary emails.")
        if (nick.isNotEmpty()) preview.add("Nickname: $nick")
        if (contact.displayName.isNotEmpty()) preview.add("Full name: ${contact.displayName}")
        if (fields.fcc.isNotEmpty()) preview.add("Fcc: ${fields.fcc}")
        if (fields.comments.isNotEmpty()) preview.add("Comments: ${fields.comments}")
        if (fields.plaintext) preview.add("Plaintext restored.")
        previewDropped(contact, options, preview)
        result.add(
            CopyContact(
                nickname = nick,
                displayName = contact.displayName,
                address = emails.joinToString(", ", prefix = "(", postfix = ")"),
                fcc = fields.fcc,
                comments = fields.comments,
                plaintext = fields.plaintext,
                changed = true,
            ),
        )
        return
    }
    val emails = ownEmails(contact)
    val use = if (options.oneEntryPerEmail) {
        if (emails.isEmpty()) listOf("") else emails
    } else {
        listOf(emails.firstOrNull().orEmpty())
    }
    if (options.oneEntryPerEmail && emails.size > 1) preview.add("One entry per email.")
    for (email in use) {
        val match = if (email.isNotEmpty()) findEmail(result, email) else -1
        if (match >= 0 && !options.mergeIntoExisting) {
            preview.add("Skipped, already there.")
            continue
        }
        val current = if (match >= 0) result[match].nickname else null
        val nick = assignNickname(pineBase(contact), taken, current)
        val fields = pineFields(contact, options)
        previewPineFields(nick, contact.displayName, email, fields, preview)
        previewDropped(contact, options, preview)
        val mapped = CopyContact(
            nickname = nick,
            displayName = contact.displayName,
            emails = if (email.isEmpty()) emptyList() else listOf(email),
            fcc = fields.fcc,
            comments = fields.comments,
            plaintext = fields.plaintext,
            changed = true,
        )
        if (match >= 0) result[match] = mapped.copy(rawId = result[match].rawId)
        else result.add(mapped)
    }
}

private fun copyIntoAndroid(
    contact: CopyContact,
    book: List<CopyContact>,
    result: MutableList<CopyContact>,
    options: CopyOptions,
    preview: MutableList<String>,
) {
    if (contact.group || isPineListAddress(contact.address)) {
        val members = listMembers(contact, book)
        if (!options.destinationHasGroups) {
            preview.add("This set has no groups.")
            val email = members.firstOrNull().orEmpty()
            val match = if (email.isNotEmpty()) findEmail(result, email) else -1
            if (match >= 0 && !options.mergeIntoExisting) {
                preview.add("Skipped, already there.")
                return
            }
            val mapped = androidContact(contact, members, options, preview)
            if (match >= 0) {
                result[match] = mapped.copy(rawId = result[match].rawId, changed = true)
            } else {
                result.add(mapped.copy(changed = true))
            }
            return
        }
        preview.add("Order and the list comment are lost.")
        val name = contact.nickname.ifEmpty { contact.displayName }.ifEmpty { "Distribution list" }
        preview.add("Distribution list becomes group $name.")
        for (email in members) {
            preview.add("Member: $email")
            if (findEmail(result, email) >= 0) continue
            result.add(
                CopyContact(
                    displayName = email,
                    emails = listOf(email),
                    emailType = "Other",
                    changed = true,
                ),
            )
        }
        result.add(
            CopyContact(
                group = true,
                nickname = contact.nickname,
                displayName = name,
                members = members,
                changed = true,
            ),
        )
        return
    }
    val email = ownEmails(contact).firstOrNull().orEmpty()
    val match = if (email.isNotEmpty()) findEmail(result, email) else -1
    if (match >= 0 && !options.mergeIntoExisting) {
        preview.add("Skipped, already there.")
        return
    }
    val mapped = androidContact(contact, if (email.isEmpty()) emptyList() else listOf(email), options, preview)
    if (match >= 0) result[match] = mapped.copy(rawId = result[match].rawId, changed = true)
    else result.add(mapped.copy(changed = true))
}

private data class PineFields(val fcc: String, val comments: String, val plaintext: Boolean)

private fun pineFields(contact: CopyContact, options: CopyOptions): PineFields {
    var fcc = contact.fcc
    var comments = contact.comments
    var plaintext = contact.plaintext || comments.contains("[plaintext]")
    if (contact.note.isNotEmpty()) {
        val parsed = parseNote(contact.note)
        fcc = parsed.fcc
        comments = parsed.comments
        plaintext = parsed.plaintext
    }
    comments = comments.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
    if (plaintext && !comments.contains("[plaintext]")) {
        comments = if (comments.isEmpty()) "[plaintext]" else "$comments [plaintext]"
    }
    if (options.appendDroppedToComments) {
        val extra = extraLines(contact)
        if (extra.isNotEmpty()) {
            comments = if (comments.isEmpty()) extra.joinToString(" ") else comments + " " + extra.joinToString(" ")
        }
    }
    if (comments.length > 1000) comments = comments.substring(0, 1000)
    return PineFields(fcc, comments, plaintext)
}

private data class ParsedNote(val fcc: String, val comments: String, val plaintext: Boolean)

private fun parseNote(note: String): ParsedNote {
    var fcc = ""
    var plaintext = false
    val rest = ArrayList<String>()
    val normalized = note.replace("\r\n", "\n").replace('\r', '\n')
    for (line in normalized.split('\n')) {
        val trimmed = line.trim()
        if (trimmed.startsWith("pine-fcc:", ignoreCase = true)) {
            fcc = trimmed.substringAfter(':').trim()
            continue
        }
        if (trimmed.equals("pine-plaintext: yes", ignoreCase = true)) {
            plaintext = true
            continue
        }
        rest.add(line.trim())
    }
    return ParsedNote(fcc, rest.joinToString(" ").trim(), plaintext)
}

private fun previewPineFields(
    nickname: String,
    displayName: String,
    email: String,
    fields: PineFields,
    preview: MutableList<String>,
) {
    if (nickname.isNotEmpty()) preview.add("Nickname: $nickname")
    if (displayName.isNotEmpty()) preview.add("Full name: $displayName")
    if (email.isNotEmpty()) preview.add("Email: $email")
    if (fields.fcc.isNotEmpty()) preview.add("Fcc: ${fields.fcc}")
    if (fields.comments.isNotEmpty()) preview.add("Comments: ${fields.comments}")
    if (fields.plaintext) preview.add("Plaintext restored.")
}

private fun androidContact(
    contact: CopyContact,
    emails: List<String>,
    options: CopyOptions,
    preview: MutableList<String>,
): CopyContact {
    val nick = contact.nickname.ifEmpty { contact.nicknames.firstOrNull { it.isNotEmpty() }.orEmpty() }
    val keptNick = if (options.destinationKeepsNickname) nick else ""
    if (nick.isNotEmpty()) {
        if (options.destinationKeepsNickname) preview.add("Nickname: $nick")
        else preview.add("Nickname is not kept by this set.")
    }
    val comma = contact.displayName.indexOf(',')
    val given: String
    val family: String
    if (comma >= 0) {
        family = contact.displayName.substring(0, comma).trim()
        given = contact.displayName.substring(comma + 1).trim()
        preview.add("Full name split into family $family and given $given.")
    } else {
        family = ""
        given = ""
        if (contact.displayName.isNotEmpty()) preview.add("Full name: ${contact.displayName}")
    }
    if (emails.size == 1) preview.add("Email: ${emails[0]}")
    else if (emails.size > 1) preview.add("Email: ${emails.joinToString(", ")}")
    val plain = contact.plaintext || contact.comments.contains("[plaintext]")
    val body = contact.comments.replace("[plaintext]", "").trim()
    val note = if (!options.destinationKeepsNotes) {
        if (contact.fcc.isNotEmpty()) preview.add("Fcc is dropped. This set has no notes.")
        if (body.isNotEmpty()) preview.add("Comments are dropped. This set has no notes.")
        if (plain) preview.add("Plaintext is dropped. This set has no notes.")
        ""
    } else {
        if (contact.fcc.isNotEmpty()) preview.add("Fcc kept as pine-fcc: ${contact.fcc}")
        if (body.isNotEmpty()) preview.add("Comments kept in the note.")
        if (plain) preview.add("Plaintext kept as pine-plaintext: yes")
        buildNote(contact.fcc, body, plain)
    }
    return CopyContact(
        nickname = keptNick,
        nicknames = if (keptNick.isEmpty()) emptyList() else listOf(keptNick),
        displayName = contact.displayName,
        givenName = given,
        familyName = family,
        emails = emails,
        emailType = "Other",
        note = note,
        plaintext = plain && options.destinationKeepsNotes,
        changed = true,
    )
}

private fun buildNote(fcc: String, comments: String, plaintext: Boolean): String = buildString {
    if (fcc.isNotEmpty()) appendLine("pine-fcc: $fcc")
    if (plaintext) appendLine("pine-plaintext: yes")
    if (comments.isNotEmpty()) append(comments)
}.trim()

private fun previewDropped(contact: CopyContact, options: CopyOptions, preview: MutableList<String>) {
    fun drop(present: Boolean, name: String) {
        if (!present) return
        if (options.appendDroppedToComments) preview.add("$name appended to comments.")
        else preview.add("$name is dropped.")
    }
    drop(contact.phones.any { it.value.isNotEmpty() }, "Phone")
    drop(contact.postal.any { it.value.isNotEmpty() }, "Postal address")
    drop(contact.organization.isNotEmpty(), "Organization")
    drop(contact.birthday.isNotEmpty(), "Birthday")
    drop(contact.photo.isNotEmpty(), "Photo")
    drop(contact.websites.any { it.value.isNotEmpty() }, "Website")
    drop(contact.im.any { it.value.isNotEmpty() }, "IM")
    drop(contact.custom.any { it.value.isNotEmpty() }, "Custom label")
    if (contact.starred) preview.add("Starred is not copied.")
    if (contact.ringtone.isNotEmpty()) preview.add("Ringtone is not copied.")
    if (contact.linked) preview.add("Linked contacts are not copied.")
}

private fun extraLines(contact: CopyContact): List<String> {
    val lines = ArrayList<String>()
    for (phone in contact.phones) {
        if (phone.value.isNotEmpty()) lines.add("${phone.label.ifEmpty { "Phone" }}: ${phone.value}")
    }
    for (postal in contact.postal) {
        if (postal.value.isNotEmpty()) lines.add("${postal.label.ifEmpty { "Postal address" }}: ${postal.value}")
    }
    if (contact.organization.isNotEmpty()) lines.add("Organization: ${contact.organization}")
    if (contact.birthday.isNotEmpty()) lines.add("Birthday: ${contact.birthday}")
    if (contact.photo.isNotEmpty()) lines.add("Photo: present")
    for (site in contact.websites) {
        if (site.value.isNotEmpty()) lines.add("${site.label.ifEmpty { "Website" }}: ${site.value}")
    }
    for (im in contact.im) {
        if (im.value.isNotEmpty()) lines.add("${im.label.ifEmpty { "IM" }}: ${im.value}")
    }
    for (custom in contact.custom) {
        if (custom.value.isNotEmpty()) lines.add("${custom.label.ifEmpty { "Custom" }}: ${custom.value}")
    }
    return lines
}

private fun ownEmails(contact: CopyContact): List<String> {
    if (contact.group || isPineListAddress(contact.address)) return emptyList()
    return contact.emails.filter { it.isNotEmpty() }
}

private fun findEmail(entries: List<CopyContact>, email: String): Int {
    val wanted = email.trim()
    if (wanted.isEmpty()) return -1
    return entries.indexOfFirst { entry ->
        ownEmails(entry).any { it.equals(wanted, ignoreCase = true) }
    }
}

private fun pineBase(contact: CopyContact): String {
    val first = contact.nicknames.firstOrNull { it.isNotEmpty() }
        ?: contact.nickname.takeIf { it.isNotEmpty() }
        ?: contact.displayName
    return first.filter { !it.isWhitespace() }
}

private fun assignNickname(base: String, taken: MutableSet<String>, current: String?): String {
    if (base.isEmpty()) return ""
    val keep = current?.filter { !it.isWhitespace() }.orEmpty()
    if (base == keep || base !in taken) {
        taken.add(base)
        return base
    }
    var n = 2
    while ("$base$n" in taken && "$base$n" != keep) n++
    val chosen = "$base$n"
    taken.add(chosen)
    return chosen
}

private fun listMembers(contact: CopyContact, book: List<CopyContact>): List<String> {
    if (contact.members.isNotEmpty() && !isPineListAddress(contact.address)) return contact.members
    if (!isPineListAddress(contact.address)) return contact.emails
    val inner = contact.address.trim().let { it.substring(1, it.length - 1) }
    val out = ArrayList<String>()
    expandPieces(splitAddresses(inner), book, HashSet(), out)
    return out
}

private fun expandPieces(
    pieces: List<String>,
    book: List<CopyContact>,
    seen: MutableSet<String>,
    out: MutableList<String>,
) {
    for (piece in pieces) {
        val spec = addrSpec(piece).replace("[plaintext]", "").trim()
        if (spec.isEmpty()) continue
        if ('@' in spec) {
            if (out.none { it.equals(spec, ignoreCase = true) }) out.add(spec)
            continue
        }
        if (!seen.add(spec.lowercase())) continue
        val entry = book.firstOrNull { it.nickname.equals(spec, ignoreCase = true) } ?: continue
        if (isPineListAddress(entry.address)) {
            val inner = entry.address.trim().let { it.substring(1, it.length - 1) }
            expandPieces(splitAddresses(inner), book, seen, out)
        } else if (entry.group && entry.members.isNotEmpty()) {
            for (email in entry.members) {
                if (out.none { it.equals(email, ignoreCase = true) }) out.add(email)
            }
        } else {
            val email = entry.emails.firstOrNull { it.isNotEmpty() }.orEmpty()
            if (email.isNotEmpty() && out.none { it.equals(email, ignoreCase = true) }) out.add(email)
        }
    }
}
