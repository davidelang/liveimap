package org.dlang.liveimap.ui.toolbar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.horizontalScroll
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import org.dlang.liveimap.settings.ToolbarRevision
import org.dlang.liveimap.ui.LocalScreenAccounts
import org.dlang.liveimap.ui.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableColumn
import sh.calvin.reorderable.ReorderableListItemScope
import sh.calvin.reorderable.ReorderableListScope
import sh.calvin.reorderable.ReorderableListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.WeakHashMap
import com.davidlang.liveimap.BuildConfig
import com.davidlang.liveimap.R
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.DataStoreSettingsStore

enum class ToolbarScreen {
    Index,
    Selection,
    Folders,
    Reader,
    Compose,
}

private class ToolbarSettings(
    private val appContext: android.content.Context,
    private val scope: CoroutineScope,
) {
    var committed = false

    suspend fun load() = Unit

    private fun write(next: ToolbarRecord) {
        ToolbarStore.replace(next)
    }

    fun save() {
        scope.launch {
            ToolbarStore.save(appContext)
            committed = true
            ToolbarRevision.bump()
        }
    }

    fun persistIndex(next: IndexBarLayout) {
        write(ToolbarStore.record.copy(indexBar = next))
    }

    fun persistSelection(next: SelectionBarLayout) {
        write(ToolbarStore.record.copy(selectionBar = next))
    }

    fun persistFolder(next: FolderBarLayout) {
        write(ToolbarStore.record.copy(folderBar = next))
    }

    fun persistReader(next: ReaderToolbarLayout) {
        write(ToolbarStore.record.copy(readerToolbar = next))
    }

    fun persistCompose(next: ComposeBarLayout) {
        write(ToolbarStore.record.copy(composeBar = next))
    }

    fun writeToolbarRows(next: Int) {
        if (next !in 1..4 || next == ToolbarStore.toolbarRows()) return
        write(ToolbarStore.record.copy(toolbarRows = next))
    }
}

