package org.dlang.liveimap.settings

import android.app.Activity
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dlang.liveimap.BuildConfig
import org.dlang.liveimap.R
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.engine.probeServer
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.ui.contacts.AndroidContactSet
import org.dlang.liveimap.ui.contacts.androidContactSetsOnce
import org.dlang.liveimap.ui.contacts.hasReadContacts
import org.dlang.liveimap.ui.contacts.moveCompletionSource
import org.dlang.liveimap.ui.contacts.withSourceEnabled
import org.dlang.liveimap.ui.debug.DebugReportReview
import org.dlang.liveimap.ui.folder.MailboxChooser

private val settingsIo = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
private val settingsMutex = Mutex()

enum class SettingsGroup(val route: String, private val titleRes: Int) {
    Account("account", R.string.settings_account),
    Mailboxes("mailboxes", R.string.settings_mailboxes),
    Folders("folders", R.string.settings_folders),
    Reading("reading", R.string.settings_reading),
    Compose("compose", R.string.compose_new),
    Appearance("appearance", R.string.settings_appearance),
    Debug("debug", R.string.settings_debug),
    ;

    val title: String
        @Composable get() = stringResource(titleRes)

    companion object {
        fun fromRoute(route: String): SettingsGroup? = entries.firstOrNull { it.route == route }
    }
}

private class SettingsEditor(
    val store: DataStoreSettingsStore,
    val appContext: Context,
    val ui: CoroutineScope,
) {
    val settingsState = mutableStateOf(AccountSettings())
    val passwordState = mutableStateOf("")
    var ready by mutableStateOf(false)
    var settings by settingsState
    var password by passwordState

    fun persist(next: AccountSettings) {
        if (!ready) return
        settings = next
        settingsIo.launch {
            settingsMutex.withLock { store.save(settingsState.value) }
        }
    }

    fun persistPassword(value: String) {
        if (!ready) return
        password = value
        settingsIo.launch {
            settingsMutex.withLock { store.setPassword(passwordState.value) }
        }
    }

    fun load() {
        settingsIo.launch {
            settingsMutex.withLock {
                settings = store.load()
                password = store.password()
                ready = true
            }
        }
    }
}

@Composable
private fun rememberSettingsEditor(): SettingsEditor {
    val appContext = LocalContext.current.applicationContext
    val ui = rememberCoroutineScope()
    val store = remember { DataStoreSettingsStore(appContext) }
    val editor = remember(store) { SettingsEditor(store, appContext, ui) }
    LaunchedEffect(store) { editor.load() }
    return editor
}

private class SectionOpen(private val selected: MutableState<String>) {
    val open: Set<String>
        get() = if (selected.value.isEmpty()) emptySet() else setOf(selected.value)

    fun toggle(id: String) {
        selected.value = if (id in open) "" else id
    }
}

@Composable
private fun rememberSectionOpen(first: String): SectionOpen {
    val selected = rememberSaveable { mutableStateOf(first) }
    return remember(selected) { SectionOpen(selected) }
}

@Composable
fun SettingsGroupList(onOpen: (SettingsGroup) -> Unit) {
    val editor = rememberSettingsEditor()
    if (!editor.ready) return
    val settings = editor.settings
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        SettingsGroup.entries.forEach { group ->
            ListItem(
                headlineContent = { Text(group.title) },
                supportingContent = { Text(groupSummary(group, settings)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(group) },
            )
        }
    }
}

@Composable
fun SettingsGroupScreen(
    group: SettingsGroup,
    onOpenExpanded: () -> Unit,
    onOpenViews: () -> Unit,
    onOpenStarts: () -> Unit,
    onCopyContacts: () -> Unit,
) {
    val editor = rememberSettingsEditor()
    if (!editor.ready) return
    when (group) {
        SettingsGroup.Account -> AccountGroup(editor)
        SettingsGroup.Mailboxes -> MailboxesGroup(editor)
        SettingsGroup.Folders -> FoldersGroup(editor, onOpenExpanded, onOpenViews, onOpenStarts)
        SettingsGroup.Reading -> ReadingGroup(editor)
        SettingsGroup.Compose -> ComposeGroup(editor, onCopyContacts)
        SettingsGroup.Appearance -> AppearanceGroup(editor)
        SettingsGroup.Debug -> DebugGroup(editor)
    }
}

