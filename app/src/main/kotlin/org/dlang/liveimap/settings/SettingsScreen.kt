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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dlang.liveimap.BuildConfig
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.engine.probeServer
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.ui.debug.DebugReportReview
import org.dlang.liveimap.ui.folder.MailboxChooser

private val settingsIo = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
private val settingsMutex = Mutex()

enum class SettingsGroup(val route: String, val title: String) {
    Account("account", "Account"),
    Mailboxes("mailboxes", "Mailboxes"),
    Folders("folders", "Folders"),
    Reading("reading", "Reading"),
    Compose("compose", "Compose"),
    Appearance("appearance", "Appearance"),
    Debug("debug", "Debug"),
    ;

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
) {
    val editor = rememberSettingsEditor()
    if (!editor.ready) return
    when (group) {
        SettingsGroup.Account -> AccountGroup(editor)
        SettingsGroup.Mailboxes -> MailboxesGroup(editor)
        SettingsGroup.Folders -> FoldersGroup(editor, onOpenExpanded, onOpenViews, onOpenStarts)
        SettingsGroup.Reading -> ReadingGroup(editor)
        SettingsGroup.Compose -> ComposeGroup(editor)
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
                    Icon(imageVector = Icons.Filled.Close, contentDescription = "Remove")
                }
            }
        }
        TextButton(onClick = { picking = true }) { Text("Add") }
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
                    "$mailbox ${sortKeyLabel(view.key)} ${if (view.newestFirst) "Newest first" else "Oldest first"}",
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    editor.persist(editor.settings.copy(folderViews = editor.settings.folderViews - mailbox))
                }) {
                    Icon(imageVector = Icons.Filled.Close, contentDescription = "Remove")
                }
            }
        }
        ChoiceField("Folder view sort", SortKey.entries, draftSort, { sortKeyLabel(it) }) { draftSort = it }
        BoolField("Folder view newest first", draftNewest) { draftNewest = it }
        TextButton(onClick = { picking = true }) { Text("Add") }
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
                    Text("$mailbox: ${startRuleLabel(rule)}")
                    if (startRuleIsRecent(rule)) Text(recentRuleNote)
                }
                TextButton(onClick = {
                    editor.persist(withFolderStart(editor.settings, mailbox, null))
                }) { Text("Default") }
            }
        }
        LineField(
            "Mailbox",
            draftStartMailbox,
            ready = editor.ready,
            commitOnLeave = false,
            onDraft = { draftStartMailbox = it },
        ) { draftStartMailbox = it }
        StartRuleField("Opens at", draftStartRule, settings.showRecentRules) { draftStartRule = it }
        TextButton(onClick = {
            if (draftStartMailbox.isEmpty()) return@TextButton
            editor.persist(withFolderStart(editor.settings, draftStartMailbox, draftStartRule))
            draftStartMailbox = ""
        }) { Text("Add") }
    }
}

