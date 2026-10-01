package org.dlang.liveimap.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dlang.liveimap.engine.probeServer
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.ui.UiDims
import org.dlang.liveimap.ui.folder.FolderListModel
import org.dlang.liveimap.ui.folder.FolderRow

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
    var draftExpanded by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<MailboxPick?>(null) }
    var probing by remember { mutableStateOf(false) }
    var serverReport by remember { mutableStateOf<List<String>>(emptyList()) }

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
        Text("IMAP port 143 and SMTP port 25 are plaintext.")
        Text("Server")
        LineField("IMAP host", settings.imapHost) { persist(settings.copy(imapHost = it)) }
        PortField("IMAP port", settings.imapPort) { persist(settings.copy(imapPort = it)) }
        LineField("SMTP host", settings.smtpHost) { persist(settings.copy(smtpHost = it)) }
        PortField("SMTP port", settings.smtpPort) { persist(settings.copy(smtpPort = it)) }
        Text("Account")
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
        MailboxLine("Sent mailbox", settings.sentMailbox, { picking = MailboxPick.Sent }) {
            persist(settings.copy(sentMailbox = it))
        }
        MailboxLine("Postponed mailbox", settings.postponedMailbox, { picking = MailboxPick.Postponed }) {
            persist(settings.copy(postponedMailbox = it))
        }
        MailboxLine("Address book mailbox", settings.addressBookMailbox, { picking = MailboxPick.AddressBook }) {
            persist(settings.copy(addressBookMailbox = it))
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
                            session.close()
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
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text("Display")
        BoolField("Mark seen on open", settings.markSeenOnOpen) {
            persist(settings.copy(markSeenOnOpen = it))
        }
        BoolField("Show deleted", settings.showDeleted) { persist(settings.copy(showDeleted = it)) }
        BoolField("Prefer HTML", settings.preferHtml) { persist(settings.copy(preferHtml = it)) }
        ChoiceField("Density", Density.entries, settings.density, { it.name }) {
            persist(settings.copy(density = it))
        }
        ChoiceField("Default view", SortKey.entries, settings.defaultView.key, { it.name }) { key ->
            persist(settings.copy(defaultView = settings.defaultView.copy(key = key)))
        }
        ChoiceField(
            "Default view newest first",
            listOf(true, false),
            settings.defaultView.newestFirst,
            { if (it) "newest" else "oldest" },
        ) { newest ->
            persist(settings.copy(defaultView = settings.defaultView.copy(newestFirst = newest)))
        }
        Text("Folder views")
        settings.folderViews.forEach { (mailbox, view) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$mailbox ${view.key.name} ${if (view.newestFirst) "newest" else "oldest"}",
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    persist(settings.copy(folderViews = settings.folderViews - mailbox))
                }) { Text("Remove") }
            }
        }
        LineField("Folder view mailbox", draftFolder) { draftFolder = it }
        ChoiceField("Folder view sort", SortKey.entries, draftSort, { it.name }) { draftSort = it }
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
        SwipeEditor(
            "Trailing swipe",
            settings.swipeTrailing,
            { picking = MailboxPick.TrailingMove },
        ) {
            persist(settings.copy(swipeTrailing = it))
        }
        SwipeEditor(
            "Leading swipe",
            settings.swipeLeading,
            { picking = MailboxPick.LeadingMove },
        ) {
            persist(settings.copy(swipeLeading = it))
        }
        BoolField("Bounce Fcc", settings.bounceFcc) { persist(settings.copy(bounceFcc = it)) }
        ChoiceField("Theme", ThemeMode.entries, settings.theme, { it.name }) {
            persist(settings.copy(theme = it))
        }
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onValue)
    }
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
    ChoiceField("Action", SwipeAction.entries, binding.action, { it.name }) { action ->
        onChange(binding.copy(action = action))
    }
    MailboxLine("Move mailbox", binding.moveMailbox, onChooseMove) {
        onChange(binding.copy(moveMailbox = it))
    }
    LineField("Flag", binding.flag) { onChange(binding.copy(flag = it)) }
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

