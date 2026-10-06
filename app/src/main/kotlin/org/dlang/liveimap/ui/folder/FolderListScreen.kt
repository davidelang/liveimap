package org.dlang.liveimap.ui.folder

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dlang.liveimap.R
import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.SettingsStore
import org.dlang.liveimap.settings.FolderFavorite
import org.dlang.liveimap.ui.ConnectionStatusStrip
import org.dlang.liveimap.ui.DebugConnectionStatus
import org.dlang.liveimap.ui.compose.readCopies
import org.dlang.liveimap.ui.mailBarInsets
import org.dlang.liveimap.ui.mailScreenInsets

internal class FolderScreenHeld : ViewModel() {
    var model: FolderListModel? = null
    val rowsState = mutableStateOf<List<FolderRow>>(emptyList())
    var levelReady: Boolean = false

    fun bind(session: MailSession, store: SettingsStore): FolderListModel {
        val current = model
        if (current != null) return current
        val created = FolderListModel(session, store)
        model = created
        return created
    }

    fun dropLoaded() {
        model = null
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(
    onOpenMailbox: (String) -> Unit,
    onCompose: (ComposeSeed) -> Unit,
    onOpenUnsent: () -> Unit,
    onOpenHelp: () -> Unit,
    focusMailbox: String? = null,
    focusToken: Int = 0,
    onOpenDrawer: (() -> Unit)? = null,
) {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    val session = remember { mailSession() }
    val connectionState by session.connectionState.collectAsState()
    val debugStatus by TrafficLog.debugStatus.collectAsState()
    val held = viewModel<FolderScreenHeld>()
    val model = held.bind(session, store)
    val host = LocalContext.current.hostActivity()
    DisposableEffect(held) {
        onDispose {
            if (host?.isChangingConfigurations != true) held.dropLoaded()
        }
    }
    val scope = rememberCoroutineScope()
    val gate = remember { Mutex() }
    val listState = rememberLazyListState()
    var rows by held.rowsState
    var loading by remember { mutableStateOf(!held.levelReady) }
    var banner by remember { mutableStateOf<String?>(null) }
    var loadToken by remember { mutableIntStateOf(0) }
    var snackEvent by remember { mutableIntStateOf(0) }
    var snackMessage by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    var stopped by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(held.levelReady) }
    var unsentCount by remember { mutableIntStateOf(0) }
    var sentMailbox by remember { mutableStateOf("") }
    var postponedMailbox by remember { mutableStateOf("") }
    var spamMailbox by remember { mutableStateOf("") }
    var addressBookMailbox by remember { mutableStateOf("") }
    var imapHost by remember { mutableStateOf("") }
    var hostKnown by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf<List<FolderFavorite>>(emptyList()) }
    var showUnreadCounts by remember { mutableStateOf(false) }
    var folderQuery by remember { mutableStateOf("") }
    var moreMenu by remember { mutableStateOf(false) }
    var refreshListed by remember { mutableStateOf(false) }
    val emptyPrefixLabel = stringResource(R.string.folders_empty_prefix)
    val retryLabel = stringResource(R.string.folders_retry)

    fun postSnack(text: String) {
        snackMessage = text
        snackEvent += 1
    }

