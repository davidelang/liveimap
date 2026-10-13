package org.dlang.liveimap.ui

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import org.dlang.liveimap.settings.PaneContent
import org.dlang.liveimap.settings.PaneNode
import org.dlang.liveimap.settings.SplitAxis
import org.dlang.liveimap.settings.decodePaneLayout
import org.dlang.liveimap.settings.leaves
import org.dlang.liveimap.settings.listSide
import org.dlang.liveimap.settings.mailNode
import org.dlang.liveimap.settings.mailSplit
import org.dlang.liveimap.settings.messageFirst
import org.dlang.liveimap.settings.paneLayoutProblem
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import org.dlang.liveimap.ui.TextButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import org.dlang.liveimap.settings.UnsavedEdits
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.dlang.liveimap.R
import org.dlang.liveimap.engine.sieve.seedCriteria
import org.dlang.liveimap.session.CertPrompt
import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.session.setMailCertConfirmer
import org.dlang.liveimap.session.setMailPlaintextConfirmer
import org.dlang.liveimap.settings.AccountChoice
import org.dlang.liveimap.settings.planLayout
import org.dlang.liveimap.settings.PaneArrangement
import org.dlang.liveimap.settings.ArrangementChoice
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.DrawerAccount
import org.dlang.liveimap.settings.ExpandedFoldersScreen
import org.dlang.liveimap.settings.FolderFavorite
import org.dlang.liveimap.settings.FolderStartsScreen
import org.dlang.liveimap.settings.FolderViewsScreen
import org.dlang.liveimap.settings.LayoutChoice
import org.dlang.liveimap.settings.MultiPane
import org.dlang.liveimap.settings.SettingsGroup
import org.dlang.liveimap.settings.SettingsGroupList
import org.dlang.liveimap.settings.SettingsGroupScreen
import org.dlang.liveimap.settings.favoriteLabel
import org.dlang.liveimap.ui.about.AboutScreen
import org.dlang.liveimap.ui.about.LicensesScreen
import org.dlang.liveimap.ui.compose.ComposeScreen
import org.dlang.liveimap.ui.contacts.ContactCopyScreen
import org.dlang.liveimap.ui.compose.UnsentScreen
import org.dlang.liveimap.ui.filter.FilterEditorScreen
import org.dlang.liveimap.ui.filter.FilterListScreen
import org.dlang.liveimap.ui.folder.FolderListModel
import org.dlang.liveimap.ui.folder.FolderListScreen
import org.dlang.liveimap.ui.help.HelpScreen
import org.dlang.liveimap.ui.index.MessageIndexScreen
import org.dlang.liveimap.ui.index.SearchScope
import org.dlang.liveimap.ui.reader.MessageReaderScreen
import org.dlang.liveimap.ui.search.AdvancedSearchScreen
import org.dlang.liveimap.ui.toolbar.ToolbarEditorScreen
import org.dlang.liveimap.ui.toolbar.ToolbarScreen

private class PendingCert(
    val prompt: CertPrompt,
    val resume: (Boolean) -> Unit,
)

/**
 * F76: certificate fields are chosen by whoever presents the certificate. Show them on one line, without
 * control or bidi characters, and cut long values so they cannot push the fingerprint off the dialog.
 */
internal fun certDisplayText(text: String, limit: Int = 160): String {
    val cleaned = buildString {
        for (c in text) {
            when {
                c == '\n' || c == '\r' || c == '\t' -> append(' ')
                c.code < 0x20 || c.code == 0x7F || c.code in 0x80..0x9F -> Unit
                c.code == 0x061C || c.code in 0x200B..0x200F || c.code in 0x202A..0x202E ||
                    c.code in 0x2066..0x2069 || c.code == 0xFEFF -> Unit
                else -> append(c)
            }
        }
    }.trim().replace(Regex(" {2,}"), " ")
    return if (cleaned.length <= limit) cleaned else cleaned.take(limit) + "…"
}

