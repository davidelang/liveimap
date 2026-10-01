package org.dlang.liveimap.ui.folder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.ui.UiDims

@Composable
fun FolderListScreen(onOpenMailbox: (String) -> Unit) {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
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
            val settings = try {
                store.load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = error.message ?: "not connected"
                stopped = true
                return@withLock
            }
            try {
                store.password()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = error.message ?: "not connected"
                stopped = true
                return@withLock
            }
            val opened = try {
                session.open(settings)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                notice = error.text
                stopped = true
                return@withLock
            }
            when (opened) {
                is OpenResult.Rejected -> {
                    notice = opened.capabilities
                    stopped = true
                    return@withLock
                }
                is OpenResult.Failed -> {
                    notice = opened.text
                    stopped = true
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
            }
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
        if (!stopped) {
            for (row in rows) {
                FolderListRow(
                    row = row,
                    onOpen = { onOpenMailbox(row.mailbox) },
                    onToggle = {
                        scope.launch {
                            gate.withLock {
                                if (stopped) return@withLock
                                try {
                                    model.toggleExpanded(row.mailbox)
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: MailFailure) {
                                    notice = error.text
                                    return@withLock
                                }
                                val listed = try {
                                    model.loadLevel()
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: MailFailure) {
                                    notice = error.text
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
    }
}

@Composable
private fun FolderListRow(
    row: FolderRow,
    onOpen: () -> Unit,
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
                .weight(1f)
                .clickable(onClick = onOpen)
                .padding(vertical = 8.dp),
        )
    }
}
