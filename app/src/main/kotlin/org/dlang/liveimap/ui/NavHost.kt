package org.dlang.liveimap.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
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
import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.FolderFavorite
import org.dlang.liveimap.settings.SettingsScreen
import org.dlang.liveimap.settings.favoriteLabel
import org.dlang.liveimap.ui.about.AboutScreen
import org.dlang.liveimap.ui.compose.ComposeScreen
import org.dlang.liveimap.ui.compose.UnsentScreen
import org.dlang.liveimap.ui.folder.FolderListModel
import org.dlang.liveimap.ui.folder.FolderListScreen
import org.dlang.liveimap.ui.index.MessageIndexScreen
import org.dlang.liveimap.ui.reader.MessageReaderScreen

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
    val drawerGestures = route == "folders" || route == "settings" || route == "about"
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

    LaunchedEffect(route, drawerState.currentValue) {
        val account = store.load()
        header = if (account.email.isNotBlank()) account.email else account.username
        favorites = account.favorites
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
                    focusMailbox = focusMailbox.value,
                    focusToken = focusToken.value,
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
                if (!expanded) {
                    MessageIndexScreen(
                        mailbox = mailbox,
                        onOpen = { uid, sequence ->
                            navController.navigate("reader/${Uri.encode(mailbox)}/$uid/$sequence")
                        },
                        onCompose = { seed -> openCompose(seed) },
                        onBack = { navController.popBackStack() },
                    )
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
                            MessageIndexScreen(
                                mailbox = mailbox,
                                onOpen = { uid, sequence ->
                                    paneUid = uid
                                    paneSequence = sequence
                                },
                                onCompose = { seed -> openCompose(seed) },
                                onBack = { navController.popBackStack() },
                                watchMailbox = paneUid < 0L,
                            )
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
                                    )
                                }
                            }
                        },
                    )
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
                )
            }
            composable("unsent") {
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
            composable("settings") {
                SettingsScreen()
            }
            composable("about") {
                AboutScreen()
            }
        }
    }

    fun navigateFromDrawer(go: () -> Unit) {
        scope.launch {
            drawerState.close()
            go()
        }
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
            onSettings = {
                navigateFromDrawer {
                    navController.navigate("settings") {
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
                drawerContent = { PermanentDrawerSheet(content = drawerSheet) },
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
                drawerContent = { ModalDrawerSheet(content = drawerSheet) },
                modifier = Modifier.fillMaxSize(),
                drawerState = drawerState,
                gesturesEnabled = drawerGestures,
            ) {
                Column(Modifier.fillMaxSize()) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(
                            imageVector = Icons.Filled.Menu,
                            contentDescription = "Menu",
                        )
                    }
                    NavHost(
                        navController = navController,
                        startDestination = "folders",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        builder = navGraph,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.DrawerSheetContent(
    header: String,
    postponedMailbox: String,
    favorites: List<FolderFavorite>,
    onInbox: () -> Unit,
    onPostponed: (String) -> Unit,
    onAllFolders: () -> Unit,
    onFavorite: (FolderFavorite) -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    Text(
        text = header,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
    NavigationDrawerItem(
        label = { Text("INBOX") },
        selected = false,
        onClick = onInbox,
    )
    val postponedRow = postponedDrawerMailbox(postponedMailbox)
    if (postponedRow != null) {
        NavigationDrawerItem(
            label = { Text(postponedRow) },
            selected = false,
            modifier = Modifier.semantics { contentDescription = "Postponed" },
            onClick = { onPostponed(postponedRow) },
        )
    }
    NavigationDrawerItem(
        label = { Text("All folders") },
        selected = false,
        onClick = onAllFolders,
    )
    for (favorite in favorites) {
        NavigationDrawerItem(
            label = { Text(favoriteLabel(favorite.node, favorite.mailbox, favorite.delimiter)) },
            selected = false,
            onClick = { onFavorite(favorite) },
        )
    }
    HorizontalDivider()
    NavigationDrawerItem(
        label = { Text("Settings") },
        selected = false,
        onClick = onSettings,
    )
    NavigationDrawerItem(
        label = { Text("About") },
        selected = false,
        onClick = onAbout,
    )
}

internal fun foldReaderIntoIndex(expanded: Boolean, route: String?): Boolean {
    return expanded && route == "reader/{mailbox}/{uid}/{sequence}"
}

internal fun postponedDrawerMailbox(value: String): String? {
    if (value.isEmpty()) return null
    return value
}