@Composable
fun LiveImapNavHost() {
    var multiPane by remember { mutableStateOf(MultiPane.Off) }
    // Custom panel layout (null = Auto); design-decisions.md "Custom panel layout".
    var customTree by remember { mutableStateOf<PaneNode?>(null) }
    val treeDp = remember { mutableStateMapOf<String, Float>() }
    val drawerPaneSlot = remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    var folderPane by remember { mutableStateOf(LayoutChoice.Off) }
    var foldPosture by remember { mutableStateOf(LayoutChoice.Off) }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    var textZoom by remember { mutableStateOf(100) }
    var arrangement by remember { mutableStateOf(ArrangementChoice.SideBySide) }
    // Effective font: system font scale x the app's text size; re-planned on rotation, fold, resize, font change.
    val autoLayout = planLayout(
        configuration.screenWidthDp,
        configuration.screenHeightDp,
        configuration.fontScale * textZoom / 100f,
    )
    // Folders above the list (left column) is planned but not rendered yet; that plan uses two panes for now.
    val autoPanes = if (autoLayout.folders == org.dlang.liveimap.settings.FolderPlacement.Beside) 3 else minOf(autoLayout.panes, 2)
    var folderPosition by remember { mutableStateOf(org.dlang.liveimap.settings.FolderPositionChoice.Beside) }
    val foldersAbove = customTree == null && useFolderPane(folderPane, 3) &&
        (folderPosition == org.dlang.liveimap.settings.FolderPositionChoice.AboveList ||
            (folderPosition == org.dlang.liveimap.settings.FolderPositionChoice.Auto &&
                autoLayout.folders == org.dlang.liveimap.settings.FolderPlacement.AboveList)) &&
        (folderPane != LayoutChoice.Auto || autoLayout.folders == org.dlang.liveimap.settings.FolderPlacement.AboveList)
    val stackedNow = arrangement == ArrangementChoice.Stacked ||
        (arrangement == ArrangementChoice.Auto && autoLayout.arrangement == PaneArrangement.Stacked)
    val customMail = customTree?.let { mailSplit(it) }
    val customMailNow = rememberUpdatedState(customMail)
    // A drawer panel inside the list/message part shows only on the index; elsewhere the modal drawer stays.
    val drawerOutsideMail = customTree?.let { tree ->
        PaneContent.AccountDrawer in tree.leaves() && PaneContent.AccountDrawer !in mailNode(tree).leaves()
    } == true
    val split = if (customTree != null) customMail != null else useMultiPane(multiPane, autoPanes)
    val splitNow = rememberUpdatedState(split)
    val hinges = currentWindowAdaptiveInfo().windowPosture.hingeList
    val axis = customMail?.let { if (it.axis == SplitAxis.Horizontal) PaneAxis.SideBySide else PaneAxis.TopBottom } ?: paneAxis(
        foldOn = useFoldPosture(foldPosture),
        autoTopBottom = stackedNow,
        hingeVertical = hinges.firstOrNull()?.isVertical == true,
        hingePresent = hinges.isNotEmpty(),
    )
    val axisNow = rememberUpdatedState(axis)
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    val certPrompt = remember { mutableStateOf<PendingCert?>(null) }
    // F74: one prompt at a time; a second caller waits its turn instead of replacing (and stranding) the first.
    val certGate = remember { kotlinx.coroutines.sync.Mutex() }
    val plaintextGate = remember { kotlinx.coroutines.sync.Mutex() }
    DisposableEffect(Unit) {
        setMailCertConfirmer { prompt -> certGate.withLock {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { cont ->
                    certPrompt.value = PendingCert(prompt) { accepted ->
                        certPrompt.value = null
                        if (cont.isActive) cont.resume(accepted)
                    }
                    cont.invokeOnCancellation { certPrompt.value = null }
                }
            }
        } }
        onDispose {
            val pending = certPrompt.value
            certPrompt.value = null
            pending?.resume(false)
            setMailCertConfirmer(null)
        }
    }
    val plaintextPrompt = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    DisposableEffect(Unit) {
        setMailPlaintextConfirmer { plaintextGate.withLock {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { cont ->
                    plaintextPrompt.value = { accepted ->
                        plaintextPrompt.value = null
                        if (cont.isActive) cont.resume(accepted)
                    }
                    cont.invokeOnCancellation { plaintextPrompt.value = null }
                }
            }
        } }
        onDispose {
            val pending = plaintextPrompt.value
            plaintextPrompt.value = null
            pending?.invoke(false)
            setMailPlaintextConfirmer(null)
        }
    }
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Back with the drawer open closes it (it used to leave the app).
    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open) {
        scope.launch { drawerState.close() }
    }
    val currentEntry by navController.currentBackStackEntryAsState()
    val route = currentEntry?.destination?.route
    val drawerGestures = route == "folders"
    // David, 2026-10-12: in two-pane layouts Settings shows its group list on the left and the opened group on the
    // right, with no drawer.
    val settingsSplit = split && settingsSplitRoute(route)
    val settingsSplitNow = rememberUpdatedState(settingsSplit)
    var settingsListDp by rememberSaveable { mutableFloatStateOf(unsetPaneDp) }
    var header by remember { mutableStateOf("") }
    var accounts by remember { mutableStateOf<List<AccountChoice>>(emptyList()) }
    var drawerAccounts by remember { mutableStateOf<List<DrawerAccount>>(emptyList()) }
    var favorites by remember { mutableStateOf<List<FolderFavorite>>(emptyList()) }
    var postponedMailbox by remember { mutableStateOf("") }
    val focusMailbox = remember { mutableStateOf<String?>(null) }
    val focusToken = remember { mutableStateOf(0) }
    val composeKindName = rememberSaveable { mutableStateOf(ComposeKind.New.name) }
    val composeMailbox = rememberSaveable { mutableStateOf("") }
    val composeUids = rememberSaveable { mutableStateOf("") }
    val composeEpoch = rememberSaveable { mutableStateOf(0L) }
    val composeUnsentId = rememberSaveable { mutableStateOf("") }
    val composeRetryOnOpen = rememberSaveable { mutableStateOf(false) }
    val composeAccount = rememberSaveable { mutableStateOf("") } // F121: the opener's account
    val filterEditIndex = rememberSaveable { mutableStateOf(-1) }
    val filterSeedFrom = rememberSaveable { mutableStateOf("") }
    val filterSeedTo = rememberSaveable { mutableStateOf("") }
    val filterSeedListId = rememberSaveable { mutableStateOf("") }
    val filterSeedSubject = rememberSaveable { mutableStateOf("") }
    var editingFavorite by remember { mutableStateOf<FolderFavorite?>(null) }
    var favoriteDraft by remember { mutableStateOf("") }
    // F121: the favorite being edited belongs to one account; edits go to that account, never the active one
    var editingAccountId by remember { mutableStateOf<String?>(null) }
    var editingFavorites by remember { mutableStateOf<List<FolderFavorite>>(emptyList()) }
    var editingWhere by remember { mutableStateOf(FavoriteWhere("", "", "")) }
    val favoriteMutex = remember { Mutex() }
    val availableDpState = remember { mutableIntStateOf(0) }
    var readerOpen by remember { mutableStateOf(false) }
    var userSized by rememberSaveable { mutableStateOf(false) }
    var drawerWidthDp by rememberSaveable { mutableFloatStateOf(unsetPaneDp) }
    var indexWidthDp by rememberSaveable { mutableFloatStateOf(unsetPaneDp) }
    var folderWidthDp by rememberSaveable { mutableFloatStateOf(unsetPaneDp) }
    val folderColumn = customTree == null && !(foldersAbove && split) && showFolderPane(folderPane, route, autoPanes)
    // Hidden (openable) drawer when the folder column shows, or when Auto finds no room for a drawer pane.
    val drawerHidden = drawerHiddenFor(folderColumn, route, multiPane, autoLayout.drawerPane)
    val folderColumnNow = rememberUpdatedState(drawerHidden)
    LaunchedEffect(drawerHidden, split) {
        val available = availableDpState.intValue
        if (split && !userSized && available > 0) {
            drawerWidthDp = initialDrawerDp(available, readerOpen, drawerHidden).toFloat()
        }
    }
    val openDrawerState = rememberUpdatedState<(() -> Unit)?>(
        if (drawerOutsideMail) {
            null
        } else if (!split || customTree != null) {
            { scope.launch { drawerState.open() } }
        } else if (drawerWidthDp >= 0f && drawerWidthDp <= 0f) {
            {
                val available = availableDpState.intValue
                if (available > 0) {
                    drawerWidthDp = minOf(360, available).toFloat()
                }
            }
        } else {
            null
        },
    )

    suspend fun applyOpenAccount() {
        val account = store.load()
        header = if (account.email.isNotBlank()) account.email else account.username
        if (editingFavorite == null) favorites = account.favorites
        postponedMailbox = account.postponedMailbox
        multiPane = account.multiPane
        customTree = decodePaneLayout(account.customLayout)?.takeIf { paneLayoutProblem(it) == null }
        treeDp.clear()
        folderPane = account.folderPane
        textZoom = account.readerTextZoom
        arrangement = account.paneArrangement
        folderPosition = account.folderPosition
        NonAsciiText.active = account.nonAsciiDisplay
        foldPosture = account.foldPosture
        accounts = store.listAccounts()
        drawerAccounts = store.listDrawerAccounts()
    }

    LaunchedEffect(route, drawerState.currentValue) {
        applyOpenAccount()
    }

    LaunchedEffect(split, route, currentEntry?.id) {
        if (!foldReaderIntoIndex(split, route)) return@LaunchedEffect
        val reader = navController.currentBackStackEntry ?: return@LaunchedEffect
        val previous = navController.previousBackStackEntry ?: return@LaunchedEffect
        if (previous.destination.route != "index/{mailbox}") return@LaunchedEffect
        val readerArgs = reader.arguments ?: return@LaunchedEffect
        val previousArgs = previous.arguments ?: return@LaunchedEffect
        val readerMailbox = readerArgs.getString("mailbox") ?: return@LaunchedEffect
        val indexMailbox = previousArgs.getString("mailbox") ?: return@LaunchedEffect
        if (readerMailbox != indexMailbox) return@LaunchedEffect
        previous.savedStateHandle["paneUid"] = readerArgs.getLong("uid")
        previous.savedStateHandle["paneSequence"] = readerArgs.getInt("sequence")
        previous.savedStateHandle["paneEpoch"] = readerArgs.getLong("epoch")
        navController.popBackStack()
    }

    fun openFilterEditor(index: Int, from: String, to: String, listId: String, subject: String) {
        filterEditIndex.value = index
        filterSeedFrom.value = from
        filterSeedTo.value = to
        filterSeedListId.value = listId
        filterSeedSubject.value = subject
        navController.navigate("filter")
    }

    // NavHost remembers the builder. A new lambda each pass would replace the graph and drop the stack.
    val folderListPane: @Composable () -> Unit = {
                    FolderListScreen(
                        onOpenMailbox = { mailbox ->
                            navController.navigate("index/${Uri.encode(mailbox)}")
                        },
                        onCompose = { seed ->
                            composeKindName.value = seed.kind.name
                            composeMailbox.value = seed.mailbox.orEmpty()
                            composeUids.value = seed.uids.joinToString(",")
                            composeUnsentId.value = ""
                            composeRetryOnOpen.value = false
                            navController.navigate("compose")
                        },
                        onOpenUnsent = { navController.navigate("unsent") },
                        onOpenHelp = { navController.navigate("help") },
                        onCustomize = { navController.navigate("toolbar/folders") },
                        focusMailbox = focusMailbox.value,
                        focusToken = focusToken.value,
                        onOpenDrawer = openDrawerState.value,
                    )
    }
    val foldersAboveNow = rememberUpdatedState(foldersAbove)
    val navGraph: NavGraphBuilder.() -> Unit = remember {
        {
            fun openCompose(seed: ComposeSeed) {
                composeKindName.value = seed.kind.name
                composeMailbox.value = seed.mailbox.orEmpty()
                composeUids.value = seed.uids.joinToString(",")
                composeEpoch.value = seed.uidValidity
                composeAccount.value = seed.accountId
                composeUnsentId.value = ""
                composeRetryOnOpen.value = false
                navController.navigate("compose")
            }
            composable("folders") {
                FolderListScreen(
                    onOpenMailbox = { mailbox ->
                        navController.navigate("index/${Uri.encode(mailbox)}")
                    },
                    onCompose = { seed -> openCompose(seed) },
                    onOpenUnsent = { navController.navigate("unsent") },
                    onOpenHelp = { navController.navigate("help") },
                    onCustomize = { navController.navigate("toolbar/folders") },
                    focusMailbox = focusMailbox.value,
                    focusToken = focusToken.value,
                    onOpenDrawer = openDrawerState.value,
                    onAddAccount = { navController.navigate("settings") },
                    onOpenAbout = { navController.navigate("about") },
                )
            }
            composable(
                route = "index/{mailbox}",
                arguments = listOf(
                    navArgument("mailbox") { type = NavType.StringType },
                ),
            ) { entry ->
                val encoded = entry.arguments?.getString("mailbox") ?: return@composable
                val mailbox = encoded // F73: Navigation already percent-decoded the path argument
                val useSplit = splitNow.value
                val useAxis = axisNow.value
                var paneUid by rememberSaveable { mutableStateOf(-1L) }
                var paneSequence by rememberSaveable { mutableIntStateOf(0) }
                // F105: the opened row's folder UIDVALIDITY as listed; the reader acts only in that epoch.
                var paneEpoch by rememberSaveable { mutableStateOf(0L) }
                var contentSpan by remember { mutableFloatStateOf(0f) }
                val folderViewToken by entry.savedStateHandle
                    .getStateFlow("folderViewToken", 0)
                    .collectAsState()
                var shownViewToken by remember {
                    mutableIntStateOf(entry.savedStateHandle.get<Int>("folderViewToken") ?: 0)
                }
                LaunchedEffect(folderViewToken, paneUid) {
                    if (paneUid < 0L) shownViewToken = folderViewToken
                }
                fun noteFolderView() {
                    val next = (entry.savedStateHandle.get<Int>("folderViewToken") ?: 0) + 1
                    entry.savedStateHandle["folderViewToken"] = next
                }
                LaunchedEffect(entry) {
                    val handle = entry.savedStateHandle
                    if (!handle.contains("paneUid")) return@LaunchedEffect
                    val foldedUid = handle.remove<Long>("paneUid")
                    val foldedSequence = handle.remove<Int>("paneSequence") ?: 0
                    val foldedEpoch = handle.remove<Long>("paneEpoch") ?: 0L
                    if (foldedUid == null) return@LaunchedEffect
                    paneSequence = foldedSequence
                    paneEpoch = foldedEpoch
                    paneUid = foldedUid
                }
                LaunchedEffect(useSplit, paneUid, paneSequence) {
                    if (!useSplit && paneUid >= 0L) {
                        val uid = paneUid
                        val sequence = paneSequence
                        paneUid = -1L
                        navController.navigate(readerRoute(mailbox, uid, sequence, paneEpoch))
                    }
                }
                LaunchedEffect(useSplit, paneUid, availableDpState.intValue) {
                    if (!useSplit) {
                        readerOpen = false
                        return@LaunchedEffect
                    }
                    val open = paneUid >= 0L
                    readerOpen = open
                    val available = availableDpState.intValue
                    if (userSized || available <= 0) return@LaunchedEffect
                    drawerWidthDp = initialDrawerDp(available, open, folderColumnNow.value).toFloat()
                }
                DisposableEffect(Unit) {
                    onDispose {
                        readerOpen = false
                        val available = availableDpState.intValue
                        if (!userSized && available > 0) {
                            drawerWidthDp = initialDrawerDp(available, false, folderColumnNow.value).toFloat()
                        }
                    }
                }
                LaunchedEffect(useSplit, paneUid, contentSpan, userSized) {
                    if (useSplit && paneUid >= 0L && !userSized && contentSpan > 8f) {
                        indexWidthDp = (contentSpan - 8f) * (customMailNow.value?.ratio?.coerceIn(0.1f, 0.9f) ?: autoLayout.listShare.coerceIn(0.2f, 0.8f))
                    }
                }
                val advancedQuery by entry.savedStateHandle
                    .getStateFlow("advancedQuery", "")
                    .collectAsState()
                val advancedScopeName by entry.savedStateHandle
                    .getStateFlow("advancedScope", "")
                    .collectAsState()
                val advancedScope = searchScopeOrCurrent(advancedScopeName)
                fun openAdvanced() {
                    navController.navigate("search/${Uri.encode(mailbox)}")
                }
                fun consumeAdvanced() {
                    entry.savedStateHandle.remove<String>("advancedQuery")
                    entry.savedStateHandle.remove<String>("advancedScope")
                }
                fun openMessage(uid: Long, sequence: Int, rowMailbox: String, epoch: Long) {
                    val target = if (rowMailbox.isEmpty()) mailbox else rowMailbox
                    if (useSplit && target == mailbox) {
                        paneUid = uid
                        paneSequence = sequence
                        paneEpoch = epoch
                    } else {
                        navController.navigate(readerRoute(target, uid, sequence, epoch))
                    }
                }
                @Composable
                fun IndexBody(viewKey: Int, watch: Boolean) {
                    key(viewKey) {
                        MessageIndexScreen(
                            mailbox = mailbox,
                            onOpen = { uid, sequence, rowMailbox, epoch -> openMessage(uid, sequence, rowMailbox, epoch) },
                            onCompose = { seed -> openCompose(seed) },
                            onBack = { navController.popBackStack() },
                            onCustomize = { navController.navigate("toolbar/index") },
                            onCustomizeSelection = { navController.navigate("toolbar/selection") },
                            watchMailbox = watch,
                            onAdvanced = { openAdvanced() },
                            advancedQuery = advancedQuery.ifEmpty { null },
                            advancedScope = advancedScope,
                            onAdvancedConsumed = { consumeAdvanced() },
                            onOpenFolder = { name ->
                                navController.navigate("index/${Uri.encode(name)}")
                            },
                        )
                    }
                }
                @Composable
                fun ListSide(viewKey: Int, watch: Boolean) {
                    val cm = customMailNow.value
                    if (cm == null) {
                        IndexBody(viewKey, watch)
                    } else {
                        PaneTree(cm.listSide(), "L", treeDp, null, {}) { content ->
                            when (content) {
                                PaneContent.MessageList -> IndexBody(viewKey, watch)
                                PaneContent.Folders -> folderListPane()
                                PaneContent.AccountDrawer -> drawerPaneSlot.value?.invoke()
                                PaneContent.Message -> Unit
                            }
                        }
                    }
                }
                BackHandler(enabled = useSplit && paneUid >= 0L) {
                    paneUid = -1L
                }
                if (!useSplit) {
                    IndexBody(folderViewToken, watch = true)
                } else if (paneUid < 0L) {
                    ListSide(shownViewToken, watch = true)
                } else {
                    val listLeading: @Composable () -> Unit = {
                            if (foldersAboveNow.value) {
                                Column(Modifier.fillMaxSize()) {
                                    Box(Modifier.weight(0.35f).fillMaxWidth()) {
                                        androidx.compose.runtime.CompositionLocalProvider(
                                            org.dlang.liveimap.ui.folder.LocalFolderPaneAboveList provides true,
                                        ) { folderListPane() }
                                    }
                                    Box(Modifier.height(8.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outlineVariant))
                                    Box(Modifier.weight(0.65f).fillMaxWidth()) { IndexBody(shownViewToken, watch = false) }
                                }
                            } else {
                                ListSide(shownViewToken, watch = false)
                            }
                        
                    }
                    val readerSide: @Composable () -> Unit = {
                            key(paneUid) {
                                MessageReaderScreen(
                                    mailbox = mailbox,
                                    uid = paneUid,
                                    sequence = paneSequence,
                                    uidValidity = paneEpoch,
                                    onCompose = { seed -> openCompose(seed) },
                                    onAdvance = { nextUid, nextSequence ->
                                        paneUid = nextUid
                                        paneSequence = nextSequence
                                    },
                                    onBack = { paneUid = -1L },
                                    onFolderViewSaved = { noteFolderView() },
                                    onCustomize = { navController.navigate("toolbar/reader") },
                                    onFilterLike = { from, to, listId, subject ->
                                        openFilterEditor(-1, from, to, listId, subject)
                                    },
                                )
                            }
                    }
                    AxisSplit(
                        axis = useAxis,
                        leadingDp = indexWidthDp,
                        fallback = { span -> ((span - 8f) / 2f).coerceAtLeast(0f) },
                        onSpan = { contentSpan = it },
                        onDrag = { delta, span ->
                            userSized = true
                            val max = (span - 8f).coerceAtLeast(0f)
                            val base = if (indexWidthDp < 0f) (span - 8f) / 2f else indexWidthDp
                            val next = base.coerceIn(0f, max) + delta
                            if (next + 8f >= span) {
                                paneUid = -1L
                            } else {
                                indexWidthDp = next.coerceAtLeast(0f)
                            }
                        },
                        leading = {
                            if (customMailNow.value?.messageFirst() == true) readerSide() else listLeading()
                        },
                        trailing = {
                            if (customMailNow.value?.messageFirst() == true) listLeading() else readerSide()
                        },
                    )
                }
            }
            composable(
                route = "search/{mailbox}",
                arguments = listOf(
                    navArgument("mailbox") { type = NavType.StringType },
                ),
            ) { entry ->
                val encoded = entry.arguments?.getString("mailbox") ?: return@composable
                val mailbox = encoded // F73: Navigation already percent-decoded the path argument
                AdvancedSearchScreen(
                    mailbox = mailbox,
                    onBack = { navController.popBackStack() },
                    onSearch = { text, scope ->
                        val previous = navController.previousBackStackEntry
                        val indexMailbox = previous?.arguments?.getString("mailbox")
                        if (previous?.destination?.route == "index/{mailbox}" && indexMailbox == mailbox) {
                            previous.savedStateHandle["advancedQuery"] = text
                            previous.savedStateHandle["advancedScope"] = scope.name
                        }
                        navController.popBackStack()
                    },
                )
            }
            composable("toolbar/index") {
                UpPage(stringResource(R.string.toolbar_customize), onUp = { navController.popBackStack() }) {
                    ToolbarEditorScreen(ToolbarScreen.Index)
                }
            }
            composable("toolbar/selection") {
                UpPage(stringResource(R.string.toolbar_customize), onUp = { navController.popBackStack() }) {
                    ToolbarEditorScreen(ToolbarScreen.Selection)
                }
            }
            composable("toolbar/folders") {
                UpPage(stringResource(R.string.toolbar_customize), onUp = { navController.popBackStack() }) {
                    ToolbarEditorScreen(ToolbarScreen.Folders)
                }
            }
            composable("toolbar/reader") {
                UpPage(stringResource(R.string.toolbar_customize), onUp = { navController.popBackStack() }) {
                    ToolbarEditorScreen(ToolbarScreen.Reader)
                }
            }
            composable("toolbar/compose") {
                UpPage(stringResource(R.string.toolbar_customize), onUp = { navController.popBackStack() }) {
                    ToolbarEditorScreen(ToolbarScreen.Compose)
                }
            }
            composable(
                route = ReaderRoutePattern,
                arguments = listOf(
                    navArgument("mailbox") { type = NavType.StringType },
                    navArgument("uid") { type = NavType.LongType },
                    navArgument("sequence") { type = NavType.IntType },
                    navArgument("epoch") {
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                ),
            ) { entry ->
                val args = entry.arguments ?: return@composable
                val encoded = args.getString("mailbox") ?: return@composable
                val mailbox = encoded // F73: Navigation already percent-decoded the path argument
                MessageReaderScreen(
                    mailbox = mailbox,
                    uid = args.getLong("uid"),
                    sequence = args.getInt("sequence"),
                    uidValidity = args.getLong("epoch"),
                    onCompose = { seed -> openCompose(seed) },
                    onAdvance = { nextUid, nextSequence ->
                        navController.navigate(
                            readerRoute(mailbox, nextUid, nextSequence, args.getLong("epoch")),
                        ) {
                            popUpTo(ReaderRoutePattern) { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() },
                    onFolderViewSaved = {
                        val previous = navController.previousBackStackEntry
                        val indexMailbox = previous?.arguments?.getString("mailbox")
                        if (previous?.destination?.route == "index/{mailbox}" && indexMailbox == mailbox) {
                            val handle = previous.savedStateHandle
                            val next = (handle.get<Int>("folderViewToken") ?: 0) + 1
                            handle["folderViewToken"] = next
                        }
                    },
                    onCustomize = { navController.navigate("toolbar/reader") },
                    onFilterLike = { from, to, listId, subject ->
                        openFilterEditor(-1, from, to, listId, subject)
                    },
                )
            }
            composable("compose") {
                val uidText = composeUids.value
                val seed = ComposeSeed(
                    kind = enumValueOf(composeKindName.value),
                    mailbox = composeMailbox.value.takeIf { it.isNotEmpty() },
                    uids = if (uidText.isEmpty()) {
                        emptyList()
                    } else {
                        uidText.split(',').map { it.toLong() }
                    },
                    uidValidity = composeEpoch.value,
                    accountId = composeAccount.value,
                )
                ComposeScreen(
                    seed = seed,
                    onDone = { navController.popBackStack() },
                    unsentId = composeUnsentId.value.takeIf { it.isNotEmpty() },
                    retryOnOpen = composeRetryOnOpen.value,
                    onOpenUnsent = { navController.navigate("unsent") },
                    onCustomize = { navController.navigate("toolbar/compose") },
                )
            }
            composable("unsent") {
                UpPage(stringResource(R.string.unsent_title), onUp = { navController.popBackStack() }) {
                    UnsentScreen(
                        onOpenCopy = { id, retry, account ->
                            composeAccount.value = account // F121: retry in the copy's own account
                            composeKindName.value = ComposeKind.New.name
                            composeMailbox.value = ""
                            composeUids.value = ""
                            composeUnsentId.value = id
                            composeRetryOnOpen.value = retry
                            navController.navigate("compose")
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable("settings") {
                UpPage("Settings", onUp = { navController.navigateUp() }) {
                    if (settingsSplitNow.value) {
                        // The list is in the left pane; this pane waits for a group.
                        Text(
                            stringResource(R.string.settings_pick_group),
                            modifier = Modifier.padding(16.dp).testTag("settings-pick-group"),
                        )
                    } else {
                        SettingsGroupList(onOpen = { group ->
                            navController.navigate("settings/${group.route}")
                        })
                    }
                }
            }
            composable("settings/folders/expanded") {
                UpPage("Expanded folders", onUp = {
                    navController.popBackStack("settings/{group}", inclusive = false)
                }) {
                    ExpandedFoldersScreen()
                }
            }
            composable("settings/folders/views") {
                UpPage("Folder views", onUp = {
                    navController.popBackStack("settings/{group}", inclusive = false)
                }) {
                    FolderViewsScreen()
                }
            }
            composable("settings/folders/starts") {
                UpPage("Start position per folder", onUp = {
                    navController.popBackStack("settings/{group}", inclusive = false)
                }) {
                    FolderStartsScreen()
                }
            }
            composable(
                route = "settings/{group}",
                arguments = listOf(navArgument("group") { type = NavType.StringType }),
            ) { entry ->
                val name = entry.arguments?.getString("group")
                val group = name?.let(SettingsGroup::fromRoute)
                if (group == null) {
                    LaunchedEffect(Unit) { navController.popBackStack() }
                } else {
                    UpPage(group.title, onUp = {
                        navController.popBackStack("settings", inclusive = false)
                    }) {
                        SettingsGroupScreen(
                            group = group,
                            onOpenExpanded = { navController.navigate("settings/folders/expanded") },
                            onOpenViews = { navController.navigate("settings/folders/views") },
                            onOpenStarts = { navController.navigate("settings/folders/starts") },
                            onCopyContacts = { navController.navigate("contacts") },
                            onEditBook = { navController.navigate("addressbook") },
                            onOpenPanel = { name -> navController.navigate("toolbar/$name") },
                        )
                    }
                }
            }
            composable("addressbook") {
                UpPage(stringResource(R.string.settings_edit_book), onUp = { navController.popBackStack() }) {
                    org.dlang.liveimap.ui.contacts.AddressBookScreen()
                }
            }
            composable("contacts") {
                UpPage("Copy contacts", onUp = { navController.popBackStack() }) {
                    ContactCopyScreen()
                }
            }
            composable("filters") {
                UpPage(stringResource(R.string.filter_edit_list), onUp = { navController.popBackStack() }) {
                    FilterListScreen(onOpen = { index -> openFilterEditor(index, "", "", "", "") })
                }
            }
            composable("filter") {
                val index = rememberSaveable { filterEditIndex.value }
                val from = rememberSaveable { filterSeedFrom.value }
                val to = rememberSaveable { filterSeedTo.value }
                val listId = rememberSaveable { filterSeedListId.value }
                val subject = rememberSaveable { filterSeedSubject.value }
                UpPage(
                    title = stringResource(if (index < 0) R.string.filter_add else R.string.filter_edit),
                    onUp = { navController.popBackStack() },
                ) {
                    FilterEditorScreen(
                        index = index,
                        seed = seedCriteria(from, to, listId, subject),
                        onDone = { navController.popBackStack() },
                    )
                }
            }
            composable("help") {
                UpPage(stringResource(R.string.help_title), onUp = { navController.navigateUp() }) {
                    HelpScreen()
                }
            }
            composable("about") {
                UpPage(stringResource(R.string.about_title), onUp = { navController.navigateUp() }) {
                    AboutScreen(onOpenLicenses = { navController.navigate("about/licenses") })
                }
            }
            composable("about/licenses") {
                UpPage(stringResource(R.string.about_licenses), onUp = {
                    navController.popBackStack("about", inclusive = false)
                }) {
                    LicensesScreen()
                }
            }
        }
    }

    fun navigateFromDrawer(go: suspend () -> Unit) {
        scope.launch {
            drawerState.close()
            go()
        }
    }

    fun persistFavorites(next: List<FolderFavorite>) {
        favorites = next
        scope.launch {
            favoriteMutex.withLock {
                store.update { it.copy(favorites = next) }
            }
        }
    }

    fun openFavoriteEditor(favorite: FolderFavorite, accountId: String? = null) {
        scope.launch {
            val id = accountId ?: store.chosenAccountId() ?: return@launch
            val settings = store.forAccount(id).load()
            editingAccountId = id
            editingFavorites = settings.favorites
            editingWhere = FavoriteWhere(
                account = settings.email.ifBlank { settings.username },
                server = settings.imapHost,
                path = favorite.mailbox,
            )
            editingFavorite = favorite
            favoriteDraft = favoriteDrawerLabel(favorite)
        }
    }

    /** F121: saves [next] as the edited account's favorites (the drawer list too when it is the active one). */
    fun persistEditingFavorites(next: List<FolderFavorite>) {
        val id = editingAccountId ?: return
        editingFavorites = next
        scope.launch {
            val active = store.chosenAccountId()
            if (id == active) favorites = next
            favoriteMutex.withLock {
                store.forAccount(id).update { it.copy(favorites = next) }
            }
            drawerAccounts = store.listDrawerAccounts()
        }
    }

    fun moveEditingFavorite(delta: Int) {
        val current = editingFavorite ?: return
        val list = editingFavorites
        val index = list.indexOfFirst { it.node == current.node && it.mailbox == current.mailbox }
        if (index < 0) return
        val target = index + delta
        if (target !in list.indices) return
        val next = list.toMutableList()
        val swap = next[target]
        next[target] = next[index]
        next[index] = swap
        persistEditingFavorites(next)
    }

    fun saveEditingFavorite() {
        val current = editingFavorite ?: return
        val trimmed = favoriteDraft.trim()
        val next = editingFavorites.map { item ->
            if (item.node == current.node && item.mailbox == current.mailbox) item.copy(label = trimmed) else item
        }
        persistEditingFavorites(next)
        editingFavorite = null
    }

    fun deleteEditingFavorite() {
        val current = editingFavorite ?: return
        persistEditingFavorites(editingFavorites.filterNot { it.node == current.node && it.mailbox == current.mailbox })
        editingFavorite = null
    }

    fun openFavorite(favorite: FolderFavorite) {
        scope.launch {
            drawerState.close()
            if (favorite.node) {
                val id = store.chosenAccountId()
                if (id != null) {
                    try {
                        FolderListModel(mailSession(id), store).showCollapsed(favorite.mailbox)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                    }
                }
                focusMailbox.value = favorite.mailbox
                focusToken.value = focusToken.value + 1
                navController.navigate("folders") {
                    popUpTo("folders")
                    launchSingleTop = true
                }
            } else {
                navController.navigate("index/${Uri.encode(favorite.mailbox)}")
            }
        }
    }

    fun openIndexRow(account: DrawerAccount, mailbox: String) {
        navigateFromDrawer {
            if (!account.chosen) {
                store.selectAccount(account.id)
                applyOpenAccount()
                navController.navigate("index/${Uri.encode(mailbox)}") {
                    popUpTo("folders")
                    launchSingleTop = false
                }
            } else {
                navController.navigate("index/${Uri.encode(mailbox)}")
            }
        }
    }

    fun openFoldersRow(account: DrawerAccount) {
        navigateFromDrawer {
            if (!account.chosen) {
                store.selectAccount(account.id)
                applyOpenAccount()
                navController.navigate("folders") {
                    popUpTo("folders") { inclusive = true }
                    launchSingleTop = false
                }
            } else {
                navController.navigate("folders") {
                    popUpTo("folders")
                    launchSingleTop = true
                }
            }
        }
    }

    fun openAccountFavorite(account: DrawerAccount, favorite: FolderFavorite) {
        if (account.chosen) {
            openFavorite(favorite)
            return
        }
        scope.launch {
            drawerState.close()
            store.selectAccount(account.id)
            applyOpenAccount()
            if (favorite.node) {
                val id = store.chosenAccountId()
                if (id != null) {
                    try {
                        FolderListModel(mailSession(id), store).showCollapsed(favorite.mailbox)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                    }
                }
                focusMailbox.value = favorite.mailbox
                focusToken.value = focusToken.value + 1
                navController.navigate("folders") {
                    popUpTo("folders") { inclusive = true }
                    launchSingleTop = false
                }
            } else {
                navController.navigate("index/${Uri.encode(favorite.mailbox)}") {
                    popUpTo("folders")
                    launchSingleTop = false
                }
            }
        }
    }

    fun editAccountFavorite(account: DrawerAccount, favorite: FolderFavorite) {
        if (account.chosen) {
            openFavoriteEditor(favorite)
            return
        }
        openFavoriteEditor(favorite, account.id) // F121: manage it in its own account; no account switch
    }

    val drawerSheet: @Composable ColumnScope.() -> Unit = {
        // Scrolls: with several accounts, Settings/Help/About fall below the edge in landscape.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("drawer-scroll")) {
        DrawerSheetContent(
            header = header,
            blocks = drawerBlocks(drawerAccounts),
            postponedMailbox = postponedMailbox,
            favorites = favorites,
            onInbox = {
                navigateFromDrawer {
                    navController.navigate("index/${Uri.encode("INBOX")}")
                }
            },
            onAccountInbox = { account -> openIndexRow(account, "INBOX") },
            onPostponed = { row ->
                navigateFromDrawer {
                    navController.navigate("index/${Uri.encode(row)}")
                }
            },
            onAccountPostponed = { account -> openIndexRow(account, account.postponedMailbox) },
            onAllFolders = {
                navigateFromDrawer {
                    navController.navigate("folders") {
                        popUpTo("folders")
                        launchSingleTop = true
                    }
                }
            },
            onAccountAllFolders = { account -> openFoldersRow(account) },
            onAddFilter = {
                navigateFromDrawer {
                    openFilterEditor(-1, "", "", "", "")
                }
            },
            onEditFilters = {
                navigateFromDrawer {
                    navController.navigate("filters")
                }
            },
            onFavorite = { favorite -> openFavorite(favorite) },
            onEditFavorite = { favorite -> openFavoriteEditor(favorite) },
            onAccountFavorite = { account, favorite -> openAccountFavorite(account, favorite) },
            onAccountEditFavorite = { account, favorite -> editAccountFavorite(account, favorite) },
            onSettings = {
                navigateFromDrawer {
                    navController.navigate("settings") {
                        launchSingleTop = true
                    }
                }
            },
            onHelp = {
                navigateFromDrawer {
                    navController.navigate("help") {
                        launchSingleTop = true
                    }
                }
            },
            onAbout = {
                navigateFromDrawer {
                    navController.navigate("about") {
                        launchSingleTop = true
                    }
                }
            },
        )
        }
    }

    @Composable
    fun DrawerScaffold() {
        if (split) {
            AxisSplit(
                axis = axis,
                leadingDp = drawerWidthDp,
                fallback = { span ->
                    initialDrawerDp(span.roundToInt(), readerOpen, folderColumnNow.value).toFloat()
                },
                onSpan = { availableDpState.intValue = it.roundToInt() },
                onDrag = { delta, span ->
                    userSized = true
                    val max = (span - 8f).coerceAtLeast(0f)
                    val base = if (drawerWidthDp < 0f) {
                        initialDrawerDp(span.roundToInt(), readerOpen, folderColumnNow.value).toFloat()
                    } else {
                        drawerWidthDp
                    }
                    drawerWidthDp = (base.coerceIn(0f, max) + delta).coerceIn(0f, max)
                },
                leading = {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .windowInsetsPadding(
                                mailScreenInsets().only(
                                    WindowInsetsSides.Vertical + WindowInsetsSides.Start,
                                ),
                            ),
                    ) {
                        // drawerSheet scrolls itself; a second verticalScroll here crashed pane mode.
                        drawerSheet()
                    }
                },
                trailing = {
                    val resolvedDrawerDp = if (drawerWidthDp >= 0f) {
                        drawerWidthDp
                    } else {
                        initialDrawerDp(availableDpState.intValue, readerOpen, folderColumnNow.value).toFloat()
                    }
                    val hostModifier = if (resolvedDrawerDp > 0f) {
                        Modifier
                            .fillMaxSize()
                            .consumeWindowInsets(
                                mailScreenInsets().only(WindowInsetsSides.Start),
                            )
                    } else {
                        Modifier.fillMaxSize()
                    }
                    NavHost(
                        navController = navController,
                        startDestination = "folders",
                        modifier = hostModifier,
                        builder = navGraph,
                    )
                },
            )
        } else {
            ModalNavigationDrawer(
                drawerContent = {
                    ModalDrawerSheet(
                        windowInsets = mailScreenInsets().only(
                            WindowInsetsSides.Vertical + WindowInsetsSides.Start,
                        ),
                        content = drawerSheet,
                    )
                },
                modifier = Modifier.fillMaxSize(),
                drawerState = drawerState,
                gesturesEnabled = drawerGestures,
            ) {
                NavHost(
                    navController = navController,
                    startDestination = "folders",
                    modifier = Modifier.fillMaxSize(),
                    builder = navGraph,
                )
            }
        }
    }
    val drawerPanel: @Composable () -> Unit = {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) { drawerSheet() }
    }
    drawerPaneSlot.value = drawerPanel

    @Composable
    fun CustomMailArea() {
        if (drawerOutsideMail) {
            NavHost(navController = navController, startDestination = "folders", modifier = Modifier.fillMaxSize(), builder = navGraph)
        } else {
            ModalNavigationDrawer(
                drawerContent = {
                    ModalDrawerSheet(
                        windowInsets = mailScreenInsets().only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
                        content = drawerSheet,
                    )
                },
                modifier = Modifier.fillMaxSize(),
                drawerState = drawerState,
                gesturesEnabled = drawerGestures,
            ) {
                NavHost(navController = navController, startDestination = "folders", modifier = Modifier.fillMaxSize(), builder = navGraph)
            }
        }
    }
    LiveImapScaffold {
        val tree = customTree
        if (settingsSplit) {
            val openGroup = currentEntry?.arguments?.getString("group")?.let(SettingsGroup::fromRoute)
            AxisSplit(
                axis = axis,
                leadingDp = settingsListDp,
                fallback = { span -> minOf(360f, span / 2f) },
                onSpan = { _ -> },
                onDrag = { delta, span ->
                    val max = (span - 8f).coerceAtLeast(0f)
                    val base = if (settingsListDp < 0f) minOf(360f, span / 2f) else settingsListDp
                    settingsListDp = (base.coerceIn(0f, max) + delta).coerceIn(0f, max)
                },
                leading = {
                    UpPage("Settings", onUp = { UnsavedEdits.guard { navController.popBackStack("settings", inclusive = true) } }) {
                        SettingsGroupList(selected = openGroup, onOpen = { group ->
                            UnsavedEdits.guard {
                                navController.navigate("settings/${group.route}") {
                                    popUpTo("settings")
                                    launchSingleTop = true
                                }
                            }
                        })
                    }
                },
                trailing = {
                    NavHost(navController = navController, startDestination = "folders", modifier = Modifier.fillMaxSize(), builder = navGraph)
                },
            )
        } else if (tree != null) {
            PaneTree(tree, "T", treeDp, mailNode(tree), { CustomMailArea() }) { content ->
                when (content) {
                    PaneContent.Folders -> folderListPane()
                    PaneContent.AccountDrawer -> drawerPanel()
                    else -> Unit
                }
            }
        } else if (folderColumn) {
            AxisSplit(
                axis = axis,
                leadingDp = folderWidthDp,
                fallback = { span -> minOf(360f, span) },
                onSpan = { _ -> },
                onDrag = { delta, span ->
                    val max = (span - 8f).coerceAtLeast(0f)
                    val base = if (folderWidthDp < 0f) minOf(360f, span) else folderWidthDp
                    folderWidthDp = (base.coerceIn(0f, max) + delta).coerceIn(0f, max)
                },
                leading = { folderListPane() },
                trailing = { DrawerScaffold() },
            )
        } else {
            DrawerScaffold()
        }
        val editing = editingFavorite
        if (editing != null) {
            BackHandler {
                editingFavorite = null
            }
            FavoriteEditDialog(
                where = editingWhere,
                name = favoriteDraft,
                onName = { favoriteDraft = it },
                onMoveUp = { moveEditingFavorite(-1) },
                onMoveDown = { moveEditingFavorite(1) },
                onSave = { saveEditingFavorite() },
                onDelete = { deleteEditingFavorite() },
                onDismiss = { editingFavorite = null },
            )
        }
        val pendingCert = certPrompt.value
        if (pendingCert != null) {
            val changed = pendingCert.prompt.reason == "certificate changed"
            val decline = {
                pendingCert.resume(false)
            }
            AlertDialog(
                onDismissRequest = decline,
                title = {
                    Text(
                        stringResource(
                            if (changed) R.string.cert_changed_title else R.string.cert_trust_title,
                        ),
                    )
                },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (changed) Text(stringResource(R.string.cert_changed_body))
                        // F76: the reason and the fingerprint first; sender-chosen names after, cleaned and cut.
                        Text(
                            stringResource(R.string.cert_reason, certDisplayText(pendingCert.prompt.reason)),
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                        Text(certDisplayText(pendingCert.prompt.fingerprint, 200))
                        Text(certDisplayText(pendingCert.prompt.subject))
                        Text(certDisplayText(pendingCert.prompt.issuer))
                        Text(certDisplayText(pendingCert.prompt.notBefore, 40))
                        Text(certDisplayText(pendingCert.prompt.notAfter, 40))
                    }
                },
                confirmButton = {
                    TextButton(onClick = { pendingCert.resume(true) }) {
                        Text(
                            stringResource(
                                R.string.cert_trust_confirm,
                                "${pendingCert.prompt.host}:${pendingCert.prompt.port}",
                            ),
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = decline) {
                        Text(stringResource(R.string.cert_trust_decline))
                    }
                },
            )
        }
        val pendingPlain = plaintextPrompt.value
        if (pendingPlain != null) {
            val decline = {
                pendingPlain(false)
            }
            AlertDialog(
                onDismissRequest = decline,
                title = { Text(stringResource(R.string.plaintext_auth_title)) },
                text = { Text(stringResource(R.string.plaintext_auth_body)) },
                confirmButton = {
                    TextButton(onClick = { pendingPlain(true) }) {
                        Text(stringResource(R.string.plaintext_auth_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = decline) {
                        Text(stringResource(R.string.plaintext_auth_decline))
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.DrawerSheetContent(
    header: String,
    blocks: List<DrawerAccount>,
    postponedMailbox: String,
    favorites: List<FolderFavorite>,
    onInbox: () -> Unit,
    onAccountInbox: (DrawerAccount) -> Unit,
    onPostponed: (String) -> Unit,
    onAccountPostponed: (DrawerAccount) -> Unit,
    onAllFolders: () -> Unit,
    onAccountAllFolders: (DrawerAccount) -> Unit,
    onFavorite: (FolderFavorite) -> Unit,
    onEditFavorite: (FolderFavorite) -> Unit,
    onAccountFavorite: (DrawerAccount, FolderFavorite) -> Unit,
    onAccountEditFavorite: (DrawerAccount, FolderFavorite) -> Unit,
    onAddFilter: () -> Unit,
    onEditFilters: () -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    onAbout: () -> Unit,
) {
    val postponedDescription = stringResource(R.string.drawer_postponed)
    if (blocks.isEmpty()) {
        // David's drawer rule: tapping the account name opens its full folder list.
        Text(
            text = header,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { UnsavedEdits.guard(onAllFolders) }
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .testTag("drawer-account-name"),
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.drawer_inbox)) },
            selected = false,
            onClick = { UnsavedEdits.guard(onInbox) },
        )
        val postponedRow = postponedDrawerMailbox(postponedMailbox)
        if (postponedRow != null) {
            NavigationDrawerItem(
                label = { Text(postponedRow) },
                selected = false,
                modifier = Modifier.semantics { contentDescription = postponedDescription },
                onClick = { UnsavedEdits.guard { onPostponed(postponedRow) } },
            )
        }
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.filter_add)) },
            selected = false,
            onClick = { UnsavedEdits.guard(onAddFilter) },
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.filter_edit_list)) },
            selected = false,
            onClick = { UnsavedEdits.guard(onEditFilters) },
        )
        for (favorite in favorites) {
            val shown = favoriteDrawerLabel(favorite)
            Box(Modifier.fillMaxWidth()) {
                NavigationDrawerItem(
                    label = { Text(shown) },
                    selected = false,
                    onClick = {},
                    modifier = Modifier.clearAndSetSemantics { },
                )
                Box(
                    Modifier
                        .matchParentSize()
                        .combinedClickable(
                            onClick = { UnsavedEdits.guard { onFavorite(favorite) } },
                            onLongClick = { UnsavedEdits.guard { onEditFavorite(favorite) } },
                        )
                        .semantics { contentDescription = shown },
                )
            }
        }
    } else {
        for (account in blocks) {
            // David's drawer rule: tapping the account name opens that account's full folder list.
            Text(
                text = account.name,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { UnsavedEdits.guard { onAccountAllFolders(account) } }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("drawer-account-name"),
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.drawer_inbox)) },
                selected = false,
                onClick = { UnsavedEdits.guard { onAccountInbox(account) } },
            )
            val postponedRow = postponedDrawerMailbox(account.postponedMailbox)
            if (postponedRow != null) {
                NavigationDrawerItem(
                    label = { Text(postponedRow) },
                    selected = false,
                    modifier = Modifier.semantics { contentDescription = postponedDescription },
                    onClick = { UnsavedEdits.guard { onAccountPostponed(account) } },
                )
            }
            for (favorite in account.favorites) {
                val shown = favoriteDrawerLabel(favorite)
                Box(Modifier.fillMaxWidth()) {
                    NavigationDrawerItem(
                        label = { Text(shown) },
                        selected = false,
                        onClick = {},
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    Box(
                        Modifier
                            .matchParentSize()
                            .combinedClickable(
                                onClick = { UnsavedEdits.guard { onAccountFavorite(account, favorite) } },
                                onLongClick = { UnsavedEdits.guard { onAccountEditFavorite(account, favorite) } },
                            )
                            .semantics { contentDescription = shown },
                    )
                }
            }
        }
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.filter_add)) },
            selected = false,
            onClick = { UnsavedEdits.guard(onAddFilter) },
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.filter_edit_list)) },
            selected = false,
            onClick = { UnsavedEdits.guard(onEditFilters) },
        )
    }
    HorizontalDivider()
    NavigationDrawerItem(
        label = { Text(stringResource(R.string.drawer_settings)) },
        selected = false,
        onClick = { UnsavedEdits.guard(onSettings) },
    )
    NavigationDrawerItem(
        label = { Text(stringResource(R.string.help_title)) },
        selected = false,
        onClick = { UnsavedEdits.guard(onHelp) },
    )
    NavigationDrawerItem(
        label = { Text(stringResource(R.string.about_title)) },
        selected = false,
        onClick = { UnsavedEdits.guard(onAbout) },
    )
}

