package org.dlang.liveimap.ui.index
import org.dlang.liveimap.i18n.S

import android.content.Context
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dlang.liveimap.session.FolderAt
import org.dlang.liveimap.session.folderInfo
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.MessageRef
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.idleDroppedByServer
import org.dlang.liveimap.session.idleFallbackSeconds
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.FolderView
import org.dlang.liveimap.settings.SettingsStore
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.overAskThreshold
import org.dlang.liveimap.settings.pollIntervalSeconds
import org.dlang.liveimap.settings.slowerClientSort
import org.dlang.liveimap.settings.slowerClientThread
import org.dlang.liveimap.settings.slowerPoll

internal class LongSearchTail {
    var job: Job = Job().also { it.complete() }
}

internal class IndexLoadHost {
    lateinit var readBanner: () -> String?
    lateinit var writeBanner: (String?) -> Unit
    var banner: String?
        get() = readBanner()
        set(value) { writeBanner(value) }

    lateinit var readConnected: () -> Boolean
    lateinit var writeConnected: (Boolean) -> Unit
    var connected: Boolean
        get() = readConnected()
        set(value) { writeConnected(value) }

    lateinit var readFallbackAsk: () -> FolderView?
    lateinit var writeFallbackAsk: (FolderView?) -> Unit
    var fallbackAsk: FolderView?
        get() = readFallbackAsk()
        set(value) { writeFallbackAsk(value) }

    lateinit var readFallbackAskFromConnect: () -> Boolean
    lateinit var writeFallbackAskFromConnect: (Boolean) -> Unit
    var fallbackAskFromConnect: Boolean
        get() = readFallbackAskFromConnect()
        set(value) { writeFallbackAskFromConnect(value) }

    lateinit var readFallbackExists: () -> Int
    lateinit var writeFallbackExists: (Int) -> Unit
    var fallbackExists: Int
        get() = readFallbackExists()
        set(value) { writeFallbackExists(value) }

    lateinit var readFolderExists: () -> Int
    lateinit var writeFolderExists: (Int) -> Unit
    var folderExists: Int
        get() = readFolderExists()
        set(value) { writeFolderExists(value) }

    lateinit var readHeading: () -> MailboxTitle
    lateinit var writeHeading: (MailboxTitle) -> Unit
    var heading: MailboxTitle
        get() = readHeading()
        set(value) { writeHeading(value) }

    lateinit var readLoading: () -> Boolean
    lateinit var writeLoading: (Boolean) -> Unit
    var loading: Boolean
        get() = readLoading()
        set(value) { writeLoading(value) }

    lateinit var readSearchAsk: () -> String?
    lateinit var writeSearchAsk: (String?) -> Unit
    var searchAsk: String?
        get() = readSearchAsk()
        set(value) { writeSearchAsk(value) }

    lateinit var readSearchConfirmed: () -> Boolean
    lateinit var writeSearchConfirmed: (Boolean) -> Unit
    var searchConfirmed: Boolean
        get() = readSearchConfirmed()
        set(value) { writeSearchConfirmed(value) }

    lateinit var readSearchExists: () -> Int
    lateinit var writeSearchExists: (Int) -> Unit
    var searchExists: Int
        get() = readSearchExists()
        set(value) { writeSearchExists(value) }

    lateinit var readSearchProgress: () -> String?
    lateinit var writeSearchProgress: (String?) -> Unit
    var searchProgress: String?
        get() = readSearchProgress()
        set(value) { writeSearchProgress(value) }

    lateinit var readSortAsk: () -> FolderView?
    lateinit var writeSortAsk: (FolderView?) -> Unit
    var sortAsk: FolderView?
        get() = readSortAsk()
        set(value) { writeSortAsk(value) }

    lateinit var readSortAskFromConnect: () -> Boolean
    lateinit var writeSortAskFromConnect: (Boolean) -> Unit
    var sortAskFromConnect: Boolean
        get() = readSortAskFromConnect()
        set(value) { writeSortAskFromConnect(value) }

    lateinit var readSortConfirmed: () -> Boolean
    lateinit var writeSortConfirmed: (Boolean) -> Unit
    var sortConfirmed: Boolean
        get() = readSortConfirmed()
        set(value) { writeSortConfirmed(value) }

    lateinit var readSortExists: () -> Int
    lateinit var writeSortExists: (Int) -> Unit
    var sortExists: Int
        get() = readSortExists()
        set(value) { writeSortExists(value) }

