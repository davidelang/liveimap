package org.dlang.liveimap.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import org.dlang.liveimap.ui.compose.formatMailbox
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import org.dlang.liveimap.ui.AlertDialog
import org.dlang.liveimap.ui.ObscuredTouchFilter
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import org.dlang.liveimap.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.davidlang.liveimap.BuildConfig
import com.davidlang.liveimap.R
import org.dlang.liveimap.engine.ClientCertStore
import org.dlang.liveimap.engine.LibetpanMailSession
import org.dlang.liveimap.engine.ServerCheck
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.engine.probeServer
import org.dlang.liveimap.engine.sieve.SieveCapabilities
import org.dlang.liveimap.engine.sieve.SieveFailure
import org.dlang.liveimap.engine.sieve.greetSieve
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.confirmMailCertificate
import org.dlang.liveimap.session.dropMailSession
import org.dlang.liveimap.session.existingMailSession
import org.dlang.liveimap.session.extraIdleBudgetFromText
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.session.watchedFolderText
import org.dlang.liveimap.session.watchedFoldersFromText
import org.dlang.liveimap.ui.contacts.AndroidContactSet
import org.dlang.liveimap.ui.contacts.androidContactSetsOnce
import org.dlang.liveimap.ui.contacts.hasReadContacts
import org.dlang.liveimap.ui.contacts.moveCompletionSource
import org.dlang.liveimap.ui.contacts.withSourceEnabled
import org.dlang.liveimap.ui.debug.DebugReportReview
import org.dlang.liveimap.ui.FavoriteEditDialog
import org.dlang.liveimap.ui.FavoriteWhere
import org.dlang.liveimap.ui.folder.MailboxChooser
import org.dlang.liveimap.ui.paneControlsEnabled
import org.dlang.liveimap.ui.toolbar.ToolbarEditorScreen
import org.dlang.liveimap.ui.toolbar.ToolbarScreen
import org.dlang.liveimap.ui.toolbar.notePaneScroll

/**
 * F96: a failed save (e.g. Keystore unavailable, so a password is refused) is shown, never an uncaught
 * exception that ends the process.
 */
internal val settingsSaveFailure = androidx.compose.runtime.mutableStateOf<String?>(null)
private val settingsIo = CoroutineScope(
    SupervisorJob() + Dispatchers.Main.immediate +
        kotlinx.coroutines.CoroutineExceptionHandler { _, error ->
            android.util.Log.w("liveimap", "settings save failed", error)
            settingsSaveFailure.value = error.message ?: error.toString()
        },
)
private val openSettingsEditors = AtomicReference<List<SettingsEditor>>(emptyList())

private fun bindSettingsEditor(editor: SettingsEditor) {
    openSettingsEditors.updateAndGet { it + editor }
}

private fun unbindSettingsEditor(editor: SettingsEditor) {
    openSettingsEditors.updateAndGet { it - editor }
}

private fun sameSieveAccount(openId: String?, capturedId: String?): Boolean =
    openId.orEmpty() == capturedId.orEmpty()

private class EditorEpoch(val settings: Int, val password: Int, val smtp: Int)

private class EditorLoad(
    val editingId: String?,
    val settings: AccountSettings,
    val password: String,
    val smtpPassword: String,
    val accounts: List<AccountChoice>,
)

private class SettingsEditor(
    val store: DataStoreSettingsStore,
    val appContext: Context,
    val ui: CoroutineScope,
) {
    val settingsState = mutableStateOf(AccountSettings())
    private val settingsMirror = AtomicReference(AccountSettings())
    private val settingsEpoch = AtomicInteger(0)
    private val passwordEpoch = AtomicInteger(0)
    private val smtpEpoch = AtomicInteger(0)
    private val editingMirror = AtomicReference<String?>(null)
    private val editingIdState = mutableStateOf<String?>(null)
    val passwordState = mutableStateOf("")
    val smtpPasswordState = mutableStateOf("")
    val accountsState = mutableStateOf<List<AccountChoice>>(emptyList())
    var ready by mutableStateOf(false)
    var settings: AccountSettings
        get() = settingsState.value
        set(value) {
            settingsMirror.set(value)
            settingsState.value = value
        }
    var password by passwordState
    var smtpPassword by smtpPasswordState
    var accounts by accountsState

    /** F121: the account Settings is editing. Settings never changes the drawer's active account. */
    var editingId: String?
        get() = editingIdState.value
        set(value) {
            editingMirror.set(value)
            editingIdState.value = value
        }
    val bound: DataStoreSettingsStore
        get() = editingId?.let { store.forAccount(it) } ?: store

    fun persist(next: AccountSettings) {
        if (!ready) return
        val rejected = rejectedAccountSettings(next)
        if (rejected != null) {
            settingsSaveFailure.value = rejected
            return
        }
        // F32: the store drops passwords/pins when a server moves; show that here instead of a stale password
        val retarget = org.dlang.liveimap.accounts.retargetCredentials(settings, next)
        if (retarget.dropImapPassword) {
            passwordEpoch.incrementAndGet()
            password = ""
        }
        if (retarget.dropSmtpPassword) {
            smtpEpoch.incrementAndGet()
            smtpPassword = ""
        }
        settingsEpoch.incrementAndGet()
        settings = retarget.settings
        val target = bound // F121: the account being edited now, even if another is picked before this runs
        settingsSaveFailure.value = null
        settingsIo.launch {
            saveKeepingToolbars(target)
            SettingsRevision.bump()
        }
    }

    // saveKeepingToolbars copies the stored row count over the snapshot, so this leaf writes the store itself.
    fun persistToolbarRows(number: Int) {
        if (!ready || number !in 1..4 || number == settings.toolbarRows) return
        val next = settings.copy(toolbarRows = number)
        val rejected = rejectedAccountSettings(next)
        if (rejected != null) {
            settingsSaveFailure.value = rejected
            return
        }
        settingsEpoch.incrementAndGet()
        settings = next
        val target = bound
        settingsSaveFailure.value = null
        settingsIo.launch {
            target.update { it.copy(toolbarRows = number) }
        }
    }

    fun persistPassword(value: String) {
        if (!ready) return
        passwordEpoch.incrementAndGet()
        password = value
        val target = bound // F121: the account being edited now, even if another is picked before this runs
        val saving = password
        settingsIo.launch {
            target.onAccount { target.setPassword(saving) }
        }
    }

    fun persistSmtpPassword(value: String) {
        if (!ready) return
        smtpEpoch.incrementAndGet()
        smtpPassword = value
        val target = bound // F121: the account being edited now, even if another is picked before this runs
        val saving = smtpPassword
        settingsIo.launch {
            target.onAccount { target.setSmtpPassword(saving) }
        }
    }

    fun load() {
        settingsIo.launch {
            val epoch = captureEpochs()
            store.onAccount(publish = { loaded: EditorLoad ->
                applyReload(loaded, epoch, force = false)
            }) {
                computeReload(editingMirror.get())
            }
        }
    }

    fun selectAccount(id: String) {
        settingsIo.launch {
            val epoch = captureEpochs()
            store.onAccount(publish = { loaded: EditorLoad ->
                editingId = id // F121: edit it; the active account is unchanged
                applyReload(loaded, epoch, force = true)
            }) {
                computeReload(id)
            }
        }
    }

    fun addAccount() {
        settingsIo.launch {
            val epoch = captureEpochs()
            store.onAccount(publish = { loaded: EditorLoad ->
                applyReload(loaded, epoch, force = true)
            }) {
                val created = store.addAccount().ifEmpty { null } // F121: edit the new account; not made active
                computeReload(created)
            }
        }
    }

    fun removeAccount(id: String) {
        settingsIo.launch {
            val epoch = captureEpochs()
            store.onAccount(publish = { loaded: EditorLoad? ->
                if (loaded != null) applyReload(loaded, epoch, force = true)
            }) {
                if (!store.removeAccount(id)) return@onAccount null
                dropMailSession(id)
                val current = editingMirror.get()
                computeReload(if (current == id) null else current)
            }
        }
    }

    private fun captureEpochs() = EditorEpoch(settingsEpoch.get(), passwordEpoch.get(), smtpEpoch.get())

    private suspend fun computeReload(preferred: String?): EditorLoad {
        val listed = store.listAccounts()
        val editing = if (preferred == null || listed.none { it.id == preferred }) {
            listed.firstOrNull { it.chosen }?.id ?: listed.firstOrNull()?.id
        } else {
            preferred
        }
        val boundStore = editing?.let { store.forAccount(it) } ?: store
        return EditorLoad(
            editingId = editing,
            settings = boundStore.load(),
            password = boundStore.password(),
            smtpPassword = boundStore.smtpPassword(),
            // "chosen" here marks the account being edited (Settings' own selection), not the active one
            accounts = listed.map { it.copy(chosen = it.id == editing) },
        )
    }

    private fun applyReload(loaded: EditorLoad, epoch: EditorEpoch, force: Boolean) {
        if (force || settingsEpoch.get() == epoch.settings) {
            editingId = loaded.editingId
            settings = loaded.settings
        }
        if (force || passwordEpoch.get() == epoch.password) password = loaded.password
        if (force || smtpEpoch.get() == epoch.smtp) smtpPassword = loaded.smtpPassword
        accounts = loaded.accounts
        ready = true
    }

    // The mirror is written on Main with the editor. Publish the write-back on the caller before the next command.
    suspend fun saveKeepingToolbars(target: DataStoreSettingsStore) {
        target.onAccount(publish = { saved ->
            settings = settings.keepingStoredToolbars(saved)
        }) {
            val snapshot = settingsMirror.get()
            target.update { stored -> snapshot.keepingStoredToolbars(stored) }
        }
    }
}

/**
 * Writes only the Sieve pin through [store], bound to [editingId] when that was captured.
 * Does not save the rest of the settings. The open editor for that account keeps the pin too.
 */
internal suspend fun persistSievePin(store: DataStoreSettingsStore, editingId: String?, fingerprint: String) {
    val target = if (editingId.isNullOrEmpty()) store else store.forAccount(editingId)
    // The pin is stored in this command and published on the caller before the next one, so a save cannot encode the old pin.
    target.onAccount(publish = {
        val editors = openSettingsEditors.get()
        for (editor in editors) {
            if (!sameSieveAccount(editor.editingId, editingId)) continue
            editor.settings = editor.settings.copy(sieveCertPin = fingerprint)
        }
    }) {
        target.update { it.copy(sieveCertPin = fingerprint) }
    }
    SettingsRevision.bump()
}

/**
 * No open session, or a JMAP account, does not show an IMAP "server lacks" reason.
 * An open IMAP session uses what that session advertised.
 */
internal fun serverCapAllows(jmapFolders: Boolean, sessionOpen: Boolean, advertised: Boolean): Boolean {
    if (jmapFolders || !sessionOpen) return true
    return advertised
}

/** Bumped after every settings save, so another editor on screen reloads. */
internal object SettingsRevision {
    private val state = androidx.compose.runtime.mutableIntStateOf(0)
    val value: Int get() = state.intValue
    fun bump() { state.intValue = state.intValue + 1 }
}

/**
 * Bumped when a toolbar save lands in the store. [ToolbarEditorScreen] does not watch [SettingsRevision],
 * so open copies reload from this instead of keeping a stale bar or row count.
 */
internal object ToolbarRevision {
    private val state = androidx.compose.runtime.mutableIntStateOf(0)
    val value: Int get() = state.intValue
    fun bump() { state.intValue = state.intValue + 1 }
}

private fun AccountSettings.keepingStoredToolbars(stored: AccountSettings) = copy(
    indexBar = stored.indexBar,
    selectionBar = stored.selectionBar,
    folderBar = stored.folderBar,
    readerToolbar = stored.readerToolbar,
    composeBar = stored.composeBar,
    toolbarRows = stored.toolbarRows,
)

private fun toolbarsDiffer(left: AccountSettings, right: AccountSettings) =
    left.indexBar != right.indexBar ||
        left.selectionBar != right.selectionBar ||
        left.folderBar != right.folderBar ||
        left.readerToolbar != right.readerToolbar ||
        left.composeBar != right.composeBar ||
        left.toolbarRows != right.toolbarRows

/**
 * Change layout is the only in-process apply. [generation] starts at 0.
 * [fullScreenGeneration] is what MainActivity collects to reload full screen.
 */
internal object LayoutApply {
    private val panes = androidx.compose.runtime.mutableIntStateOf(0)
    private val screen = androidx.compose.runtime.mutableIntStateOf(0)
    val generation: Int get() = panes.intValue
    val fullScreenGeneration: Int get() = screen.intValue
    fun request() { panes.intValue = panes.intValue + 1 }
    fun noteFullScreen() { screen.intValue = screen.intValue + 1 }
}

@Composable
internal fun rememberSettingsGate(): String? {
    val context = LocalContext.current.applicationContext
    var error by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        error = startupSettingsError(context)
        ready = true
    }
    return if (!ready) "" else error
}

@Composable
private fun rememberSettingsEditor(): SettingsEditor {
    val appContext = LocalContext.current.applicationContext
    val ui = rememberCoroutineScope()
    val store = remember { DataStoreSettingsStore(appContext) }
    val editor = remember(store) { SettingsEditor(store, appContext, ui) }
    DisposableEffect(editor) {
        bindSettingsEditor(editor)
        onDispose { unbindSettingsEditor(editor) }
    }
    LaunchedEffect(store) { editor.load() }
    return editor
}

private class SectionOpen(private val packed: MutableState<String>) {
    val open: Set<String>
        get() = if (packed.value.isEmpty()) emptySet() else packed.value.split('\n').toSet()

    fun toggle(id: String) {
        val now = open
        val next = if (id in now) now - id else now + id
        packed.value = next.joinToString("\n")
    }
}

@Composable
private fun rememberSectionOpen(): SectionOpen {
    val packed = rememberSaveable { mutableStateOf("") }
    return remember(packed) { SectionOpen(packed) }
}

@Composable
fun SettingsTree(
    onCopyContacts: () -> Unit,
    onEditBook: () -> Unit,
    onOpenPanel: (String, String) -> Unit,
) {
    val gate = rememberSettingsGate()
    val editor = rememberSettingsEditor()
    if (!gate.isNullOrEmpty()) {
        Text(gate)
        return
    }
    if (gate == "") return
    if (!editor.ready) return
    val sections = rememberSectionOpen()
    val settings = editor.settings
    val depth = 0
    val scroll = rememberScrollState()
    var editingFavorite by remember { mutableStateOf<FolderFavorite?>(null) }
    var favoriteDraft by remember { mutableStateOf("") }
    val picking = remember { mutableStateOf<MailboxPick?>(null) }
    val walk = SettingsWalk(
        editor = editor,
        sections = sections,
        scroll = scroll,
        onCopyContacts = onCopyContacts,
        onEditBook = onEditBook,
        onOpenPanel = onOpenPanel,
        onEditFavorite = { favorite ->
            editingFavorite = favorite
            favoriteDraft = if (favorite.label.isNotBlank()) {
                favorite.label
            } else {
                favoriteLabel(favorite.node, favorite.mailbox, favorite.delimiter)
            }
        },
        picking = picking,
    )
    BackHandler(enabled = picking.value != null) { picking.value = null }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInRoot()
                notePaneScroll(scroll, bounds.top, bounds.bottom)
            }
            .verticalScroll(scroll)
            .padding(16.dp)
            .testTag("smoke-settings"),
    ) {
            val saveFailure = settingsSaveFailure.value
            if (saveFailure != null) {
                Text(saveFailure)
            }
            for (node in installedSettingsRoots()) {
                renderSettingsNode(node, depth, walk)
            }
        }
    MailboxPickDialog(editor, picking.value) { picking.value = it }
    val editing = editingFavorite
    if (editing != null) {
        FavoriteEditDialog(
            where = FavoriteWhere(
                account = settings.email.ifBlank { settings.username },
                server = settings.imapHost,
                path = editing.mailbox,
            ),
            name = favoriteDraft,
            onName = { favoriteDraft = it },
            onMoveUp = { moveTreeFavorite(editor, editing, -1) },
            onMoveDown = { moveTreeFavorite(editor, editing, 1) },
            onSave = {
                val trimmed = favoriteDraft.trim()
                editor.persist(
                    editor.settings.copy(
                        favorites = editor.settings.favorites.map { item ->
                            if (item.node == editing.node && item.mailbox == editing.mailbox) {
                                item.copy(label = trimmed)
                            } else {
                                item
                            }
                        },
                    ),
                )
                editingFavorite = null
            },
            onDelete = {
                editor.persist(
                    editor.settings.copy(
                        favorites = editor.settings.favorites.filterNot {
                            it.node == editing.node && it.mailbox == editing.mailbox
                        },
                    ),
                )
                editingFavorite = null
            },
            onDismiss = { editingFavorite = null },
        )
    }
}

private fun moveTreeFavorite(editor: SettingsEditor, current: FolderFavorite, delta: Int) {
    val list = editor.settings.favorites
    val index = list.indexOfFirst { it.node == current.node && it.mailbox == current.mailbox }
    if (index < 0) return
    val target = index + delta
    if (target !in list.indices) return
    val next = list.toMutableList()
    val swap = next[target]
    next[target] = next[index]
    next[index] = swap
    editor.persist(editor.settings.copy(favorites = next))
}