@Composable
private fun FavoriteEditDialog(
    where: FavoriteWhere,
    name: String,
    onName: (String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.drawer_favorite)) },
        text = {
            Column {
                // F121: which account and server, and the folder's full path, so same-named folders are told apart
                Text(
                    favoriteWhereText(where),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("favorite-where"),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = onName,
                    label = { Text(stringResource(R.string.drawer_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Column {
                TextButton(onClick = onMoveUp) { Text(stringResource(R.string.drawer_move_up)) }
                TextButton(onClick = onMoveDown) { Text(stringResource(R.string.drawer_move_down)) }
                TextButton(onClick = onSave) { Text(stringResource(R.string.drawer_save)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.drawer_delete)) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.drawer_cancel)) }
        },
    )
}

/** F121: where a favorite lives: account, its IMAP server, and the folder's full server path. */
internal data class FavoriteWhere(val account: String, val server: String, val path: String)

internal fun favoriteWhereText(where: FavoriteWhere): String {
    val owner = listOf(where.account, where.server).filter { it.isNotBlank() }.joinToString(" on ")
    val path = where.path.ifEmpty { "(top level)" }
    return if (owner.isEmpty()) path else "$owner\n$path"
}

internal fun favoriteDrawerLabel(favorite: FolderFavorite): String {
    if (favorite.label.isNotBlank()) return favorite.label
    return favoriteLabel(favorite.node, favorite.mailbox, favorite.delimiter)
}

internal fun foldReaderIntoIndex(expanded: Boolean, route: String?): Boolean {
    return expanded && route == ReaderRoutePattern
}

internal fun useMultiPane(mode: MultiPane, autoPanes: Int = 1): Boolean =
    mode == MultiPane.Wide || (mode == MultiPane.Auto && autoPanes >= 2)


internal fun useFolderPane(choice: LayoutChoice, autoPanes: Int = 1): Boolean =
    choice == LayoutChoice.On || (choice == LayoutChoice.Auto && autoPanes >= 3)

internal fun useFoldPosture(choice: LayoutChoice): Boolean = choice == LayoutChoice.On

internal enum class PaneAxis {
    SideBySide,
    TopBottom,
}

/**
 * The account drawer is a hidden (openable) drawer when a folder column shows, or in Auto when there is no
 * room for it. In Auto the folder list screen counts as folders shown: the app starts there before it opens
 * the start folder, and a drawer pane beside the folder list flashed on the Boox before the list appeared.
 */
/** Settings routes, which get the list/detail split (no drawer) in two-pane layouts. */
internal fun settingsSplitRoute(route: String?): Boolean =
    route == "settings" || route?.startsWith("settings/") == true

internal fun drawerHiddenFor(folderColumn: Boolean, route: String?, multiPane: MultiPane, autoDrawerPane: Boolean): Boolean =
    folderColumn || (multiPane == MultiPane.Auto && (!autoDrawerPane || route == "folders"))

internal fun showFolderPane(choice: LayoutChoice, route: String?, autoPanes: Int = 1): Boolean {
    return useFolderPane(choice, autoPanes) &&
        (route == "index/{mailbox}" || route == ReaderRoutePattern)
}

internal fun paneAxis(foldOn: Boolean, hingeVertical: Boolean, hingePresent: Boolean, autoTopBottom: Boolean = false): PaneAxis {
    if (foldOn && hingePresent && !hingeVertical) return PaneAxis.TopBottom
    if (autoTopBottom && !hingePresent) return PaneAxis.TopBottom
    return PaneAxis.SideBySide
}

internal fun initialDrawerDp(availableDp: Int, readerOpen: Boolean, folderColumn: Boolean = false): Int {
    // The folder column already shows folders: the account drawer pane starts hidden (still openable).
    if (folderColumn) return 0
    if (!readerOpen) return minOf(360, availableDp)
    return if (availableDp < 1000) 0 else 360
}

private const val unsetPaneDp = -1f

@Composable
private fun DragSplit(
    leadingDp: Float,
    fallback: (Float) -> Float,
    onSpan: (Float) -> Unit,
    onDrag: (Float, Float) -> Unit,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val span = maxWidth.value
        SideEffect { onSpan(span) }
        val maxLeading = (span - 8f).coerceAtLeast(0f)
        val raw = if (leadingDp < 0f) fallback(span) else leadingDp
        val width = raw.coerceIn(0f, maxLeading).dp
        val drag = rememberUpdatedState(onDrag)
        val spanNow = rememberUpdatedState(span)
        val density = LocalDensity.current
        val densityNow = rememberUpdatedState(density)
        val label = stringResource(R.string.pane_resize)
        Box(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                if (width > 0.dp) {
                    Box(
                        Modifier
                            .width(width)
                            .fillMaxHeight(),
                    ) {
                        leading()
                    }
                }
                // A collapsed pane (e.g. the hidden drawer) leaves only a 1 dp line; the drag target stays.
                Box(
                    Modifier
                        .width(if (width > 0.dp) 8.dp else 1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    trailing()
                }
            }
            Box(
                Modifier
                    .offset(x = width + 4.dp - 24.dp)
                    .width(48.dp)
                    .fillMaxHeight()
                    .zIndex(1f)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { _, dragPx ->
                            drag.value(dragPx / densityNow.value.density, spanNow.value)
                        }
                    }
                    .focusable()
                    .semantics { contentDescription = label },
            )
        }
    }
}