@Composable
private fun AccountGroup(editor: SettingsEditor) {
    val sections = rememberSectionOpen("server")
    val settings = editor.settings
    var probing by remember { mutableStateOf(false) }
    var serverReport by remember { mutableStateOf<List<String>>(emptyList()) }
    var importPreview by remember { mutableStateOf<PinercPreview?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
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
                importError = "That file is too large."
            }
            PinercRead.Bad -> {
                importPreview = null
                importError = "Could not read that file."
            }
        }
    }
    SettingsPage {
        SettingsSection(
            title = "Server",
            summary = hostAndPort(settings.imapHost, settings.imapPort, "No server"),
            expanded = "server" in sections.open,
            onToggle = { sections.toggle("server") },
        ) {
            Text("IMAP port 143 and SMTP port 25 are plaintext.")
            LineField("IMAP host", settings.imapHost, ready = editor.ready) {
                editor.persist(editor.settings.copy(imapHost = it))
            }
            PortField("IMAP port", settings.imapPort, ready = editor.ready) {
                editor.persist(editor.settings.copy(imapPort = it))
            }
        }
        SettingsSection(
            title = "Identity",
            summary = settings.username.ifBlank { "No user" },
            expanded = "identity" in sections.open,
            onToggle = { sections.toggle("identity") },
        ) {
            LineField("Friendly name", settings.friendlyName, ready = editor.ready) {
                editor.persist(editor.settings.copy(friendlyName = it))
            }
            LineField("Username", settings.username, ready = editor.ready) { draft ->
                editor.persist(
                    editor.settings.copy(
                        username = draft,
                        email = emailDefaultedFromUsername(draft, editor.settings.email),
                    ),
                )
            }
            LineField(
                "Password",
                editor.password,
                KeyboardType.Password,
                password = true,
                ready = editor.ready,
            ) { editor.persistPassword(it) }
            LineField("Email", settings.email, KeyboardType.Email, ready = editor.ready) {
                editor.persist(editor.settings.copy(email = it))
            }
            LineField("Display name", settings.displayName, ready = editor.ready) {
                editor.persist(editor.settings.copy(displayName = it))
            }
        }
        SettingsSection(
            title = "Sending",
            summary = hostAndPort(settings.smtpHost, settings.smtpPort, "No SMTP server"),
            expanded = "sending" in sections.open,
            onToggle = { sections.toggle("sending") },
        ) {
            LineField("SMTP host", settings.smtpHost, ready = editor.ready) {
                editor.persist(editor.settings.copy(smtpHost = it))
            }
            PortField("SMTP port", settings.smtpPort, ready = editor.ready) {
                editor.persist(editor.settings.copy(smtpPort = it))
            }
        }
        SettingsSection(
            title = "Import",
            summary = "Import from .pinerc…",
            expanded = "import" in sections.open,
            onToggle = { sections.toggle("import") },
        ) {
            TextButton(onClick = { openPinerc.launch(arrayOf("text/plain", "*/*")) }) {
                Text("Import from .pinerc…")
            }
        }
        SettingsSection(
            title = "Advanced",
            summary = "Test server",
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
                Text(if (probing) "Testing…" else "Test server")
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
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { importError = null }) { Text("Close") }
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
                    Text("Import from .pinerc", style = MaterialTheme.typography.titleLarge)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Will change")
                        preview.rows.forEach { line -> Text(line) }
                        Text("Not applied")
                        preview.skipped.forEach { line -> Text(line) }
                        Text("Ignored")
                        if (preview.omittedCount != 0) {
                            Text("${preview.omittedCount} other lines were left out")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { importPreview = null }) { Text("Cancel") }
                        TextButton(
                            onClick = {
                                editor.persist(preview.next)
                                importPreview = null
                            },
                            enabled = preview.next != editor.settings,
                        ) { Text("Apply") }
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
            title = "Special-use",
            summary = mailboxSetCount(settings),
            expanded = "special" in sections.open,
            onToggle = { sections.toggle("special") },
        ) {
            MailboxLine("Sent mailbox", settings.sentMailbox, { picking = MailboxPick.Sent }) {
                editor.persist(editor.settings.copy(sentMailbox = it))
            }
            MailboxLine("Postponed mailbox", settings.postponedMailbox, { picking = MailboxPick.Postponed }) {
                editor.persist(editor.settings.copy(postponedMailbox = it))
            }
            MailboxLine("Address book mailbox", settings.addressBookMailbox, { picking = MailboxPick.AddressBook }) {
                editor.persist(editor.settings.copy(addressBookMailbox = it))
            }
            MailboxLine("Spam mailbox", settings.spamMailbox, { picking = MailboxPick.Spam }) {
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
            title = "Folder list",
            summary = "${settings.expandedFolders.size} expanded",
            expanded = "list" in sections.open,
            onToggle = { sections.toggle("list") },
        ) {
            OpenRow("Expanded folders", onOpenExpanded)
        }
        SettingsSection(
            title = "Folder views",
            summary = sortDirection(settings),
            expanded = "views" in sections.open,
            onToggle = { sections.toggle("views") },
        ) {
            ChoiceField("Default view", SortKey.entries, settings.defaultView.key, { sortKeyLabel(it) }) { key ->
                editor.persist(editor.settings.copy(defaultView = editor.settings.defaultView.copy(key = key)))
            }
            BoolField("Newest first", settings.defaultView.newestFirst) { newest ->
                editor.persist(editor.settings.copy(defaultView = editor.settings.defaultView.copy(newestFirst = newest)))
            }
            OpenRow("Folder views", onOpenViews)
        }
        SettingsSection(
            title = "Start position",
            summary = "INBOX ${startRuleLabel(settings.inboxStart)}",
            expanded = "start" in sections.open,
            onToggle = { sections.toggle("start") },
        ) {
            StartRuleField("INBOX opens at", settings.inboxStart, settings.showRecentRules) { rule ->
                editor.persist(editor.settings.copy(inboxStart = rule))
            }
            StartRuleField("Other folders open at", settings.folderStart, settings.showRecentRules) { rule ->
                editor.persist(editor.settings.copy(folderStart = rule))
            }
            ChoiceField(
                "After a sort, direction, filter or search change",
                StartAfterChange.entries,
                settings.startAfterChange,
                { startAfterChangeLabel(it) },
            ) { value ->
                editor.persist(editor.settings.copy(startAfterChange = value))
            }
            BoolField("Show \\Recent-based rules", settings.showRecentRules) { enabled ->
                editor.persist(editor.settings.copy(showRecentRules = enabled))
            }
            BoolField("'Open at' in the index menu", settings.openAtInIndexMenu) { enabled ->
                editor.persist(editor.settings.copy(openAtInIndexMenu = enabled))
            }
            ChoiceField(
                "When a pinerc has no incoming-startup-rule",
                PinercStartDefault.entries,
                settings.pinercStartDefault,
                { pinercStartDefaultLabel(it) },
            ) { value ->
                editor.persist(editor.settings.copy(pinercStartDefault = value))
            }
            OpenRow("Start position per folder", onOpenStarts)
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
            title = "Opening",
            summary = bodyViewLabel(settings.bodyView),
            expanded = "opening" in sections.open,
            onToggle = { sections.toggle("opening") },
        ) {
            BoolField("Mark seen on open", settings.markSeenOnOpen) {
                editor.persist(editor.settings.copy(markSeenOnOpen = it))
            }
            BoolField("Show deleted", settings.showDeleted) {
                editor.persist(editor.settings.copy(showDeleted = it))
            }
            ChoiceField("Message view", BodyView.entries, settings.bodyView, { bodyViewLabel(it) }) { view ->
                editor.persist(editor.settings.copy(bodyView = view))
            }
        }
        SettingsSection(
            title = "Deleting",
            summary = if (settings.askBeforeExpunge) "Ask before expunge" else "Expunge without asking",
            expanded = "deleting" in sections.open,
            onToggle = { sections.toggle("deleting") },
        ) {
            BoolField("Ask before expunge", settings.askBeforeExpunge) {
                editor.persist(editor.settings.copy(askBeforeExpunge = it))
            }
        }
        SettingsSection(
            title = "Counts",
            summary = if (settings.showUnreadCounts) "Unread counts on" else "Unread counts off",
            expanded = "counts" in sections.open,
            onToggle = { sections.toggle("counts") },
        ) {
            BoolField("Show unread counts", settings.showUnreadCounts || warnUnread) { enabled ->
                if (enabled) {
                    warnUnread = true
                } else {
                    warnUnread = false
                    editor.persist(editor.settings.copy(showUnreadCounts = false))
                }
            }
        }
        SettingsSection(
            title = "Actions",
            summary = "${swipeActionLabel(settings.swipeTrailing.action)} / ${swipeActionLabel(settings.swipeLeading.action)}",
            expanded = "actions" in sections.open,
            onToggle = { sections.toggle("actions") },
        ) {
            SwipeEditor(
                if (leftToRight) "Swipe left" else "Swipe right",
                settings.swipeTrailing,
                { picking = MailboxPick.TrailingMove },
            ) { editor.persist(editor.settings.copy(swipeTrailing = it)) }
            SwipeEditor(
                if (leftToRight) "Swipe right" else "Swipe left",
                settings.swipeLeading,
                { picking = MailboxPick.LeadingMove },
            ) { editor.persist(editor.settings.copy(swipeLeading = it)) }
            Text("Message bar")
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
                Text(
                    "Counting unread messages asks the server for an unseen count for every mailbox in the list, and a large mailbox can make the folder list slow.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    warnUnread = false
                    editor.persist(editor.settings.copy(showUnreadCounts = true))
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { warnUnread = false }) { Text("Dismiss") }
            },
        )
    }
    MailboxPickDialog(editor, picking) { picking = it }
}

