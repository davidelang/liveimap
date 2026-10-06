package org.dlang.liveimap.ui.index

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.outlined.Flag as OutlinedFlag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.lifecycle.compose.LifecycleStartEffect
import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.ui.compose.armForwardOnce
import org.dlang.liveimap.ui.compose.oppositeForwardLabel
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.Namespace
import org.dlang.liveimap.session.NamespaceKind
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.ui.ConnectionStatusStrip
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.DateFormat
import org.dlang.liveimap.settings.FolderView
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.StartRule
import org.dlang.liveimap.settings.SwipeAction
import org.dlang.liveimap.settings.openAtMenuText
import org.dlang.liveimap.settings.recentRuleNote
import org.dlang.liveimap.settings.startRuleChoices
import org.dlang.liveimap.settings.startRuleFor
import org.dlang.liveimap.settings.startRuleIsRecent
import org.dlang.liveimap.settings.startRuleLabel
import org.dlang.liveimap.settings.SwipeBinding
import org.dlang.liveimap.settings.swipeActionLabel
import org.dlang.liveimap.ui.mailBarInsets
import org.dlang.liveimap.ui.mailScreenInsets
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
private fun IndexStatusColumn(
    row: IndexRow,
    description: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val forwarded = "\$Forwarded" in row.flags
    val answered = "\\Answered" in row.flags
    val flagged = "\\Flagged" in row.flags
    val flaggedColor = MaterialTheme.colorScheme.error
    val toMeColor = MaterialTheme.colorScheme.primary
    val columnModifier = if (description.isEmpty()) {
        modifier
    } else {
        modifier.semantics { contentDescription = description }
    }
    Row(columnModifier, verticalAlignment = Alignment.Top) {
        if (selected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
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
                    Box(Modifier.size(10.dp).background(toMeColor, CircleShape))
                    Box(Modifier.size(6.dp).background(flaggedColor, CircleShape))
                }
                flagged -> Box(Modifier.size(10.dp).background(flaggedColor, CircleShape))
                row.toMe -> Box(Modifier.size(10.dp).background(toMeColor, CircleShape))
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageIndexScreen(
    mailbox: String,
    onOpen: (Long, Int) -> Unit,
    onCompose: (ComposeSeed) -> Unit,
    onBack: () -> Unit,
    watchMailbox: Boolean = true,
) {
    val appContext = LocalContext.current.applicationContext
    val store = remember { DataStoreSettingsStore(appContext) }
    val session = remember { mailSession() }
    val connectionState by session.connectionState.collectAsState()
    val model = remember(session, store, mailbox) { IndexModel(session, store, mailbox) }
    val scope = rememberCoroutineScope()
    val watchRecovery = remember { WatchBackoff() }
    val gate = remember { Mutex() }
    val sync = remember { SnapshotSync() }
    val listState = rememberLazyListState()
    var rows by remember { mutableStateOf<List<IndexRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var banner by remember { mutableStateOf<String?>(null) }
    var fetchNotice by remember { mutableStateOf<String?>(null) }
    var loadToken by remember { mutableIntStateOf(0) }
    var snackEvent by remember { mutableIntStateOf(0) }
    var snackMessage by remember { mutableStateOf("") }
    var pendingNew by remember { mutableIntStateOf(0) }
    var newMailUnnumbered by remember { mutableStateOf(false) }
    val userMovedSincePill = remember { mutableStateOf(false) }
    var lastReported by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    var view by remember { mutableStateOf(FolderView(SortKey.Arrival, true)) }
    var account by remember { mutableStateOf(AccountSettings()) }
    var menuKeys by remember { mutableStateOf(model.menuKeys) }
    var anchorPage by remember { mutableStateOf(0) }
    var connected by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var openAt by remember { mutableStateOf(false) }
    var heading by remember(mailbox) { mutableStateOf(MailboxTitle(mailbox, "")) }
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
    var threadMembers by remember { mutableStateOf<Map<Long, IndexRow>>(emptyMap()) }
    var threadHidden by remember { mutableStateOf<Map<Long, List<Long>>>(emptyMap()) }
    var threadDepth by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }
    var refreshing by remember { mutableStateOf(false) }
    var threadAsk by remember { mutableStateOf<FolderView?>(null) }
    var threadAskFromConnect by remember { mutableStateOf(false) }
    var threadExists by remember { mutableIntStateOf(0) }
    var threadConfirmed by rememberSaveable(mailbox) { mutableStateOf(false) }
    var expandedText by rememberSaveable(mailbox) { mutableStateOf("") }
    val expandedThreads = parseExpandedThreads(expandedText)
    model.noteExpanded(expandedThreads)
    val threadChoice = remember(mailbox) { Channel<Boolean>(Channel.CONFLATED) }
    val sequenceMeasurer = rememberTextMeasurer()
    var multiSelect by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<List<Long>>(emptyList()) }
    var allMailbox by remember { mutableStateOf(false) }
    var mailboxExists by remember { mutableIntStateOf(0) }
    var folderExists by remember(mailbox) { mutableIntStateOf(0) }
    var selectionMore by remember { mutableStateOf(false) }
    var confirmExpunge by remember { mutableStateOf(false) }
    var pendingExpungeUids by remember { mutableStateOf<List<Long>?>(null) }
    var undoOffer by remember { mutableStateOf<MailUndo?>(null) }
    var undoToken by remember { mutableIntStateOf(0) }
    var snackMode by remember { mutableStateOf("retry") }
    var flagUid by remember { mutableStateOf<Long?>(null) }
    val leftToRight = LocalLayoutDirection.current == LayoutDirection.Ltr
    val anchorState = rememberUpdatedState(anchorPage)
    val watchMailboxNow = rememberUpdatedState(watchMailbox)
    val allowNewer = remember { mutableStateOf(true) }

    BackHandler(
        enabled = prompt != null || filterOpen || menuOpen || openAt || flagUid != null || searchVisible || multiSelect,
    ) {
        when {
            prompt != null -> prompt = null
            filterOpen -> filterOpen = false
            openAt -> openAt = false
            menuOpen -> menuOpen = false
            flagUid != null -> flagUid = null
            searchVisible -> searchVisible = false
            multiSelect -> {
                multiSelect = false
                selected = emptyList()
                allMailbox = false
                selectionMore = false
            }
        }
    }

    fun postSnack(text: String) {
        lastReported = text
        snackMessage = text
        snackMode = "retry"
        snackEvent += 1
    }

    fun clearSelection() {
        multiSelect = false
        selected = emptyList()
        allMailbox = false
        selectionMore = false
    }

    fun publishUndo(undo: MailUndo) {
        undoOffer = undo
        snackMode = "undo"
        undoToken += 1
    }

    fun applySelectionFlags(add: Set<String>, remove: Set<String>) {
        val uids = selected.toList()
        val entire = allMailbox
        selectionMore = false
        scope.launch {
            gate.withLock {
                model.changeFlags(uids, add, remove, entire)
                sync.block()
            }
        }
    }

    var noteVisibleTop: () -> Unit = {}

    suspend fun scrollToIndex(rowIndex: Int) {
        if (model.rows.isEmpty()) return
        val target = rowIndex.coerceIn(0, model.rows.lastIndex)
        var lazyIndex = 0
        var seen = 0
        for (row in model.rows) {
            if (seen == target) break
            lazyIndex += 1
            if (model.summaries.containsKey(row.uid)) lazyIndex += 1
            if (row.uid in expandedThreads) {
                for (uid in model.threadHidden[row.uid].orEmpty()) {
                    if (model.threadMembers.containsKey(uid)) lazyIndex += 1
                }
            }
            seen += 1
        }
        listState.scrollToItem(lazyIndex)
    }

    suspend fun scrollToStart() {
        scrollToIndex(model.startIndex)
    }

    suspend fun scrollToNewestEnd() {
        scrollToIndex(model.newestHeldIndex)
    }

    fun userAtNewestEnd(): Boolean {
        if (model.view.newestFirst) return listState.firstVisibleItemIndex == 0
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) return model.rows.isEmpty()
        val total = info.totalItemsCount
        if (total <= 0) return true
        return visible.last().index >= total - 1
    }

    fun runUndo(offer: MailUndo?) {
        val current = offer ?: return
        undoOffer = null
        scope.launch {
            gate.withLock {
                if (current.delete) model.undoDelete(current.uids, current.allMailbox)
                else model.undoMove(
                    current.uids,
                    current.targetMailbox,
                    current.destUids,
                    current.usedMove,
                    current.allMailbox,
                )
                sync.block()
            }
            snackbarHostState.currentSnackbarData?.dismiss()
        }
    }

    fun finishUndoExpunge(ok: Boolean) {
        if (!ok) return
        undoOffer = null
        snackbarHostState.currentSnackbarData?.dismiss()
    }

    fun runExpunge(offer: MailUndo) {
        if (offer.uids.isEmpty()) return
        if (account.askBeforeExpunge) {
            pendingExpungeUids = offer.uids
            confirmExpunge = true
            return
        }
        scope.launch {
            var ok = false
            gate.withLock {
                model.expungeUids(offer.uids)
                sync.block()
                ok = model.notice == null
            }
            finishUndoExpunge(ok)
        }
    }

    sync.block = {
        rows = model.rows
        fetchNotice = model.notice
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
        threadMembers = model.threadMembers
        threadHidden = model.threadHidden
        threadDepth = model.threadDepth
        pendingNew = model.pendingNew
        newMailUnnumbered = model.newMailUnnumbered
    }

    fun pull() { sync.block() }

    fun onNewMailPill() {
        scope.launch {
            gate.withLock {
                val plainArrival = model.view.key == SortKey.Arrival &&
                    !model.filterActive &&
                    !model.searchActive
                model.acknowledgeNewMail()
                if (plainArrival) model.jumpToNewest()
                pull()
            }
            if (model.rows.isNotEmpty()) scrollToNewestEnd()
        }
    }

    fun toggleThread(rootUid: Long) {
        scope.launch {
            gate.withLock {
                val current = parseExpandedThreads(expandedText)
                val opening = rootUid !in current
                if (opening && !model.cacheThreadMembers(rootUid)) {
                    pull()
                    return@withLock
                }
                val next = LinkedHashSet(current)
                if (opening) next.add(rootUid) else next.remove(rootUid)
                model.noteExpanded(next)
                model.publishMessageOrder()
                expandedText = formatExpandedThreads(next)
                pull()
            }
        }
    }

    fun runCriterion(kind: String, argument: String, label: String) {
        val narrow = narrowArmed
        scope.launch {
            gate.withLock {
                noteVisibleTop()
                model.applyCriterion(kind, argument, narrow, label)
                pull()
            }
            if (model.notice == null) {
                narrowArmed = false
                query = ""
                searchVisible = false
            }
            if (model.rows.isNotEmpty()) scrollToStart()
        }
    }

    fun runShowAll() {
        scope.launch {
            gate.withLock {
                noteVisibleTop()
                model.showAll()
                pull()
            }
            if (model.notice == null) {
                narrowArmed = false
                query = ""
                searchVisible = false
            }
            if (model.rows.isNotEmpty()) scrollToStart()
        }
    }

    fun runWiden() {
        scope.launch {
            gate.withLock {
                noteVisibleTop()
                model.widenFilter()
                pull()
            }
            if (model.notice == null) narrowArmed = false
            if (model.rows.isNotEmpty()) scrollToStart()
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
                noteVisibleTop()
                model.applyView(pending)
                pull()
            }
            if (model.rows.isNotEmpty()) scrollToStart()
        }
    }

    LaunchedEffect(snackEvent) {
        if (snackEvent == 0) return@LaunchedEffect
        snackMode = "retry"
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
            if (model.rows.isNotEmpty()) scrollToStart()
        }
    }

    LaunchedEffect(undoToken) {
        val token = undoToken
        if (token == 0) return@LaunchedEffect
        val offer = undoOffer ?: return@LaunchedEffect
        snackMode = "undo"
        snackbarHostState.showSnackbar(
            message = mailUndoText(offer),
            duration = SnackbarDuration.Indefinite,
        )
        if (undoToken == token) {
            undoOffer = null
            snackMode = "retry"
        }
    }

    LaunchedEffect(session, mailbox, loadToken) {
        heading = MailboxTitle(mailbox, "")
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
            heading = mailboxTitleFor(session, mailbox)
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
                pendingExists = selected.exists
                if (selected.exists > ThreadConfirmExists && !threadConfirmed) {
                    pendingThread = savedView
                    return@withLock false
                }
            }
            model.loadWindow()
            connected = true
            pull()
            true
        }
        if (pendingExists > 0) folderExists = pendingExists
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
            if (model.rows.isNotEmpty()) scrollToStart()
        } else if (!watchNow) {
            loading = false
            return@LaunchedEffect
        } else {
            loading = false
            if (model.rows.isNotEmpty()) scrollToStart()
        }
        var watchJob: Job? = null
        try {
            snapshotFlow { watchMailboxNow.value }.collect { watching ->
                watchJob?.cancel()
                watchJob?.join()
                watchJob = null
                if (!watching) {
                    withContext(NonCancellable) {
                        try {
                            model.stopWatch()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: MailFailure) {
                        }
                    }
                    return@collect
                }
                watchJob = launch {
                    try {
                        model.watch { change ->
                            when (change) {
                                MailboxChange.Reconnected -> scope.launch { watchRecovery.onRefresh() }
                                MailboxChange.WatchLost -> watchRecovery.onWatchLost(scope, session)
                                else -> scope.launch {
                                    when (change) {
                                        is MailboxChange.Exists -> folderExists = change.exists
                                        is MailboxChange.Expunge -> folderExists = change.exists
                                        else -> Unit
                                    }
                                    val anchorOffset = listState.firstVisibleItemScrollOffset
                                    val anchorIndex = listState.firstVisibleItemIndex
                                    val anchorUid = model.rows.getOrNull(anchorIndex)?.uid
                                    gate.withLock {
                                        model.applyChange(change)
                                        pull()
                                    }
                                    if (change is MailboxChange.Exists && anchorUid != null && model.view.newestFirst) {
                                        val after = model.rows.indexOfFirst { it.uid == anchorUid }
                                        if (after > anchorIndex) {
                                            listState.requestScrollToItem(after, anchorOffset)
                                        }
                                    }
                                    if (change is MailboxChange.Exists &&
                                        (model.pendingNew > 0 || model.newMailUnnumbered)
                                    ) {
                                        userMovedSincePill.value = false
                                    }
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
            }
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

    val indexEntries = buildIndexEntries(
        rows,
        summaries,
        expandedThreads,
        threadMembers,
        threadHidden,
        threadDepth,
    )
    val indexEntryState = rememberUpdatedState(indexEntries)
    noteVisibleTop = {
        if (model.rows.isEmpty()) {
            model.noteTopUid(null)
        } else {
            val root = rootRowIndex(indexEntryState.value, listState.firstVisibleItemIndex)
            model.noteTopUid(model.rows.getOrNull(root)?.uid)
        }
    }

    fun visibleMessageUids(): List<Long> {
        val entries = indexEntryState.value
        val info = listState.layoutInfo.visibleItemsInfo
        if (info.isEmpty()) return rows.map { it.uid }
        val uids = ArrayList<Long>()
        for (item in info) {
            val entry = entries.getOrNull(item.index) ?: continue
            if (entry is IndexEntry.Message) uids.add(entry.row.uid)
        }
        return if (uids.isEmpty()) rows.map { it.uid } else uids
    }

    fun refreshIndex() {
        if (loading || refreshing) return
        val shown = visibleMessageUids()
        val exists = folderExists
        refreshing = true
        scope.launch {
            try {
                gate.withLock {
                    model.refreshShown(shown, exists)
                    val failed = model.notice
                    if (failed != null) {
                        lastReported = failed
                        banner = failed
                    } else if (connected) {
                        banner = null
                    }
                    pull()
                }
            } finally {
                refreshing = false
            }
        }
    }
    watchRecovery.onRefresh = { refreshIndex() }

    fun retryConnection() {
        watchRecovery.cancel()
        scope.launch {
            try {
                session.resume()
                watchRecovery.attempt = 0
            } catch (error: CancellationException) {
                throw error
            } catch (_: MailFailure) {
            }
            if (connected) refreshIndex() else loadToken += 1
        }
    }

    LifecycleStartEffect(Unit) {
        if (watchRecovery.skipFirstStart) {
            watchRecovery.skipFirstStart = false
        } else {
            watchRecovery.cancel()
            scope.launch {
                try {
                    session.resume()
                    watchRecovery.attempt = 0
                } catch (error: CancellationException) {
                    throw error
                } catch (_: MailFailure) {
                }
                refreshIndex()
            }
        }
        onStopOrDispose { }
    }
    LaunchedEffect(listState, connected) {
        if (!connected) return@LaunchedEffect
        snapshotFlow {
            rootRowIndex(indexEntryState.value, listState.firstVisibleItemIndex)
        }.collect { index ->
            val before = model.anchorPage
            val holdNewest = !model.view.newestFirst && model.showsNewestEnd && userAtNewestEnd()
            gate.withLock {
                if (!holdNewest) {
                    model.onFirstVisible(index)
                    pull()
                }
            }
            if (model.anchorPage != before && model.rows.isNotEmpty()) {
                listState.scrollToItem(0)
            }
        }
    }

    LaunchedEffect(listState, connected) {
        if (!connected) return@LaunchedEffect
        snapshotFlow { userAtNewestEnd() }.collect { atEnd ->
            if (!atEnd || !userMovedSincePill.value) return@collect
            if (model.pendingNew <= 0 && !model.newMailUnnumbered) return@collect
            if (!model.showsNewestEnd) return@collect
            userMovedSincePill.value = false
            gate.withLock {
                model.acknowledgeNewMail()
                pull()
            }
        }
    }

    val newerConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.SideEffect) userMovedSincePill.value = true
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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            val selectionOrder = if (multiSelect) model.order else emptyList()
            val selectionCount = if (multiSelect) selected.size else 0
            val filterComplete = multiSelect && !allMailbox && filterActive && selectionOrder.isNotEmpty() &&
                selectionCount == selectionOrder.size && selected.toSet() == selectionOrder.toSet()
            val titleExists = when {
                !multiSelect -> 0
                allMailbox -> mailboxExists
                filterComplete -> selectionOrder.size
                else -> 0
            }
            val selectionLabel = if (multiSelect) {
                selectionTitle(allMailbox, if (allMailbox) 0 else selectionCount, titleExists)
            } else {
                ""
            }
            val loadedSelected = when {
                !multiSelect -> emptyList()
                allMailbox -> rows
                else -> rows.filter { it.uid in selected }
            }
            val markUnread = multiSelect && !allMailbox && loadedSelected.isNotEmpty() &&
                loadedSelected.all { "\\Seen" in it.flags }
            val clearFlag = multiSelect && !allMailbox && loadedSelected.isNotEmpty() &&
                loadedSelected.all { "\\Flagged" in it.flags }
            val showUndelete = multiSelect && (
                allMailbox || rows.any { it.uid in selected && "\\Deleted" in it.flags }
            )
            TopAppBar(
                title = {
                    if (multiSelect) {
                        Text(
                            text = selectionLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        MailboxTitleLines(heading.leaf, heading.parent)
                    }
                },
                navigationIcon = {
                    if (multiSelect) {
                        IconButton(onClick = { clearSelection() }) {
                            Icon(imageVector = Icons.Filled.Close, contentDescription = "Close")
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    }
                },
                actions = {
                    if (multiSelect) {
                        IconButton(
                            onClick = {
                                if (allMailbox || !markUnread) applySelectionFlags(setOf("\\Seen"), emptySet())
                                else applySelectionFlags(emptySet(), setOf("\\Seen"))
                            },
                        ) {
                            Icon(
                                imageVector = if (markUnread) Icons.Filled.Email else Icons.Filled.Drafts,
                                contentDescription = if (markUnread) "Mark unread" else "Mark read",
                            )
                        }
                        IconButton(
                            onClick = {
                                if (allMailbox || !clearFlag) applySelectionFlags(setOf("\\Flagged"), emptySet())
                                else applySelectionFlags(emptySet(), setOf("\\Flagged"))
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Flag,
                                contentDescription = if (clearFlag) "Unflag" else "Flag",
                            )
                        }
                        IconButton(
                            onClick = {
                                val uids = selected.toList()
                                val entire = allMailbox
                                scope.launch {
                                    var undo: MailUndo? = null
                                    gate.withLock {
                                        model.clearMailUndo()
                                        model.moveMessages(uids, barMoveMailbox(account), entire)
                                        pull()
                                        undo = model.mailUndo
                                    }
                                    val pending = undo
                                    if (pending != null) publishUndo(pending)
                                }
                            },
                        ) {
                            Icon(imageVector = Icons.Filled.DriveFileMove, contentDescription = "Move")
                        }
                        IconButton(
                            onClick = {
                                val uids = selected.toList()
                                val entire = allMailbox
                                scope.launch {
                                    var undo: MailUndo? = null
                                    gate.withLock {
                                        model.clearMailUndo()
                                        model.deleteMessages(uids, entire)
                                        pull()
                                        undo = model.mailUndo
                                    }
                                    val pending = undo
                                    if (pending != null) publishUndo(pending)
                                }
                            },
                        ) {
                            Icon(imageVector = Icons.Filled.Delete, contentDescription = "Delete")
                        }
                        Box {
                            IconButton(onClick = { selectionMore = true }) {
                                Icon(imageVector = Icons.Filled.MoreVert, contentDescription = "More")
                            }
                            DropdownMenu(
                                expanded = selectionMore,
                                onDismissRequest = { selectionMore = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Select all") },
                                    onClick = {
                                        selectionMore = false
                                        when (val target = selectAllTarget(filterActive, model.order)) {
                                            is SelectAllTarget.Uids -> {
                                                allMailbox = false
                                                selected = target.uids
                                                multiSelect = true
                                            }
                                            SelectAllTarget.EntireMailbox -> {
                                                scope.launch {
                                                    val exists = gate.withLock { session.selectedExists() }
                                                    allMailbox = true
                                                    mailboxExists = exists
                                                    folderExists = exists
                                                    selected = emptyList()
                                                    multiSelect = true
                                                }
                                            }
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Mark answered") },
                                    onClick = { applySelectionFlags(setOf("\\Answered"), emptySet()) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Mark unanswered") },
                                    onClick = { applySelectionFlags(emptySet(), setOf("\\Answered")) },
                                )
                                if (showUndelete) {
                                    DropdownMenuItem(
                                        text = { Text("Undelete") },
                                        onClick = { applySelectionFlags(emptySet(), setOf("\\Deleted")) },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Bounce") },
                                    enabled = !allMailbox,
                                    onClick = {
                                        selectionMore = false
                                        onCompose(model.bounceSeed(selected))
                                    },
                                )
                                if (!allMailbox && selected.size == 1) {
                                    val oneUid = selected.single()
                                    DropdownMenuItem(
                                        text = { Text(oppositeForwardLabel(account.forwardAsAttachment)) },
                                        onClick = {
                                            selectionMore = false
                                            armForwardOnce(!account.forwardAsAttachment)
                                            onCompose(
                                                ComposeSeed(ComposeKind.Forward, mailbox, listOf(oneUid)),
                                            )
                                        },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Clear selection") },
                                    onClick = { clearSelection() },
                                )
                            }
                        }
                    } else {
                    IconButton(onClick = { refreshIndex() }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh",
                        )
                    }
                    if (connected) {
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
                                imageVector = Icons.Filled.Sort,
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
                        val fieldKeys = menuKeys.filter { key ->
                            key != SortKey.ThreadReferences && key != SortKey.ThreadOrderedSubject
                        }
                        val threadKeys = menuKeys.filter { key ->
                            key == SortKey.ThreadReferences || key == SortKey.ThreadOrderedSubject
                        }
                        val chooseSort: (SortKey) -> Unit = { key ->
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
                                    folderExists = exists
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
                                    noteVisibleTop()
                                    model.applyView(FolderView(key, view.newestFirst))
                                    pull()
                                }
                                if (model.rows.isNotEmpty()) scrollToStart()
                            }
                        }
                        fieldKeys.forEach { key ->
                            SortMenuChoice(
                                key = key,
                                selected = key == view.key,
                                enabled = sortKeyAdvertised(session.capabilities, key),
                                onClick = { chooseSort(key) },
                            )
                        }
                        HorizontalDivider()
                        threadKeys.forEach { key ->
                            SortMenuChoice(
                                key = key,
                                selected = key == view.key,
                                enabled = sortKeyAdvertised(session.capabilities, key),
                                onClick = { chooseSort(key) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Newest first") },
                            onClick = {
                                menuOpen = false
                                query = ""
                                narrowArmed = false
                                prompt = null
                                scope.launch {
                                    gate.withLock {
                                        noteVisibleTop()
                                        model.applyView(view.copy(newestFirst = !view.newestFirst))
                                        pull()
                                    }
                                    if (model.rows.isNotEmpty()) scrollToStart()
                                }
                            },
                            trailingIcon = {
                                Checkbox(
                                    checked = view.newestFirst,
                                    onCheckedChange = null,
                                )
                            },
                        )
                        val openAtLabel = openAtMenuText(account)
                        if (openAtLabel != null) {
                            DropdownMenuItem(
                                text = { Text(openAtLabel) },
                                onClick = {
                                    menuOpen = false
                                    openAt = true
                                },
                            )
                        }
                    }
                }
                    }
                    }
                },
                windowInsets = mailBarInsets(),
            )
        },
        contentWindowInsets = mailScreenInsets(),
    ) { padding ->
    Box(Modifier.fillMaxSize().padding(padding)) {
    Column(Modifier.fillMaxSize()) {
        ConnectionStatusStrip(connectionState, onRetry = { retryConnection() })
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
                TextButton(
                    onClick = {
                        val uidPlus = session.capabilities.any { it.equals("UIDPLUS", ignoreCase = true) }
                        if (account.askBeforeExpunge || !uidPlus) {
                            pendingExpungeUids = null
                            confirmExpunge = true
                        } else {
                            scope.launch {
                                gate.withLock {
                                    model.expunge()
                                    pull()
                                }
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
                        onDismissRequest = {
                            confirmExpunge = false
                            pendingExpungeUids = null
                        },
                        title = { Text("Expunge?") },
                        text = { Text(body) },
                        confirmButton = {
                            TextButton(onClick = {
                                val specific = pendingExpungeUids
                                pendingExpungeUids = null
                                confirmExpunge = false
                                scope.launch {
                                    var ok = false
                                    gate.withLock {
                                        if (specific != null) model.expungeUids(specific) else model.expunge()
                                        pull()
                                        ok = model.notice == null
                                    }
                                    if (specific != null) finishUndoExpunge(ok)
                                }
                            }) { Text("Expunge", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                confirmExpunge = false
                                pendingExpungeUids = null
                            }) { Text("Cancel") }
                        },
                    )
                }
            if (filterActive && filters.isNotEmpty()) {
                val shown = indexEntries.count { it is IndexEntry.Message }
                val total = if (folderExists > 0) folderExists else shown
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
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
                                            noteVisibleTop()
                                            model.dropFiltersFrom(index)
                                            pull()
                                        }
                                        if (model.notice == null) narrowArmed = false
                                        if (model.rows.isNotEmpty()) scrollToStart()
                                    }
                                },
                                label = { Text(chipText) },
                            )
                        }
                    }
                    Text(
                        text = "$shown of $total",
                        modifier = Modifier.padding(start = 8.dp),
                        maxLines = 1,
                    )
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
                                    noteVisibleTop()
                                    model.applySearch("")
                                    pull()
                                }
                                if (model.rows.isNotEmpty()) scrollToStart()
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
                                    noteVisibleTop()
                                    model.applySearch(query)
                                    pull()
                                }
                                if (model.rows.isNotEmpty()) scrollToStart()
                            }
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )
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
            val nowEpoch = Instant.now().epochSecond
            val dateZone = ZoneId.systemDefault()
            val dateStyle = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum")
            var dateWidthPx = 0
            for (entry in indexEntries) {
                if (entry !is IndexEntry.Message) continue
                val formatted = formatIndexDate(
                    epochSeconds = entry.row.internalDateEpoch,
                    format = account.dateFormat,
                    pattern = account.datePattern,
                    nowEpoch = nowEpoch,
                    zone = dateZone,
                )
                val measured = sequenceMeasurer.measure(text = formatted, style = dateStyle).size.width
                if (measured > dateWidthPx) dateWidthPx = measured
            }
            val dateWidth = with(LocalDensity.current) { dateWidthPx.toDp() }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
            PullToRefreshBox(
                isRefreshing = loading || refreshing,
                onRefresh = { refreshIndex() },
                modifier = Modifier.fillMaxSize(),
            ) {
            if (!loading && banner == null && rows.isEmpty() && threadAsk == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
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
                        text = fetchNotice ?: emptyIndexText(query, mailbox),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(newerConnection),
            ) {
                items(indexEntries, key = { it.key }) { entry ->
                    when (entry) {
                        is IndexEntry.Message -> {
                            val row = entry.row
                            IndexMessageRow(
                                row = row,
                                account = account,
                                depth = entry.depth,
                                selected = allMailbox || row.uid in selected,
                                multiSelect = multiSelect,
                                leftToRight = leftToRight,
                                sequenceWidth = sequenceWidth,
                                sequenceStyle = sequenceStyle,
                                dateWidth = dateWidth,
                                dateStyle = dateStyle,
                                nowEpoch = nowEpoch,
                                zone = dateZone,
                                onClick = {
                                    if (multiSelect) {
                                        if (allMailbox) {
                                            allMailbox = false
                                            selected = rows.map { it.uid }.filter { it != row.uid }
                                        } else {
                                            selected = if (row.uid in selected) selected - row.uid else selected + row.uid
                                        }
                                    } else {
                                        val seed = model.openSeed(row.uid)
                                        if (seed != null) onCompose(seed) else onOpen(row.uid, row.sequence)
                                    }
                                },
                                onLongPress = {
                                    multiSelect = true
                                    if (!allMailbox && row.uid !in selected) selected = selected + row.uid
                                },
                                onSwipe = { binding ->
                                    var undo: MailUndo? = null
                                    gate.withLock {
                                        model.clearMailUndo()
                                        when (val command = model.performSwipe(row.uid, binding)) {
                                            is IndexCommand.Compose -> onCompose(command.seed)
                                            is IndexCommand.ShowFlags -> flagUid = command.uid
                                            IndexCommand.None -> Unit
                                        }
                                        pull()
                                        undo = model.mailUndo
                                    }
                                    val pending = undo
                                    if (pending != null) publishUndo(pending)
                                },
                            )
                        }
                        is IndexEntry.Summary -> IndexSummaryRow(
                            text = entry.text,
                            sequenceWidth = sequenceWidth,
                            expanded = entry.rootUid in expandedThreads,
                            onClick = { toggleThread(entry.rootUid) },
                        )
                    }
                }
            }
            }
            }
            if (newMailUnnumbered || pendingNew > 0) {
                NewMailPill(
                    count = pendingNew,
                    unnumbered = newMailUnnumbered,
                    newestFirst = view.newestFirst,
                    onClick = { onNewMailPill() },
                )
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
        ) { data ->
            val offer = undoOffer
            val uidPlus = session.capabilities.any { it.equals("UIDPLUS", ignoreCase = true) }
            val showExpunge = snackMode == "undo" && offer != null && offer.delete &&
                !offer.allMailbox && offer.uids.isNotEmpty() && uidPlus
            if (snackMode == "undo" && offer != null) {
                Snackbar(
                    action = {
                        TextButton(onClick = { runUndo(offer) }) { Text("Undo") }
                        if (showExpunge) {
                            TextButton(onClick = { runExpunge(offer) }) { Text("Expunge") }
                        }
                    },
                ) { Text(data.visuals.message) }
            } else {
                Snackbar(data)
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
    if (openAt) {
        val selected = account.folderStarts[mailbox] ?: startRuleFor(mailbox, account)
        val choices = startRuleChoices(account.showRecentRules, selected)
        AlertDialog(
            onDismissRequest = { openAt = false },
            title = { Text("Open this folder at…") },
            text = {
                Column {
                    choices.forEach { rule ->
                        TextButton(onClick = {
                            openAt = false
                            scope.launch {
                                gate.withLock {
                                    model.setFolderStart(rule)
                                    pull()
                                }
                            }
                        }) {
                            Column {
                                Text(startRuleLabel(rule))
                                if (startRuleIsRecent(rule)) Text(recentRuleNote)
                            }
                        }
                    }
                    TextButton(onClick = {
                        openAt = false
                        scope.launch {
                            gate.withLock {
                                model.setFolderStart(null)
                                pull()
                            }
                        }
                    }) { Text("Default") }
                }
            },
            confirmButton = {
                TextButton(onClick = { openAt = false }) { Text("Close") }
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
    depth: Int = 0,
    selected: Boolean,
    multiSelect: Boolean,
    leftToRight: Boolean,
    sequenceWidth: Dp,
    sequenceStyle: TextStyle,
    dateWidth: Dp,
    dateStyle: TextStyle,
    nowEpoch: Long,
    zone: ZoneId,
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
    val haptic = LocalHapticFeedback.current
    var crossed by remember { mutableStateOf(false) }
    val dragOffset = runCatching { dismissState.requireOffset() }.getOrDefault(0f)
    val crossedNow = swipeReached(dragOffset, width.toFloat())
    SideEffect {
        if (crossedNow) {
            if (!crossed) {
                crossed = true
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        } else if (crossed) {
            crossed = false
        }
    }
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
    Box(
        Modifier
            .padding(start = (depth.coerceAtMost(6) * 16).dp)
            .onSizeChanged { width = it.width }
            .fillMaxWidth(),
    ) {
        SwipeToDismissBox(
            state = dismissState,
            enableDismissFromStartToEnd = !multiSelect,
            enableDismissFromEndToStart = !multiSelect,
            gesturesEnabled = !multiSelect,
            backgroundContent = {
                val offset = runCatching { dismissState.requireOffset() }.getOrDefault(0f)
                val binding = swipeBindingForOffset(
                    offsetPx = offset,
                    leftToRight = leftToRight,
                    trailing = account.swipeTrailing,
                    leading = account.swipeLeading,
                )
                if (binding != null) {
                    val visual = swipeVisual(binding.action)
                    val tint = if (binding.action == SwipeAction.Delete) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                    val atStart = if (leftToRight) offset > 0f else offset < 0f
                    val reached = swipeReached(offset, width.toFloat())
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                swipeContainerColor(visual.container).copy(
                                    alpha = if (reached) 1f else 0.45f,
                                ),
                            )
                            .padding(
                                start = if (atStart) 24.dp else 0.dp,
                                end = if (atStart) 0.dp else 24.dp,
                            ),
                        contentAlignment = if (atStart) Alignment.CenterStart else Alignment.CenterEnd,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (atStart) {
                                SwipeActionIcon(visual.icon, tint)
                                Text(
                                    text = swipeActionLabel(binding.action),
                                    color = tint,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            } else {
                                Text(
                                    text = swipeActionLabel(binding.action),
                                    color = tint,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                SwipeActionIcon(visual.icon, tint)
                            }
                        }
                    }
                }
            },
        ) {
            val statusDescription = indexStatusDescription(row.flags, row.toMe, row.hasAttachment)
            val rowSelected = selected
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(
                        if (rowSelected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    )
                    .combinedClickable(onLongClick = onLongPress, onClick = onClick)
                    .semantics {
                        this.selected = rowSelected
                    }
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
                    IndexStatusColumn(
                        row,
                        statusDescription,
                        selected = rowSelected,
                        modifier = Modifier.padding(end = 4.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (depth > 6) {
                                Text(
                                    text = depth.toString(),
                                    modifier = Modifier.padding(end = 4.dp),
                                    color = textColor,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            Text(
                                text = row.from,
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
                                    nowEpoch = nowEpoch,
                                    zone = zone,
                                ),
                                modifier = Modifier.width(dateWidth),
                                style = dateStyle,
                                color = textColor,
                                textDecoration = decoration,
                                maxLines = 1,
                                overflow = TextOverflow.Clip,
                                textAlign = TextAlign.End,
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

private fun sortKeyAdvertised(capabilities: Set<String>, key: SortKey): Boolean {
    fun has(name: String) = capabilities.any { it.equals(name, ignoreCase = true) }
    return when (key) {
        SortKey.Arrival -> true
        SortKey.Date, SortKey.From, SortKey.Subject, SortKey.To, SortKey.Cc, SortKey.Size -> has("SORT")
        SortKey.ThreadReferences -> has("THREAD=REFERENCES")
        SortKey.ThreadOrderedSubject -> has("THREAD=ORDEREDSUBJECT")
    }
}

@Composable
private fun SortMenuChoice(
    key: SortKey,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = sortShortLabel(key)
    DropdownMenuItem(
        text = { Text(if (enabled) label else "$label Not advertised") },
        onClick = onClick,
        leadingIcon = if (selected) {
            { Icon(imageVector = Icons.Filled.Check, contentDescription = null) }
        } else {
            null
        },
        enabled = enabled,
    )
}

private class SwipeBoxHolder {
    var state: SwipeToDismissBoxState? = null
}

private class WatchBackoff {
    var onRefresh: () -> Unit = {}
    var job: Job? = null
    var attempt: Int = 0
    var skipFirstStart: Boolean = true

    fun onWatchLost(scope: CoroutineScope, session: MailSession) {
        if (job?.isActive == true) return
        attempt = 0
        job = scope.launch {
            val delays = longArrayOf(2_000L, 10_000L, 60_000L)
            while (attempt < delays.size) {
                delay(delays[attempt])
                try {
                    session.resume()
                    attempt = 0
                    return@launch
                } catch (error: CancellationException) {
                    throw error
                } catch (_: MailFailure) {
                    attempt += 1
                }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

@Composable
private fun swipeContainerColor(name: String): Color = when (name) {
    "errorContainer" -> MaterialTheme.colorScheme.errorContainer
    "tertiaryContainer" -> MaterialTheme.colorScheme.tertiaryContainer
    "primaryContainer" -> MaterialTheme.colorScheme.primaryContainer
    else -> MaterialTheme.colorScheme.secondaryContainer
}

@Composable
private fun SwipeActionIcon(name: String, tint: Color) {
    Icon(
        imageVector = when (name) {
            "delete" -> Icons.Filled.Delete
            "drive_file_move" -> Icons.Filled.DriveFileMove
            "reply" -> Icons.AutoMirrored.Filled.Reply
            "reply_all" -> Icons.AutoMirrored.Filled.ReplyAll
            "flag" -> Icons.Filled.Flag
            else -> Icons.Outlined.OutlinedFlag
        },
        contentDescription = null,
        modifier = Modifier.size(24.dp),
        tint = tint,
    )
}

private fun dismissOffset(value: SwipeToDismissBoxValue, widthPx: Float, leftToRight: Boolean): Float {
    val span = if (widthPx > 0f) widthPx else 1f
    return when (value) {
        SwipeToDismissBoxValue.EndToStart -> if (leftToRight) -span else span
        SwipeToDismissBoxValue.StartToEnd -> if (leftToRight) span else -span
        SwipeToDismissBoxValue.Settled -> 0f
    }
}

private fun newMailPillText(count: Int, unnumbered: Boolean, newestFirst: Boolean): String {
    val arrow = if (newestFirst) "\u2191" else "\u2193"
    val base = if (unnumbered) {
        "New messages"
    } else if (count == 1) {
        "1 new message"
    } else {
        "$count new messages"
    }
    return "$base $arrow"
}

private fun newMailPillSpoken(count: Int, unnumbered: Boolean): String {
    val base = if (unnumbered) {
        "New messages"
    } else if (count == 1) {
        "1 new message"
    } else {
        "$count new messages"
    }
    return "$base, jump to newest"
}

@Composable
private fun BoxScope.NewMailPill(
    count: Int,
    unnumbered: Boolean,
    newestFirst: Boolean,
    onClick: () -> Unit,
) {
    val spoken = newMailPillSpoken(count, unnumbered)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shadowElevation = 3.dp,
        modifier = Modifier
            .align(if (newestFirst) Alignment.TopCenter else Alignment.BottomCenter)
            .padding(
                top = 8.dp,
                bottom = if (newestFirst) 8.dp else 72.dp,
            )
            .semantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        Text(
            text = newMailPillText(count, unnumbered, newestFirst),
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clearAndSetSemantics { },
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun IndexSummaryRow(
    text: String,
    sequenceWidth: Dp,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val description = if (expanded) "Hide messages" else "Show messages"
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
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

    data class Message(val row: IndexRow, val member: Boolean = false, val depth: Int = 0) : IndexEntry() {
        override val key: String = "m${row.uid}"
    }

    data class Summary(val rootUid: Long, val text: String) : IndexEntry() {
        override val key: String = "s$rootUid"
    }
}

private fun parseExpandedThreads(text: String): LinkedHashSet<Long> {
    val out = LinkedHashSet<Long>()
    if (text.isEmpty()) return out
    for (part in text.split(',')) {
        val uid = part.toLongOrNull() ?: continue
        out.add(uid)
    }
    return out
}

private fun formatExpandedThreads(uids: Collection<Long>): String = uids.joinToString(",")

private fun buildIndexEntries(
    rows: List<IndexRow>,
    summaries: Map<Long, ThreadSummary>,
    expanded: Set<Long>,
    threadMembers: Map<Long, IndexRow>,
    threadHidden: Map<Long, List<Long>>,
    threadDepth: Map<Long, Int>,
): List<IndexEntry> {
    val entries = ArrayList<IndexEntry>(rows.size)
    for (row in rows) {
        entries.add(IndexEntry.Message(row))
        val summary = summaries[row.uid]
        if (summary != null) {
            val text = if (row.uid in expanded) {
                threadSummaryLine(summary)
            } else {
                val unread = summary.unread + if ("\\Seen" !in row.flags) 1 else 0
                threadCountMark(summary.hidden + 1, unread)
            }
            entries.add(IndexEntry.Summary(row.uid, text))
        }
        if (row.uid !in expanded) continue
        for (uid in threadHidden[row.uid].orEmpty()) {
            val member = threadMembers[uid] ?: continue
            entries.add(IndexEntry.Message(member, member = true, depth = threadDepth[uid] ?: 1))
        }
    }
    return entries
}

private fun rootRowIndex(entries: List<IndexEntry>, lazyIndex: Int): Int {
    if (entries.isEmpty() || lazyIndex <= 0) return 0
    var roots = 0
    val last = minOf(lazyIndex, entries.lastIndex)
    for (index in 0..last) {
        val entry = entries[index]
        if (entry is IndexEntry.Message && !entry.member) {
            if (index == lazyIndex) return roots
            roots += 1
        } else if (index == lazyIndex) {
            return (roots - 1).coerceAtLeast(0)
        }
    }
    return roots
}

internal data class MailboxTitle(val leaf: String, val parent: String)

internal fun mailboxTitle(mailbox: String, delimiter: Char): MailboxTitle {
    val cut = mailbox.lastIndexOf(delimiter)
    if (cut < 0) return MailboxTitle(mailbox, "")
    return MailboxTitle(
        leaf = mailbox.substring(cut + 1),
        parent = mailbox.substring(0, cut),
    )
}

internal fun personalDelimiter(mailbox: String, namespaces: List<Namespace>): Char? {
    val personal = namespaces.filter { it.kind == NamespaceKind.Personal }
    val matched = personal.filter { mailbox.startsWith(it.prefix) }
    val chosen = if (matched.isNotEmpty()) {
        matched.maxBy { it.prefix.length }
    } else {
        personal.firstOrNull()
    }
    return chosen?.delimiter
}

internal suspend fun mailboxTitleFor(session: MailSession, mailbox: String): MailboxTitle {
    val delimiter = try {
        personalDelimiter(mailbox, session.namespaces())
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    } ?: return MailboxTitle(mailbox, "")
    return mailboxTitle(mailbox, delimiter)
}

@Composable
internal fun MailboxTitleLines(leaf: String, parent: String) {
    Column {
        Text(
            text = leaf,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (parent.isNotEmpty()) {
            Text(
                text = parent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
