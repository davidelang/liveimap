package org.dlang.liveimap.ui.contacts

import org.dlang.liveimap.ui.compose.addrSpec
import org.dlang.liveimap.ui.compose.splitAddresses

data class CopyField(val label: String, val value: String)

data class CopyOptions(
    val mergeIntoExisting: Boolean = false,
    val oneEntryPerEmail: Boolean = false,
    /** Keep fields Alpine has no place for in the extras block of the comment (default on: nothing is dropped). */
    val appendDroppedToComments: Boolean = true,
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
    /** Extras-block entries that have no field here; carried so the next copy keeps them. */
    val extras: List<Pair<String, String>> = emptyList(),
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
        val base = pineBase(contact)
        val match = if (base.isEmpty()) -1 else result.indexOfFirst { it.nickname.equals(base, ignoreCase = true) }
        if (match >= 0) {
            if (!options.mergeIntoExisting) {
                preview.add("Skipped, already there.")
                return
            }
            val existing = result[match]
            val have = when {
                isPineListAddress(existing.address) -> listMembers(existing, emptyList())
                existing.members.isNotEmpty() -> existing.members
                else -> existing.emails.filter { it.isNotEmpty() }
            }
            val combined = ArrayList(have)
            for (email in emails) {
                if (email.isNotEmpty() && combined.none { it.equals(email, ignoreCase = true) }) combined.add(email)
            }
            result[match] = if (isPineListAddress(existing.address)) {
                val trimmed = existing.address.trim()
                val inner = trimmed.substring(1, trimmed.length - 1).trim()
                val extra = combined.filter { email -> have.none { it.equals(email, ignoreCase = true) } }
                val address = when {
                    extra.isEmpty() -> existing.address
                    inner.isEmpty() -> extra.joinToString(", ", prefix = "(", postfix = ")")
                    else -> "(" + inner + ", " + extra.joinToString(", ") + ")"
                }
                existing.copy(address = address, changed = true)
            } else if (existing.group) {
                existing.copy(members = combined, changed = true)
            } else {
                existing.copy(emails = combined, changed = true)
            }
            if (existing.nickname.isNotEmpty()) preview.add("Nickname: ${existing.nickname}")
            preview.add("Group becomes a list of primary emails.")
            return
        }
        val nick = assignNickname(base, taken, current = null)
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
        if (match >= 0) {
            val (merged, kept) = fillOnlyMerge(result[match], mapped)
            if (kept) preview.add("Kept the existing name/comment; only empty fields are filled.") // F120
            result[match] = merged
        } else {
            result.add(mapped)
        }
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
        val name = contact.nickname.ifEmpty { contact.displayName }.ifEmpty { "Distribution list" }
        val match = result.indexOfFirst { it.group && it.displayName.equals(name, ignoreCase = true) }
        if (match >= 0 && !options.mergeIntoExisting) {
            preview.add("Skipped, already there.")
            return
        }
        preview.add("Order and the list comment are lost.")
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
        if (match >= 0) {
            val existing = result[match]
            val combined = ArrayList(existing.members)
            for (email in members) {
                if (combined.none { it.equals(email, ignoreCase = true) }) combined.add(email)
            }
            result[match] = existing.copy(members = combined, changed = true)
        } else {
            result.add(
                CopyContact(
                    group = true,
                    nickname = contact.nickname,
                    displayName = name,
                    members = members,
                    changed = true,
                ),
            )
        }
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
        if (parsed.fcc.isNotEmpty()) fcc = parsed.fcc
        comments = parsed.comments
        plaintext = parsed.plaintext
    }
    val block = parseExtraBlock(comments)
    comments = block.text
    val carried = ArrayList<Pair<String, String>>()
    for ((k, v) in block.entries) {
        when (k) {
            "fcc" -> if (fcc.isEmpty()) fcc = v
            "plaintext" -> if (v.equals("yes", ignoreCase = true)) plaintext = true
            else -> carried += k to v
        }
    }
    comments = comments.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
    if (plaintext && !comments.contains("[plaintext]")) {
        comments = if (comments.isEmpty()) "[plaintext]" else "$comments [plaintext]"
    }
    if (comments.length > 1000) comments = comments.substring(0, 1000)
    val entries = ArrayList<Pair<String, String>>()
    if (options.appendDroppedToComments) entries += nativeExtraEntries(contact)
    for (e in contact.extras + carried) if (e !in entries) entries += e
    val tail = (listOf(encodeExtraBlock(entries, oneLine = true)) + block.foreign.map { it.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ') })
        .filter { it.isNotEmpty() }
    if (tail.isNotEmpty()) comments = (listOf(comments).filter { it.isNotEmpty() } + tail).joinToString(" ")
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
    // Source text: an Alpine comment, or an Android note (old pine-fcc lines are still read).
    var fcc = contact.fcc
    var plain = contact.plaintext || contact.comments.contains("[plaintext]")
    var text = contact.comments
    if (contact.note.isNotEmpty()) {
        val parsed = parseNote(contact.note)
        if (parsed.fcc.isNotEmpty()) fcc = parsed.fcc
        if (parsed.plaintext) plain = true
        text = listOf(text, parsed.comments).filter { it.isNotEmpty() }.joinToString(" ")
    }
    val block = parseExtraBlock(text)
    val restored = restoreExtras(contact, block.entries)
    if (restored.fcc.isNotEmpty() && fcc.isEmpty()) fcc = restored.fcc
    if (restored.plaintext) plain = true
    val body = block.text.replace("[plaintext]", "").replace(Regex("\\s+"), " ").trim()
    val r = restored.contact
    fun restoredNote(present: Boolean, name: String) { if (present) preview.add("$name restored.") }
    restoredNote(r.phones.size > contact.phones.size, "Phone")
    restoredNote(r.postal.size > contact.postal.size, "Postal address")
    restoredNote(r.organization.isNotEmpty() && contact.organization.isEmpty(), "Organization")
    restoredNote(r.birthday.isNotEmpty() && contact.birthday.isEmpty(), "Birthday")
    restoredNote(r.websites.size > contact.websites.size, "Website")
    restoredNote(r.im.size > contact.im.size, "IM")
    val blockEntries = ArrayList<Pair<String, String>>()
    if (fcc.isNotEmpty()) blockEntries += "fcc" to fcc
    if (plain) blockEntries += "plaintext" to "yes"
    for (p in contact.custom) if (p.value.isNotEmpty()) blockEntries += "custom.${p.label}" to p.value
    for (e in contact.extras + restored.leftovers) if (e !in blockEntries) blockEntries += e
    if (contact.photo.isNotEmpty()) preview.add("Photo is not copied.")
    val note = if (!options.destinationKeepsNotes) {
        if (fcc.isNotEmpty()) preview.add("Fcc is dropped. This set has no notes.")
        if (body.isNotEmpty()) preview.add("Comments are dropped. This set has no notes.")
        if (plain) preview.add("Plaintext is dropped. This set has no notes.")
        if (blockEntries.any { it.first != "fcc" && it.first != "plaintext" }) preview.add("Other fields are dropped. This set has no notes.")
        ""
    } else {
        if (fcc.isNotEmpty()) preview.add("Fcc kept in the extras block: $fcc")
        if (body.isNotEmpty()) preview.add("Comments kept in the note.")
        if (plain) preview.add("Plaintext kept in the extras block.")
        if (contact.custom.any { it.value.isNotEmpty() }) preview.add("Custom label kept in the extras block.")
        buildNote(body, blockEntries + block.foreign.map { "" to it })
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
        phones = r.phones,
        postal = r.postal,
        organization = r.organization,
        birthday = r.birthday,
        websites = r.websites,
        im = r.im,
        changed = true,
    )
}

/** Note text: the user's text, then one extras block; entries keyed "" are foreign blocks kept verbatim. */
private fun buildNote(text: String, entries: List<Pair<String, String>>): String {
    val own = entries.filter { it.first.isNotEmpty() }
    val foreign = entries.filter { it.first.isEmpty() }.map { it.second }
    return (listOf(text, encodeExtraBlock(own, oneLine = false)) + foreign).filter { it.isNotEmpty() }.joinToString("\n")
}

private fun previewDropped(contact: CopyContact, options: CopyOptions, preview: MutableList<String>) {
    fun drop(present: Boolean, name: String) {
        if (!present) return
        if (options.appendDroppedToComments) preview.add("$name kept in the extras block.")
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

/** F120: one row of an existing contact the merge can touch. */
data class ExistingField(val rowId: Long, val value: String)

/** F120: what merging one copied contact into an existing one does. rowId null = insert a new row. */
data class MergeChange(val field: String, val rowId: Long?, val value: String)

data class MergePlan(val changes: List<MergeChange>, val conflicts: List<String>)

/**
 * F120: "merge into existing". Addresses are added (case-insensitively new ones only) and never removed. Name,
 * nickname and note fill a missing or empty field; a different non-empty value is a conflict and is overwritten
 * only when [overwrite] (the user confirmed). Fields keyed "name", "nickname", "email", "note".
 */
fun mergeContactPlan(entry: CopyContact, existing: Map<String, List<ExistingField>>, overwrite: Boolean): MergePlan {
    val changes = ArrayList<MergeChange>()
    val conflicts = ArrayList<String>()
    fun single(field: String, wanted: String) {
        if (wanted.isEmpty()) return
        val rows = existing[field].orEmpty()
        if (rows.any { it.value == wanted }) return
        val row = rows.firstOrNull()
        when {
            row == null -> changes += MergeChange(field, null, wanted)
            row.value.isBlank() -> changes += MergeChange(field, row.rowId, wanted)
            else -> {
                conflicts += field
                if (overwrite) changes += MergeChange(field, row.rowId, wanted)
            }
        }
    }
    val name = entry.displayName.ifEmpty { listOf(entry.givenName, entry.familyName).filter { it.isNotEmpty() }.joinToString(" ") }
    single("name", name)
    single("nickname", entry.nickname)
    val have = existing["email"].orEmpty().map { it.value.lowercase() }.toMutableSet()
    for (email in entry.emails) {
        if (email.isEmpty() || !have.add(email.lowercase())) continue
        changes += MergeChange("email", null, email)
    }
    single("note", entry.note)
    return MergePlan(changes, conflicts)
}

/**
 * F120: merge a copied Alpine entry into an existing one without losing anything: addresses are unioned (the
 * existing ones first), and full name, Fcc and comments fill only when empty. Second = an existing value was kept.
 */
fun fillOnlyMerge(existing: CopyContact, copied: CopyContact): Pair<CopyContact, Boolean> {
    val emails = (existing.emails + copied.emails).filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
    fun pick(old: String, new: String) = if (old.isNotEmpty()) old else new
    val kept = (existing.displayName.isNotEmpty() && copied.displayName.isNotEmpty() && existing.displayName != copied.displayName) ||
        (existing.comments.isNotEmpty() && copied.comments.isNotEmpty() && existing.comments != copied.comments) ||
        (existing.fcc.isNotEmpty() && copied.fcc.isNotEmpty() && existing.fcc != copied.fcc)
    val merged = existing.copy(
        nickname = pick(existing.nickname, copied.nickname),
        displayName = pick(existing.displayName, copied.displayName),
        emails = emails,
        fcc = pick(existing.fcc, copied.fcc),
        comments = pick(existing.comments, copied.comments),
        plaintext = existing.plaintext || copied.plaintext,
        changed = true,
    )
    return merged to kept
}