@Composable
fun ExpandedFoldersScreen() {
    val editor = rememberSettingsEditor()
    if (!editor.ready) return
    var picking by remember { mutableStateOf(false) }
    val settings = editor.settings
    SettingsPage {
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
            store = editor.store,
            saveMutex = settingsMutex,
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
    var picking by remember { mutableStateOf(false) }
    var draftSort by remember { mutableStateOf(SortKey.Arrival) }
    var draftNewest by remember { mutableStateOf(true) }
    val settings = editor.settings
    SettingsPage {
        settings.folderViews.forEach { (mailbox, view) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        R.string.settings_view_line,
                        mailbox,
                        sortKeyLabel(view.key),
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
            { sortKeyLabel(it) },
        ) { draftSort = it }
        BoolField(stringResource(R.string.settings_folder_view_newest), draftNewest) { draftNewest = it }
        TextButton(onClick = { picking = true }) { Text(stringResource(R.string.settings_add)) }
    }
    if (picking) {
        MailboxChooser(
            store = editor.store,
            saveMutex = settingsMutex,
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
    var draftStartMailbox by remember { mutableStateOf("") }
    var draftStartRule by remember { mutableStateOf(StartRule.Newest) }
    val settings = editor.settings
    SettingsPage {
        settings.folderStarts.forEach { (mailbox, rule) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_mailbox_rule, mailbox, startRuleLabel(rule)))
                    if (startRuleIsRecent(rule)) Text(recentRuleNote)
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

@Composable
private fun AccountGroup(editor: SettingsEditor) {
    val sections = rememberSectionOpen("server")
    val settings = editor.settings
    var probing by remember { mutableStateOf(false) }
    var serverReport by remember { mutableStateOf<List<String>>(emptyList()) }
    var importPreview by remember { mutableStateOf<PinercPreview?>(null) }
    var importError by remember { mutableStateOf<Int?>(null) }
    val settingsState = editor.settingsState
    val contextState = rememberUpdatedState(LocalContext.current)
    val openPinerc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        when (val outcome = readPinercStream(contextState.value.contentResolver, uri)) {
            is PinercRead.Ok -> {
                importError = null
                importPreview = pinercPreview(outcome.text, settingsState.value)
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
    SettingsPage {
        SettingsSection(
            title = stringResource(R.string.settings_server),
            summary = hostAndPort(
                settings.imapHost,
                settings.imapPort,
                stringResource(R.string.settings_no_server),
            ),
            expanded = "server" in sections.open,
            onToggle = { sections.toggle("server") },
        ) {
            Text(stringResource(R.string.settings_plaintext_ports))
            LineField(stringResource(R.string.settings_imap_host), settings.imapHost, ready = editor.ready) {
                editor.persist(editor.settings.copy(imapHost = it))
            }
            PortField(stringResource(R.string.settings_imap_port), settings.imapPort, ready = editor.ready) {
                editor.persist(editor.settings.copy(imapPort = it))
            }
        }
        val noUser = stringResource(R.string.settings_no_user)
        SettingsSection(
            title = stringResource(R.string.settings_identity),
            summary = settings.username.ifBlank { noUser },
            expanded = "identity" in sections.open,
            onToggle = { sections.toggle("identity") },
        ) {
            LineField(stringResource(R.string.settings_friendly_name), settings.friendlyName, ready = editor.ready) {
                editor.persist(editor.settings.copy(friendlyName = it))
            }
            LineField(stringResource(R.string.settings_username), settings.username, ready = editor.ready) { draft ->
                editor.persist(
                    editor.settings.copy(
                        username = draft,
                        email = emailDefaultedFromUsername(draft, editor.settings.email),
                    ),
                )
            }
            LineField(
                stringResource(R.string.settings_password),
                editor.password,
                KeyboardType.Password,
                password = true,
                ready = editor.ready,
            ) { editor.persistPassword(it) }
            LineField(stringResource(R.string.settings_email), settings.email, KeyboardType.Email, ready = editor.ready) {
                editor.persist(editor.settings.copy(email = it))
            }
            LineField(stringResource(R.string.settings_display_name), settings.displayName, ready = editor.ready) {
                editor.persist(editor.settings.copy(displayName = it))
            }
        }
        SettingsSection(
            title = stringResource(R.string.settings_sending),
            summary = hostAndPort(
                settings.smtpHost,
                settings.smtpPort,
                stringResource(R.string.settings_no_smtp),
            ),
            expanded = "sending" in sections.open,
            onToggle = { sections.toggle("sending") },
        ) {
            LineField(stringResource(R.string.settings_smtp_host), settings.smtpHost, ready = editor.ready) {
                editor.persist(editor.settings.copy(smtpHost = it))
            }
            PortField(stringResource(R.string.settings_smtp_port), settings.smtpPort, ready = editor.ready) {
                editor.persist(editor.settings.copy(smtpPort = it))
            }
        }
        SettingsSection(
            title = stringResource(R.string.settings_import),
            summary = stringResource(R.string.settings_import_pinerc),
            expanded = "import" in sections.open,
            onToggle = { sections.toggle("import") },
        ) {
            TextButton(onClick = { openPinerc.launch(arrayOf("text/plain", "*/*")) }) {
                Text(stringResource(R.string.settings_import_pinerc))
            }
        }
        SettingsSection(
            title = stringResource(R.string.settings_advanced),
            summary = stringResource(R.string.settings_test_server),
            expanded = "advanced" in sections.open,
            onToggle = { sections.toggle("advanced") },
        ) {
            TextButton(
                onClick = {
                    if (!probing) {
                        val account = editor.settings
                        probing = true
                        editor.ui.launch {
                            val session = mailSession()
                            try {
                                serverReport = probeServer(session, account)
                            } finally {
                                probing = false
                            }
                        }
                    }
                },
                enabled = !probing,
            ) {
                Text(
                    stringResource(
                        if (probing) R.string.settings_testing else R.string.settings_test_server,
                    ),
                )
            }
            for (line in serverReport) {
                Text(text = line, fontFamily = FontFamily.Monospace)
            }
        }
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
        Dialog(
            onDismissRequest = { importPreview = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
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
                                editor.persist(preview.next)
                                importPreview = null
                            },
                            enabled = preview.next != editor.settings,
                        ) { Text(stringResource(R.string.settings_apply)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MailboxesGroup(editor: SettingsEditor) {
    val sections = rememberSectionOpen("special")
    val settings = editor.settings
    var picking by remember { mutableStateOf<MailboxPick?>(null) }
    BackHandler(enabled = picking != null) { picking = null }
    SettingsPage {
        SettingsSection(
            title = stringResource(R.string.settings_special_use),
            summary = mailboxSetCount(settings),
            expanded = "special" in sections.open,
            onToggle = { sections.toggle("special") },
        ) {
            MailboxLine(stringResource(R.string.settings_sent_mailbox), settings.sentMailbox, { picking = MailboxPick.Sent }) {
                editor.persist(editor.settings.copy(sentMailbox = it))
            }
            MailboxLine(stringResource(R.string.settings_postponed_mailbox), settings.postponedMailbox, { picking = MailboxPick.Postponed }) {
                editor.persist(editor.settings.copy(postponedMailbox = it))
            }
            MailboxLine(stringResource(R.string.settings_address_book_mailbox), settings.addressBookMailbox, { picking = MailboxPick.AddressBook }) {
                editor.persist(editor.settings.copy(addressBookMailbox = it))
            }
            MailboxLine(stringResource(R.string.settings_spam_mailbox), settings.spamMailbox, { picking = MailboxPick.Spam }) {
                editor.persist(editor.settings.copy(spamMailbox = it))
            }
        }
    }
    MailboxPickDialog(editor, picking) { picking = it }
}

@Composable
private fun FoldersGroup(
    editor: SettingsEditor,
    onOpenExpanded: () -> Unit,
    onOpenViews: () -> Unit,
    onOpenStarts: () -> Unit,
) {
    val sections = rememberSectionOpen("list")
    val settings = editor.settings
    SettingsPage {
        SettingsSection(
            title = stringResource(R.string.settings_folder_list),
            summary = pluralStringResource(
                R.plurals.settings_expanded,
                settings.expandedFolders.size,
                settings.expandedFolders.size,
            ),
            expanded = "list" in sections.open,
            onToggle = { sections.toggle("list") },
        ) {
            OpenRow(stringResource(R.string.settings_expanded_folders), onOpenExpanded)
        }
        SettingsSection(
            title = stringResource(R.string.settings_folder_views),
            summary = sortDirection(settings),
            expanded = "views" in sections.open,
            onToggle = { sections.toggle("views") },
        ) {
            ChoiceField(
                stringResource(R.string.settings_default_view),
                SortKey.entries,
                settings.defaultView.key,
                { sortKeyLabel(it) },
            ) { key ->
                editor.persist(editor.settings.copy(defaultView = editor.settings.defaultView.copy(key = key)))
            }
            BoolField(stringResource(R.string.reader_newest), settings.defaultView.newestFirst) { newest ->
                editor.persist(editor.settings.copy(defaultView = editor.settings.defaultView.copy(newestFirst = newest)))
            }
            OpenRow(stringResource(R.string.settings_folder_views), onOpenViews)
        }
        SettingsSection(
            title = stringResource(R.string.settings_start_position),
            summary = stringResource(R.string.settings_inbox_line, startRuleLabel(settings.inboxStart)),
            expanded = "start" in sections.open,
            onToggle = { sections.toggle("start") },
        ) {
            StartRuleField(stringResource(R.string.settings_inbox_opens), settings.inboxStart, settings.showRecentRules) { rule ->
                editor.persist(editor.settings.copy(inboxStart = rule))
            }
            StartRuleField(stringResource(R.string.settings_other_opens), settings.folderStart, settings.showRecentRules) { rule ->
                editor.persist(editor.settings.copy(folderStart = rule))
            }
            ChoiceField(
                stringResource(R.string.settings_after_change),
                StartAfterChange.entries,
                settings.startAfterChange,
                { startAfterChangeLabel(it) },
            ) { value ->
                editor.persist(editor.settings.copy(startAfterChange = value))
            }
            BoolField(stringResource(R.string.settings_show_recent), settings.showRecentRules) { enabled ->
                editor.persist(editor.settings.copy(showRecentRules = enabled))
            }
            BoolField(stringResource(R.string.settings_open_at_menu), settings.openAtInIndexMenu) { enabled ->
                editor.persist(editor.settings.copy(openAtInIndexMenu = enabled))
            }
            ChoiceField(
                stringResource(R.string.settings_pinerc_missing),
                PinercStartDefault.entries,
                settings.pinercStartDefault,
                { pinercStartDefaultLabel(it) },
            ) { value ->
                editor.persist(editor.settings.copy(pinercStartDefault = value))
            }
            OpenRow(stringResource(R.string.settings_start_per_folder), onOpenStarts)
        }
    }
}

@Composable
private fun ReadingGroup(editor: SettingsEditor) {
    val sections = rememberSectionOpen("opening")
    val settings = editor.settings
    var picking by remember { mutableStateOf<MailboxPick?>(null) }
    var warnUnread by remember { mutableStateOf(false) }
    BackHandler(enabled = picking != null || warnUnread) {
        if (picking != null) picking = null else warnUnread = false
    }
    val leftToRight = LocalLayoutDirection.current == LayoutDirection.Ltr
    SettingsPage {
        SettingsSection(
            title = stringResource(R.string.settings_opening),
            summary = bodyViewLabel(settings.bodyView),
            expanded = "opening" in sections.open,
            onToggle = { sections.toggle("opening") },
        ) {
            BoolField(stringResource(R.string.settings_mark_seen), settings.markSeenOnOpen) {
                editor.persist(editor.settings.copy(markSeenOnOpen = it))
            }
            BoolField(stringResource(R.string.settings_show_deleted), settings.showDeleted) {
                editor.persist(editor.settings.copy(showDeleted = it))
            }
            ChoiceField(
                stringResource(R.string.settings_message_view),
                BodyView.entries,
                settings.bodyView,
                { bodyViewLabel(it) },
            ) { view ->
                editor.persist(editor.settings.copy(bodyView = view))
            }
            BoolField(stringResource(R.string.settings_monospace), settings.plainTextMonospace) {
                editor.persist(editor.settings.copy(plainTextMonospace = it))
            }
        }
        SettingsSection(
            title = stringResource(R.string.settings_deleting),
            summary = stringResource(
                if (settings.askBeforeExpunge) R.string.settings_ask_expunge else R.string.settings_expunge_without,
            ),
            expanded = "deleting" in sections.open,
            onToggle = { sections.toggle("deleting") },
        ) {
            BoolField(stringResource(R.string.settings_ask_expunge), settings.askBeforeExpunge) {
                editor.persist(editor.settings.copy(askBeforeExpunge = it))
            }
        }
        SettingsSection(
            title = stringResource(R.string.settings_counts),
            summary = stringResource(
                if (settings.showUnreadCounts) R.string.settings_unread_on else R.string.settings_unread_off,
            ),
            expanded = "counts" in sections.open,
            onToggle = { sections.toggle("counts") },
        ) {
            BoolField(stringResource(R.string.settings_show_unread), settings.showUnreadCounts || warnUnread) { enabled ->
                if (enabled) {
                    warnUnread = true
                } else {
                    warnUnread = false
                    editor.persist(editor.settings.copy(showUnreadCounts = false))
                }
            }
        }
        SettingsSection(
            title = stringResource(R.string.settings_actions),
            summary = stringResource(
                R.string.settings_swipe_line,
                swipeActionLabel(settings.swipeTrailing.action),
                swipeActionLabel(settings.swipeLeading.action),
            ),
            expanded = "actions" in sections.open,
            onToggle = { sections.toggle("actions") },
        ) {
            SwipeEditor(
                stringResource(if (leftToRight) R.string.settings_swipe_left else R.string.settings_swipe_right),
                settings.swipeTrailing,
                { picking = MailboxPick.TrailingMove },
            ) { editor.persist(editor.settings.copy(swipeTrailing = it)) }
            SwipeEditor(
                stringResource(if (leftToRight) R.string.settings_swipe_right else R.string.settings_swipe_left),
                settings.swipeLeading,
                { picking = MailboxPick.LeadingMove },
            ) { editor.persist(editor.settings.copy(swipeLeading = it)) }
            Text(stringResource(R.string.settings_message_bar))
            ReaderAction.entries.forEach { action ->
                BoolField(readerActionLabel(action), settings.readerBar.contains(action)) { enabled ->
                    val next = if (enabled) {
                        ReaderAction.entries.filter { it == action || editor.settings.readerBar.contains(it) }
                    } else {
                        editor.settings.readerBar.filterNot { it == action }
                    }
                    editor.persist(editor.settings.copy(readerBar = next))
                }
            }
        }
    }
    if (warnUnread) {
        AlertDialog(
            onDismissRequest = { warnUnread = false },
            text = {
                Text(stringResource(R.string.settings_unread_warn))
            },
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
    MailboxPickDialog(editor, picking) { picking = it }
}

@Composable
private fun ComposeGroup(editor: SettingsEditor, onCopyContacts: () -> Unit) {
    val sections = rememberSectionOpen("replying")
    val settings = editor.settings
    SettingsPage {
        SettingsSection(
            title = stringResource(R.string.settings_replying),
            summary = stringResource(
                if (settings.replyAboveQuote) R.string.settings_reply_above else R.string.settings_reply_below,
            ),
            expanded = "replying" in sections.open,
            onToggle = { sections.toggle("replying") },
        ) {
            BoolField(stringResource(R.string.settings_reply_above), settings.replyAboveQuote) {
                editor.persist(editor.settings.copy(replyAboveQuote = it))
            }
            LineField(
                stringResource(R.string.settings_alt_addresses),
                settings.altAddresses.joinToString(", "),
                ready = editor.ready,
            ) { text ->
                editor.persist(
                    editor.settings.copy(
                        altAddresses = text.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    ),
                )
            }
        }
        SettingsSection(
            title = stringResource(R.string.settings_forwarding),
            summary = forwardSummary(settings),
            expanded = "forwarding" in sections.open,
            onToggle = { sections.toggle("forwarding") },
        ) {
            BoolField(stringResource(R.string.settings_include_attachments), settings.includeForwardAttachments) {
                editor.persist(editor.settings.copy(includeForwardAttachments = it))
            }
            BoolField(stringResource(R.string.settings_forward_attachment), settings.forwardAsAttachment) {
                editor.persist(editor.settings.copy(forwardAsAttachment = it))
            }
        }
        SettingsSection(
            title = stringResource(R.string.settings_fcc),
            summary = stringResource(
                if (settings.bounceFcc) R.string.settings_bounce_fcc_on else R.string.settings_bounce_fcc_off,
            ),
            expanded = "fcc" in sections.open,
            onToggle = { sections.toggle("fcc") },
        ) {
            BoolField(stringResource(R.string.settings_bounce_fcc), settings.bounceFcc) {
                editor.persist(editor.settings.copy(bounceFcc = it))
            }
        }
        SettingsSection(
            title = stringResource(R.string.settings_completion),
            summary = completionSummary(settings),
            expanded = "address-completion" in sections.open,
            onToggle = { sections.toggle("address-completion") },
        ) {
            AddressCompletionRows(editor)
        }
        SettingsSection(
            title = stringResource(R.string.settings_address_book),
            summary = if (settings.addressBookNeverTrim) {
                stringResource(R.string.settings_never_trim)
            } else {
                stringResource(R.string.settings_history, settings.addressBookHistory)
            },
            expanded = "address-book" in sections.open,
            onToggle = { sections.toggle("address-book") },
        ) {
            LineField(
                stringResource(R.string.settings_history_label),
                settings.addressBookHistory.toString(),
                keyboardType = KeyboardType.Number,
                ready = editor.ready,
                interpret = { draft, _ ->
                    val text = draft.trim()
                    if (text.isEmpty() || text.any { !it.isDigit() }) {
                        null
                    } else {
                        text.toIntOrNull()?.toString()
                    }
                },
                onCommit = { text ->
                    val number = text.toIntOrNull()
                    if (number != null) editor.persist(editor.settings.copy(addressBookHistory = number))
                },
            )
            BoolField(stringResource(R.string.settings_never_trim), settings.addressBookNeverTrim) {
                editor.persist(editor.settings.copy(addressBookNeverTrim = it))
            }
            OpenRow(stringResource(R.string.settings_copy_contacts), onCopyContacts)
        }
    }
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
private fun AppearanceGroup(editor: SettingsEditor) {
    val sections = rememberSectionOpen("theme")
    val settings = editor.settings
    SettingsPage {
        SettingsSection(
            title = stringResource(R.string.settings_theme),
            summary = themeSummary(settings),
            expanded = "theme" in sections.open,
            onToggle = { sections.toggle("theme") },
        ) {
            ChoiceField(stringResource(R.string.settings_theme), ThemeMode.entries, settings.theme, { themeLabel(it) }) {
                editor.persist(editor.settings.copy(theme = it))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BoolField(stringResource(R.string.settings_dynamic_color), settings.dynamicColor) {
                    editor.persist(editor.settings.copy(dynamicColor = it))
                }
            }
        }
        SettingsSection(
            title = stringResource(R.string.settings_display),
            summary = stringResource(
                R.string.settings_display_line,
                densityLabel(settings.density),
                dateFormatLabel(settings.dateFormat),
            ),
            expanded = "display" in sections.open,
            onToggle = { sections.toggle("display") },
        ) {
            ChoiceField(stringResource(R.string.settings_density), Density.entries, settings.density, { densityLabel(it) }) {
                editor.persist(editor.settings.copy(density = it))
            }
            ChoiceField(
                stringResource(R.string.settings_date_format),
                DateFormat.entries,
                settings.dateFormat,
                { dateFormatLabel(it) },
            ) {
                editor.persist(editor.settings.copy(dateFormat = it))
            }
            if (settings.dateFormat == DateFormat.Custom) {
                LineField(stringResource(R.string.settings_date_pattern), settings.datePattern, ready = editor.ready) {
                    editor.persist(editor.settings.copy(datePattern = it))
                }
            }
        }
    }
}

@Composable
private fun DebugGroup(editor: SettingsEditor) {
    val sections = rememberSectionOpen("logging")
    val settings = editor.settings
    var reportLines by remember { mutableStateOf<List<String>?>(null) }
    val contextState = rememberUpdatedState(LocalContext.current)
    SettingsPage {
        SettingsSection(
            title = stringResource(R.string.settings_logging),
            summary = loggingSummary(settings),
            expanded = "logging" in sections.open,
            onToggle = { sections.toggle("logging") },
        ) {
            BoolField(stringResource(R.string.settings_pipeline), settings.pipelineCommands) {
                editor.persist(editor.settings.copy(pipelineCommands = it))
            }
            BoolField(stringResource(R.string.settings_log_traffic), settings.logImapTraffic) {
                editor.persist(editor.settings.copy(logImapTraffic = it))
            }
            Text(stringResource(R.string.settings_log_note))
            TextButton(onClick = {
                val log = TrafficLog.install(File(editor.appContext.cacheDir, "imap-traffic.log"))
                log.shareFile(contextState.value)
            }) { Text(stringResource(R.string.settings_share_log)) }
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
            BoolField(stringResource(R.string.settings_show_user), settings.showUserInDebugReport) {
                editor.persist(editor.settings.copy(showUserInDebugReport = it))
            }
        }
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
private fun MailboxPickDialog(
    editor: SettingsEditor,
    picking: MailboxPick?,
    onPicking: (MailboxPick?) -> Unit,
) {
    val field = picking ?: return
    MailboxChooser(
        store = editor.store,
        saveMutex = settingsMutex,
        onStored = { loaded ->
            editor.settings = editor.settings.copy(expandedFolders = loaded.expandedFolders)
        },
        onPick = { mailbox ->
            settingsIo.launch {
                settingsMutex.withLock {
                    val next = assignMailbox(editor.store.load(), field, mailbox)
                    editor.store.save(next)
                    editor.settings = next
                }
                onPicking(null)
            }
        },
        onDismiss = { onPicking(null) },
    )
}

@Composable
private fun SettingsPage(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        content()
    }
}

@Composable
private fun SettingsSection(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    rows: @Composable () -> Unit,
) {
    val description = stringResource(
        if (expanded) R.string.settings_expanded_state else R.string.settings_collapsed_state,
    )
    val headerModifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onToggle)
        .semantics { stateDescription = description }
    if (expanded) {
        ListItem(
            headlineContent = { Text(title) },
            modifier = headerModifier,
        )
        rows()
    } else {
        ListItem(
            headlineContent = { Text(title) },
            supportingContent = { Text(summary) },
            modifier = headerModifier,
        )
    }
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

@Composable
private fun groupSummary(group: SettingsGroup, settings: AccountSettings): String = when (group) {
    SettingsGroup.Account -> {
        val noServer = stringResource(R.string.settings_no_server)
        settings.imapHost.ifBlank { noServer }
    }
    SettingsGroup.Mailboxes -> mailboxSetCount(settings)
    SettingsGroup.Folders -> sortDirection(settings)
    SettingsGroup.Reading -> bodyViewLabel(settings.bodyView)
    SettingsGroup.Compose -> stringResource(
        if (settings.replyAboveQuote) R.string.settings_reply_above else R.string.settings_reply_below,
    )
    SettingsGroup.Appearance -> themeLabel(settings.theme)
    SettingsGroup.Debug -> stringResource(
        if (settings.logImapTraffic) R.string.settings_traffic_on else R.string.settings_traffic_off,
    )
}

@Composable
private fun mailboxSetCount(settings: AccountSettings): String {
    val count = listOf(
        settings.sentMailbox,
        settings.postponedMailbox,
        settings.addressBookMailbox,
        settings.spamMailbox,
    ).count { it.isNotEmpty() }
    return stringResource(R.string.settings_mailboxes_set, count)
}

@Composable
private fun sortDirection(settings: AccountSettings): String {
    val direction = stringResource(
        if (settings.defaultView.newestFirst) R.string.reader_newest else R.string.reader_oldest,
    )
    return stringResource(R.string.settings_sort_line, sortKeyLabel(settings.defaultView.key), direction)
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
    val base = themeLabel(settings.theme)
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

@Composable
private fun LineField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    ready: Boolean = true,
    commitOnLeave: Boolean = true,
    onDraft: ((String) -> Unit)? = null,
    interpret: (String, String) -> String? = { draft, stored -> commitText(draft, stored) },
    onCommit: (String) -> Unit,
) {
    val draftState = rememberSaveable { mutableStateOf(value) }
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
            draftState.value = next
            onDraft?.invoke(next)
        },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            committer.commit()
            focusManager.clearFocus()
        }),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                if (hadFocus && !state.isFocused && !committer.skipLeave()) committer.commit()
                hadFocus = state.isFocused
            },
    )
}

@Composable
private fun PortField(
    label: String,
    value: Int,
    ready: Boolean = true,
    onValue: (Int) -> Unit,
) {
    val storedPort = value
    LineField(
        label = label,
        value = value.toString(),
        keyboardType = KeyboardType.Number,
        ready = ready,
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
private fun BoolField(label: String, value: Boolean, onValue: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = value, role = Role.Switch, onValueChange = onValue),
        trailingContent = {
            Switch(checked = value, onCheckedChange = null)
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
            value = startRuleLabel(selected),
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
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(startRuleLabel(option))
                            if (startRuleIsRecent(option)) Text(recentRuleNote)
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
    if (startRuleIsRecent(selected)) Text(recentRuleNote)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceField(
    label: String,
    options: List<T>,
    selected: T,
    name: (T) -> String,
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
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(name(option)) },
                    onClick = {
                        onSelect(option)
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
    onChange: (SwipeBinding) -> Unit,
) {
    Text(label)
    ChoiceField(stringResource(R.string.settings_action), SwipeAction.entries, binding.action, { swipeActionLabel(it) }) { action ->
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

private enum class MailboxPick {
    Sent,
    Postponed,
    AddressBook,
    Spam,
    TrailingMove,
    LeadingMove,
}

private fun assignMailbox(base: AccountSettings, field: MailboxPick, mailbox: String): AccountSettings {
    return when (field) {
        MailboxPick.Sent -> base.copy(sentMailbox = mailbox)
        MailboxPick.Postponed -> base.copy(postponedMailbox = mailbox)
        MailboxPick.AddressBook -> base.copy(addressBookMailbox = mailbox)
        MailboxPick.Spam -> base.copy(spamMailbox = mailbox)
        MailboxPick.TrailingMove -> base.copy(
            swipeTrailing = base.swipeTrailing.copy(moveMailbox = mailbox),
        )
        MailboxPick.LeadingMove -> base.copy(
            swipeLeading = base.swipeLeading.copy(moveMailbox = mailbox),
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
