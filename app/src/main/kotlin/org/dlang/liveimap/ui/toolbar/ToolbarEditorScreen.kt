package org.dlang.liveimap.ui.toolbar

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
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

enum class ToolbarScreen {
    Index,
    Selection,
    Folders,
    Reader,
    Compose,
}

private class ToolbarSettings(val store: DataStoreSettingsStore) {
    var settings by mutableStateOf<AccountSettings?>(null)
    private val gate = Mutex()

    suspend fun load() {
        settings = store.load()
    }

    fun persistIndex(next: IndexBarLayout) {
        val current = settings ?: return
        settings = current.copy(indexBar = next)
        save()
    }

    fun persistSelection(next: SelectionBarLayout) {
        val current = settings ?: return
        settings = current.copy(selectionBar = next)
        save()
    }

    fun persistFolder(next: FolderBarLayout) {
        val current = settings ?: return
        settings = current.copy(folderBar = next)
        save()
    }

    fun persistReader(next: ReaderToolbarLayout) {
        val current = settings ?: return
        settings = current.copy(readerToolbar = next)
        save()
    }

    fun persistCompose(next: ComposeBarLayout) {
        val current = settings ?: return
        settings = current.copy(composeBar = next)
        save()
    }

    fun persistToolbarRows(next: Int) {
        val current = settings ?: return
        if (next !in 1..4 || next == current.toolbarRows) return
        settings = current.copy(toolbarRows = next)
        save()
    }

    private fun save() {
        toolbarIo.launch {
            gate.withLock {
                val latest = settings ?: return@withLock
                store.save(latest)
            }
        }
    }
}

