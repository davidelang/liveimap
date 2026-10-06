package org.dlang.liveimap.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dlang.liveimap.BuildConfig
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.engine.probeServer
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.ui.folder.MailboxChooser

@Composable
fun SettingsScreen() {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    val scope = rememberCoroutineScope()
    val saveMutex = remember { Mutex() }
    var settings by remember { mutableStateOf(AccountSettings()) }
    var password by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    var draftFolder by remember { mutableStateOf("") }
    var draftSort by remember { mutableStateOf(SortKey.Arrival) }
    var draftNewest by remember { mutableStateOf(true) }
    var editingStarts by remember { mutableStateOf(false) }
    var draftStartMailbox by remember { mutableStateOf("") }
    var draftStartRule by remember { mutableStateOf(StartRule.Newest) }
    var draftExpanded by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<MailboxPick?>(null) }
    var probing by remember { mutableStateOf(false) }
    var serverReport by remember { mutableStateOf<List<String>>(emptyList()) }
    var warnUnread by remember { mutableStateOf(false) }
    var reportLines by remember { mutableStateOf<List<String>?>(null) }
    var importPreview by remember { mutableStateOf<PinercPreview?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    val settingsState = rememberUpdatedState(settings)
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

    BackHandler(enabled = picking != null || warnUnread || editingStarts || reportLines != null) {
        when {
            reportLines != null -> reportLines = null
            picking != null -> picking = null
            editingStarts -> editingStarts = false
            else -> warnUnread = false
        }
    }

    LaunchedEffect(store) {
        settings = store.load()
        password = store.password()
        ready = true
    }

    fun persist(next: AccountSettings) {
        if (!ready) return
        settings = next
        scope.launch {
            saveMutex.withLock { store.save(next) }
        }
    }

    fun persistPassword(value: String) {
        if (!ready) return
        password = value
        scope.launch {
            saveMutex.withLock { store.setPassword(value) }
        }
    }

    if (!ready) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Account")
        Text("IMAP port 143 and SMTP port 25 are plaintext.")
        LineField("IMAP host", settings.imapHost) { persist(settings.copy(imapHost = it)) }
        PortField("IMAP port", settings.imapPort) { persist(settings.copy(imapPort = it)) }
        LineField("SMTP host", settings.smtpHost) { persist(settings.copy(smtpHost = it)) }
        PortField("SMTP port", settings.smtpPort) { persist(settings.copy(smtpPort = it)) }
        LineField("Friendly name", settings.friendlyName) {
            persist(settings.copy(friendlyName = it))
        }
        LineField(
            "Username",
            settings.username,
            onFocusLost = { username ->
                persist(settings.copy(email = emailDefaultedFromUsername(username, settings.email)))
            },
        ) { persist(settings.copy(username = it)) }
        LineField("Password", password, KeyboardType.Password, password = true) {
            persistPassword(it)
        }
        LineField("Email", settings.email, KeyboardType.Email) { persist(settings.copy(email = it)) }
        LineField("Display name", settings.displayName) { persist(settings.copy(displayName = it)) }
        TextButton(onClick = { openPinerc.launch(arrayOf("text/plain", "*/*")) }) {
            Text("Import from .pinerc…")
        }
        TextButton(
            onClick = {
                if (!probing) {
                    val account = settings
                    probing = true
                    scope.launch {
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
        Text("Mailboxes")
        MailboxLine("Sent mailbox", settings.sentMailbox, { picking = MailboxPick.Sent }) {
            persist(settings.copy(sentMailbox = it))
        }
        MailboxLine("Postponed mailbox", settings.postponedMailbox, { picking = MailboxPick.Postponed }) {
            persist(settings.copy(postponedMailbox = it))
        }
        MailboxLine("Address book mailbox", settings.addressBookMailbox, { picking = MailboxPick.AddressBook }) {
            persist(settings.copy(addressBookMailbox = it))
        }
        MailboxLine("Spam mailbox", settings.spamMailbox, { picking = MailboxPick.Spam }) {
            persist(settings.copy(spamMailbox = it))
        }
        Text("Expanded folders")
        settings.expandedFolders.forEach { mailbox ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(mailbox, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    persist(settings.copy(expandedFolders = settings.expandedFolders - mailbox))
                }) { Text("Remove") }
            }
        }
        LineField("Expanded folder", draftExpanded) { draftExpanded = it }
        TextButton(onClick = {
            if (draftExpanded.isEmpty()) return@TextButton
            persist(settings.copy(expandedFolders = settings.expandedFolders + draftExpanded))
            draftExpanded = ""
        }) { Text("Add expanded folder") }
        Text("Display")
        BoolField("Mark seen on open", settings.markSeenOnOpen) {
            persist(settings.copy(markSeenOnOpen = it))
        }
        BoolField("Show deleted", settings.showDeleted) { persist(settings.copy(showDeleted = it)) }
        BoolField("Ask before expunge", settings.askBeforeExpunge) {
            persist(settings.copy(askBeforeExpunge = it))
        }
        BoolField("Show unread counts", settings.showUnreadCounts || warnUnread) { enabled ->
            if (enabled) {
                warnUnread = true
            } else {
                warnUnread = false
                persist(settings.copy(showUnreadCounts = false))
            }
        }
        ChoiceField("Message view", BodyView.entries, settings.bodyView, { bodyViewLabel(it) }) { view ->
            persist(settings.copy(bodyView = view))
        }
        ChoiceField("Density", Density.entries, settings.density, { densityLabel(it) }) {
            persist(settings.copy(density = it))
        }
        ChoiceField("Date format", DateFormat.entries, settings.dateFormat, { dateFormatLabel(it) }) {
            persist(settings.copy(dateFormat = it))
        }
        if (settings.dateFormat == DateFormat.Custom) {
            LineField("Date pattern", settings.datePattern) {
                persist(settings.copy(datePattern = it))
            }
        }
        ChoiceField("Default view", SortKey.entries, settings.defaultView.key, { sortKeyLabel(it) }) { key ->
            persist(settings.copy(defaultView = settings.defaultView.copy(key = key)))
        }
        BoolField("Newest first", settings.defaultView.newestFirst) { newest ->
            persist(settings.copy(defaultView = settings.defaultView.copy(newestFirst = newest)))
        }
        Text("Folder views")
        settings.folderViews.forEach { (mailbox, view) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$mailbox ${sortKeyLabel(view.key)} ${if (view.newestFirst) "Newest first" else "Oldest first"}",
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    persist(settings.copy(folderViews = settings.folderViews - mailbox))
                }) { Text("Remove") }
            }
        }
        LineField("Folder view mailbox", draftFolder) { draftFolder = it }
        ChoiceField("Folder view sort", SortKey.entries, draftSort, { sortKeyLabel(it) }) { draftSort = it }
        BoolField("Folder view newest first", draftNewest) { draftNewest = it }
        TextButton(onClick = {
            if (draftFolder.isEmpty()) return@TextButton
            persist(
                settings.copy(
                    folderViews = settings.folderViews +
                        (draftFolder to FolderView(draftSort, draftNewest)),
                ),
            )
            draftFolder = ""
        }) { Text("Add folder view") }
        Text("Start position")
        StartRuleField("INBOX opens at", settings.inboxStart, settings.showRecentRules) { rule ->
            persist(settings.copy(inboxStart = rule))
        }
        StartRuleField("Other folders open at", settings.folderStart, settings.showRecentRules) { rule ->
            persist(settings.copy(folderStart = rule))
        }
        TextButton(onClick = { editingStarts = true }) { Text("Start position per folder") }
        ChoiceField(
            "After a sort, direction, filter or search change",
            StartAfterChange.entries,
            settings.startAfterChange,
            { startAfterChangeLabel(it) },
        ) { value ->
            persist(settings.copy(startAfterChange = value))
        }
        BoolField("Show \\Recent-based rules", settings.showRecentRules) { enabled ->
            persist(settings.copy(showRecentRules = enabled))
        }
        BoolField("'Open at' in the index menu", settings.openAtInIndexMenu) { enabled ->
            persist(settings.copy(openAtInIndexMenu = enabled))
        }
        ChoiceField(
            "When a pinerc has no incoming-startup-rule",
            PinercStartDefault.entries,
            settings.pinercStartDefault,
            { pinercStartDefaultLabel(it) },
        ) { value ->
            persist(settings.copy(pinercStartDefault = value))
        }
        val leftToRight = LocalLayoutDirection.current == LayoutDirection.Ltr
        SwipeEditor(
            if (leftToRight) "Swipe left" else "Swipe right",
            settings.swipeTrailing,
            { picking = MailboxPick.TrailingMove },
        ) {
            persist(settings.copy(swipeTrailing = it))
        }
        SwipeEditor(
            if (leftToRight) "Swipe right" else "Swipe left",
            settings.swipeLeading,
            { picking = MailboxPick.LeadingMove },
        ) {
            persist(settings.copy(swipeLeading = it))
        }
        BoolField("Bounce Fcc", settings.bounceFcc) { persist(settings.copy(bounceFcc = it)) }
        BoolField("Include attachments when forwarding", settings.includeForwardAttachments) {
            persist(settings.copy(includeForwardAttachments = it))
        }
        BoolField("Forward as attachment", settings.forwardAsAttachment) {
            persist(settings.copy(forwardAsAttachment = it))
        }
        BoolField("Reply above the quote", settings.replyAboveQuote) {
            persist(settings.copy(replyAboveQuote = it))
        }
        ChoiceField("Theme", ThemeMode.entries, settings.theme, { themeLabel(it) }) {
            persist(settings.copy(theme = it))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BoolField("Dynamic color", settings.dynamicColor) {
                persist(settings.copy(dynamicColor = it))
            }
        }
        Text("Message bar")
        ReaderAction.entries.forEach { action ->
            BoolField(readerActionLabel(action), settings.readerBar.contains(action)) { enabled ->
                val next = if (enabled) {
                    ReaderAction.entries.filter { it == action || settings.readerBar.contains(it) }
                } else {
                    settings.readerBar.filterNot { it == action }
                }
                persist(settings.copy(readerBar = next))
            }
        }
        Text("Debug")
        BoolField("Pipeline IMAP commands", settings.pipelineCommands) {
            persist(settings.copy(pipelineCommands = it))
        }
        BoolField("Log IMAP traffic", settings.logImapTraffic) {
            persist(settings.copy(logImapTraffic = it))
        }
        Text("Commands and server replies go to logcat under LiveIMAP. The password is omitted.")
        TextButton(onClick = {
            val log = TrafficLog.install(File(appContext.cacheDir, "imap-traffic.log"))
            log.shareFile(contextState.value)
        }) { Text("Share log") }
        TextButton(onClick = {
            val log = TrafficLog.install(File(appContext.cacheDir, "imap-traffic.log"))
            val device = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ")
            reportLines = log.debugReport(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                androidVersion = Build.VERSION.RELEASE,
                device = device,
                settings = settings,
            ).lines()
        }) { Text("Debug report") }
        BoolField("Show user name in the debug report", settings.showUserInDebugReport) {
            persist(settings.copy(showUserInDebugReport = it))
        }
    }

    val shownReport = reportLines
    if (shownReport != null) {
        Dialog(
            onDismissRequest = { reportLines = null },
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Debug report", style = MaterialTheme.typography.titleLarge)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        shownReport.forEachIndexed { index, line ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = line.ifEmpty { " " },
                                    modifier = Modifier.weight(1f),
                                    fontFamily = FontFamily.Monospace,
                                )
                                TextButton(onClick = {
                                    reportLines = shownReport.filterIndexed { i, _ -> i != index }
                                }) { Text("Remove line") }
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            copyDebugReport(contextState.value, shownReport)
                        }) { Text("Copy") }
                        TextButton(onClick = {
                            shareDebugReport(contextState.value, shownReport)
                        }) { Text("Share") }
                        TextButton(onClick = { reportLines = null }) { Text("Dismiss") }
                    }
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
                    persist(settings.copy(showUnreadCounts = true))
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { warnUnread = false }) { Text("Dismiss") }
            },
        )
    }

    val field = picking
    if (field != null) {
        MailboxChooser(
            store = store,
            saveMutex = saveMutex,
            onStored = { loaded ->
                settings = settings.copy(expandedFolders = loaded.expandedFolders)
            },
            onPick = { mailbox ->
                scope.launch {
                    saveMutex.withLock {
                        val next = assignMailbox(store.load(), field, mailbox)
                        store.save(next)
                        settings = next
                    }
                    picking = null
                }
            },
            onDismiss = { picking = null },
        )
    }

    if (editingStarts) {
        Dialog(
            onDismissRequest = { editingStarts = false },
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Start position per folder", style = MaterialTheme.typography.titleLarge)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        settings.folderStarts.forEach { (mailbox, rule) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("$mailbox: ${startRuleLabel(rule)}")
                                    if (startRuleIsRecent(rule)) Text(recentRuleNote)
                                }
                                TextButton(onClick = {
                                    persist(withFolderStart(settings, mailbox, null))
                                }) { Text("Default") }
                            }
                        }
                        LineField("Mailbox", draftStartMailbox) { draftStartMailbox = it }
                        StartRuleField("Opens at", draftStartRule, settings.showRecentRules) {
                            draftStartRule = it
                        }
                        TextButton(onClick = {
                            if (draftStartMailbox.isEmpty()) return@TextButton
                            persist(withFolderStart(settings, draftStartMailbox, draftStartRule))
                            draftStartMailbox = ""
                        }) { Text("Add") }
                    }
                    TextButton(onClick = { editingStarts = false }) { Text("Close") }
                }
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
                                persist(preview.next)
                                importPreview = null
                            },
                            enabled = preview.next != settings,
                        ) { Text("Apply") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LineField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    onFocusLost: ((String) -> Unit)? = null,
    onValue: (String) -> Unit,
) {
    var hadFocus by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { state ->
                if (onFocusLost != null && hadFocus && !state.isFocused) {
                    onFocusLost(value)
                }
                hadFocus = state.isFocused
            },
    )
}

@Composable
private fun PortField(label: String, value: Int, onValue: (Int) -> Unit) {
    LineField(label, value.toString(), KeyboardType.Number) { text ->
        val port = text.toIntOrNull() ?: return@LineField
        onValue(port)
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
            LineField(label, value, onValue = onValue)
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

private fun copyDebugReport(context: Context, lines: List<String>) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("LiveIMAP debug report", lines.joinToString("\n")))
}

private fun shareDebugReport(context: Context, lines: List<String>) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, lines.joinToString("\n"))
    }
    context.startActivity(Intent.createChooser(send, "Share debug report"))
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
