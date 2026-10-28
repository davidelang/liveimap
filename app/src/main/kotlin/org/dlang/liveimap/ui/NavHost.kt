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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.davidlang.liveimap.R
import org.dlang.liveimap.engine.sieve.seedCriteria
import org.dlang.liveimap.session.CertPrompt
import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.session.MessageRef
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.session.setMailCertConfirmer
import org.dlang.liveimap.session.setMailPlaintextConfirmer
import org.dlang.liveimap.settings.AccountChoice
import org.dlang.liveimap.settings.planLayout
import org.dlang.liveimap.settings.PaneArrangement
import org.dlang.liveimap.settings.ArrangementChoice
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.DrawerAccount
import org.dlang.liveimap.settings.editors.ExpandedFoldersScreen
import org.dlang.liveimap.settings.FolderFavorite
import org.dlang.liveimap.settings.editors.FolderStartsScreen
import org.dlang.liveimap.settings.editors.FolderViewsScreen
import org.dlang.liveimap.settings.LayoutChoice
import org.dlang.liveimap.settings.MultiPane
import org.dlang.liveimap.settings.LayoutApply
import org.dlang.liveimap.settings.SettingsTree
import org.dlang.liveimap.settings.rememberSettingsGate
import org.dlang.liveimap.settings.ToolbarRevision
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
import org.dlang.liveimap.ui.help.HelpExtendedFab
import org.dlang.liveimap.ui.help.HelpScreen
import org.dlang.liveimap.ui.index.MessageIndexScreen
import org.dlang.liveimap.ui.index.OpenMessageOrder
import org.dlang.liveimap.ui.index.SearchScope
import org.dlang.liveimap.ui.reader.MessageReaderScreen
import org.dlang.liveimap.ui.search.AdvancedSearchScreen
import org.dlang.liveimap.ui.toolbar.ToolbarEditorPage
import org.dlang.liveimap.ui.toolbar.ToolbarScreen

private class PendingCert(
    val prompt: CertPrompt,
    val resume: (Boolean) -> Unit,
)

/** One prompt command at a time. The consumer runs on the main dispatcher and stops when the composition leaves. */
private class PromptTurns {
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    suspend fun consume() {
        for (command in commands) {
            try {
                command()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
            }
        }
    }

    suspend operator fun <T> invoke(block: suspend () -> T): T {
        val result = CompletableDeferred<T>()
        commands.send {
            try {
                result.complete(block())
            } catch (error: Throwable) {
                result.completeExceptionally(error)
                if (error is CancellationException) throw error
            }
        }
        return result.await()
    }
}

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

internal enum class ComposeButton { Hidden, Open, Pick }

internal fun composeButton(route: String, accounts: Set<String>): ComposeButton {
    if (route == "compose" || route == "help" || route == "about" || route == "about/licenses" ||
        route == "settings" || route.startsWith("settings/")
    ) {
        return ComposeButton.Hidden
    }
    val named = accounts.filter { it.isNotEmpty() }
    if (named.size == 1 && named.size == accounts.size) return ComposeButton.Open
    return ComposeButton.Pick
}

internal enum class ComposeFabLayer { Hidden, UnderDrawer, Overlay }

internal fun mailUsesModalDrawer(
    settingsSplit: Boolean,
    hasCustomTree: Boolean,
    drawerOutsideMail: Boolean,
    split: Boolean,
): Boolean {
    if (settingsSplit) return false
    if (hasCustomTree) return !drawerOutsideMail
    return !split
}

internal fun composeFabLayer(choice: ComposeButton, modalDrawer: Boolean): ComposeFabLayer {
    if (choice == ComposeButton.Hidden) return ComposeFabLayer.Hidden
    if (modalDrawer) return ComposeFabLayer.UnderDrawer
    return ComposeFabLayer.Overlay
}

internal class ScreenAccounts {
    private val counts = mutableStateMapOf<String, Int>()

    fun ids(): Set<String> = counts.keys.toSet()

    @Composable
    fun Register(accountId: String) {
        DisposableEffect(accountId) {
            if (accountId.isNotEmpty()) {
                counts[accountId] = (counts[accountId] ?: 0) + 1
            }
            onDispose {
                if (accountId.isEmpty()) return@onDispose
                val left = (counts[accountId] ?: 1) - 1
                if (left <= 0) counts.remove(accountId) else counts[accountId] = left
            }
        }
    }
}

internal val LocalScreenAccounts = staticCompositionLocalOf<ScreenAccounts> {
    error("ScreenAccounts is not provided")
}

