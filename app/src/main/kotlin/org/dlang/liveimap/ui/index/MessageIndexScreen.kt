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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import org.dlang.liveimap.R
import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.ui.compose.armForwardOnce
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.Namespace
import org.dlang.liveimap.session.NamespaceKind
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.engine.TrafficLog
import org.dlang.liveimap.session.mailSession
import org.dlang.liveimap.ui.ConnectionStatusStrip
import org.dlang.liveimap.ui.DebugConnectionStatus
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SettingsStore
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.DateFormat
import org.dlang.liveimap.settings.FolderView
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.StartRule
import org.dlang.liveimap.settings.SwipeAction
import org.dlang.liveimap.settings.startRuleChoices
import org.dlang.liveimap.settings.startRuleFor
import org.dlang.liveimap.settings.startRuleIsRecent
import org.dlang.liveimap.settings.SwipeBinding
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
    val labelRes: Int,
    val kind: String = "",
    val needsValue: Boolean = false,
    val role: FilterRole = FilterRole.Criterion,
)

private val filterChoices = listOf(
    FilterChoice(R.string.index_filter_all, role = FilterRole.All),
    FilterChoice(R.string.index_filter_new, "New"),
    FilterChoice(R.string.index_filter_not_new, "NotNew"),
    FilterChoice(R.string.index_filter_deleted, "Deleted"),
    FilterChoice(R.string.index_filter_not_deleted, "NotDeleted"),
    FilterChoice(R.string.index_filter_answered, "Answered"),
    FilterChoice(R.string.index_filter_not_answered, "NotAnswered"),
    FilterChoice(R.string.index_filter_important, "Important"),
    FilterChoice(R.string.index_filter_not_important, "NotImportant"),
    FilterChoice(R.string.index_filter_forwarded, "Forwarded"),
    FilterChoice(R.string.index_filter_not_forwarded, "NotForwarded"),
    FilterChoice(R.string.index_filter_from, "From", needsValue = true),
    FilterChoice(R.string.index_filter_to, "To", needsValue = true),
    FilterChoice(R.string.index_filter_cc, "Cc", needsValue = true),
    FilterChoice(R.string.index_filter_subject, "Subject", needsValue = true),
    FilterChoice(R.string.index_filter_text, "Text", needsValue = true),
    FilterChoice(R.string.index_filter_recipient, "Recipient", needsValue = true),
    FilterChoice(R.string.index_filter_participant, "Participant", needsValue = true),
    FilterChoice(R.string.index_filter_since, "Since", needsValue = true),
    FilterChoice(R.string.index_filter_before, "Before", needsValue = true),
    FilterChoice(R.string.index_filter_on, "On", needsValue = true),
    FilterChoice(R.string.index_filter_age, "Age", needsValue = true),
    FilterChoice(R.string.index_filter_larger, "Larger", needsValue = true),
    FilterChoice(R.string.index_filter_smaller, "Smaller", needsValue = true),
    FilterChoice(R.string.index_filter_keyword, "Keyword", needsValue = true),
    FilterChoice(R.string.index_filter_not_keyword, "NotKeyword", needsValue = true),
    FilterChoice(R.string.index_filter_narrow, role = FilterRole.Narrow),
    FilterChoice(R.string.index_filter_widen, role = FilterRole.Widen),
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
    nowWord: String,
    minWord: String,
    hourWord: String,
    hoursWord: String,
    dayWord: String,
    daysWord: String,
    agoPhrase: String,
    aheadPhrase: String,
    badPattern: String,
): String {
    if (epochSeconds == 0L) return ""
    val whenZoned = Instant.ofEpochSecond(epochSeconds).atZone(zone)
    val nowZoned = Instant.ofEpochSecond(nowEpoch).atZone(zone)
    return when (format) {
        DateFormat.Local -> localIndexDate.format(whenZoned)
        DateFormat.Short -> shortIndexDate(whenZoned, nowZoned)
        DateFormat.Relative -> relativeIndexDate(
            epochSeconds,
            whenZoned,
            nowEpoch,
            nowZoned,
            nowWord,
            minWord,
            hourWord,
            hoursWord,
            dayWord,
            daysWord,
            agoPhrase,
            aheadPhrase,
        )
        DateFormat.Custom -> customIndexDate(whenZoned, pattern, badPattern)
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
    nowWord: String,
    minWord: String,
    hourWord: String,
    hoursWord: String,
    dayWord: String,
    daysWord: String,
    agoPhrase: String,
    aheadPhrase: String,
): String {
    val delta = epochSeconds - nowEpoch
    val magnitude = if (delta < 0L) -delta else delta
    if (magnitude < relativeNowSeconds) return nowWord
    if (magnitude >= relativeDayLimit * daySeconds) return shortIndexDate(whenZoned, nowZoned)
    val days = magnitude / daySeconds
    if (days >= 1L) return relativeUnit(delta < 0L, days, dayWord, daysWord, agoPhrase, aheadPhrase)
    val hours = magnitude / hourSeconds
    if (hours >= 1L) return relativeUnit(delta < 0L, hours, hourWord, hoursWord, agoPhrase, aheadPhrase)
    val minutes = (magnitude / minuteSeconds).coerceAtLeast(1L)
    return relativeUnit(delta < 0L, minutes, minWord, minWord, agoPhrase, aheadPhrase)
}

private fun relativeUnit(
    past: Boolean,
    count: Long,
    one: String,
    many: String,
    agoPhrase: String,
    aheadPhrase: String,
): String {
    val unit = if (count == 1L) one else many
    val phrase = if (past) agoPhrase else aheadPhrase
    return phrase.format(count, unit)
}

private fun customIndexDate(whenZoned: ZonedDateTime, pattern: String, badPattern: String): String {
    if (pattern.isEmpty()) return badPattern
    val formatter = try {
        DateTimeFormatter.ofPattern(pattern)
    } catch (error: IllegalArgumentException) {
        return badPattern
    }
    return try {
        formatter.format(whenZoned)
    } catch (error: DateTimeException) {
        badPattern
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

fun emptyIndexText(
    query: String,
    mailbox: String,
    emptySentence: String,
    queryFormat: String,
): String {
    if (query.isBlank()) return emptySentence
    return queryFormat.format(query, mailbox)
}

internal class IndexScreenHeld : ViewModel() {
    var model: IndexModel? = null
    var boundMailbox: String? = null
    val rowsState = mutableStateOf<List<IndexRow>>(emptyList())
    val selectedState = mutableStateOf<List<Long>>(emptyList())
    val allMailboxState = mutableStateOf(false)
    var anchorUid: Long? = null
    var anchorOffset: Int = 0
    var anchorSummary: Boolean = false
    var windowReady: Boolean = false
    var recordAnchor: Boolean = true
    var headingLeaf: String = ""
    var headingParent: String = ""

    fun bind(session: MailSession, store: SettingsStore, mailbox: String): IndexModel {
        val current = model
        if (current != null && boundMailbox == mailbox) return current
        val created = IndexModel(session, store, mailbox)
        model = created
        boundMailbox = mailbox
        rowsState.value = emptyList()
        selectedState.value = emptyList()
        allMailboxState.value = false
        anchorUid = null
        anchorOffset = 0
        anchorSummary = false
        windowReady = false
        recordAnchor = true
        headingLeaf = ""
        headingParent = ""
        return created
    }

    fun dropLoaded() {
        model = null
        boundMailbox = null
        rowsState.value = emptyList()
        selectedState.value = emptyList()
        allMailboxState.value = false
        anchorUid = null
        anchorOffset = 0
        anchorSummary = false
        windowReady = false
        recordAnchor = true
        headingLeaf = ""
        headingParent = ""
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
    val emptyIndexSentence = stringResource(R.string.index_empty)
    val emptyIndexQueryFormat = LocalContext.current.resources.getText(R.string.index_empty_query).toString()
    val retryLabel = stringResource(R.string.index_retry)
    val indexNow = stringResource(R.string.index_now)
    val indexMin = stringResource(R.string.index_min)
    val indexHour = stringResource(R.string.index_hour)
    val indexHours = stringResource(R.string.index_hours)
    val indexDay = stringResource(R.string.index_day)
    val indexDays = stringResource(R.string.index_days)
    val indexAgo = stringResource(R.string.index_ago)
    val indexAhead = stringResource(R.string.index_ahead)
    val indexBadPattern = stringResource(R.string.index_bad_pattern)
    val store = remember { DataStoreSettingsStore(appContext) }
    val session = remember { mailSession() }
    val connectionState by session.connectionState.collectAsState()
    val debugStatus by TrafficLog.debugStatus.collectAsState()
    val held = viewModel<IndexScreenHeld>()
    val model = held.bind(session, store, mailbox)
    val reuseWindow = held.windowReady && held.boundMailbox == mailbox
    val host = LocalContext.current.hostActivity()
    DisposableEffect(held) {
        onDispose {
            if (host?.isChangingConfigurations != true) held.dropLoaded()
        }
    }
    remember {
        if (reuseWindow && held.anchorUid != null) held.recordAnchor = false
        true
    }
    val scope = rememberCoroutineScope()
    val watchRecovery = remember { WatchBackoff() }
    val gate = remember { Mutex() }
    val sync = remember { SnapshotSync() }
    val listState = rememberLazyListState()
    var rows by held.rowsState
    var loading by remember { mutableStateOf(!reuseWindow) }
    var banner by remember { mutableStateOf<String?>(null) }
    var fetchNotice by remember { mutableStateOf(if (reuseWindow) model.notice else null) }
    var loadToken by remember { mutableIntStateOf(0) }
    var snackEvent by remember { mutableIntStateOf(0) }
    var snackMessage by remember { mutableStateOf("") }
    var pendingNew by remember { mutableIntStateOf(if (reuseWindow) model.pendingNew else 0) }
    var newMailUnnumbered by remember { mutableStateOf(reuseWindow && model.newMailUnnumbered) }
    val userMovedSincePill = remember { mutableStateOf(false) }
    var lastReported by remember { mutableStateOf<String?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    var view by remember { mutableStateOf(if (reuseWindow) model.view else FolderView(SortKey.Arrival, true)) }
    var account by remember { mutableStateOf(if (reuseWindow) model.account else AccountSettings()) }
    var menuKeys by remember { mutableStateOf(model.menuKeys) }
    var anchorPage by remember { mutableStateOf(if (reuseWindow) model.anchorPage else 0) }
    var connected by remember { mutableStateOf(reuseWindow) }
    var menuOpen by remember { mutableStateOf(false) }
    var openAt by remember { mutableStateOf(false) }
    var heading by remember(mailbox) {
        mutableStateOf(
            if (reuseWindow) MailboxTitle(held.headingLeaf, held.headingParent) else MailboxTitle(mailbox, ""),
        )
    }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filterOpen by remember { mutableStateOf(false) }
    var filterActive by remember { mutableStateOf(reuseWindow && model.filterActive) }
    var canWiden by remember { mutableStateOf(reuseWindow && model.canWiden) }
    var narrowArmed by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<FilterChoice?>(null) }
    var promptText by remember { mutableStateOf("") }
    var filters by remember { mutableStateOf(if (reuseWindow) model.filters else emptyList()) }
    var summaries by remember { mutableStateOf(if (reuseWindow) model.summaries else emptyMap()) }
    var threadMembers by remember { mutableStateOf(if (reuseWindow) model.threadMembers else emptyMap()) }
    var threadHidden by remember { mutableStateOf(if (reuseWindow) model.threadHidden else emptyMap()) }
    var threadDepth by remember { mutableStateOf(if (reuseWindow) model.threadDepth else emptyMap()) }
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
    var multiSelect by remember {
        mutableStateOf(reuseWindow && (held.selectedState.value.isNotEmpty() || held.allMailboxState.value))
    }
    var selected by held.selectedState
    var allMailbox by held.allMailboxState
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
            actionLabel = retryLabel,
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
        val reuse = held.windowReady && held.boundMailbox == mailbox && loadToken == 0
        var pendingThread: FolderView? = null
        var pendingExists = 0
        val watchNow = if (reuse) {
            held.recordAnchor = false
            connected = true
            loading = false
            pull()
            heading = MailboxTitle(held.headingLeaf, held.headingParent)
            val uid = held.anchorUid
            if (uid != null) {
                val entries = buildIndexEntries(
                    model.rows,
                    model.summaries,
                    parseExpandedThreads(expandedText),
                    model.threadMembers,
                    model.threadHidden,
                    model.threadDepth,
                )
                val index = entries.indexOfFirst { entry ->
                    if (held.anchorSummary) {
                        entry is IndexEntry.Summary && entry.rootUid == uid
                    } else {
                        entry is IndexEntry.Message && entry.row.uid == uid
                    }
                }
                if (index >= 0) listState.scrollToItem(index, held.anchorOffset)
            }
            held.recordAnchor = true
            true
        } else gate.withLock {
            heading = MailboxTitle(mailbox, "")
            loading = true
            banner = null
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
            held.headingLeaf = heading.leaf
            held.headingParent = heading.parent
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
            held.windowReady = true
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
            held.windowReady = true
            loading = false
            if (model.rows.isNotEmpty()) scrollToStart()
        } else if (!watchNow) {
            loading = false
            return@LaunchedEffect
        } else {
            loading = false
            if (!reuse && model.rows.isNotEmpty()) scrollToStart()
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
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (index, offset) ->
            if (!held.recordAnchor) return@collect
            val entry = indexEntryState.value.getOrNull(index) ?: return@collect
            val uid = when (entry) {
                is IndexEntry.Message -> entry.row.uid
                is IndexEntry.Summary -> entry.rootUid
            }
            held.anchorUid = uid
            held.anchorOffset = offset
            held.anchorSummary = entry is IndexEntry.Summary
        }
    }
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
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.index_close),
                            )
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.index_back),
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
                            Icon(
                                imageVector = Icons.Filled.DriveFileMove,
                                contentDescription = stringResource(R.string.index_move),
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
                                        model.deleteMessages(uids, entire)
                                        pull()
                                        undo = model.mailUndo
                                    }
                                    val pending = undo
                                    if (pending != null) publishUndo(pending)
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.index_delete),
                            )
                        }
                        Box {
                            IconButton(onClick = { selectionMore = true }) {
                                Icon(
                                    imageVector = Icons.Filled.MoreVert,
                                    contentDescription = stringResource(R.string.index_more),
                                )
                            }
                            DropdownMenu(
                                expanded = selectionMore,
                                onDismissRequest = { selectionMore = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.index_select_all)) },
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
                                    text = { Text(stringResource(R.string.index_mark_answered)) },
                                    onClick = { applySelectionFlags(setOf("\\Answered"), emptySet()) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.index_mark_unanswered)) },
                                    onClick = { applySelectionFlags(emptySet(), setOf("\\Answered")) },
                                )
                                if (showUndelete) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.index_undelete)) },
                                        onClick = { applySelectionFlags(emptySet(), setOf("\\Deleted")) },
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.index_bounce)) },
                                    enabled = !allMailbox,
                                    onClick = {
                                        selectionMore = false
                                        onCompose(model.bounceSeed(selected))
                                    },
                                )
                                if (!allMailbox && selected.size == 1) {
                                    val oneUid = selected.single()
                                    DropdownMenuItem(
                                        text = { Text(forwardStyleName(account.forwardAsAttachment)) },
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
                                    text = { Text(stringResource(R.string.index_clear_selection)) },
                                    onClick = { clearSelection() },
                                )
                            }
                        }
                    } else {
                    IconButton(onClick = { refreshIndex() }) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.index_refresh),
                        )
                    }
                    if (connected) {
                IconButton(onClick = { searchVisible = true }) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.index_search),
                    )
                }
                Box {
                    IconButton(onClick = { filterOpen = true }) {
                        Icon(
                            imageVector = filterImage,
                            contentDescription = stringResource(R.string.index_filter),
                        )
                    }
                    DropdownMenu(
                        expanded = filterOpen,
                        onDismissRequest = { filterOpen = false },
                    ) {
                        for (choice in filterChoices) {
                            val label = stringResource(choice.labelRes)
                            val enabled = when (choice.role) {
                                FilterRole.Narrow -> filterActive
                                FilterRole.Widen -> canWiden
                                else -> true
                            }
                            DropdownMenuItem(
                                text = { Text(label) },
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
                                                runCriterion(choice.kind, "", label)
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
                                contentDescription = stringResource(R.string.index_sort),
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
                            text = { Text(stringResource(R.string.index_newest_first)) },
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
                        if (account.openAtInIndexMenu) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.label_open_at)) },
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
        DebugConnectionStatus(debugStatus)
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
                ) { Text(stringResource(R.string.index_expunge)) }
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
                        title = { Text(stringResource(R.string.index_expunge_title)) },
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
                            }) { Text(stringResource(R.string.index_expunge), color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                confirmExpunge = false
                                pendingExpungeUids = null
                            }) { Text(stringResource(R.string.index_cancel)) }
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
                    label = { Text(stringResource(R.string.index_search)) },
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
                Text(stringResource(R.string.index_flags), modifier = Modifier.padding(horizontal = 8.dp))
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
                        ) { Text(stringResource(R.string.index_set_flag, flag)) }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    gate.withLock {
                                        model.changeFlags(listOf(flagsFor), emptySet(), setOf(flag))
                                        pull()
                                    }
                                }
                            },
                        ) { Text(stringResource(R.string.index_clear_flag, flag)) }
                    }
                }
                TextButton(onClick = { flagUid = null }) { Text(stringResource(R.string.index_close)) }
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
                    nowWord = indexNow,
                    minWord = indexMin,
                    hourWord = indexHour,
                    hoursWord = indexHours,
                    dayWord = indexDay,
                    daysWord = indexDays,
                    agoPhrase = indexAgo,
                    aheadPhrase = indexAhead,
                    badPattern = indexBadPattern,
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
                        text = fetchNotice ?: emptyIndexText(
                            query,
                            mailbox,
                            emptyIndexSentence,
                            emptyIndexQueryFormat,
                        ),
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
                                mailbox = mailbox,
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
                                nowWord = indexNow,
                                minWord = indexMin,
                                hourWord = indexHour,
                                hoursWord = indexHours,
                                dayWord = indexDay,
                                daysWord = indexDays,
                                agoPhrase = indexAgo,
                                aheadPhrase = indexAhead,
                                badPattern = indexBadPattern,
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
            text = { Text(stringResource(R.string.index_compose)) },
            icon = { Icon(imageVector = Icons.Filled.Edit, contentDescription = stringResource(R.string.index_compose)) },
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
                        TextButton(onClick = { runUndo(offer) }) { Text(stringResource(R.string.index_undo)) }
                        if (showExpunge) {
                            TextButton(onClick = { runExpunge(offer) }) { Text(stringResource(R.string.index_expunge)) }
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
        val promptLabel = stringResource(pendingPrompt.labelRes)
        AlertDialog(
            onDismissRequest = { prompt = null },
            text = {
                OutlinedTextField(
                    value = promptText,
                    onValueChange = { promptText = it },
                    label = { Text(promptLabel) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val kind = pendingPrompt.kind
                    val value = promptText
                    prompt = null
                    runCriterion(kind, value, promptLabel)
                }) { Text(stringResource(R.string.index_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { prompt = null }) { Text(stringResource(R.string.index_dismiss)) }
            },
        )
    }
    if (openAt) {
        val selected = account.folderStarts[mailbox] ?: startRuleFor(mailbox, account)
        val choices = startRuleChoices(account.showRecentRules, selected)
        AlertDialog(
            onDismissRequest = { openAt = false },
            title = { Text(stringResource(R.string.label_open_at)) },
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
                                Text(startRuleName(rule))
                                if (startRuleIsRecent(rule)) Text(stringResource(R.string.label_recent_note))
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
                    }) { Text(stringResource(R.string.index_default)) }
                }
            },
            confirmButton = {
                TextButton(onClick = { openAt = false }) { Text(stringResource(R.string.index_close)) }
            },
        )
    }
    val asking = threadAsk
    if (asking != null) {
        AlertDialog(
            onDismissRequest = { resolveThreadAsk(false) },
            title = { Text(stringResource(R.string.index_thread_title)) },
            text = {
                Text(pluralStringResource(R.plurals.index_thread_body, threadExists, threadExists))
            },
            confirmButton = {
                TextButton(onClick = { resolveThreadAsk(true) }) { Text(stringResource(R.string.index_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { resolveThreadAsk(false) }) { Text(stringResource(R.string.index_cancel)) }
            },
        )
    }
}

private fun indexPartyName(mailbox: String, sentMailbox: String, recipients: String, from: String): String {
    if (sentMailbox.isNotEmpty() && mailbox == sentMailbox && recipients.isNotBlank()) return recipients
    return from
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IndexMessageRow(
    row: IndexRow,
    mailbox: String,
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
    nowWord: String,
    minWord: String,
    hourWord: String,
    hoursWord: String,
    dayWord: String,
    daysWord: String,
    agoPhrase: String,
    aheadPhrase: String,
    badPattern: String,
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
                                    text = swipeActionName(binding.action),
                                    color = tint,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            } else {
                                Text(
                                    text = swipeActionName(binding.action),
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
                                text = indexPartyName(mailbox, account.sentMailbox, row.recipients, row.from),
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
                                    nowWord = nowWord,
                                    minWord = minWord,
                                    hourWord = hourWord,
                                    hoursWord = hoursWord,
                                    dayWord = dayWord,
                                    daysWord = daysWord,
                                    agoPhrase = agoPhrase,
                                    aheadPhrase = aheadPhrase,
                                    badPattern = badPattern,
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
            TextButton(onClick = onRetry) { Text(stringResource(R.string.index_retry)) }
        }
    }
}

@Composable
private fun startRuleName(rule: StartRule): String = stringResource(
    when (rule) {
        StartRule.FirstUnseen -> R.string.label_first_unread
        StartRule.FirstRecent -> R.string.label_first_recent
        StartRule.FirstImportant -> R.string.label_first_important
        StartRule.FirstImportantOrUnseen -> R.string.label_first_important_unread
        StartRule.FirstImportantOrRecent -> R.string.label_first_important_recent
        StartRule.First -> R.string.label_top
        StartRule.Last -> R.string.label_bottom
        StartRule.Newest -> R.string.label_newest_message
    },
)

@Composable
private fun swipeActionName(action: SwipeAction): String = stringResource(
    when (action) {
        SwipeAction.Delete -> R.string.drawer_delete
        SwipeAction.Move -> R.string.label_move
        SwipeAction.Reply -> R.string.compose_reply
        SwipeAction.ReplyAll -> R.string.compose_reply_all
        SwipeAction.SetFlag -> R.string.label_set_flag
        SwipeAction.ClearFlag -> R.string.label_clear_flag
        SwipeAction.FlagScreen -> R.string.label_flag_screen
    },
)

@Composable
private fun forwardStyleName(asAttachment: Boolean): String = stringResource(
    if (asAttachment) R.string.label_forward_inline else R.string.settings_forward_attachment,
)

@Composable
private fun sortShortLabel(key: SortKey): String = stringResource(
    when (key) {
        SortKey.Arrival -> R.string.index_sort_arrival
        SortKey.Date -> R.string.index_sort_date
        SortKey.From -> R.string.index_sort_from
        SortKey.Subject -> R.string.index_sort_subject
        SortKey.To -> R.string.index_sort_to
        SortKey.Cc -> R.string.index_sort_cc
        SortKey.Size -> R.string.index_sort_size
        SortKey.ThreadReferences -> R.string.index_sort_thread
        SortKey.ThreadOrderedSubject -> R.string.index_sort_ordered
    },
)

internal fun sortKeyAdvertised(capabilities: Set<String>, key: SortKey): Boolean {
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
    val disabledLabel = stringResource(R.string.index_not_advertised, label)
    DropdownMenuItem(
        text = { Text(if (enabled) label else disabledLabel) },
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

@Composable
private fun BoxScope.NewMailPill(
    count: Int,
    unnumbered: Boolean,
    newestFirst: Boolean,
    onClick: () -> Unit,
) {
    val plain = stringResource(R.string.index_new_plain)
    val counted = pluralStringResource(R.plurals.index_new, count, count)
    val base = if (unnumbered) plain else counted
    val up = stringResource(R.string.index_pill_up, base)
    val down = stringResource(R.string.index_pill_down, base)
    val spoken = stringResource(R.string.index_pill_spoken, base)
    val label = if (newestFirst) up else down
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
            text = label,
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