@Composable
fun ExpandedFoldersScreen() {
    val editor = rememberSettingsEditor()
    if (!editor.ready) return
    SettingsPage {
        ExpandedFoldersBody(editor)
    }
}

@Composable
private fun ExpandedFoldersBody(editor: SettingsEditor) {
    var picking by remember { mutableStateOf(false) }
    val settings = editor.settings
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        settings.expandedFolders.forEach { mailbox ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mailbox, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    editor.persist(editor.settings.copy(expandedFolders = editor.settings.expandedFolders - mailbox))
                }) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(R.string.compose_remove))
                }
            }
        }
        TextButton(onClick = { picking = true }) { Text(stringResource(R.string.settings_add)) }
    }
    if (picking) {
        MailboxChooser(
            store = editor.bound,
            onStored = { _ -> },
            onPick = { mailbox ->
                if (mailbox.isNotEmpty()) {
                    editor.persist(
                        editor.settings.copy(expandedFolders = editor.settings.expandedFolders + mailbox),
                    )
                }
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
fun FolderViewsScreen() {
    val editor = rememberSettingsEditor()
    if (!editor.ready) return
    SettingsPage {
        FolderViewsBody(editor)
    }
}

@Composable
private fun FolderViewsBody(editor: SettingsEditor) {
    var picking by remember { mutableStateOf(false) }
    var draftSort by remember { mutableStateOf(SortKey.Arrival) }
    var draftNewest by remember { mutableStateOf(true) }
    val settings = editor.settings
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        settings.folderViews.forEach { (mailbox, view) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        R.string.settings_view_line,
                        mailbox,
                        sortKeyName(view.key),
                        stringResource(
                            if (view.newestFirst) R.string.reader_newest else R.string.reader_oldest,
                        ),
                    ),
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    editor.persist(editor.settings.copy(folderViews = editor.settings.folderViews - mailbox))
                }) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(R.string.compose_remove))
                }
            }
        }
        ChoiceField(
            stringResource(R.string.settings_folder_view_sort),
            SortKey.entries,
            draftSort,
            { sortKeyName(it) },
        ) { draftSort = it }
        BoolField(stringResource(R.string.settings_folder_view_newest), draftNewest) { draftNewest = it }
        TextButton(onClick = { picking = true }) { Text(stringResource(R.string.settings_add)) }
    }
    if (picking) {
        MailboxChooser(
            store = editor.bound,
            onStored = { _ -> },
            onPick = { mailbox ->
                if (mailbox.isNotEmpty()) {
                    editor.persist(
                        editor.settings.copy(
                            folderViews = editor.settings.folderViews +
                                (mailbox to FolderView(draftSort, draftNewest)),
                        ),
                    )
                }
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
fun FolderStartsScreen() {
    val editor = rememberSettingsEditor()
    if (!editor.ready) return
    SettingsPage {
        FolderStartsBody(editor)
    }
}

@Composable
private fun FolderStartsBody(editor: SettingsEditor) {
    var draftStartMailbox by remember { mutableStateOf("") }
    var draftStartRule by remember { mutableStateOf(StartRule.Newest) }
    val settings = editor.settings
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        settings.folderStarts.forEach { (mailbox, rule) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_mailbox_rule, mailbox, startRuleName(rule)))
                    if (startRuleIsRecent(rule)) Text(stringResource(R.string.label_recent_note))
                }
                TextButton(onClick = {
                    editor.persist(withFolderStart(editor.settings, mailbox, null))
                }) { Text(stringResource(R.string.settings_default)) }
            }
        }
        LineField(
            stringResource(R.string.settings_mailbox),
            draftStartMailbox,
            ready = editor.ready,
            commitOnLeave = false,
            onDraft = { draftStartMailbox = it },
        ) { draftStartMailbox = it }
        StartRuleField(
            stringResource(R.string.settings_opens_at),
            draftStartRule,
            settings.showRecentRules,
        ) { draftStartRule = it }
        TextButton(onClick = {
            if (draftStartMailbox.isEmpty()) return@TextButton
            editor.persist(withFolderStart(editor.settings, draftStartMailbox, draftStartRule))
            draftStartMailbox = ""
        }) { Text(stringResource(R.string.settings_add)) }
    }
}

private fun sieveCheckText(caps: SieveCapabilities): String {
    if (caps.implementation.isBlank() && caps.version.isBlank()) return "OK"
    val parts = ArrayList<String>(2)
    if (caps.implementation.isNotBlank()) parts.add(caps.implementation)
    if (caps.version.isNotBlank()) parts.add(caps.version)
    return parts.joinToString(" ")
}

private fun sharePinerc(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND)
    send.type = "text/plain"
    send.putExtra(Intent.EXTRA_TEXT, text)
    send.putExtra(Intent.EXTRA_SUBJECT, "liveimap.pinerc")
    val chooser = Intent.createChooser(send, "liveimap.pinerc")
    chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (_: ActivityNotFoundException) {
        Unit
    }
}

private val hideCapabilityTokens = listOf(
    "SORT",
    "THREAD=REFERENCES",
    "THREAD=ORDEREDSUBJECT",
    "ESEARCH",
    "LIST-STATUS",
    "LIST-EXTENDED",
    "UIDPLUS",
    "IDLE",
    "UNSELECT",
    "NAMESPACE",
    "CONDSTORE",
    "QRESYNC",
    "SPECIAL-USE",
)

private class ServerDrafts {
    var probing by mutableStateOf(false)
    var serverReport by mutableStateOf<List<String>>(emptyList())
    var checkingSieve by mutableStateOf(false)
    var sieveReport by mutableStateOf("")
    var serverChecking by mutableStateOf(false)
    var serverDraft by mutableStateOf<AccountSettings?>(null)
    var passwordDraft by mutableStateOf<String?>(null)
    var smtpPasswordDraft by mutableStateOf<String?>(null)
    var serverReview by mutableStateOf<ServerSaveReview?>(null)
    var discardAsk by mutableStateOf(false)
    var leaveAfter by mutableStateOf<(() -> Unit)?>(null)

    val hasDraft: Boolean
        get() = serverDraft != null || passwordDraft != null || smtpPasswordDraft != null

    fun discard() {
        serverDraft = null
        passwordDraft = null
        smtpPasswordDraft = null
    }

    fun commitPasswords(editor: SettingsEditor) {
        passwordDraft?.let { editor.persistPassword(it) }
        smtpPasswordDraft?.let { editor.persistSmtpPassword(it) }
        passwordDraft = null
        smtpPasswordDraft = null
    }

    // Server fields edit a draft; nothing is stored or checked until Save.
    fun userServerSave(editor: SettingsEditor, next: AccountSettings) {
        val settings = editor.settings
        if (!sameServer(next, settings)) {
            serverDraft = next
            return
        }
        val kept = editor.settings
        if (next.withoutPreserved("tlsMode") == next) {
            val cleared = kept.withoutPreserved("tlsMode")
            if (cleared != kept) editor.persist(cleared)
        }
        serverDraft = null
    }

    fun savedServer(editor: SettingsEditor, chosen: AccountSettings): AccountSettings {
        val merged = editor.settings.withServerOf(chosen)
        return if (chosen.withoutPreserved("tlsMode") == chosen) merged.withoutPreserved("tlsMode") else merged
    }

    fun saveServer(editor: SettingsEditor) {
        val draft = serverDraft ?: return commitPasswords(editor)
        editor.ui.launch {
            serverChecking = true
            val result = try {
                if (draft.jmapFolders) {
                    null
                } else {
                    withContext(Dispatchers.IO) { LibetpanMailSession.checkServerSettings(draft) }
                }
            } catch (_: Exception) {
                null
            } finally {
                serverChecking = false
            }
            val review = serverSaveReview(draft, result)
            if (review.quiet) {
                editor.persist(savedServer(editor, draft))
                commitPasswords(editor)
                serverDraft = null
            } else {
                serverReview = review
            }
        }
    }
}

private class SettingsWalk(
    val editor: SettingsEditor,
    val sections: SectionOpen,
    val scroll: ScrollState,
    val onCopyContacts: () -> Unit,
    val onEditBook: () -> Unit,
    val onOpenPanel: (String, String) -> Unit,
    val onEditFavorite: (FolderFavorite) -> Unit,
    val picking: MutableState<MailboxPick?>,
    var drafts: ServerDrafts? = null,
)

@Composable
private fun renderSettingsNode(node: TreeNode, depth: Int, walk: SettingsWalk) {
    when (node) {
        is TreeNode.Leaf -> settingLeaf(node.leaf.setting, walk)
        is TreeNode.Group -> renderGroup(node, depth, walk)
    }
}

@Composable
private fun renderGroup(node: TreeNode.Group, depth: Int, walk: SettingsWalk) {
    val editor = walk.editor
    if (node.title == "Watched folders" && editor.settings.jmapFolders) return
    val id = SettingsTreeIndex.id(node.title)
    SettingsNode(
        title = stringResource(SettingsTreeGenerated.titleOf(id)),
        depth = depth,
        summary = groupSummary(node.title, editor.settings),
        expanded = id in walk.sections.open,
        onToggle = { walk.sections.toggle(id) },
    ) {
        if (node.title == "Account") {
            accountFrame(node, depth + 1, walk)
        } else {
            if (node.title == "Mailboxes") SpecialFolderLeaveWarning(editor)
            if (node.title == "Toolbars") ToolbarStoreSync(editor)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groupIntro(node.title, editor)
                for (child in node.children) renderSettingsNode(child, depth + 1, walk)
                groupFooter(node.title, depth + 1, walk)
            }
        }
    }
}

@Composable
private fun groupIntro(title: String, editor: SettingsEditor) {
    if (title != "Server") return
    if (editor.settings.jmapFolders) {
        Text(stringResource(R.string.settings_jmap_server_note))
    } else {
        Text(stringResource(R.string.settings_plaintext_ports))
    }
}

@Composable
private fun groupFooter(title: String, depth: Int, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    val drafts = walk.drafts
    when (title) {
        "Server" -> {
            if (drafts != null && drafts.serverChecking) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("server-checking"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.settings_checking_server), Modifier.padding(start = 12.dp))
                }
            }
            if (drafts != null && drafts.hasDraft) {
                androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    TextButton(onClick = { drafts.discard() }) {
                        Text(stringResource(R.string.settings_discard))
                    }
                }
            }
        }
        "Sending" -> {
            sendingProblem(settings)?.let { res ->
                Text(
                    stringResource(res),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("no-way-to-send"),
                )
            }
        }
        "Logging" -> LoggingExtras(editor)
        "Layout" -> {
            SettingsNode(
                title = stringResource(R.string.settings_panels),
                depth = depth,
                summary = stringResource(R.string.settings_panels),
                expanded = "panels" in walk.sections.open,
                onToggle = { walk.sections.toggle("panels") },
            ) {
                val accountId = editor.editingId.orEmpty()
                OpenRow(stringResource(R.string.settings_panel_index)) { walk.onOpenPanel("index", accountId) }
                OpenRow(stringResource(R.string.settings_panel_selection)) { walk.onOpenPanel("selection", accountId) }
                OpenRow(stringResource(R.string.settings_panel_folders)) { walk.onOpenPanel("folders", accountId) }
                OpenRow(stringResource(R.string.settings_panel_reader)) { walk.onOpenPanel("reader", accountId) }
                OpenRow(stringResource(R.string.settings_panel_compose)) { walk.onOpenPanel("compose", accountId) }
            }
            Button(
                onClick = {
                    val target = editor.bound
                    settingsIo.launch {
                        editor.saveKeepingToolbars(target)
                        LayoutApply.request()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_change_layout))
            }
        }
        "Address book" -> {
            OpenRow(stringResource(R.string.settings_copy_contacts), walk.onCopyContacts)
            OpenRow(stringResource(R.string.settings_edit_book), walk.onEditBook)
        }
    }
}

