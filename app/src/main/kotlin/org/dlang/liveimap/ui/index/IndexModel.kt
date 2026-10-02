package org.dlang.liveimap.ui.index

import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.session.IndexMode
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.ThreadNode
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.Density
import org.dlang.liveimap.settings.FolderView
import org.dlang.liveimap.settings.SettingsStore
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.SwipeAction
import org.dlang.liveimap.settings.SwipeBinding
import kotlin.math.abs

const val IndexPageSize = 60
const val SwipeWidthPercent = 40
const val ThreadConfirmExists = 5000

data class AppliedFilter(
    val label: String,
    val argument: String,
)

data class CollapsedThread(
    val rootUid: Long,
    val hiddenUids: List<Long>,
)

data class ThreadSummary(
    val hidden: Int,
    val unread: Int,
    val froms: List<String>,
    val extraFroms: Int,
)

fun previewLineCount(density: Density): Int = when (density) {
    Density.Compact -> 0
    Density.Medium -> 3
    Density.Large -> 7
}

fun swipeReached(offsetPx: Float, widthPx: Float): Boolean {
    if (widthPx <= 0f) return false
    return abs(offsetPx) * 100f >= widthPx * SwipeWidthPercent
}

fun indexSwipeBinding(
    offsetPx: Float,
    widthPx: Float,
    leftToRight: Boolean,
    multiSelect: Boolean,
    trailing: SwipeBinding,
    leading: SwipeBinding,
): SwipeBinding? {
    if (multiSelect || !swipeReached(offsetPx, widthPx)) return null
    val swipeLeft = offsetPx < 0f
    val trailingSwipe = if (leftToRight) swipeLeft else !swipeLeft
    return if (trailingSwipe) trailing else leading
}

fun barMoveMailbox(settings: AccountSettings): String {
    return listOf(settings.swipeTrailing, settings.swipeLeading)
        .filter { it.action == SwipeAction.Move }
        .map { it.moveMailbox }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
}

fun orderedThreadUids(node: ThreadNode, newestFirst: Boolean): List<Long> {
    fun subtreeMaxUid(current: ThreadNode): Long? {
        var maxUid = current.uid
        for (child in current.children) {
            val childMax = subtreeMaxUid(child) ?: continue
            if (maxUid == null || childMax > maxUid) maxUid = childMax
        }
        return maxUid
    }
    val children = if (node.children.isEmpty()) {
        node.children
    } else {
        node.children.sortedWith { left, right ->
            val leftKey = subtreeMaxUid(left)
            val rightKey = subtreeMaxUid(right)
            when {
                leftKey == null && rightKey == null -> 0
                leftKey == null -> 1
                rightKey == null -> -1
                newestFirst -> rightKey.compareTo(leftKey)
                else -> leftKey.compareTo(rightKey)
            }
        }
    }
    val out = ArrayList<Long>()
    fun walk(current: ThreadNode) {
        val uid = current.uid
        if (uid != null) out.add(uid)
        for (child in current.children) walk(child)
    }
    walk(node.copy(children = children))
    return out
}

private fun subtreeMaxUid(current: ThreadNode): Long? {
    var maxUid = current.uid
    for (child in current.children) {
        val childMax = subtreeMaxUid(child) ?: continue
        if (maxUid == null || childMax > maxUid) maxUid = childMax
    }
    return maxUid
}

private fun preorderUids(current: ThreadNode): List<Long> {
    val out = ArrayList<Long>()
    fun walk(node: ThreadNode) {
        val uid = node.uid
        if (uid != null) out.add(uid)
        for (child in node.children) walk(child)
    }
    walk(current)
    return out
}

