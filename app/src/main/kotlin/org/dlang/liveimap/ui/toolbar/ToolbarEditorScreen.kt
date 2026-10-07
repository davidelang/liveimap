package org.dlang.liveimap.ui.toolbar

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.dlang.liveimap.R
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.DataStoreSettingsStore

private val toolbarIo = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

private class ToolbarSettings(val store: DataStoreSettingsStore) {
    var settings by mutableStateOf<AccountSettings?>(null)
    private val gate = Mutex()

    suspend fun load() {
        settings = store.load()
    }

    fun persist(next: IndexBarLayout) {
        val current = settings ?: return
        settings = current.copy(indexBar = next)
        toolbarIo.launch {
            gate.withLock {
                val latest = settings ?: return@withLock
                store.save(latest)
            }
        }
    }
}

@Composable
fun ToolbarEditorScreen() {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    val holder = remember(store) { ToolbarSettings(store) }
    LaunchedEffect(holder) { holder.load() }
    val loaded = holder.settings ?: return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (section in BarSection.entries) {
            Text(
                text = sectionTitle(section),
                style = MaterialTheme.typography.titleSmall,
            )
            val rows = loaded.indexBar.actionsIn(section)
            rows.forEachIndexed { index, action ->
                IndexBarEditorRow(
                    action = action,
                    section = section,
                    index = index,
                    count = rows.size,
                    onMoveBy = { delta ->
                        val current = holder.settings?.indexBar
                        if (current != null) holder.persist(moveIndexActionBy(current, action, delta))
                    },
                    onMoveTo = { target ->
                        val current = holder.settings?.indexBar
                        if (current != null) holder.persist(moveIndexAction(current, action, target))
                    },
                )
            }
        }
        TextButton(onClick = { holder.persist(resetIndexBar()) }) {
            Text(stringResource(R.string.toolbar_reset))
        }
    }
}

@Composable
private fun IndexBarEditorRow(
    action: IndexBarAction,
    section: BarSection,
    index: Int,
    count: Int,
    onMoveBy: (Int) -> Unit,
    onMoveTo: (BarSection) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(indexBarActionRes(action)))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (index > 0) {
                TextButton(onClick = { onMoveBy(-1) }) {
                    Text(stringResource(R.string.toolbar_move_up))
                }
            }
            if (index < count - 1) {
                TextButton(onClick = { onMoveBy(1) }) {
                    Text(stringResource(R.string.toolbar_move_down))
                }
            }
            for (target in BarSection.entries) {
                if (target == section) continue
                TextButton(onClick = { onMoveTo(target) }) {
                    Text(stringResource(R.string.toolbar_move_to, sectionTitle(target)))
                }
            }
        }
    }
}

@Composable
private fun sectionTitle(section: BarSection): String = stringResource(
    when (section) {
        BarSection.Toolbar -> R.string.toolbar_section_toolbar
        BarSection.Overflow -> R.string.toolbar_section_overflow
        BarSection.Hidden -> R.string.toolbar_section_hidden
    },
)

private fun indexBarActionRes(action: IndexBarAction): Int = when (action) {
    IndexBarAction.Refresh -> R.string.index_refresh
    IndexBarAction.Search -> R.string.index_search
    IndexBarAction.Filter -> R.string.index_filter
}

private fun IndexBarLayout.actionsIn(section: BarSection): List<IndexBarAction> = when (section) {
    BarSection.Toolbar -> toolbar
    BarSection.Overflow -> overflow
    BarSection.Hidden -> hidden
}