@Composable
private fun LoggingExtras(editor: SettingsEditor) {
    var reportLines by remember { mutableStateOf<List<String>?>(null) }
    var confirmShareLog by remember { mutableStateOf(false) }
    val contextState = rememberUpdatedState(LocalContext.current)
    Text(stringResource(R.string.settings_log_note))
    TextButton(onClick = { confirmShareLog = true }) { Text(stringResource(R.string.settings_share_log)) }
    TextButton(onClick = {
        val log = TrafficLog.install(File(editor.appContext.cacheDir, "imap-traffic.log"))
        val device = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
        reportLines = log.debugReport(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
            androidVersion = Build.VERSION.RELEASE,
            device = device,
            settings = editor.settings,
        ).lines()
    }) { Text(stringResource(R.string.settings_debug_report)) }
    if (confirmShareLog) {
        AlertDialog(
            onDismissRequest = { confirmShareLog = false },
            title = { Text(stringResource(R.string.settings_share_log)) },
            text = { Text(stringResource(R.string.traffic_share_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmShareLog = false
                    val log = TrafficLog.install(File(editor.appContext.cacheDir, "imap-traffic.log"))
                    log.shareFile(contextState.value)
                }) { Text(stringResource(R.string.traffic_share_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmShareLog = false }) { Text(stringResource(R.string.debug_dismiss)) }
            },
        )
    }
    val shownReport = reportLines
    if (shownReport != null) {
        DebugReportReview(
            lines = shownReport,
            onLines = { reportLines = it },
            onDismiss = { reportLines = null },
        )
    }
}

@Composable
private fun ToolbarStoreSync(editor: SettingsEditor) {
    LaunchedEffect(editor.editingId) {
        val target = editor.bound
        var previous = target.load()
        target.theme().collect {
            val loaded = target.load()
            val rowsChanged = previous.toolbarRows != loaded.toolbarRows
            if (toolbarsDiffer(previous, loaded)) {
                target.onAccount(publish = {
                    editor.settings = editor.settings.keepingStoredToolbars(loaded)
                }) {
                }
            }
            if (rowsChanged) ToolbarRevision.bump()
            previous = loaded
        }
    }
}

@Composable
private fun ToolbarLeaf(screen: ToolbarScreen, walk: SettingsWalk) {
    key(ToolbarRevision.value) {
        ToolbarEditorScreen(screen, walk.editor.editingId.orEmpty(), walk.scroll)
    }
}

@Composable
private fun SpecialFolderLeaveWarning(editor: SettingsEditor) {
    val leaveContext = LocalContext.current
    val sentLabel = stringResource(R.string.settings_sent_mailbox)
    val postponedLabel = stringResource(R.string.settings_postponed_mailbox)
    val latest = rememberUpdatedState(editor.settings)
    DisposableEffect(Unit) {
        onDispose {
            val unset = unsetSpecialFolders(latest.value).map {
                if (it == SpecialFolder.Sent) sentLabel else postponedLabel
            }
            if (unset.isNotEmpty()) {
                android.widget.Toast.makeText(
                    leaveContext,
                    leaveContext.getString(R.string.settings_special_unset_warning, unset.joinToString(", ")),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
}

@Composable
private fun PasswordDraftField(editor: SettingsEditor, drafts: ServerDrafts) {
    LineField(
        stringResource(R.string.settings_password),
        drafts.passwordDraft ?: editor.password,
        KeyboardType.Password,
        password = true,
        ready = editor.ready,
        onDraft = { typed -> drafts.passwordDraft = typed.takeIf { it != editor.password } },
    ) { drafts.passwordDraft = it.takeIf { typed -> typed != editor.password } }
}

@Composable
private fun SmtpPasswordDraftField(editor: SettingsEditor, drafts: ServerDrafts, ready: Boolean, enabled: Boolean) {
    Text(stringResource(R.string.settings_smtp_password_hint))
    LineField(
        stringResource(R.string.settings_smtp_password),
        drafts.smtpPasswordDraft ?: editor.smtpPassword,
        KeyboardType.Password,
        password = true,
        ready = ready,
        enabled = enabled,
        onDraft = { typed -> drafts.smtpPasswordDraft = typed.takeIf { it != editor.smtpPassword } },
    ) { drafts.smtpPasswordDraft = it.takeIf { typed -> typed != editor.smtpPassword } }
}

@Composable
private fun ShowUnreadLeaf(editor: SettingsEditor) {
    var warnUnread by remember { mutableStateOf(false) }
    BackHandler(enabled = warnUnread) { warnUnread = false }
    val settings = editor.settings
    BoolField(stringResource(R.string.settings_show_unread), settings.showUnreadCounts || warnUnread) { enabled ->
        if (enabled) {
            warnUnread = true
        } else {
            warnUnread = false
            editor.persist(editor.settings.copy(showUnreadCounts = false))
        }
    }
    if (warnUnread) {
        AlertDialog(
            onDismissRequest = { warnUnread = false },
            text = { Text(stringResource(R.string.settings_unread_warn)) },
            confirmButton = {
                TextButton(onClick = {
                    warnUnread = false
                    editor.persist(editor.settings.copy(showUnreadCounts = true))
                }) { Text(stringResource(R.string.settings_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { warnUnread = false }) { Text(stringResource(R.string.settings_dismiss)) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DsnChoices(editor: SettingsEditor) {
    val settings = editor.settings
    Text(stringResource(R.string.settings_dsn))
    FlowRow {
        for (mode in org.dlang.liveimap.ui.compose.DsnMode.entries) {
            FilterChip(
                selected = settings.dsnMode == mode,
                onClick = { editor.persist(editor.settings.copy(dsnMode = mode)) },
                label = { Text(dsnLabel(mode)) },
            )
        }
    }
}

private data class CapFlags(
    val listExtended: Boolean,
    val uidPlus: Boolean,
    val canMove: Boolean,
    val compress: Boolean,
)

@Composable
private fun rememberCapFlags(editor: SettingsEditor): CapFlags {
    val settings = editor.settings
    val sessionId = editor.accounts.firstOrNull { it.chosen }?.id
    val session = if (sessionId.isNullOrEmpty()) null else existingMailSession(sessionId)
    val caps = session?.featureCaps
    fun allows(advertised: Boolean) = serverCapAllows(settings.jmapFolders, session != null, advertised)
    return CapFlags(
        listExtended = allows(caps?.listExtended == true),
        uidPlus = allows(caps?.uidPlus == true),
        canMove = allows(caps?.move == true),
        compress = allows(caps?.compressDeflate == true),
    )
}

@Composable
private fun CountLeaf(key: String, count: Int) {
    Text(stringResource(SettingsTreeGenerated.regTitle(key)))
    Text(count.toString())
}

@Composable
private fun SkipSetField(key: String, value: Set<String>, ready: Boolean, onValue: (Set<String>) -> Unit) {
    LineField(
        stringResource(SettingsTreeGenerated.regTitle(key)),
        value.joinToString(", "),
        ready = ready,
    ) { text ->
        onValue(text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet())
    }
}

@Composable
private fun NumberLeaf(
    label: String,
    value: Int,
    ready: Boolean,
    ok: (Int) -> Boolean,
    onValue: (Int) -> Unit,
) {
    LineField(
        label,
        value.toString(),
        keyboardType = KeyboardType.Number,
        ready = ready,
        interpret = { draft, _ ->
            val text = draft.trim()
            val number = text.toIntOrNull()
            if (number != null && ok(number)) number.toString() else null
        },
        onCommit = { text ->
            val number = text.toIntOrNull()
            if (number != null && ok(number)) onValue(number)
        },
    )
}

@Composable
private fun settingLeaf(key: String, walk: SettingsWalk) {
    val editor = walk.editor
    val settings = editor.settings
    val drafts = walk.drafts
    val server = drafts?.serverDraft ?: settings
    val leftToRight = LocalLayoutDirection.current == LayoutDirection.Ltr
    val panesOn = paneControlsEnabled(settings.multiPane)
    when (key) {
        "friendlyName" -> LineField(stringResource(R.string.settings_friendly_name), settings.friendlyName, ready = editor.ready) {
            editor.persist(editor.settings.copy(friendlyName = it))
        }
        "email" -> LineField(stringResource(R.string.settings_email), settings.email, KeyboardType.Email, ready = editor.ready) {
            editor.persist(editor.settings.copy(email = it))
        }
        "displayName" -> LineField(stringResource(R.string.settings_display_name), settings.displayName, ready = editor.ready) {
            editor.persist(editor.settings.copy(displayName = it))
        }
        "altAddresses" -> LineField(
            stringResource(R.string.settings_alt_addresses),
            settings.altAddresses.joinToString(", "),
            ready = editor.ready,
        ) { text ->
            editor.persist(
                editor.settings.copy(altAddresses = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }),
            )
        }
        "jmapFolders" -> ChoiceField(
            stringResource(R.string.settings_mail_access),
            listOf(false, true),
            settings.jmapFolders,
            { stringResource(if (it) R.string.settings_access_jmap else R.string.settings_access_imap) },
        ) { jmap -> editor.persist(withMailAccess(editor.settings, jmap)) }
        "imapHost" -> {
            if (settings.jmapFolders) {
                LineField(stringResource(R.string.settings_jmap_host), server.imapHost, ready = editor.ready) {
                    if (drafts != null) drafts.userServerSave(editor, server.copy(imapHost = it))
                }
                if (server.imapHost.trim().startsWith("http://", ignoreCase = true)) {
                    Text(stringResource(R.string.settings_jmap_plain_http_warning), color = MaterialTheme.colorScheme.error)
                }
            } else {
                LineField(stringResource(R.string.settings_imap_host), server.imapHost, ready = editor.ready) {
                    if (drafts != null) drafts.userServerSave(editor, server.copy(imapHost = it))
                }
            }
        }
        "imapPort" -> if (!settings.jmapFolders) {
            PortField(stringResource(R.string.settings_imap_port), server.imapPort, ready = editor.ready) {
                if (drafts != null) drafts.userServerSave(editor, server.copy(imapPort = it))
            }
        }
        "username" -> {
            LineField(stringResource(R.string.settings_username), settings.username, ready = editor.ready) { draft ->
                editor.persist(
                    editor.settings.copy(
                        username = draft,
                        email = emailDefaultedFromUsername(draft, editor.settings.email),
                    ),
                )
            }
            if (drafts != null) PasswordDraftField(editor, drafts)
        }
        "tlsMode" -> if (!settings.jmapFolders) {
            ChoiceField(
                stringResource(R.string.settings_tls),
                TlsMode.entries,
                server.tlsMode,
                { tlsName(it) },
            ) { mode ->
                if (drafts != null) drafts.userServerSave(editor, server.withTlsMode(mode).withoutPreserved("tlsMode"))
            }
        }
        "allowPlaintextAuth" -> if (!settings.jmapFolders) {
            BoolField(
                stringResource(R.string.settings_allow_plaintext),
                server.allowPlaintextAuth,
                note = stringResource(R.string.settings_allow_plaintext_note),
            ) { enabled ->
                if (drafts != null) drafts.userServerSave(editor, server.copy(allowPlaintextAuth = enabled))
            }
        }
        "acceptInvalidCert" -> if (!settings.jmapFolders) {
            BoolField(
                stringResource(R.string.settings_accept_invalid_cert),
                server.acceptInvalidCert,
                note = stringResource(R.string.settings_accept_invalid_cert_note),
            ) { enabled ->
                if (drafts != null) drafts.userServerSave(editor, server.copy(acceptInvalidCert = enabled))
            }
        }
        "allowOldTls" -> if (!settings.jmapFolders) {
            BoolField(
                stringResource(R.string.settings_allow_old_tls),
                server.allowOldTls,
                note = stringResource(R.string.settings_allow_old_tls_note),
            ) { enabled ->
                if (drafts != null) drafts.userServerSave(editor, server.copy(allowOldTls = enabled))
            }
        }
        "authMechanism" -> if (!settings.jmapFolders) {
            LineField(stringResource(R.string.settings_auth_mechanism), settings.authMechanism, ready = editor.ready) {
                editor.persist(editor.settings.copy(authMechanism = it.trim().uppercase()))
            }
        }
        "clientCert" -> if (!settings.jmapFolders) ClientCertField(editor)
        "clientCertAlias" -> if (!settings.jmapFolders) {
            Text(stringResource(SettingsTreeGenerated.regTitle(key)))
            Text(settings.clientCertAlias.ifBlank { stringResource(R.string.settings_client_cert_none) })
        }
        "certPin" -> if (!settings.jmapFolders) {
            if (settings.certPin.isBlank()) {
                Text(stringResource(R.string.settings_cert_pin_none))
            } else {
                Text(settings.certPin)
                TextButton(onClick = { editor.persist(editor.settings.copy(certPin = "")) }) {
                    Text(stringResource(R.string.settings_cert_pin_clear))
                }
            }
        }
        "jmapCertPin" -> {
            if (settings.jmapCertPin.isBlank()) {
                Text(stringResource(R.string.settings_jmap_cert_pin_none))
            } else {
                Text(settings.jmapCertPin)
                TextButton(onClick = { editor.persist(editor.settings.copy(jmapCertPin = "")) }) {
                    Text(stringResource(R.string.settings_jmap_cert_pin_clear))
                }
            }
        }
        "pipelineCommands" -> BoolField(stringResource(R.string.settings_pipeline), settings.pipelineCommands) {
            editor.persist(editor.settings.copy(pipelineCommands = it))
        }
        "emailSubmission" -> BoolField(
            stringResource(R.string.settings_send_over_jmap),
            settings.emailSubmission,
            note = stringResource(R.string.settings_email_submission_note),
        ) { enabled ->
            editor.persist(editor.settings.copy(emailSubmission = enabled))
        }
        "smtpHost" -> if (!settings.emailSubmission) {
            LineField(stringResource(R.string.settings_smtp_host), settings.smtpHost, ready = editor.ready) {
                editor.persist(editor.settings.copy(smtpHost = it))
            }
        }
        "smtpPort" -> if (!settings.emailSubmission) {
            PortField(stringResource(R.string.settings_smtp_port), settings.smtpPort, ready = editor.ready) {
                editor.persist(editor.settings.copy(smtpPort = it))
            }
        }
        "smtpTlsMode" -> if (!settings.emailSubmission) {
            ChoiceField(
                stringResource(SettingsTreeGenerated.regTitle(key)),
                TlsMode.entries,
                settings.smtpTlsMode,
                { tlsName(it) },
            ) { mode ->
                editor.persist(editor.settings.copy(smtpTlsMode = mode))
            }
        }
        "smtpUsername" -> if (!settings.emailSubmission) {
            val authOn = settings.smtpNoAuth == SmtpNoAuth.On
            Text(stringResource(R.string.settings_smtp_username_hint))
            LineField(
                stringResource(R.string.settings_smtp_username),
                settings.smtpUsername,
                ready = editor.ready && !authOn,
                enabled = !authOn,
            ) {
                editor.persist(editor.settings.copy(smtpUsername = it))
            }
        }
        "smtpSameAsImap" -> if (!settings.emailSubmission) {
            val authOn = settings.smtpNoAuth == SmtpNoAuth.On
            BoolField(
                stringResource(R.string.settings_smtp_same_as_imap),
                settings.smtpSameAsImap,
                enabled = !authOn,
            ) {
                editor.persist(editor.settings.copy(smtpSameAsImap = it))
            }
            if (drafts != null) SmtpPasswordDraftField(editor, drafts, editor.ready && !authOn, !authOn)
        }
        "smtpNoAuth" -> if (!settings.emailSubmission) {
            ChoiceField(
                stringResource(SettingsTreeGenerated.regTitle(key)),
                SmtpNoAuth.entries,
                settings.smtpNoAuth,
                { it.name },
            ) { choice ->
                editor.persist(editor.settings.copy(smtpNoAuth = choice))
            }
        }
        "sieveHost" -> if (!settings.jmapFolders) {
            LineField(stringResource(R.string.settings_sieve_host), settings.sieveHost, ready = editor.ready) {
                editor.persist(editor.settings.copy(sieveHost = it))
            }
        }
        "sievePort" -> if (!settings.jmapFolders) {
            PortField(stringResource(R.string.settings_sieve_port), settings.sievePort, ready = editor.ready) {
                editor.persist(editor.settings.copy(sievePort = it))
            }
        }
        "sieveTlsMode" -> if (!settings.jmapFolders) {
            ChoiceField(
                stringResource(R.string.settings_sieve_tls),
                TlsMode.entries,
                settings.sieveTlsMode,
                { tlsName(it) },
            ) { mode ->
                editor.persist(editor.settings.copy(sieveTlsMode = mode).withoutPreserved("sieveTlsMode"))
            }
        }
        "sieveCertPin" -> if (!settings.jmapFolders) {
            if (settings.sieveCertPin.isBlank()) {
                Text(stringResource(R.string.settings_sieve_cert_pin_none))
            } else {
                Text(settings.sieveCertPin)
                TextButton(onClick = { editor.persist(editor.settings.copy(sieveCertPin = "")) }) {
                    Text(stringResource(R.string.settings_sieve_cert_pin_clear))
                }
            }
            if (drafts != null) SieveCheckButton(editor, drafts)
        }
        "watchedFolders" -> {
            Text(stringResource(R.string.settings_watched_folder_note))
            LineField(
                label = stringResource(R.string.settings_extra_folders),
                value = watchedFolderText(settings.watchedFolders),
                singleLine = false,
                ready = editor.ready,
                interpret = { draft, stored ->
                    val next = watchedFolderText(watchedFoldersFromText(draft))
                    if (next == stored) null else next
                },
                onCommit = { text ->
                    editor.persist(editor.settings.copy(watchedFolders = watchedFoldersFromText(text)))
                },
            )
        }
        "extraIdleBudget" -> LineField(
            label = stringResource(R.string.settings_extra_idle_budget),
            value = settings.extraIdleBudget.toString(),
            ready = editor.ready,
            interpret = { draft, _ -> extraIdleBudgetFromText(draft)?.toString() },
            onCommit = { text ->
                val budget = extraIdleBudgetFromText(text) ?: return@LineField
                editor.persist(editor.settings.copy(extraIdleBudget = budget))
            },
        )
        "maxConnections" -> LineField(
            label = stringResource(
                R.string.settings_max_connections,
                org.dlang.liveimap.session.MinConnections,
                org.dlang.liveimap.session.MaxConnectionsCeiling,
            ),
            value = settings.maxConnections.toString(),
            ready = editor.ready,
            interpret = { draft, _ -> org.dlang.liveimap.session.maxConnectionsFromText(draft)?.toString() },
            onCommit = { text ->
                val limit = org.dlang.liveimap.session.maxConnectionsFromText(text) ?: return@LineField
                editor.persist(editor.settings.copy(maxConnections = limit))
            },
        )
        "sendingRules" -> SendingRulesRows(editor)
        "signature" -> LineField(stringResource(R.string.settings_signature), settings.signature, singleLine = false, ready = editor.ready) {
            editor.persist(editor.settings.copy(signature = it))
        }
        "sigDashes" -> BoolField(stringResource(R.string.settings_sig_dashes), settings.sigDashes) {
            editor.persist(editor.settings.copy(sigDashes = it))
        }
        "sigAtBottom" -> BoolField(stringResource(R.string.settings_sig_bottom), settings.sigAtBottom) {
            editor.persist(editor.settings.copy(sigAtBottom = it))
        }
        "quotePrefix" -> LineField(
            stringResource(R.string.settings_quote_prefix),
            settings.quotePrefix,
            ready = editor.ready,
            interpret = { draft, _ -> draft },
        ) {
            editor.persist(editor.settings.copy(quotePrefix = it))
        }
        "replyLeadIn" -> LineField(stringResource(R.string.settings_lead_in), settings.replyLeadIn, ready = editor.ready) {
            editor.persist(editor.settings.copy(replyLeadIn = it.ifBlank { org.dlang.liveimap.ui.compose.DefaultLeadIn }))
        }
        "dsnMode" -> DsnChoices(editor)
        "composerHeaders" -> LineField(stringResource(R.string.settings_composer_headers), settings.composerHeaders, ready = editor.ready) {
            editor.persist(editor.settings.copy(composerHeaders = it))
        }
        "inboundRules" -> CountLeaf(key, settings.inboundRules.size)
        "sentMailbox" -> MailboxLine(stringResource(R.string.settings_sent_mailbox), settings.sentMailbox, { walk.picking.value = MailboxPick.Sent }) {
            editor.persist(editor.settings.copy(sentMailbox = it))
        }
        "postponedMailbox" -> MailboxLine(stringResource(R.string.settings_postponed_mailbox), settings.postponedMailbox, { walk.picking.value = MailboxPick.Postponed }) {
            editor.persist(editor.settings.copy(postponedMailbox = it))
        }
        "addressBookMailbox" -> MailboxLine(stringResource(R.string.settings_address_book_mailbox), settings.addressBookMailbox, { walk.picking.value = MailboxPick.AddressBook }) {
            editor.persist(editor.settings.copy(addressBookMailbox = it))
        }
        "spamMailbox" -> MailboxLine(stringResource(R.string.settings_spam_mailbox), settings.spamMailbox, { walk.picking.value = MailboxPick.Spam }) {
            editor.persist(editor.settings.copy(spamMailbox = it))
        }
        "trashMailbox" -> MailboxLine(stringResource(R.string.settings_trash_mailbox), settings.trashMailbox, { walk.picking.value = MailboxPick.Trash }) {
            editor.persist(editor.settings.copy(trashMailbox = it))
        }
        "showUnreadCounts" -> ShowUnreadLeaf(editor)
        "expandedFolders" -> {
            Text(stringResource(R.string.settings_expanded_folders))
            ExpandedFoldersBody(editor)
        }
        "defaultView" -> {
            ChoiceField(
                stringResource(R.string.settings_default_view),
                SortKey.entries,
                settings.defaultView.key,
                { sortKeyName(it) },
            ) { sort ->
                editor.persist(editor.settings.copy(defaultView = editor.settings.defaultView.copy(key = sort)))
            }
            BoolField(stringResource(R.string.reader_newest), settings.defaultView.newestFirst) { newest ->
                editor.persist(editor.settings.copy(defaultView = editor.settings.defaultView.copy(newestFirst = newest)))
            }
        }
        "folderViews" -> {
            Text(stringResource(R.string.settings_folder_views))
            FolderViewsBody(editor)
        }
        "threadIndexStyle" -> ChoiceField(
            stringResource(R.string.settings_thread_index),
            ThreadIndexStyle.entries,
            settings.threadIndexStyle,
            { threadIndexStyleName(it) },
        ) { style ->
            editor.persist(editor.settings.copy(threadIndexStyle = style))
        }
        "inboxStart" -> StartRuleField(stringResource(R.string.settings_inbox_opens), settings.inboxStart, settings.showRecentRules) { rule ->
            editor.persist(editor.settings.copy(inboxStart = rule))
        }
        "folderStart" -> StartRuleField(stringResource(R.string.settings_other_opens), settings.folderStart, settings.showRecentRules) { rule ->
            editor.persist(editor.settings.copy(folderStart = rule))
        }
        "folderStarts" -> {
            Text(stringResource(R.string.settings_start_per_folder))
            FolderStartsBody(editor)
        }
        "startAfterChange" -> ChoiceField(
            stringResource(R.string.settings_after_change),
            StartAfterChange.entries,
            settings.startAfterChange,
            { startAfterChangeName(it) },
        ) { value ->
            editor.persist(editor.settings.copy(startAfterChange = value))
        }
        "showRecentRules" -> BoolField(stringResource(R.string.settings_show_recent), settings.showRecentRules) { enabled ->
            editor.persist(editor.settings.copy(showRecentRules = enabled))
        }
        "openAtInIndexMenu" -> BoolField(stringResource(R.string.settings_open_at_menu), settings.openAtInIndexMenu) { enabled ->
            editor.persist(editor.settings.copy(openAtInIndexMenu = enabled))
        }
        "pinercStartDefault" -> ChoiceField(
            stringResource(R.string.settings_pinerc_missing),
            PinercStartDefault.entries,
            settings.pinercStartDefault,
            { pinercStartDefaultName(it) },
        ) { value ->
            editor.persist(editor.settings.copy(pinercStartDefault = value))
        }
        "askSort" -> {
            Text(stringResource(R.string.settings_ask_note))
            AskLimitField(stringResource(R.string.settings_ask_sort), settings.askSort, editor.ready) { limit ->
                editor.persist(editor.settings.copy(askSort = limit))
            }
        }
        "askThread" -> AskLimitField(stringResource(R.string.settings_ask_thread), settings.askThread, editor.ready) { limit ->
            editor.persist(editor.settings.copy(askThread = limit))
        }
        "askSearch" -> AskLimitField(stringResource(R.string.settings_ask_search), settings.askSearch, editor.ready) { limit ->
            editor.persist(editor.settings.copy(askSearch = limit))
        }
        "askFallback" -> AskLimitField(stringResource(R.string.settings_ask_fallback), settings.askFallback, editor.ready) { limit ->
            editor.persist(editor.settings.copy(askFallback = limit))
        }
        "sortAskSkip" -> SkipSetField(key, settings.sortAskSkip, editor.ready) {
            editor.persist(editor.settings.copy(sortAskSkip = it))
        }
        "threadAskSkip" -> SkipSetField(key, settings.threadAskSkip, editor.ready) {
            editor.persist(editor.settings.copy(threadAskSkip = it))
        }
        "searchAskSkip" -> SkipSetField(key, settings.searchAskSkip, editor.ready) {
            editor.persist(editor.settings.copy(searchAskSkip = it))
        }
        "fallbackAskSkip" -> SkipSetField(key, settings.fallbackAskSkip, editor.ready) {
            editor.persist(editor.settings.copy(fallbackAskSkip = it))
        }
        "savedSearches" -> CountLeaf(key, settings.savedSearches.size)
        "savedAdvanced" -> CountLeaf(key, settings.savedAdvanced.size)
        "markSeenOnOpen" -> BoolField(stringResource(R.string.settings_mark_seen), settings.markSeenOnOpen) {
            editor.persist(editor.settings.copy(markSeenOnOpen = it))
        }
        "bodyView" -> ChoiceField(
            stringResource(R.string.settings_message_view),
            BodyView.entries,
            settings.bodyView,
            { bodyViewName(it) },
        ) { view ->
            editor.persist(editor.settings.copy(bodyView = view))
        }
        "preferHtml" -> BoolField(stringResource(SettingsTreeGenerated.regTitle(key)), settings.preferHtml) { enabled ->
            val next = if (enabled) {
                BodyView.PlainOrHtml
            } else if (settings.bodyView == BodyView.PlainOrHtml) {
                BodyView.PlainOrError
            } else {
                settings.bodyView
            }
            editor.persist(editor.settings.copy(bodyView = next))
        }
        "readerTextLayout" -> ChoiceField(
            stringResource(R.string.settings_reader_display),
            org.dlang.liveimap.ui.reader.ReaderDisplay.entries,
            org.dlang.liveimap.ui.reader.readerDisplayOf(settings.bodyView, settings.readerTextLayout),
            { org.dlang.liveimap.ui.reader.readerDisplayName(it) },
        ) { display ->
            editor.persist(
                editor.settings.copy(
                    bodyView = org.dlang.liveimap.ui.reader.bodyViewOf(display, editor.settings.bodyView),
                    readerTextLayout = org.dlang.liveimap.ui.reader.textLayoutOf(display, editor.settings.readerTextLayout),
                ),
            )
        }
        "readerTextZoom" -> ChoiceField(
            stringResource(R.string.settings_text_size),
            ReaderTextZooms,
            settings.readerTextZoom.takeIf { it in ReaderTextZooms } ?: 100,
            { "$it%" },
        ) { editor.persist(editor.settings.copy(readerTextZoom = it)) }
        "viewerHeaders" -> LineField(stringResource(R.string.settings_viewer_headers), settings.viewerHeaders, ready = editor.ready) {
            editor.persist(editor.settings.copy(viewerHeaders = it))
        }
        "nonAsciiDisplay" -> ChoiceField(
            stringResource(R.string.settings_non_ascii),
            NonAsciiDisplay.entries,
            settings.nonAsciiDisplay,
            { stringResource(when (it) {
                NonAsciiDisplay.Normal -> R.string.settings_non_ascii_normal
                NonAsciiDisplay.HighlightSuspicious -> R.string.settings_non_ascii_suspicious
                NonAsciiDisplay.HighlightAll -> R.string.settings_non_ascii_all
                NonAsciiDisplay.Warn -> R.string.settings_non_ascii_warn
                NonAsciiDisplay.Escape -> R.string.settings_non_ascii_escape
            }) },
        ) {
            editor.persist(editor.settings.copy(nonAsciiDisplay = it))
        }
        "showDeleted" -> BoolField(stringResource(R.string.settings_show_deleted), settings.showDeleted) {
            editor.persist(editor.settings.copy(showDeleted = it))
        }
        "autoExpunge" -> BoolField(
            stringResource(R.string.settings_auto_expunge),
            settings.autoExpunge,
            note = stringResource(R.string.settings_auto_expunge_note),
        ) {
            editor.persist(editor.settings.copy(autoExpunge = it))
        }
        "askBeforeExpunge" -> BoolField(stringResource(R.string.settings_ask_expunge), settings.askBeforeExpunge) {
            editor.persist(editor.settings.copy(askBeforeExpunge = it))
        }
        "deletePolicy" -> {
            val gate = rememberCapFlags(editor)
            val trashSet = settings.trashMailbox.isNotEmpty()
            ReasonChoice(
                stringResource(R.string.settings_delete_button),
                listOf(
                    ReasonOption(DeletePolicy.MarkDeleted, true, null),
                    ReasonOption(
                        DeletePolicy.MoveToTrash,
                        trashSet || gate.listExtended,
                        when {
                            trashSet -> null
                            gate.listExtended -> stringResource(R.string.settings_trash_needed)
                            else -> stringResource(R.string.settings_reason_no_trash)
                        },
                    ),
                    ReasonOption(
                        DeletePolicy.DeletePermanently,
                        gate.uidPlus,
                        if (gate.uidPlus) null else stringResource(R.string.settings_reason_uidplus),
                    ),
                ),
                settings.deletePolicy,
                { deletePolicyName(it) },
            ) { policy ->
                editor.persist(editor.settings.copy(deletePolicy = policy).withoutPreserved("deletePolicy"))
            }
        }
        "moveMethod" -> {
            val gate = rememberCapFlags(editor)
            ReasonChoice(
                stringResource(R.string.settings_move_method),
                listOf(
                    ReasonOption(MoveMethod.CopyThenMarkDeleted, true, null),
                    ReasonOption(
                        MoveMethod.ImapMove,
                        gate.canMove,
                        if (gate.canMove) null else stringResource(R.string.settings_reason_move),
                    ),
                ),
                settings.moveMethod,
                { moveMethodName(it) },
            ) { method ->
                editor.persist(editor.settings.copy(moveMethod = method).withoutPreserved("moveMethod"))
            }
        }
        "moveAllIncludesDeleted" -> BoolField(
            stringResource(R.string.settings_move_all_deleted),
            settings.moveAllIncludesDeleted,
            note = stringResource(R.string.settings_move_all_deleted_note),
        ) {
            editor.persist(editor.settings.copy(moveAllIncludesDeleted = it))
        }
        "compression" -> {
            val gate = rememberCapFlags(editor)
            ReasonChoice(
                stringResource(R.string.settings_compression),
                listOf(
                    ReasonOption(Compression.On, gate.compress, if (gate.compress) null else stringResource(R.string.settings_reason_compress)),
                    ReasonOption(Compression.Auto, gate.compress, if (gate.compress) null else stringResource(R.string.settings_reason_compress)),
                    ReasonOption(Compression.Off, true, null),
                ),
                settings.compression,
                { compressionName(it) },
            ) { mode ->
                editor.persist(editor.settings.copy(compression = mode))
            }
        }
        "swipeTrailing" -> SwipeEditor(
            stringResource(if (leftToRight) R.string.settings_swipe_left else R.string.settings_swipe_right),
            settings.swipeTrailing,
            { walk.picking.value = MailboxPick.TrailingMove },
            SwipeAction.entries.filter { it != SwipeAction.Next && it != SwipeAction.Previous },
        ) { editor.persist(editor.settings.copy(swipeTrailing = it)) }
        "swipeLeading" -> SwipeEditor(
            stringResource(if (leftToRight) R.string.settings_swipe_right else R.string.settings_swipe_left),
            settings.swipeLeading,
            { walk.picking.value = MailboxPick.LeadingMove },
            SwipeAction.entries.filter { it != SwipeAction.Next && it != SwipeAction.Previous },
        ) { editor.persist(editor.settings.copy(swipeLeading = it)) }
        "readerSwipeTrailing" -> SwipeEditor(
            stringResource(if (leftToRight) R.string.settings_message_swipe_left else R.string.settings_message_swipe_right),
            settings.readerSwipeTrailing,
            { walk.picking.value = MailboxPick.ReaderTrailingMove },
            SwipeAction.entries,
        ) { editor.persist(editor.settings.copy(readerSwipeTrailing = it)) }
        "readerSwipeLeading" -> SwipeEditor(
            stringResource(if (leftToRight) R.string.settings_message_swipe_right else R.string.settings_message_swipe_left),
            settings.readerSwipeLeading,
            { walk.picking.value = MailboxPick.ReaderLeadingMove },
            SwipeAction.entries,
        ) { editor.persist(editor.settings.copy(readerSwipeLeading = it)) }
        "bounceFcc" -> BoolField(stringResource(R.string.settings_bounce_fcc), settings.bounceFcc) {
            editor.persist(editor.settings.copy(bounceFcc = it))
        }
        "composerWrapColumn" -> NumberLeaf(
            stringResource(R.string.settings_wrap_column),
            settings.composerWrapColumn,
            editor.ready,
            { it in 0..998 },
        ) { number ->
            editor.persist(editor.settings.copy(composerWrapColumn = number))
        }
        "quellFlowed" -> BoolField(
            stringResource(R.string.settings_flowed_text),
            !settings.quellFlowed,
            note = stringResource(R.string.settings_flowed_text_note),
        ) { enabled ->
            editor.persist(editor.settings.copy(quellFlowed = !enabled))
        }
        "replyAboveQuote" -> BoolField(stringResource(R.string.settings_reply_above), settings.replyAboveQuote) {
            editor.persist(editor.settings.copy(replyAboveQuote = it))
        }
        "includeForwardAttachments" -> BoolField(stringResource(R.string.settings_include_attachments), settings.includeForwardAttachments) {
            editor.persist(editor.settings.copy(includeForwardAttachments = it))
        }
        "forwardAsAttachment" -> BoolField(stringResource(R.string.settings_forward_attachment), settings.forwardAsAttachment) {
            editor.persist(editor.settings.copy(forwardAsAttachment = it))
        }
        "savedMailbox" -> MailboxLine(stringResource(R.string.settings_saved_mailbox), settings.savedMailbox, { walk.picking.value = MailboxPick.Saved }) {
            editor.persist(editor.settings.copy(savedMailbox = it))
        }
        "saveNameRule" -> ChoiceField(
            stringResource(R.string.settings_save_rule),
            SaveNameRule.entries,
            settings.saveNameRule,
            { saveNameRuleName(it) },
        ) { rule ->
            editor.persist(editor.settings.copy(saveNameRule = rule))
        }
        "lastSaveMailbox" -> MailboxLine(stringResource(SettingsTreeGenerated.regTitle(key)), settings.lastSaveMailbox, { walk.picking.value = MailboxPick.LastSave }) {
            editor.persist(editor.settings.copy(lastSaveMailbox = it))
        }
        "completionSources" -> AddressCompletionRows(editor)
        "addressBookHistory" -> NumberLeaf(
            stringResource(R.string.settings_history_label),
            settings.addressBookHistory,
            editor.ready,
            { true },
        ) { number ->
            editor.persist(editor.settings.copy(addressBookHistory = number))
        }
        "addressBookNeverTrim" -> BoolField(stringResource(R.string.settings_never_trim), settings.addressBookNeverTrim) {
            editor.persist(editor.settings.copy(addressBookNeverTrim = it))
        }
        "extraAddressBooks" -> BookList(stringResource(R.string.settings_extra_books), settings.extraAddressBooks, { walk.picking.value = MailboxPick.ExtraBook }) { gone ->
            editor.persist(editor.settings.copy(extraAddressBooks = editor.settings.extraAddressBooks - gone))
        }
        "globalAddressBooks" -> BookList(stringResource(R.string.settings_global_books), settings.globalAddressBooks, { walk.picking.value = MailboxPick.GlobalBook }) { gone ->
            editor.persist(editor.settings.copy(globalAddressBooks = editor.settings.globalAddressBooks - gone))
        }
        "theme" -> ChoiceField(stringResource(R.string.settings_theme), ThemeMode.entries, settings.theme, { themeName(it) }) {
            editor.persist(editor.settings.copy(theme = it))
        }
        "dynamicColor" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BoolField(stringResource(R.string.settings_dynamic_color), settings.dynamicColor) {
                editor.persist(editor.settings.copy(dynamicColor = it))
            }
        }
        "density" -> ChoiceField(stringResource(R.string.settings_density), Density.entries, settings.density, { densityName(it) }) {
            editor.persist(editor.settings.copy(density = it))
        }
        "dateFormat" -> ChoiceField(
            stringResource(R.string.settings_date_format),
            DateFormat.entries,
            settings.dateFormat,
            { dateFormatName(it) },
        ) {
            editor.persist(editor.settings.copy(dateFormat = it))
        }
        "datePattern" -> if (settings.dateFormat == DateFormat.Custom) {
            LineField(stringResource(R.string.settings_date_pattern), settings.datePattern, ready = editor.ready) {
                editor.persist(editor.settings.copy(datePattern = it))
            }
        }
        "customLayout" -> PaneLayoutEditor(settings.customLayout, settings.readerTextZoom, persist = panesOn) { text ->
            if (panesOn) editor.persist(editor.settings.copy(customLayout = text))
        }
        "multiPane" -> ChoiceField(
            stringResource(R.string.settings_multi_pane),
            MultiPane.entries,
            settings.multiPane,
            { multiPaneName(it) },
        ) {
            editor.persist(editor.settings.copy(multiPane = it))
        }
        "folderPane" -> ChoiceField(
            stringResource(R.string.settings_folder_pane),
            LayoutChoice.entries,
            settings.folderPane,
            { layoutChoiceName(it) },
            enabled = panesOn,
        ) {
            if (panesOn) editor.persist(editor.settings.copy(folderPane = it))
        }
        "foldPosture" -> ChoiceField(
            stringResource(R.string.settings_fold_posture),
            listOf(LayoutChoice.Off, LayoutChoice.On),
            settings.foldPosture,
            { layoutChoiceName(it) },
            enabled = panesOn,
        ) {
            if (panesOn) editor.persist(editor.settings.copy(foldPosture = it))
        }
        "fullScreen" -> BoolField(stringResource(R.string.settings_full_screen), settings.fullScreen) {
            editor.persist(editor.settings.copy(fullScreen = it))
        }
        "paneArrangement" -> ChoiceField(
            stringResource(R.string.settings_pane_arrangement),
            ArrangementChoice.entries,
            settings.paneArrangement,
            { stringResource(when (it) {
                ArrangementChoice.Auto -> R.string.settings_pane_auto
                ArrangementChoice.SideBySide -> R.string.settings_pane_side_by_side
                ArrangementChoice.Stacked -> R.string.settings_pane_stacked
            }) },
            enabled = panesOn,
        ) {
            if (panesOn) editor.persist(editor.settings.copy(paneArrangement = it))
        }
        "previewSize" -> ChoiceField(
            stringResource(R.string.settings_preview_size),
            PreviewChoice.entries,
            settings.previewSize,
            { stringResource(when (it) {
                PreviewChoice.Auto -> R.string.settings_pane_auto
                PreviewChoice.Density -> R.string.settings_preview_density
                PreviewChoice.None -> R.string.settings_preview_none
                PreviewChoice.Small -> R.string.settings_preview_small
                PreviewChoice.Large -> R.string.settings_preview_large
            }) },
        ) {
            editor.persist(editor.settings.copy(previewSize = it))
        }
        "folderPosition" -> ChoiceField(
            stringResource(R.string.settings_folder_position),
            FolderPositionChoice.entries,
            settings.folderPosition,
            { stringResource(when (it) {
                FolderPositionChoice.Auto -> R.string.settings_pane_auto
                FolderPositionChoice.Beside -> R.string.settings_folder_beside
                FolderPositionChoice.AboveList -> R.string.settings_folder_above
            }) },
            enabled = panesOn,
        ) {
            if (panesOn) editor.persist(editor.settings.copy(folderPosition = it))
        }
        "clientSort" -> BoolField(
            stringResource(R.string.settings_client_sort),
            settings.clientSort,
            note = stringResource(R.string.settings_client_sort_note),
        ) {
            editor.persist(editor.settings.copy(clientSort = it))
        }
        "clientThread" -> BoolField(
            stringResource(R.string.settings_client_thread),
            settings.clientThread,
            note = stringResource(R.string.settings_client_thread_note),
        ) {
            editor.persist(editor.settings.copy(clientThread = it))
        }
        "pollForNewMail" -> BoolField(
            stringResource(R.string.settings_poll_mail),
            settings.pollForNewMail,
            note = stringResource(R.string.settings_poll_note),
        ) {
            editor.persist(editor.settings.copy(pollForNewMail = it))
        }
        "pollSeconds" -> NumberLeaf(
            stringResource(SettingsTreeGenerated.regTitle(key)),
            settings.pollSeconds,
            editor.ready,
            { it in 15..600 },
        ) { number ->
            editor.persist(editor.settings.copy(pollSeconds = number))
        }
        "idleFallbackSeconds" -> NumberLeaf(
            stringResource(SettingsTreeGenerated.regTitle(key)),
            settings.idleFallbackSeconds,
            editor.ready,
            { it >= 1 },
        ) { number ->
            editor.persist(editor.settings.copy(idleFallbackSeconds = number))
        }
        "keepAliveMinutes" -> NumberLeaf(
            stringResource(SettingsTreeGenerated.regTitle(key)),
            settings.keepAliveMinutes,
            editor.ready,
            { it >= 1 },
        ) { number ->
            editor.persist(editor.settings.copy(keepAliveMinutes = number))
        }
        "idleRenewMinutes" -> NumberLeaf(
            stringResource(SettingsTreeGenerated.regTitle(key)),
            settings.idleRenewMinutes,
            editor.ready,
            { it >= 1 },
        ) { number ->
            editor.persist(editor.settings.copy(idleRenewMinutes = number))
        }
        "longSearchProgressSec" -> NumberLeaf(
            stringResource(SettingsTreeGenerated.regTitle(key)),
            settings.longSearchProgressSec,
            editor.ready,
            { it >= 1 },
        ) { number ->
            editor.persist(editor.settings.copy(longSearchProgressSec = number))
        }
        "longSearchQuietSec" -> NumberLeaf(
            stringResource(SettingsTreeGenerated.regTitle(key)),
            settings.longSearchQuietSec,
            editor.ready,
            { it >= 1 },
        ) { number ->
            editor.persist(editor.settings.copy(longSearchQuietSec = number))
        }
        "maxJmapResponseBytes" -> NumberLeaf(
            stringResource(SettingsTreeGenerated.regTitle(key)),
            settings.maxJmapResponseBytes,
            editor.ready,
            { it >= 1 },
        ) { number ->
            editor.persist(editor.settings.copy(maxJmapResponseBytes = number))
        }
        "statusVisibleCounts" -> BoolField(
            stringResource(R.string.settings_status_counts),
            settings.statusVisibleCounts,
            note = stringResource(R.string.settings_status_note),
        ) {
            editor.persist(editor.settings.copy(statusVisibleCounts = it))
        }
        "forceSlowerFallbacks" -> BoolField(
            stringResource(R.string.settings_force_slower),
            settings.forceSlowerFallbacks,
            note = stringResource(R.string.settings_force_slower_note),
        ) {
            editor.persist(editor.settings.copy(forceSlowerFallbacks = it))
        }
        "logImapTraffic" -> BoolField(stringResource(R.string.settings_log_traffic), settings.logImapTraffic) {
            editor.persist(editor.settings.copy(logImapTraffic = it))
        }
        "showUserInDebugReport" -> BoolField(stringResource(R.string.settings_show_user), settings.showUserInDebugReport) {
            editor.persist(editor.settings.copy(showUserInDebugReport = it))
        }
        "idleWakeSeconds" -> ChoiceField(
            stringResource(R.string.settings_idle_wake),
            IdleWakeSeconds,
            settings.idleWakeSeconds.takeIf { it in IdleWakeSeconds } ?: 5,
            { "$it s" },
        ) { editor.persist(editor.settings.copy(idleWakeSeconds = it)) }
        "hiddenCapabilities" -> {
            for (token in hideCapabilityTokens) {
                val hidden = settings.hiddenCapabilities.any { it.equals(token, ignoreCase = true) }
                BoolField(token, hidden) { on ->
                    val next = settings.hiddenCapabilities
                        .filterNot { it.equals(token, ignoreCase = true) }
                        .toMutableSet()
                    if (on) next.add(token)
                    editor.persist(editor.settings.copy(hiddenCapabilities = next))
                }
            }
        }
        "indexBar" -> ToolbarLeaf(ToolbarScreen.Index, walk)
        "selectionBar" -> ToolbarLeaf(ToolbarScreen.Selection, walk)
        "folderBar" -> ToolbarLeaf(ToolbarScreen.Folders, walk)
        "readerToolbar" -> ToolbarLeaf(ToolbarScreen.Reader, walk)
        "composeBar" -> ToolbarLeaf(ToolbarScreen.Compose, walk)
        "toolbarRows" -> NumberLeaf(
            stringResource(SettingsTreeGenerated.regTitle(key)),
            settings.toolbarRows,
            editor.ready,
            { it in 1..4 },
        ) { number ->
            editor.persistToolbarRows(number)
        }
        "favorites" -> {
            for (favorite in settings.favorites) {
                val shown = if (favorite.label.isNotBlank()) {
                    favorite.label
                } else {
                    favoriteLabel(favorite.node, favorite.mailbox, favorite.delimiter)
                }
                ListItem(
                    headlineContent = { Text(shown) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { walk.onEditFavorite(favorite) },
                )
            }
        }
        "maxResultUids" -> NumberLeaf(
            stringResource(SettingsTreeGenerated.regTitle(key)),
            settings.maxResultUids,
            editor.ready,
            { it >= 1 },
        ) { number ->
            editor.persist(editor.settings.copy(maxResultUids = number))
        }
        "maxRawViewBytes" -> {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            NumberLeaf(
                stringResource(SettingsTreeGenerated.regTitle(key)),
                InstalledGlobals.maxRawViewBytes.get(),
                editor.ready,
                { it >= 1 },
            ) { number ->
                scope.launch { saveGlobalNumber(context, key, number) }
            }
        }
        "maxImageTrustBytes" -> {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            NumberLeaf(
                stringResource(SettingsTreeGenerated.regTitle(key)),
                InstalledGlobals.maxImageTrustBytes.get(),
                editor.ready,
                { it >= 1 },
            ) { number ->
                scope.launch { saveGlobalNumber(context, key, number) }
            }
        }
        else -> Text(stringResource(SettingsTreeGenerated.regTitle(key)))
    }
}

@Composable
private fun SieveCheckButton(editor: SettingsEditor, drafts: ServerDrafts) {
    val noSieveHost = stringResource(R.string.settings_sieve_no_host)
    TextButton(
        onClick = {
            if (drafts.checkingSieve) return@TextButton
            val sieveStore = editor.bound
            val sieveAccount = editor.editingId
            val (host, port) = sieveEndpoint(editor.settings)
            if (host.isBlank()) {
                drafts.sieveReport = noSieveHost
                return@TextButton
            }
            val blocked = editor.settings.preservedSieveTlsKey()
            if (blocked != null) {
                drafts.sieveReport = "account settings need attention: $blocked"
                return@TextButton
            }
            val sieveTlsMode = editor.settings.sieveTlsMode
            val pin = editor.settings.sieveCertPin
            drafts.checkingSieve = true
            drafts.sieveReport = ""
            editor.ui.launch {
                try {
                    drafts.sieveReport = sieveCheckText(
                        withContext(editor.settings.tlsPolicy()) {
                            greetSieve(host, port, sieveTlsMode, pin, ::confirmMailCertificate) { fingerprint ->
                                persistSievePin(sieveStore, sieveAccount, fingerprint)
                            }
                        },
                    )
                } catch (failure: SieveFailure) {
                    drafts.sieveReport = failure.text
                } finally {
                    drafts.checkingSieve = false
                }
            }
        },
        enabled = !drafts.checkingSieve,
    ) {
        Text(stringResource(R.string.settings_sieve_check))
    }
    if (drafts.sieveReport.isNotEmpty()) Text(drafts.sieveReport)
}

@Composable
private fun accountFrame(node: TreeNode.Group, depth: Int, walk: SettingsWalk) {
    val editor = walk.editor
    val sections = walk.sections
    val settings = editor.settings
    val chosenAccount = editor.accounts.firstOrNull { it.chosen }?.id
    val drafts = remember(editor.editingId, chosenAccount) { ServerDrafts() }
    walk.drafts = drafts
    val server = drafts.serverDraft ?: settings
    DisposableEffect(drafts.hasDraft) {
        UnsavedEdits.pending = drafts.hasDraft
        UnsavedEdits.ask = { go -> drafts.leaveAfter = go; drafts.discardAsk = true }
        onDispose { UnsavedEdits.pending = false; UnsavedEdits.ask = null }
    }
    LaunchedEffect(drafts.hasDraft, drafts.serverReview == null, drafts.serverChecking) {
        if (!drafts.hasDraft && drafts.serverReview == null && !drafts.serverChecking) {
            drafts.leaveAfter?.let { drafts.leaveAfter = null; it() }
        }
    }
    BackHandler(enabled = drafts.hasDraft) { drafts.discardAsk = true }
    drafts.serverReview?.let { review ->
        AlertDialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
            title = { Text(stringResource(R.string.settings_save_server_title)) },
            text = {
                Text(
                    (listOfNotNull(review.tlsWarning?.let { stringResource(it) }) +
                        (if (review.unreachable) listOf(stringResource(R.string.settings_check_unreachable)) else emptyList()) +
                        review.lines).joinToString("\n"),
                )
            },
            confirmButton = {
                androidx.compose.material3.Button(onClick = {
                    drafts.serverReview = null
                    drafts.serverDraft?.let { editor.persist(drafts.savedServer(editor, it)) }
                    drafts.commitPasswords(editor)
                    drafts.serverDraft = null
                }) { Text(stringResource(R.string.settings_check_save_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { drafts.serverReview = null; drafts.leaveAfter = null }) { Text(stringResource(R.string.settings_keep_editing)) }
            },
        )
    }
    if (drafts.discardAsk) {
        AlertDialog(
            onDismissRequest = {},
            properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
            title = { Text(stringResource(R.string.settings_unsaved_title)) },
            confirmButton = {
                androidx.compose.material3.Button(onClick = { drafts.discardAsk = false; drafts.saveServer(editor) }) {
                    Text(stringResource(R.string.settings_save))
                }
            },
            dismissButton = {
                androidx.compose.foundation.layout.Row {
                    TextButton(onClick = { drafts.discardAsk = false; drafts.leaveAfter = null }) { Text(stringResource(R.string.settings_keep_editing)) }
                    TextButton(onClick = { drafts.discardAsk = false; drafts.discard() }) {
                        Text(stringResource(R.string.settings_discard))
                    }
                }
            },
        )
    }
    var importPreview by remember { mutableStateOf<PinercPreview?>(null) }
    var importGeneration by remember { mutableStateOf(0) }
    var importError by remember { mutableStateOf<Int?>(null) }
    var removingId by remember { mutableStateOf<String?>(null) }
    val settingsState = editor.settingsState
    val contextState = rememberUpdatedState(LocalContext.current)
    val pinercPhrases = PinercPhrases(
        tls = stringResource(R.string.pinerc_tls),
        tlsMode = stringResource(R.string.settings_tls),
        tlsNone = stringResource(R.string.settings_tls_none),
        tlsStart = stringResource(R.string.settings_tls_starttls),
        tlsImplicit = stringResource(R.string.settings_tls_implicit),
        smtpUsername = stringResource(R.string.settings_smtp_username),
        local = stringResource(R.string.pinerc_local),
        history = stringResource(R.string.pinerc_history),
        sort = stringResource(R.string.pinerc_sort),
        rule = stringResource(R.string.pinerc_rule),
        expunge = stringResource(R.string.pinerc_expunge),
        passwords = stringResource(R.string.pinerc_passwords),
        folders = stringResource(R.string.pinerc_folders),
        signature = stringResource(R.string.pinerc_signature),
        perFolder = stringResource(R.string.pinerc_per_folder),
        inboxDefault = stringResource(R.string.pinerc_inbox_default),
        change = stringResource(R.string.pinerc_change),
        imapHost = stringResource(R.string.settings_imap_host),
        imapPort = stringResource(R.string.settings_imap_port),
        smtpHost = stringResource(R.string.settings_smtp_host),
        smtpPort = stringResource(R.string.settings_smtp_port),
        username = stringResource(R.string.settings_username),
        displayName = stringResource(R.string.settings_display_name),
        altAddresses = stringResource(R.string.settings_alt_addresses),
        email = stringResource(R.string.settings_email),
        sentMailbox = stringResource(R.string.settings_sent_mailbox),
        postponedMailbox = stringResource(R.string.settings_postponed_mailbox),
        addressBookMailbox = stringResource(R.string.settings_address_book_mailbox),
        historyLabel = stringResource(R.string.settings_history_label),
        extraAddressBooks = stringResource(R.string.settings_extra_books),
        globalAddressBooks = stringResource(R.string.settings_global_books),
        defaultView = stringResource(R.string.settings_default_view),
        newest = stringResource(R.string.reader_newest),
        askExpunge = stringResource(R.string.settings_ask_expunge),
        inboxOpens = stringResource(R.string.settings_inbox_opens),
        otherHost = stringResource(R.string.pinerc_other_host),
        inboxBraces = stringResource(R.string.pinerc_inbox_braces),
        missingFolder = stringResource(R.string.pinerc_folder_missing),
        folderCheck = stringResource(R.string.pinerc_folder_check),
        favorite = stringResource(R.string.pinerc_favorite),
        signatureFile = stringResource(R.string.pinerc_signature_file),
        signatureLabel = stringResource(R.string.pinerc_signature_label),
        quotePrefixLabel = stringResource(R.string.pinerc_quote_prefix),
        leadInLabel = stringResource(R.string.pinerc_lead_in),
        sigDashesLabel = stringResource(R.string.pinerc_sig_dashes),
        sigBottomLabel = stringResource(R.string.pinerc_sig_bottom),
        dsnLabel = stringResource(R.string.pinerc_dsn),
        composerHeadersLabel = stringResource(R.string.pinerc_composer_headers),
        viewerHeadersLabel = stringResource(R.string.pinerc_viewer_headers),
        rolesLabel = stringResource(R.string.pinerc_role_rule),
        roleSkipped = stringResource(R.string.pinerc_role_skipped),
        roleNoConditions = stringResource(R.string.pinerc_role_no_conditions),
    )
    val openPinerc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        when (val outcome = readPinercStream(contextState.value.contentResolver, uri)) {
            is PinercRead.Ok -> {
                importError = null
                importGeneration += 1
                val generation = importGeneration
                importPreview = null
                val text = outcome.text
                editor.ui.launch {
                    val current = settingsState.value
                    val built = pinercPreview(text, current, pinercPhrases)
                    val names = ArrayList<String>(3)
                    if (built.next.sentMailbox.isNotEmpty() && built.next.sentMailbox != current.sentMailbox) {
                        names.add(built.next.sentMailbox)
                    }
                    if (built.next.postponedMailbox.isNotEmpty() &&
                        built.next.postponedMailbox != current.postponedMailbox
                    ) {
                        names.add(built.next.postponedMailbox)
                    }
                    if (built.next.addressBookMailbox.isNotEmpty() &&
                        built.next.addressBookMailbox != current.addressBookMailbox
                    ) {
                        names.add(built.next.addressBookMailbox)
                    }
                    val id = editor.bound.chosenAccountId()
                    val shown = if (id == null) {
                        withoutCheckedFavorites(
                            withoutCheckedMailboxes(built, current, pinercPhrases),
                            current,
                            pinercPhrases,
                        )
                    } else {
                        val session = mailSession(id)
                        try {
                            val checked = if (names.isEmpty()) {
                                built
                            } else {
                                val missing = linkedSetOf<String>()
                                for (name in names) {
                                    if (!pinercMailboxListed(session, name)) missing.add(name)
                                }
                                withoutMissingMailboxes(built, current, missing, pinercPhrases)
                            }
                            val fresh = newLeafMailboxes(checked.next.favorites, current.favorites)
                            if (fresh.isEmpty()) {
                                checked
                            } else {
                                try {
                                    val delimiter = favoriteDelimiter(session.namespaces())
                                    val missingFavorites = linkedSetOf<String>()
                                    for (name in fresh) {
                                        if (!pinercMailboxListed(session, name)) missingFavorites.add(name)
                                    }
                                    withFavoriteDelimiter(
                                        withoutMissingFavorites(checked, current, missingFavorites, pinercPhrases),
                                        current,
                                        delimiter,
                                    )
                                } catch (_: MailFailure) {
                                    withoutCheckedFavorites(checked, current, pinercPhrases)
                                }
                            }
                        } catch (_: MailFailure) {
                            withoutCheckedFavorites(
                                withoutCheckedMailboxes(built, current, pinercPhrases),
                                current,
                                pinercPhrases,
                            )
                        }
                    }
                    if (importGeneration == generation) importPreview = shown
                }
            }
            PinercRead.TooLarge -> {
                importPreview = null
                importError = R.string.settings_file_large
            }
            PinercRead.Bad -> {
                importPreview = null
                importError = R.string.settings_file_unreadable
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val accountRows = editor.accounts
        SettingsNode(
            title = stringResource(R.string.settings_accounts),
            depth = depth,
            summary = accountRows.firstOrNull { it.chosen }?.name.orEmpty(),
            expanded = "accounts" in sections.open,
            onToggle = { sections.toggle("accounts") },
        ) {
            accountRows.forEach { account ->
                ListItem(
                    headlineContent = { Text(account.name) },
                    leadingContent = {
                        RadioButton(
                            selected = account.chosen,
                            onClick = { editor.selectAccount(account.id) },
                        )
                    },
                    trailingContent = if (accountRows.size < 2) {
                        null
                    } else {
                        {
                            TextButton(onClick = { removingId = account.id }) {
                                Text(stringResource(R.string.settings_remove_account))
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editor.selectAccount(account.id) },
                )
            }
        }
        // Always visible (not only inside the expanded Accounts section): the in-app way to create an account.
        TextButton(onClick = { editor.addAccount() }) {
            Text(stringResource(R.string.settings_add_account))
        }
        for (child in node.children) {
            renderSettingsNode(child, depth, walk)
        }
        SettingsNode(
            title = stringResource(R.string.settings_import),
            depth = depth,
            summary = stringResource(R.string.settings_import_pinerc),
            expanded = "import" in sections.open,
            onToggle = { sections.toggle("import") },
        ) {
            TextButton(onClick = { openPinerc.launch(arrayOf("text/plain", "*/*")) }) {
                Text(stringResource(R.string.settings_import_pinerc))
            }
            TextButton(onClick = {
                sharePinerc(contextState.value, encodePinerc(editor.settings))
            }) {
                Text(stringResource(R.string.settings_export_pinerc))
            }
        }
        // David, 2026-10-12: Save and Test account side by side; Test account logs in (IMAP or JMAP, and SMTP).
        androidx.compose.foundation.layout.Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            androidx.compose.material3.Button(
                onClick = { drafts.saveServer(editor) },
                enabled = drafts.hasDraft && !drafts.serverChecking,
                modifier = Modifier.testTag("account-save"),
            ) { Text(stringResource(R.string.settings_save)) }
            TextButton(
                onClick = {
                    if (!drafts.probing) {
                        val candidate = drafts.savedServer(editor, server)
                        drafts.probing = true
                        drafts.serverReport = emptyList()
                        editor.ui.launch {
                            val id = editor.bound.chosenAccountId()
                            try {
                                drafts.serverReport = if (id == null) emptyList() else testAccountLogins(candidate, testAccountProbes(id, candidate, drafts.passwordDraft ?: editor.password, drafts.smtpPasswordDraft ?: editor.smtpPassword))
                            } finally {
                                drafts.probing = false
                            }
                        }
                    }
                },
                enabled = !drafts.probing,
                modifier = Modifier.testTag("account-test"),
            ) {
                Text(stringResource(if (drafts.probing) R.string.settings_testing else R.string.settings_test_account))
            }
        }
        for (line in drafts.serverReport) {
            Text(text = line, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 16.dp))
        }
        // F46: the acceptance runner is a developer tool; release builds do not show it.
        if (com.davidlang.liveimap.BuildConfig.DEBUG) org.dlang.liveimap.debug.acceptance.AcceptanceSection()
    }
    val removing = removingId
    if (removing != null) {
        AlertDialog(
            onDismissRequest = { removingId = null },
            title = { Text(stringResource(R.string.settings_remove_account)) },
            text = { Text(stringResource(R.string.settings_remove_account_body)) },
            confirmButton = {
                TextButton(onClick = {
                    removingId = null
                    editor.removeAccount(removing)
                }) { Text(stringResource(R.string.settings_remove_account)) }
            },
            dismissButton = {
                TextButton(onClick = { removingId = null }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
    val error = importError
    if (error != null) {
        AlertDialog(
            onDismissRequest = { importError = null },
            text = { Text(stringResource(error)) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text(stringResource(R.string.compose_close)) }
            },
        )
    }
    val preview = importPreview
    if (preview != null) {
        var turnOnAutoExpunge by remember(importGeneration) { mutableStateOf(false) }
        val applied = pinercApplied(preview, turnOnAutoExpunge)
        Dialog(
            onDismissRequest = { importPreview = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            ObscuredTouchFilter()
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(
                            WindowInsets.statusBars
                                .union(WindowInsets.navigationBars)
                                .union(WindowInsets.displayCutout),
                        )
                        .padding(16.dp),
                ) {
                    Text(stringResource(R.string.settings_import_title), style = MaterialTheme.typography.titleLarge)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.settings_will_change))
                        preview.rows.forEach { line -> Text(line) }
                        if (preview.offerAutoExpunge) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { turnOnAutoExpunge = !turnOnAutoExpunge },
                            ) {
                                Checkbox(
                                    checked = turnOnAutoExpunge,
                                    onCheckedChange = { turnOnAutoExpunge = it },
                                )
                                Text(stringResource(R.string.settings_import_auto_expunge))
                            }
                        }
                        Text(stringResource(R.string.settings_not_applied))
                        preview.skipped.forEach { line -> Text(line) }
                        Text(stringResource(R.string.settings_ignored))
                        if (preview.omittedCount != 0) {
                            Text(
                                pluralStringResource(
                                    R.plurals.settings_omitted,
                                    preview.omittedCount,
                                    preview.omittedCount,
                                ),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { importPreview = null }) { Text(stringResource(R.string.unsent_cancel)) }
                        TextButton(
                            onClick = {
                                editor.persist(applied)
                                importPreview = null
                            },
                            enabled = applied != editor.settings,
                        ) { Text(stringResource(R.string.settings_apply)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MailboxPickDialog(
    editor: SettingsEditor,
    picking: MailboxPick?,
    onPicking: (MailboxPick?) -> Unit,
) {
    val field = picking ?: return
    // David's Sent rule: the server's marks only prefill (offer) a default for an unset folder here.
    val current = when (field) {
        MailboxPick.Sent -> editor.settings.sentMailbox
        MailboxPick.Postponed -> editor.settings.postponedMailbox
        MailboxPick.Trash -> editor.settings.trashMailbox
        MailboxPick.Spam -> editor.settings.spamMailbox
        else -> "x"
    }
    val suggestUse = if (current.isNotEmpty()) "" else when (field) {
        MailboxPick.Sent -> "\\Sent"
        MailboxPick.Postponed -> "\\Drafts"
        MailboxPick.Trash -> "\\Trash"
        MailboxPick.Spam -> "\\Junk"
        else -> ""
    }
    MailboxChooser(
        suggestUse = suggestUse,
        store = editor.bound,
        onStored = { loaded ->
            editor.settings = editor.settings.copy(expandedFolders = loaded.expandedFolders)
        },
        onPick = { mailbox ->
            settingsIo.launch {
                editor.bound.onAccount(publish = { saved ->
                    editor.settings = editor.settings.keepingStoredToolbars(saved).let { current ->
                        assignMailbox(current, field, mailbox)
                    }
                }) {
                    editor.bound.update { assignMailbox(it, field, mailbox) }
                }
                onPicking(null)
            }
        },
        onDismiss = { onPicking(null) },
    )
}

@Composable
private fun SettingsPage(content: @Composable () -> Unit) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun SettingsNode(
    title: String,
    depth: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    summary: String,
    children: @Composable () -> Unit,
) {
    val description = stringResource(
        if (expanded) R.string.settings_expanded_state else R.string.settings_collapsed_state,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp * depth)
            .clickable(onClick = onToggle)
            .semantics { stateDescription = description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight,
                contentDescription = stringResource(
                    if (expanded) R.string.folders_collapse else R.string.folders_expand,
                    title,
                ),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title)
            if (!expanded) Text(summary)
        }
    }
    if (expanded) children()
}

@Composable
private fun OpenRow(title: String, onOpen: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    )
}

private data class CompletionRow(val id: String, val label: String, val enabled: Boolean)

@Composable
private fun completionSummary(settings: AccountSettings): String {
    val ids = settings.completionSources
    if (ids.isEmpty()) return stringResource(R.string.settings_no_sources)
    if (ids == listOf(pineSourceId)) return stringResource(R.string.settings_pine)
    return pluralStringResource(R.plurals.settings_sources, ids.size, ids.size)
}

private fun completionRows(
    settings: AccountSettings,
    sets: List<AndroidContactSet>,
    pineLabel: String,
): List<CompletionRow> {
    val enabled = settings.completionSources
    val setById = sets.associateBy { it.id }
    val rows = ArrayList<CompletionRow>()
    val shown = HashSet<String>()
    for (id in enabled) {
        if (id == pineSourceId) {
            if (settings.addressBookMailbox.isEmpty()) continue
            rows.add(CompletionRow(id, pineLabel, true))
            shown.add(id)
            continue
        }
        val set = setById[id] ?: continue
        rows.add(CompletionRow(id, set.label, true))
        shown.add(id)
    }
    if (settings.addressBookMailbox.isNotEmpty() && pineSourceId !in shown) {
        rows.add(CompletionRow(pineSourceId, pineLabel, false))
    }
    for (set in sets) {
        if (set.id in shown) continue
        rows.add(CompletionRow(set.id, set.label, false))
    }
    return rows
}

@Composable
private fun AddressCompletionRows(editor: SettingsEditor) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    var granted by remember { mutableStateOf(hasReadContacts(context)) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var sets by remember { mutableStateOf<List<AndroidContactSet>>(emptyList()) }
    val requestContacts = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(granted) {
        if (!granted) {
            sets = emptyList()
            return@LaunchedEffect
        }
        sets = try {
            androidContactSetsOnce(appContext.contentResolver, appContext.packageManager)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
    }
    val pineLabel = stringResource(R.string.settings_pine)
    for (row in completionRows(editor.settings, sets, pineLabel)) {
        BoolField(row.label, row.enabled) { on ->
            editor.persist(
                editor.settings.copy(
                    completionSources = withSourceEnabled(editor.settings.completionSources, row.id, on),
                ),
            )
        }
        if (!row.enabled) continue
        Row {
            TextButton(onClick = {
                editor.persist(
                    editor.settings.copy(
                        completionSources = moveCompletionSource(
                            editor.settings.completionSources,
                            row.id,
                            up = true,
                        ),
                    ),
                )
            }) { Text(stringResource(R.string.drawer_move_up)) }
            TextButton(onClick = {
                editor.persist(
                    editor.settings.copy(
                        completionSources = moveCompletionSource(
                            editor.settings.completionSources,
                            row.id,
                            up = false,
                        ),
                    ),
                )
            }) { Text(stringResource(R.string.drawer_move_down)) }
        }
    }
    if (granted) return
    BoolField(stringResource(R.string.settings_show_android), false) { on ->
        if (on) requestContacts.launch(android.Manifest.permission.READ_CONTACTS)
    }
    if (denied) Text(stringResource(R.string.settings_contacts_denied))
}

@Composable
private fun hiddenSummary(settings: AccountSettings): String {
    val count = settings.hiddenCapabilities.size
    return if (count == 0) {
        stringResource(R.string.settings_hide_none)
    } else {
        stringResource(R.string.settings_hide_count, count)
    }
}


@Composable
private fun groupSummary(title: String, settings: AccountSettings): String = when (title) {
    "Account" -> {
        val noServer = stringResource(R.string.settings_no_server)
        settings.imapHost.ifBlank { noServer }
    }
    "Identity" -> settings.email.ifBlank { settings.displayName }
    "Mail access" -> stringResource(
        if (settings.jmapFolders) R.string.settings_access_jmap else R.string.settings_access_imap,
    )
    "Server" -> if (settings.jmapFolders) {
        settings.imapHost.ifBlank { stringResource(R.string.settings_no_server) }
    } else {
        hostAndPort(settings.imapHost, settings.imapPort, stringResource(R.string.settings_no_server))
    }
    "Sending" -> if (settings.emailSubmission) {
        stringResource(R.string.settings_send_over_jmap)
    } else {
        hostAndPort(settings.smtpHost, settings.smtpPort, stringResource(R.string.settings_no_smtp))
    }
    "Watched folders" -> if (settings.watchedFolders.isEmpty()) {
        stringResource(R.string.settings_watched_none)
    } else {
        settings.watchedFolders.size.toString()
    }
    "Sending rules" -> sendingRulesSummary(settings)
    "Mailboxes", "Special-use" -> mailboxSetCount(settings)
    "Folders" -> sortDirection(settings)
    "Folder list" -> pluralStringResource(
        R.plurals.settings_expanded,
        settings.expandedFolders.size,
        settings.expandedFolders.size,
    )
    "Folder views" -> sortDirection(settings)
    "Start position" -> stringResource(R.string.settings_inbox_line, startRuleName(settings.inboxStart))
    "Large folders" -> stringResource(R.string.settings_ask_note)
    "Reading", "Opening" -> bodyViewName(settings.bodyView)
    "Deleting" -> stringResource(
        if (settings.askBeforeExpunge) R.string.settings_ask_expunge else R.string.settings_expunge_without,
    )
    "Actions" -> stringResource(
        R.string.settings_swipe_line,
        swipeActionName(settings.swipeTrailing.action),
        swipeActionName(settings.swipeLeading.action),
    )
    "Compose" -> stringResource(
        if (settings.replyAboveQuote) R.string.settings_reply_above else R.string.settings_reply_below,
    )
    "Composing" -> settings.composerWrapColumn.toString()
    "Replying" -> stringResource(
        if (settings.replyAboveQuote) R.string.settings_reply_above else R.string.settings_reply_below,
    )
    "Forwarding" -> forwardSummary(settings)
    "Fcc" -> saveNameRuleName(settings.saveNameRule)
    "Address completion" -> completionSummary(settings)
    "Address book" -> if (settings.addressBookNeverTrim) {
        stringResource(R.string.settings_never_trim)
    } else {
        stringResource(R.string.settings_history, settings.addressBookHistory)
    }
    "Appearance" -> themeName(settings.theme)
    "Theme" -> themeSummary(settings)
    "Display" -> stringResource(
        R.string.settings_display_line,
        densityName(settings.density),
        dateFormatName(settings.dateFormat),
    )
    "Layout" -> multiPaneName(settings.multiPane)
    "Custom panel layout" -> if (settings.customLayout.isEmpty()) {
        stringResource(R.string.settings_pane_auto)
    } else {
        stringResource(R.string.layout_custom)
    }
    "Slower" -> stringResource(
        if (
            settings.clientSort ||
            settings.clientThread ||
            settings.pollForNewMail ||
            settings.statusVisibleCounts
        ) {
            R.string.settings_slower_on
        } else {
            R.string.settings_slower_off
        },
    )
    "Debug" -> stringResource(
        if (settings.logImapTraffic) R.string.settings_traffic_on else R.string.settings_traffic_off,
    )
    "Logging" -> loggingSummary(settings)
    "Hide capabilities" -> hiddenSummary(settings)
    "Favorites" -> settings.favorites.size.toString()
    else -> ""
}

@Composable
private fun saveNameRuleName(rule: SaveNameRule): String = stringResource(
    when (rule) {
        SaveNameRule.DefaultFolder -> R.string.settings_save_default
        SaveNameRule.ByFrom -> R.string.settings_save_from
        SaveNameRule.BySender -> R.string.settings_save_sender
        SaveNameRule.ByRecipient -> R.string.settings_save_recipient
        SaveNameRule.LastFolderUsed -> R.string.settings_save_last
    },
)

@Composable
private fun mailboxSetCount(settings: AccountSettings): String {
    val count = listOf(
        settings.sentMailbox,
        settings.postponedMailbox,
        settings.addressBookMailbox,
        settings.spamMailbox,
        settings.trashMailbox,
    ).count { it.isNotEmpty() }
    return stringResource(R.string.settings_mailboxes_set, count)
}

@Composable
private fun sortDirection(settings: AccountSettings): String {
    val direction = stringResource(
        if (settings.defaultView.newestFirst) R.string.reader_newest else R.string.reader_oldest,
    )
    return stringResource(R.string.settings_sort_line, sortKeyName(settings.defaultView.key), direction)
}

@Composable
private fun hostAndPort(host: String, port: Int, empty: String): String {
    if (host.isBlank()) return empty
    return stringResource(R.string.settings_host_port, host, port)
}

@Composable
private fun forwardSummary(settings: AccountSettings): String = when {
    settings.forwardAsAttachment -> stringResource(R.string.settings_forward_attachment)
    settings.includeForwardAttachments -> stringResource(R.string.settings_include_short)
    else -> stringResource(R.string.settings_attachments_off)
}

@Composable
private fun themeSummary(settings: AccountSettings): String {
    val base = themeName(settings.theme)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && settings.dynamicColor) {
        return stringResource(R.string.settings_dynamic_line, base)
    }
    return base
}

@Composable
private fun loggingSummary(settings: AccountSettings): String {
    val pipeline = stringResource(
        if (settings.pipelineCommands) R.string.settings_pipeline_on else R.string.settings_pipeline_off,
    )
    val log = stringResource(
        if (settings.logImapTraffic) R.string.settings_log_on else R.string.settings_log_off,
    )
    return pipeline + log
}

private class LineCommitter {
    var ready: Boolean = true
    var commitOnLeave: Boolean = true
    var onCommit: (String) -> Unit = {}
    var onDraft: ((String) -> Unit)? = null
    var interpret: (String, String) -> String? = { _, _ -> null }
    var skipLeave: () -> Boolean = { false }
    lateinit var draft: MutableState<String>
    lateinit var baseline: MutableState<String>

    fun commit() {
        if (!ready) return
        val current = draft.value
        val base = baseline.value
        val written = interpret(current, base)
        if (written == null) {
            if (current != base) {
                draft.value = base
                onDraft?.invoke(base)
            }
            return
        }
        baseline.value = written
        if (draft.value != written) draft.value = written
        onCommit(written)
    }

    fun commitFromDispose() {
        if (!commitOnLeave || !ready || skipLeave()) return
        commit()
    }
}

/** Advanced: import a PKCS#12 client certificate (.p12 + password) for IMAP and SMTP, or remove it. */
@Composable
private fun ClientCertField(editor: SettingsEditor) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<android.net.Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pending = uri
        password = ""
        message = null
    }
    val current = editor.settings.clientCert
    val alias = editor.settings.clientCertAlias
    Text(stringResource(R.string.settings_client_cert))
    if (current.isBlank() && alias.isNotBlank()) {
        // Per-account choice 2: an Android KeyChain certificate (key stays in the system keystore).
        Text(stringResource(R.string.settings_client_cert_keychain_current, alias))
        TextButton(onClick = {
            org.dlang.liveimap.engine.KeyChainCerts.removeImported(alias)
            editor.persist(editor.settings.copy(clientCertAlias = ""))
        }) {
            Text(stringResource(R.string.settings_client_cert_remove))
        }
    } else if (current.isBlank()) {
        Text(stringResource(R.string.settings_client_cert_none))
    } else {
        Text(ClientCertStore.load(current)?.let { runCatching { ClientCertStore.check(it.p12, it.password) }.getOrNull() }
            ?: stringResource(R.string.settings_client_cert_missing))
        TextButton(onClick = {
            ClientCertStore.remove(current)
            editor.persist(editor.settings.copy(clientCert = ""))
        }) { Text(stringResource(R.string.settings_client_cert_remove)) }
    }
    TextButton(onClick = { pick.launch(arrayOf("application/x-pkcs12", "application/octet-stream", "*/*")) }) {
        Text(stringResource(R.string.settings_client_cert_import))
    }
    TextButton(onClick = {
        var c = context
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        val activity = c as? android.app.Activity ?: return@TextButton
        android.security.KeyChain.choosePrivateKeyAlias(
            activity,
            { chosen ->
                if (chosen != null) {
                    scope.launch {
                        val old = editor.settings.clientCert
                        editor.persist(editor.settings.copy(clientCert = "", clientCertAlias = chosen))
                        if (old.isNotBlank()) withContext(Dispatchers.IO) { ClientCertStore.remove(old) }
                    }
                }
            },
            arrayOf("RSA", "EC"),
            null,
            editor.settings.imapHost.ifBlank { null },
            editor.settings.imapPort,
            alias.ifBlank { null },
        )
    }) { Text(stringResource(R.string.settings_client_cert_keychain)) }
    val uri = pending
    if (uri != null) {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.settings_client_cert_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        TextButton(onClick = {
            val typed = password
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                        ClientCertStore.check(bytes, typed)
                        // The key goes into AndroidKeyStore; neither the .p12 nor its password is stored.
                        org.dlang.liveimap.engine.KeyChainCerts.importP12(java.util.UUID.randomUUID().toString(), bytes, typed)
                    }
                }
                result.onSuccess { alias ->
                    val old = editor.settings.clientCert
                    val oldAlias = editor.settings.clientCertAlias
                    editor.persist(editor.settings.copy(clientCert = "", clientCertAlias = alias))
                    if (old.isNotBlank()) ClientCertStore.remove(old)
                    if (oldAlias != alias) org.dlang.liveimap.engine.KeyChainCerts.removeImported(oldAlias)
                    pending = null
                    password = ""
                    message = null
                }.onFailure { message = it.message ?: it.toString() }
            }
        }) { Text(stringResource(R.string.settings_client_cert_save)) }
    }
    message?.let { Text(it) }
}

@Composable
private fun LineField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    singleLine: Boolean = true,
    ready: Boolean = true,
    enabled: Boolean = true,
    commitOnLeave: Boolean = true,
    accept: (String) -> Boolean = { true },
    onDraft: ((String) -> Unit)? = null,
    interpret: (String, String) -> String? = { draft, stored -> commitText(draft, stored) },
    onFocus: ((Boolean) -> Unit)? = null,
    onCommit: (String) -> Unit,
) {
    val draftState = if (password) {
        remember { mutableStateOf(value) }
    } else {
        rememberSaveable { mutableStateOf(value) }
    }
    val baselineState = remember { mutableStateOf(value) }
    if (value != baselineState.value) {
        if (draftState.value == baselineState.value || draftState.value == value) {
            draftState.value = value
        }
        baselineState.value = value
    }
    val committer = remember { LineCommitter() }
    committer.draft = draftState
    committer.baseline = baselineState
    committer.ready = ready
    committer.commitOnLeave = commitOnLeave
    committer.onCommit = onCommit
    committer.onDraft = onDraft
    committer.interpret = interpret
    val context = LocalContext.current
    committer.skipLeave = { context.findActivity()?.isChangingConfigurations == true }
    if (onDraft != null) {
        SideEffect { onDraft(draftState.value) }
    }
    DisposableEffect(committer) {
        onDispose { committer.commitFromDispose() }
    }
    var hadFocus by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = draftState.value,
        onValueChange = { next ->
            if (!accept(next)) return@OutlinedTextField
            draftState.value = next
            onDraft?.invoke(next)
        },
        enabled = enabled,
        label = { Text(label) },
        singleLine = singleLine,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(onDone = {
            committer.commit()
            focusManager.clearFocus()
        }),
        modifier = Modifier
            .fillMaxWidth()
            // Hardware Enter: commit on key-down, give up focus only on key-up. Clearing focus on key-down sent
            // the key-up to the next focused element, which activated it (Navigate up left the screen).
            .onPreviewKeyEvent { event ->
                val enter = event.key == Key.Enter || event.key == Key.NumPadEnter
                when (lineFieldKey(enter, event.type == KeyEventType.KeyDown, singleLine)) {
                    LineKey.Pass -> false
                    LineKey.Commit -> {
                        committer.commit()
                        true
                    }
                    LineKey.Swallow -> {
                        focusManager.clearFocus()
                        true
                    }
                }
            }
            .onFocusChanged { state ->
                if (hadFocus && !state.isFocused && !committer.skipLeave()) committer.commit()
                if (hadFocus != state.isFocused) onFocus?.invoke(state.isFocused)
                hadFocus = state.isFocused
            },
    )
}

@Composable
private fun AskLimitField(
    label: String,
    value: Int,
    ready: Boolean,
    onValue: (Int) -> Unit,
) {
    LineField(
        label = label,
        value = value.toString(),
        keyboardType = KeyboardType.Number,
        ready = ready,
        accept = { draft -> askLimitDraft(draft) },
        interpret = { draft, _ -> askLimitCommit(draft) },
        onCommit = { text ->
            val number = text.toIntOrNull() ?: return@LineField
            onValue(number)
        },
    )
}

private fun askLimitDraft(draft: String): Boolean {
    if (draft.isEmpty() || draft == "-") return true
    val digits = if (draft.startsWith("-")) draft.drop(1) else draft
    if (digits.isEmpty() || digits.any { !it.isDigit() }) return false
    if (digits.length > 10) return false
    val number = draft.toLongOrNull() ?: return false
    return number <= Int.MAX_VALUE.toLong()
}

private fun askLimitCommit(draft: String): String? {
    if (draft.isEmpty() || !askLimitDraft(draft)) return null
    val number = draft.toLongOrNull() ?: return null
    if (number < 0) return "-1"
    if (number > Int.MAX_VALUE.toLong()) return null
    return number.toInt().toString()
}

@Composable
private fun PortField(
    label: String,
    value: Int,
    ready: Boolean = true,
    onFocus: ((Boolean) -> Unit)? = null,
    onValue: (Int) -> Unit,
) {
    val storedPort = value
    LineField(
        label = label,
        value = value.toString(),
        keyboardType = KeyboardType.Number,
        ready = ready,
        onFocus = onFocus,
        interpret = { draft, stored ->
            commitPort(draft, stored.toIntOrNull() ?: storedPort)?.toString()
        },
        onCommit = { text ->
            val port = text.toIntOrNull() ?: return@LineField
            onValue(port)
        },
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (true) {
        if (current is Activity) return current
        if (current !is ContextWrapper) return null
        val next = current.baseContext
        if (next === current) return null
        current = next
    }
}

@Composable
private fun BoolField(
    label: String,
    value: Boolean,
    note: String? = null,
    enabled: Boolean = true,
    onValue: (Boolean) -> Unit,
) {
    val faded = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = if (note == null) {
            null
        } else {
            { Text(note) }
        },
        colors = if (enabled) {
            androidx.compose.material3.ListItemDefaults.colors()
        } else {
            androidx.compose.material3.ListItemDefaults.colors(
                headlineColor = faded,
                supportingColor = faded,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (enabled) {
                    Modifier.toggleable(value = value, role = Role.Switch, onValueChange = onValue)
                } else {
                    Modifier
                },
            ),
        trailingContent = {
            Switch(checked = value, onCheckedChange = null, enabled = enabled)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartRuleField(
    label: String,
    selected: StartRule,
    showRecent: Boolean,
    onSelect: (StartRule) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val options = startRuleChoices(showRecent, selected)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = startRuleName(selected),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(type = MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ObscuredTouchFilter()
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(startRuleName(option))
                            if (startRuleIsRecent(option)) Text(stringResource(R.string.label_recent_note))
                        }
                    },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
    if (startRuleIsRecent(selected)) Text(stringResource(R.string.label_recent_note))
}

@Composable
private fun threadIndexStyleName(style: ThreadIndexStyle): String = stringResource(
    when (style) {
        ThreadIndexStyle.Expanded -> R.string.settings_thread_expanded
        ThreadIndexStyle.Collapsed -> R.string.settings_thread_collapsed
    },
)

@Composable
private fun sortKeyName(key: SortKey): String = stringResource(
    when (key) {
        SortKey.Arrival -> R.string.label_arrival
        SortKey.Date -> R.string.label_date
        SortKey.From -> R.string.label_from
        SortKey.Subject -> R.string.compose_subject
        SortKey.To -> R.string.compose_to
        SortKey.Cc -> R.string.compose_cc
        SortKey.Size -> R.string.label_size
        SortKey.ThreadReferences -> R.string.label_thread
        SortKey.ThreadOrderedSubject -> R.string.label_ordered_subject
    },
)

@Composable
private fun startRuleName(rule: StartRule): String = stringResource(
    when (rule) {
        StartRule.FirstUnseen -> R.string.label_first_unread
        StartRule.FirstRecent -> R.string.label_first_recent
        StartRule.FirstImportant -> R.string.label_first_important
        StartRule.FirstImportantOrUnseen -> R.string.label_first_important_unread
        StartRule.FirstImportantOrRecent -> R.string.label_first_important_recent
        StartRule.First -> R.string.label_top
        StartRule.Last -> R.string.label_bottom
        StartRule.Newest -> R.string.label_newest_message
    },
)

@Composable
private fun startAfterChangeName(value: StartAfterChange): String = stringResource(
    when (value) {
        StartAfterChange.RerunRule -> R.string.label_rerun_rule
        StartAfterChange.KeepTopVisible -> R.string.label_keep_top
    },
)

@Composable
private fun pinercStartDefaultName(value: PinercStartDefault): String = stringResource(
    when (value) {
        PinercStartDefault.LeaveUnchanged -> R.string.label_leave_unchanged
        PinercStartDefault.AlpineDefault -> R.string.label_alpine_default
    },
)

@Composable
private fun swipeActionName(action: SwipeAction): String = stringResource(
    when (action) {
        SwipeAction.Delete -> R.string.drawer_delete
        SwipeAction.Move -> R.string.label_move
        SwipeAction.Reply -> R.string.compose_reply
        SwipeAction.ReplyAll -> R.string.compose_reply_all
        SwipeAction.SetFlag -> R.string.label_set_flag
        SwipeAction.ClearFlag -> R.string.label_clear_flag
        SwipeAction.FlagScreen -> R.string.label_flag_screen
        SwipeAction.Next -> R.string.reader_next
        SwipeAction.Previous -> R.string.reader_previous
    },
)

@Composable
private fun tlsName(mode: TlsMode): String = stringResource(
    when (mode) {
        TlsMode.None -> R.string.settings_tls_none
        TlsMode.StartTls -> R.string.settings_tls_starttls
        TlsMode.Implicit -> R.string.settings_tls_implicit
    },
)

@Composable
private fun themeName(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.Dark -> R.string.label_dark
        ThemeMode.Light -> R.string.label_light
        ThemeMode.FollowSystem -> R.string.label_system
    },
)

@Composable
private fun dateFormatName(format: DateFormat): String = stringResource(
    when (format) {
        DateFormat.Local -> R.string.label_local
        DateFormat.Short -> R.string.label_short
        DateFormat.Relative -> R.string.label_relative
        DateFormat.Custom -> R.string.label_custom
    },
)

@Composable
private fun multiPaneName(mode: MultiPane): String = stringResource(
    when (mode) {
        MultiPane.Off -> R.string.settings_multi_pane_off
        MultiPane.Wide -> R.string.settings_multi_pane_wide
        MultiPane.Auto -> R.string.settings_pane_auto
    },
)

@Composable
private fun layoutChoiceName(choice: LayoutChoice): String = stringResource(
    when (choice) {
        LayoutChoice.Off -> R.string.settings_multi_pane_off
        LayoutChoice.On -> R.string.settings_multi_pane_wide
        LayoutChoice.Auto -> R.string.settings_pane_auto
    },
)

@Composable
private fun densityName(density: Density): String = stringResource(
    when (density) {
        Density.Compact -> R.string.label_compact
        Density.Medium -> R.string.label_comfortable
        Density.Large -> R.string.label_large
    },
)

@Composable
private fun bodyViewName(view: BodyView): String = stringResource(
    when (view) {
        BodyView.PlainOrError -> R.string.label_plain
        BodyView.PlainOrHtml -> R.string.label_html
        BodyView.PlainOrText -> R.string.label_html_text
        BodyView.Headers -> R.string.label_headers
        BodyView.Raw -> R.string.label_raw
    },
)

@Composable
private fun deletePolicyName(policy: DeletePolicy): String = stringResource(
    when (policy) {
        DeletePolicy.MarkDeleted -> R.string.settings_mark_deleted
        DeletePolicy.MoveToTrash -> R.string.settings_move_to_trash
        DeletePolicy.DeletePermanently -> R.string.settings_delete_permanently
    },
)

@Composable
private fun compressionName(mode: Compression): String = stringResource(
    when (mode) {
        Compression.On -> R.string.settings_compression_on
        Compression.Off -> R.string.settings_compression_off
        Compression.Auto -> R.string.settings_compression_auto
    },
)

@Composable
private fun moveMethodName(method: MoveMethod): String = stringResource(
    when (method) {
        MoveMethod.CopyThenMarkDeleted -> R.string.settings_copy_then_mark
        MoveMethod.ImapMove -> R.string.settings_imap_move
    },
)

private data class ReasonOption<T>(
    val value: T,
    val enabled: Boolean,
    val reason: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ReasonChoice(
    label: String,
    options: List<ReasonOption<T>>,
    selected: T,
    name: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = name(selected),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(type = MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ObscuredTouchFilter()
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(name(option.value))
                            val reason = option.reason
                            if (reason != null) {
                                Text(
                                    reason,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    },
                    enabled = option.enabled,
                    onClick = {
                        onSelect(option.value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceField(
    label: String,
    options: List<T>,
    selected: T,
    name: @Composable (T) -> String,
    enabled: Boolean = true,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val faded = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = name(selected),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            enabled = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = if (enabled) {
                OutlinedTextFieldDefaults.colors()
            } else {
                OutlinedTextFieldDefaults.colors(
                    focusedTextColor = faded,
                    unfocusedTextColor = faded,
                    focusedLabelColor = faded,
                    unfocusedLabelColor = faded,
                )
            },
            modifier = Modifier
                .menuAnchor(type = MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ObscuredTouchFilter()
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(name(option)) },
                    onClick = {
                        if (enabled) onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SwipeEditor(
    label: String,
    binding: SwipeBinding,
    onChooseMove: () -> Unit,
    actions: List<SwipeAction>,
    onChange: (SwipeBinding) -> Unit,
) {
    Text(label)
    ChoiceField(stringResource(R.string.settings_action), actions, binding.action, { swipeActionName(it) }) { action ->
        onChange(binding.copy(action = action))
    }
    if (binding.action == SwipeAction.Move) {
        MailboxLine(stringResource(R.string.settings_move_mailbox), binding.moveMailbox, onChooseMove) {
            onChange(binding.copy(moveMailbox = it))
        }
    }
    if (binding.action == SwipeAction.SetFlag || binding.action == SwipeAction.ClearFlag) {
        LineField(stringResource(R.string.settings_flag), binding.flag) { onChange(binding.copy(flag = it)) }
    }
}

@Composable
private fun MailboxLine(
    label: String,
    value: String,
    onChoose: () -> Unit,
    onValue: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            LineField(label, value, onCommit = onValue)
        }
        TextButton(onClick = onChoose) { Text(stringResource(R.string.settings_choose)) }
    }
}

/** Extra or global Alpine address books: one row per book with Remove, and Add (mailbox picker). */
@Composable
private fun BookList(label: String, books: List<String>, onAdd: () -> Unit, onRemove: (String) -> Unit) {
    Text(label)
    for (book in books) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(book, modifier = Modifier.weight(1f))
            TextButton(onClick = { onRemove(book) }) { Text(stringResource(R.string.settings_book_remove)) }
        }
    }
    TextButton(onClick = onAdd) { Text(stringResource(R.string.settings_book_add)) }
}

private enum class MailboxPick {
    Sent,
    Postponed,
    AddressBook,
    ExtraBook,
    GlobalBook,
    Spam,
    Trash,
    Saved,
    LastSave,
    TrailingMove,
    LeadingMove,
    ReaderTrailingMove,
    ReaderLeadingMove,
}

private fun assignMailbox(base: AccountSettings, field: MailboxPick, mailbox: String): AccountSettings {
    return when (field) {
        MailboxPick.Sent -> base.copy(sentMailbox = mailbox)
        MailboxPick.Postponed -> base.copy(postponedMailbox = mailbox)
        MailboxPick.AddressBook -> base.copy(addressBookMailbox = mailbox)
        MailboxPick.ExtraBook -> if (mailbox in base.extraAddressBooks || mailbox == base.addressBookMailbox) base
            else base.copy(extraAddressBooks = base.extraAddressBooks + mailbox, globalAddressBooks = base.globalAddressBooks - mailbox)
        MailboxPick.GlobalBook -> if (mailbox in base.globalAddressBooks || mailbox == base.addressBookMailbox) base
            else base.copy(globalAddressBooks = base.globalAddressBooks + mailbox, extraAddressBooks = base.extraAddressBooks - mailbox)
        MailboxPick.Spam -> base.copy(spamMailbox = mailbox)
        MailboxPick.Trash -> base.copy(trashMailbox = mailbox)
        MailboxPick.Saved -> base.copy(savedMailbox = mailbox)
        MailboxPick.LastSave -> base.copy(lastSaveMailbox = mailbox)
        MailboxPick.TrailingMove -> base.copy(
            swipeTrailing = base.swipeTrailing.copy(moveMailbox = mailbox),
        )
        MailboxPick.LeadingMove -> base.copy(
            swipeLeading = base.swipeLeading.copy(moveMailbox = mailbox),
        )
        MailboxPick.ReaderTrailingMove -> base.copy(
            readerSwipeTrailing = base.readerSwipeTrailing.copy(moveMailbox = mailbox),
        )
        MailboxPick.ReaderLeadingMove -> base.copy(
            readerSwipeLeading = base.readerSwipeLeading.copy(moveMailbox = mailbox),
        )
    }
}

private const val PINERC_MAX_BYTES = 1024 * 1024

private sealed class PinercRead {
    class Ok(val text: String) : PinercRead()
    object TooLarge : PinercRead()
    object Bad : PinercRead()
}

private fun readPinercStream(resolver: ContentResolver, uri: Uri): PinercRead {
    return try {
        val input = resolver.openInputStream(uri) ?: return PinercRead.Bad
        input.use { stream ->
            val buf = ByteArray(PINERC_MAX_BYTES + 1)
            var total = 0
            while (total < buf.size) {
                val n = stream.read(buf, total, buf.size - total)
                if (n < 0) break
                total += n
            }
            if (total > PINERC_MAX_BYTES) return PinercRead.TooLarge
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            try {
                PinercRead.Ok(decoder.decode(ByteBuffer.wrap(buf, 0, total)).toString())
            } catch (e: CharacterCodingException) {
                PinercRead.Bad
            }
        }
    } catch (e: IOException) {
        PinercRead.Bad
    } catch (e: SecurityException) {
        PinercRead.Bad
    }
}


@Composable
private fun sendingRulesSummary(settings: AccountSettings): String {
    val count = org.dlang.liveimap.ui.compose.decodeRules(settings.sendingRules).size
    return stringResource(R.string.settings_rules_summary, rulesSummaryWho(settings), count)
}

/** The account's ordered sending rules and presets. */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SendingRulesRows(editor: SettingsEditor) {
    val settings = editor.settings
    val rules = org.dlang.liveimap.ui.compose.decodeRules(settings.sendingRules)
    fun save(list: List<org.dlang.liveimap.ui.compose.SendingRule>) =
        editor.persist(editor.settings.copy(sendingRules = org.dlang.liveimap.ui.compose.encodeRules(list)))
    LaunchedEffect(rules.map { it.id }) {
        if (rules.any { it.id.isBlank() }) {
            save(org.dlang.liveimap.ui.compose.assignSendingIds(rules))
        }
    }
    var editing by remember { mutableStateOf<Int?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var accounts by remember { mutableStateOf<List<AccountChoice>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        accounts = DataStoreSettingsStore(context.applicationContext).listAccounts()
    }

    Text(stringResource(R.string.settings_rules_list))
    rules.forEachIndexed { index, rule ->
        ListItem(
            headlineContent = { Text("${index + 1}. ${rule.name}") },
            supportingContent = { Text(ruleSummary(rule)) },
            modifier = Modifier.clickable { editing = if (editing == index) null else index },
        )
        Row {
            TextButton(enabled = index > 0, onClick = {
                val list = org.dlang.liveimap.ui.compose.decodeRules(editor.settings.sendingRules)
                if (index in list.indices) save(list.toMutableList().also { it.add(index - 1, it.removeAt(index)) })
                editing = null
            }) { Text(stringResource(R.string.settings_rule_up)) }
            TextButton(enabled = index < rules.size - 1, onClick = {
                val list = org.dlang.liveimap.ui.compose.decodeRules(editor.settings.sendingRules)
                if (index in list.indices) save(list.toMutableList().also { it.add(index + 1, it.removeAt(index)) })
                editing = null
            }) { Text(stringResource(R.string.settings_rule_down)) }
            TextButton(onClick = {
                val list = org.dlang.liveimap.ui.compose.decodeRules(editor.settings.sendingRules)
                if (index in list.indices) save(list.filterIndexed { i, _ -> i != index })
                editing = null
            }) {
                Text(stringResource(R.string.settings_rule_delete))
            }
        }
        if (editing == index) RuleEditor(rule, editor.ready, accounts) { edit ->
            val current = org.dlang.liveimap.ui.compose.decodeRules(editor.settings.sendingRules)
            save(org.dlang.liveimap.ui.compose.patchSendingRule(current, rule.id, edit))
        }
    }
    Text(stringResource(R.string.settings_rules_presets))
    val me = settings.email
    FlowRow {
        fun add(new: List<org.dlang.liveimap.ui.compose.SendingRule>) {
            if (new.isEmpty()) return
            val current = org.dlang.liveimap.ui.compose.decodeRules(editor.settings.sendingRules)
            save(org.dlang.liveimap.ui.compose.assignSendingIds(current + new))
            editing = current.size
        }
        val p = org.dlang.liveimap.ui.compose.RulePresets
        AssistChip(onClick = { add(p.replyFromSentTo(settings.altAddresses.ifEmpty { listOf(me) })) },
            label = { Text(stringResource(R.string.settings_preset_sent_to)) })
        AssistChip(onClick = { add(listOf(p.perRecipient("@example.com", me))) },
            label = { Text(stringResource(R.string.settings_preset_recipient)) })
        AssistChip(onClick = { add(listOf(p.perFolder("Work*", me, null, null))) },
            label = { Text(stringResource(R.string.settings_preset_folder)) })
        AssistChip(onClick = { add(listOf(p.mailingList("list.example.org", me))) },
            label = { Text(stringResource(R.string.settings_preset_list)) })
        AssistChip(onClick = { add(listOf(p.plusFolder(me, "tag"))) },
            label = { Text(stringResource(R.string.settings_preset_plus)) })
        AssistChip(onClick = { add(listOf(org.dlang.liveimap.ui.compose.SendingRule(
            "Rule ${rules.size + 1}")) ) },
            label = { Text(stringResource(R.string.settings_preset_blank)) })
    }
}

@Composable
private fun dsnLabel(mode: org.dlang.liveimap.ui.compose.DsnMode): String = stringResource(
    when (mode) {
        org.dlang.liveimap.ui.compose.DsnMode.Off -> R.string.settings_dsn_off
        org.dlang.liveimap.ui.compose.DsnMode.Failure -> R.string.settings_dsn_failure
        org.dlang.liveimap.ui.compose.DsnMode.FailureDelay -> R.string.settings_dsn_delay
        org.dlang.liveimap.ui.compose.DsnMode.All -> R.string.settings_dsn_all
    },
)

private fun ruleSummary(rule: org.dlang.liveimap.ui.compose.SendingRule): String {
    val c = rule.conditions
    val a = rule.actions
    val when_ = listOfNotNull(
        c.kinds.takeIf { it.isNotEmpty() }?.joinToString("/") { it.code },
        c.recipient.takeIf { it.isNotEmpty() }?.let { "to " + it.joinToString(",") },
        c.sentTo.takeIf { it.isNotEmpty() }?.let { "sent to " + it.joinToString(",") },
        c.listId.takeIf { it.isNotEmpty() }?.let { "list $it" },
        c.folder.takeIf { it.isNotEmpty() }?.let { "folder $it" },
        c.originalFrom.takeIf { it.isNotEmpty() }?.let { "from " + it.joinToString(",") },
    ).ifEmpty { listOf("always") }.joinToString(", ")
    val then = listOfNotNull(
        a.fromAddress?.let { "From $it" }, a.replyTo?.let { "Reply-To $it" },
        a.signature?.let { "signature" }, a.fcc?.let { "Fcc $it" }, a.dsn?.let { "DSN ${it.code}" },
        a.sendAccount?.let { "send via another account" }, a.replyToList?.takeIf { it }?.let { "reply to list" },
    ).joinToString(", ")
    return "$when_ → $then"
}

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
/** Field-by-field editor for one rule; list fields are comma separated, empty means "not set". */
@Composable
private fun RuleEditor(
    rule: org.dlang.liveimap.ui.compose.SendingRule,
    ready: Boolean,
    accounts: List<AccountChoice>,
    onPatch: ((org.dlang.liveimap.ui.compose.SendingRule) -> org.dlang.liveimap.ui.compose.SendingRule) -> Unit,
) {
    fun list(t: String) = t.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    fun opt(t: String) = t.ifBlank { null }
    val c = rule.conditions
    val a = rule.actions
    LineField(stringResource(R.string.settings_rule_name), rule.name, ready = ready) { text ->
        onPatch { latest -> latest.copy(name = text.ifBlank { latest.name }) }
    }
    LineField(stringResource(R.string.settings_rule_recipient), c.recipient.joinToString(", "), ready = ready) { text ->
        onPatch { latest -> latest.copy(conditions = latest.conditions.copy(recipient = list(text))) }
    }
    LineField(stringResource(R.string.settings_rule_sent_to), c.sentTo.joinToString(", "), ready = ready) { text ->
        onPatch { latest -> latest.copy(conditions = latest.conditions.copy(sentTo = list(text))) }
    }
    LineField(stringResource(R.string.settings_rule_list_id), c.listId, ready = ready) { text ->
        onPatch { latest -> latest.copy(conditions = latest.conditions.copy(listId = text.trim())) }
    }
    LineField(stringResource(R.string.settings_rule_folder), c.folder, ready = ready) { text ->
        onPatch { latest -> latest.copy(conditions = latest.conditions.copy(folder = text.trim())) }
    }
    LineField(stringResource(R.string.settings_rule_orig_from), c.originalFrom.joinToString(", "), ready = ready) { text ->
        onPatch { latest -> latest.copy(conditions = latest.conditions.copy(originalFrom = list(text))) }
    }
    LineField(stringResource(R.string.settings_rule_from_name), a.fromName.orEmpty(), ready = ready) { text ->
        onPatch { latest -> latest.copy(actions = latest.actions.copy(fromName = opt(text))) }
    }
    LineField(stringResource(R.string.settings_rule_from), a.fromAddress.orEmpty(), KeyboardType.Email, ready = ready) { text ->
        onPatch { latest -> latest.copy(actions = latest.actions.copy(fromAddress = opt(text))) }
    }
    LineField(stringResource(R.string.settings_rule_reply_to), a.replyTo.orEmpty(), KeyboardType.Email, ready = ready) { text ->
        onPatch { latest -> latest.copy(actions = latest.actions.copy(replyTo = opt(text))) }
    }
    LineField(stringResource(R.string.settings_signature), a.signature.orEmpty(), singleLine = false, ready = ready) { text ->
        onPatch { latest -> latest.copy(actions = latest.actions.copy(signature = opt(text))) }
    }
    LineField(stringResource(R.string.settings_rule_fcc), a.fcc.orEmpty(), ready = ready) { text ->
        onPatch { latest -> latest.copy(actions = latest.actions.copy(fcc = opt(text))) }
    }
    BoolField(stringResource(R.string.settings_rule_reply_list), a.replyToList == true) { enabled ->
        onPatch { latest -> latest.copy(actions = latest.actions.copy(replyToList = if (enabled) true else null)) }
    }
    Text(stringResource(R.string.settings_rule_send_account))
    FlowRow {
        FilterChip(
            selected = a.sendAccount == null,
            onClick = { onPatch { latest -> latest.copy(actions = latest.actions.copy(sendAccount = null)) } },
            label = { Text(stringResource(R.string.settings_rule_send_this)) },
        )
        for (choice in accounts) {
            FilterChip(
                selected = a.sendAccount == choice.id,
                onClick = { onPatch { latest -> latest.copy(actions = latest.actions.copy(sendAccount = choice.id)) } },
                label = { Text(choice.name) },
            )
        }
    }
}

/** Who the Sending rules summary names: From as composed, else the login, else the host. Never blank. */
internal fun rulesSummaryWho(settings: AccountSettings): String =
    formatMailbox(settings.displayName, settings.email).trim()
        .ifEmpty { settings.username.trim() }
        .ifEmpty { settings.imapHost.trim() }
        .ifEmpty { "?" }


internal enum class SpecialFolder { Sent, Postponed }

/** The special folders a leave warning names: unset ones only. A warning, never a block (David's Sent rule d). */
internal fun unsetSpecialFolders(settings: AccountSettings): List<SpecialFolder> = buildList {
    if (settings.sentMailbox.isBlank()) add(SpecialFolder.Sent)
    if (settings.postponedMailbox.isBlank()) add(SpecialFolder.Postponed)
}