@Composable
private fun StackSplit(
    leadingDp: Float,
    fallback: (Float) -> Float,
    onSpan: (Float) -> Unit,
    onDrag: (Float, Float) -> Unit,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val span = maxHeight.value
        SideEffect { onSpan(span) }
        val maxLeading = (span - 8f).coerceAtLeast(0f)
        val raw = if (leadingDp < 0f) fallback(span) else leadingDp
        val height = raw.coerceIn(0f, maxLeading).dp
        val drag = rememberUpdatedState(onDrag)
        val spanNow = rememberUpdatedState(span)
        val density = LocalDensity.current
        val densityNow = rememberUpdatedState(density)
        val label = stringResource(R.string.pane_resize)
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (height > 0.dp) {
                    Box(
                        Modifier
                            .height(height)
                            .fillMaxWidth(),
                    ) {
                        leading()
                    }
                }
                Box(
                    Modifier
                        .height(if (height > 0.dp) 8.dp else 1.dp)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    trailing()
                }
            }
            Box(
                Modifier
                    .offset(y = height + 4.dp - 24.dp)
                    .height(48.dp)
                    .fillMaxWidth()
                    .zIndex(1f)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragPx ->
                            drag.value(dragPx / densityNow.value.density, spanNow.value)
                        }
                    }
                    .focusable()
                    .semantics { contentDescription = label },
            )
        }
    }
}