    LaunchedEffect(snackEvent) {
        if (snackEvent == 0) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = snackMessage,
            actionLabel = retryLabel,
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
        val reuse = held.levelReady && !refreshListed && loadToken == 0
        unsentCount = readCopies(appContext).size
        if (!reuse) {
            loading = true
            banner = null
            stopped = false
        }
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
            imapHost = settings.imapHost
            hostKnown = true
            sentMailbox = settings.sentMailbox
            postponedMailbox = settings.postponedMailbox
            spamMailbox = settings.spamMailbox
            addressBookMailbox = settings.addressBookMailbox
            favorites = settings.favorites
            showUnreadCounts = settings.showUnreadCounts
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
                if (!reuse) {
                    if (refreshListed) {
                        model.refreshLevels()
                        refreshListed = false
                    }
                    rows = model.loadLevel()
                    held.levelReady = true
                }
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
                scrollIndex = folderRowsMatchingName(listed, folderQuery, emptyPrefixLabel).indexOfFirst { it.mailbox == target }
            }
        }
        if (scrollIndex >= 0) listState.scrollToItem(scrollIndex)
    }

    LaunchedEffect(rows, stopped, folderQuery) {
        if (stopped || rows.isEmpty()) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.map { it.index } }
            .distinctUntilChanged()
            .collect { indices ->
                if (indices.isEmpty()) return@collect
                gate.withLock {
                    if (stopped) return@withLock
                    val current = rows
                    val listed = folderRowsMatchingName(current, folderQuery, emptyPrefixLabel)
                    val visible = indices.mapNotNull { listed.getOrNull(it) }.filter { !it.namespaceRoot }
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
                val nextFavorites = if (settings.favorites.any { it.node == node && it.mailbox == row.mailbox }) {
                    settings.favorites.filterNot { it.node == node && it.mailbox == row.mailbox }
                } else {
                    settings.favorites + favorite
                }
                store.save(settings.copy(favorites = nextFavorites))
                favorites = nextFavorites
            }
        }
    }

    fun collapseAll() {
        scope.launch {
            gate.withLock {
                if (stopped) return@withLock
                model.collapseAll()
                // Rebuilt from the level cache. Does not send LIST.
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

    fun saveDefaultView() {
        scope.launch {
            gate.withLock {
                if (stopped) return@withLock
                try {
                    model.saveDefaultView()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    postSnack(error.message ?: "not connected")
                }
            }
        }
    }

    fun resetToDefaultView() {
        scope.launch {
            gate.withLock {
                if (stopped) return@withLock
                try {
                    model.resetToDefault()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    postSnack(error.message ?: "not connected")
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
                if (listed != null) rows = listed
            }
        }
    }

    fun alwaysExpand(mailbox: String) {
        scope.launch {
            gate.withLock {
                if (stopped) return@withLock
                try {
                    model.alwaysExpand(mailbox)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    postSnack(error.message ?: "not connected")
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
                if (listed != null) rows = listed
            }
        }
    }

    fun dontAlwaysExpand(mailbox: String) {
        scope.launch {
            gate.withLock {
                if (stopped) return@withLock
                try {
                    model.dontAlwaysExpand(mailbox)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    postSnack(error.message ?: "not connected")
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.folders_title)) },
                    navigationIcon = {
                        if (onOpenDrawer != null) {
                            IconButton(onClick = onOpenDrawer) {
                                Icon(
                                    imageVector = Icons.Filled.Menu,
                                    contentDescription = stringResource(R.string.folders_menu),
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (!loading) {
                                refreshListed = true
                                loadToken += 1
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.folders_refresh),
                            )
                        }
                        Box {
                            IconButton(onClick = { moreMenu = true }) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.folders_more),
                                )
                            }
                            DropdownMenu(
                                expanded = moreMenu,
                                onDismissRequest = { moreMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.folders_collapse_all)) },
                                    onClick = {
                                        moreMenu = false
                                        collapseAll()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.folders_save_default)) },
                                    onClick = {
                                        moreMenu = false
                                        saveDefaultView()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.folders_reset_default)) },
                                    onClick = {
                                        moreMenu = false
                                        resetToDefaultView()
                                    },
                                )
                                if (unsentCount > 0) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.folders_unsent)) },
                                        onClick = {
                                            moreMenu = false
                                            onOpenUnsent()
                                        },
                                    )
                                }
                            }
                        }
                    },
                    windowInsets = mailBarInsets(),
                )
                Surface(color = MaterialTheme.colorScheme.surface) {
                    OutlinedTextField(
                        value = folderQuery,
                        onValueChange = { folderQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.folders_search)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = null,
                            )
                        },
                        trailingIcon = {
                            if (folderQuery.isNotEmpty()) {
                                IconButton(onClick = { folderQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.folders_clear_search),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        },
        contentWindowInsets = mailScreenInsets(),
    ) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
    Column(modifier = Modifier.fillMaxSize()) {
        ConnectionStatusStrip(connectionState) {
            scope.launch {
                try {
                    session.resume()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: MailFailure) {
                }
                loadToken += 1
            }
        }
        if (hostKnown && imapHost.isBlank()) {
            TextButton(onClick = onOpenHelp) { Text(stringResource(R.string.help_title)) }
        }
        DebugConnectionStatus(debugStatus)
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
        if (unsentCount > 0) {
            Text(
                text = stringResource(R.string.folders_unsent_count, unsentCount),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenUnsent)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        }
        if (!stopped) {
            // Names already loaded. Does not send LIST.
            val shown = folderRowsMatchingName(rows, folderQuery, emptyPrefixLabel)
            val countStyle = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum")
            val countMeasurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val totalWidth = folderCountColumnWidth(
                countMeasurer,
                countStyle,
                shown.mapNotNull { it.messages?.toString() },
                density,
            )
            val unreadWidth = folderCountColumnWidth(
                countMeasurer,
                countStyle,
                shown.mapNotNull { folderUnreadLabel(showUnreadCounts, it.unseen) },
                density,
            )
            PullToRefreshBox(
                isRefreshing = loading,
                onRefresh = {
                    if (!loading) {
                        refreshListed = true
                        loadToken += 1
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
            if (!loading && banner == null && rows.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
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
                        text = stringResource(R.string.folders_none),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else if (!loading && shown.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.folders_none_match),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown) { row ->
                    val starNode = folderFavoriteNode(row)
                    FolderListRow(
                        row = row,
                        totalWidth = totalWidth,
                        unreadWidth = unreadWidth,
                        countStyle = countStyle,
                        showUnread = showUnreadCounts,
                        favorite = favorites.any { it.node == starNode && it.mailbox == row.mailbox },
                        sentMailbox = sentMailbox,
                        postponedMailbox = postponedMailbox,
                        spamMailbox = spamMailbox,
                        addressBookMailbox = addressBookMailbox,
                        onOpen = { if (!row.namespaceRoot) onOpenMailbox(row.mailbox) },
                        onStar = { toggleFavorite(starNode, row) },
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
                        onAlwaysExpand = { alwaysExpand(row.mailbox) },
                        onDontAlwaysExpand = { dontAlwaysExpand(row.mailbox) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                }
            }
            }
            }
        }
    }
        ExtendedFloatingActionButton(
            text = { Text(stringResource(R.string.folders_compose)) },
            icon = { Icon(imageVector = Icons.Filled.Edit, contentDescription = stringResource(R.string.folders_compose)) },
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
            TextButton(onClick = onRetry) { Text(stringResource(R.string.folders_retry)) }
        }
    }
}

internal fun folderIconKey(
    mailbox: String,
    sentMailbox: String,
    postponedMailbox: String,
    spamMailbox: String,
    addressBookMailbox: String,
    specialUse: String?,
): String {
    if (sentMailbox.isNotEmpty() && mailbox == sentMailbox) return "send"
    if (postponedMailbox.isNotEmpty() && mailbox == postponedMailbox) return "drafts"
    if (spamMailbox.isNotEmpty() && mailbox == spamMailbox) return "report"
    if (addressBookMailbox.isNotEmpty() && mailbox == addressBookMailbox) return "contacts"
    val tokens = specialUse.orEmpty().split(' ').filter { it.isNotEmpty() }
    for (token in listOf("\\Sent", "\\Drafts", "\\Trash", "\\Junk")) {
        if (token in tokens) {
            return when (token) {
                "\\Sent" -> "send"
                "\\Drafts" -> "drafts"
                "\\Trash" -> "delete"
                else -> "report"
            }
        }
    }
    if (mailbox == "INBOX") return "inbox"
    return "folder"
}

internal fun folderDisplayName(row: FolderRow, emptyPrefixLabel: String): String =
    if (row.namespaceRoot && row.mailbox.isEmpty()) emptyPrefixLabel else row.leaf

internal fun folderRowsMatchingName(
    rows: List<FolderRow>,
    query: String,
    emptyPrefixLabel: String,
): List<FolderRow> {
    if (query.isBlank()) return rows
    val needle = query.trim()
    return rows.filter { row ->
        val emptyPrefix = row.namespaceRoot && row.mailbox.isEmpty()
        emptyPrefix || folderDisplayName(row, emptyPrefixLabel).contains(needle, ignoreCase = true)
    }
}

internal fun folderFavoriteNode(row: FolderRow): Boolean = when {
    !row.hasChildren -> row.namespaceRoot
    row.expanded -> false
    else -> true
}

internal fun folderUnreadLabel(showUnreadCounts: Boolean, unseen: Int?): String? {
    if (!showUnreadCounts) return null
    if (unseen == null || unseen <= 0) return null
    return unseen.toString()
}

internal fun folderCountColumnWidth(
    measurer: TextMeasurer,
    style: TextStyle,
    labels: List<String>,
    density: Density,
): Dp {
    var widest = measurer.measure(text = "0", style = style).size.width
    for (label in labels) {
        val width = measurer.measure(text = label, style = style).size.width
        if (width > widest) widest = width
    }
    return with(density) { widest.toDp() }
}

internal fun folderRowDescription(
    shownLeaf: String,
    messagesPhrase: String?,
    unreadPhrase: String?,
    expansionPhrase: String?,
): String = listOfNotNull(shownLeaf, messagesPhrase, unreadPhrase, expansionPhrase).joinToString(", ")

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderListRow(
    row: FolderRow,
    totalWidth: Dp,
    unreadWidth: Dp,
    countStyle: TextStyle,
    showUnread: Boolean,
    favorite: Boolean,
    sentMailbox: String,
    postponedMailbox: String,
    spamMailbox: String,
    addressBookMailbox: String,
    onOpen: () -> Unit,
    onStar: () -> Unit,
    onToggle: () -> Unit,
    onAlwaysExpand: () -> Unit,
    onDontAlwaysExpand: () -> Unit,
) {
    val emptyPrefix = row.namespaceRoot && row.mailbox.isEmpty()
    var rowMenu by remember { mutableStateOf(false) }
    val shownLeaf = folderDisplayName(row, stringResource(R.string.folders_empty_prefix))
    val unreadLabel = folderUnreadLabel(showUnread, row.unseen)
    val messageCount = row.messages
    val messagesPhrase = if (messageCount == null) {
        null
    } else {
        pluralStringResource(R.plurals.folders_messages, messageCount, messageCount)
    }
    val unseenCount = if (unreadLabel == null) null else row.unseen
    val unreadPhrase = if (unseenCount == null) {
        null
    } else {
        pluralStringResource(R.plurals.folders_unread, unseenCount, unseenCount)
    }
    val expansionPhrase = if (!row.hasChildren) {
        null
    } else if (row.expanded) {
        stringResource(R.string.folders_expanded)
    } else {
        stringResource(R.string.folders_collapsed)
    }
    val description = if (emptyPrefix) {
        stringResource(R.string.folders_namespace_empty)
    } else {
        folderRowDescription(shownLeaf, messagesPhrase, unreadPhrase, expansionPhrase)
    }
    val favoriteText = stringResource(
        if (favorite) R.string.folders_remove_favorite else R.string.folders_add_favorite,
    )
    val icon = if (emptyPrefix) {
        Icons.Filled.FolderSpecial
    } else {
        when (
            folderIconKey(
                row.mailbox,
                sentMailbox,
                postponedMailbox,
                spamMailbox,
                addressBookMailbox,
                row.specialUse,
            )
        ) {
            "inbox" -> Icons.Filled.Inbox
            "send" -> Icons.AutoMirrored.Filled.Send
            "drafts" -> Icons.Filled.Drafts
            "report" -> Icons.Filled.Report
            "contacts" -> Icons.Filled.Contacts
            "delete" -> Icons.Filled.Delete
            else -> Icons.Filled.Folder
        }
    }
    val openRowMenu = { rowMenu = true }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp * row.depth, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val slot = if (row.hasChildren) {
            Modifier
                .size(48.dp)
                .combinedClickable(onClick = onToggle, onLongClick = openRowMenu)
        } else {
            Modifier.size(48.dp)
        }
        Box(modifier = slot, contentAlignment = Alignment.Center) {
            if (row.hasChildren) {
                Icon(
                    imageVector = if (row.expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                    contentDescription = if (row.expanded) {
                        stringResource(R.string.folders_collapse, shownLeaf)
                    } else {
                        stringResource(R.string.folders_expand, shownLeaf)
                    },
                )
            }
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .combinedClickable(onClick = onOpen, onLongClick = openRowMenu)
                .clearAndSetSemantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = shownLeaf,
                fontStyle = if (emptyPrefix) FontStyle.Italic else FontStyle.Normal,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
            )
        }
        DropdownMenu(
            expanded = rowMenu,
            onDismissRequest = { rowMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(favoriteText) },
                onClick = {
                    rowMenu = false
                    onStar()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.folders_always_expand)) },
                onClick = {
                    rowMenu = false
                    onAlwaysExpand()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.folders_dont_always_expand)) },
                onClick = {
                    rowMenu = false
                    onDontAlwaysExpand()
                },
            )
        }
        IconButton(onClick = onStar) {
            Icon(
                imageVector = if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = favoriteText,
                tint = if (favorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Text(
            text = row.messages?.toString().orEmpty(),
            style = countStyle,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .width(totalWidth),
        )
        Text(
            text = unreadLabel.orEmpty(),
            style = countStyle,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier
                .padding(start = 8.dp, top = 8.dp, bottom = 8.dp)
                .width(unreadWidth),
        )
    }
}
