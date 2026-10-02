package org.dlang.liveimap.ui.index

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.dlang.liveimap.session.ComposeKind
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

private val statusFlagged = Color(0xFFD32F2F)
private val statusToMe = Color(0xFF1976D2)

fun indexStatusDescription(flags: Set<String>, toMe: Boolean, hasAttachment: Boolean): String {
    val parts = ArrayList<String>(4)
    if ("\$Forwarded" in flags) {
        parts.add("forwarded")
    } else if ("\\Answered" in flags) {
        parts.add("replied")
    }
    if ("\\Flagged" in flags) parts.add("flagged")
    if (toMe) parts.add("to me")
    if (hasAttachment) parts.add("has attachment")
    return parts.joinToString(", ")
}

fun sequenceColumnChars(sequences: Iterable<Int>): Int {
    var digits = 0
    for (sequence in sequences) {
        if (sequence == 0) continue
        val count = sequence.toString().length
        if (count > digits) digits = count
    }
    return if (digits == 0) 1 else digits
}

@Composable
private fun IndexStatusColumn(row: IndexRow, modifier: Modifier = Modifier) {
    val forwarded = "\$Forwarded" in row.flags
    val answered = "\\Answered" in row.flags
    val flagged = "\\Flagged" in row.flags
    Row(modifier, verticalAlignment = Alignment.Top) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            val icon = when {
                forwarded -> Icons.AutoMirrored.Filled.Forward
                answered -> Icons.AutoMirrored.Filled.Reply
                else -> null
            }
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
            }
        }
        Box(Modifier.size(10.dp), contentAlignment = Alignment.Center) {
            when {
                flagged && row.toMe -> {
                    Box(Modifier.size(10.dp).background(statusToMe, CircleShape))
                    Box(Modifier.size(6.dp).background(statusFlagged, CircleShape))
                }
                flagged -> Box(Modifier.size(10.dp).background(statusFlagged, CircleShape))
                row.toMe -> Box(Modifier.size(10.dp).background(statusToMe, CircleShape))
            }
        }
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (row.hasAttachment) {
                Icon(
                    imageVector = Icons.Filled.AttachFile,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

private class SnapshotSync {
    var block: () -> Unit = {}
}

fun emptyIndexText(query: String, mailbox: String): String {
    if (query.isBlank()) return "No messages"
    return "No messages match \u201c$query\u201d in $mailbox"
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
    var loading by remember { mutableStateOf(true) }
    var banner by remember { mutableStateOf<String?>(null) }
    var loadToken by remember { mutableIntStateOf(0) }
    var snackEvent by remember { mutableIntStateOf(0) }
    var snackMessage by remember { mutableStateOf("") }
    var lastReported by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    var view by remember { mutableStateOf(FolderView(SortKey.Arrival, true)) }
    var account by remember { mutableStateOf(AccountSettings()) }
    var menuKeys by remember { mutableStateOf(model.menuKeys) }
    var anchorPage by remember { mutableStateOf(0) }
    var connected by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filterOpen by remember { mutableStateOf(false) }
    var filterActive by remember { mutableStateOf(false) }
    var canWiden by remember { mutableStateOf(false) }
    var narrowArmed by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<FilterChoice?>(null) }
    var promptText by remember { mutableStateOf("") }
    var filters by remember { mutableStateOf<List<AppliedFilter>>(emptyList()) }
    var summaries by remember { mutableStateOf<Map<Long, ThreadSummary>>(emptyMap()) }
    var threadAsk by remember { mutableStateOf<FolderView?>(null) }
    var threadAskFromConnect by remember { mutableStateOf(false) }
    var threadExists by remember { mutableIntStateOf(0) }
    var threadConfirmed by rememberSaveable(mailbox) { mutableStateOf(false) }
    val threadChoice = remember(mailbox) { Channel<Boolean>(Channel.CONFLATED) }
    val sequenceMeasurer = rememberTextMeasurer()
    var multiSelect by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<List<Long>>(emptyList()) }
    var flagUid by remember { mutableStateOf<Long?>(null) }
    val leftToRight = LocalLayoutDirection.current == LayoutDirection.Ltr
    val anchorState = rememberUpdatedState(anchorPage)
    val allowNewer = remember { mutableStateOf(true) }

    BackHandler(
        enabled = prompt != null || filterOpen || menuOpen || flagUid != null || searchVisible || multiSelect,
    ) {
        when {
            prompt != null -> prompt = null
            filterOpen -> filterOpen = false
            menuOpen -> menuOpen = false
            flagUid != null -> flagUid = null
            searchVisible -> searchVisible = false
            multiSelect -> {
                multiSelect = false
                selected = emptyList()
            }
        }
    }

    fun postSnack(text: String) {
        lastReported = text
        snackMessage = text
        snackEvent += 1
    }

    sync.block = {
        rows = model.rows
        if (connected) {
            val reported = model.notice
            if (reported == null) {
                lastReported = null
            } else if (reported != lastReported) {
                postSnack(reported)
            }
        }
        view = model.view
        account = model.account
        menuKeys = model.menuKeys
        anchorPage = model.anchorPage
        filterActive = model.filterActive
        canWiden = model.canWiden
        filters = model.filters
        summaries = model.summaries
    }

    fun pull() = sync.block

    fun runCriterion(kind: String, argument: String, label: String) {
        val narrow = narrowArmed
        scope.launch {
            gate.withLock {
                model.applyCriterion(kind, argument, narrow, label)
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

    fun resolveThreadAsk(continueThread: Boolean) {
        val pending = threadAsk ?: return
        val fromConnect = threadAskFromConnect
        threadAsk = null
        threadAskFromConnect = false
        if (continueThread) threadConfirmed = true
        if (fromConnect) {
            threadChoice.trySend(continueThread)
            return
        }
        if (!continueThread) return
        query = ""
        narrowArmed = false
        prompt = null
        scope.launch {
            gate.withLock {
                model.applyView(pending)
                pull()
            }
            if (model.rows.isNotEmpty()) listState.scrollToItem(0)
        }
    }

    LaunchedEffect(snackEvent) {
        if (snackEvent == 0) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = snackMessage,
            actionLabel = "Retry",
        )
        if (result == SnackbarResult.ActionPerformed) {
            lastReported = null
            gate.withLock {
                model.loadWindow()
                pull()
            }
            if (model.rows.isNotEmpty()) listState.scrollToItem(0)
        }
    }

    LaunchedEffect(session, mailbox, loadToken) {
        loading = true
        banner = null
        var pendingThread: FolderView? = null
        var pendingExists = 0
        val watchNow = gate.withLock {
            val settings = try {
                store.load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                banner = error.message ?: "not connected"
                return@withLock false
            }
            try {
                store.password()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                banner = error.message ?: "not connected"
                return@withLock false
            }
            val opened = try {
                session.open(settings)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                banner = error.text
                return@withLock false
            }
            when (opened) {
                is OpenResult.Rejected -> {
                    banner = opened.capabilities
                    return@withLock false
                }
                is OpenResult.Failed -> {
                    banner = opened.text
                    return@withLock false
                }
                OpenResult.Connected -> Unit
            }
            val savedView = settings.folderViews[mailbox] ?: settings.defaultView
            val threading = savedView.key == SortKey.ThreadReferences ||
                savedView.key == SortKey.ThreadOrderedSubject
            if (threading) {
                val selected = try {
                    session.select(mailbox)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    banner = error.text
                    return@withLock false
                }
                if (selected.exists > ThreadConfirmExists && !threadConfirmed) {
                    pendingThread = savedView
                    pendingExists = selected.exists
                    return@withLock false
                }
            }
            model.loadWindow()
            connected = true
            pull()
            true
        }
        val chosen = pendingThread
        if (chosen != null) {
            threadExists = pendingExists
            threadAskFromConnect = true
            threadAsk = chosen
            val continueThread = threadChoice.receive()
            gate.withLock {
                if (continueThread) {
                    model.applyView(chosen)
                } else {
                    model.applyView(FolderView(SortKey.Arrival, newestFirst = true))
                }
                connected = true
                pull()
            }
            loading = false
            if (model.rows.isNotEmpty()) listState.scrollToItem(0)
        } else if (!watchNow) {
            loading = false
            return@LaunchedEffect
        } else {
            loading = false
        }
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
            postSnack(error.text)
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

    val indexEntries = buildIndexEntries(rows, summaries)
    val indexEntryState = rememberUpdatedState(indexEntries)
    LaunchedEffect(listState, connected) {
        if (!connected) return@LaunchedEffect
        snapshotFlow {
            rootRowIndex(indexEntryState.value, listState.firstVisibleItemIndex)
        }.collect { index ->
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

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        if (loading && banner == null) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
            )
        }
        val shownBanner = banner
        if (shownBanner != null) {
            FailureBanner(message = shownBanner) {
                banner = null
                loading = true
                loadToken += 1
            }
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
                                                runCriterion(choice.kind, "", choice.label)
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
                                    scope.launch {
                                        val threading = key == SortKey.ThreadReferences ||
                                            key == SortKey.ThreadOrderedSubject
                                        if (threading) {
                                            val exists = try {
                                                gate.withLock { session.select(mailbox).exists }
                                            } catch (error: CancellationException) {
                                                throw error
                                            } catch (error: MailFailure) {
                                                postSnack(error.text)
                                                return@launch
                                            }
                                            if (exists > ThreadConfirmExists && !threadConfirmed) {
                                                threadExists = exists
                                                threadAskFromConnect = false
                                                threadAsk = FolderView(key, view.newestFirst)
                                                return@launch
                                            }
                                        }
                                        query = ""
                                        narrowArmed = false
                                        prompt = null
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
                var confirmExpunge by remember { mutableStateOf(false) }
                TextButton(
                    onClick = {
                        val uidPlus = session.capabilities.any { it.equals("UIDPLUS", ignoreCase = true) }
                        if (account.askBeforeExpunge || !uidPlus) {
                            confirmExpunge = true
                        } else {
                            scope.launch {
                                gate.withLock {
                                    model.expunge()
                                    pull()
                                }
                                if (model.rows.isNotEmpty()) listState.scrollToItem(0)
                            }
                        }
                    },
                ) { Text("Expunge") }
                if (confirmExpunge) {
                    val uidPlus = session.capabilities.any { it.equals("UIDPLUS", ignoreCase = true) }
                    val body = buildString {
                        append("Permanently removes messages marked deleted in this folder. This cannot be undone.")
                        if (!uidPlus) {
                            append(" This includes messages marked deleted by other clients.")
                        }
                    }
                    AlertDialog(
                        onDismissRequest = { confirmExpunge = false },
                        title = { Text("Expunge?") },
                        text = { Text(body) },
                        confirmButton = {
                            TextButton(onClick = {
                                confirmExpunge = false
                                scope.launch {
                                    gate.withLock {
                                        model.expunge()
                                        pull()
                                    }
                                    if (model.rows.isNotEmpty()) listState.scrollToItem(0)
                                }
                            }) { Text("Expunge", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmExpunge = false }) { Text("Cancel") }
                        },
                    )
                }
            }
            if (filters.isNotEmpty()) {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    filters.forEachIndexed { index, filter ->
                        val chipText = if (filter.argument.isEmpty()) {
                            filter.label
                        } else {
                            "${filter.label} ${filter.argument}"
                        }
                        FilterChip(
                            selected = true,
                            onClick = {
                                scope.launch {
                                    gate.withLock {
                                        model.dropFiltersFrom(index)
                                        pull()
                                    }
                                    if (model.notice == null) narrowArmed = false
                                    if (model.rows.isNotEmpty()) listState.scrollToItem(0)
                                }
                            },
                            label = { Text(chipText) },
                        )
                    }
                }
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
            val visibleInfo = listState.layoutInfo.visibleItemsInfo
            val sequences = if (visibleInfo.isNotEmpty()) {
                visibleInfo.map { info ->
                    when (val entry = indexEntries.getOrNull(info.index)) {
                        is IndexEntry.Message -> entry.row.sequence
                        else -> 0
                    }
                }
            } else {
                rows.map { it.sequence }
            }
            val sequenceChars = sequenceColumnChars(sequences)
            val sequenceStyle = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum")
            val sequenceWidth = with(LocalDensity.current) {
                sequenceMeasurer.measure(
                    text = "0".repeat(sequenceChars),
                    style = sequenceStyle,
                ).size.width.toDp()
            }
            if (!loading && banner == null && rows.isEmpty() && threadAsk == null) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Inbox,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = emptyIndexText(query, mailbox),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .nestedScroll(newerConnection),
            ) {
                items(indexEntries, key = { it.key }) { entry ->
                    when (entry) {
                        is IndexEntry.Message -> {
                            val row = entry.row
                            IndexMessageRow(
                                row = row,
                                account = account,
                                selected = row.uid in selected,
                                multiSelect = multiSelect,
                                leftToRight = leftToRight,
                                sequenceWidth = sequenceWidth,
                                sequenceStyle = sequenceStyle,
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
                        is IndexEntry.Summary -> IndexSummaryRow(entry.text, sequenceWidth)
                    }
                }
            }
            }
        }
    }
        ExtendedFloatingActionButton(
            text = { Text("Compose") },
            icon = { Icon(imageVector = Icons.Filled.Edit, contentDescription = "Compose") },
            onClick = { onCompose(ComposeSeed(ComposeKind.New, null, emptyList())) },
            expanded = !listState.isScrollInProgress,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
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
                    val label = pendingPrompt.label
                    val value = promptText
                    prompt = null
                    runCriterion(kind, value, label)
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { prompt = null }) { Text("Dismiss") }
            },
        )
    }
    val asking = threadAsk
    if (asking != null) {
        AlertDialog(
            onDismissRequest = { resolveThreadAsk(false) },
            title = { Text("Thread this folder?") },
            text = { Text("This folder has $threadExists messages. Threading reads the whole folder.") },
            confirmButton = {
                TextButton(onClick = { resolveThreadAsk(true) }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { resolveThreadAsk(false) }) { Text("Cancel") }
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
    sequenceWidth: Dp,
    sequenceStyle: TextStyle,
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
            val statusDescription = indexStatusDescription(row.flags, row.toMe, row.hasAttachment)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .combinedClickable(onLongClick = onLongPress, onClick = onClick)
                    .then(
                        if (statusDescription.isNotEmpty()) {
                            Modifier.semantics { stateDescription = statusDescription }
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp),
            ) {
                val appearance = indexAppearance(row.flags)
                val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = appearance.alpha)
                val decoration = if (appearance.strikethrough) TextDecoration.LineThrough else TextDecoration.None
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(sequenceWidth)) {
                        if (row.sequence != 0) {
                            Text(
                                text = row.sequence.toString(),
                                modifier = Modifier.fillMaxWidth(),
                                style = sequenceStyle,
                                color = textColor,
                                textDecoration = decoration,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                textAlign = TextAlign.End,
                            )
                        }
                    }
                    IndexStatusColumn(row, Modifier.padding(end = 4.dp))
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

@Composable
private fun FailureBanner(message: String, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.CloudOff,
                contentDescription = null,
            )
            Text(
                text = message,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            TextButton(onClick = onRetry) { Text("Retry") }
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

@Composable
private fun IndexSummaryRow(text: String, sequenceWidth: Dp) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(sequenceWidth))
        Text(
            text = text,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private sealed class IndexEntry {
    abstract val key: String

    data class Message(val row: IndexRow) : IndexEntry() {
        override val key: String = "m${row.uid}"
    }

    data class Summary(val rootUid: Long, val text: String) : IndexEntry() {
        override val key: String = "s$rootUid"
    }
}

private fun buildIndexEntries(
    rows: List<IndexRow>,
    summaries: Map<Long, ThreadSummary>,
): List<IndexEntry> {
    val entries = ArrayList<IndexEntry>(rows.size)
    for (row in rows) {
        entries.add(IndexEntry.Message(row))
        val summary = summaries[row.uid]
        if (summary != null) entries.add(IndexEntry.Summary(row.uid, threadSummaryLine(summary)))
    }
    return entries
}

private fun rootRowIndex(entries: List<IndexEntry>, lazyIndex: Int): Int {
    if (entries.isEmpty() || lazyIndex <= 0) return 0
    var roots = 0
    val last = minOf(lazyIndex, entries.lastIndex)
    for (index in 0..last) {
        val entry = entries[index]
        if (entry is IndexEntry.Message) {
            if (index == lazyIndex) return roots
            roots += 1
        } else if (index == lazyIndex) {
            return (roots - 1).coerceAtLeast(0)
        }
    }
    return roots
}
