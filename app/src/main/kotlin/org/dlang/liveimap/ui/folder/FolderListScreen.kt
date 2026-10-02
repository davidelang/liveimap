package org.dlang.liveimap.ui.folder

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.FolderFavorite
import org.dlang.liveimap.ui.UiDims

@Composable
fun FolderListScreen(
    onOpenMailbox: (String) -> Unit,
    onCompose: (ComposeSeed) -> Unit,
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
    var loading by remember { mutableStateOf(true) }
    var banner by remember { mutableStateOf<String?>(null) }
    var loadToken by remember { mutableIntStateOf(0) }
    var snackEvent by remember { mutableIntStateOf(0) }
    var snackMessage by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    var stopped by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }

    fun postSnack(text: String) {
        snackMessage = text
        snackEvent += 1
    }

    LaunchedEffect(snackEvent) {
        if (snackEvent == 0) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = snackMessage,
            actionLabel = "Retry",
        )
        if (result == SnackbarResult.ActionPerformed) {
            gate.withLock {
                if (stopped) return@withLock
                val listed = try {
                    model.loadLevel()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    postSnack(error.text)
                    null
                }
                if (listed != null) rows = listed
            }
        }
    }

    LaunchedEffect(session, loadToken) {
        loading = true
        banner = null
        stopped = false
        gate.withLock {
            val settings = try {
                store.load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                banner = error.message ?: "not connected"
                stopped = true
                loading = false
                return@withLock
            }
            try {
                store.password()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                banner = error.message ?: "not connected"
                stopped = true
                loading = false
                return@withLock
            }
            val opened = try {
                session.open(settings)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                banner = error.text
                stopped = true
                loading = false
                return@withLock
            }
            when (opened) {
                is OpenResult.Rejected -> {
                    banner = opened.capabilities
                    stopped = true
                    loading = false
                    return@withLock
                }
                is OpenResult.Failed -> {
                    banner = opened.text
                    stopped = true
                    loading = false
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
                postSnack(error.text)
            }
            loading = false
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
                postSnack(error.text)
                null
            }
            if (listed != null) {
                rows = listed
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
                        postSnack(error.text)
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
                    postSnack(error.message ?: "not connected")
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

    Box(Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (loading && banner == null) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            )
        }
        val shownBanner = banner
        if (shownBanner != null) {
            FailureBanner(message = shownBanner) {
                banner = null
                stopped = false
                loading = true
                loadToken += 1
            }
        }
        if (!stopped) {
            val reserveMessages = rows.any { it.messages != null }
            val reserveUnseen = rows.any { it.unseen != null }
            if (!loading && banner == null && rows.isEmpty()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Inbox,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "No folders",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
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
                                        postSnack(error.text)
                                        return@withLock
                                    }
                                    val listed = try {
                                        model.loadLevel()
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: MailFailure) {
                                        postSnack(error.text)
                                        null
                                    }
                                    if (listed != null) {
                                        rows = listed
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
        ExtendedFloatingActionButton(
            text = { Text("Compose") },
            icon = { Icon(imageVector = Icons.Filled.Edit, contentDescription = "Compose") },
            onClick = { onCompose(ComposeSeed(ComposeKind.New, null, emptyList())) },
            expanded = !listState.isScrollInProgress,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun FailureBanner(message: String, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
            )
            Text(
                text = message,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            TextButton(onClick = onRetry) { Text("Retry") }
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