@Composable
private fun AxisSplit(
    axis: PaneAxis,
    leadingDp: Float,
    fallback: (Float) -> Float,
    onSpan: (Float) -> Unit,
    onDrag: (Float, Float) -> Unit,
    leading: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
) {
    if (axis == PaneAxis.SideBySide) {
        DragSplit(leadingDp, fallback, onSpan, onDrag, leading, trailing)
    } else {
        StackSplit(leadingDp, fallback, onSpan, onDrag, leading, trailing)
    }
}

internal fun drawerInboxes(choices: List<AccountChoice>): List<AccountChoice> {
    if (choices.size < 2) return emptyList()
    return choices
}

internal fun drawerBlocks(rows: List<DrawerAccount>): List<DrawerAccount> {
    if (rows.size < 2) return emptyList()
    return rows
}

internal fun postponedDrawerMailbox(value: String): String? {
    if (value.isEmpty()) return null
    return value
}

private fun searchScopeOrCurrent(name: String): SearchScope {
    for (scope in SearchScope.entries) {
        if (scope.name == name) return scope
    }
    return SearchScope.Current
}

@Composable
private fun UpPage(title: String, onUp: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(topBar = { UpTopAppBar(title, onUp) }) { innerPadding ->
        Box(
            Modifier
                .padding(innerPadding)
                .windowInsetsPadding(
                    mailScreenInsets().only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                ),
        ) {
            content()
        }
    }
}

