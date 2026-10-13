package org.dlang.liveimap.ui.contacts

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.dlang.liveimap.R
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.alpineBooks
import org.dlang.liveimap.settings.openBound

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
    val context = LocalContext.current
    val session = remember(accountId) { mailSession(accountId) }
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<AccountSettings?>(null) }
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
            when (val opened = session.openBound(store, loaded)) {
                is OpenResult.Rejected -> notice = opened.capabilities
                is OpenResult.Failed -> notice = opened.text
                OpenResult.Connected -> settings = loaded
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: MailFailure) {
            notice = error.text
        } catch (error: Exception) {
            notice = error.message ?: context.getString(R.string.reader_not_connected)
        }
    }

    suspend fun read(mailbox: String) {
        when (val r = readPineBook(session, mailbox)) {
            is PineRead.NotBook -> { state = null; notice = r.notice }
            is PineRead.Ready -> state = r.state
        }
    }

    suspend fun write(edit: BookEdit, entries: List<AlpineEntry>, on: PineBookState) {
        val mailbox = book?.first ?: return
        val s = store.load()
        when (val result = writePineBook(session, mailbox, on.lastUid, entries, s.addressBookHistory, s.addressBookNeverTrim)) {
            is PineWriteResult.Wrote -> { notice = context.getString(R.string.book_saved); read(mailbox) }
            is PineWriteResult.NotBook -> notice = result.notice
            is PineWriteResult.Stale -> when (val rb = rebaseEdit(on.entries, result.state.entries, edit)) {
                is Rebase.Clean -> when (val o = rb.outcome) {
                    is EditOutcome.Refused -> { notice = o.reason; state = result.state }
                    is EditOutcome.Done -> {
                        notice = context.getString(R.string.book_reapplied)
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
                        notice = error.message ?: context.getString(R.string.copy_book_failed)
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }

    conflict?.let { (edit, c, latest) ->
        fun show(e: AlpineEntry?) = e?.let { "${it.nickname}: ${it.fullname} ${it.address} ${it.comments}".trim() }
            ?: context.getString(R.string.book_entry_gone)
        AlertDialog(
            onDismissRequest = { conflict = null; state = latest },
            title = { Text(stringResource(R.string.book_conflict_title)) },
            text = { Text(stringResource(R.string.book_conflict_text, show(c.theirs), show(c.mine))) },
            confirmButton = {
                TextButton(onClick = {
                    conflict = null
                    state = latest
                    // keep mine on the latest book (an Update of an entry removed meanwhile becomes an Add)
                    val again = if (edit is BookEdit.Update && c.theirs == null) BookEdit.Add(edit.entry) else edit
                    save(again)
                }) { Text(stringResource(R.string.book_keep_mine)) }
            },
            dismissButton = {
                TextButton(onClick = { conflict = null; state = latest; draft = null }) { Text(stringResource(R.string.book_use_theirs)) }
            },
        )
    }
    deleting?.let { nick ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.book_delete_title, nick)) },
            text = { Text(stringResource(R.string.book_delete_text)) },
            confirmButton = { TextButton(onClick = { deleting = null; save(BookEdit.Delete(nick)) }) { Text(stringResource(R.string.book_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.book_cancel)) } },
        )
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp)) {
        notice?.let { Text(it) }
        if (busy) Text(stringResource(R.string.copy_reading))
        val current = settings ?: return@Column
        val chosen = book
        val d = draft
        when {
            chosen == null -> {
                val books = current.alpineBooks()
                if (books.isEmpty()) Text(stringResource(R.string.book_none))
                for (b in books) {
                    val label = if (b.second) context.getString(R.string.copy_read_only, b.first) else b.first
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
                                notice = e.message ?: context.getString(R.string.copy_read_failed)
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
                OutlinedTextField(nick, { nick = it }, label = { Text(stringResource(R.string.book_nickname)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(full, { full = it }, label = { Text(stringResource(R.string.book_fullname)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(addr, { addr = it }, label = { Text(stringResource(R.string.book_address)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(fcc, { fcc = it }, label = { Text(stringResource(R.string.book_fcc)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(comments, { comments = it }, label = { Text(stringResource(R.string.book_comments)) }, modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(onClick = { draft = null }, enabled = !busy) { Text(stringResource(R.string.book_cancel)) }
                    TextButton(onClick = {
                        val e = AlpineEntry(nick, full, addr, fcc, comments)
                        save(if (d.original == null) BookEdit.Add(e) else BookEdit.Update(d.original, e))
                    }, enabled = !busy) { Text(stringResource(R.string.book_save)) }
                }
            }
            else -> {
                val st = state
                Text(chosen.first)
                Row {
                    TextButton(onClick = { book = null; state = null; notice = null }, enabled = !busy) { Text(stringResource(R.string.reader_back)) }
                    if (!chosen.second && st != null) {
                        TextButton(onClick = { draft = Draft(null, AlpineEntry("", "", "", "", "")) }, enabled = !busy) {
                            Text(stringResource(R.string.book_add))
                        }
                    }
                }
                for (e in st?.entries.orEmpty()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${e.nickname}  ${e.fullname}  ${e.address}", Modifier.weight(1f))
                        if (!chosen.second) {
                            TextButton(onClick = { draft = Draft(e.nickname, e) }, enabled = !busy) { Text(stringResource(R.string.book_edit)) }
                            TextButton(onClick = { deleting = e.nickname }, enabled = !busy) { Text(stringResource(R.string.book_delete)) }
                        }
                    }
                }
            }
        }
    }
}