@Composable
fun ToolbarEditorScreen(screen: ToolbarScreen, scrollState: ScrollState) {
    val appContext = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val holder = remember(appContext, scope) { ToolbarSettings(appContext, scope) }
    LaunchedEffect(holder) { holder.load() }
    DisposableEffect(holder) {
        onDispose {
            if (!holder.committed) {
                ToolbarStore.install(org.dlang.liveimap.settings.SettingsHub.uiNow().toolbars)
            }
        }
    }
    var announcement by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
    ) {
        Text(
            text = announcement,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        androidx.compose.material3.Button(onClick = { holder.save() }) {
            Text(stringResource(com.davidlang.liveimap.R.string.settings_save))
        }
        val loaded = ToolbarStore.record
        when (screen) {
        ToolbarScreen.Index -> SectionEditor(
            rowsIn = { section -> loaded.indexBar.actionsIn(section) },
            labelRes = ::indexBarActionRes,
            onMoveBy = { action, delta ->
                val current = ToolbarStore.record.indexBar
                holder.persistIndex(moveIndexActionBy(current, action, delta))
                movedBySentence(appContext, action, delta, ::indexBarActionRes)?.let { announcement = it }
            },
            onMoveTo = { action, target ->
                val current = ToolbarStore.record.indexBar
                holder.persistIndex(moveIndexAction(current, action, target))
                announcement = movedToSentence(appContext, action, target, ::indexBarActionRes)
            },
            onDrag = { from, to ->
                val current = ToolbarStore.record.indexBar
                val landing = dragLanding(current.toolbar, current.overflow, current.hidden, from, to)
                holder.persistIndex(dragIndexLayout(current, from, to))
                if (landing != null) {
                    val (action, section) = landing
                    announcement = movedToSentence(appContext, action, section, ::indexBarActionRes)
                }
            },
            onReset = { holder.persistIndex(resetIndexBar()) },
            toolbarRows = loaded.toolbarRows,
            onToolbarRows = { next -> holder.writeToolbarRows(next) },
            scrollState = scrollState,
        )
        ToolbarScreen.Selection -> SectionEditor(
            rowsIn = { section -> loaded.selectionBar.actionsIn(section) },
            labelRes = ::selectionBarActionRes,
            onMoveBy = { action, delta ->
                val current = ToolbarStore.record.selectionBar
                holder.persistSelection(moveSelectionActionBy(current, action, delta))
                movedBySentence(appContext, action, delta, ::selectionBarActionRes)?.let { announcement = it }
            },
            onMoveTo = { action, target ->
                val current = ToolbarStore.record.selectionBar
                holder.persistSelection(moveSelectionAction(current, action, target))
                announcement = movedToSentence(appContext, action, target, ::selectionBarActionRes)
            },
            onDrag = { from, to ->
                val current = ToolbarStore.record.selectionBar
                val landing = dragLanding(current.toolbar, current.overflow, current.hidden, from, to)
                holder.persistSelection(dragSelectionLayout(current, from, to))
                if (landing != null) {
                    val (action, section) = landing
                    announcement = movedToSentence(appContext, action, section, ::selectionBarActionRes)
                }
            },
            onReset = { holder.persistSelection(resetSelectionBar()) },
            toolbarRows = loaded.toolbarRows,
            onToolbarRows = { next -> holder.writeToolbarRows(next) },
            scrollState = scrollState,
        )
        ToolbarScreen.Folders -> SectionEditor(
            rowsIn = { section -> loaded.folderBar.actionsIn(section) },
            labelRes = ::folderBarActionRes,
            onMoveBy = { action, delta ->
                val current = ToolbarStore.record.folderBar
                holder.persistFolder(moveFolderActionBy(current, action, delta))
                movedBySentence(appContext, action, delta, ::folderBarActionRes)?.let { announcement = it }
            },
            onMoveTo = { action, target ->
                val current = ToolbarStore.record.folderBar
                holder.persistFolder(moveFolderAction(current, action, target))
                announcement = movedToSentence(appContext, action, target, ::folderBarActionRes)
            },
            onDrag = { from, to ->
                val current = ToolbarStore.record.folderBar
                val landing = dragLanding(current.toolbar, current.overflow, current.hidden, from, to)
                holder.persistFolder(dragFolderLayout(current, from, to))
                if (landing != null) {
                    val (action, section) = landing
                    announcement = movedToSentence(appContext, action, section, ::folderBarActionRes)
                }
            },
            onReset = { holder.persistFolder(resetFolderBar()) },
            toolbarRows = loaded.toolbarRows,
            onToolbarRows = { next -> holder.writeToolbarRows(next) },
            scrollState = scrollState,
        )
        ToolbarScreen.Reader -> SectionEditor(
            rowsIn = { section -> effectiveReaderToolbar().actionsIn(section) },
            labelRes = ::readerToolbarActionRes,
            onMoveBy = { action, delta ->
                val current = effectiveReaderToolbar()
                holder.persistReader(moveReaderActionBy(current, action, delta))
                movedBySentence(appContext, action, delta, ::readerToolbarActionRes)?.let { announcement = it }
            },
            onMoveTo = { action, target ->
                val current = effectiveReaderToolbar()
                holder.persistReader(moveReaderAction(current, action, target))
                announcement = movedToSentence(appContext, action, target, ::readerToolbarActionRes)
            },
            onDrag = { from, to ->
                val current = effectiveReaderToolbar()
                val landing = dragLanding(current.toolbar, current.overflow, current.hidden, from, to)
                holder.persistReader(dragReaderLayout(current, from, to))
                if (landing != null) {
                    val (action, section) = landing
                    announcement = movedToSentence(appContext, action, section, ::readerToolbarActionRes)
                }
            },
            onReset = { holder.persistReader(resetReaderToolbar()) },
            toolbarRows = loaded.toolbarRows,
            onToolbarRows = { next -> holder.writeToolbarRows(next) },
            scrollState = scrollState,
        )
        ToolbarScreen.Compose -> SectionEditor(
            rowsIn = { section -> loaded.composeBar.actionsIn(section) },
            labelRes = ::composeBarActionRes,
            onMoveBy = { action, delta ->
                val current = ToolbarStore.record.composeBar
                holder.persistCompose(moveComposeActionBy(current, action, delta))
                movedBySentence(appContext, action, delta, ::composeBarActionRes)?.let { announcement = it }
            },
            onMoveTo = { action, target ->
                val current = ToolbarStore.record.composeBar
                holder.persistCompose(moveComposeAction(current, action, target))
                announcement = movedToSentence(appContext, action, target, ::composeBarActionRes)
            },
            onDrag = { from, to ->
                val current = ToolbarStore.record.composeBar
                val landing = dragLanding(current.toolbar, current.overflow, current.hidden, from, to)
                holder.persistCompose(dragComposeLayout(current, from, to))
                if (landing != null) {
                    val (action, section) = landing
                    announcement = movedToSentence(appContext, action, section, ::composeBarActionRes)
                }
            },
            onReset = { holder.persistCompose(resetComposeBar()) },
            toolbarRows = loaded.toolbarRows,
            onToolbarRows = { next -> holder.writeToolbarRows(next) },
            scrollState = scrollState,
        )
        }
    }
}