@Composable
fun ToolbarEditorScreen(screen: ToolbarScreen) {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    val holder = remember(store) { ToolbarSettings(store) }
    LaunchedEffect(holder) { holder.load() }
    val loaded = holder.settings ?: return
    when (screen) {
        ToolbarScreen.Index -> SectionEditor(
            rowsIn = { section -> loaded.indexBar.actionsIn(section) },
            labelRes = ::indexBarActionRes,
            onMoveBy = { action, delta ->
                val current = holder.settings?.indexBar
                if (current != null) holder.persistIndex(moveIndexActionBy(current, action, delta))
            },
            onMoveTo = { action, target ->
                val current = holder.settings?.indexBar
                if (current != null) holder.persistIndex(moveIndexAction(current, action, target))
            },
            onDrag = { from, to ->
                val current = holder.settings?.indexBar
                if (current != null) holder.persistIndex(dragIndexLayout(current, from, to))
            },
            onReset = { holder.persistIndex(resetIndexBar()) },
            toolbarRows = loaded.toolbarRows,
            onToolbarRows = { next -> holder.persistToolbarRows(next) },
        )
        ToolbarScreen.Selection -> SectionEditor(
            rowsIn = { section -> loaded.selectionBar.actionsIn(section) },
            labelRes = ::selectionBarActionRes,
            onMoveBy = { action, delta ->
                val current = holder.settings?.selectionBar
                if (current != null) holder.persistSelection(moveSelectionActionBy(current, action, delta))
            },
            onMoveTo = { action, target ->
                val current = holder.settings?.selectionBar
                if (current != null) holder.persistSelection(moveSelectionAction(current, action, target))
            },
            onDrag = { from, to ->
                val current = holder.settings?.selectionBar
                if (current != null) holder.persistSelection(dragSelectionLayout(current, from, to))
            },
            onReset = { holder.persistSelection(resetSelectionBar()) },
            toolbarRows = loaded.toolbarRows,
            onToolbarRows = { next -> holder.persistToolbarRows(next) },
        )
        ToolbarScreen.Folders -> SectionEditor(
            rowsIn = { section -> loaded.folderBar.actionsIn(section) },
            labelRes = ::folderBarActionRes,
            onMoveBy = { action, delta ->
                val current = holder.settings?.folderBar
                if (current != null) holder.persistFolder(moveFolderActionBy(current, action, delta))
            },
            onMoveTo = { action, target ->
                val current = holder.settings?.folderBar
                if (current != null) holder.persistFolder(moveFolderAction(current, action, target))
            },
            onDrag = { from, to ->
                val current = holder.settings?.folderBar
                if (current != null) holder.persistFolder(dragFolderLayout(current, from, to))
            },
            onReset = { holder.persistFolder(resetFolderBar()) },
            toolbarRows = loaded.toolbarRows,
            onToolbarRows = { next -> holder.persistToolbarRows(next) },
        )
        ToolbarScreen.Reader -> SectionEditor(
            rowsIn = { section -> effectiveReaderToolbar(loaded).actionsIn(section) },
            labelRes = ::readerToolbarActionRes,
            onMoveBy = { action, delta ->
                val current = holder.settings?.let { effectiveReaderToolbar(it) }
                if (current != null) holder.persistReader(moveReaderActionBy(current, action, delta))
            },
            onMoveTo = { action, target ->
                val current = holder.settings?.let { effectiveReaderToolbar(it) }
                if (current != null) holder.persistReader(moveReaderAction(current, action, target))
            },
            onDrag = { from, to ->
                val current = holder.settings?.let { effectiveReaderToolbar(it) }
                if (current != null) holder.persistReader(dragReaderLayout(current, from, to))
            },
            onReset = { holder.persistReader(resetReaderToolbar()) },
            toolbarRows = loaded.toolbarRows,
            onToolbarRows = { next -> holder.persistToolbarRows(next) },
        )
        ToolbarScreen.Compose -> SectionEditor(
            rowsIn = { section -> loaded.composeBar.actionsIn(section) },
            labelRes = ::composeBarActionRes,
            onMoveBy = { action, delta ->
                val current = holder.settings?.composeBar
                if (current != null) holder.persistCompose(moveComposeActionBy(current, action, delta))
            },
            onMoveTo = { action, target ->
                val current = holder.settings?.composeBar
                if (current != null) holder.persistCompose(moveComposeAction(current, action, target))
            },
            onDrag = { from, to ->
                val current = holder.settings?.composeBar
                if (current != null) holder.persistCompose(dragComposeLayout(current, from, to))
            },
            onReset = { holder.persistCompose(resetComposeBar()) },
            toolbarRows = loaded.toolbarRows,
            onToolbarRows = { next -> holder.persistToolbarRows(next) },
        )
    }
}

@Composable
private fun <A : Enum<A>> SectionEditor(
    rowsIn: (BarSection) -> List<A>,
    labelRes: (A) -> Int,
    onMoveBy: (A, Int) -> Unit,
    onMoveTo: (A, BarSection) -> Unit,
    onDrag: (Int, Int) -> Unit,
    onReset: () -> Unit,
    toolbarRows: Int,
    onToolbarRows: (Int) -> Unit,
) {
    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        onDrag(from.index, to.index)
    }
    LazyColumn(
        state = lazyListState,
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (section in BarSection.entries) {
            item(key = section.name) {
                ReorderableItem(reorderableState, key = section.name) {
                    Text(
                        text = sectionTitle(section),
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            val rows = rowsIn(section)
            items(rows, key = { action -> action.name }) { action ->
                val index = rows.indexOf(action)
                ReorderableItem(reorderableState, key = action.name) {
                    ToolbarEditorRow(
                        labelRes = labelRes(action),
                        section = section,
                        index = index,
                        count = rows.size,
                        onMoveBy = { delta -> onMoveBy(action, delta) },
                        onMoveTo = { target -> onMoveTo(action, target) },
                    )
                }
            }
        }
        item(key = "reset") {
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.toolbar_reset))
            }
        }
        item(key = "toolbar-rows") {
            val toolbarCount = rowsIn(BarSection.Toolbar).size
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.toolbar_rows))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(toolbarRows.toString())
                    TextButton(
                        onClick = { onToolbarRows(toolbarRows - 1) },
                        enabled = toolbarRows > 1,
                    ) {
                        Text("−")
                    }
                    TextButton(
                        onClick = { onToolbarRows(toolbarRows + 1) },
                        enabled = toolbarRows < 4,
                    ) {
                        Text("+")
                    }
                }
                // Editor budget is 3 slots. Each bar measures its own width.
                if (packToolbar(toolbarCount, 3, toolbarRows).overflowsCap) {
                    Text(stringResource(R.string.toolbar_rows_warning, toolbarRows))
                }
            }
        }
    }
}

