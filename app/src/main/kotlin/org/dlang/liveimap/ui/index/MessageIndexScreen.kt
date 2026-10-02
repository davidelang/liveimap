package org.dlang.liveimap.ui.index

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.DateFormat
import org.dlang.liveimap.settings.FolderView
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.SwipeBinding
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

private val indexFlags = listOf("\\Seen", "\\Answered", "\\Flagged", "\\Deleted")

private enum class FilterRole {
    Criterion,
    All,
    Narrow,
    Widen,
}

private data class FilterChoice(
    val label: String,
    val kind: String = "",
    val needsValue: Boolean = false,
    val role: FilterRole = FilterRole.Criterion,
)

private val filterChoices = listOf(
    FilterChoice("All", role = FilterRole.All),
    FilterChoice("New", "New"),
    FilterChoice("Not new", "NotNew"),
    FilterChoice("Deleted", "Deleted"),
    FilterChoice("Not deleted", "NotDeleted"),
    FilterChoice("Answered", "Answered"),
    FilterChoice("Not answered", "NotAnswered"),
    FilterChoice("Important", "Important"),
    FilterChoice("Not important", "NotImportant"),
    FilterChoice("Forwarded", "Forwarded"),
    FilterChoice("Not forwarded", "NotForwarded"),
    FilterChoice("From", "From", needsValue = true),
    FilterChoice("To", "To", needsValue = true),
    FilterChoice("Cc", "Cc", needsValue = true),
    FilterChoice("Subject", "Subject", needsValue = true),
    FilterChoice("All text", "Text", needsValue = true),
    FilterChoice("Recipient", "Recipient", needsValue = true),
    FilterChoice("Participant", "Participant", needsValue = true),
    FilterChoice("Since", "Since", needsValue = true),
    FilterChoice("Before", "Before", needsValue = true),
    FilterChoice("On", "On", needsValue = true),
    FilterChoice("Age", "Age", needsValue = true),
    FilterChoice("Larger", "Larger", needsValue = true),
    FilterChoice("Smaller", "Smaller", needsValue = true),
    FilterChoice("Keyword", "Keyword", needsValue = true),
    FilterChoice("Not keyword", "NotKeyword", needsValue = true),
    FilterChoice("Narrow", role = FilterRole.Narrow),
    FilterChoice("Widen", role = FilterRole.Widen),
)

data class IndexAppearance(
    val alpha: Float,
    val strikethrough: Boolean,
)

private val localIndexDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val sameDayIndexDate = DateTimeFormatter.ofPattern("HH:mm")
private val sameYearIndexDate = DateTimeFormatter.ofPattern("MMM d")
private val otherYearIndexDate = DateTimeFormatter.ofPattern("MMM yyyy")

private const val relativeNowSeconds = 45L
private const val minuteSeconds = 60L
private const val hourSeconds = 3600L
private const val daySeconds = 86400L
private const val relativeDayLimit = 7L

fun formatIndexDate(
    epochSeconds: Long,
    format: DateFormat,
    pattern: String,
    nowEpoch: Long,
    zone: ZoneId,
): String {
    if (epochSeconds == 0L) return ""
    val whenZoned = Instant.ofEpochSecond(epochSeconds).atZone(zone)
    val nowZoned = Instant.ofEpochSecond(nowEpoch).atZone(zone)
    return when (format) {
        DateFormat.Local -> localIndexDate.format(whenZoned)
        DateFormat.Short -> shortIndexDate(whenZoned, nowZoned)
        DateFormat.Relative -> relativeIndexDate(epochSeconds, whenZoned, nowEpoch, nowZoned)
        DateFormat.Custom -> customIndexDate(whenZoned, pattern)
    }
}

private fun shortIndexDate(whenZoned: ZonedDateTime, nowZoned: ZonedDateTime): String {
    val formatter = when {
        whenZoned.toLocalDate() == nowZoned.toLocalDate() -> sameDayIndexDate
        whenZoned.year == nowZoned.year -> sameYearIndexDate
        else -> otherYearIndexDate
    }
    return formatter.format(whenZoned)
}

private fun relativeIndexDate(
    epochSeconds: Long,
    whenZoned: ZonedDateTime,
    nowEpoch: Long,
    nowZoned: ZonedDateTime,
): String {
    val delta = epochSeconds - nowEpoch
    val magnitude = if (delta < 0L) -delta else delta
    if (magnitude < relativeNowSeconds) return "now"
    if (magnitude >= relativeDayLimit * daySeconds) return shortIndexDate(whenZoned, nowZoned)
    val days = magnitude / daySeconds
    if (days >= 1L) return relativeUnit(delta < 0L, days, "day", "days")
    val hours = magnitude / hourSeconds
    if (hours >= 1L) return relativeUnit(delta < 0L, hours, "hour", "hours")
    val minutes = (magnitude / minuteSeconds).coerceAtLeast(1L)
    return relativeUnit(delta < 0L, minutes, "min", "min")
}

