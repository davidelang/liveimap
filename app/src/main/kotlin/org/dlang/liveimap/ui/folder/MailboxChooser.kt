package org.dlang.liveimap.ui.folder

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dlang.liveimap.R
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SettingsStore
import org.dlang.liveimap.ui.UiDims

internal class MailboxChooserHeld : ViewModel() {
    var model: FolderListModel? = null
    val rowsState = mutableStateOf<List<FolderRow>>(emptyList())
    var levelReady: Boolean = false

    fun bind(session: MailSession, store: SettingsStore): FolderListModel {
        val current = model
        if (current != null) return current
        val created = FolderListModel(session, store)
        model = created
        return created
    }

    fun dropLoaded() {
        model = null
        rowsState.value = emptyList()
        levelReady = false
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

@Composable
internal fun MailboxChooser(
    store: SettingsStore,
    saveMutex: Mutex,
    onStored: (AccountSettings) -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val notConnected = stringResource(R.string.reader_not_connected)
    val session = remember { mailSession() }
    val held = viewModel<MailboxChooserHeld>()
    val model = held.bind(session, store)
    val host = LocalContext.current.hostActivity()
    DisposableEffect(held) {
        onDispose {
            if (host?.isChangingConfigurations != true) held.dropLoaded()
        }
    }
    val scope = rememberCoroutineScope()
    val gate = remember { Mutex() }
    var rows by held.rowsState
    var notice by remember { mutableStateOf<String?>(null) }
    var stopped by remember { mutableStateOf(false) }

    LaunchedEffect(session) {
        if (held.levelReady) return@LaunchedEffect
        gate.withLock {
            val account = try {
                saveMutex.withLock { store.load() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = error.message ?: notConnected
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
                held.levelReady = true
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
                Text(stringResource(R.string.chooser_title))
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
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.compose_close)) }
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