@Composable
private fun ComposeGroup(editor: SettingsEditor) {
    val sections = rememberSectionOpen("replying")
    val settings = editor.settings
    SettingsPage {
        SettingsSection(
            title = "Replying",
            summary = if (settings.replyAboveQuote) "Reply above the quote" else "Reply below the quote",
            expanded = "replying" in sections.open,
            onToggle = { sections.toggle("replying") },
        ) {
            BoolField("Reply above the quote", settings.replyAboveQuote) {
                editor.persist(editor.settings.copy(replyAboveQuote = it))
            }
        }
        SettingsSection(
            title = "Forwarding",
            summary = forwardSummary(settings),
            expanded = "forwarding" in sections.open,
            onToggle = { sections.toggle("forwarding") },
        ) {
            BoolField("Include attachments when forwarding", settings.includeForwardAttachments) {
                editor.persist(editor.settings.copy(includeForwardAttachments = it))
            }
            BoolField("Forward as attachment", settings.forwardAsAttachment) {
                editor.persist(editor.settings.copy(forwardAsAttachment = it))
            }
        }
        SettingsSection(
            title = "Fcc",
            summary = if (settings.bounceFcc) "Bounce Fcc on" else "Bounce Fcc off",
            expanded = "fcc" in sections.open,
            onToggle = { sections.toggle("fcc") },
        ) {
            BoolField("Bounce Fcc", settings.bounceFcc) { editor.persist(editor.settings.copy(bounceFcc = it)) }
        }
    }
}