private fun relativeUnit(past: Boolean, count: Long, one: String, many: String): String {
    val unit = if (count == 1L) one else many
    return if (past) "$count $unit ago" else "in $count $unit"
}

private fun customIndexDate(whenZoned: ZonedDateTime, pattern: String): String {
    if (pattern.isEmpty()) return "bad date pattern"
    val formatter = try {
        DateTimeFormatter.ofPattern(pattern)
    } catch (error: IllegalArgumentException) {
        return "bad date pattern"
    }
    return try {
        formatter.format(whenZoned)
    } catch (error: DateTimeException) {
        "bad date pattern"
    }
}

fun indexAppearance(flags: Set<String>): IndexAppearance {
    val seen = "\\Seen" in flags
    val deleted = "\\Deleted" in flags
    return IndexAppearance(
        alpha = if (seen) 0.55f else 1f,
        strikethrough = deleted,
    )
}

private val markImportant = Color(0xFFFFC107)
private val markToMe = Color(0xFF1976D2)
private val markReplied = Color(0xFF388E3C)
private val markForwarded = Color(0xFF7B1FA2)
private val markAttachment = Color(0xFF795548)

private fun indexMarkColors(row: IndexRow): List<Color> = buildList {
    if ("\\Flagged" in row.flags) add(markImportant)
    if (row.toMe) add(markToMe)
    if ("\\Answered" in row.flags) add(markReplied)
    if ("\$Forwarded" in row.flags) add(markForwarded)
    if (row.hasAttachment) add(markAttachment)
}

@Composable
private fun IndexMarkDots(marks: List<Color>, modifier: Modifier = Modifier) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.Top,
    ) {
        for (column in marks.chunked(4)) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                for (color in column) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .background(color, CircleShape),
                    )
                }
            }
        }
    }
}

private class SnapshotSync {
    var block: () -> Unit = {}
}

