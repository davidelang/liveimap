package org.dlang.liveimap.ui.folder
import org.dlang.liveimap.i18n.S
import org.dlang.liveimap.i18n.P

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
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderSpecial
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
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
import org.dlang.liveimap.ui.ObscuredTouchFilter
import org.dlang.liveimap.ui.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dlang.liveimap.ui.help.HelpDropdownMenuItem
import org.dlang.liveimap.ui.help.HelpIconButton
import org.dlang.liveimap.accounts.AccountRestoreGate
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.ui.LocalScreenAccounts
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.extraIdleChanges
import org.dlang.liveimap.session.folderMessageCount
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.alpineBooks
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.SettingsStore
import org.dlang.liveimap.smoke.smokeLaunch
import org.dlang.liveimap.settings.FolderFavorite
import org.dlang.liveimap.ui.ConnectionStatusStrip
import org.dlang.liveimap.ui.DebugStatusIcon
import org.dlang.liveimap.ui.compose.readCopies
import org.dlang.liveimap.ui.mailBarInsets
import org.dlang.liveimap.ui.mailScreenInsets
import org.dlang.liveimap.ui.toolbar.FolderBarAction
import org.dlang.liveimap.ui.toolbar.FolderMenuEntry
import org.dlang.liveimap.ui.toolbar.ToolbarIconRows
import org.dlang.liveimap.ui.toolbar.defaultFolderBar
import org.dlang.liveimap.ui.toolbar.folderMenu
import org.dlang.liveimap.ui.toolbar.toolbarExpandedHeight

