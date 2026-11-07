package org.dlang.liveimap.ui
import org.dlang.liveimap.i18n.S
import org.dlang.liveimap.settings.SettingsTreeGenerated

import androidx.compose.runtime.mutableStateMapOf
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.ViewTreeObserver
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.clickable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import org.dlang.liveimap.ui.TextButton
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import org.dlang.liveimap.settings.UnsavedEdits
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.movableContentOf
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.dlang.liveimap.engine.sieve.seedCriteria
import org.dlang.liveimap.session.CertPrompt
import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.session.MessageRef
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.session.setMailCertConfirmer
import org.dlang.liveimap.session.setMailPlaintextConfirmer
import org.dlang.liveimap.settings.AccountChoice
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.DrawerAccount
import org.dlang.liveimap.settings.editors.ExpandedFoldersScreen
import org.dlang.liveimap.settings.FolderFavorite
import org.dlang.liveimap.settings.editors.FolderStartsScreen
import org.dlang.liveimap.settings.editors.FolderViewsScreen
import org.dlang.liveimap.settings.LayoutApply
import org.dlang.liveimap.settings.SettingsTree
import org.dlang.liveimap.settings.LoadCheckDialog
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
import org.dlang.liveimap.ui.index.IndexMarks
import org.dlang.liveimap.ui.index.MessageIndexScreen
import org.dlang.liveimap.ui.index.OpenMessageOrder
import org.dlang.liveimap.ui.index.SearchScope
import org.dlang.liveimap.window.FillResult
import org.dlang.liveimap.window.LayoutRecord
import org.dlang.liveimap.window.LayoutRotationUpright
import org.dlang.liveimap.window.LayoutSituation
import org.dlang.liveimap.window.WindowLayer
import org.dlang.liveimap.window.WindowLayerKind
import org.dlang.liveimap.window.WindowStack
import org.dlang.liveimap.window.ZoomState
import org.dlang.liveimap.window.back
import org.dlang.liveimap.window.decodeLayoutCatalog
import org.dlang.liveimap.window.fill
import org.dlang.liveimap.window.resolve
import org.dlang.liveimap.window.keyLayer
import org.dlang.liveimap.window.keyPanel
import org.dlang.liveimap.window.displayedFill
import org.dlang.liveimap.window.drawerOverlayOpen
import org.dlang.liveimap.window.DrawerInput
import org.dlang.liveimap.window.DrawerView
import org.dlang.liveimap.window.drawerStep
import org.dlang.liveimap.window.hamburgerPlan
import org.dlang.liveimap.window.zoomAfterScreenOpen
import org.dlang.liveimap.ui.reader.MessageReaderScreen
import org.dlang.liveimap.ui.search.AdvancedSearchScreen
import org.dlang.liveimap.ui.toolbar.ToolbarEditorPage
import org.dlang.liveimap.ui.toolbar.ToolbarScreen

private data class OpenedMessage(
    val mailbox: String,
    val uid: Long,
    val sequence: Int,
    val uidValidity: Long,
    val markKey: String,
)

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
private val repeatedSpaces = Regex(" {2,}")

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
    }.trim().replace(repeatedSpaces, " ")
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

/** The mail window zooms the drawer. It does not use a modal drawer. */
internal fun mailUsesModalDrawer(
    settingsSplit: Boolean,
    hasCustomTree: Boolean,
    drawerOutsideMail: Boolean,
    split: Boolean,
): Boolean = false

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

/**
 * Rotation and display id are not configuration. A 180 degree turn keeps the same orientation
 * and size, and a move can keep the same configuration. Both still change the live Display.
 */
