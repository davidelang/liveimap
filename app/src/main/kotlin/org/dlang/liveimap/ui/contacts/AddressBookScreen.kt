package org.dlang.liveimap.ui.contacts
import org.dlang.liveimap.i18n.S

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import org.dlang.liveimap.ui.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import org.dlang.liveimap.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.alpineBooks
import org.dlang.liveimap.ui.LocalScreenAccounts

/** The entry being edited: null original = a new entry. */
private data class Draft(val original: String?, val entry: AlpineEntry)

/**
 * Contacts plan phase 4: browse one Alpine book of this account and add, edit, rename or delete entries (lists:
 * members edited as "(a, b, nick)"). Each save is one whole-book write with the history trim. The book is re-read
 * before writing; another client's change to the same entry is shown and asked about.
 */
@Composable
fun AddressBookScreen() {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { if (accountId == null) accountId = store.chosenAccountId() }
    val id = accountId
    if (id.isNullOrEmpty()) return
    AddressBookLoaded(id, remember(id) { store.forAccount(id) })
}

@Composable
private fun AddressBookLoaded(accountId: String, store: DataStoreSettingsStore) {
    LocalScreenAccounts.current.Register(accountId)
    val context = LocalContext.current
    val session = remember(accountId) { mailSession(accountId) }
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<Settings?>(null) }
    var book by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var state by remember { mutableStateOf<PineBookState?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var conflict by remember { mutableStateOf<Triple<BookEdit, Rebase.Conflict, PineBookState>?>(null) }

    LaunchedEffect(session) {
        try {
            val loaded = store.load()
            store.password()
            when (val opened = session.open(loaded)) {
                is OpenResult.Rejected -> notice = opened.capabilities
                is OpenResult.Failed -> notice = opened.text
                OpenResult.Connected -> settings = loaded
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: MailFailure) {
            notice = error.text
        } catch (error: Exception) {
            notice = error.message ?: S(context, "not connected")
        }
    }

    suspend fun read(mailbox: String) {
        when (val r = readPineBook(session, mailbox, settings?.bodyFetchChunk ?: 65536)) {
            is PineRead.NotBook -> { state = null; notice = r.notice }
            is PineRead.Ready -> state = r.state
        }
    }

    suspend fun write(edit: BookEdit, entries: List<AlpineEntry>, on: PineBookState) {
        val mailbox = book?.first ?: return
        val s = store.load()
        when (val result = writePineBook(session, mailbox, on.lastUid, entries, s.addressBookHistory.toInt(), s.addressBookNeverTrim)) {
            is PineWriteResult.Wrote -> { notice = S(context, "Address book saved."); read(mailbox) }
            is PineWriteResult.NotBook -> notice = result.notice
            is PineWriteResult.Stale -> when (val rb = rebaseEdit(on.entries, result.state.entries, edit)) {
                is Rebase.Clean -> when (val o = rb.outcome) {
                    is EditOutcome.Refused -> { notice = o.reason; state = result.state }
                    is EditOutcome.Done -> {
                        notice = S(context, "The book was changed elsewhere; your change was re-applied to the latest copy.")
                        write(edit, o.entries, result.state)
                    }
                }
                is Rebase.Conflict -> conflict = Triple(edit, rb, result.state)
            }
        }
    }

    fun save(edit: BookEdit) {
        val on = state ?: return
        when (val o = applyBookEdit(on.entries, edit)) {
            is EditOutcome.Refused -> notice = o.reason
            is EditOutcome.Done -> {
                busy = true
                scope.launch {
                    try {
                        write(edit, o.entries, on)
                        draft = null
                        if (o.notes.isNotEmpty()) notice = (listOfNotNull(notice) + o.notes).joinToString("\n")
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        notice = error.message ?: S(context, "Could not write the address book.")
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }

    conflict?.let { (edit, c, latest) ->
        fun show(e: AlpineEntry?) = e?.let { "${it.nickname}: ${it.fullname} ${it.address} ${it.comments}".trim() }
            ?: S(context, "(removed)")
        AlertDialog(
            onDismissRequest = { conflict = null; state = latest },
            title = { Text(S("Changed by another client")) },
            text = { Text(S("This entry was changed elsewhere.\n\nNow in the book: %1\$s\n\nYour version: %2\$s", show(c.theirs), show(c.mine))) },
            confirmButton = {
                TextButton(onClick = {
                    conflict = null
                    state = latest
                    // keep mine on the latest book (an Update of an entry removed meanwhile becomes an Add)
                    val again = if (edit is BookEdit.Update && c.theirs == null) BookEdit.Add(edit.entry) else edit
                    save(again)
                }) { Text(S("Keep mine")) }
            },
            dismissButton = {
                TextButton(onClick = { conflict = null; state = latest; draft = null }) { Text(S("Use theirs")) }
            },
        )
    }
    deleting?.let { nick ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(S("Delete %1\$s?", nick)) },
            text = { Text(S("The entry is removed in a new copy of the book; earlier copies stay until the history trim.")) },
            confirmButton = { TextButton(onClick = { deleting = null; save(BookEdit.Delete(nick)) }) { Text(S("Delete")) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(S("Cancel", name = "book_cancel")) } },
        )
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        notice?.let { Text(it) }
        if (busy) Text(S("Reading…"))
        val current = settings ?: return@Column
        val chosen = book
        val d = draft
        when {
            chosen == null -> {
                val books = current.alpineBooks()
                if (books.isEmpty()) Text(S("No address book is set for this account."))
                for (b in books) {
                    val label = if (b.second) S(context, "%1\$s, read only", b.first) else b.first
                    TextButton(onClick = {
                        book = b
                        notice = null
                        busy = true
                        scope.launch {
                            try {
                                read(b.first)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                notice = e.message ?: S(context, "Could not read contacts.")
                            } finally {
                                busy = false
                            }
                        }
                    }, enabled = !busy) { Text(label) }
                }
            }
            d != null -> {
                var nick by remember(d) { mutableStateOf(d.entry.nickname) }
                var full by remember(d) { mutableStateOf(d.entry.fullname) }
                var addr by remember(d) { mutableStateOf(d.entry.address) }
                var fcc by remember(d) { mutableStateOf(d.entry.fcc) }
                var comments by remember(d) { mutableStateOf(d.entry.comments) }
                OutlinedTextField(nick, { nick = it }, label = { Text(S("Nickname")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(full, { full = it }, label = { Text(S("Full name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(addr, { addr = it }, label = { Text(S("Address, or a list as (a@x, b@y, nickname)")) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fcc, { fcc = it }, label = { Text(S("Fcc")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(comments, { comments = it }, label = { Text(S("Comments")) }, modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick = { draft = null }, enabled = !busy) { Text(S("Cancel", name = "book_cancel")) }
                    TextButton(onClick = {
                        val e = AlpineEntry(nick, full, addr, fcc, comments)
                        save(if (d.original == null) BookEdit.Add(e) else BookEdit.Update(d.original, e))
                    }, enabled = !busy) { Text(S("Save")) }
                }
            }
            else -> {
                val st = state
                Text(chosen.first)
                Row {
                    TextButton(onClick = { book = null; state = null; notice = null }, enabled = !busy) { Text(S("Back", name = "reader_back")) }
                    if (!chosen.second && st != null) {
                        TextButton(onClick = { draft = Draft(null, AlpineEntry("", "", "", "", "")) }, enabled = !busy) {
                            Text(S("Add entry"))
                        }
                    }
                }
                for (e in st?.entries.orEmpty()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(S("%1\$s  %2\$s  %3\$s", e.nickname, e.fullname, e.address), Modifier.weight(1f))
                        if (!chosen.second) {
                            TextButton(onClick = { draft = Draft(e.nickname, e) }, enabled = !busy) { Text(S("Edit")) }
                            TextButton(onClick = { deleting = e.nickname }, enabled = !busy) { Text(S("Delete")) }
                        }
                    }
                }
            }
        }
    }
}
