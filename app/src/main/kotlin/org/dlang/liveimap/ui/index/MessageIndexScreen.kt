package org.dlang.liveimap.ui.index

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.FolderView
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.SwipeBinding
import org.dlang.liveimap.ui.FlagMarks

private val indexFlags = listOf("\\Seen", "\\Answered", "\\Flagged", "\\Deleted")

private class SnapshotSync {
    var block: () -> Unit = {}
}

@Composable
fun MessageIndexScreen(
    mailbox: String,
    onOpen: (Long) -> Unit,
    onCompose: (ComposeSeed) -> Unit,
    onBack: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    val session = remember { mailSession() }
    val model = remember(session, store, mailbox) { IndexModel(session, store, mailbox) }
    val scope = rememberCoroutineScope()
    val gate = remember { Mutex() }
    val sync = remember { SnapshotSync() }
    val listState = rememberLazyListState()
    var rows by remember { mutableStateOf<List<IndexRow>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf(FolderView(SortKey.Arrival, true)) }
    var account by remember { mutableStateOf(AccountSettings()) }
    var menuKeys by remember { mutableStateOf(model.menuKeys) }
    var anchorPage by remember { mutableStateOf(0) }
    var connected by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var multiSelect by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<List<Long>>(emptyList()) }
    var flagUid by remember { mutableStateOf<Long?>(null) }
    val leftToRight = LocalLayoutDirection.current == LayoutDirection.Ltr
    val anchorState = rememberUpdatedState(anchorPage)
    val allowNewer = remember { mutableStateOf(true) }

    sync.block = {
        rows = model.rows
        notice = model.notice
        view = model.view
        account = model.account
        menuKeys = model.menuKeys
        anchorPage = model.anchorPage
    }

    fun pull() = sync.block()

    DisposableEffect(session) {
        onDispose { session.close() }
    }

    LaunchedEffect(session, mailbox) {
        val watchNow = gate.withLock {
            val settings = try {
                store.load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = error.message ?: "not connected"
                return@withLock false
            }
            try {
                store.password()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = error.message ?: "not connected"
                return@withLock false
            }
            val opened = try {
                session.open(settings)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                notice = error.text
                return@withLock false
            }
            when (opened) {
                is OpenResult.Rejected -> {
                    notice = opened.capabilities
                    return@withLock false
                }
                is OpenResult.Failed -> {
                    notice = opened.text
                    return@withLock false
                }
                OpenResult.Connected -> Unit
            }
            model.loadWindow()
            pull()
            connected = true
            true
        }
        if (!watchNow) return@LaunchedEffect
        try {
            model.watch { change ->
                scope.launch {
                    gate.withLock {
                        model.applyChange(change)
                        pull()
                    }
                    if (change !is MailboxChange.Flags && model.rows.isNotEmpty()) {
                        listState.scrollToItem(0)
                    }
                }
            }
            awaitCancellation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: MailFailure) {
            notice = error.text
        } finally {
            withContext(NonCancellable) {
                try {
                    model.stopWatch()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: MailFailure) {
                }
            }
        }
    }

    LaunchedEffect(listState, connected) {
        if (!connected) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            val before = model.anchorPage
            gate.withLock {
                model.onFirstVisible(index)
                pull()
            }
            if (model.anchorPage != before && model.rows.isNotEmpty()) {
                listState.scrollToItem(0)
            }
        }
    }

    val newerConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f) allowNewer.value = true
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && allowNewer.value && anchorState.value > 0 && !listState.canScrollBackward) {
                    allowNewer.value = false
                    scope.launch {
                        val before = model.anchorPage
                        gate.withLock {
                            model.revealNewer()
                            sync.block()
                        }
                        if (model.anchorPage < before && model.rows.isNotEmpty()) {
                            val target = (IndexPageSize - 1).coerceAtMost(model.rows.lastIndex)
                            if (target >= 0) listState.scrollToItem(target)
                        }
                    }
                }
                return Offset.Zero
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        val message = notice
        if (message != null) {
            Text(text = message, modifier = Modifier.padding(8.dp))
        }
        if (connected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
                Text(
                    text = mailbox,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { searchVisible = true }) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                    )
                }
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.List,
                                contentDescription = "Sort",
                            )
                        }
                        Text(
                            text = sortShortLabel(view.key),
                            modifier = Modifier.clickable { menuOpen = true },
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        menuKeys.forEach { key ->
                            DropdownMenuItem(
                                text = {
                                    Text(if (key == view.key) "[${sortShortLabel(key)}]" else sortShortLabel(key))
                                },
                                onClick = {
                                    menuOpen = false
                                    query = ""
                                    scope.launch {
                                        gate.withLock {
                                            model.applyView(FolderView(key, view.newestFirst))
                                            pull()
                                        }
                                        if (model.rows.isNotEmpty()) listState.scrollToItem(0)
                                    }
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (view.newestFirst) "Newest first" else "Oldest first") },
                            onClick = {
                                menuOpen = false
                                query = ""
                                scope.launch {
                                    gate.withLock {
                                        model.applyView(view.copy(newestFirst = !view.newestFirst))
                                        pull()
                                    }
                                    if (model.rows.isNotEmpty()) listState.scrollToItem(0)
                                }
                            },
                        )
                    }
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            gate.withLock {
                                model.expunge()
                                pull()
                            }
                            if (model.rows.isNotEmpty()) listState.scrollToItem(0)
                        }
                    },
                ) { Text("Expunge") }
            }
            if (searchVisible) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { next ->
                        val cleared = next.isEmpty() && query.isNotEmpty()
                        query = next
                        if (cleared) {
                            searchVisible = false
                            scope.launch {
                                gate.withLock {
                                    model.applySearch("")
                                    pull()
                                }
                                if (model.rows.isNotEmpty()) listState.scrollToItem(0)
                            }
                        }
                    },
                    label = { Text("Search") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            scope.launch {
                                gate.withLock {
                                    model.applySearch(query)
                                    pull()
                                }
                                if (model.rows.isNotEmpty()) listState.scrollToItem(0)
                            }
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )
            }
            if (multiSelect) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(
                        onClick = {
                            multiSelect = false
                            selected = emptyList()
                        },
                    ) { Text("Cancel") }
                    indexFlags.forEach { flag ->
                        TextButton(
                            onClick = {
                                scope.launch {
                                    gate.withLock {
                                        model.changeFlags(selected, setOf(flag), emptySet())
                                        pull()
                                    }
                                }
                            },
                        ) { Text("Set $flag") }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    gate.withLock {
                                        model.changeFlags(selected, emptySet(), setOf(flag))
                                        pull()
                                    }
                                }
                            },
                        ) { Text("Clear $flag") }
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                gate.withLock {
                                    model.moveMessages(selected, barMoveMailbox(account))
                                    pull()
                                }
                            }
                        },
                    ) { Text("Move") }
                    TextButton(
                        onClick = {
                            scope.launch {
                                gate.withLock {
                                    model.deleteMessages(selected)
                                    pull()
                                }
                            }
                        },
                    ) { Text("Delete") }
                    TextButton(onClick = { onCompose(model.bounceSeed(selected)) }) { Text("Bounce") }
                }
            }
            val flagsFor = flagUid
            if (flagsFor != null) {
                Text("Flags", modifier = Modifier.padding(horizontal = 8.dp))
                indexFlags.forEach { flag ->
                    Row {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    gate.withLock {
                                        model.changeFlags(listOf(flagsFor), setOf(flag), emptySet())
                                        pull()
                                    }
                                }
                            },
                        ) { Text("Set $flag") }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    gate.withLock {
                                        model.changeFlags(listOf(flagsFor), emptySet(), setOf(flag))
                                        pull()
                                    }
                                }
                            },
                        ) { Text("Clear $flag") }
                    }
                }
                TextButton(onClick = { flagUid = null }) { Text("Close") }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .nestedScroll(newerConnection),
            ) {
                items(rows, key = { it.uid }) { row ->
                    IndexMessageRow(
                        row = row,
                        account = account,
                        selected = row.uid in selected,
                        multiSelect = multiSelect,
                        leftToRight = leftToRight,
                        onClick = {
                            if (multiSelect) {
                                selected = if (row.uid in selected) selected - row.uid else selected + row.uid
                            } else {
                                val seed = model.openSeed(row.uid)
                                if (seed != null) onCompose(seed) else onOpen(row.uid)
                            }
                        },
                        onLongPress = {
                            multiSelect = true
                            if (row.uid !in selected) selected = selected + row.uid
                        },
                        onSwipe = { binding ->
                            gate.withLock {
                                when (val command = model.performSwipe(row.uid, binding)) {
                                    is IndexCommand.Compose -> onCompose(command.seed)
                                    is IndexCommand.ShowFlags -> flagUid = command.uid
                                    IndexCommand.None -> Unit
                                }
                                pull()
                            }
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IndexMessageRow(
    row: IndexRow,
    account: AccountSettings,
    selected: Boolean,
    multiSelect: Boolean,
    leftToRight: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onSwipe: suspend (SwipeBinding) -> Unit,
) {
    var width by remember { mutableIntStateOf(0) }
    val holder = remember { SwipeBoxHolder() }
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * SwipeWidthPercent / 100f },
        confirmValueChange = { target ->
            if (target == SwipeToDismissBoxValue.Settled) {
                true
            } else if (multiSelect) {
                false
            } else {
                val box = holder.state ?: return@rememberSwipeToDismissBoxState false
                val offset = runCatching { box.requireOffset() }.getOrDefault(0f)
                swipeReached(offset, width.toFloat())
            }
        },
    )
    holder.state = dismissState
    LaunchedEffect(dismissState.currentValue) {
        val value = dismissState.currentValue
        if (value == SwipeToDismissBoxValue.Settled || multiSelect) return@LaunchedEffect
        val binding = indexSwipeBinding(
            offsetPx = dismissOffset(value, width.toFloat(), leftToRight),
            widthPx = width.toFloat().coerceAtLeast(1f),
            leftToRight = leftToRight,
            multiSelect = false,
            trailing = account.swipeTrailing,
            leading = account.swipeLeading,
        )
        if (binding != null) onSwipe(binding)
        dismissState.reset()
    }
    Box(Modifier.onSizeChanged { width = it.width }.fillMaxWidth()) {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = !multiSelect,
            enableDismissFromEndToStart = !multiSelect,
            gesturesEnabled = !multiSelect,
            backgroundContent = {
                val direction = dismissState.targetValue
                val binding = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> account.swipeLeading
                    SwipeToDismissBoxValue.EndToStart -> account.swipeTrailing
                    SwipeToDismissBoxValue.Settled -> null
                }
                if (binding != null) {
                    Box(Modifier.fillMaxSize()) {
                        Text(
                            text = binding.action.name,
                            modifier = Modifier
                                .align(
                                    if (direction == SwipeToDismissBoxValue.StartToEnd) {
                                        Alignment.CenterStart
                                    } else {
                                        Alignment.CenterEnd
                                    },
                                )
                                .padding(horizontal = 8.dp),
                        )
                    }
                }
            },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .combinedClickable(onLongClick = onLongPress, onClick = onClick)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val from = if (selected) "selected ${row.from}" else row.from
                    Text(
                        text = from,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(text = row.envelopeDate, maxLines = 1, overflow = TextOverflow.Clip)
                }
                Text(text = row.subject, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FlagMarks(row.flags)
                val lines = previewLineCount(account.density)
                val preview = row.preview
                if (lines > 0 && preview != null) {
                    Text(text = preview, maxLines = lines, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun sortShortLabel(key: SortKey): String = when (key) {
    SortKey.ThreadReferences -> "Thread"
    SortKey.ThreadOrderedSubject -> "Ordered"
    else -> key.name
}

private class SwipeBoxHolder {
    var state: SwipeToDismissBoxState? = null
}

private fun dismissOffset(value: SwipeToDismissBoxValue, widthPx: Float, leftToRight: Boolean): Float {
    val span = if (widthPx > 0f) widthPx else 1f
    return when (value) {
        SwipeToDismissBoxValue.EndToStart -> if (leftToRight) -span else span
        SwipeToDismissBoxValue.StartToEnd -> if (leftToRight) span else -span
        SwipeToDismissBoxValue.Settled -> 0f
    }
}