@Composable
private fun windowSituation(): LayoutSituation {
    val configuration = LocalConfiguration.current
    val view = LocalView.current
    val rotationState = remember {
        mutableIntStateOf(view.display?.rotation ?: LayoutRotationUpright)
    }
    val builtInState = remember {
        val display = view.display
        mutableStateOf(display == null || display.displayId == Display.DEFAULT_DISPLAY)
    }
    DisposableEffect(view) {
        fun publish() {
            val display = view.display
            val nextRotation = display?.rotation ?: LayoutRotationUpright
            val nextBuiltIn = display == null || display.displayId == Display.DEFAULT_DISPLAY
            if (rotationState.intValue != nextRotation) rotationState.intValue = nextRotation
            if (builtInState.value != nextBuiltIn) builtInState.value = nextBuiltIn
        }
        val manager = view.context.getSystemService(DisplayManager::class.java)
        val displays = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = publish()
            override fun onDisplayRemoved(displayId: Int) = publish()
            override fun onDisplayChanged(displayId: Int) = publish()
        }
        manager?.registerDisplayListener(displays, Handler(Looper.getMainLooper()))
        val beforeDraw = ViewTreeObserver.OnPreDrawListener {
            val display = view.display
            val nextRotation = display?.rotation ?: LayoutRotationUpright
            val nextBuiltIn = display == null || display.displayId == Display.DEFAULT_DISPLAY
            if (rotationState.intValue != nextRotation || builtInState.value != nextBuiltIn) {
                view.post { publish() }
            }
            true
        }
        view.viewTreeObserver.addOnPreDrawListener(beforeDraw)
        onDispose {
            manager?.unregisterDisplayListener(displays)
            val observer = view.viewTreeObserver
            if (observer.isAlive) observer.removeOnPreDrawListener(beforeDraw)
        }
    }
    val portrait = when (configuration.orientation) {
        Configuration.ORIENTATION_PORTRAIT -> true
        Configuration.ORIENTATION_LANDSCAPE -> false
        else -> null
    }
    return LayoutSituation(rotationState.intValue, portrait, builtInState.value)
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
    LoadCheckDialog()
    var layoutCatalogText by remember { mutableStateOf("") }
    var layoutAccountId by remember { mutableStateOf("") }
    val treeDp = remember { mutableStateMapOf<String, Float>() }
    var zoom by remember { mutableStateOf<ZoomState?>(null) }
    var keyPanelId by remember { mutableStateOf<String?>(null) }
    var openedMessage by remember { mutableStateOf<OpenedMessage?>(null) }
    val uiContext = LocalContext.current
    val appContext = uiContext.applicationContext
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
    var mailStack by remember { mutableStateOf(WindowStack.drawer("")) }
    // Where the stack arrows moved the view. Tied to the stack it was set on: any stack change shows the top again.
    var stackView by remember { mutableStateOf<org.dlang.liveimap.window.StackView?>(null) }
    // The configured startup view is built once, when the first account is known.
    var startupBuilt by remember { mutableStateOf(false) }
    val drawerRowsChecked = remember { HashSet<String>() }
    var startupProblem by remember { mutableStateOf<org.dlang.liveimap.window.StartupProblem?>(null) }
    val scope = rememberCoroutineScope()
    val situation = windowSituation()
    fun currentRecord(): LayoutRecord = decodeLayoutCatalog(layoutCatalogText).resolve(situation)
    fun currentOffset(): Int = org.dlang.liveimap.window.viewOffset(stackView, mailStack)
    fun currentFill(): FillResult = displayedFill(currentRecord(), mailStack, zoom, currentOffset())
    fun toggleDrawerOverlay() {
        org.dlang.liveimap.engine.DebugLog.event("drawer") { "hamburger: drawer was ${if (drawerOverlayOpen(zoom)) "open" else "closed"}" }
        val base = fill(currentRecord(), mailStack, currentOffset())
        val next = drawerStep(DrawerView(mailStack, drawerOverlayOpen(zoom)), DrawerInput.Menu,
            org.dlang.liveimap.window.drawerInPanel(base))
        zoom = if (next.peek) ZoomState(mailStack.layers.first(), base) else null
    }
    /** A drawer pick: the drawer stays, [above] replaces the rest of the stack, the peek closes. */
    fun pickFromDrawer(above: List<WindowLayer>) {
        val next = drawerStep(DrawerView(mailStack, drawerOverlayOpen(zoom)), DrawerInput.Pick(above), false)
        org.dlang.liveimap.engine.DebugLog.event("stack") { "drawer pick " + above.joinToString { "${it.kind}:${it.key}" } }
        mailStack = next.stack
        zoom = null
    }
    fun openDrawerOverlay() {
        if (!drawerOverlayOpen(zoom)) toggleDrawerOverlay()
    }
    // A swipe, a scrim tap, or Back closing the sheet clears the drawer zoom.
    val drawerSheetState = rememberDrawerState(DrawerValue.Closed) { value ->
        if (value == DrawerValue.Closed && drawerOverlayOpen(zoom)) zoom = null
        true
    }
    val drawerOverlay = drawerOverlayOpen(zoom)
    LaunchedEffect(zoom) {
        val z = zoom
        org.dlang.liveimap.engine.DebugLog.event("zoom") { if (z == null) "zoom cleared" else "zoom set ${z.layer.kind}:${z.layer.key}" }
    }
    LaunchedEffect(drawerOverlay) {
        org.dlang.liveimap.engine.DebugLog.event("drawer") { if (drawerOverlay) "drawer open" else "drawer closed" }
        if (drawerOverlay) drawerSheetState.open() else drawerSheetState.close()
    }
    val currentEntry by navController.currentBackStackEntryAsState()
    val route = currentEntry?.destination?.route
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
    val openDrawerState = rememberUpdatedState<(() -> Unit)?>({
        toggleDrawerOverlay()
    })
    val displayFill = currentFill()
    LaunchedEffect(mailStack) {
        org.dlang.liveimap.engine.DebugLog.event("stack") { "stack " + mailStack.layers.joinToString(" > ") { "${it.kind}:${it.key}" } }
    }
    LaunchedEffect(displayFill) {
        org.dlang.liveimap.engine.DebugLog.event("fill") {
            "fill " + displayFill.slots.joinToString(" | ") { slot ->
                slot.panelIds.joinToString(",") + "=" + (slot.layer?.let { "${it.kind}:${it.key}" } ?: "-") +
                    (if (slot.collapsed) " collapsed" else "")
            }
        }
    }
    val activeKey = keyPanel(displayFill, keyPanelId)
    val activeLayer = keyLayer(displayFill, activeKey)
    SideEffect {
        if (activeKey != keyPanelId) keyPanelId = activeKey
    }

    suspend fun applyRunningLayout() {
        val account = try {
            store.load()
        } catch (_: org.dlang.liveimap.accounts.NoAccountSelected) {
            return
        }
        val next = account.layoutCatalog
        if (next != layoutCatalogText) {
            layoutCatalogText = next
            zoom = null
            treeDp.clear()
        }
        LayoutApply.noteFullScreen()
    }

    suspend fun applyOpenAccount() {
        val account = try {
            store.load()
        } catch (_: org.dlang.liveimap.accounts.NoAccountSelected) {
            drawerAccounts = store.listDrawerAccounts()
            accounts = store.listAccounts()
            header = ""
            val showing = navController.currentBackStackEntry?.destination?.route
            if (openDrawerOnNoAccount(showing, accounts)) {
                openDrawerOverlay()
            } else {
                zoom = null
            }
            return
        }
        var drawers = store.listDrawerAccounts()
        // Each account's drawer rows become real favorites once (new accounts on first listing: at the end of the drawer).
        var addedRows = false
        for (row in drawers) {
            if (row.corrupt || row.id in drawerRowsChecked) continue
            drawerRowsChecked += row.id
            val accountStore = store.forAccount(row.id)
            val stored = try { accountStore.load() } catch (_: org.dlang.liveimap.accounts.NoAccountSelected) { continue }
            if (stored.drawerRowsAdded) continue
            if (!org.dlang.liveimap.window.SettingsSession.allowWrite(uiContext)) continue
            accountStore.update {
                it.copy(favorites = withAccountDrawerRows(it.favorites, it.postponedMailbox, it.drawerRowsAdded), drawerRowsAdded = true)
            }
            org.dlang.liveimap.engine.DebugLog.event("settings") { "drawer rows added to favorites for account ${row.id}" }
            addedRows = true
        }
        if (addedRows) drawers = store.listDrawerAccounts()
        drawerAccounts = drawers
        header = drawers.firstOrNull { it.chosen }?.name ?: org.dlang.liveimap.accounts.accountVisibleName(account)
        layoutAccountId = drawers.firstOrNull { it.chosen }?.id.orEmpty()
        if (layoutAccountId.isNotEmpty() && mailStack.layers.first().key.isEmpty()) {
            // A mailbox jump can land before the account id is known. Stamp the key; do not add a tree over that index.
            mailStack = if (mailStack.layers.size == 1 && !startupBuilt) {
                startupBuilt = true
                val ui = org.dlang.liveimap.settings.SettingsHub.uiNow()
                val plan = org.dlang.liveimap.window.startupStack(
                    ui.startupView, ui.startupAccount, layoutAccountId, drawers.map { it.id }.toSet(), ui.startupFolder,
                    // The startup account's favorites: the open account's, or the configured account's own.
                    // The drawer list is read after the drawer rows were added, so it is the current favorites.
                    drawers.firstOrNull { it.id == ui.startupAccount.ifEmpty { layoutAccountId } }?.favorites.orEmpty()
                        .let { org.dlang.liveimap.settings.startupFavoriteOf(it) }
                        ?.let { org.dlang.liveimap.window.StartupFavorite(it.node, it.mailbox) },
                )
                startupProblem = plan.problem
                org.dlang.liveimap.engine.DebugLog.event("stack") {
                    "startup ${ui.startupView} -> " + plan.stack.layers.joinToString { "${it.kind}:${it.key}" } + " problem=${plan.problem}"
                }
                if (plan.accountId != layoutAccountId) store.selectAccount(plan.accountId)
                val index = plan.stack.layers.lastOrNull()?.takeIf { it.kind == WindowLayerKind.FolderIndex }
                if (index != null) {
                    navController.navigate("index/${Uri.encode(index.key)}") { popUpTo(0) { inclusive = true } }
                }
                plan.stack
            } else if (mailStack.layers.size == 1) {
                WindowStack.drawer(layoutAccountId).pushFolderTree("root")
            } else {
                withDrawerKey(mailStack, layoutAccountId)
            }
        } else if (layoutAccountId.isNotEmpty()) {
            mailStack = withDrawerKey(mailStack, layoutAccountId)
        }
        if (editingFavorite == null) favorites = drawers.firstOrNull { it.chosen }?.favorites ?: account.favorites
        postponedMailbox = account.postponedMailbox
        NonAsciiText.active = account.nonAsciiDisplay
        accounts = store.listAccounts()
    }

    LaunchedEffect(route) {
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
            // The folder root is replaced even when it is already showing, so its saved account id does not survive.
            if (resetToNoAccount(accounts)) {
                mailStack = WindowStack.drawer("")
                navController.navigate("folders") {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        org.dlang.liveimap.accounts.AccountEvents.removedIds.collect { removedId ->
            // The route pop removes this account's screens and whatever sits above them.
            mailStack = mailStack.dropAccountScreens(removedId)
            while (true) {
                val entry = navController.currentBackStack.value.firstOrNull {
                    it.arguments?.getString("accountId") == removedId
                } ?: break
                if (!navController.popBackStack(entry, true)) break
            }
            // The selected-id clear is not "no accounts". Decide from the list after the entry is gone.
            applyOpenAccount()
            if (resetToNoAccount(accounts)) {
                mailStack = WindowStack.drawer("")
                navController.navigate("folders") {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    /** Pushes the screen layer and its route. The opener's route stays underneath. */
    /** The screen key of the shown route, rebuilt from its arguments as ScreenLayerPage does. */
    fun currentScreenKey(): String? {
        val entry = navController.currentBackStackEntry ?: return null
        val pattern = entry.destination.route ?: return null
        return screenKeyOfRoute(pattern) { name -> entry.arguments?.getString(name) }
    }

    fun openScreen(route: String, fromDrawer: Boolean = false) {
        val key = screenLayerKey(route)
        if (key == null) {
            navController.navigate(route)
            return
        }
        if (fromDrawer) {
            pickFromDrawer(listOf(WindowLayer(WindowLayerKind.Screen, key)))
            navController.navigate(route) { popUpTo(0) { inclusive = true } }
            return
        }
        val plan = screenOpenPlan(mailStack, key)
        org.dlang.liveimap.engine.DebugLog.event("stack") { "open screen $key existing=${plan.existing} pops=${plan.pops} shown=${currentScreenKey()}" }
        mailStack = plan.stack
        zoom = zoomAfterScreenOpen(zoom, key)
        repeat(plan.pops) { navController.popBackStack() }
        if (screenNeedsNavigate(plan, currentScreenKey(), key)) navController.navigate(route)
    }

    /** Drops screen routes so a mail navigation sees the opener, not the screen pile. */
    fun popScreenRoutes() {
        while (isScreenRoute(navController.currentDestination?.route)) {
            val top = mailStack.layers.lastOrNull() ?: break
            if (top.kind != WindowLayerKind.Screen) break
            mailStack = mailStack.pop()
            if (!navController.popBackStack()) break
        }
    }

    fun openFilterEditor(
        index: Int,
        from: String,
        to: String,
        listId: String,
        subject: String,
        accountId: String,
        ruleId: String,
        fromDrawer: Boolean = false,
    ) {
        filterEditIndex.value = index
        filterSeedFrom.value = from
        filterSeedTo.value = to
        filterSeedListId.value = listId
        filterSeedSubject.value = subject
        filterAccountId.value = accountId
        filterRuleId.value = ruleId
        openScreen("filter", fromDrawer)
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
        openScreen("compose")
    }

    fun openToolbar(name: String) {
        openScreen("toolbar/$name")
    }

    fun publishMark(uid: Long?, reveal: Boolean) {
        val key = navController.currentBackStackEntry?.savedStateHandle?.get<String>("markKey")
            ?: navController.previousBackStackEntry?.savedStateHandle?.get<String>("markKey")
            ?: return
        IndexMarks.publish(key, uid, reveal)
    }

    fun pushMessageLayer(uid: Long) {
        popScreenRoutes()
        val source = messageSource(mailStack)
        if (source != WindowLayerKind.FolderIndex && source != WindowLayerKind.SearchIndex) return
        if (mailStack.layers.none { it.kind == source }) return
        mailStack = mailStack.pushMessage(uid.toString(), source)
    }

    /** A drawer mailbox is the only destination. Popping it leaves a blank host, so Back finishes. */
    fun popMailDestination() {
        if (navController.previousBackStackEntry == null) {
            uiContext.hostActivity()?.finish()
        } else {
            navController.popBackStack()
        }
    }

    fun dialogOpen(): Boolean =
        certPrompt.value != null || plaintextPrompt.value != null || editingFavorite != null

    fun dismissOpenDialog() {
        val cert = certPrompt.value
        if (cert != null) {
            cert.resume(false)
            return
        }
        val plain = plaintextPrompt.value
        if (plain != null) {
            plain(false)
            return
        }
        editingFavorite = null
    }

    /**
     * Back dismisses a dialog, then leaves zoom, then pops the top layer.
     * A screen pops with its route. The opener's route stays.
     * A tree that is not already under this entry is shown in its place.
     */
    fun leaveLayer(failure: String? = null) {
        if (!failure.isNullOrEmpty()) IndexLeaveReport.report(failure)
        val step = back(dialogOpen(), zoom, mailStack)
        org.dlang.liveimap.engine.DebugLog.event("stack") {
            "leave layer dialog=${step.dismissedDialog} zoom=${zoom?.layer?.kind} pop=${mailStack.layers.lastOrNull()?.kind}"
        }
        if (step.dismissedDialog) {
            dismissOpenDialog()
            return
        }
        if (zoom != null) {
            zoom = null
            return
        }
        val poppedScreen = mailStack.layers.lastOrNull()?.kind == WindowLayerKind.Screen
        if (mailStack.layers.size == 1) {
            popMailDestination()
            return
        }
        if (org.dlang.liveimap.window.backLeavesWindow(mailStack, org.dlang.liveimap.window.pinnedKinds(currentRecord()))) {
            org.dlang.liveimap.engine.DebugLog.event("stack") { "back leaves the window: only pinned layers would remain" }
            uiContext.hostActivity()?.finish()
            return
        }
        mailStack = step.stack
        if (step.stack.layers.size == 1) {
            // Down to the drawer: it is the bottom layer and stays shown; the next Back leaves.
            navController.navigate("folders") { popUpTo(0) { inclusive = true } }
            return
        }
        if (poppedScreen) {
            popMailDestination()
            // Closing the message while a screen covered it can leave the reader route under the screen.
            if (navController.currentDestination?.route == ReaderRoutePattern &&
                mailStack.layers.none { it.kind == WindowLayerKind.Message }
            ) {
                popMailDestination()
            }
            return
        }
        val showTree = step.stack.layers.last().kind == WindowLayerKind.FolderTree &&
            navController.previousBackStackEntry?.destination?.route != "folders"
        if (showTree) {
            val currentId = navController.currentBackStackEntry?.destination?.id ?: return
            navController.navigate("folders") {
                popUpTo(currentId) { inclusive = true }
            }
        } else {
            popMailDestination()
        }
    }

    fun placeMessage(ref: MessageRef, sequence: Int, from: WindowLayerKind, mailboxFallback: String, markKey: String) {
        popScreenRoutes()
        if (mailStack.layers.any { it.kind == from }) {
            mailStack = mailStack.pushMessage(ref.uid.toString(), from)
        }
        val target = ref.mailbox.ifEmpty { mailboxFallback }
        openedMessage = OpenedMessage(target, ref.uid, sequence, ref.uidValidity, markKey)
        zoom = null
        val kinds = fill(currentRecord(), mailStack, currentOffset()).slots.filter { !it.collapsed }.mapNotNull { it.layer?.kind }.toSet()
        if (WindowLayerKind.Message in kinds && from in kinds) {
            if (navController.currentDestination?.route == ReaderRoutePattern) navController.popBackStack()
            return
        }
        navController.navigate(readerRoute(target, ref.uid, sequence, ref.uidValidity)) {
            if (navController.currentDestination?.route == ReaderRoutePattern) {
                popUpTo(ReaderRoutePattern) { inclusive = true }
            }
        }
    }

    fun closeMessageLayer() {
        mailStack = mailStack.dropMessage()
    }

    // NavHost remembers the builder. A new lambda each pass would replace the graph and drop the stack.
    val folderListPane: @Composable () -> Unit = {
                    FolderListScreen(
                        onOpenMailbox = { mailbox ->
                            popScreenRoutes()
                            mailStack = folderFromTree(mailStack, mailbox)
                            navController.navigate("index/${Uri.encode(mailbox)}")
                        },
                        onCompose = { seed -> openCompose(seed) },
                        onOpenUnsent = { openScreen("unsent") },
                        onOpenHelp = { openScreen("help") },
                        onOpenAbout = { openScreen("about") },
                        onOpenSettings = { openScreen("settings") },
                        onCustomize = { openToolbar("folders") },
                        focusMailbox = focusMailbox.value,
                        focusToken = focusToken.value,
                        onOpenDrawer = openDrawerState.value,
                    )
    }
    androidx.activity.compose.BackHandler(enabled = zoom != null) {
        leaveLayer()
    }
    @Composable
    fun ScreenLayerPage(screenKey: String) {
        key(screenKey) {
            val settingsRefusal = org.dlang.liveimap.window.SettingsEntry.generation
            var seenRefusal by remember(screenKey) { mutableIntStateOf(settingsRefusal) }
            LaunchedEffect(settingsRefusal) {
                if (settingsRefusal != seenRefusal && screenClosesOnSettingsRefusal(screenKey)) {
                    org.dlang.liveimap.engine.DebugLog.event("screen") { "close $screenKey: settings refused" }
                    seenRefusal = settingsRefusal
                    leaveLayer()
                } else {
                    seenRefusal = settingsRefusal
                }
            }
            // Registered before the page, so a page that asks about unsaved edits wins while it is pending.
            BackHandler { leaveLayer() }
            val toolbar = toolbarScreenOf(screenKey)
            if (toolbar != null) {
                UpPage(S("Customize toolbar…"), onUp = { leaveLayer() }) {
                    key(ToolbarRevision.value) {
                        ToolbarEditorPage(toolbar)
                    }
                }
            } else when (screenKey) {
                "compose" -> {
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
                        onDone = { leaveLayer() },
                        unsentId = composeUnsentId.value.takeIf { it.isNotEmpty() },
                        retryOnOpen = composeRetryOnOpen.value,
                        onOpenUnsent = { openScreen("unsent") },
                        onCustomize = { openToolbar("compose") },
                    )
                }
                "unsent" -> UpPage(S("Unsent"), onUp = { leaveLayer() }) {
                    UnsentScreen(
                        onOpenCopy = { id, retry, account ->
                            composeAccount.value = account // F121: retry in the copy's own account
                            composeKindName.value = ComposeKind.New.name
                            composeMailbox.value = ""
                            composeUids.value = ""
                            composeUnsentId.value = id
                            composeRetryOnOpen.value = retry
                            composeForward.value = ""
                            openScreen("compose")
                        },
                        onBack = { leaveLayer() },
                    )
                }
                "settings/setup" -> org.dlang.liveimap.settings.editors.SetupGuide(
                    onDone = {
                        mailStack = drawerMailbox(mailStack, "INBOX")
                        navController.navigate("index/${Uri.encode("INBOX")}") {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onAdvanced = { settings, password, smtp ->
                        val pending = org.dlang.liveimap.settings.editors.PendingNewAccount
                        pending.active = true
                        pending.settings = settings
                        pending.password = password
                        pending.smtpPassword = smtp
                        pending.onCreated = { _ ->
                            mailStack = drawerMailbox(mailStack, "INBOX")
                            navController.navigate("index/${Uri.encode("INBOX")}") {
                                popUpTo(0) { inclusive = true }
                            }
                        }
                        openScreen("settings/account/_")
                    },
                )
                "settings" -> UpPage(S("Settings"), onUp = { leaveLayer() }) {
                    SettingsTree(
                        onCopyContacts = { openScreen("contacts") },
                        onEditBook = { openScreen("addressbook") },
                        onOpenPanel = { name, _ -> openToolbar(name) },
                        onNavigate = { route -> openScreen(route) },
                        page = "settings",
                    )
                }
                "settings/accounts" -> UpPage(
                    stringResource(SettingsTreeGenerated.resource("settings_accounts")),
                    onUp = { leaveLayer() },
                ) {
                    SettingsTree(
                        onCopyContacts = { openScreen("contacts") },
                        onEditBook = { openScreen("addressbook") },
                        onOpenPanel = { name, _ -> openToolbar(name) },
                        onNavigate = { route -> openScreen(route) },
                        page = "accounts",
                    )
                }
                "folderLists/expanded" -> UpPage(
                    stringResource(SettingsTreeGenerated.resource("settings_expanded_folders")),
                    onUp = { leaveLayer() },
                ) {
                    ExpandedFoldersScreen()
                }
                "folderLists/views" -> UpPage(
                    stringResource(SettingsTreeGenerated.resource("settings_folder_views")),
                    onUp = { leaveLayer() },
                ) {
                    FolderViewsScreen()
                }
                "folderLists/starts" -> UpPage(
                    S("Start position per folder", name = "settings_start_per_folder"),
                    onUp = { leaveLayer() },
                ) {
                    FolderStartsScreen()
                }
                "addressbook" -> UpPage(S("Edit address book"), onUp = { leaveLayer() }) {
                    org.dlang.liveimap.ui.contacts.AddressBookScreen()
                }
                "contacts" -> UpPage(S("Copy contacts"), onUp = { leaveLayer() }) {
                    ContactCopyScreen(registerAccount = true)
                }
                "filters" -> {
                    val accountId = rememberSaveable { filterListAccount.value }
                    UpPage(S("Edit filters"), onUp = { leaveLayer() }) {
                        FilterListScreen(accountId = accountId, onOpen = { index, ruleId ->
                            openFilterEditor(index, "", "", "", "", accountId, ruleId)
                        })
                    }
                }
                "filter" -> {
                    // One filter layer. A later edit replaces this page, including when the route is popped back.
                    val index = filterEditIndex.value
                    val from = filterSeedFrom.value
                    val to = filterSeedTo.value
                    val listId = filterSeedListId.value
                    val subject = filterSeedSubject.value
                    val accountId = filterAccountId.value
                    val ruleId = filterRuleId.value
                    key(index, from, to, listId, subject, accountId, ruleId) {
                        UpPage(
                            title = if (index < 0) S("Add filter") else S("Edit filter"),
                            onUp = { leaveLayer() },
                        ) {
                            FilterEditorScreen(
                                accountId = accountId,
                                ruleId = ruleId,
                                index = index,
                                seed = seedCriteria(from, to, listId, subject),
                                onDone = { leaveLayer() },
                            )
                        }
                    }
                }
                "help" -> UpPage(S("Help"), onUp = { leaveLayer() }) {
                    HelpScreen()
                }
                "about" -> UpPage(S("About"), onUp = { leaveLayer() }) {
                    AboutScreen(onOpenLicenses = { openScreen("about/licenses") })
                }
                "about/licenses" -> UpPage(S("Open-source licenses"), onUp = { leaveLayer() }) {
                    LicensesScreen()
                }
                else -> when {
                    screenKey.startsWith("settings/setup?") -> {
                        val setupId = screenKey.substringAfter("accountId=", "").substringBefore("&")
                        org.dlang.liveimap.settings.editors.SetupGuide(
                            accountId = setupId,
                            onDone = { leaveLayer() },
                            onAdvanced = { settings, password, smtp ->
                                val pending = org.dlang.liveimap.settings.editors.PendingNewAccount
                                pending.active = true
                                pending.settings = settings
                                pending.password = password
                                pending.smtpPassword = smtp
                                openScreen("settings/account/_")
                            },
                        )
                    }
                    screenKey.contains("/editor/") -> {
                        val accountId = screenKey.removePrefix("settings/account/").substringBefore("/")
                        val editorId = screenKey.substringAfter("/editor/", "")
                        UpPage(S("Settings"), onUp = { leaveLayer() }) {
                            SettingsTree(
                                onCopyContacts = { openScreen("contacts") },
                                onEditBook = { openScreen("addressbook") },
                                onOpenPanel = { name, _ -> openToolbar(name) },
                                onNavigate = { route -> openScreen(route) },
                                page = "editor",
                                accountId = accountId,
                                editorId = editorId,
                            )
                        }
                    }
                    screenKey.startsWith("settings/account/") -> {
                        val accountId = screenKey.removePrefix("settings/account/").substringBefore("/")
                        UpPage(
                            stringResource(SettingsTreeGenerated.resource("settings_account")),
                            onUp = { leaveLayer() },
                        ) {
                            SettingsTree(
                                onCopyContacts = { openScreen("contacts") },
                                onEditBook = { openScreen("addressbook") },
                                onOpenPanel = { name, _ -> openToolbar(name) },
                                onNavigate = { route -> openScreen(route) },
                                page = "account",
                                accountId = accountId,
                            )
                        }
                    }
                }
            }
        }
    }

    val navGraph: NavGraphBuilder.() -> Unit = remember {
        {
            composable("folders") {
                FolderListScreen(
                    onOpenMailbox = { mailbox ->
                        popScreenRoutes()
                        mailStack = folderFromTree(mailStack, mailbox)
                        navController.navigate("index/${Uri.encode(mailbox)}")
                    },
                    onCompose = { seed -> openCompose(seed) },
                    onOpenUnsent = { openScreen("unsent") },
                    onOpenHelp = { openScreen("help") },
                    onCustomize = { openToolbar("folders") },
                    focusMailbox = focusMailbox.value,
                    focusToken = focusToken.value,
                    onOpenDrawer = openDrawerState.value,
                    onAddAccount = { openScreen("settings/setup") },
                    onOpenAbout = { openScreen("about") },
                    onOpenSettings = { openScreen("settings") },
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
                LaunchedEffect(entry) {
                    val handle = entry.savedStateHandle
                    if (handle.remove<Boolean>("closePane") == true) {
                        closeMessageLayer()
                        openedMessage = null
                    }
                }
                val advancedQuery by entry.savedStateHandle
                    .getStateFlow("advancedQuery", "")
                    .collectAsState()
                val advancedScopeName by entry.savedStateHandle
                    .getStateFlow("advancedScope", "")
                    .collectAsState()
                val advancedScope = searchScopeOrCurrent(advancedScopeName)
                val markKey = "index:$mailbox"
                SideEffect { entry.savedStateHandle["markKey"] = markKey }
                val folderViewToken by entry.savedStateHandle
                    .getStateFlow("folderViewToken", 0)
                    .collectAsState()
                val messageShown = currentFill().slots.any { slot ->
                    !slot.collapsed && slot.layer?.kind == WindowLayerKind.Message
                }
                @Composable
                fun IndexBody(viewKey: Int, watch: Boolean) {
                    key(viewKey) {
                        MessageIndexScreen(
                            mailbox = mailbox,
                            onOpen = { ref, sequence ->
                                placeMessage(ref, sequence, WindowLayerKind.FolderIndex, mailbox, markKey)
                            },
                            onCompose = { seed -> openCompose(seed) },
                            onBack = { failure -> leaveLayer(failure) },
                            onCustomize = { openToolbar("index") },
                            onCustomizeSelection = { openToolbar("selection") },
                            watchMailbox = watch,
                            onAdvanced = { navController.navigate("search/${Uri.encode(mailbox)}") },
                            advancedQuery = advancedQuery.ifEmpty { null },
                            advancedScope = advancedScope,
                            onAdvancedConsumed = {
                                entry.savedStateHandle.remove<String>("advancedQuery")
                                entry.savedStateHandle.remove<String>("advancedScope")
                            },
                            onOpenFolder = { name ->
                                popScreenRoutes()
                                mailStack = folderFromTree(mailStack, name)
                                navController.navigate("index/${Uri.encode(name)}") {
                                    popUpTo("index/{mailbox}") { inclusive = true }
                                }
                            },
                            markKey = markKey,
                            reloadToken = viewKey,
                            messageOpen = messageShown,
                            onCloseMessage = {
                                closeMessageLayer()
                                openedMessage = null
                            },
                        )
                    }
                }
                IndexBody(folderViewToken, watch = true)
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
                        PendingSearch.query = text
                        PendingSearch.scope = scope
                        navController.popBackStack()
                        if (navController.currentDestination?.route == FoundRoutePattern) {
                            navController.popBackStack()
                        }
                        popScreenRoutes()
                        if (mailStack.layers.any { it.kind == WindowLayerKind.FolderIndex }) {
                            mailStack = mailStack.pushSearchIndex(text)
                        }
                        navController.currentBackStack.value
                            .lastOrNull { it.destination.route == "index/{mailbox}" }
                            ?.savedStateHandle
                            ?.set("closePane", true)
                        navController.navigate("found/${Uri.encode(mailbox)}/${scope.name}")
                    },
                )
            }
            composable("toolbar/index") { ScreenLayerPage("toolbar/index") }
            composable("toolbar/selection") { ScreenLayerPage("toolbar/selection") }
            composable("toolbar/folders") { ScreenLayerPage("toolbar/folders") }
            composable("toolbar/reader") { ScreenLayerPage("toolbar/reader") }
            composable("toolbar/compose") { ScreenLayerPage("toolbar/compose") }
            composable(
                route = FoundRoutePattern,
                arguments = listOf(
                    navArgument("mailbox") { type = NavType.StringType },
                    navArgument("scope") { type = NavType.StringType },
                ),
            ) { entry ->
                val encoded = entry.arguments?.getString("mailbox") ?: return@composable
                val mailbox = encoded
                val scopeName = entry.arguments?.getString("scope").orEmpty()
                val query = rememberSaveable { PendingSearch.query }
                var armed by rememberSaveable { mutableStateOf(query) }
                val markKey = "search:$mailbox:$query"
                SideEffect { entry.savedStateHandle["markKey"] = markKey }
                BackHandler { leaveLayer() }
                MessageIndexScreen(
                    mailbox = mailbox,
                    onOpen = { ref, sequence ->
                        placeMessage(ref, sequence, WindowLayerKind.SearchIndex, mailbox, markKey)
                    },
                    messageOpen = currentFill().slots.any { slot ->
                        !slot.collapsed && slot.layer?.kind == WindowLayerKind.Message
                    },
                    onCompose = { seed -> openCompose(seed) },
                    onBack = { failure -> leaveLayer(failure) },
                    onCustomize = { openToolbar("index") },
                    onCustomizeSelection = { openToolbar("selection") },
                    onAdvanced = { navController.navigate("search/${Uri.encode(mailbox)}") },
                    advancedQuery = armed.ifEmpty { null },
                    advancedScope = searchScopeOrCurrent(scopeName),
                    onAdvancedConsumed = { armed = "" },
                    markKey = markKey,
                    onCloseMessage = {
                        closeMessageLayer()
                        openedMessage = null
                    },
                )
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
                BackHandler { leaveLayer() }
                MessageReaderScreen(
                    mailbox = mailbox,
                    uid = args.getLong("uid"),
                    sequence = args.getInt("sequence"),
                    uidValidity = args.getLong("epoch"),
                    onCompose = { seed -> openCompose(seed) },
                    onAdvance = { next, nextSequence ->
                        val mark = entry.savedStateHandle.get<String>("markKey") ?: "index:$mailbox"
                        placeMessage(
                            next,
                            nextSequence,
                            messageSource(mailStack),
                            next.mailbox.ifEmpty { mailbox },
                            mark,
                        )
                        publishMark(next.uid, true)
                    },
                    onBack = { leaveLayer() },
                    onMark = { uid -> publishMark(uid, true) },
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
            composable("compose") { ScreenLayerPage("compose") }
            composable("unsent") { ScreenLayerPage("unsent") }
            composable("settings/setup") { ScreenLayerPage("settings/setup") }
            composable(
                route = "settings/setup?accountId={accountId}",
                arguments = listOf(navArgument("accountId") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                val setupId = entry.arguments?.getString("accountId").orEmpty()
                ScreenLayerPage("settings/setup?accountId=$setupId")
            }
            composable("settings") { ScreenLayerPage("settings") }
            composable("settings/accounts") { ScreenLayerPage("settings/accounts") }
            composable(
                route = "settings/account/{accountId}/editor/{editorId}",
                arguments = listOf(
                    navArgument("accountId") { type = NavType.StringType },
                    navArgument("editorId") { type = NavType.StringType },
                ),
            ) { entry ->
                val accountId = entry.arguments?.getString("accountId").orEmpty()
                val editorId = entry.arguments?.getString("editorId").orEmpty()
                ScreenLayerPage("settings/account/$accountId/editor/$editorId")
            }
            composable(
                route = "settings/account/{accountId}",
                arguments = listOf(navArgument("accountId") { type = NavType.StringType }),
            ) { entry ->
                val accountId = entry.arguments?.getString("accountId").orEmpty()
                ScreenLayerPage("settings/account/$accountId")
            }
            composable("folderLists/expanded") { ScreenLayerPage("folderLists/expanded") }
            composable("folderLists/views") { ScreenLayerPage("folderLists/views") }
            composable("folderLists/starts") { ScreenLayerPage("folderLists/starts") }
            composable("addressbook") { ScreenLayerPage("addressbook") }
            composable("contacts") { ScreenLayerPage("contacts") }
            composable("filters") { ScreenLayerPage("filters") }
            composable("filter") { ScreenLayerPage("filter") }
            composable("help") { ScreenLayerPage("help") }
            composable("about") { ScreenLayerPage("about") }
            composable("about/licenses") { ScreenLayerPage("about/licenses") }
        }
    }

    /** Picking any drawer item closes the drawer. */
    fun navigateFromDrawer(go: suspend () -> Unit) {
        scope.launch {
            go()
            zoom = null
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
            // Blank keeps the built-in name (the account name for its folder list).
            favoriteDraft = if (isFolderListFavorite(favorite)) favorite.label else favoriteDrawerLabel(favorite)
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
        if (org.dlang.liveimap.settings.deletesDefaultView(editingFavorites, current)) {
            android.widget.Toast.makeText(uiContext, org.dlang.liveimap.settings.deletedDefaultViewText(uiContext), android.widget.Toast.LENGTH_LONG).show()
        }
        persistEditingFavorites(editingFavorites.filterNot { it.node == current.node && it.mailbox == current.mailbox })
        editingFavorite = null
    }

    fun backStackHas(route: String): Boolean =
        navController.currentBackStack.value.any { it.destination.route == route }

    /** A drawer mailbox replaces the nav stack so Back does not uncover a folder tree. */
    fun openDrawerMailbox(mailbox: String) {
        mailStack = drawerMailbox(mailStack, mailbox)
        navController.navigate("index/${Uri.encode(mailbox)}") {
            popUpTo(0) { inclusive = true }
        }
    }

    /** Same-account All folders. An open index stays; the tree is what Back shows. */
    /** Drawer "All folders": the tree replaces everything above the drawer. */
    fun openSameAccountTree() {
        pickFromDrawer(listOf(WindowLayer(WindowLayerKind.FolderTree, "root")))
        navController.navigate("folders") { popUpTo(0) { inclusive = true } }
    }

    fun openFavorite(favorite: FolderFavorite) {
        scope.launch {
            zoom = null
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
                mailStack = chooseFolderNode(mailStack, favorite.mailbox)
                navController.navigate("folders") {
                    if (backStackHas("folders")) {
                        popUpTo("folders")
                        launchSingleTop = true
                    } else {
                        popUpTo(0) { inclusive = true }
                    }
                }
            } else {
                mailStack = chooseFavoriteMailbox(mailStack, favorite.mailbox)
                navController.navigate("index/${Uri.encode(favorite.mailbox)}") {
                    popUpTo(0) { inclusive = true }
                }
            }
        }
    }

    fun openIndexRow(account: DrawerAccount, mailbox: String) {
        navigateFromDrawer {
            if (!account.chosen) {
                store.selectAccount(account.id)
                applyOpenAccount()
                mailStack = chooseAccountMailbox(mailStack, account.id, mailbox)
                navController.navigate("index/${Uri.encode(mailbox)}") {
                    popUpTo(0) { inclusive = true }
                }
            } else {
                openDrawerMailbox(mailbox)
            }
        }
    }

    fun openFoldersRow(account: DrawerAccount) {
        navigateFromDrawer {
            if (!account.chosen) {
                store.selectAccount(account.id)
                applyOpenAccount()
                mailStack = chooseAccountTree(mailStack, account.id, "root")
                navController.navigate("folders") {
                    popUpTo(0) { inclusive = true }
                }
            } else {
                openSameAccountTree()
            }
        }
    }

    fun openAccountFavorite(account: DrawerAccount, favorite: FolderFavorite) {
        if (account.chosen) {
            openFavorite(favorite)
            return
        }
        scope.launch {
            zoom = null
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
                mailStack = chooseFolderNode(mailStack, favorite.mailbox)
                navController.navigate("folders") {
                    popUpTo(0) { inclusive = true }
                }
            } else {
                mailStack = chooseFavoriteMailbox(mailStack, favorite.mailbox)
                navController.navigate("index/${Uri.encode(favorite.mailbox)}") {
                    popUpTo(0) { inclusive = true }
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

    var noFreeWindow by remember { mutableStateOf(false) }
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
                    openDrawerMailbox("INBOX")
                }
            },
            onAccountInbox = { account -> openIndexRow(account, "INBOX") },
            onPostponed = { row ->
                navigateFromDrawer {
                    openDrawerMailbox(row)
                }
            },
            onAccountPostponed = { account -> openIndexRow(account, account.postponedMailbox) },
            onAllFolders = {
                navigateFromDrawer {
                    openSameAccountTree()
                }
            },
            onAccountAllFolders = { account -> openFoldersRow(account) },
            onAddFilter = {
                navigateFromDrawer {
                    val chosen = drawerAccounts.firstOrNull { it.chosen }?.id ?: accounts.firstOrNull()?.id
                    if (!chosen.isNullOrEmpty()) openFilterEditor(-1, "", "", "", "", chosen, "", fromDrawer = true)
                }
            },
            showFilters = drawerAccounts.isNotEmpty(),
            onEditFilters = {
                navigateFromDrawer {
                    val chosen = drawerAccounts.firstOrNull { it.chosen }?.id ?: accounts.firstOrNull()?.id
                    if (!chosen.isNullOrEmpty()) {
                        filterListAccount.value = chosen
                        openScreen("filters", fromDrawer = true)
                    }
                }
            },
            onFavorite = { favorite -> openFavorite(favorite) },
            onEditFavorite = { favorite -> openFavoriteEditor(favorite) },
            onAccountFavorite = { account, favorite -> openAccountFavorite(account, favorite) },
            onAccountEditFavorite = { account, favorite -> editAccountFavorite(account, favorite) },
            onSettings = {
                navigateFromDrawer {
                    openScreen("settings", fromDrawer = true)
                }
            },
            onNewWindow = {
                navigateFromDrawer {
                    val slot = withContext(Dispatchers.IO) {
                        org.dlang.liveimap.window.firstFreeSlot(uiContext)
                    }
                    if (slot == null) {
                        noFreeWindow = true
                    } else {
                        org.dlang.liveimap.window.startWindow(uiContext, slot)
                    }
                }
            },
            onManageAddresses = {
                navigateFromDrawer {
                    val chosen = drawerAccounts.firstOrNull { it.chosen }?.id ?: accounts.firstOrNull()?.id
                    if (!chosen.isNullOrEmpty()) {
                        openScreen("settings/account/$chosen/editor/addressBooks", fromDrawer = true)
                    }
                }
            },
            onHelp = {
                navigateFromDrawer {
                    openScreen("help", fromDrawer = true)
                }
            },
            onAbout = {
                navigateFromDrawer {
                    openScreen("about", fromDrawer = true)
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
            label = S("Compose", name = "screen_compose"),
            help = S("Starts a new message."),
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
                    contentDescription = S("Compose", name = "screen_compose"),
                )
            },
        )
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
    CompositionLocalProvider(LocalScreenAccounts provides screenAccounts) {
    LiveImapScaffold {
        Box(Modifier.fillMaxSize()) {
        val composeChoice = composeButton(route.orEmpty(), screenAccounts.ids())
        composeLayer = composeFabLayer(composeChoice, modalDrawer = false)
        LaunchedEffect(composeChoice) {
            if (composeChoice != ComposeButton.Pick) composeAccounts = null
        }
        val record = currentRecord()
        val shown = displayFill
        val mailRoute = route == null || route == "folders" || route == "index/{mailbox}" ||
            route == FoundRoutePattern || route == ReaderRoutePattern
        val screenRoute = isScreenRoute(route)
        val topScreenKey = mailStack.layers.lastOrNull()
            ?.takeIf { it.kind == WindowLayerKind.Screen }
            ?.key
        val hostedNav = remember {
            movableContentOf { modifier: Modifier ->
                NavHost(
                    navController = navController,
                    startDestination = "folders",
                    modifier = modifier,
                    builder = navGraph,
                )
            }
        }
        val baseFill = fill(record, mailStack, currentOffset())
        val hamburger = hamburgerPlan(baseFill, shown, zoom)
        val hamburgerSlot = hamburger.slot?.let { shown.slots[it] }
        val drawerToggle: () -> Unit = { toggleDrawerOverlay() }
        var drawerAreaOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        Box(
            Modifier.fillMaxSize().onGloballyPositioned { drawerAreaOrigin = it.positionInWindow() },
        ) {
        ModalNavigationDrawer(
            drawerContent = {
                // A panel that already shows the drawer gets no second copy; toggleDrawer never opens over it.
                val baseShowsDrawer = fill(record, mailStack, currentOffset()).slots.any {
                    !it.collapsed && it.layer?.kind == WindowLayerKind.Drawer
                }
                if (!baseShowsDrawer) {
                    ModalDrawerSheet(
                        windowInsets = mailScreenInsets().only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
                        content = drawerSheet,
                    )
                }
            },
            modifier = Modifier.fillMaxSize(),
            drawerState = drawerSheetState,
            gesturesEnabled = drawerOverlay,
        ) {
        Box(Modifier.fillMaxSize()) {
        if (!mailRoute && !screenRoute) {
            CompositionLocalProvider(LocalDrawerToggle provides (if (hamburgerSlot != null) drawerToggle else null)) {
                hostedNav(Modifier.fillMaxSize())
            }
        } else {
            val routeKind = when (route) {
                "folders" -> WindowLayerKind.FolderTree
                "index/{mailbox}" -> WindowLayerKind.FolderIndex
                FoundRoutePattern -> WindowLayerKind.SearchIndex
                ReaderRoutePattern -> WindowLayerKind.Message
                else -> null
            }
            val routeOnScreen = shown.slots.any { slot ->
                !slot.collapsed && slot.layer?.kind == routeKind
            }
            val screenOnScreen = shown.slots.any { slot ->
                !slot.collapsed && slot.layer?.kind == WindowLayerKind.Screen && slot.layer?.key == topScreenKey
            }
            MailLayout(
                record = record,
                fillResult = shown,
                sizes = treeDp,
                keyPanelId = activeKey,
                keyLayer = activeLayer,
                onKey = { id -> keyPanelId = id },
            ) { slot ->
                CompositionLocalProvider(LocalDrawerToggle provides (if (slot == hamburgerSlot) drawerToggle else null)) {
                val kind = slot.layer?.kind
                val hostThisSlot = when {
                    screenRoute && kind == WindowLayerKind.Screen && slot.layer?.key == topScreenKey -> true
                    kind != null && kind == routeKind && routeOnScreen -> true
                    else -> false
                }
                if (hostThisSlot) {
                    hostedNav(Modifier.fillMaxSize())
                } else {
                    when (kind) {
                        WindowLayerKind.Drawer -> drawerPanel()
                        WindowLayerKind.FolderTree -> folderListPane()
                        WindowLayerKind.FolderIndex -> {
                            // The slot's layer: a pinned message list with no index in the stack shows its stand-in.
                            val box = slot.layer?.key.orEmpty()
                            MessageIndexScreen(
                                mailbox = box,
                                onOpen = { ref, sequence ->
                                    placeMessage(ref, sequence, WindowLayerKind.FolderIndex, box, "index:$box")
                                },
                                onCompose = { seed -> openCompose(seed) },
                                onBack = { failure -> leaveLayer(failure) },
                                onCustomize = { openToolbar("index") },
                                onCustomizeSelection = { openToolbar("selection") },
                                markKey = "index:$box",
                                messageOpen = shown.slots.any { !it.collapsed && it.layer?.kind == WindowLayerKind.Message },
                                onCloseMessage = {
                                    closeMessageLayer()
                                    openedMessage = null
                                },
                            )
                        }
                        WindowLayerKind.SearchIndex -> {
                            val box = mailStack.layers.firstOrNull { it.kind == WindowLayerKind.FolderIndex }?.key.orEmpty()
                            val query = slot.layer?.key.orEmpty()
                            MessageIndexScreen(
                                mailbox = box,
                                onOpen = { ref, sequence ->
                                    placeMessage(ref, sequence, WindowLayerKind.SearchIndex, box, "search:$box:$query")
                                },
                                onCompose = { seed -> openCompose(seed) },
                                onBack = { failure -> leaveLayer(failure) },
                                onCustomize = { openToolbar("index") },
                                onCustomizeSelection = { openToolbar("selection") },
                                advancedQuery = query.ifEmpty { null },
                                markKey = "search:$box:$query",
                                messageOpen = shown.slots.any { !it.collapsed && it.layer?.kind == WindowLayerKind.Message },
                                onCloseMessage = {
                                    closeMessageLayer()
                                    openedMessage = null
                                },
                            )
                        }
                        WindowLayerKind.Message -> {
                            val opened = openedMessage
                            val uid = slot.layer?.key?.toLongOrNull()
                            if (uid != null) {
                                val mailbox = if (opened != null && opened.uid == uid) {
                                    opened.mailbox
                                } else {
                                    mailStack.layers.firstOrNull { it.kind == WindowLayerKind.FolderIndex }?.key.orEmpty()
                                }
                                val sequence = if (opened != null && opened.uid == uid) opened.sequence else 0
                                val epoch = if (opened != null && opened.uid == uid) opened.uidValidity else 0L
                                MessageReaderScreen(
                                    mailbox = mailbox,
                                    uid = uid,
                                    sequence = sequence,
                                    uidValidity = epoch,
                                    onCompose = { seed -> openCompose(seed) },
                                    onAdvance = { next, nextSequence ->
                                        val from = messageSource(mailStack)
                                        placeMessage(
                                            next,
                                            nextSequence,
                                            from,
                                            next.mailbox.ifEmpty { mailbox },
                                            opened?.markKey ?: "index:$mailbox",
                                        )
                                        IndexMarks.publish(opened?.markKey ?: "index:$mailbox", next.uid, true)
                                    },
                                    onBack = {
                                        closeMessageLayer()
                                        openedMessage = null
                                    },
                                    onMark = { marked ->
                                        IndexMarks.publish(opened?.markKey ?: "index:$mailbox", marked, true)
                                    },
                                    onCustomize = { openToolbar("reader") },
                                    onFilterLike = { from, to, listId, subject, accountId ->
                                        openFilterEditor(-1, from, to, listId, subject, accountId, "")
                                    },
                                )
                            }
                        }
                        WindowLayerKind.Screen -> {
                            val screenKey = slot.layer?.key.orEmpty()
                            if (screenKey.isEmpty()) Box(Modifier.fillMaxSize()) else ScreenLayerPage(screenKey)
                        }
                        null -> Box(Modifier.fillMaxSize())
                    }
                }
                }
            }
            if (screenRoute) {
                if (!screenOnScreen) hostedNav(Modifier.size(0.dp))
            } else if (!routeOnScreen) {
                hostedNav(Modifier.size(0.dp))
            }
            StackArrowsBar(
                arrows = org.dlang.liveimap.window.stackArrows(shown),
                onMove = { down ->
                    stackView = org.dlang.liveimap.window.stackViewStep(record, stackView, mailStack, down)
                    org.dlang.liveimap.engine.DebugLog.event("stack") { "view moved ${if (down) "down" else "up"} to ${stackView?.offset}" }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
            val startupText = when (startupProblem) {
                org.dlang.liveimap.window.StartupProblem.AccountMissing ->
                    S("The startup account no longer exists. Showing the drawer.", name = "startup_problem_account")
                org.dlang.liveimap.window.StartupProblem.FolderMissing ->
                    S("No startup folder is set. Showing the drawer.", name = "startup_problem_folder")
                org.dlang.liveimap.window.StartupProblem.UnknownView ->
                    S("The startup view setting is not recognized. Showing the drawer.", name = "startup_problem_view")
                org.dlang.liveimap.window.StartupProblem.NoFavorite ->
                    S("There are no favorites to start on. Showing the drawer.", name = "startup_problem_no_favorite")
                org.dlang.liveimap.window.StartupProblem.FavoriteInvalid ->
                    S("The default favorite has no folder. Showing the drawer.", name = "startup_problem_bad_favorite")
                null -> null
            }
            if (startupText != null) {
                // Tap to dismiss; it stays until then.
                Text(
                    startupText,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable { startupProblem = null }.padding(12.dp),
                )
            }
            if (shown.problem == org.dlang.liveimap.window.LayoutRecordProblem.NoUnpinnedPanel) {
                Text(
                    S("Every panel in this layout is pinned, so what you open has nowhere to show. Change the layout in Settings.", name = "layout_runtime_all_pinned"),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
                )
            }
        }
        }
        }
        if (hamburger.overOpenDrawer) {
            // The same hamburger, above the sheet and scrim, on the spot the bar's button reported.
            val spot = DrawerToggleSpot.window
            val at = if (spot == null) androidx.compose.ui.unit.IntOffset.Zero else androidx.compose.ui.unit.IntOffset(
                (spot.x - drawerAreaOrigin.x).toInt(),
                (spot.y - drawerAreaOrigin.y).toInt(),
            )
            DrawerToggleIcon(
                drawerToggle,
                Modifier.offset { at }.testTag("drawer-toggle-open"),
            )
        }
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
                isDefaultView = editingFavorites.any { it.defaultView && it.node == editingFavorite?.node && it.mailbox == editingFavorite?.mailbox },
                onDefaultView = { on ->
                    val current = editingFavorite
                    if (current != null) persistEditingFavorites(org.dlang.liveimap.settings.withDefaultView(editingFavorites, current, on))
                },
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
                        if (changed) S("This certificate changed") else S("Trust this certificate?"),
                    )
                },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (changed) Text(S("The certificate is not the one pinned for this account."))
                        // F76: the reason and the fingerprint first; sender-chosen names after, cleaned and cut.
                        Text(
                            S("Problem: %1\$s", certDisplayText(pendingCert.prompt.reason)),
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
                            S("Trust this certificate for %1\$s",
                                "${pendingCert.prompt.host}:${pendingCert.prompt.port}",
                            ),
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = decline) {
                        Text(S("Don't trust"))
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
                title = { Text(S("Send the password in the clear?")) },
                text = { Text(S("This connection is not encrypted. The password will be sent in the clear.")) },
                confirmButton = {
                    TextButton(onClick = { pendingPlain(true) }) {
                        Text(S("Send the password"))
                    }
                },
                dismissButton = {
                    TextButton(onClick = decline) {
                        Text(S("Don't send"))
                    }
                },
            )
        }
        if (noFreeWindow) {
            AlertDialog(
                onDismissRequest = { noFreeWindow = false },
                title = { Text(S("No free window")) },
                confirmButton = {
                    TextButton(onClick = { noFreeWindow = false }) {
                        Text(S("OK"))
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
                title = { Text(S("Compose", name = "screen_compose")) },
                text = {
                    if (listed.isEmpty()) {
                        Text(S("No mail account yet"))
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            for (account in listed) {
                                TextButton(
                                    onClick = {
                                        openCompose(
                                            ComposeSeed(
                                                ComposeKind.New,
                                                null,
                                                emptyList(),
                                                accountId = account.id,
                                            ),
                                        )
                                        composeAccounts = null
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(account.name)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { composeAccounts = null }) {
                        Text(S("Cancel"))
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
    showFilters: Boolean,
    onSettings: () -> Unit,
    onNewWindow: () -> Unit,
    onManageAddresses: () -> Unit = {},
    onHelp: () -> Unit,
    onAbout: () -> Unit,
) {
    if (blocks.isEmpty()) {
        // The drawer is the favorites list. The account's folder list (shown as its name), INBOX and Postponed are favorites too.
        for (favorite in favorites) {
            DrawerFavoriteRow(favorite, header, postponedMailbox,
                onOpen = { if (isFolderListFavorite(favorite)) onAllFolders() else onFavorite(favorite) },
                onEdit = { onEditFavorite(favorite) })
        }
        if (showFilters) {
            NavigationDrawerItem(
                label = { Text(S("Add filter")) },
                selected = false,
                onClick = { UnsavedEdits.guard(onAddFilter) },
            )
            NavigationDrawerItem(
                label = { Text(S("Edit filters")) },
                selected = false,
                onClick = { UnsavedEdits.guard(onEditFilters) },
            )
        }
    } else {
        for (account in blocks) {
            if (account.corrupt) {
                // Its favorites cannot be read, so the account name stays as a fixed way into its folder list.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { UnsavedEdits.guard { onAccountAllFolders(account) } }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("drawer-account-name"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = account.name, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ChevronRight, contentDescription = null)
                }
                Text(
                    text = S("This account's settings could not be read."),
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .testTag("drawer-account-corrupt"),
                )
            }
            for (favorite in account.favorites) {
                DrawerFavoriteRow(favorite, account.name, account.postponedMailbox,
                    onOpen = { if (isFolderListFavorite(favorite)) onAccountAllFolders(account) else onAccountFavorite(account, favorite) },
                    onEdit = { onAccountEditFavorite(account, favorite) })
            }
        }
        if (showFilters) {
            NavigationDrawerItem(
                label = { Text(S("Add filter")) },
                selected = false,
                onClick = { UnsavedEdits.guard(onAddFilter) },
            )
            NavigationDrawerItem(
                label = { Text(S("Edit filters")) },
                selected = false,
                onClick = { UnsavedEdits.guard(onEditFilters) },
            )
        }
    }
    HorizontalDivider()
    NavigationDrawerItem(
        label = { Text(S("New window")) },
        selected = false,
        onClick = { UnsavedEdits.guard(onNewWindow) },
    )
    NavigationDrawerItem(
        label = { Text(S("Settings")) },
        selected = false,
        onClick = { UnsavedEdits.guard(onSettings) },
    )
    NavigationDrawerItem(
        label = { Text(S("Manage addresses")) },
        selected = false,
        onClick = { UnsavedEdits.guard(onManageAddresses) },
    )
    NavigationDrawerItem(
        label = { Text(S("Help")) },
        selected = false,
        onClick = { UnsavedEdits.guard(onHelp) },
    )
    NavigationDrawerItem(
        label = { Text(S("About")) },
        selected = false,
        onClick = { UnsavedEdits.guard(onAbout) },
    )
}

/** One drawer favorite: tap opens, long-press edits (rename, reorder, delete, default view). */
@Composable
private fun DrawerFavoriteRow(
    favorite: FolderFavorite,
    accountName: String,
    postponedMailbox: String,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
) {
    // Unnamed INBOX reads as the old row did, translated.
    val shown = favoriteDrawerLabel(favorite, accountName, S("INBOX"))
    val described = if (favorite.label.isBlank() && !favorite.node && favorite.mailbox == postponedMailbox && postponedMailbox.isNotEmpty()) {
        S("Postponed")
    } else {
        shown
    }
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
                    onClick = { UnsavedEdits.guard(onOpen) },
                    onLongClick = { UnsavedEdits.guard(onEdit) },
                )
                .semantics { contentDescription = described },
        )
    }
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
    isDefaultView: Boolean = false,
    onDefaultView: (Boolean) -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S("Favorite")) },
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
                    label = { Text(S("Name")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                // One favorite per account carries the tag; tagging this one moves it here.
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { onDefaultView(!isDefaultView) }.testTag("favorite-default-view"),
                ) {
                    androidx.compose.material3.Checkbox(checked = isDefaultView, onCheckedChange = onDefaultView)
                    Text(S("Default view", name = "favorite_default_view"))
                }
            }
        },
        confirmButton = {
            Column {
                TextButton(onClick = onMoveUp) { Text(S("Move up")) }
                TextButton(onClick = onMoveDown) { Text(S("Move down")) }
                TextButton(onClick = onSave) { Text(S("Save")) }
                TextButton(onClick = onDelete) { Text(S("Delete")) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(S("Cancel")) }
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

internal fun favoriteDrawerLabel(favorite: FolderFavorite, accountName: String = "", inboxText: String = "INBOX"): String {
    if (favorite.label.isNotBlank()) return favorite.label
    if (!favorite.node && favorite.mailbox == "INBOX") return inboxText
    // Unnamed, the folder-list row reads as the old fixed row did: the account name. INBOX reads INBOX.
    if (isFolderListFavorite(favorite) && accountName.isNotBlank()) return accountName
    return favoriteLabel(favorite.node, favorite.mailbox, favorite.delimiter)
}

internal enum class PaneAxis {
    SideBySide,
    TopBottom,
}

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
        val label = S("Resize pane")
        val wider = S("Wider")
        val narrower = S("Narrower")
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
        val label = S("Resize pane")
        val taller = S("Taller")
        val shorter = S("Shorter")
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
internal fun AxisSplit(
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

internal fun resetToNoAccount(accounts: List<org.dlang.liveimap.settings.AccountChoice>): Boolean = accounts.isEmpty()

/** Whether a missing open account should open the drawer over [route]. Read at the moment it would open. */
internal fun openDrawerOnNoAccount(route: String?, accounts: List<AccountChoice>): Boolean {
    if (accounts.isEmpty()) return false
    val showing = route.orEmpty()
    if (showing == "settings" || showing.startsWith("settings/")) return false
    if (showing == "help" || showing == "about" || showing == "about/licenses") return false
    return true
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
                    navigationIcon = { androidx.compose.foundation.layout.Row {
                        DrawerToggleButton()
                        IconButton(onClick = up) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = S("Navigate up"),
                            )
                        }
                    } },
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

/** F105: the reader route carries the folder's UIDVALIDITY as listed (0 = unknown, captured on first open). */
internal const val ReaderRoutePattern = "reader/{mailbox}/{uid}/{sequence}?epoch={epoch}"

/** A search index above the folder index. Its query is [PendingSearch], not the folder index's. */
internal const val FoundRoutePattern = "found/{mailbox}/{scope}"

internal fun navForwardChoice(stored: String): Boolean? = when (stored) {
    "true" -> true
    "false" -> false
    else -> null
}

internal fun readerRoute(mailbox: String, uid: Long, sequence: Int, epoch: Long): String =
    "reader/${Uri.encode(mailbox)}/$uid/$sequence?epoch=$epoch"

private val exactScreenRoutes = setOf(
    "compose",
    "unsent",
    "settings",
    "settings/accounts",
    "settings/setup",
    "folderLists/expanded",
    "folderLists/views",
    "folderLists/starts",
    "addressbook",
    "contacts",
    "filters",
    "filter",
    "help",
    "about",
    "about/licenses",
    "toolbar/index",
    "toolbar/selection",
    "toolbar/folders",
    "toolbar/reader",
    "toolbar/compose",
)

/** A settings editor, including setup and the folder-list pages, closes when the marker is refused. */
internal fun screenClosesOnSettingsRefusal(screenKey: String): Boolean {
    if (screenKey.startsWith("settings")) return true
    return screenKey == "folderLists/expanded" ||
        screenKey == "folderLists/views" ||
        screenKey == "folderLists/starts"
}

/** [pops] routes come off above an existing copy of the screen; [existing] says there was one. */
internal data class ScreenOpen(val stack: WindowStack, val pops: Int, val existing: Boolean)

internal fun screenOpenPlan(stack: WindowStack, key: String): ScreenOpen {
    val at = stack.layers.indexOfFirst { it.kind == WindowLayerKind.Screen && it.key == key }
    val pops = if (at >= 0) stack.layers.size - at - 1 else 0
    return ScreenOpen(stack.pushScreen(key), pops, existing = at >= 0)
}

/** A layer already in the stack still needs its route when the shown route is not that screen. */
internal fun screenNeedsNavigate(plan: ScreenOpen, shownKey: String?, key: String): Boolean =
    !plan.existing || shownKey != key

/** A route pattern with its `{name}` arguments filled in, as a screen key. Null when an argument is missing. */
internal fun screenKeyOfRoute(pattern: String, arg: (String) -> String?): String? {
    var missing = false
    val filled = Regex("\\{([^}]+)\\}").replace(pattern) { match ->
        arg(match.groupValues[1]) ?: run { missing = true; "" }
    }
    if (missing) return null
    return screenLayerKey(filled)
}

/** The screen-layer key is the route that was opened. Mail routes are not screens. */
internal fun screenLayerKey(route: String): String? = if (isScreenRoute(route)) route else null

internal fun isScreenRoute(route: String?): Boolean {
    if (route.isNullOrEmpty()) return false
    val path = route.substringBefore('?')
    if (path in exactScreenRoutes) return true
    return path.startsWith("settings/account/")
}

private fun toolbarScreenOf(route: String): ToolbarScreen? = when (route) {
    "toolbar/index" -> ToolbarScreen.Index
    "toolbar/selection" -> ToolbarScreen.Selection
    "toolbar/folders" -> ToolbarScreen.Folders
    "toolbar/reader" -> ToolbarScreen.Reader
    "toolbar/compose" -> ToolbarScreen.Compose
    else -> null
}