@Composable
fun ToolbarEditorPage(
    screen: ToolbarScreen,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coords ->
                val bounds = coords.boundsInRoot()
                notePaneScroll(scroll, bounds.top, bounds.bottom)
            }
            .verticalScroll(scroll),
    ) {
        ToolbarEditorScreen(screen, scroll)
    }
}

private sealed interface EditorLine<out A> {
    val key: String

    data class Header<A>(val section: BarSection) : EditorLine<A> {
        override val key: String = "section-${section.name}"
    }

    data class Action<A>(val section: BarSection, val action: A, val index: Int, val count: Int) : EditorLine<A> {
        override val key: String = "action-${(action as Enum<*>).name}"
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
    scrollState: ScrollState,
) {
    val lines = buildList {
        for (section in BarSection.entries) {
            add(EditorLine.Header(section))
            val rows = rowsIn(section)
            rows.forEachIndexed { index, action ->
                add(EditorLine.Action(section, action, index, rows.size))
            }
        }
    }
    val drag = rememberUpdatedState(onDrag)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReorderableColumn(
            list = lines,
            onSettle = { from, to -> drag.value(from, to) },
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { index, line, isDragging ->
            val reorderScope = this
            key(line.key) {
                ReorderableItem {
                    // Drag draws with graphicsLayer translation, which does not place the item again.
                    var itemCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                    if (isDragging) {
                        val edgePx = with(LocalDensity.current) { 48.dp.toPx() }
                        LaunchedEffect(scrollState) {
                            while (isActive) {
                                withFrameNanos { }
                                val pane = paneEdge(scrollState)
                                val coords = itemCoords
                                if (pane == null || pane.top.isNaN() || coords == null || !coords.isAttached) continue
                                val item = coords.boundsInRoot()
                                val step = when {
                                    item.top <= pane.top + edgePx && scrollState.value > 0 ->
                                        -minOf(24f, scrollState.value.toFloat())
                                    item.bottom >= pane.bottom - edgePx && scrollState.value < scrollState.maxValue ->
                                        minOf(24f, (scrollState.maxValue - scrollState.value).toFloat())
                                    else -> 0f
                                }
                                if (step == 0f) continue
                                var consumed = 0f
                                scrollState.scroll { consumed = scrollBy(step) }
                                // itemOffsets is only the finger delta. Add the scroll or the row leaves the finger.
                                if (consumed != 0f) addDragOffset(reorderScope, index, consumed)
                            }
                        }
                    }
                    Column(
                        Modifier.onGloballyPositioned { itemCoords = it },
                    ) {
                        when (line) {
                            is EditorLine.Header -> Text(
                                text = sectionTitle(line.section),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            is EditorLine.Action -> ToolbarEditorRow(
                                labelRes = labelRes(line.action),
                                section = line.section,
                                index = line.index,
                                count = line.count,
                                onMoveBy = { delta -> onMoveBy(line.action, delta) },
                                onMoveTo = { target -> onMoveTo(line.action, target) },
                            )
                        }
                    }
                }
            }
        }
        TextButton(onClick = onReset) {
            Text(stringResource(R.string.toolbar_reset))
        }
        val toolbarCount = rowsIn(BarSection.Toolbar).size
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.toolbar_rows))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(toolbarRows.toString())
                TextButton(
                    onClick = { onToolbarRows(toolbarRows - 1) },
                    enabled = toolbarRows > 1,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Text(stringResource(R.string.toolbar_rows_fewer))
                }
                TextButton(
                    onClick = { onToolbarRows(toolbarRows + 1) },
                    enabled = toolbarRows < 4,
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Text(stringResource(R.string.toolbar_rows_more))
                }
            }
            // Editor budget is 3 slots. Each bar measures its own width.
            if (packToolbar(toolbarCount, 3, toolbarRows).overflowsCap) {
                Text(stringResource(R.string.toolbar_rows_warning, toolbarRows))
            }
        }
    }
}

