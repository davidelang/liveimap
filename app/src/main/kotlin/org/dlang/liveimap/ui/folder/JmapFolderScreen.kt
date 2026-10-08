package org.dlang.liveimap.ui.folder

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.dlang.liveimap.jmap.JmapFailure
import org.dlang.liveimap.jmap.JmapFolderRow
import org.dlang.liveimap.jmap.JmapFolderScreenModel
import org.dlang.liveimap.jmap.jmapFolderLabel

@Composable
fun JmapFolderList(
    rows: List<JmapFolderRow>,
    onToggle: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    Column {
        rows.forEachIndexed { index, row ->
            Button(
                onClick = {
                    if (row.hasChildren) onToggle(row.id) else onOpen(row.id)
                },
            ) {
                Text(jmapFolderLabel(rows, index))
            }
        }
    }
}

@Composable
fun JmapFolderRoute(
    model: JmapFolderScreenModel,
    onGiveUp: () -> Unit,
) {
    var rows by remember(model) { mutableStateOf(model.rows) }
    val gate = remember(model) { Mutex() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) {
        try {
            gate.withLock {
                withContext(Dispatchers.IO) { model.loadTop() }
            }
            rows = model.rows
        } catch (error: CancellationException) {
            throw error
        } catch (_: JmapFailure) {
            onGiveUp()
        }
    }
    JmapFolderList(
        rows = rows,
        onToggle = { id ->
            scope.launch {
                try {
                    gate.withLock {
                        withContext(Dispatchers.IO) { model.toggle(id) }
                    }
                    rows = model.rows
                } catch (error: CancellationException) {
                    throw error
                } catch (_: JmapFailure) {
                    onGiveUp()
                }
            }
        },
        onOpen = { _ -> },
    )
}
