package org.dlang.liveimap.ui.folder

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.dlang.liveimap.R
import org.dlang.liveimap.jmap.JmapFailure
import org.dlang.liveimap.jmap.JmapFolderRow
import org.dlang.liveimap.jmap.JmapFolderScreenModel
import org.dlang.liveimap.jmap.JmapListPush
import org.dlang.liveimap.jmap.JmapMessage
import org.dlang.liveimap.jmap.JmapMessagePage
import org.dlang.liveimap.jmap.jmapFolderLabel
import org.dlang.liveimap.jmap.jmapMessageLine
import org.dlang.liveimap.jmap.jmapMessageListKeeps
import org.dlang.liveimap.jmap.jmapReadMessageListPush

@Composable
fun JmapFolderList(
    rows: List<JmapFolderRow>,
    onToggle: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    Column {
        rows.forEachIndexed { index, row ->
            Button(
                onClick = {
                    if (row.hasChildren) onToggle(row.id) else onOpen(row.id)
                },
            ) {
                Text(jmapFolderLabel(rows, index))
            }
        }
    }
}

@Composable
fun JmapFolderRoute(
    model: JmapFolderScreenModel,
    onGiveUp: () -> Unit,
) {
    var rows by remember(model) { mutableStateOf(model.rows) }
    var page by remember(model) { mutableStateOf<JmapMessagePage?>(null) }
    var opened by remember(model) { mutableStateOf<JmapMessage?>(null) }
    var body by remember(model) { mutableStateOf<String?>(null) }
    var mailboxId by remember(model) { mutableStateOf<String?>(null) }
    var searchText by remember(model) { mutableStateOf("") }
    var searchMore by remember(model) { mutableStateOf("") }
    var searchOperator by remember(model) { mutableStateOf("AND") }
    var openingMessage by remember(model) { mutableStateOf(false) }
    var listEpoch by remember(model) { mutableIntStateOf(0) }
    val listPush = remember(model) { JmapListPush() }
    val gate = remember(model) { Mutex() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) {
        try {
            gate.withLock {
                withContext(Dispatchers.IO) { model.loadTop() }
            }
            rows = model.rows
        } catch (error: CancellationException) {
            throw error
        } catch (_: JmapFailure) {
            onGiveUp()
        }
    }
    val open = page
    if (open == null) {
        JmapFolderList(
            rows = rows,
            onToggle = { id ->
                scope.launch {
                    try {
                        gate.withLock {
                            withContext(Dispatchers.IO) { model.toggle(id) }
                        }
                        rows = model.rows
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: JmapFailure) {
                        onGiveUp()
                    }
                }
            },
            onOpen = { id ->
                scope.launch {
                    try {
                        val loaded = gate.withLock {
                            withContext(Dispatchers.IO) { model.messages(id) }
                        }
                        opened = null
                        body = null
                        openingMessage = false
                        listPush.clear()
                        listEpoch += 1
                        page = loaded
                        mailboxId = id
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: JmapFailure) {
                        opened = null
                        body = null
                        page = null
                        mailboxId = null
                    }
                }
            },
        )
    } else {
        val shown = opened
        val text = body
        Column {
            Button(onClick = {
                listPush.clear()
                openingMessage = false
                page = null
                opened = null
                body = null
                mailboxId = null
                searchText = ""
                searchMore = ""
                searchOperator = "AND"
            }) {
                Text(stringResource(R.string.folders_title))
            }
            if (shown == null || text == null) {
                if (!openingMessage) {
                    val epoch = listEpoch
                    LaunchedEffect(model, epoch) {
                        val listed = page ?: return@LaunchedEffect
                        val box = mailboxId
                        try {
                            // F68: the long-poll waits outside the gate, so other actions are not blocked by it.
                            val next = run {
                                withContext(Dispatchers.IO) {
                                    jmapReadMessageListPush(
                                        listed,
                                        box,
                                        listPush,
                                        apply = { state ->
                                            model.applyPush(state, model.pushOpen())
                                        },
                                        reload = { id -> model.messages(id) },
                                        stillOpen = {
                                            listEpoch == epoch && !openingMessage && page != null
                                        },
                                    )
                                }
                            }
                            if (listEpoch != epoch || openingMessage || page == null) {
                                return@LaunchedEffect
                            }
                            page = next
                        } catch (error: CancellationException) {
                            throw error
                        } catch (failure: JmapFailure) {
                            val current = page
                            if (current != null) page = jmapMessageListKeeps(current, failure)
                        }
                    }
                }
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = searchMore,
                    onValueChange = { searchMore = it },
                    singleLine = true,
                )
                Button(onClick = { searchOperator = "AND" }) {
                    Text(stringResource(R.string.index_search_and))
                }
                Button(onClick = { searchOperator = "OR" }) {
                    Text(stringResource(R.string.index_search_or))
                }
                Button(onClick = { searchOperator = "NOT" }) {
                    Text(stringResource(R.string.index_search_not))
                }
                Button(onClick = {
                    val first = searchText
                    val second = searchMore
                    val op = searchOperator
                    val id = mailboxId
                    if (first.isBlank() || id == null) return@Button
                    val terms = if (op == "NOT" || second.isBlank()) {
                        listOf(first)
                    } else {
                        listOf(first, second)
                    }
                    scope.launch {
                        try {
                            val loaded = gate.withLock {
                                withContext(Dispatchers.IO) { model.searchSteps(id, op, terms) }
                            }
                            if (page == null) return@launch
                            opened = null
                            body = null
                            openingMessage = false
                            listPush.clear()
                            listEpoch += 1
                            page = loaded
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: JmapFailure) {
                        }
                    }
                }) {
                    Text(stringResource(R.string.index_search))
                }
                open.messages.forEach { message ->
                    Button(onClick = {
                        openingMessage = true
                        scope.launch {
                            try {
                                val loaded = gate.withLock {
                                    withContext(Dispatchers.IO) { model.body(message.id) }
                                }
                                if (page == null) return@launch
                                opened = message
                                body = loaded
                                openingMessage = false
                                if (message.unread) {
                                    gate.withLock {
                                        withContext(Dispatchers.IO) { model.markSeen(message.id) }
                                    }
                                    val current = page
                                    if (current != null) {
                                        page = current.copy(
                                            messages = current.messages.map { item ->
                                                if (item.id == message.id) {
                                                    item.copy(unread = false)
                                                } else {
                                                    item
                                                }
                                            },
                                        )
                                    }
                                }
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: JmapFailure) {
                                openingMessage = false
                            }
                        }
                    }) {
                        Text(jmapMessageLine(message))
                    }
                }
            } else {
                Button(onClick = {
                    openingMessage = false
                    opened = null
                    body = null
                }) {
                    Text(jmapMessageLine(shown))
                }
                var confirmDelete by remember(shown.id) { mutableStateOf(false) }
                var deleteNotice by remember(shown.id) { mutableStateOf<String?>(null) }
                deleteNotice?.let { Text(it, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
                if (confirmDelete) {
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        text = { Text(stringResource(R.string.jmap_delete_confirm)) },
                        confirmButton = {
                            org.dlang.liveimap.ui.TextButton(onClick = {
                                confirmDelete = false
                                val emailId = shown.id
                                val fromMailboxId = mailboxId.orEmpty()
                                scope.launch {
                                    try {
                                        gate.withLock {
                                            withContext(Dispatchers.IO) {
                                                model.deleteMessage(emailId, fromMailboxId)
                                            }
                                        }
                                        val current = page ?: return@launch
                                        opened = null
                                        body = null
                                        page = current.copy(
                                            messages = current.messages.filter { it.id != emailId },
                                        )
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (failure: JmapFailure) {
                                        deleteNotice = failure.text // F68: failures are shown, not swallowed
                                    }
                                }
                            }) { Text(stringResource(R.string.index_delete)) }
                        },
                        dismissButton = {
                            org.dlang.liveimap.ui.TextButton(onClick = { confirmDelete = false }) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        },
                    )
                }
                Button(onClick = { confirmDelete = true }) {
                    Text(stringResource(R.string.index_delete))
                }
                Text(text)
            }
        }
    }
}