@Composable
private fun InsetPage(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(mailScreenInsets()),
    ) {
        content()
    }
}

/** Renders a custom layout tree; [mail] (the list/message part) is drawn by [mailArea]. */
@Composable
private fun PaneTree(
    node: PaneNode,
    key: String,
    sizes: SnapshotStateMap<String, Float>,
    mail: PaneNode?,
    mailArea: @Composable () -> Unit,
    leaf: @Composable (PaneContent) -> Unit,
) {
    if (mail != null && node == mail) {
        mailArea()
        return
    }
    when (node) {
        is PaneNode.Leaf -> Box(Modifier.fillMaxSize()) { leaf(node.content) }
        is PaneNode.Split -> AxisSplit(
            axis = if (node.axis == SplitAxis.Horizontal) PaneAxis.SideBySide else PaneAxis.TopBottom,
            leadingDp = sizes[key] ?: unsetPaneDp,
            fallback = { span -> ((span - 8f) * node.ratio).coerceAtLeast(0f) },
            onSpan = { _ -> },
            onDrag = { delta, span ->
                val max = (span - 8f).coerceAtLeast(0f)
                val base = sizes[key] ?: ((span - 8f) * node.ratio)
                sizes[key] = (base + delta).coerceIn(0f, max)
            },
            leading = { PaneTree(node.first, key + "0", sizes, mail, mailArea, leaf) },
            trailing = { PaneTree(node.second, key + "1", sizes, mail, mailArea, leaf) },
        )
    }
}

/** F105: the reader route carries the folder's UIDVALIDITY as listed (0 = unknown, captured on first open). */
internal const val ReaderRoutePattern = "reader/{mailbox}/{uid}/{sequence}?epoch={epoch}"

internal fun readerRoute(mailbox: String, uid: Long, sequence: Int, epoch: Long): String =
    "reader/${Uri.encode(mailbox)}/$uid/$sequence?epoch=$epoch"