internal class FolderScreenHeld : ViewModel() {
    var model: FolderListModel? = null
    var boundSession: MailSession? = null
    val rowsState = mutableStateOf<List<FolderRow>>(emptyList())
    var levelReady: Boolean = false
    var scrollIndex: Int = 0
    var scrollOffset: Int = 0
    private val turns = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val turnScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        turnScope.launch {
            for (command in turns) {
                try {
                    command()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private class OnTurn(val owner: FolderScreenHeld) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<OnTurn>
    }

    /** One folder-list command at a time, on the main thread. */
    suspend fun <T> turn(block: suspend () -> T): T {
        if (coroutineContext[OnTurn]?.owner === this) return block()
        val carried = coroutineContext.minusKey(ContinuationInterceptor)
        val done = CompletableDeferred<T>()
        try {
            turns.send {
                try {
                    withContext(carried + OnTurn(this@FolderScreenHeld)) {
                        done.complete(block())
                    }
                } catch (error: Throwable) {
                    done.completeExceptionally(error)
                }
            }
        } catch (_: ClosedSendChannelException) {
            done.completeExceptionally(CancellationException("folder screen cleared"))
        }
        return done.await()
    }

    override fun onCleared() {
        dropLoaded()
        turnScope.cancel()
        turns.close()
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
        scrollIndex = 0
        scrollOffset = 0
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

private fun folderBarLabel(action: FolderBarAction): String = when (action) {
    FolderBarAction.Refresh -> org.dlang.liveimap.i18n.literal.S("Refresh")
    FolderBarAction.CollapseAll -> org.dlang.liveimap.i18n.literal.S("Collapse all")
    FolderBarAction.SaveDefault -> org.dlang.liveimap.i18n.literal.S("Save as default view")
    FolderBarAction.ResetDefault -> org.dlang.liveimap.i18n.literal.S("Reset to default view")
}

/** Create-account only after restore has finished and the list is still empty. */
internal enum class FolderAccountBody { Pending, Create, Account }

internal fun folderAccountBody(restoreFinished: Boolean, accountIds: Set<String>): FolderAccountBody = when {
    accountIds.isNotEmpty() -> FolderAccountBody.Account
    restoreFinished -> FolderAccountBody.Create
    else -> FolderAccountBody.Pending
}

/** A saved id that is not in [accountIds] is dropped. A saved id that is still an account is kept. */
internal fun folderScreenAccountId(saved: String?, accountIds: Set<String>, chosen: String?): String? {
    if (saved != null && saved in accountIds) return saved
    if (saved == null) return chosen
    if (chosen != null && chosen in accountIds) return chosen
    return null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderListScreen(
    onOpenMailbox: (String) -> Unit,
    onCompose: (ComposeSeed) -> Unit,
    onOpenUnsent: () -> Unit,
    onOpenHelp: () -> Unit,
    onCustomize: () -> Unit = {},
    focusMailbox: String? = null,
    focusToken: Int = 0,
    onOpenDrawer: (() -> Unit)? = null,
    onAddAccount: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenSettings: () -> Unit = onAddAccount,
) {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    // F121: the account is fixed per screen (saved with its back-stack entry); a drawer switch never rebinds it
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    var accountChecked by remember { mutableStateOf(false) }
    var noAccounts by remember { mutableStateOf(false) }
    val restoreFinished = AccountRestoreGate.finished
    LaunchedEffect(restoreFinished) {
        val listed = store.listAccounts().map { it.id }.toSet()
        ensureActive()
        val body = folderAccountBody(restoreFinished, listed)
        noAccounts = body == FolderAccountBody.Create
        accountId = folderScreenAccountId(accountId, listed, store.chosenAccountId())
        accountChecked = body != FolderAccountBody.Pending
        if (body == FolderAccountBody.Account && accountId == null) onOpenDrawer?.invoke()
    }
    val smoke = smokeLaunch(LocalContext.current.hostActivity()?.intent)
    val id = accountId
    if (id.isNullOrEmpty()) {
        if (smoke) {
            Box(Modifier.fillMaxSize().testTag("smoke-folders"))
            return
        }
        // Fresh install: no account yet. Say so and offer setup instead of an empty screen (seen on a Boox).
        if (noAccounts && accountChecked) {
            NoAccountYet(onAddAccount, onOpenDrawer, onOpenUnsent, onCustomize, onOpenHelp, onOpenAbout, onOpenSettings)
        }
        return
    }
    if (smoke) {
        FolderListLoaded(
            accountId = id,
            store = remember(id) { store.forAccount(id) },
            onOpenMailbox = onOpenMailbox,
            onCompose = { seed -> onCompose(seed.copy(accountId = id)) }, // F121
            onOpenUnsent = onOpenUnsent,
            onOpenHelp = onOpenHelp,
            onCustomize = onCustomize,
            focusMailbox = focusMailbox,
            focusToken = focusToken,
            onOpenDrawer = onOpenDrawer,
        )
        return
    }
    // F181: everything below reads and writes this account, never the drawer's active one.
    val bound = remember(id) { store.forAccount(id) }
    FolderListLoaded(
        accountId = id,
        store = bound,
        onOpenMailbox = onOpenMailbox,
        onCompose = { seed -> onCompose(seed.copy(accountId = id)) }, // F121
        onOpenUnsent = onOpenUnsent,
        onOpenHelp = onOpenHelp,
        onCustomize = onCustomize,
        focusMailbox = focusMailbox,
        focusToken = focusToken,
        onOpenDrawer = onOpenDrawer,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderListLoaded(
    accountId: String,
    store: SettingsStore,
    onOpenMailbox: (String) -> Unit,
    onCompose: (ComposeSeed) -> Unit,
    onOpenUnsent: () -> Unit,
    onOpenHelp: () -> Unit,
    onCustomize: () -> Unit = {},
    focusMailbox: String? = null,
    focusToken: Int = 0,
    onOpenDrawer: (() -> Unit)? = null,
) {
    LocalScreenAccounts.current.Register(accountId)
    val appContext = LocalContext.current.applicationContext
    val smoke = smokeLaunch(LocalContext.current.hostActivity()?.intent)
    val session = remember(accountId) { mailSession(accountId) }
    val connectionState by session.connectionState.collectAsState()
    val debugStatus by TrafficLog.debugStatus.collectAsState()
    val held = viewModel<FolderScreenHeld>()
    val model = held.bind(session, store)
    val scope = rememberCoroutineScope()
    // Covered by a higher layer: the entry keeps this scroll until the layer is popped.
    val listState = rememberLazyListState(held.scrollIndex, held.scrollOffset)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if (held.rowsState.value.isEmpty()) return@collect
                held.scrollIndex = index
                held.scrollOffset = offset
            }
    }
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
    var folderBar by remember { mutableStateOf(defaultFolderBar()) }
    var toolbarRows by remember { mutableIntStateOf(2) }
    var iconRows by remember { mutableIntStateOf(1) }
    val emptyPrefixLabel = S("(empty prefix)")
    val retryLabel = S("Retry", name = "folders_retry")

    LifecycleStartEffect(store) {
        val job = scope.launch {
            val loaded = try {
                store.load()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (loaded != null) {
                folderBar = org.dlang.liveimap.ui.toolbar.ToolbarStore.folderBar()
                toolbarRows = org.dlang.liveimap.ui.toolbar.ToolbarStore.folderRows()
            }
        }
        onStopOrDispose { job.cancel() }
    }

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
            held.turn {
                if (stopped) return@turn
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

    LaunchedEffect(Unit) {
        extraIdleChanges().collect { event ->
            // Another account's extra IDLE folder can share this mailbox name. A blank id matches nothing.
            if (event.accountId != accountId) return@collect
            rows = rows.map { row ->
                if (row.mailbox != event.mailbox) {
                    row
                } else {
                    row.copy(messages = folderMessageCount(row.messages, event.change)).also { model.noteMessages(it.mailbox, it.messages) }
                }
            }
        }
    }

    LaunchedEffect(session, loadToken) {
        if (smoke) return@LaunchedEffect
        val reuse = held.levelReady && !refreshListed && loadToken == 0
        unsentCount = readCopies(appContext, accountId).size
        if (!reuse) {
            loading = true
            banner = null
            stopped = false
        }
        held.turn {
            val settings = try {
                store.load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                banner = error.message ?: "not connected"
                stopped = true
                loading = false
                return@turn
            }
            imapHost = settings.imapHost
            hostKnown = true
            sentMailbox = settings.sentMailbox
            postponedMailbox = settings.postponedMailbox
            spamMailbox = settings.spamMailbox
            addressBookMailbox = settings.alpineBooks().firstOrNull()?.first.orEmpty()
            favorites = settings.favorites
            showUnreadCounts = settings.showUnreadCounts
            folderBar = org.dlang.liveimap.ui.toolbar.ToolbarStore.folderBar()
            toolbarRows = org.dlang.liveimap.ui.toolbar.ToolbarStore.folderRows()
            try {
                store.password()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                banner = error.message ?: "not connected"
                stopped = true
                loading = false
                return@turn
            }
            val opened = try {
                session.open(settings)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                banner = error.text
                stopped = true
                loading = false
                return@turn
            }
            when (opened) {
                is OpenResult.Rejected -> {
                    banner = opened.capabilities
                    stopped = true
                    loading = false
                    return@turn
                }
                is OpenResult.Failed -> {
                    banner = opened.text
                    stopped = true
                    loading = false
                    return@turn
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
        held.turn {
            if (stopped) return@turn
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
                held.turn {
                    if (stopped) return@turn
                    val current = rows
                    val listed = folderRowsMatchingName(current, folderQuery, emptyPrefixLabel)
                    val visible = indices.mapNotNull { listed.getOrNull(it) }.filter { !it.namespaceRoot }
                    if (visible.isEmpty()) return@turn
                    val updated = try {
                        model.refreshWatchedCounts(model.refreshVisibleCounts(visible))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: MailFailure) {
                        postSnack(error.text)
                        return@turn
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
            held.turn {
                if (stopped) return@turn
                val settings = try {
                    store.load()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    postSnack(error.message ?: "not connected")
                    return@turn
                }
                val favorite = FolderFavorite(node, row.mailbox, row.delimiter)
                val nextFavorites = if (settings.favorites.any { it.node == node && it.mailbox == row.mailbox }) {
                    settings.favorites.filterNot { it.node == node && it.mailbox == row.mailbox }
                } else {
                    settings.favorites + favorite
                }
                store.update { it.copy(favorites = nextFavorites) }
                favorites = nextFavorites
            }
        }
    }

    fun collapseAll() {
        scope.launch {
            held.turn {
                if (stopped) return@turn
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
            held.turn {
                if (stopped) return@turn
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
            held.turn {
                if (stopped) return@turn
                try {
                    model.resetToDefault()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    postSnack(error.message ?: "not connected")
                    return@turn
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
            held.turn {
                if (stopped) return@turn
                try {
                    model.alwaysExpand(mailbox)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    postSnack(error.message ?: "not connected")
                    return@turn
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
            held.turn {
                if (stopped) return@turn
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
                    title = { Text(S("Folders")) },
                    navigationIcon = {
                        org.dlang.liveimap.ui.DrawerToggleButton()
                    },
                    actions = {
                        DebugStatusIcon(debugStatus)
                        val refreshFolderList = {
                            if (!loading) {
                                refreshListed = true
                                loadToken += 1
                            }
                        }
                        val folderIcons = ArrayList<@Composable () -> Unit>(folderBar.toolbar.size)
                        for (action in folderBar.toolbar) {
                            when (action) {
                                FolderBarAction.Refresh -> folderIcons.add {
                                    HelpIconButton(
                                        label = S("Refresh"),
                                        help = S("Loads the folder list again."),
                                        page = "folders.html#refresh",
                                        onClick = refreshFolderList,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Refresh,
                                            contentDescription = S("Refresh"),
                                        )
                                    }
                                }
                                FolderBarAction.CollapseAll -> folderIcons.add {
                                    IconButton(onClick = { collapseAll() }) {
                                        Icon(
                                            imageVector = Icons.Filled.UnfoldLess,
                                            contentDescription = S("Collapse all"),
                                        )
                                    }
                                }
                                FolderBarAction.SaveDefault -> folderIcons.add {
                                    IconButton(onClick = { saveDefaultView() }) {
                                        Icon(
                                            imageVector = Icons.Filled.Save,
                                            contentDescription = S("Save as default view"),
                                        )
                                    }
                                }
                                FolderBarAction.ResetDefault -> folderIcons.add {
                                    IconButton(onClick = { resetToDefaultView() }) {
                                        Icon(
                                            imageVector = Icons.Filled.RestartAlt,
                                            contentDescription = S("Reset to default view"),
                                        )
                                    }
                                }
                            }
                        }
                        ToolbarIconRows(
                            maxRows = toolbarRows,
                            onRows = { count -> iconRows = count },
                            icons = folderIcons,
                        )
                        Box {
                            HelpIconButton(
                                label = S("More"),
                                help = S("Collapse all, the default view, and the toolbar editor."),
                                page = "folders.html#more",
                                onClick = { moreMenu = true },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = S("More"),
                                )
                            }
                            DropdownMenu(
                                expanded = moreMenu,
                                onDismissRequest = { moreMenu = false },
                            ) {
                                ObscuredTouchFilter()
                                for (entry in folderMenu(folderBar, unsentCount > 0)) {
                                    when (entry) {
                                        is FolderMenuEntry.Action -> {
                                            val title = S(folderBarLabel(entry.action))
                                            HelpDropdownMenuItem(
                                                label = title,
                                                help = when (entry.action) {
                                                        FolderBarAction.Refresh -> S("Reloads the folder list.")
                                                        FolderBarAction.CollapseAll -> S("Collapses every expanded folder.")
                                                        FolderBarAction.SaveDefault -> S("Saves the current expansion as the default.")
                                                        FolderBarAction.ResetDefault -> S("Restores the saved default expansion.")
                                                    },
                                                page = "folders.html",
                                                text = { Text(title) },
                                                onClick = {
                                                    moreMenu = false
                                                    when (entry.action) {
                                                        FolderBarAction.Refresh -> refreshFolderList()
                                                        FolderBarAction.CollapseAll -> collapseAll()
                                                        FolderBarAction.SaveDefault -> saveDefaultView()
                                                        FolderBarAction.ResetDefault -> resetToDefaultView()
                                                    }
                                                },
                                            )
                                        }
                                        FolderMenuEntry.Unsent -> {
                                            val title = S("Unsent")
                                            HelpDropdownMenuItem(
                                                label = title,
                                                help = S("Opens messages kept on this device."),
                                                page = "folders.html",
                                                text = { Text(title) },
                                                onClick = {
                                                    moreMenu = false
                                                    onOpenUnsent()
                                                },
                                            )
                                        }
                                        FolderMenuEntry.Divider -> HorizontalDivider()
                                        FolderMenuEntry.Customize -> {
                                            val title = S("Customize toolbar…")
                                            HelpDropdownMenuItem(
                                                label = title,
                                                help = S("Chooses which actions stay on the bar."),
                                                page = "folders.html",
                                                text = { Text(title) },
                                                onClick = {
                                                    moreMenu = false
                                                    onCustomize()
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    },
                    expandedHeight = toolbarExpandedHeight(iconRows),
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
                        placeholder = { Text(S("Search folders")) },
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
                                        contentDescription = S("Clear search"),
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
    Box(Modifier.fillMaxSize().padding(padding).testTag("smoke-folders")) {
    Column(modifier = Modifier.fillMaxSize()) {
        ConnectionStatusStrip(connectionState) {
            scope.launch {
                try {
                    if (!smoke) session.resume()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: MailFailure) {
                }
                loadToken += 1
            }
        }
        if (hostKnown && imapHost.isBlank()) {
            TextButton(onClick = onOpenHelp) { Text(S("Help")) }
        }
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenUnsent)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = S("Unsent %1\$d", unsentCount), modifier = Modifier.weight(1f))
                Icon(Icons.Filled.ChevronRight, contentDescription = null)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
        }
        if (!stopped) {
            // Names already loaded. Does not send LIST.
            // Jank (#22): filtering ~1000 rows and measuring every count label ran on each recomposition (every
            // expand, every snackbar). Both are remembered on their inputs, and widths measure one label per length.
            val shown = remember(rows, folderQuery, emptyPrefixLabel) {
                folderRowsMatchingName(rows, folderQuery, emptyPrefixLabel).distinctBy { folderRowKey(it) }
            }
            val countStyle = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum")
            val countMeasurer = rememberTextMeasurer()
            val density = LocalDensity.current
            val totalWidth = remember(shown, countStyle, density) {
                folderCountColumnWidth(countMeasurer, countStyle, shown.mapNotNull { it.messages?.toString() }, density)
            }
            val unreadWidth = remember(shown, countStyle, density, showUnreadCounts) {
                folderCountColumnWidth(
                    countMeasurer,
                    countStyle,
                    shown.mapNotNull { folderUnreadLabel(showUnreadCounts, it.unseen) },
                    density,
                )
            }
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
                        text = S("No folders"),
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
                        text = S("No matching folders"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                // Stable keys: an expand inserts rows without re-laying-out (or re-keying) the ones already shown.
                items(shown, key = { folderRowKey(it) }, contentType = { if (it.namespaceRoot) 1 else 0 }) { row ->
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
                                held.turn {
                                    if (stopped) return@turn
                                    try {
                                        model.toggleExpanded(row.mailbox)
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: MailFailure) {
                                        postSnack(error.text)
                                        return@turn
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
            TextButton(onClick = onRetry) { Text(S("Retry", name = "folders_retry")) }
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
    for (label in widthCandidates(labels)) {
        val width = measurer.measure(text = label, style = style).size.width
        if (width > widest) widest = width
    }
    return with(density) { widest.toDp() }
}

/**
 * The labels whose widths can differ: counts use tabular figures, so labels of one length (same digits and
 * separators) are the same width; one per length, the first seen.
 */
internal fun widthCandidates(labels: List<String>): List<String> = labels.distinctBy { it.length }

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
    val shownLeaf = folderDisplayName(row, S("(empty prefix)"))
    val unreadLabel = folderUnreadLabel(showUnread, row.unseen)
    val messageCount = row.messages
    val messagesPhrase = if (messageCount == null) {
        null
    } else {
        P(messageCount, messageCount, one = "%d message", other = "%d messages")
    }
    val unseenCount = if (unreadLabel == null) null else row.unseen
    val unreadPhrase = if (unseenCount == null) {
        null
    } else {
        P(unseenCount, unseenCount, one = "%d unread", other = "%d unread")
    }
    val expansionPhrase = if (!row.hasChildren) {
        null
    } else if (row.expanded) {
        S("expanded")
    } else {
        S("collapsed")
    }
    val description = if (emptyPrefix) {
        S("Namespace, empty prefix")
    } else {
        folderRowDescription(shownLeaf, messagesPhrase, unreadPhrase, expansionPhrase)
    }
    val favoriteText = if (favorite) S("Remove favorite") else S("Add favorite")
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
                        S("Collapse %1\$s", shownLeaf)
                    } else {
                        S("Expand %1\$s", shownLeaf)
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
                text = org.dlang.liveimap.ui.nonAscii(shownLeaf),
                fontStyle = if (emptyPrefix) FontStyle.Italic else FontStyle.Normal,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp, top = 8.dp, bottom = 8.dp),
            )
        }
        FolderRowMenu(
            expanded = rowMenu,
            onDismiss = { rowMenu = false },
            favoriteText = favoriteText,
            onStar = onStar,
            onAlwaysExpand = onAlwaysExpand,
            onDontAlwaysExpand = onDontAlwaysExpand,
        )
        FolderRowDialogs()
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

@Composable
private fun FolderRowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    favoriteText: String,
    onStar: () -> Unit,
    onAlwaysExpand: () -> Unit,
    onDontAlwaysExpand: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        ObscuredTouchFilter()
        HelpDropdownMenuItem(
            label = favoriteText,
            help = S("Adds or removes this folder as a favorite."),
            page = "folders.html",
            text = { Text(favoriteText) },
            onClick = {
                onDismiss()
                onStar()
            },
        )
        val alwaysExpand = S("Always expand")
        HelpDropdownMenuItem(
            label = alwaysExpand,
            help = S("This folder starts expanded."),
            page = "folders.html",
            text = { Text(alwaysExpand) },
            onClick = {
                onDismiss()
                onAlwaysExpand()
            },
        )
        val dontAlwaysExpand = S("Don't always expand")
        HelpDropdownMenuItem(
            label = dontAlwaysExpand,
            help = S("This folder starts collapsed."),
            page = "folders.html",
            text = { Text(dontAlwaysExpand) },
            onClick = {
                onDismiss()
                onDontAlwaysExpand()
            },
        )
    }
}

/** Folder rows have no dialog of their own; the menu above is the only overlay. */
@Composable
private fun FolderRowDialogs() = Unit

/**
 * Fresh install: the normal folder top bar (drawer button, and a More menu with everything that works without an
 * account: Settings, Unsent, Customize, Help, About), with "No mail account yet" and Add account as the body.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@androidx.compose.runtime.Composable
private fun NoAccountYet(
    onAddAccount: () -> Unit,
    onOpenDrawer: (() -> Unit)?,
    onOpenUnsent: () -> Unit,
    onCustomize: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(S("Folders")) },
                navigationIcon = {
                    if (onOpenDrawer != null) {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(Icons.Filled.Menu, contentDescription = S("Menu"))
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { more = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = S("More"))
                        }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            ObscuredTouchFilter()
                            val items = listOf(
                                Triple(org.dlang.liveimap.i18n.literal.S("Settings"), org.dlang.liveimap.i18n.literal.S("Opens settings."), onOpenSettings),
                                Triple(org.dlang.liveimap.i18n.literal.S("Unsent"), org.dlang.liveimap.i18n.literal.S("Opens messages kept on this device."), onOpenUnsent),
                                Triple(org.dlang.liveimap.i18n.literal.S("Customize toolbar…"), org.dlang.liveimap.i18n.literal.S("Chooses which actions stay on the bar."), onCustomize),
                                Triple(org.dlang.liveimap.i18n.literal.S("Help"), org.dlang.liveimap.i18n.literal.S("Opens help."), onOpenHelp),
                                Triple(org.dlang.liveimap.i18n.literal.S("About"), org.dlang.liveimap.i18n.literal.S("Opens About."), onOpenAbout),
                            )
                            for ((label, help, action) in items) {
                                val title = S(label)
                                HelpDropdownMenuItem(
                                    label = title,
                                    help = S(help),
                                    page = "folders.html",
                                    text = { Text(title) },
                                    onClick = { more = false; action() },
                                )
                            }
                        }
                    }
                },
                windowInsets = mailBarInsets(),
            )
        },
    ) { padding ->
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            Text(
                S("No mail account yet"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            androidx.compose.material3.Button(onClick = onAddAccount, modifier = Modifier.padding(top = 16.dp)) {
                Text(S("Add account"))
            }
        }
    }
}

/** A LazyColumn key for a folder row: unique per tree position (a mailbox appears once under one parent). */
internal fun folderRowKey(row: FolderRow): String =
    (if (row.namespaceRoot) "ns\u0000" else "mb\u0000") + row.depth + "\u0000" + row.parentMailbox.orEmpty() + "\u0000" + row.mailbox