@Composable
private fun MailboxChooser(
    store: SettingsStore,
    saveMutex: Mutex,
    onStored: (AccountSettings) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val session = remember { mailSession() }
    val model = remember(session, store) { FolderListModel(session, store) }
    val scope = rememberCoroutineScope()
    val gate = remember { Mutex() }
    var rows by remember { mutableStateOf<List<FolderRow>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var stopped by remember { mutableStateOf(false) }

    DisposableEffect(session) {
        onDispose { session.close() }
    }

    LaunchedEffect(session) {
        gate.withLock {
            val account = try {
                saveMutex.withLock { store.load() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = error.message ?: "not connected"
                stopped = true
                rows = emptyList()
                return@withLock
            }
            val opened = try {
                session.open(account)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                notice = error.text
                stopped = true
                rows = emptyList()
                return@withLock
            }
            when (opened) {
                is OpenResult.Rejected -> {
                    notice = opened.capabilities
                    stopped = true
                    rows = emptyList()
                    return@withLock
                }
                is OpenResult.Failed -> {
                    notice = opened.text
                    stopped = true
                    rows = emptyList()
                    return@withLock
                }
                OpenResult.Connected -> Unit
            }
            try {
                rows = model.loadLevel()
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                notice = error.text
                stopped = true
                rows = emptyList()
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Choose mailbox")
                val message = notice
                if (message != null) {
                    Text(text = message)
                }
                if (!stopped) {
                    for (row in rows) {
                        MailboxPickRow(
                            row = row,
                            onPick = { onPick(row.mailbox) },
                            onToggle = {
                                scope.launch {
                                    gate.withLock {
                                        if (stopped) return@withLock
                                        try {
                                            saveMutex.withLock {
                                                model.toggleExpanded(row.mailbox)
                                                onStored(store.load())
                                            }
                                        } catch (error: CancellationException) {
                                            throw error
                                        } catch (error: MailFailure) {
                                            notice = error.text
                                            stopped = true
                                            rows = emptyList()
                                            return@withLock
                                        }
                                        val listed = try {
                                            model.loadLevel()
                                        } catch (error: CancellationException) {
                                            throw error
                                        } catch (error: MailFailure) {
                                            notice = error.text
                                            stopped = true
                                            rows = emptyList()
                                            null
                                        }
                                        if (listed != null) {
                                            rows = listed
                                            notice = null
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

@Composable
private fun MailboxPickRow(
    row: FolderRow,
    onPick: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = UiDims.expanderWidth * row.depth),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val slot = if (row.hasChildren) {
            Modifier
                .width(UiDims.expanderWidth)
                .clickable(onClick = onToggle)
        } else {
            Modifier.width(UiDims.expanderWidth)
        }
        Box(modifier = slot, contentAlignment = Alignment.Center) {
            if (row.hasChildren) {
                Text(if (row.expanded) "-" else "+")
            }
        }
        Text(
            text = row.leaf,
            modifier = Modifier
                .clickable(onClick = onPick)
                .padding(vertical = 8.dp),
        )
    }
}

private enum class MailboxPick {
    Sent,
    Postponed,
    AddressBook,
    TrailingMove,
    LeadingMove,
}

private fun assignMailbox(base: AccountSettings, field: MailboxPick, mailbox: String): AccountSettings {
    return when (field) {
        MailboxPick.Sent -> base.copy(sentMailbox = mailbox)
        MailboxPick.Postponed -> base.copy(postponedMailbox = mailbox)
        MailboxPick.AddressBook -> base.copy(addressBookMailbox = mailbox)
        MailboxPick.TrailingMove -> base.copy(
            swipeTrailing = base.swipeTrailing.copy(moveMailbox = mailbox),
        )
        MailboxPick.LeadingMove -> base.copy(
            swipeLeading = base.swipeLeading.copy(moveMailbox = mailbox),
        )
    }
}