    lateinit var readThreadAsk: () -> FolderView?
    lateinit var writeThreadAsk: (FolderView?) -> Unit
    var threadAsk: FolderView?
        get() = readThreadAsk()
        set(value) { writeThreadAsk(value) }

    lateinit var readThreadAskFromConnect: () -> Boolean
    lateinit var writeThreadAskFromConnect: (Boolean) -> Unit
    var threadAskFromConnect: Boolean
        get() = readThreadAskFromConnect()
        set(value) { writeThreadAskFromConnect(value) }

    lateinit var readThreadConfirmed: () -> Boolean
    lateinit var writeThreadConfirmed: (Boolean) -> Unit
    var threadConfirmed: Boolean
        get() = readThreadConfirmed()
        set(value) { writeThreadConfirmed(value) }

    lateinit var readThreadExists: () -> Int
    lateinit var writeThreadExists: (Int) -> Unit
    var threadExists: Int
        get() = readThreadExists()
        set(value) { writeThreadExists(value) }

    lateinit var expandedThreadsFn: () -> Set<MessageRef>
    val expandedThreads: Set<MessageRef> get() = expandedThreadsFn()

    lateinit var appContext: Context
    lateinit var held: IndexScreenHeld
    lateinit var listState: LazyListState
    lateinit var longSearchTail: LongSearchTail
    lateinit var model: IndexModel
    lateinit var scope: CoroutineScope
    lateinit var session: MailSession
    lateinit var snackbarHostState: SnackbarHostState
    lateinit var store: SettingsStore
    lateinit var cancelSearch: MutableState<Boolean>
    lateinit var indexWatch: Array<Job?>
    lateinit var userMovedSincePill: MutableState<Boolean>
    lateinit var watchMailboxNow: State<Boolean>
    lateinit var watchRecovery: WatchBackoff
    lateinit var fallbackChoice: Channel<AskAnswer>
    lateinit var searchChoice: Channel<AskAnswer>
    lateinit var sortChoice: Channel<AskAnswer>
    lateinit var threadChoice: Channel<AskAnswer>
    lateinit var mailbox: String
    var loadToken: Int = 0
    lateinit var notConnected: String
    var advancedQuery: String? = null
    lateinit var advancedScope: SearchScope

    lateinit var pullFn: () -> Unit
    fun pull() = pullFn()
    lateinit var postSnackFn: (String) -> Unit
    fun postSnack(text: String) = postSnackFn(text)
    lateinit var noteVisibleTopFn: () -> Unit
    fun noteVisibleTop() = noteVisibleTopFn()
    lateinit var onAdvancedConsumedFn: () -> Unit
    fun onAdvancedConsumed() = onAdvancedConsumedFn()
    lateinit var scrollToStartFn: suspend () -> Unit
    suspend fun scrollToStart() = scrollToStartFn()
    lateinit var seedExpandedFromStyleFn: () -> Unit
    fun seedExpandedFromStyle() = seedExpandedFromStyleFn()
    lateinit var showLongSearchHitsFn: (String, List<Long>) -> Unit
    fun showLongSearchHits(text: String, hits: List<Long>) = showLongSearchHitsFn(text, hits)
    lateinit var enqueueModelFn: (suspend () -> Unit) -> Unit
    fun enqueueModel(block: suspend () -> Unit) = enqueueModelFn(block)
    lateinit var onModelQueueFn: suspend (suspend () -> Unit) -> Unit
    suspend fun onModelQueue(block: suspend () -> Unit) = onModelQueueFn(block)

