package org.dlang.liveimap.ui.contacts

import org.dlang.liveimap.ui.compose.addrSpec
import org.dlang.liveimap.ui.compose.splitAddresses

/** Contacts plan phase 4: one edit of an Alpine book; each save is one whole-book write. */
sealed class BookEdit {
    data class Add(val entry: AlpineEntry) : BookEdit()
    /** Replace the entry named [nickname]; a changed nickname is a rename (list references follow). */
    data class Update(val nickname: String, val entry: AlpineEntry) : BookEdit()
    data class Delete(val nickname: String) : BookEdit()
}

sealed class EditOutcome {
    data class Done(val entries: List<AlpineEntry>, val notes: List<String>) : EditOutcome()
    data class Refused(val reason: String) : EditOutcome()
}

private fun sameNick(a: String, b: String) = a.equals(b, ignoreCase = true)

/** Why [nickname] can't be used, or null. [except] is the entry being edited. */
fun nicknameProblem(entries: List<AlpineEntry>, nickname: String, except: String? = null): String? {
    if (nickname.isEmpty()) return "A nickname is required."
    if (nickname.any { it.isWhitespace() }) return "A nickname cannot contain spaces."
    if (nickname.any { it == '(' || it == ')' || it == ',' }) return "A nickname cannot contain ( ) or ,."
    if (entries.any { sameNick(it.nickname, nickname) && (except == null || !sameNick(it.nickname, except)) }) {
        return "Nickname $nickname is already used."
    }
    return null
}

/** List members of an entry (addresses and nicknames, as written), or empty for a single address. */
fun listMembersOf(entry: AlpineEntry): List<String> {
    if (!isPineListAddress(entry.address)) return emptyList()
    val inner = entry.address.trim().let { it.substring(1, it.length - 1) }
    return splitAddresses(inner).map { it.trim() }.filter { it.isNotEmpty() }
}

fun listAddress(members: List<String>): String = members.joinToString(", ", prefix = "(", postfix = ")")

/** Lists whose members name [nickname]. */
fun listsReferring(entries: List<AlpineEntry>, nickname: String): List<String> =
    entries.filter { e -> listMembersOf(e).any { sameNick(addrSpec(it).trim(), nickname) } }.map { it.nickname }

fun applyBookEdit(entries: List<AlpineEntry>, edit: BookEdit): EditOutcome {
    val clean: (AlpineEntry) -> AlpineEntry = { e ->
        e.copy(
            nickname = e.nickname.trim(),
            fullname = e.fullname.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim(),
            address = e.address.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim(),
            fcc = e.fcc.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim(),
            comments = e.comments.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim(),
        )
    }
    return when (edit) {
        is BookEdit.Add -> {
            val e = clean(edit.entry)
            nicknameProblem(entries, e.nickname)?.let { return EditOutcome.Refused(it) }
            if (e.address.isEmpty()) return EditOutcome.Refused("An address is required.")
            EditOutcome.Done(entries + e, emptyList())
        }
        is BookEdit.Update -> {
            val index = entries.indexOfFirst { sameNick(it.nickname, edit.nickname) }
            if (index < 0) return EditOutcome.Refused("Entry ${edit.nickname} is no longer in the book.")
            val e = clean(edit.entry)
            nicknameProblem(entries, e.nickname, except = edit.nickname)?.let { return EditOutcome.Refused(it) }
            if (e.address.isEmpty()) return EditOutcome.Refused("An address is required.")
            val out = entries.toMutableList()
            out[index] = e
            val notes = ArrayList<String>()
            if (e.nickname != edit.nickname) {
                // rename: lists naming the old nickname follow the new one
                for (i in out.indices) {
                    val members = listMembersOf(out[i])
                    if (members.none { sameNick(addrSpec(it).trim(), edit.nickname) }) continue
                    out[i] = out[i].copy(address = listAddress(members.map { m ->
                        if (sameNick(addrSpec(m).trim(), edit.nickname)) e.nickname else m
                    }))
                    notes += "List ${out[i].nickname} now names ${e.nickname}."
                }
            }
            EditOutcome.Done(out, notes)
        }
        is BookEdit.Delete -> {
            val index = entries.indexOfFirst { sameNick(it.nickname, edit.nickname) }
            if (index < 0) return EditOutcome.Refused("Entry ${edit.nickname} is no longer in the book.")
            val refs = listsReferring(entries, edit.nickname).filter { !sameNick(it, edit.nickname) }
            val notes = refs.map { "List $it still names ${edit.nickname}; it will no longer expand." }
            EditOutcome.Done(entries.filterIndexed { i, _ -> i != index }, notes)
        }
    }
}

/** The nickname the edit touches in the book it was made on. */
private fun touched(edit: BookEdit): String = when (edit) {
    is BookEdit.Add -> edit.entry.nickname
    is BookEdit.Update -> edit.nickname
    is BookEdit.Delete -> edit.nickname
}

sealed class Rebase {
    /** The other client's change did not touch this entry; the edit applies cleanly to the latest book. */
    data class Clean(val outcome: EditOutcome) : Rebase()
    /** The same entry changed meanwhile: [theirs] is the latest version (null = removed), shown to the user. */
    data class Conflict(val mine: AlpineEntry?, val theirs: AlpineEntry?, val base: AlpineEntry?) : Rebase()
}

/**
 * Before writing, the latest book is re-read (plan phase 4). If the touched entry is the same in [base] and
 * [latest], the edit is re-applied to [latest]; otherwise the user is shown both and asked.
 */
fun rebaseEdit(base: List<AlpineEntry>, latest: List<AlpineEntry>, edit: BookEdit): Rebase {
    val nick = touched(edit)
    val before = base.firstOrNull { sameNick(it.nickname, nick) }
    val now = latest.firstOrNull { sameNick(it.nickname, nick) }
    val mine = when (edit) {
        is BookEdit.Add -> edit.entry
        is BookEdit.Update -> edit.entry
        is BookEdit.Delete -> null
    }
    if (before == now) return Rebase.Clean(applyBookEdit(latest, edit))
    return Rebase.Conflict(mine, now, before)
}