@Composable
private fun AppearanceGroup(editor: SettingsEditor) {
    val sections = rememberSectionOpen("theme")
    val settings = editor.settings
    SettingsPage {
        SettingsSection(
            title = "Theme",
            summary = themeSummary(settings),
            expanded = "theme" in sections.open,
            onToggle = { sections.toggle("theme") },
        ) {
            ChoiceField("Theme", ThemeMode.entries, settings.theme, { themeLabel(it) }) {
                editor.persist(editor.settings.copy(theme = it))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                BoolField("Dynamic color", settings.dynamicColor) {
                    editor.persist(editor.settings.copy(dynamicColor = it))
                }
            }
        }
        SettingsSection(
            title = "Display",
            summary = "${densityLabel(settings.density)}, ${dateFormatLabel(settings.dateFormat)}",
            expanded = "display" in sections.open,
            onToggle = { sections.toggle("display") },
        ) {
            ChoiceField("Density", Density.entries, settings.density, { densityLabel(it) }) {
                editor.persist(editor.settings.copy(density = it))
            }
            ChoiceField("Date format", DateFormat.entries, settings.dateFormat, { dateFormatLabel(it) }) {
                editor.persist(editor.settings.copy(dateFormat = it))
            }
            if (settings.dateFormat == DateFormat.Custom) {
                LineField("Date pattern", settings.datePattern, ready = editor.ready) {
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
            title = "Logging",
            summary = loggingSummary(settings),
            expanded = "logging" in sections.open,
            onToggle = { sections.toggle("logging") },
        ) {
            BoolField("Pipeline IMAP commands", settings.pipelineCommands) {
                editor.persist(editor.settings.copy(pipelineCommands = it))
            }
            BoolField("Log IMAP traffic", settings.logImapTraffic) {
                editor.persist(editor.settings.copy(logImapTraffic = it))
            }
            Text("Commands and server replies go to logcat under LiveIMAP. The password is omitted.")
            TextButton(onClick = {
                val log = TrafficLog.install(File(editor.appContext.cacheDir, "imap-traffic.log"))
                log.shareFile(contextState.value)
            }) { Text("Share log") }
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
            }) { Text("Debug report") }
            BoolField("Show user name in the debug report", settings.showUserInDebugReport) {
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
    val description = if (expanded) "Expanded" else "Collapsed"
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

private fun groupSummary(group: SettingsGroup, settings: AccountSettings): String = when (group) {
    SettingsGroup.Account -> settings.imapHost.ifBlank { "No server" }
    SettingsGroup.Mailboxes -> mailboxSetCount(settings)
    SettingsGroup.Folders -> sortDirection(settings)
    SettingsGroup.Reading -> bodyViewLabel(settings.bodyView)
    SettingsGroup.Compose -> if (settings.replyAboveQuote) "Reply above the quote" else "Reply below the quote"
    SettingsGroup.Appearance -> themeLabel(settings.theme)
    SettingsGroup.Debug -> if (settings.logImapTraffic) "Traffic log on" else "Traffic log off"
}

private fun mailboxSetCount(settings: AccountSettings): String {
    val count = listOf(
        settings.sentMailbox,
        settings.postponedMailbox,
        settings.addressBookMailbox,
        settings.spamMailbox,
    ).count { it.isNotEmpty() }
    return "$count of 4 set"
}

private fun sortDirection(settings: AccountSettings): String {
    val direction = if (settings.defaultView.newestFirst) "Newest first" else "Oldest first"
    return "${sortKeyLabel(settings.defaultView.key)}, $direction"
}

private fun hostAndPort(host: String, port: Int, empty: String): String {
    if (host.isBlank()) return empty
    return "$host:$port"
}

private fun forwardSummary(settings: AccountSettings): String = when {
    settings.forwardAsAttachment -> "Forward as attachment"
    settings.includeForwardAttachments -> "Include attachments"
    else -> "Attachments off"
}

private fun themeSummary(settings: AccountSettings): String {
    val base = themeLabel(settings.theme)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && settings.dynamicColor) {
        return "$base · Dynamic color"
    }
    return base
}

private fun loggingSummary(settings: AccountSettings): String {
    val pipeline = if (settings.pipelineCommands) "Pipeline on" else "Pipeline off"
    val log = if (settings.logImapTraffic) ", log on" else ", log off"
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
    ChoiceField("Action", SwipeAction.entries, binding.action, { swipeActionLabel(it) }) { action ->
        onChange(binding.copy(action = action))
    }
    if (binding.action == SwipeAction.Move) {
        MailboxLine("Move mailbox", binding.moveMailbox, onChooseMove) {
            onChange(binding.copy(moveMailbox = it))
        }
    }
    if (binding.action == SwipeAction.SetFlag || binding.action == SwipeAction.ClearFlag) {
        LineField("Flag", binding.flag) { onChange(binding.copy(flag = it)) }
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
        TextButton(onClick = onChoose) { Text("Choose") }
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