@Composable
private fun ReorderableListItemScope.ToolbarEditorRow(
    labelRes: Int,
    section: BarSection,
    index: Int,
    count: Int,
    onMoveBy: (Int) -> Unit,
    onMoveTo: (BarSection) -> Unit,
) {
    val moveUp = stringResource(R.string.toolbar_move_up)
    val moveDown = stringResource(R.string.toolbar_move_down)
    val moveTo = BarSection.entries.filter { it != section }.map { target ->
        target to stringResource(R.string.toolbar_move_to, sectionTitle(target))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                customActions = buildList {
                    if (index > 0) {
                        add(
                            CustomAccessibilityAction(moveUp) {
                                onMoveBy(-1)
                                true
                            },
                        )
                    }
                    if (index < count - 1) {
                        add(
                            CustomAccessibilityAction(moveDown) {
                                onMoveBy(1)
                                true
                            },
                        )
                    }
                    for ((target, label) in moveTo) {
                        add(
                            CustomAccessibilityAction(label) {
                                onMoveTo(target)
                                true
                            },
                        )
                    }
                }
            },
    ) {
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
                TextButton(
                    onClick = { onMoveBy(-1) },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Text(moveUp)
                }
            }
            if (index < count - 1) {
                TextButton(
                    onClick = { onMoveBy(1) },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Text(moveDown)
                }
            }
            for ((target, label) in moveTo) {
                TextButton(
                    onClick = { onMoveTo(target) },
                    modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                ) {
                    Text(label)
                }
            }
        }
    }
}

private fun sectionTitleRes(section: BarSection): Int = when (section) {
    BarSection.Toolbar -> R.string.toolbar_section_toolbar
    BarSection.Overflow -> R.string.toolbar_section_overflow
    BarSection.Hidden -> R.string.toolbar_section_hidden
}

@Composable
private fun sectionTitle(section: BarSection): String = stringResource(sectionTitleRes(section))

private fun <A> movedBySentence(
    context: Context,
    action: A,
    delta: Int,
    labelOf: (A) -> Int,
): String? {
    val res = when (delta) {
        -1 -> R.string.toolbar_moved_up
        1 -> R.string.toolbar_moved_down
        else -> return null
    }
    return context.getString(res, context.getString(labelOf(action)))
}

private fun <A> movedToSentence(
    context: Context,
    action: A,
    section: BarSection,
    labelOf: (A) -> Int,
): String = context.getString(
    R.string.toolbar_moved_to,
    context.getString(labelOf(action)),
    context.getString(sectionTitleRes(section)),
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
    ReaderToolbarAction.Headers -> R.string.reader_all_headers
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

private class PaneEdge {
    var top = Float.NaN
    var bottom = Float.NaN
}

private val paneEdges = WeakHashMap<ScrollState, PaneEdge>()

internal fun notePaneScroll(scroll: ScrollState, top: Float, bottom: Float) {
    val edge = paneEdges.getOrPut(scroll) { PaneEdge() }
    edge.top = top
    edge.bottom = bottom
}

private fun paneEdge(scroll: ScrollState): PaneEdge? = paneEdges[scroll]

// Reorderable 3.1.0 keeps the drag offset internal. The same scroll must be added there or positionInParent stays put.
private suspend fun addDragOffset(scope: ReorderableListScope, index: Int, delta: Float) {
    val state = ReorderAccess.state(scope)
    val anim = ReorderAccess.offset(state, index) ?: return
    val drag = ReorderAccess.drag(state, index) ?: return
    anim.snapTo(anim.targetValue + delta)
    drag.dispatchRawDelta(0f)
}

private object ReorderAccess {
    private val suffix = if (BuildConfig.DEBUG) "reorderable_debug" else "reorderable_release"
    private val stateOf by lazy {
        member(ReorderableListScope::class.java, "access\$getState\$p", ReorderableListScope::class.java)
    }
    private val offsetsOf by lazy { member(ReorderableListState::class.java, "getItemOffsets\$$suffix") }
    private val dragsOf by lazy { member(ReorderableListState::class.java, "getDraggableStates\$$suffix") }

    private fun member(type: Class<*>, name: String, vararg params: Class<*>): java.lang.reflect.Method =
        try {
            type.getMethod(name, *params)
        } catch (_: NoSuchMethodException) {
            throw IllegalStateException("reorderable drag offset member missing: $name")
        }

    fun state(scope: ReorderableListScope): ReorderableListState =
        stateOf.invoke(null, scope) as ReorderableListState

    @Suppress("UNCHECKED_CAST")
    fun offset(state: ReorderableListState, index: Int): Animatable<Float, AnimationVector1D>? {
        val offsets = offsetsOf.invoke(state) as List<Animatable<Float, AnimationVector1D>>
        return offsets.getOrNull(index)
    }

    @Suppress("UNCHECKED_CAST")
    fun drag(state: ReorderableListState, index: Int): DraggableState? {
        val drags = dragsOf.invoke(state) as List<DraggableState>
        return drags.getOrNull(index)
    }
}