@Composable
fun LiveImapNavHost() {
    val gate = rememberSettingsGate()
    if (!gate.isNullOrEmpty()) {
        Text(gate)
    } else if (gate == null) {
        LiveImapNavHostReady()
    }
}

@Composable
private fun LiveImapNavHostReady() {
    var multiPane by remember { mutableStateOf(MultiPane.Off) }
    // Custom panel layout (null = Auto); design-decisions.md "Custom panel layout".
    var customTree by remember { mutableStateOf<PaneNode?>(null) }
    var layoutProblem by remember { mutableStateOf<String?>(null) }
    var layoutAccountId by remember { mutableStateOf("") }
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
    val promptTurn = remember { PromptTurns() }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Main.immediate) { promptTurn.consume() }
    }
    DisposableEffect(Unit) {
        setMailCertConfirmer { prompt -> promptTurn {
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
        setMailPlaintextConfirmer { promptTurn {
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
    val composeForward = rememberSaveable { mutableStateOf("") }

    val filterEditIndex = rememberSaveable { mutableStateOf(-1) }
    val filterSeedFrom = rememberSaveable { mutableStateOf("") }
    val filterSeedTo = rememberSaveable { mutableStateOf("") }
    val filterSeedListId = rememberSaveable { mutableStateOf("") }
    val filterSeedSubject = rememberSaveable { mutableStateOf("") }
    val filterAccountId = rememberSaveable { mutableStateOf("") }
    val filterRuleId = rememberSaveable { mutableStateOf("") }
    val filterListAccount = rememberSaveable { mutableStateOf("") }
    var editingFavorite by remember { mutableStateOf<FolderFavorite?>(null) }
    var favoriteDraft by remember { mutableStateOf("") }
    // F121: the favorite being edited belongs to one account; edits go to that account, never the active one
    var editingAccountId by remember { mutableStateOf<String?>(null) }
    var editingFavorites by remember { mutableStateOf<List<FolderFavorite>>(emptyList()) }
    var editingWhere by remember { mutableStateOf(FavoriteWhere("", "", "")) }
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

    suspend fun applyRunningLayout() {
        val account = store.load()
        multiPane = account.multiPane
        val decoded = decodePaneLayout(account.customLayout)
        val problem = decoded?.let { paneLayoutProblem(it) }
        customTree = if (problem == null) decoded else null
        layoutProblem = if (problem == null || decoded == null) {
            null
        } else {
            appContext.getString(
                R.string.pane_layout_problem,
                appContext.getString(
                    when (problem) {
                        org.dlang.liveimap.settings.PaneLayoutProblem.NoMessageList -> R.string.layout_problem_no_list
                        org.dlang.liveimap.settings.PaneLayoutProblem.Duplicate -> R.string.layout_problem_duplicate
                        org.dlang.liveimap.settings.PaneLayoutProblem.MessageNotBesideList -> R.string.layout_problem_message
                    },
                ),
            )
        }
        treeDp.clear()
        folderPane = account.folderPane
        foldPosture = account.foldPosture
        arrangement = account.paneArrangement
        folderPosition = account.folderPosition
        LayoutApply.noteFullScreen()
    }

    suspend fun applyOpenAccount() {
        val account = store.load()
        val drawers = store.listDrawerAccounts()
        drawerAccounts = drawers
        header = drawers.firstOrNull { it.chosen }?.name ?: org.dlang.liveimap.accounts.accountVisibleName(account)
        layoutAccountId = drawers.firstOrNull { it.chosen }?.id.orEmpty()
        if (editingFavorite == null) favorites = account.favorites
        postponedMailbox = account.postponedMailbox
        textZoom = account.readerTextZoom
        NonAsciiText.active = account.nonAsciiDisplay
        accounts = store.listAccounts()
    }

    LaunchedEffect(route, drawerState.currentValue) {
        applyOpenAccount()
    }

    LaunchedEffect(LayoutApply.generation) {
        applyRunningLayout()
    }

    LaunchedEffect(Unit) {
        store.chosenAccountIdFlow().collect {
            OpenMessageOrder.clear()
            applyOpenAccount()
            applyRunningLayout()
        }
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

    fun openFilterEditor(
        index: Int,
        from: String,
        to: String,
        listId: String,
        subject: String,
        accountId: String,
        ruleId: String,
    ) {
        filterEditIndex.value = index
        filterSeedFrom.value = from
        filterSeedTo.value = to
        filterSeedListId.value = listId
        filterSeedSubject.value = subject
        filterAccountId.value = accountId
        filterRuleId.value = ruleId
        navController.navigate("filter")
    }

    fun openCompose(seed: ComposeSeed) {
        composeKindName.value = seed.kind.name
        composeMailbox.value = seed.mailbox.orEmpty()
        composeUids.value = seed.uids.joinToString(",")
        composeEpoch.value = seed.uidValidity
        composeAccount.value = seed.accountId
        composeForward.value = when (seed.forwardAsAttachment) {
            null -> ""
            true -> "true"
            false -> "false"
        }
        composeUnsentId.value = ""
        composeRetryOnOpen.value = false
        navController.navigate("compose")
    }

    fun openToolbar(name: String) {
        navController.navigate("toolbar/$name")
    }

    // NavHost remembers the builder. A new lambda each pass would replace the graph and drop the stack.
    val folderListPane: @Composable () -> Unit = {
                    FolderListScreen(
                        onOpenMailbox = { mailbox ->
                            navController.navigate("index/${Uri.encode(mailbox)}")
                        },
                        onCompose = { seed -> openCompose(seed) },
                        onOpenUnsent = { navController.navigate("unsent") },
                        onOpenHelp = { navController.navigate("help") },
                        onCustomize = { openToolbar("folders") },
                        focusMailbox = focusMailbox.value,
                        focusToken = focusToken.value,
                        onOpenDrawer = openDrawerState.value,
                    )
    }
    val foldersAboveNow = rememberUpdatedState(foldersAbove)
    val navGraph: NavGraphBuilder.() -> Unit = remember {
        {
            composable("folders") {
                FolderListScreen(
                    onOpenMailbox = { mailbox ->
                        navController.navigate("index/${Uri.encode(mailbox)}")
                    },
                    onCompose = { seed -> openCompose(seed) },
                    onOpenUnsent = { navController.navigate("unsent") },
                    onOpenHelp = { navController.navigate("help") },
                    onCustomize = { openToolbar("folders") },
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
                var paneMailbox by rememberSaveable { mutableStateOf("") }
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
                fun openMessage(ref: MessageRef, sequence: Int) {
                    val target = ref.mailbox.ifEmpty { mailbox }
                    if (useSplit && target == mailbox) {
                        paneMailbox = target
                        paneUid = ref.uid
                        paneSequence = sequence
                        paneEpoch = ref.uidValidity
                    } else {
                        navController.navigate(readerRoute(target, ref.uid, sequence, ref.uidValidity))
                    }
                }
                @Composable
                fun IndexBody(viewKey: Int, watch: Boolean) {
                    key(viewKey) {
                        MessageIndexScreen(
                            mailbox = mailbox,
                            onOpen = { ref, sequence -> openMessage(ref, sequence) },
                            onCompose = { seed -> openCompose(seed) },
                            onBack = { failure ->
                                if (!failure.isNullOrEmpty()) IndexLeaveReport.report(failure)
                                navController.popBackStack()
                            },
                            onCustomize = { openToolbar("index") },
                            onCustomizeSelection = { openToolbar("selection") },
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
                                        folderListPane()
                                    }
                                    Box(Modifier.height(8.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outlineVariant))
                                    Box(Modifier.weight(0.65f).fillMaxWidth()) { IndexBody(shownViewToken, watch = false) }
                                }
                            } else {
                                ListSide(shownViewToken, watch = false)
                            }
                        
                    }
                    val readerSide: @Composable () -> Unit = {
                            key(paneMailbox, paneUid, paneEpoch) {
                                MessageReaderScreen(
                                    mailbox = paneMailbox.ifEmpty { mailbox },
                                    uid = paneUid,
                                    sequence = paneSequence,
                                    uidValidity = paneEpoch,
                                    onCompose = { seed -> openCompose(seed) },
                                    onAdvance = { next, nextSequence ->
                                        paneMailbox = next.mailbox.ifEmpty { paneMailbox.ifEmpty { mailbox } }
                                        paneUid = next.uid
                                        paneSequence = nextSequence
                                        paneEpoch = next.uidValidity
                                    },
                                    onBack = { paneUid = -1L },
                                    onFolderViewSaved = { noteFolderView() },
                                    onCustomize = { openToolbar("reader") },
                                    onFilterLike = { from, to, listId, subject, accountId ->
                                        openFilterEditor(-1, from, to, listId, subject, accountId, "")
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
                    key(ToolbarRevision.value) {
                        ToolbarEditorPage(ToolbarScreen.Index)
                    }
                }
            }
            composable("toolbar/selection") {
                UpPage(stringResource(R.string.toolbar_customize), onUp = { navController.popBackStack() }) {
                    key(ToolbarRevision.value) {
                        ToolbarEditorPage(ToolbarScreen.Selection)
                    }
                }
            }
            composable("toolbar/folders") {
                UpPage(stringResource(R.string.toolbar_customize), onUp = { navController.popBackStack() }) {
                    key(ToolbarRevision.value) {
                        ToolbarEditorPage(ToolbarScreen.Folders)
                    }
                }
            }
            composable("toolbar/reader") {
                UpPage(stringResource(R.string.toolbar_customize), onUp = { navController.popBackStack() }) {
                    key(ToolbarRevision.value) {
                        ToolbarEditorPage(ToolbarScreen.Reader)
                    }
                }
            }
            composable("toolbar/compose") {
                UpPage(stringResource(R.string.toolbar_customize), onUp = { navController.popBackStack() }) {
                    key(ToolbarRevision.value) {
                        ToolbarEditorPage(ToolbarScreen.Compose)
                    }
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
                    onAdvance = { next, nextSequence ->
                        navController.navigate(
                            readerRoute(
                                next.mailbox.ifEmpty { mailbox },
                                next.uid,
                                nextSequence,
                                next.uidValidity,
                            ),
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
                    onCustomize = { openToolbar("reader") },
                    onFilterLike = { from, to, listId, subject, accountId ->
                        openFilterEditor(-1, from, to, listId, subject, accountId, "")
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
                    forwardAsAttachment = navForwardChoice(composeForward.value),
                )
                ComposeScreen(
                    seed = seed,
                    onDone = { navController.popBackStack() },
                    unsentId = composeUnsentId.value.takeIf { it.isNotEmpty() },
                    retryOnOpen = composeRetryOnOpen.value,
                    onOpenUnsent = { navController.navigate("unsent") },
                    onCustomize = { openToolbar("compose") },
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
                            composeForward.value = ""
                            navController.navigate("compose")
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable("settings") {
                UpPage(stringResource(R.string.drawer_settings), onUp = { navController.navigateUp() }) {
                    SettingsTree(
                        onCopyContacts = { navController.navigate("contacts") },
                        onEditBook = { navController.navigate("addressbook") },
                        onOpenPanel = { name, _ -> openToolbar(name) },
                        onNavigate = { route -> navController.navigate(route) },
                        page = "top",
                    )
                }
            }
            composable("settings/global") {
                UpPage(stringResource(R.string.settings_all_accounts), onUp = { navController.popBackStack() }) {
                    SettingsTree(
                        onCopyContacts = { navController.navigate("contacts") },
                        onEditBook = { navController.navigate("addressbook") },
                        onOpenPanel = { name, _ -> openToolbar(name) },
                        onNavigate = { route -> navController.navigate(route) },
                        page = "ui",
                    )
                }
            }
            composable("settings/accounts") {
                UpPage(stringResource(R.string.settings_accounts), onUp = { navController.popBackStack() }) {
                    SettingsTree(
                        onCopyContacts = { navController.navigate("contacts") },
                        onEditBook = { navController.navigate("addressbook") },
                        onOpenPanel = { name, _ -> openToolbar(name) },
                        onNavigate = { route -> navController.navigate(route) },
                        page = "accounts",
                    )
                }
            }
            composable(
                route = "settings/account/{accountId}/editor/{editorId}",
                arguments = listOf(
                    navArgument("accountId") { type = NavType.StringType },
                    navArgument("editorId") { type = NavType.StringType },
                ),
            ) { entry ->
                val accountId = entry.arguments?.getString("accountId").orEmpty()
                val editorId = entry.arguments?.getString("editorId").orEmpty()
                UpPage(stringResource(R.string.drawer_settings), onUp = { navController.popBackStack() }) {
                    SettingsTree(
                        onCopyContacts = { navController.navigate("contacts") },
                        onEditBook = { navController.navigate("addressbook") },
                        onOpenPanel = { name, _ -> openToolbar(name) },
                        onNavigate = { route -> navController.navigate(route) },
                        page = "editor",
                        accountId = accountId,
                        editorId = editorId,
                    )
                }
            }
            composable(
                route = "settings/account/{accountId}",
                arguments = listOf(navArgument("accountId") { type = NavType.StringType }),
            ) { entry ->
                val accountId = entry.arguments?.getString("accountId").orEmpty()
                UpPage(stringResource(R.string.settings_account), onUp = { navController.popBackStack() }) {
                    SettingsTree(
                        onCopyContacts = { navController.navigate("contacts") },
                        onEditBook = { navController.navigate("addressbook") },
                        onOpenPanel = { name, _ -> openToolbar(name) },
                        onNavigate = { route -> navController.navigate(route) },
                        page = "account",
                        accountId = accountId,
                    )
                }
            }
            composable("folderLists/expanded") {
                UpPage(stringResource(R.string.settings_expanded_folders), onUp = {
                    navController.popBackStack("settings", inclusive = false)
                }) {
                    ExpandedFoldersScreen()
                }
            }
            composable("folderLists/views") {
                UpPage(stringResource(R.string.settings_folder_views), onUp = {
                    navController.popBackStack("settings", inclusive = false)
                }) {
                    FolderViewsScreen()
                }
            }
            composable("folderLists/starts") {
                UpPage(stringResource(R.string.settings_start_per_folder), onUp = {
                    navController.popBackStack("settings", inclusive = false)
                }) {
                    FolderStartsScreen()
                }
            }
            composable("addressbook") {
                UpPage(stringResource(R.string.settings_edit_book), onUp = { navController.popBackStack() }) {
                    org.dlang.liveimap.ui.contacts.AddressBookScreen()
                }
            }
            composable("contacts") {
                UpPage(stringResource(R.string.settings_copy_contacts), onUp = { navController.popBackStack() }) {
                    ContactCopyScreen(registerAccount = true)
                }
            }
            composable("filters") {
                val accountId = rememberSaveable { filterListAccount.value }
                UpPage(stringResource(R.string.filter_edit_list), onUp = { navController.popBackStack() }) {
                    FilterListScreen(accountId = accountId, onOpen = { index, ruleId ->
                        openFilterEditor(index, "", "", "", "", accountId, ruleId)
                    })
                }
            }
            composable("filter") {
                val index = rememberSaveable { filterEditIndex.value }
                val from = rememberSaveable { filterSeedFrom.value }
                val to = rememberSaveable { filterSeedTo.value }
                val listId = rememberSaveable { filterSeedListId.value }
                val subject = rememberSaveable { filterSeedSubject.value }
                val accountId = rememberSaveable { filterAccountId.value }
                val ruleId = rememberSaveable { filterRuleId.value }
                UpPage(
                    title = stringResource(if (index < 0) R.string.filter_add else R.string.filter_edit),
                    onUp = { navController.popBackStack() },
                ) {
                    FilterEditorScreen(
                        accountId = accountId,
                        ruleId = ruleId,
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
            store.forAccount(id).update { it.copy(favorites = next) }
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
                    openFilterEditor(-1, "", "", "", "", store.chosenAccountId().orEmpty(), "")
                }
            },
            onEditFilters = {
                navigateFromDrawer {
                    filterListAccount.value = store.chosenAccountId().orEmpty()
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

    val screenAccounts = remember { ScreenAccounts() }
    var composeAccounts by remember { mutableStateOf<List<AccountChoice>?>(null) }
    var composeLayer = ComposeFabLayer.Hidden

    @Composable
    fun BoxScope.ComposeFab(modifier: Modifier = Modifier) {
        HelpExtendedFab(
            label = stringResource(R.string.screen_compose),
            help = stringResource(R.string.help_screen_compose),
            page = "writing.html",
            onClick = {
                val ids = screenAccounts.ids()
                when (composeButton(route.orEmpty(), ids)) {
                    ComposeButton.Hidden -> Unit
                    ComposeButton.Open -> openCompose(
                        ComposeSeed(ComposeKind.New, null, emptyList(), accountId = ids.single()),
                    )
                    ComposeButton.Pick -> scope.launch { composeAccounts = store.listAccounts() }
                }
            },
            expanded = true,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(
                    mailScreenInsets().only(WindowInsetsSides.Bottom + WindowInsetsSides.End),
                )
                .padding(16.dp)
                .then(modifier),
            icon = {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.screen_compose),
                )
            },
        )
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
                Box(Modifier.fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = "folders",
                        modifier = Modifier.fillMaxSize(),
                        builder = navGraph,
                    )
                    if (composeLayer == ComposeFabLayer.UnderDrawer) {
                        ComposeFab()
                    }
                }
            }
        }
    }
    val drawerPanel: @Composable () -> Unit = {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .windowInsetsPadding(
                    mailScreenInsets().only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
                ),
        ) { drawerSheet() }
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
                Box(Modifier.fillMaxSize()) {
                    NavHost(navController = navController, startDestination = "folders", modifier = Modifier.fillMaxSize(), builder = navGraph)
                    if (composeLayer == ComposeFabLayer.UnderDrawer) {
                        ComposeFab()
                    }
                }
            }
        }
    }
    CompositionLocalProvider(LocalScreenAccounts provides screenAccounts) {
    LiveImapScaffold {
        Box(Modifier.fillMaxSize()) {
        val problem = layoutProblem
        if (problem != null) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(8.dp)
                    .fillMaxWidth(),
            ) {
                Text(problem)
                TextButton(onClick = {
                    val id = layoutAccountId
                    if (id.isNotEmpty()) {
                        navController.navigate("settings/account/$id/editor/paneLayout")
                    }
                }) {
                    Text(stringResource(R.string.pane_layout_edit))
                }
            }
        }
        val composeChoice = composeButton(route.orEmpty(), screenAccounts.ids())
        val modalDrawer = mailUsesModalDrawer(
            settingsSplit = false,
            hasCustomTree = customTree != null,
            drawerOutsideMail = drawerOutsideMail,
            split = split,
        )
        composeLayer = composeFabLayer(composeChoice, modalDrawer)
        LaunchedEffect(composeChoice) {
            if (composeChoice != ComposeButton.Pick) composeAccounts = null
        }
        val tree = customTree
        if (tree != null) {
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
        if (composeLayer == ComposeFabLayer.Overlay) {
            ComposeFab(Modifier.zIndex(2f))
        }
        val listed = composeAccounts
        if (listed != null && composeChoice == ComposeButton.Pick) {
            AlertDialog(
                onDismissRequest = { composeAccounts = null },
                title = { Text(stringResource(R.string.screen_compose)) },
                text = {
                    if (listed.isEmpty()) {
                        Text(stringResource(R.string.folders_no_account))
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            for (account in listed) {
                                Text(
                                    text = account.name,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            openCompose(
                                                ComposeSeed(
                                                    ComposeKind.New,
                                                    null,
                                                    emptyList(),
                                                    accountId = account.id,
                                                ),
                                            )
                                            composeAccounts = null
                                        }
                                        .padding(vertical = 12.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { composeAccounts = null }) {
                        Text(stringResource(R.string.drawer_cancel))
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
internal fun FavoriteEditDialog(
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

internal fun paneControlsEnabled(mode: MultiPane) = mode != MultiPane.Off


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
        val wider = stringResource(R.string.pane_wider)
        val narrower = stringResource(R.string.pane_narrower)
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
                    .semantics {
                        contentDescription = label
                        customActions = listOf(
                            CustomAccessibilityAction(wider) {
                                onDrag(48f, span)
                                true
                            },
                            CustomAccessibilityAction(narrower) {
                                onDrag(-48f, span)
                                true
                            },
                        )
                    },
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
        val taller = stringResource(R.string.pane_taller)
        val shorter = stringResource(R.string.pane_shorter)
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
                    .semantics {
                        contentDescription = label
                        customActions = listOf(
                            CustomAccessibilityAction(taller) {
                                onDrag(48f, span)
                                true
                            },
                            CustomAccessibilityAction(shorter) {
                                onDrag(-48f, span)
                                true
                            },
                        )
                    },
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UpPage(title: String, onUp: () -> Unit, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    var coversStatus by remember { mutableStateOf(true) }
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Box(
                Modifier.onGloballyPositioned { coords ->
                    val next = coords.positionInWindow().y < statusTop
                    if (next != coversStatus) coversStatus = next
                },
            ) {
                val back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
                val up: () -> Unit = {
                    if (UnsavedEdits.pending && back != null) back.onBackPressed() else onUp()
                }
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = up) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.scaffold_up),
                            )
                        }
                    },
                    windowInsets = if (coversStatus) {
                        TopAppBarDefaults.windowInsets
                    } else {
                        WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal)
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(
            Modifier
                .padding(innerPadding)
                .windowInsetsPadding(
                    WindowInsets.navigationBars.union(
                        WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal),
                    ),
                ),
        ) {
            content()
        }
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

internal fun navForwardChoice(stored: String): Boolean? = when (stored) {
    "true" -> true
    "false" -> false
    else -> null
}

internal fun readerRoute(mailbox: String, uid: Long, sequence: Int, epoch: Long): String =
    "reader/${Uri.encode(mailbox)}/$uid/$sequence?epoch=$epoch"