fun collapsedThreads(node: ThreadNode, newestFirst: Boolean): List<CollapsedThread> {
    val children = node.children.sortedWith { left, right ->
        val leftKey = subtreeMaxUid(left)
        val rightKey = subtreeMaxUid(right)
        when {
            leftKey == null && rightKey == null -> 0
            leftKey == null -> 1
            rightKey == null -> -1
            newestFirst -> rightKey.compareTo(leftKey)
            else -> leftKey.compareTo(rightKey)
        }
    }
    val out = ArrayList<CollapsedThread>()
    for (child in children) {
        val uids = preorderUids(child)
        if (uids.isEmpty()) continue
        out.add(CollapsedThread(uids.first(), uids.drop(1)))
    }
    return out
}

fun threadSummaryLine(summary: ThreadSummary): String {
    var line = "${summary.hidden} more · ${summary.unread} unread"
    if (summary.froms.isNotEmpty()) {
        line = line + " · " + summary.froms.joinToString(", ")
    }
    if (summary.extraFroms != 0) {
        line = line + " +" + summary.extraFroms
    }
    return line
}

sealed class IndexCommand {
    data object None : IndexCommand()
    data class Compose(val seed: ComposeSeed) : IndexCommand()
    data class ShowFlags(val uid: Long) : IndexCommand()
}