    suspend fun openIndexWindow(): Boolean {
        val opening = !held.windowReady
        val reuse = held.windowReady && held.boundMailbox == mailbox && loadToken == 0
        var pendingThread: FolderView? = null
        var pendingFallback: FolderView? = null
        var pendingSort: FolderView? = null
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
                    expandedThreads,
                    model.threadMembers,
                    model.threadHidden,
                    model.threadDepth,
                )
                val index = entries.indexOfFirst { entry ->
                    entry is IndexEntry.Message && entry.row.uid == uid.uid
                }
                if (index >= 0) listState.scrollToItem(index, held.anchorOffset)
            }
            held.recordAnchor = true
            true
        } else run {
            heading = MailboxTitle(mailbox, "")
            loading = true
            banner = null
            val settings = try {
                store.load()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                banner = error.message ?: notConnected
                return@run false
            }
            try {
                store.password()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                banner = error.message ?: notConnected
                return@run false
            }
            val opened = try {
                session.open(settings)
            } catch (error: CancellationException) {
                throw error
            } catch (error: MailFailure) {
                banner = error.text
                return@run false
            }
            when (opened) {
                is OpenResult.Rejected -> {
                    banner = opened.capabilities
                    return@run false
                }
                is OpenResult.Failed -> {
                    banner = opened.text
                    return@run false
                }
                OpenResult.Connected -> Unit
            }
            heading = mailboxTitleFor(session, mailbox)
            held.headingLeaf = heading.leaf
            held.headingParent = heading.parent
            val savedView = settings.folderViews[mailbox] ?: settings.defaultView
            val clientSort = slowerClientSort(settings)
            val clientThread = slowerClientThread(settings)
            val savedAdvertised = sortKeyAdvertised(session.featureCaps, savedView.key, clientSort, clientThread)
            if (!savedAdvertised) {
                onModelQueue { model.loadUnadvertisedArrival(savedView.newestFirst) }
            }
            val clientFallback = savedAdvertised && usesClientFallback(
                session.featureCaps,
                savedView.key,
                clientSort,
                clientThread,
            )
            if (clientFallback) {
                val selected = try {
                    session.folderInfo(FolderAt(mailbox))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    banner = error.text
                    return@run false
                }
                pendingExists = selected.exists
                if (
                    mailbox !in settings.fallbackAskSkip &&
                    !model.allowLargeClientFallback &&
                    overAskThreshold(selected.exists, settings.askFallback)
                ) {
                    pendingFallback = savedView
                    return@run false
                }
            }
            val threading = savedAdvertised && !clientFallback && (savedView.key == SortKey.ThreadReferences ||
                savedView.key == SortKey.ThreadOrderedSubject)
            if (threading) {
                val selected = try {
                    session.folderInfo(FolderAt(mailbox))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    banner = error.text
                    return@run false
                }
                pendingExists = selected.exists
                if (
                    mailbox !in settings.threadAskSkip &&
                    !threadConfirmed &&
                    overAskThreshold(selected.exists, settings.askThread)
                ) {
                    pendingThread = savedView
                    return@run false
                }
            }
            val serverSort = savedAdvertised && !clientFallback && !threading &&
                savedView.key != SortKey.Arrival
            if (serverSort) {
                val selected = try {
                    session.folderInfo(FolderAt(mailbox))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    banner = error.text
                    return@run false
                }
                pendingExists = selected.exists
                if (
                    mailbox !in settings.sortAskSkip &&
                    !sortConfirmed &&
                    overAskThreshold(selected.exists, settings.askSort)
                ) {
                    pendingSort = savedView
                    return@run false
                }
            }
            onModelQueue {
                model.loadWindow()
                if (opening) seedExpandedFromStyle()
                if (pendingExists <= 0) {
                    pendingExists = try {
                        model.shownExists() // F123
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: MailFailure) {
                        0
                    }
                }
                held.windowReady = true
                connected = true
                pull()
            }
            true
        }
        if (pendingExists > 0) folderExists = pendingExists
        val fallback = pendingFallback
        if (fallback != null) {
            fallbackExists = pendingExists
            fallbackAskFromConnect = true
            fallbackAsk = fallback
            val fallbackAnswer = fallbackChoice.receive()

            onModelQueue {
                if (fallbackAnswer != AskAnswer.Cancel) {
                    model.allowLargeClientFallback = true
                    model.applyView(fallback)
                } else {
                    model.allowLargeClientFallback = false
                    model.applyView(FolderView(SortKey.Arrival, newestFirst = true))
                }
                seedExpandedFromStyle()
                connected = true
                pull()
                held.windowReady = true
                loading = false
                if (model.rows.isNotEmpty()) scrollToStart()
            }
        } else {
            val chosen = pendingThread
            if (chosen != null) {
                threadExists = pendingExists
                threadAskFromConnect = true
                threadAsk = chosen
                val threadAnswer = threadChoice.receive()

                onModelQueue {
                    if (threadAnswer != AskAnswer.Cancel) {
                        model.applyView(chosen)
                    } else {
                        model.applyView(FolderView(SortKey.Arrival, newestFirst = true))
                    }
                    seedExpandedFromStyle()
                    connected = true
                    pull()
                    held.windowReady = true
                    loading = false
                    if (model.rows.isNotEmpty()) scrollToStart()
                }
            } else {
                val sortPending = pendingSort
                if (sortPending != null) {
                    sortExists = pendingExists
                    sortAskFromConnect = true
                    sortAsk = sortPending
                    val sortAnswer = sortChoice.receive()

                    onModelQueue {
                        if (sortAnswer != AskAnswer.Cancel) {
                            sortConfirmed = true
                            model.applyView(sortPending)
                        } else {
                            model.applyView(FolderView(SortKey.Arrival, newestFirst = true))
                        }
                        seedExpandedFromStyle()
                        connected = true
                        pull()
                        held.windowReady = true
                        loading = false
                        if (model.rows.isNotEmpty()) scrollToStart()
                    }
                } else if (!watchNow) {
                    loading = false
                    return false
                } else {
                    loading = false
                    if (!reuse && model.rows.isNotEmpty()) scrollToStart()
                }
            }
        }
        return true
    }

    suspend fun runAdvancedSearch() {
        val pendingAdvanced = advancedQuery
        val pendingScope = advancedScope
        if (!pendingAdvanced.isNullOrEmpty() && held.windowReady) {
            var runAdvanced = true
            if (
                advancedHasBody(pendingAdvanced) &&
                !searchConfirmed &&
                mailbox !in model.account.searchAskSkip
            ) {
                val exists = try {
                    var found = 0
                    onModelQueue { found = model.shownExists() }
                    found
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    postSnack(error.text)
                    onAdvancedConsumed()
                    runAdvanced = false
                    0
                }
                if (runAdvanced && overAskThreshold(exists, model.account.askSearch)) {
                    searchExists = exists
                    searchAsk = pendingAdvanced
                    val searchAnswer = searchChoice.receive()
                    if (searchAnswer == AskAnswer.Cancel) {
                        onAdvancedConsumed()
                        runAdvanced = false
                    } else {
                        searchConfirmed = true
                    }
                }
            }
            if (runAdvanced && model.isLongAdvanced(pendingAdvanced, pendingScope)) {
                // Long search: runs on its own connection without the list gate, so the list stays usable.
                val text: String = pendingAdvanced
                val mailboxAtStart = held.boundMailbox
                val orderAtStart = model.order
                val orderMailboxesAtStart = model.orderMailboxes
                onAdvancedConsumed()
                // On the screen scope, not this effect: the effect restarts when the query is consumed.
                // A second long search waits until this one returns and its apply op is queued.
                val previous = longSearchTail.job
                val searchJob = scope.launch {
                    previous.join()
                    val hits = try {
                        model.runLongSearch(text)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: MailFailure) {
                        postSnack(error.text)
                        null
                    }
                    if (hits != null) {
                        enqueueModel {
                            if (
                                held.boundMailbox == mailboxAtStart &&
                                model.order == orderAtStart &&
                                model.orderMailboxes == orderMailboxesAtStart
                            ) {
                                // Same mailbox and UID order this search started from: show the results in place.
                                noteVisibleTop()
                                model.applyAdvancedHits(text, hits)
                                pull()
                                if (model.rows.isNotEmpty()) scrollToStart()
                            } else {
                                // The user moved on: offer the results as their own view.
                                scope.launch {
                                    val shown = snackbarHostState.showSnackbar(
                                        message = S(appContext, "Search finished: %1\$d messages", hits.size),
                                        actionLabel = S(appContext, "Show"),
                                        duration = SnackbarDuration.Long,
                                    )
                                    if (shown == SnackbarResult.ActionPerformed) showLongSearchHits(text, hits)
                                }
                            }
                        }
                    }
                }
                longSearchTail.job = searchJob
                runAdvanced = false
            }
            if (runAdvanced) {
                cancelSearch.value = false
                try {
                    onModelQueue {
                        noteVisibleTop()
                        model.applyAdvanced(
                            pendingAdvanced,
                            pendingScope,
                            cancelled = { cancelSearch.value },
                        ) { current, total ->
                            searchProgress = S(appContext, "Folder %1\$d of %2\$d",
                                current,
                                total,
                            )
                        }
                        pull()
                    }
                } finally {
                    searchProgress = null
                }
                onAdvancedConsumed()
                if (model.rows.isNotEmpty()) scrollToStart()
            }
        }
    }

    suspend fun watchIndexMailbox(effectScope: CoroutineScope) {
        fun deliverMailboxChange(change: MailboxChange) {
            when (change) {
                MailboxChange.Reconnected -> scope.launch { watchRecovery.onRefresh() }
                // Called on the watch take coroutine: hop to the screen scope first (F25).
                MailboxChange.WatchLost -> scope.launch { watchRecovery.onWatchLost(scope, session) }
                else -> enqueueModel {
                    when (change) {
                        is MailboxChange.Exists -> folderExists = change.exists
                        is MailboxChange.Expunge -> folderExists = change.exists
                        else -> Unit
                    }
                    val anchorOffset = listState.firstVisibleItemScrollOffset
                    val anchorIndex = listState.firstVisibleItemIndex
                    val anchorEntries = buildIndexEntries(
                        model.rows,
                        expandedThreads,
                        model.threadMembers,
                        model.threadHidden,
                        model.threadDepth,
                        model.anchorPage,
                    )
                    val anchorEntry = anchorEntries.getOrNull(anchorIndex)

                    when (change) {
                        MailboxChange.ShownFlags -> {
                            val entries = buildIndexEntries(
                                model.rows,
                                expandedThreads,
                                model.threadMembers,
                                model.threadHidden,
                                model.threadDepth,
                                model.anchorPage,
                            )
                            val info = listState.layoutInfo.visibleItemsInfo
                            val shown = if (info.isEmpty()) {
                                model.rows.map { it.uid }
                            } else {
                                val uids = ArrayList<Long>()
                                for (item in info) {
                                    val entry = entries.getOrNull(item.index) ?: continue
                                    if (entry is IndexEntry.Message) uids.add(entry.row.uid)
                                }
                                if (uids.isEmpty()) model.rows.map { it.uid } else uids
                            }
                            model.applyShownFlags(shown, folderExists)
                        }
                        else -> model.applyChange(change)
                    }
                    pull()
                
                    if (
                        anchorEntry is IndexEntry.Message &&
                        (change is MailboxChange.Exists || change is MailboxChange.Expunge)
                    ) {
                        val afterEntries = buildIndexEntries(
                            model.rows,
                            expandedThreads,
                            model.threadMembers,
                            model.threadHidden,
                            model.threadDepth,
                            model.anchorPage,
                        )
                        val after = afterEntries.indexOfFirst { entry ->
                            entry is IndexEntry.Message &&
                                entry.row.uid == anchorEntry.row.uid &&
                                entry.row.mailbox == anchorEntry.row.mailbox
                        }
                        if (after >= 0 && after != anchorIndex) {
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
        try {
            snapshotFlow { watchMailboxNow.value }.collect { watching ->
                indexWatch[0]?.cancel()
                indexWatch[0]?.join()
                indexWatch[0] = null
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
                indexWatch[0] = effectScope.launch {
                    try {
                        val loaded = try {
                            store.load()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            null
                        }
                        val settings = loaded ?: Settings()
                        val pollSeconds = idleFallbackSeconds(
                            slowerPoll(settings),
                            pollIntervalSeconds(settings),
                            settings.idleFallbackSeconds,
                        )
                        suspend fun pollLoop(fixedSeconds: Int) {
                            while (true) {
                                delay(fixedSeconds * 1000L)
                                onModelQueue {
                                    try {
                                        val exists = model.pollShownExists()
                                        deliverMailboxChange(MailboxChange.Exists(exists))
                                    } catch (error: CancellationException) {
                                        throw error
                                    } catch (error: MailFailure) {
                                        postSnack(error.text)
                                    }
                                }
                            }
                        }
                        if (!session.featureCaps.idle) {
                            pollLoop(pollSeconds)
                        } else {
                            try {
                                model.watch { change ->
                                    deliverMailboxChange(change)
                                }
                            } catch (error: MailFailure) {
                                if (!idleDroppedByServer(error.text)) throw error
                                // The server closed the connection on IDLE (a server bug): say so, then poll instead.
                                postSnack(S(appContext, "The server closed the connection when asked to push new mail (IDLE), which is a server problem. Checking for new mail every %1\$d s instead.", pollSeconds))
                                pollLoop(pollSeconds)
                            }
                            // Keep-alive NOOP on the main connection while the watch IDLEs.
                            while (true) {
                                delay(settings.keepAliveMinutes * 60 * 1000L)
                                try {
                                    session.noop()
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (_: MailFailure) {
                                    // A dead connection is reconnected by the next real command; no snackbar here.
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
}

internal suspend fun IndexModel.openIndexLoad(effectScope: CoroutineScope, host: IndexLoadHost) {
    if (!host.openIndexWindow()) return
    host.runAdvancedSearch()
    host.watchIndexMailbox(effectScope)
}

