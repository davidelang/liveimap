package org.dlang.liveimap.ui.folder

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.FolderFavorite
import org.dlang.liveimap.ui.UiDims

@Composable
fun FolderListScreen(
    onOpenMailbox: (String) -> Unit,
    focusMailbox: String? = null,
    focusToken: Int = 0,
) {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    val session = remember { mailSession() }
    val model = remember(session, store) { FolderListModel(session, store) }
    val scope = rememberCoroutineScope()
    val gate = remember { Mutex() }
    val listState = rememberLazyListState()
    var rows by remember { mutableStateOf<List<FolderRow>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var stopped by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }

    LaunchedEffect(session) {
        gate.withLock {
            val settings = try {
                store.load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = error.message ?: "not connected"
                stopped = true
                return@withLock
            }
            try {
                store.password()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = error.message ?: "not connected"
                stopped = true
                return@withLock
            }
            val opened = try {
                session.open(settings)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                notice = error.text
                stopped = true
                return@withLock
            }
            when (opened) {
                is OpenResult.Rejected -> {
                    notice = opened.capabilities
                    stopped = true
                    return@withLock
                }
                is OpenResult.Failed -> {
                    notice = opened.text
                    stopped = true
                    return@withLock
                }
                OpenResult.Connected -> Unit
            }
            try {
                rows = model.loadLevel()
                ready = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                notice = error.text
            }
        }
    }

    LaunchedEffect(focusToken, ready) {
        val target = focusMailbox
        if (!ready || stopped || target == null || focusToken == 0) return@LaunchedEffect
        var scrollIndex = -1
        gate.withLock {
            if (stopped) return@withLock
            val listed = try {
                model.loadLevel()
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                notice = error.text
                null
            }
            if (listed != null) {
                rows = listed
                notice = null
                scrollIndex = listed.indexOfFirst { it.mailbox == target }
            }
        }
        if (scrollIndex >= 0) listState.scrollToItem(scrollIndex)
    }

    LaunchedEffect(rows, stopped) {
        if (stopped || rows.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { indices ->
                if (indices.isEmpty()) return@collect
                gate.withLock {
                    if (stopped) return@withLock
                    val current = rows
                    val visible = indices.mapNotNull { current.getOrNull(it) }.filter { !it.namespaceRoot }
                    if (visible.isEmpty()) return@withLock
                    val updated = try {
                        model.refreshVisibleCounts(visible)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: MailFailure) {
                        notice = error.text
                        return@withLock
                    }
                    val byMailbox = updated.associateBy { it.mailbox }
                    val merged = current.map { row ->
                        val next = byMailbox[row.mailbox] ?: return@map row
                        if (next.messages == row.messages) row else row.copy(messages = next.messages)
                    }
                    if (merged != current) rows = merged
                }
            }
    }

    fun toggleFavorite(node: Boolean, row: FolderRow) {
        scope.launch {
            gate.withLock {
                if (stopped) return@withLock
                val settings = try {
                    store.load()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    notice = error.message ?: "not connected"
                    return@withLock
                }
                val favorite = FolderFavorite(node, row.mailbox, row.delimiter)
                val favorites = if (settings.favorites.any { it.node == node && it.mailbox == row.mailbox }) {
                    settings.favorites.filterNot { it.node == node && it.mailbox == row.mailbox }
                } else {
                    settings.favorites + favorite
                }
                store.save(settings.copy(favorites = favorites))
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        val message = notice
        if (message != null) {
            Text(text = message, modifier = Modifier.padding(8.dp))
        }
        if (!stopped) {
            val reserveMessages = rows.any { it.messages != null }
            val reserveUnseen = rows.any { it.unseen != null }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
            ) {
                items(rows) { row ->
                    FolderListRow(
                        row = row,
                        reserveMessages = reserveMessages,
                        reserveUnseen = reserveUnseen,
                        onOpen = { if (!row.namespaceRoot) onOpenMailbox(row.mailbox) },
                        onToggle = {
                            scope.launch {
                                gate.withLock {
                                    if (stopped) return@withLock
                                    try {
                                        model.toggleExpanded(row.mailbox)
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: MailFailure) {
                                        notice = error.text
                                        return@withLock
                                    }
                                    val listed = try {
                                        model.loadLevel()
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: MailFailure) {
                                        notice = error.text
                                        null
                                    }
                                    if (listed != null) {
                                        rows = listed
                                        notice = null
                                    }
                                }
                            }
                        },
                        onLeafLongPress = { toggleFavorite(row.namespaceRoot, row) },
                        onNodeLongPress = { toggleFavorite(true, row) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderListRow(
    row: FolderRow,
    reserveMessages: Boolean,
    reserveUnseen: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onLeafLongPress: () -> Unit,
    onNodeLongPress: () -> Unit,
) {
    val shownLeaf = if (row.namespaceRoot && row.mailbox.isEmpty()) "(empty prefix)" else row.leaf
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = UiDims.expanderWidth * row.depth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val slot = if (row.hasChildren) {
            Modifier
                .width(UiDims.expanderWidth)
                .combinedClickable(onClick = onToggle, onLongClick = onNodeLongPress)
        } else {
            Modifier.width(UiDims.expanderWidth)
        }
        Box(modifier = slot, contentAlignment = Alignment.Center) {
            if (row.hasChildren) {
                Text(if (row.expanded) "-" else "+")
            }
        }
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = shownLeaf,
                modifier = Modifier
                    .combinedClickable(onClick = onOpen, onLongClick = onLeafLongPress)
                    .padding(vertical = 8.dp),
            )
            val use = row.specialUse
            if (use != null) {
                Text(text = use, modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp))
            }
        }
        if (reserveMessages) {
            Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
                val messages = row.messages
                if (messages != null) {
                    Text(text = messages.toString(), modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
        if (reserveUnseen) {
            Box(modifier = Modifier.width(48.dp), contentAlignment = Alignment.CenterEnd) {
                val unseen = row.unseen
                if (unseen != null) {
                    Text(text = unseen.toString(), modifier = Modifier.padding(vertical = 8.dp))
                }
            }
        }
    }
}