@Composable
private fun ReorderableCollectionItemScope.ToolbarEditorRow(
    labelRes: Int,
    section: BarSection,
    index: Int,
    count: Int,
    onMoveBy: (Int) -> Unit,
    onMoveTo: (BarSection) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(labelRes))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {},
                modifier = Modifier.draggableHandle().clearAndSetSemantics {},
            ) {
                Icon(
                    imageVector = Icons.Filled.DragHandle,
                    contentDescription = stringResource(R.string.toolbar_drag),
                )
            }
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

private fun selectionBarActionRes(action: SelectionBarAction): Int = when (action) {
    SelectionBarAction.Seen -> R.string.toolbar_mark_read
    SelectionBarAction.Flag -> R.string.toolbar_flag
    SelectionBarAction.Move -> R.string.index_move
    SelectionBarAction.Delete -> R.string.drawer_delete
}

private fun folderBarActionRes(action: FolderBarAction): Int = when (action) {
    FolderBarAction.Refresh -> R.string.folders_refresh
    FolderBarAction.CollapseAll -> R.string.folders_collapse_all
    FolderBarAction.SaveDefault -> R.string.folders_save_default
    FolderBarAction.ResetDefault -> R.string.folders_reset_default
}

private fun readerToolbarActionRes(action: ReaderToolbarAction): Int = when (action) {
    ReaderToolbarAction.Refresh -> R.string.reader_refresh
    ReaderToolbarAction.Reply -> R.string.compose_reply
    ReaderToolbarAction.ReplyAll -> R.string.compose_reply_all
    ReaderToolbarAction.Forward -> R.string.compose_forward
    ReaderToolbarAction.Delete -> R.string.drawer_delete
    ReaderToolbarAction.Move -> R.string.label_move
    ReaderToolbarAction.Spam -> R.string.label_spam
    ReaderToolbarAction.Bounce -> R.string.compose_bounce
}

private fun composeBarActionRes(action: ComposeBarAction): Int = when (action) {
    ComposeBarAction.Postpone -> R.string.compose_postpone
}

private fun IndexBarLayout.actionsIn(section: BarSection): List<IndexBarAction> = when (section) {
    BarSection.Toolbar -> toolbar
    BarSection.Overflow -> overflow
    BarSection.Hidden -> hidden
}

private fun SelectionBarLayout.actionsIn(section: BarSection): List<SelectionBarAction> = when (section) {
    BarSection.Toolbar -> toolbar
    BarSection.Overflow -> overflow
    BarSection.Hidden -> hidden
}

private fun FolderBarLayout.actionsIn(section: BarSection): List<FolderBarAction> = when (section) {
    BarSection.Toolbar -> toolbar
    BarSection.Overflow -> overflow
    BarSection.Hidden -> hidden
}

private fun ReaderToolbarLayout.actionsIn(section: BarSection): List<ReaderToolbarAction> = when (section) {
    BarSection.Toolbar -> toolbar
    BarSection.Overflow -> overflow
    BarSection.Hidden -> hidden
}

private fun ComposeBarLayout.actionsIn(section: BarSection): List<ComposeBarAction> = when (section) {
    BarSection.Toolbar -> toolbar
    BarSection.Overflow -> overflow
    BarSection.Hidden -> hidden
}
