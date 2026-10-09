package org.dlang.liveimap.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
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

@Composable
fun LiveImapNavHost() {
    var multiPane by remember { mutableStateOf(MultiPane.Off) }
    val split = useMultiPane(multiPane)
    val splitNow = rememberUpdatedState(split)
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    val certPrompt = remember { mutableStateOf<PendingCert?>(null) }
    DisposableEffect(Unit) {
        setMailCertConfirmer { prompt ->
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { cont ->
                    certPrompt.value = PendingCert(prompt) { accepted ->
                        certPrompt.value = null
                        if (cont.isActive) cont.resume(accepted)
                    }
                    cont.invokeOnCancellation { certPrompt.value = null }
                }
            }
        }
        onDispose {
            val pending = certPrompt.value
            certPrompt.value = null
            pending?.resume(false)
            setMailCertConfirmer(null)
        }
    }
    val plaintextPrompt = remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    DisposableEffect(Unit) {
        setMailPlaintextConfirmer {
            withContext(Dispatchers.Main.immediate) {
                suspendCancellableCoroutine { cont ->
                    plaintextPrompt.value = { accepted ->
                        plaintextPrompt.value = null
                        if (cont.isActive) cont.resume(accepted)
                    }
                    cont.invokeOnCancellation { plaintextPrompt.value = null }
                }
            }
        }
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
    val composeUnsentId = rememberSaveable { mutableStateOf("") }
    val composeRetryOnOpen = rememberSaveable { mutableStateOf(false) }
    val filterEditIndex = rememberSaveable { mutableStateOf(-1) }
    val filterSeedFrom = rememberSaveable { mutableStateOf("") }
    val filterSeedTo = rememberSaveable { mutableStateOf("") }
    val filterSeedListId = rememberSaveable { mutableStateOf("") }
    val filterSeedSubject = rememberSaveable { mutableStateOf("") }
    var editingFavorite by remember { mutableStateOf<FolderFavorite?>(null) }
    var favoriteDraft by remember { mutableStateOf("") }
    val favoriteMutex = remember { Mutex() }
    val availableDpState = remember { mutableIntStateOf(0) }
    var readerOpen by remember { mutableStateOf(false) }
    var userSized by rememberSaveable { mutableStateOf(false) }
    var drawerWidthDp by rememberSaveable { mutableFloatStateOf(unsetPaneDp) }
    var indexWidthDp by rememberSaveable { mutableFloatStateOf(unsetPaneDp) }
    val openDrawerState = rememberUpdatedState<(() -> Unit)?>(
        if (!split) {
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
        val readerMailbox = readerArgs.getString("mailbox")?.let(Uri::decode) ?: return@LaunchedEffect
        val indexMailbox = previousArgs.getString("mailbox")?.let(Uri::decode) ?: return@LaunchedEffect
        if (readerMailbox != indexMailbox) return@LaunchedEffect
        previous.savedStateHandle["paneUid"] = readerArgs.getLong("uid")
        previous.savedStateHandle["paneSequence"] = readerArgs.getInt("sequence")
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
    val navGraph: NavGraphBuilder.() -> Unit = remember {
        {
            fun openCompose(seed: ComposeSeed) {
                composeKindName.value = seed.kind.name
                composeMailbox.value = seed.mailbox.orEmpty()
                composeUids.value = seed.uids.joinToString(",")
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
                )
            }
            composable(
                route = "index/{mailbox}",
                arguments = listOf(
                    navArgument("mailbox") { type = NavType.StringType },
                ),
            ) { entry ->
                val encoded = entry.arguments?.getString("mailbox") ?: return@composable
                val mailbox = Uri.decode(encoded)
                val useSplit = splitNow.value
                var paneUid by rememberSaveable { mutableStateOf(-1L) }
                var paneSequence by rememberSaveable { mutableIntStateOf(0) }
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
                    if (foldedUid == null) return@LaunchedEffect
                    paneSequence = foldedSequence
                    paneUid = foldedUid
                }
                LaunchedEffect(useSplit, paneUid, paneSequence) {
                    if (!useSplit && paneUid >= 0L) {
                        val uid = paneUid
                        val sequence = paneSequence
                        paneUid = -1L
                        navController.navigate("reader/${Uri.encode(mailbox)}/$uid/$sequence")
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
                    drawerWidthDp = initialDrawerDp(available, open).toFloat()
                }
                DisposableEffect(Unit) {
                    onDispose {
                        readerOpen = false
                        val available = availableDpState.intValue
                        if (!userSized && available > 0) {
                            drawerWidthDp = initialDrawerDp(available, false).toFloat()
                        }
                    }
                }
                LaunchedEffect(useSplit, paneUid, contentSpan, userSized) {
                    if (useSplit && paneUid >= 0L && !userSized && contentSpan > 8f) {
                        indexWidthDp = (contentSpan - 8f) / 2f
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
                fun openMessage(uid: Long, sequence: Int, rowMailbox: String) {
                    val target = if (rowMailbox.isEmpty()) mailbox else rowMailbox
                    if (useSplit && target == mailbox) {
                        paneUid = uid
                        paneSequence = sequence
                    } else {
                        navController.navigate("reader/${Uri.encode(target)}/$uid/$sequence")
                    }
                }
                @Composable
                fun IndexBody(viewKey: Int, watch: Boolean) {
                    key(viewKey) {
                        MessageIndexScreen(
                            mailbox = mailbox,
                            onOpen = { uid, sequence, rowMailbox -> openMessage(uid, sequence, rowMailbox) },
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
                BackHandler(enabled = useSplit && paneUid >= 0L) {
                    paneUid = -1L
                }
                if (!useSplit) {
                    IndexBody(folderViewToken, watch = true)
                } else if (paneUid < 0L) {
                    IndexBody(shownViewToken, watch = true)
                } else {
                    DragSplit(
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
                        leading = { IndexBody(shownViewToken, watch = false) },
                        trailing = {
                            key(paneUid) {
                                MessageReaderScreen(
                                    mailbox = mailbox,
                                    uid = paneUid,
                                    sequence = paneSequence,
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
                val mailbox = Uri.decode(encoded)
                AdvancedSearchScreen(
                    mailbox = mailbox,
                    onBack = { navController.popBackStack() },
                    onSearch = { text, scope ->
                        val previous = navController.previousBackStackEntry
                        val indexMailbox = previous?.arguments?.getString("mailbox")?.let(Uri::decode)
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
                route = "reader/{mailbox}/{uid}/{sequence}",
                arguments = listOf(
                    navArgument("mailbox") { type = NavType.StringType },
                    navArgument("uid") { type = NavType.LongType },
                    navArgument("sequence") { type = NavType.IntType },
                ),
            ) { entry ->
                val args = entry.arguments ?: return@composable
                val encoded = args.getString("mailbox") ?: return@composable
                val mailbox = Uri.decode(encoded)
                MessageReaderScreen(
                    mailbox = mailbox,
                    uid = args.getLong("uid"),
                    sequence = args.getInt("sequence"),
                    onCompose = { seed -> openCompose(seed) },
                    onAdvance = { nextUid, nextSequence ->
                        navController.navigate(
                            "reader/${Uri.encode(mailbox)}/$nextUid/$nextSequence",
                        ) {
                            popUpTo("reader/{mailbox}/{uid}/{sequence}") { inclusive = true }
                        }
                    },
                    onBack = { navController.popBackStack() },
                    onFolderViewSaved = {
                        val previous = navController.previousBackStackEntry
                        val indexMailbox = previous?.arguments?.getString("mailbox")?.let(Uri::decode)
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
                        onOpenCopy = { id, retry ->
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
                    SettingsGroupList(onOpen = { group ->
                        navController.navigate("settings/${group.route}")
                    })
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
                            onOpenPanel = { name -> navController.navigate("toolbar/$name") },
                        )
                    }
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
                val loaded = store.load()
                store.save(loaded.copy(favorites = next))
            }
        }
    }

    fun openFavoriteEditor(favorite: FolderFavorite) {
        editingFavorite = favorite
        favoriteDraft = favoriteDrawerLabel(favorite)
    }

    fun moveEditingFavorite(delta: Int) {
        val current = editingFavorite ?: return
        val index = favorites.indexOfFirst { it.node == current.node && it.mailbox == current.mailbox }
        if (index < 0) return
        val target = index + delta
        if (target !in favorites.indices) return
        val next = favorites.toMutableList()
        val swap = next[target]
        next[target] = next[index]
        next[index] = swap
        persistFavorites(next)
    }

    fun saveEditingFavorite() {
        val current = editingFavorite ?: return
        val trimmed = favoriteDraft.trim()
        val next = favorites.map { item ->
            if (item.node == current.node && item.mailbox == current.mailbox) {
                item.copy(label = trimmed)
            } else {
                item
            }
        }
        persistFavorites(next)
        editingFavorite = null
    }

    fun deleteEditingFavorite() {
        val current = editingFavorite ?: return
        val next = favorites.filterNot { it.node == current.node && it.mailbox == current.mailbox }
        persistFavorites(next)
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
        scope.launch {
            store.selectAccount(account.id)
            applyOpenAccount()
            openFavoriteEditor(favorite)
        }
    }

    val drawerSheet: @Composable ColumnScope.() -> Unit = {
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

    LiveImapScaffold {
        if (split) {
            DragSplit(
                leadingDp = drawerWidthDp,
                fallback = { span ->
                    initialDrawerDp(span.roundToInt(), readerOpen).toFloat()
                },
                onSpan = { availableDpState.intValue = it.roundToInt() },
                onDrag = { delta, span ->
                    userSized = true
                    val max = (span - 8f).coerceAtLeast(0f)
                    val base = if (drawerWidthDp < 0f) {
                        initialDrawerDp(span.roundToInt(), readerOpen).toFloat()
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
                            )
                            .verticalScroll(rememberScrollState()),
                    ) {
                        drawerSheet()
                    }
                },
                trailing = {
                    val resolvedDrawerDp = if (drawerWidthDp >= 0f) {
                        drawerWidthDp
                    } else {
                        initialDrawerDp(availableDpState.intValue, readerOpen).toFloat()
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
        val editing = editingFavorite
        if (editing != null) {
            BackHandler {
                editingFavorite = null
            }
            FavoriteEditDialog(
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
                    Column {
                        if (changed) Text(stringResource(R.string.cert_changed_body))
                        Text(pendingCert.prompt.subject)
                        Text(pendingCert.prompt.issuer)
                        Text(pendingCert.prompt.notBefore)
                        Text(pendingCert.prompt.notAfter)
                        Text(pendingCert.prompt.fingerprint)
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
        Text(
            text = header,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.drawer_inbox)) },
            selected = false,
            onClick = onInbox,
        )
        val postponedRow = postponedDrawerMailbox(postponedMailbox)
        if (postponedRow != null) {
            NavigationDrawerItem(
                label = { Text(postponedRow) },
                selected = false,
                modifier = Modifier.semantics { contentDescription = postponedDescription },
                onClick = { onPostponed(postponedRow) },
            )
        }
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.drawer_all_folders)) },
            selected = false,
            onClick = onAllFolders,
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.filter_add)) },
            selected = false,
            onClick = onAddFilter,
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.filter_edit_list)) },
            selected = false,
            onClick = onEditFilters,
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
                            onClick = { onFavorite(favorite) },
                            onLongClick = { onEditFavorite(favorite) },
                        )
                        .semantics { contentDescription = shown },
                )
            }
        }
    } else {
        for (account in blocks) {
            Text(
                text = account.name,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.drawer_inbox)) },
                selected = false,
                onClick = { onAccountInbox(account) },
            )
            val postponedRow = postponedDrawerMailbox(account.postponedMailbox)
            if (postponedRow != null) {
                NavigationDrawerItem(
                    label = { Text(postponedRow) },
                    selected = false,
                    modifier = Modifier.semantics { contentDescription = postponedDescription },
                    onClick = { onAccountPostponed(account) },
                )
            }
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.drawer_all_folders)) },
                selected = false,
                onClick = { onAccountAllFolders(account) },
            )
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
                                onClick = { onAccountFavorite(account, favorite) },
                                onLongClick = { onAccountEditFavorite(account, favorite) },
                            )
                            .semantics { contentDescription = shown },
                    )
                }
            }
        }
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.filter_add)) },
            selected = false,
            onClick = onAddFilter,
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.filter_edit_list)) },
            selected = false,
            onClick = onEditFilters,
        )
    }
    HorizontalDivider()
    NavigationDrawerItem(
        label = { Text(stringResource(R.string.drawer_settings)) },
        selected = false,
        onClick = onSettings,
    )
    NavigationDrawerItem(
        label = { Text(stringResource(R.string.help_title)) },
        selected = false,
        onClick = onHelp,
    )
    NavigationDrawerItem(
        label = { Text(stringResource(R.string.about_title)) },
        selected = false,
        onClick = onAbout,
    )
}

@Composable
private fun FavoriteEditDialog(
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
            OutlinedTextField(
                value = name,
                onValueChange = onName,
                label = { Text(stringResource(R.string.drawer_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
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

internal fun favoriteDrawerLabel(favorite: FolderFavorite): String {
    if (favorite.label.isNotBlank()) return favorite.label
    return favoriteLabel(favorite.node, favorite.mailbox, favorite.delimiter)
}

internal fun foldReaderIntoIndex(expanded: Boolean, route: String?): Boolean {
    return expanded && route == "reader/{mailbox}/{uid}/{sequence}"
}

internal fun useMultiPane(mode: MultiPane): Boolean = mode == MultiPane.Wide

internal fun useFolderPane(choice: LayoutChoice): Boolean = choice == LayoutChoice.On

internal fun useFoldPosture(choice: LayoutChoice): Boolean = choice == LayoutChoice.On

internal fun initialDrawerDp(availableDp: Int, readerOpen: Boolean): Int {
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
                Box(
                    Modifier
                        .width(8.dp)
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
