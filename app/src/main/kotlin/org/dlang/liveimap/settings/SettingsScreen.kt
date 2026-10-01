package org.dlang.liveimap.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
        LineField("IMAP host", settings.imapHost) { persist(settings.copy(imapHost = it)) }
        PortField("IMAP port", settings.imapPort) { persist(settings.copy(imapPort = it)) }
        LineField("SMTP host", settings.smtpHost) { persist(settings.copy(smtpHost = it)) }
        PortField("SMTP port", settings.smtpPort) { persist(settings.copy(smtpPort = it)) }
        LineField("Username", settings.username) { persist(settings.copy(username = it)) }
        LineField("Display name", settings.displayName) { persist(settings.copy(displayName = it)) }
        LineField("Email", settings.email, KeyboardType.Email) { persist(settings.copy(email = it)) }
        LineField("Sent mailbox", settings.sentMailbox) { persist(settings.copy(sentMailbox = it)) }
        LineField("Postponed mailbox", settings.postponedMailbox) {
            persist(settings.copy(postponedMailbox = it))
        }
        LineField("Address book mailbox", settings.addressBookMailbox) {
            persist(settings.copy(addressBookMailbox = it))
        }
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
        BoolField("Default view newest first", settings.defaultView.newestFirst) { newest ->
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
        SwipeEditor("Trailing swipe", settings.swipeTrailing) {
            persist(settings.copy(swipeTrailing = it))
        }
        SwipeEditor("Leading swipe", settings.swipeLeading) {
            persist(settings.copy(swipeLeading = it))
        }
        BoolField("Bounce Fcc", settings.bounceFcc) { persist(settings.copy(bounceFcc = it)) }
        LineField("Password", password, KeyboardType.Password, password = true) {
            persistPassword(it)
        }
    }
}

@Composable
private fun LineField(
    label: String,
    value: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    onValue: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
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

@Composable
private fun <T> ChoiceField(
    label: String,
    options: List<T>,
    selected: T,
    name: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Text(label)
    options.forEach { option ->
        TextButton(onClick = { onSelect(option) }) {
            val shown = name(option)
            Text(if (option == selected) "[$shown]" else shown)
        }
    }
}

@Composable
private fun SwipeEditor(label: String, binding: SwipeBinding, onChange: (SwipeBinding) -> Unit) {
    Text(label)
    ChoiceField("Action", SwipeAction.entries, binding.action, { it.name }) { action ->
        onChange(binding.copy(action = action))
    }
    LineField("Move mailbox", binding.moveMailbox) { onChange(binding.copy(moveMailbox = it)) }
    LineField("Flag", binding.flag) { onChange(binding.copy(flag = it)) }
}
