package org.dlang.liveimap.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffold
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.window.core.layout.WindowWidthSizeClass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dlang.liveimap.R
import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.ExpandedFoldersScreen
import org.dlang.liveimap.settings.FolderFavorite
import org.dlang.liveimap.settings.FolderStartsScreen
import org.dlang.liveimap.settings.FolderViewsScreen
import org.dlang.liveimap.settings.SettingsGroup
import org.dlang.liveimap.settings.SettingsGroupList
import org.dlang.liveimap.settings.SettingsGroupScreen
import org.dlang.liveimap.settings.favoriteLabel
import org.dlang.liveimap.ui.about.AboutScreen
import org.dlang.liveimap.ui.about.LicensesScreen
import org.dlang.liveimap.ui.compose.ComposeScreen
import org.dlang.liveimap.ui.contacts.ContactCopyScreen
import org.dlang.liveimap.ui.compose.UnsentScreen
import org.dlang.liveimap.ui.folder.FolderListModel
import org.dlang.liveimap.ui.folder.FolderListScreen
import org.dlang.liveimap.ui.help.HelpScreen
import org.dlang.liveimap.ui.index.MessageIndexScreen
import org.dlang.liveimap.ui.index.SearchScope
import org.dlang.liveimap.ui.reader.MessageReaderScreen
import org.dlang.liveimap.ui.search.AdvancedSearchScreen
import org.dlang.liveimap.ui.toolbar.ToolbarEditorScreen
import org.dlang.liveimap.ui.toolbar.ToolbarScreen

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun LiveImapNavHost() {
    val expandedWindow =
        currentWindowAdaptiveInfo().windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.EXPANDED
    val expandedNow = rememberUpdatedState(expandedWindow)
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val currentEntry by navController.currentBackStackEntryAsState()
    val route = currentEntry?.destination?.route
    val drawerGestures = route == "folders"
    var header by remember { mutableStateOf("") }
    var favorites by remember { mutableStateOf<List<FolderFavorite>>(emptyList()) }
    var postponedMailbox by remember { mutableStateOf("") }
    val focusMailbox = remember { mutableStateOf<String?>(null) }
    val focusToken = remember { mutableStateOf(0) }
    val composeKindName = rememberSaveable { mutableStateOf(ComposeKind.New.name) }
    val composeMailbox = rememberSaveable { mutableStateOf("") }
    val composeUids = rememberSaveable { mutableStateOf("") }
    val composeUnsentId = rememberSaveable { mutableStateOf("") }
    val composeRetryOnOpen = rememberSaveable { mutableStateOf(false) }
    var editingFavorite by remember { mutableStateOf<FolderFavorite?>(null) }
    var favoriteDraft by remember { mutableStateOf("") }
    val favoriteMutex = remember { Mutex() }
    val openDrawerState = rememberUpdatedState<(() -> Unit)?>(
        if (expandedWindow) {
            null
        } else {
            { scope.launch { drawerState.open() } }
        },
    )

    LaunchedEffect(route, drawerState.currentValue) {
        val account = store.load()
        header = if (account.email.isNotBlank()) account.email else account.username
        if (editingFavorite == null) favorites = account.favorites
        postponedMailbox = account.postponedMailbox
    }

    LaunchedEffect(expandedWindow, route, currentEntry?.id) {
        if (!foldReaderIntoIndex(expandedWindow, route)) return@LaunchedEffect
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
                val expanded = expandedNow.value
                var paneUid by rememberSaveable { mutableStateOf(-1L) }
                var paneSequence by rememberSaveable { mutableIntStateOf(0) }
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
                LaunchedEffect(expanded, paneUid, paneSequence) {
                    if (!expanded && paneUid >= 0L) {
                        val uid = paneUid
                        val sequence = paneSequence
                        paneUid = -1L
                        navController.navigate("reader/${Uri.encode(mailbox)}/$uid/$sequence")
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
                if (!expanded) {
                    key(folderViewToken) {
                        MessageIndexScreen(
                            mailbox = mailbox,
                            onOpen = { uid, sequence, rowMailbox ->
                                val target = if (rowMailbox.isEmpty()) mailbox else rowMailbox
                                navController.navigate("reader/${Uri.encode(target)}/$uid/$sequence")
                            },
                            onCompose = { seed -> openCompose(seed) },
                            onBack = { navController.popBackStack() },
                            onCustomize = { navController.navigate("toolbar/index") },
                            onCustomizeSelection = { navController.navigate("toolbar/selection") },
                            onAdvanced = { openAdvanced() },
                            advancedQuery = advancedQuery.ifEmpty { null },
                            advancedScope = advancedScope,
                            onAdvancedConsumed = { consumeAdvanced() },
                            onOpenFolder = { name ->
                                navController.navigate("index/${Uri.encode(name)}")
                            },
                        )
                    }
                } else {
                    BackHandler(enabled = expanded && paneUid >= 0L) {
                        paneUid = -1L
                    }
                    val scaffoldValue = if (paneUid < 0L) {
                        ThreePaneScaffoldValue(
                            primary = PaneAdaptedValue.Hidden,
                            secondary = PaneAdaptedValue.Expanded,
                            tertiary = PaneAdaptedValue.Hidden,
                        )
                    } else {
                        ThreePaneScaffoldValue(
                            primary = PaneAdaptedValue.Expanded,
                            secondary = PaneAdaptedValue.Expanded,
                            tertiary = PaneAdaptedValue.Hidden,
                        )
                    }
                    ListDetailPaneScaffold(
                        directive = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo()),
                        value = scaffoldValue,
                        listPane = {
                            key(shownViewToken) {
                                MessageIndexScreen(
                                    mailbox = mailbox,
                                    onOpen = { uid, sequence, rowMailbox ->
                                        val target = if (rowMailbox.isEmpty()) mailbox else rowMailbox
                                        if (target == mailbox) {
                                            paneUid = uid
                                            paneSequence = sequence
                                        } else {
                                            navController.navigate(
                                                "reader/${Uri.encode(target)}/$uid/$sequence",
                                            )
                                        }
                                    },
                                    onCompose = { seed -> openCompose(seed) },
                                    onBack = { navController.popBackStack() },
                                    onCustomize = { navController.navigate("toolbar/index") },
                                    onCustomizeSelection = { navController.navigate("toolbar/selection") },
                                    watchMailbox = paneUid < 0L,
                                    onAdvanced = { openAdvanced() },
                                    advancedQuery = advancedQuery.ifEmpty { null },
                                    advancedScope = advancedScope,
                                    onAdvancedConsumed = { consumeAdvanced() },
                                    onOpenFolder = { name ->
                                        navController.navigate("index/${Uri.encode(name)}")
                                    },
                                )
                            }
                        },
                        detailPane = {
                            key(paneUid) {
                                if (paneUid >= 0L) {
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
                                    )
                                }
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
                        )
                    }
                }
            }
            composable("contacts") {
                UpPage("Copy contacts", onUp = { navController.popBackStack() }) {
                    ContactCopyScreen()
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

    fun navigateFromDrawer(go: () -> Unit) {
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
                try {
                    FolderListModel(mailSession(), store).showCollapsed(favorite.mailbox)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
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

    val drawerSheet: @Composable ColumnScope.() -> Unit = {
        DrawerSheetContent(
            header = header,
            postponedMailbox = postponedMailbox,
            favorites = favorites,
            onInbox = {
                navigateFromDrawer {
                    navController.navigate("index/${Uri.encode("INBOX")}")
                }
            },
            onPostponed = { row ->
                navigateFromDrawer {
                    navController.navigate("index/${Uri.encode(row)}")
                }
            },
            onAllFolders = {
                navigateFromDrawer {
                    navController.navigate("folders") {
                        popUpTo("folders")
                        launchSingleTop = true
                    }
                }
            },
            onFavorite = { favorite -> openFavorite(favorite) },
            onEditFavorite = { favorite -> openFavoriteEditor(favorite) },
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
        if (expandedWindow) {
            PermanentNavigationDrawer(
                drawerContent = {
                    PermanentDrawerSheet(
                        windowInsets = mailScreenInsets(),
                        content = drawerSheet,
                    )
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                NavHost(
                    navController = navController,
                    startDestination = "folders",
                    modifier = Modifier.fillMaxSize(),
                    builder = navGraph,
                )
            }
        } else {
            ModalNavigationDrawer(
                drawerContent = {
                    ModalDrawerSheet(
                        windowInsets = mailScreenInsets(),
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
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.DrawerSheetContent(
    header: String,
    postponedMailbox: String,
    favorites: List<FolderFavorite>,
    onInbox: () -> Unit,
    onPostponed: (String) -> Unit,
    onAllFolders: () -> Unit,
    onFavorite: (FolderFavorite) -> Unit,
    onEditFavorite: (FolderFavorite) -> Unit,
    onSettings: () -> Unit,
    onHelp: () -> Unit,
    onAbout: () -> Unit,
) {
    Text(
        text = header,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
    NavigationDrawerItem(
        label = { Text(stringResource(R.string.drawer_inbox)) },
        selected = false,
        onClick = onInbox,
    )
    val postponedDescription = stringResource(R.string.drawer_postponed)
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