@Composable
fun MessageIndexScreen(
    mailbox: String,
    onOpen: (Long, Int) -> Unit,
    onCompose: (ComposeSeed) -> Unit,
    onBack: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    val session = remember { mailSession() }
    val model = remember(session, store, mailbox) { IndexModel(session, store, mailbox) }
    val scope = rememberCoroutineScope()
    val gate = remember { Mutex() }
    val sync = remember { SnapshotSync() }
    val listState = rememberLazyListState()
    var rows by remember { mutableStateOf<List<IndexRow>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var view by remember { mutableStateOf(FolderView(SortKey.Arrival, true)) }
    var account by remember { mutableStateOf(AccountSettings()) }
    var menuKeys by remember { mutableStateOf(model.menuKeys) }
    var anchorPage by remember { mutableStateOf(0) }
    var connected by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var searchVisible by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filterOpen by remember { mutableStateOf(false) }
    var filterActive by remember { mutableStateOf(false) }
    var canWiden by remember { mutableStateOf(false) }
    var narrowArmed by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<FilterChoice?>(null) }
    var promptText by remember { mutableStateOf("") }
    var multiSelect by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<List<Long>>(emptyList()) }
    var flagUid by remember { mutableStateOf<Long?>(null) }
    val leftToRight = LocalLayoutDirection.current == LayoutDirection.Ltr
    val anchorState = rememberUpdatedState(anchorPage)
    val allowNewer = remember { mutableStateOf(true) }

    sync.block = {
        rows = model.rows
        notice = model.notice
        view = model.view
        account = model.account
        menuKeys = model.menuKeys
        anchorPage = model.anchorPage
        filterActive = model.filterActive
        canWiden = model.canWiden
    }

    fun pull() = sync.block

    fun runCriterion(kind: String, argument: String) {
        val narrow = narrowArmed
        scope.launch {
            gate.withLock {
                model.applyCriterion(kind, argument, narrow)
                pull()
            }
            if (model.notice == null) {
                narrowArmed = false
                query = ""
                searchVisible = false
            }
            if (model.rows.isNotEmpty()) listState.scrollToItem(0)
        }
    }

    fun runShowAll() {
        scope.launch {
            gate.withLock {
                model.showAll()
                pull()
            }
            if (model.notice == null) {
                narrowArmed = false
                query = ""
                searchVisible = false
            }
            if (model.rows.isNotEmpty()) listState.scrollToItem(0)
        }
    }

    fun runWiden() {
        scope.launch {
            gate.withLock {
                model.widenFilter()
                pull()
            }
            if (model.notice == null) narrowArmed = false
            if (model.rows.isNotEmpty()) listState.scrollToItem(0)
        }
    }

    LaunchedEffect(session, mailbox) {
        val watchNow = gate.withLock {
            val settings = try {
                store.load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = error.message ?: "not connected"
                return@withLock false
            }
            try {
                store.password()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                notice = error.message ?: "not connected"
                return@withLock false
            }
            val opened = try {
                session.open(settings)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                notice = error.text
                return@withLock false
            }
            when (opened) {
                is OpenResult.Rejected -> {
                    notice = opened.capabilities
                    return@withLock false
                }
                is OpenResult.Failed -> {
                    notice = opened.text
                    return@withLock false
                }
                OpenResult.Connected -> Unit
            }
            model.loadWindow()
            pull()
            connected = true
            true
        }
        if (!watchNow) return@LaunchedEffect
        try {
            model.watch { change ->
                scope.launch {
                    gate.withLock {
                        model.applyChange(change)
                        pull()
                    }
                    if (change !is MailboxChange.Flags && model.rows.isNotEmpty()) {
                        listState.scrollToItem(0)
                    }
                }
            }
            awaitCancellation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: MailFailure) {
            notice = error.text
        } finally {
            withContext(NonCancellable) {
                try {
                    model.stopWatch()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: MailFailure) {
                }
            }
        }
    }

    LaunchedEffect(listState, connected) {
        if (!connected) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            val before = model.anchorPage
            gate.withLock {
                model.onFirstVisible(index)
                pull()
            }
            if (model.anchorPage != before && model.rows.isNotEmpty()) {
                listState.scrollToItem(0)
            }
        }
    }

    val newerConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0f) allowNewer.value = true
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && allowNewer.value && anchorState.value > 0 && !listState.canScrollBackward) {
                    allowNewer.value = false
                    scope.launch {
                        val before = model.anchorPage
                        gate.withLock {
                            model.revealNewer()
                            sync.block()
                        }
                        if (model.anchorPage < before && model.rows.isNotEmpty()) {
                            val target = (IndexPageSize - 1).coerceAtMost(model.rows.lastIndex)
                            if (target >= 0) listState.scrollToItem(target)
                        }
                    }
                }
                return Offset.Zero
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        val message = notice
        if (message != null) {
            Text(text = message, modifier = Modifier.padding(8.dp))
        }
        if (connected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
                Text(
                    text = mailbox,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { searchVisible = true }) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Search",
                    )
                }
                Box {
                    IconButton(onClick = { filterOpen = true }) {
                        Icon(
                            imageVector = filterImage,
                            contentDescription = "Filter",
                        )
                    }
                    DropdownMenu(
                        expanded = filterOpen,
                        onDismissRequest = { filterOpen = false },
                    ) {
                        filterChoices.forEach { choice ->
                            val enabled = when (choice.role) {
                                FilterRole.Narrow -> filterActive
                                FilterRole.Widen -> canWiden
                                else -> true
                            }
                            DropdownMenuItem(
                                text = { Text(choice.label) },
                                enabled = enabled,
                                onClick = {
                                    filterOpen = false
                                    when (choice.role) {
                                        FilterRole.All -> runShowAll()
                                        FilterRole.Narrow -> if (filterActive) narrowArmed = true
                                        FilterRole.Widen -> if (canWiden) runWiden()
                                        FilterRole.Criterion -> {
                                            if (choice.needsValue) {
                                                prompt = choice
                                                promptText = ""
                                            } else {
                                                runCriterion(choice.kind, "")
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                Box {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.List,
                                contentDescription = "Sort",
                            )
                        }
                        Text(
                            text = sortShortLabel(view.key),
                            modifier = Modifier.clickable { menuOpen = true },
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        menuKeys.forEach { key ->
                            DropdownMenuItem(
                                text = {
                                    Text(if (key == view.key) "[${sortShortLabel(key)}]" else sortShortLabel(key))
                                },
                                onClick = {
                                    menuOpen = false
                                    query = ""
                                    narrowArmed = false
                                    prompt = null
                                    scope.launch {
                                        gate.withLock {
                                            model.applyView(FolderView(key, view.newestFirst))
                                            pull()
                                        }
                                        if (model.rows.isNotEmpty()) listState.scrollToItem(0)
                                    }
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (view.newestFirst) "Newest first" else "Oldest first") },
                            onClick = {
                                menuOpen = false
                                query = ""
                                narrowArmed = false
                                prompt = null
                                scope.launch {
                                    gate.withLock {
                                        model.applyView(view.copy(newestFirst = !view.newestFirst))
                                        pull()
                                    }
                                    if (model.rows.isNotEmpty()) listState.scrollToItem(0)
                                }
                            },
                        )
                    }
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            gate.withLock {
                                model.expunge()
                                pull()
                            }
                            if (model.rows.isNotEmpty()) listState.scrollToItem(0)
                        }
                    },
                ) { Text("Expunge") }
            }
            if (searchVisible) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { next ->
                        val cleared = next.isEmpty() && query.isNotEmpty()
                        query = next
                        if (cleared) {
                            searchVisible = false
                            narrowArmed = false
                            prompt = null
                            scope.launch {
                                gate.withLock {
                                    model.applySearch("")
                                    pull()
                                }
                                if (model.rows.isNotEmpty()) listState.scrollToItem(0)
                            }
                        }
                    },
                    label = { Text("Search") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            narrowArmed = false
                            prompt = null
                            scope.launch {
                                gate.withLock {
                                    model.applySearch(query)
                                    pull()
                                }
                                if (model.rows.isNotEmpty()) listState.scrollToItem(0)
                            }
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )
            }
            if (multiSelect) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(
                        onClick = {
                            multiSelect = false
                            selected = emptyList()
                        },
                    ) { Text("Cancel") }
                    indexFlags.forEach { flag ->
                        TextButton(
                            onClick = {
                                scope.launch {
                                    gate.withLock {
                                        model.changeFlags(selected, setOf(flag), emptySet())
                                        pull()
                                    }
                                }
                            },
                        ) { Text("Set $flag") }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    gate.withLock {
                                        model.changeFlags(selected, emptySet(), setOf(flag))
                                        pull()
                                    }
                                }
                            },
                        ) { Text("Clear $flag") }
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                gate.withLock {
                                    model.moveMessages(selected, barMoveMailbox(account))
                                    pull()
                                }
                            }
                        },
                    ) { Text("Move") }
                    TextButton(
                        onClick = {
                            scope.launch {
                                gate.withLock {
                                    model.deleteMessages(selected)
                                    pull()
                                }
                            }
                        },
                    ) { Text("Delete") }
                    TextButton(onClick = { onCompose(model.bounceSeed(selected)) }) { Text("Bounce") }
                }
            }
            val flagsFor = flagUid
            if (flagsFor != null) {
                Text("Flags", modifier = Modifier.padding(horizontal = 8.dp))
                indexFlags.forEach { flag ->
                    Row {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    gate.withLock {
                                        model.changeFlags(listOf(flagsFor), setOf(flag), emptySet())
                                        pull()
                                    }
                                }
                            },
                        ) { Text("Set $flag") }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    gate.withLock {
                                        model.changeFlags(listOf(flagsFor), emptySet(), setOf(flag))
                                        pull()
                                    }
                                }
                            },
                        ) { Text("Clear $flag") }
                    }
                }
                TextButton(onClick = { flagUid = null }) { Text("Close") }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .nestedScroll(newerConnection),
            ) {
                items(rows, key = { it.uid }) { row ->
                    IndexMessageRow(
                        row = row,
                        account = account,
                        selected = row.uid in selected,
                        multiSelect = multiSelect,
                        leftToRight = leftToRight,
                        onClick = {
                            if (multiSelect) {
                                selected = if (row.uid in selected) selected - row.uid else selected + row.uid
                            } else {
                                val seed = model.openSeed(row.uid)
                                if (seed != null) onCompose(seed) else onOpen(row.uid, row.sequence)
                            }
                        },
                        onLongPress = {
                            multiSelect = true
                            if (row.uid !in selected) selected = selected + row.uid
                        },
                        onSwipe = { binding ->
                            gate.withLock {
                                when (val command = model.performSwipe(row.uid, binding)) {
                                    is IndexCommand.Compose -> onCompose(command.seed)
                                    is IndexCommand.ShowFlags -> flagUid = command.uid
                                    IndexCommand.None -> Unit
                                }
                                pull()
                            }
                        },
                    )
                }
            }
        }
    }
    val pendingPrompt = prompt
    if (pendingPrompt != null) {
        AlertDialog(
            onDismissRequest = { prompt = null },
            text = {
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    label = { Text(pendingPrompt.label) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val kind = pendingPrompt.kind
                    val value = promptText
                    prompt = null
                    runCriterion(kind, value)
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { prompt = null }) { Text("Dismiss") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IndexMessageRow(
    row: IndexRow,
    account: AccountSettings,
    selected: Boolean,
    multiSelect: Boolean,
    leftToRight: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onSwipe: suspend (SwipeBinding) -> Unit,
) {
    var width by remember { mutableIntStateOf(0) }
    val holder = remember { SwipeBoxHolder() }
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { distance -> distance * SwipeWidthPercent / 100f },
        confirmValueChange = { target ->
            if (target == SwipeToDismissBoxValue.Settled) {
                true
            } else if (multiSelect) {
                false
            } else {
                val box = holder.state ?: return@rememberSwipeToDismissBoxState false
                val offset = runCatching { box.requireOffset() }.getOrDefault(0f)
                swipeReached(offset, width.toFloat())
            }
        },
    )
    holder.state = dismissState
    LaunchedEffect(dismissState.currentValue) {
        val value = dismissState.currentValue
        if (value == SwipeToDismissBoxValue.Settled || multiSelect) return@LaunchedEffect
        val binding = indexSwipeBinding(
            offsetPx = dismissOffset(value, width.toFloat(), leftToRight),
            widthPx = width.toFloat().coerceAtLeast(1f),
            leftToRight = leftToRight,
            multiSelect = false,
            trailing = account.swipeTrailing,
            leading = account.swipeLeading,
        )
        if (binding != null) onSwipe(binding)
        dismissState.reset()
    }
    Box(Modifier.onSizeChanged { width = it.width }.fillMaxWidth()) {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = !multiSelect,
            enableDismissFromEndToStart = !multiSelect,
            gesturesEnabled = !multiSelect,
            backgroundContent = {
                val direction = dismissState.targetValue
                val binding = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> account.swipeLeading
                    SwipeToDismissBoxValue.EndToStart -> account.swipeTrailing
                    SwipeToDismissBoxValue.Settled -> null
                }
                if (binding != null) {
                    Box(Modifier.fillMaxSize()) {
                        Text(
                            text = binding.action.name,
                            modifier = Modifier
                                .align(
                                    if (direction == SwipeToDismissBoxValue.StartToEnd) {
                                        Alignment.CenterStart
                                    } else {
                                        Alignment.CenterEnd
                                    },
                                )
                                .padding(horizontal = 8.dp),
                        )
                    }
                }
            },
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .combinedClickable(onLongClick = onLongPress, onClick = onClick)
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                val appearance = indexAppearance(row.flags)
                val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = appearance.alpha)
                val decoration = if (appearance.strikethrough) TextDecoration.LineThrough else TextDecoration.None
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(48.dp)) {
                        if (row.sequence != 0) {
                            Text(
                                text = row.sequence.toString(),
                                modifier = Modifier.fillMaxWidth(),
                                color = textColor,
                                textDecoration = decoration,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                    val marks = indexMarkColors(row)
                    IndexMarkDots(
                        marks,
                        Modifier
                            .padding(end = 4.dp)
                            .widthIn(min = 6.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val from = if (selected) "selected ${row.from}" else row.from
                            Text(
                                text = from,
                                modifier = Modifier.weight(1f),
                                color = textColor,
                                textDecoration = decoration,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = formatIndexDate(
                                    epochSeconds = row.internalDateEpoch,
                                    format = account.dateFormat,
                                    pattern = account.datePattern,
                                    nowEpoch = Instant.now().epochSecond,
                                    zone = ZoneId.systemDefault(),
                                ),
                                color = textColor,
                                textDecoration = decoration,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                            )
                        }
                        Text(
                            text = row.subject,
                            color = textColor,
                            textDecoration = decoration,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                val lines = previewLineCount(account.density)
                val preview = row.preview
                if (lines > 0 && preview != null) {
                    Text(
                        text = preview,
                        color = textColor,
                        textDecoration = decoration,
                        maxLines = lines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun sortShortLabel(key: SortKey): String = when (key) {
    SortKey.ThreadReferences -> "Thread"
    SortKey.ThreadOrderedSubject -> "Ordered"
    else -> key.name
}

private class SwipeBoxHolder {
    var state: SwipeToDismissBoxState? = null
}

private fun dismissOffset(value: SwipeToDismissBoxValue, widthPx: Float, leftToRight: Boolean): Float {
    val span = if (widthPx > 0f) widthPx else 1f
    return when (value) {
        SwipeToDismissBoxValue.EndToStart -> if (leftToRight) -span else span
        SwipeToDismissBoxValue.StartToEnd -> if (leftToRight) span else -span
        SwipeToDismissBoxValue.Settled -> 0f
    }
}
