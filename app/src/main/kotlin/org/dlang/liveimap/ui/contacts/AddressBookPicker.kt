package org.dlang.liveimap.ui.contacts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.SelectedAddress
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.DataStoreSettingsStore

@Composable
fun AddressBookPicker(onPicked: (SelectedAddress) -> Unit, onDismiss: () -> Unit) {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    val session = remember { mailSession() }
    var notice by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<AlpineEntry>>(emptyList()) }

    LaunchedEffect(session) {
        val settings = try {
            store.load()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            notice = error.message ?: "not connected"
            return@LaunchedEffect
        }
        val mailbox = settings.addressBookMailbox
        if (mailbox.isEmpty()) {
            notice = "Address book mailbox is not set"
            return@LaunchedEffect
        }
        try {
            store.password()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            notice = error.message ?: "not connected"
            return@LaunchedEffect
        }
        val opened = try {
            session.open(settings)
        } catch (error: CancellationException) {
            throw error
        } catch (error: MailFailure) {
            notice = error.text
            return@LaunchedEffect
        }
        when (opened) {
            is OpenResult.Rejected -> {
                notice = opened.capabilities
                return@LaunchedEffect
            }
            is OpenResult.Failed -> {
                notice = opened.text
                return@LaunchedEffect
            }
            OpenResult.Connected -> Unit
        }
        try {
            val loaded = loadAlpineBook(session, mailbox)
            notice = loaded.notice
            entries = loaded.entries
        } catch (error: CancellationException) {
            throw error
        } catch (error: MailFailure) {
            notice = error.text
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
    ) {
        val message = notice
        if (message != null) {
            Text(text = message, modifier = Modifier.padding(8.dp))
        }
        TextButton(onClick = onDismiss) { Text("Close") }
        for (entry in entries) {
            Text(
                text = entryLabel(entry),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        for (picked in pickedAddresses(entry)) {
                            onPicked(picked)
                        }
                    }
                    .padding(vertical = 8.dp),
            )
        }
    }
}

private fun entryLabel(entry: AlpineEntry): String {
    val name = entry.fullname.ifEmpty { entry.nickname }
    if (name.isEmpty()) return entry.address
    if (entry.address.isEmpty()) return name
    return "$name ${entry.address}"
}
