package org.dlang.liveimap.ui.folder
import org.dlang.liveimap.i18n.S

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import org.dlang.liveimap.ui.ObscuredTouchFilter
import org.dlang.liveimap.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.SettingsStore
import org.dlang.liveimap.settings.accountCommand
import org.dlang.liveimap.ui.UiDims

internal class MailboxChooserHeld : ViewModel() {
    var model: FolderListModel? = null
    var boundSession: MailSession? = null
    val rowsState = mutableStateOf<List<FolderRow>>(emptyList())
    var levelReady: Boolean = false
    private val turns = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val turnScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        turnScope.launch {
            for (command in turns) {
                try {
                    command()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                }
            }
        }
    }

    private class OnTurn(val owner: MailboxChooserHeld) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<OnTurn>
    }

    suspend fun <T> turn(block: suspend () -> T): T {
        if (coroutineContext[OnTurn]?.owner === this) return block()
        val carried = coroutineContext.minusKey(ContinuationInterceptor)
        val done = CompletableDeferred<T>()
        turns.send {
            try {
                withContext(carried + OnTurn(this@MailboxChooserHeld)) {
                    done.complete(block())
                }
            } catch (error: Throwable) {
                done.completeExceptionally(error)
            }
        }
        return done.await()
    }

    override fun onCleared() {
        turnScope.cancel()
    }

    fun bind(session: MailSession, store: SettingsStore): FolderListModel {
        val current = model
        if (current != null && boundSession === session) return current
        rowsState.value = emptyList()
        levelReady = false
        val created = FolderListModel(session, store)
        model = created
        boundSession = session
        return created
    }

    fun dropLoaded() {
        model = null
        boundSession = null
        rowsState.value = emptyList()
        levelReady = false
    }
}

private fun Context.hostActivity(): Activity? {
    var current: Context = this
    while (true) {
        if (current is Activity) return current
        if (current !is ContextWrapper) return null
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
}

@Composable
internal fun MailboxChooser(
    store: SettingsStore,
    onStored: (Settings) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    /** F103: the folder the messages are in; it is not offered as a target. */
    exclude: String = "",
    /** Prefill only: a SPECIAL-USE/role mark (e.g. "\\Sent") whose folder is offered first; never applied unasked. */
    suggestUse: String = "",
) {
    // F121: the account is fixed per screen (saved with its back-stack entry); a drawer switch never rebinds it
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        if (accountId == null) accountId = store.chosenAccountId()
    }
    val id = accountId
    if (id.isNullOrEmpty()) return
    MailboxChooserLoaded(
        accountId = id,
        store = store, // the caller's store, already bound to its account (F121)
        onStored = onStored,
        onPick = onPick,
        onDismiss = onDismiss,
        exclude = exclude,
        suggestUse = suggestUse,
    )
}

@Composable
private fun MailboxChooserLoaded(
    accountId: String,
    store: SettingsStore,
    onStored: (Settings) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    exclude: String = "",
    suggestUse: String = "",
) {
    val notConnected = S("not connected")
    val session = remember(accountId) { mailSession(accountId) }
    val held = viewModel<MailboxChooserHeld>()
    val model = held.bind(session, store)
    val host = LocalContext.current.hostActivity()
    DisposableEffect(held) {
        onDispose {
            if (host?.isChangingConfigurations != true) held.dropLoaded()
        }
    }
    val scope = rememberCoroutineScope()
    var rows by held.rowsState
    var notice by remember { mutableStateOf<String?>(null) }
    var stopped by remember { mutableStateOf(false) }

    LaunchedEffect(session) {
        if (held.levelReady) return@LaunchedEffect
        held.turn {
            val account = try {
                store.load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = error.message ?: notConnected
                stopped = true
                rows = emptyList()
                return@turn
            }
            val opened = try {
                session.open(account)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                notice = error.text
                stopped = true
                rows = emptyList()
                return@turn
            }
            when (opened) {
                is OpenResult.Rejected -> {
                    notice = opened.capabilities
                    stopped = true
                    rows = emptyList()
                    return@turn
                }
                is OpenResult.Failed -> {
                    notice = opened.text
                    stopped = true
                    rows = emptyList()
                    return@turn
                }
                OpenResult.Connected -> Unit
            }
            try {
                rows = model.loadLevel()
                held.levelReady = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                notice = error.text
                stopped = true
                rows = emptyList()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        ObscuredTouchFilter()
        Surface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(S("Choose mailbox"))
                val message = notice
                if (message != null) {
                    Text(text = message)
                }
                if (!stopped) {
                    val suggested = markedMailbox(rows, suggestUse, { it.mailbox }, { it.specialUse })
                    if (suggested != null) {
                        TextButton(onClick = { onPick(suggested) }) {
                            Text(S("Use %1\$s (marked by the server)", suggested))
                        }
                    }
                    val pickRows = chooserRows(rows, exclude) { it.mailbox }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false),
                    ) {
                        items(pickRows, key = { it.mailbox }) { row ->
                            MailboxPickRow(
                                row = row,
                                onPick = { onPick(row.mailbox) },
                                onToggle = {
                                    scope.launch {
                                        held.turn {
                                            if (stopped) return@turn
                                            try {
                                                store.accountCommand(publish = { loaded ->
                                                    onStored(loaded)
                                                }) {
                                                    model.toggleExpanded(row.mailbox)
                                                    store.load()
                                                }
                                            } catch (error: CancellationException) {
                                                throw error
                                            } catch (error: MailFailure) {
                                                notice = error.text
                                                stopped = true
                                                rows = emptyList()
                                                return@turn
                                            }
                                            val listed = try {
                                                model.loadLevel()
                                            } catch (error: CancellationException) {
                                                throw error
                                            } catch (error: MailFailure) {
                                                notice = error.text
                                                stopped = true
                                                rows = emptyList()
                                                null
                                            }
                                            if (listed != null) {
                                                rows = listed
                                                notice = null
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                TextButton(onClick = onDismiss) { Text(S("Close")) }
            }
        }
    }
}

@Composable
private fun MailboxPickRow(
    row: FolderRow,
    onPick: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = UiDims.expanderWidth * row.depth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val slot = if (row.hasChildren) {
            Modifier
                .width(UiDims.expanderWidth)
                .clickable(onClick = onToggle)
        } else {
            Modifier.width(UiDims.expanderWidth)
        }
        Box(modifier = slot, contentAlignment = Alignment.Center) {
            if (row.hasChildren) {
                Text(if (row.expanded) "-" else "+")
            }
        }
        Text(
            text = row.leaf,
            modifier = Modifier
                .clickable(onClick = onPick)
                .padding(vertical = 8.dp),
        )
    }
}

/** F103: the rows offered as targets: everything but [exclude] (INBOX compared case-insensitively). */
internal fun <T> chooserRows(rows: List<T>, exclude: String, name: (T) -> String): List<T> =
    if (exclude.isEmpty()) rows else rows.filterNot { org.dlang.liveimap.session.sameMailbox(name(it), exclude) }

/** The folder the server marks with [use] (SPECIAL-USE flag or JMAP role, case-insensitive), or null. */
internal fun <T> markedMailbox(rows: List<T>, use: String, name: (T) -> String, specialUse: (T) -> String?): String? {
    if (use.isBlank()) return null
    return rows.firstOrNull { row ->
        specialUse(row).orEmpty().split(' ').any { it.isNotEmpty() && it.equals(use, ignoreCase = true) }
    }?.let(name)
}