class IndexModel(
    private val session: MailSession,
    private val store: SettingsStore,
    private val mailbox: String,
) {
    private var heldRows: List<IndexRow> = emptyList()
    private var order: List<Long> = emptyList()
    private var pageAnchor: Int = 0
    private var includePreview: Boolean = false
    private var activeSearch: String? = null
    private var filterUids: Set<Long>? = null
    private val filterStack = ArrayDeque<Set<Long>>()
    private val appliedFilters = ArrayList<AppliedFilter>()
    private var lastVisibleIndex: Int = 0
    private var threading = false
    private var threadPlan: List<CollapsedThread> = emptyList()

    var notice: String? = null
        private set

    var view: FolderView = FolderView(SortKey.Arrival, newestFirst = true)
        private set

    val filterActive: Boolean
        get() = filterUids != null

    val canWiden: Boolean
        get() = filterStack.isNotEmpty()

    val filters: List<AppliedFilter>
        get() = appliedFilters.toList()

    var summaries: Map<Long, ThreadSummary> = emptyMap()
        private set

    var account: AccountSettings = AccountSettings()
        private set

    val rows: List<IndexRow>
        get() = heldRows

    val anchorPage: Int
        get() = pageAnchor

    val menuKeys: List<SortKey>
        get() = SortKey.entries.filter { key ->
            key != SortKey.ThreadOrderedSubject || orderedSubjectAdvertised()
        }

    suspend fun loadWindow(): List<IndexRow> {
        activeSearch = null
        return replaceWindow { fetchCurrent() }
    }

    suspend fun applyView(next: FolderView): List<IndexRow> {
        val loaded = store.load()
        val saved = loaded.copy(folderViews = loaded.folderViews + (mailbox to next))
        store.save(saved)
        account = saved
        activeSearch = null
        filterUids = null
        filterStack.clear()
        appliedFilters.clear()
        includePreview = saved.density != Density.Compact
        view = next
        return replaceWindow { fetchView(next) }
    }

    suspend fun applySearch(query: String): List<IndexRow> {
        val hadFilter = filterUids != null
        filterUids = null
        filterStack.clear()
        appliedFilters.clear()
        if (query.isEmpty()) {
            if (activeSearch == null && !hadFilter) return heldRows
            activeSearch = null
            return replaceWindow { fetchCurrent() }
        }
        account = store.load()
        includePreview = account.density != Density.Compact
        activeSearch = query
        return replaceWindow { fetchSearch(query) }
    }

    suspend fun applyCriterion(kind: String, argument: String, narrow: Boolean, label: String): List<IndexRow> {
        val found = try {
            session.searchCriterion(kind, argument)
        } catch (failure: MailFailure) {
            notice = failure.text
            return heldRows
        }
        val loaded = store.load()
        val savedUids = filterUids
        val savedStack = filterStack.toList()
        val savedSearch = activeSearch
        val savedFilters = appliedFilters.toList()
        account = loaded
        includePreview = loaded.density != Density.Compact
        activeSearch = null
        val foundSet = found.toSet()
        val chip = AppliedFilter(label, argument)
        if (narrow && savedUids != null) {
            filterStack.addLast(savedUids)
            filterUids = savedUids.intersect(foundSet)
            appliedFilters.add(chip)
        } else {
            filterStack.clear()
            filterUids = foundSet
            appliedFilters.clear()
            appliedFilters.add(chip)
        }
        val rows = replaceWindow { fetchCurrent() }
        if (notice != null) {
            filterUids = savedUids
            filterStack.clear()
            filterStack.addAll(savedStack)
            activeSearch = savedSearch
            appliedFilters.clear()
            appliedFilters.addAll(savedFilters)
        }
        return rows
    }

    suspend fun showAll(): List<IndexRow> {
        val savedUids = filterUids
        val savedStack = filterStack.toList()
        val savedSearch = activeSearch
        val savedFilters = appliedFilters.toList()
        if (savedUids == null && savedStack.isEmpty() && savedSearch == null && savedFilters.isEmpty()) return heldRows
        filterUids = null
        filterStack.clear()
        activeSearch = null
        appliedFilters.clear()
        val rows = replaceWindow { fetchCurrent() }
        if (notice != null) {
            filterUids = savedUids
            filterStack.clear()
            filterStack.addAll(savedStack)
            activeSearch = savedSearch
            appliedFilters.clear()
            appliedFilters.addAll(savedFilters)
        }
        return rows
    }

    suspend fun widenFilter(): List<IndexRow> {
        if (filterStack.isEmpty()) return heldRows
        val savedUids = filterUids
        val savedStack = filterStack.toList()
        val savedSearch = activeSearch
        val savedFilters = appliedFilters.toList()
        filterUids = filterStack.removeLast()
        if (appliedFilters.isNotEmpty()) appliedFilters.removeAt(appliedFilters.lastIndex)
        activeSearch = null
        val rows = replaceWindow { fetchCurrent() }
        if (notice != null) {
            filterUids = savedUids
            filterStack.clear()
            filterStack.addAll(savedStack)
            activeSearch = savedSearch
            appliedFilters.clear()
            appliedFilters.addAll(savedFilters)
        }
        return rows
    }

    suspend fun dropFiltersFrom(index: Int): List<IndexRow> {
        if (index < 0 || index >= appliedFilters.size) return heldRows
        if (index == 0) return showAll()
        val savedUids = filterUids
        val savedStack = filterStack.toList()
        val savedSearch = activeSearch
        val savedFilters = appliedFilters.toList()
        repeat(appliedFilters.size - index) {
            if (filterStack.isNotEmpty()) filterUids = filterStack.removeLast()
        }
        appliedFilters.subList(index, appliedFilters.size).clear()
        activeSearch = null
        val rows = replaceWindow { fetchCurrent() }
        if (notice != null) {
            filterUids = savedUids
            filterStack.clear()
            filterStack.addAll(savedStack)
            activeSearch = savedSearch
            appliedFilters.clear()
            appliedFilters.addAll(savedFilters)
        }
        return rows
    }

    suspend fun onFirstVisible(index: Int): List<IndexRow> {
        val previousIndex = lastVisibleIndex
        lastVisibleIndex = index
        if (index < IndexPageSize) return heldRows
        if (previousIndex >= IndexPageSize) return heldRows
        val next = (pageAnchor + 1) * IndexPageSize
        if (next >= order.size) return heldRows
        val previous = heldRows
        val previousAnchor = pageAnchor
        val previousSummaries = summaries
        pageAnchor += 1
        try {
            heldRows = if (threading) loadThreadPage() else pagesOf(order)
            notice = null
        } catch (failure: MailFailure) {
            pageAnchor = previousAnchor
            lastVisibleIndex = previousIndex
            heldRows = previous
            summaries = previousSummaries
            notice = failure.text
        }
        return heldRows
    }

    suspend fun revealNewer(): List<IndexRow> {
        if (pageAnchor == 0 || order.isEmpty()) return heldRows
        val previous = heldRows
        val previousAnchor = pageAnchor
        val previousIndex = lastVisibleIndex
        val previousSummaries = summaries
        pageAnchor -= 1
        lastVisibleIndex = 0
        try {
            heldRows = if (threading) loadThreadPage() else pagesOf(order)
            notice = null
        } catch (failure: MailFailure) {
            pageAnchor = previousAnchor
            lastVisibleIndex = previousIndex
            heldRows = previous
            summaries = previousSummaries
            notice = failure.text
        }
        return heldRows
    }

    suspend fun applyChange(change: MailboxChange): List<IndexRow> {
        when (change) {
            is MailboxChange.Flags -> {
                heldRows = heldRows.map { row ->
                    if (row.uid == change.uid) row.copy(flags = change.flags) else row
                }
            }
            is MailboxChange.Exists,
            is MailboxChange.Expunge,
            MailboxChange.UidValidityReset,
            -> {
                replaceWindow { fetchCurrent() }
            }
        }
        return heldRows
    }

    suspend fun deleteMessages(uids: List<Long>): List<IndexRow> {
        if (uids.isEmpty()) return heldRows
        try {
            session.storeFlags(uids, setOf("\\Deleted"), emptySet())
            val idSet = uids.toSet()
            heldRows = heldRows.map { row ->
                if (row.uid in idSet) row.copy(flags = row.flags + "\\Deleted") else row
            }
            notice = null
        } catch (failure: MailFailure) {
            notice = failure.text
        }
        return heldRows
    }

    suspend fun moveMessages(uids: List<Long>, moveMailbox: String): List<IndexRow> {
        if (moveMailbox.isEmpty()) {
            notice = "Move folder is not set"
            return heldRows
        }
        if (uids.isEmpty()) return heldRows
        try {
            session.copyThenDelete(uids, moveMailbox)
            val gone = uids.toSet()
            order = order.filter { it !in gone }
            heldRows = heldRows.filter { it.uid !in gone }
            notice = null
        } catch (failure: MailFailure) {
            notice = failure.text
        }
        return heldRows
    }

    suspend fun changeFlags(uids: List<Long>, add: Set<String>, remove: Set<String>): List<IndexRow> {
        if (uids.isEmpty() || (add.isEmpty() && remove.isEmpty())) return heldRows
        try {
            session.storeFlags(uids, add, remove)
            val idSet = uids.toSet()
            heldRows = heldRows.map { row ->
                if (row.uid in idSet) row.copy(flags = (row.flags + add) - remove) else row
            }
            notice = null
        } catch (failure: MailFailure) {
            notice = failure.text
        }
        return heldRows
    }

    suspend fun expunge(): List<IndexRow> {
        try {
            session.uidExpungeDeleted()
        } catch (failure: MailFailure) {
            notice = failure.text
            return heldRows
        }
        return replaceWindow { fetchCurrent() }
    }

    suspend fun performSwipe(uid: Long, binding: SwipeBinding): IndexCommand {
        when (binding.action) {
            SwipeAction.Delete -> deleteMessages(listOf(uid))
            SwipeAction.Move -> moveMessages(listOf(uid), binding.moveMailbox)
            SwipeAction.Reply -> return IndexCommand.Compose(actionSeed(ComposeKind.Reply, listOf(uid)))
            SwipeAction.ReplyAll -> return IndexCommand.Compose(actionSeed(ComposeKind.ReplyAll, listOf(uid)))
            SwipeAction.SetFlag -> {
                if (binding.flag.isNotEmpty()) changeFlags(listOf(uid), setOf(binding.flag), emptySet())
            }
            SwipeAction.ClearFlag -> {
                if (binding.flag.isNotEmpty()) changeFlags(listOf(uid), emptySet(), setOf(binding.flag))
            }
            SwipeAction.FlagScreen -> return IndexCommand.ShowFlags(uid)
        }
        return IndexCommand.None
    }

    fun openSeed(uid: Long): ComposeSeed? {
        if (mailbox == account.postponedMailbox) {
            return ComposeSeed(ComposeKind.ResumePostpone, mailbox, listOf(uid))
        }
        return null
    }

    fun bounceSeed(uids: List<Long>): ComposeSeed = actionSeed(ComposeKind.Bounce, uids)

    fun actionSeed(kind: ComposeKind, uids: List<Long>): ComposeSeed =
        ComposeSeed(kind, mailbox, uids)

    suspend fun watch(onChange: (MailboxChange) -> Unit) {
        session.watch(mailbox, onChange)
    }

    suspend fun stopWatch() {
        session.stopWatch()
    }

    private suspend fun replaceWindow(load: suspend () -> List<IndexRow>): List<IndexRow> {
        val previous = heldRows
        val previousAnchor = pageAnchor
        val previousIndex = lastVisibleIndex
        val previousSummaries = summaries
        val previousThreading = threading
        val previousPlan = threadPlan
        pageAnchor = 0
        lastVisibleIndex = 0
        try {
            heldRows = load()
            notice = null
        } catch (failure: MailFailure) {
            heldRows = previous
            pageAnchor = previousAnchor
            lastVisibleIndex = previousIndex
            summaries = previousSummaries
            threading = previousThreading
            threadPlan = previousPlan
            notice = failure.text
        }
        return heldRows
    }

    private suspend fun fetchCurrent(): List<IndexRow> {
        val loaded = store.load()
        account = loaded
        includePreview = loaded.density != Density.Compact
        val query = activeSearch
        if (query != null) return fetchSearch(query)
        val resolved = loaded.folderViews[mailbox] ?: loaded.defaultView
        view = resolved
        return fetchView(resolved)
    }

    private suspend fun fetchView(folderView: FolderView): List<IndexRow> {
        pageAnchor = 0
        return when (folderView.key) {
            SortKey.Arrival -> fetchArrival(folderView.newestFirst)
            SortKey.ThreadReferences, SortKey.ThreadOrderedSubject ->
                fetchThread(folderView.key, folderView.newestFirst)
            SortKey.Date, SortKey.From, SortKey.Subject, SortKey.To, SortKey.Cc, SortKey.Size ->
                fetchSorted(folderView.key, folderView.newestFirst)
        }
    }

    private suspend fun fetchArrival(newestFirst: Boolean): List<IndexRow> {
        clearThreads()
        val mode = if (newestFirst) IndexMode.ArrivalNewest else IndexMode.ArrivalOldest
        val fetched = session.fetchIndex(
            IndexRequest(
                mailbox = mailbox,
                mode = mode,
                limit = IndexPageSize,
                prefetch = IndexPageSize,
                includePreview = includePreview,
            ),
        )
        val keep = filterUids
        if (keep == null) {
            order = fetched.map { it.uid }
            return fetched.take(IndexPageSize * 2)
        }
        val restricted = fetched.map { it.uid }.filter { it in keep }
        val loaded = pagesOf(restricted)
        order = restricted
        return loaded
    }

    private suspend fun fetchSorted(key: SortKey, newestFirst: Boolean): List<IndexRow> {
        clearThreads()
        val uids = restrict(session.sort(key, newestFirst))
        val loaded = pagesOf(uids)
        order = uids
        return loaded
    }

    private suspend fun fetchThread(key: SortKey, newestFirst: Boolean): List<IndexRow> {
        val collapsed = collapsedThreads(session.thread(key), newestFirst)
        val kept = restrictThreads(collapsed)
        threading = true
        threadPlan = kept
        order = kept.map { it.rootUid }
        return loadThreadPage()
    }

    private fun restrict(uids: List<Long>): List<Long> {
        val keep = filterUids ?: return uids
        return uids.filter { it in keep }
    }

    private suspend fun fetchSearch(query: String): List<IndexRow> {
        clearThreads()
        val uids = session.searchText(query)
        pageAnchor = 0
        val loaded = pagesOf(uids)
        order = uids
        return loaded
    }

    private suspend fun pagesOf(uids: List<Long>): List<IndexRow> {
        val visible = uids.drop(pageAnchor * IndexPageSize).take(IndexPageSize)
        val prefetch = uids.drop((pageAnchor + 1) * IndexPageSize).take(IndexPageSize)
        val loaded = ArrayList<IndexRow>(visible.size + prefetch.size)
        if (visible.isNotEmpty()) loaded += align(visible, fetchByUid(visible))
        if (prefetch.isNotEmpty()) loaded += align(prefetch, fetchByUid(prefetch))
        return loaded
    }

    private suspend fun fetchByUid(uids: List<Long>, preview: Boolean = includePreview): List<IndexRow> {
        return session.fetchIndex(
            IndexRequest(
                mailbox = mailbox,
                mode = IndexMode.ByUid,
                uids = uids,
                limit = IndexPageSize,
                prefetch = IndexPageSize,
                includePreview = preview,
            ),
        )
    }

    private fun clearThreads() {
        threading = false
        threadPlan = emptyList()
        summaries = emptyMap()
    }

    private fun restrictThreads(threads: List<CollapsedThread>): List<CollapsedThread> {
        val keep = filterUids ?: return threads
        return threads.filter { it.rootUid in keep }
    }

    private suspend fun loadThreadPage(): List<IndexRow> {
        val visible = order.drop(pageAnchor * IndexPageSize).take(IndexPageSize)
        val prefetch = order.drop((pageAnchor + 1) * IndexPageSize).take(IndexPageSize)
        val pageRoots = ArrayList<Long>(visible.size + prefetch.size)
        pageRoots.addAll(visible)
        pageRoots.addAll(prefetch)
        val loaded = ArrayList<IndexRow>(pageRoots.size)
        if (visible.isNotEmpty()) loaded += align(visible, fetchByUid(visible, includePreview))
        if (prefetch.isNotEmpty()) loaded += align(prefetch, fetchByUid(prefetch, includePreview))
        val byRoot = threadPlan.associateBy { it.rootUid }
        val hiddenUids = pageRoots.flatMap { byRoot[it]?.hiddenUids.orEmpty() }
        val hiddenRows = if (hiddenUids.isEmpty()) {
            emptyList()
        } else {
            fetchByUid(hiddenUids, preview = false)
        }
        summaries = summariesFor(pageRoots, byRoot, hiddenRows)
        return loaded
    }

    private fun summariesFor(
        roots: List<Long>,
        byRoot: Map<Long, CollapsedThread>,
        hiddenRows: List<IndexRow>,
    ): Map<Long, ThreadSummary> {
        val byUid = hiddenRows.associateBy { it.uid }
        val out = LinkedHashMap<Long, ThreadSummary>()
        for (root in roots) {
            val hiddenUids = byRoot[root]?.hiddenUids.orEmpty()
            if (hiddenUids.isEmpty()) continue
            val froms = LinkedHashSet<String>()
            var unread = 0
            for (uid in hiddenUids) {
                val row = byUid[uid]
                if (row == null || "\\Seen" !in row.flags) unread += 1
                val from = row?.from?.trim().orEmpty()
                if (from.isNotEmpty()) froms.add(from)
            }
            out[root] = ThreadSummary(
                hidden = hiddenUids.size,
                unread = unread,
                froms = froms.take(3),
                extraFroms = (froms.size - 3).coerceAtLeast(0),
            )
        }
        return out
    }

    private fun align(uids: List<Long>, fetched: List<IndexRow>): List<IndexRow> {
        val byUid = fetched.associateBy { it.uid }
        return uids.mapNotNull { byUid[it] }
    }

    private fun orderedSubjectAdvertised(): Boolean {
        return session.capabilities.any { it.equals("THREAD=ORDEREDSUBJECT", ignoreCase = true) }
    }
}
