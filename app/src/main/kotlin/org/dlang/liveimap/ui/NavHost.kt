package org.dlang.liveimap.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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

@Composable
fun LiveImapNavHost() {
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

    // NavHost remembers the builder. A new lambda each pass would replace the graph and drop the stack.
    val navGraph: NavGraphBuilder.() -> Unit = remember {
        {
            composable("folders") {
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
                MessageIndexScreen(
                    mailbox = mailbox,
                    onOpen = { uid, sequence ->
                        navController.navigate("reader/${Uri.encode(mailbox)}/$uid/$sequence")
                    },
                    onCompose = { seed ->
                        composeKindName.value = seed.kind.name
                        composeMailbox.value = seed.mailbox.orEmpty()
                        composeUids.value = seed.uids.joinToString(",")
                        composeUnsentId.value = ""
                        composeRetryOnOpen.value = false
                        navController.navigate("compose")
                    },
                    onBack = { navController.popBackStack() },
                )
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
                    onCompose = { seed ->
                        composeKindName.value = seed.kind.name
                        composeMailbox.value = seed.mailbox.orEmpty()
                        composeUids.value = seed.uids.joinToString(",")
                        composeUnsentId.value = ""
                        composeRetryOnOpen.value = false
                        navController.navigate("compose")
                    },
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

    LiveImapScaffold {
        ModalNavigationDrawer(
            drawerContent = {
                ModalDrawerSheet {
                    Text(
                        text = header,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("INBOX") },
                        selected = false,
                        onClick = {
                            navigateFromDrawer {
                                navController.navigate("index/${Uri.encode("INBOX")}")
                            }
                        },
                    )
                    val postponedRow = postponedDrawerMailbox(postponedMailbox)
                    if (postponedRow != null) {
                        NavigationDrawerItem(
                            label = { Text(postponedRow) },
                            selected = false,
                            modifier = Modifier.semantics { contentDescription = "Postponed" },
                            onClick = {
                                navigateFromDrawer {
                                    navController.navigate("index/${Uri.encode(postponedRow)}")
                                }
                            },
                        )
                    }
                    NavigationDrawerItem(
                        label = { Text("All folders") },
                        selected = false,
                        onClick = {
                            navigateFromDrawer {
                                navController.navigate("folders") {
                                    popUpTo("folders")
                                    launchSingleTop = true
                                }
                            }
                        },
                    )
                    for (favorite in favorites) {
                        NavigationDrawerItem(
                            label = { Text(favoriteLabel(favorite.node, favorite.mailbox, favorite.delimiter)) },
                            selected = false,
                            onClick = { openFavorite(favorite) },
                        )
                    }
                    HorizontalDivider()
                    NavigationDrawerItem(
                        label = { Text("Settings") },
                        selected = false,
                        onClick = {
                            navigateFromDrawer {
                                navController.navigate("settings") {
                                    launchSingleTop = true
                                }
                            }
                        },
                    )
                    NavigationDrawerItem(
                        label = { Text("About") },
                        selected = false,
                        onClick = {
                            navigateFromDrawer {
                                navController.navigate("about") {
                                    launchSingleTop = true
                                }
                            }
                        },
                    )
                }
            },
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

internal fun postponedDrawerMailbox(value: String): String? {
    if (value.isEmpty()) return null
    return value
}
