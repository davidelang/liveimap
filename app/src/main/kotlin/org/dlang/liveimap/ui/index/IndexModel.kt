package org.dlang.liveimap.ui.index

import kotlinx.coroutines.CancellationException
import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.session.FolderEntry
import org.dlang.liveimap.session.IndexMode
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.NamespaceKind
import org.dlang.liveimap.session.SearchEdge
import org.dlang.liveimap.session.ThreadNode
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.DeletePolicy
import org.dlang.liveimap.settings.Density
import org.dlang.liveimap.settings.FolderView
import org.dlang.liveimap.settings.SettingsStore
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.StartAfterChange
import org.dlang.liveimap.settings.moveCommandKind
import org.dlang.liveimap.settings.slowerClientSort
import org.dlang.liveimap.settings.slowerClientThread
import org.dlang.liveimap.settings.StartRule
import org.dlang.liveimap.settings.SwipeAction
import org.dlang.liveimap.settings.startRuleFor
import org.dlang.liveimap.settings.withFolderStart
import org.dlang.liveimap.settings.SwipeBinding
import org.dlang.liveimap.ui.compose.mailboxLeaf
import java.text.NumberFormat
import kotlin.math.abs

const val IndexPageSize = 60
const val SwipeWidthPercent = 40
const val ThreadConfirmExists = 5000
const val ClientFallbackWarn = 5000

enum class SimpleSearchField {
    Subject,
    From,
    To,
    Cc,
    Participant,
}

enum class AdvancedCombiner {
    And,
    Or,
}

data class AdvancedStep(
    val negated: Boolean,
    val kind: String,
    val argument: String,
)

data class AdvancedQuery(
    val combiner: AdvancedCombiner,
    val steps: List<AdvancedStep>,
)

fun encodeAdvancedQuery(combiner: AdvancedCombiner, steps: List<AdvancedStep>): String? {
    val lines = ArrayList<String>(steps.size + 1)
    lines.add(combiner.name)
    for (step in steps) {
        if (step.kind.contains('\t') || step.kind.contains('\n')) return null
        if (step.argument.contains('\t') || step.argument.contains('\n')) return null
        val mark = if (step.negated) "Not" else "Yes"
        lines.add(mark + "\t" + step.kind + "\t" + step.argument)
    }
    return lines.joinToString("\n")
}

fun parseAdvancedQuery(text: String): AdvancedQuery? {
    val lines = text.split('\n')
    if (lines.isEmpty()) return null
    val combiner = when (lines[0]) {
        AdvancedCombiner.And.name -> AdvancedCombiner.And
        AdvancedCombiner.Or.name -> AdvancedCombiner.Or
        else -> return null
    }
    val steps = ArrayList<AdvancedStep>(lines.size - 1)
    for (index in 1 until lines.size) {
        val line = lines[index]
        var tabs = 0
        for (ch in line) if (ch == '\t') tabs += 1
        if (tabs != 2) return null
        val parts = line.split('\t', limit = 3)
        if (parts.size != 3) return null
        val negated = when (parts[0]) {
            "Yes" -> false
            "Not" -> true
            else -> return null
        }
        steps.add(AdvancedStep(negated, parts[1], parts[2]))
    }
    return AdvancedQuery(combiner, steps)
}

enum class SearchScope {
    Current,
    Subtree,
    Subscribed,
    All,
}

data class SearchCount(
    val count: Int,
    val skipped: List<String>,
)

fun blocksFolderSelection(orderMailboxes: List<String>, indexMailbox: String): Boolean {
    for (name in orderMailboxes) {
        if (name.isNotEmpty() && name != indexMailbox) return true
    }
    return false
}

suspend fun expandMailboxes(
    roots: List<String>,
    children: suspend (String) -> List<FolderEntry>,
): List<String> {
    val seen = LinkedHashSet<String>()
    val order = ArrayList<String>()
    val pending = ArrayDeque<String>()
    for (root in roots) {
        if (root.isEmpty() || !seen.add(root)) continue
        order.add(root)
        pending.add(root)
    }
    while (pending.isNotEmpty()) {
        val mailbox = pending.removeFirst()
        for (child in children(mailbox)) {
            val name = child.mailbox
            if (name.isEmpty() || !seen.add(name)) continue
            order.add(name)
            if (child.hasChildren) pending.add(name)
        }
    }
    return order
}

suspend fun mailboxesFor(scope: SearchScope, home: String, session: MailSession): List<String> {
    return when (scope) {
        SearchScope.Current -> listOf(home)
        SearchScope.Subtree -> expandMailboxes(listOf(home)) { parent ->
            session.listLevel("", parent, false)
        }
        SearchScope.Subscribed -> session.subscribedMailboxes()
        SearchScope.All -> {
            val roots = ArrayList<String>()
            for (namespace in session.namespaces()) {
                for (entry in session.listLevel(namespace.prefix, null, false)) {
                    if (entry.mailbox.isEmpty()) continue
                    roots.add(entry.mailbox)
                }
            }
            expandMailboxes(roots) { parent ->
                session.listLevel("", parent, false)
            }
        }
    }
}

suspend fun usesFastScope(session: MailSession, scope: SearchScope): Boolean {
    if (!session.featureCaps.multisearch || scope == SearchScope.Current) return false
    if (scope != SearchScope.All) return true
    for (namespace in session.namespaces()) {
        if (namespace.kind != NamespaceKind.Personal) return false
    }
    return true
}

suspend fun countHits(
    session: MailSession,
    home: String,
    text: String,
    scope: SearchScope,
    cancelled: () -> Boolean = { false },
    onProgress: (Int, Int) -> Unit = { _, _ -> },
): SearchCount {
    if (scope == SearchScope.Current) {
        if (cancelled()) return SearchCount(0, emptyList())
        onProgress(1, 1)
        return SearchCount(session.searchCount("Advanced", text), emptyList())
    }
    if (usesFastScope(session, scope)) {
        if (cancelled()) return SearchCount(0, emptyList())
        onProgress(1, 1)
        return SearchCount(session.searchScopeCount(scope.name, home, "Advanced", text), emptyList())
    }
    val boxes = mailboxesFor(scope, home, session)
    var total = 0
    val skipped = ArrayList<String>()
    var leftHome = false
    try {
        for ((index, box) in boxes.withIndex()) {
            if (cancelled()) break
            onProgress(index + 1, boxes.size)
            try {
                session.select(box)
                if (box != home) leftHome = true
            } catch (error: CancellationException) {
                throw error
            } catch (_: MailFailure) {
                skipped.add(box)
                continue
            }
            total += session.searchCount("Advanced", text)
        }
    } finally {
        if (leftHome) {
            try {
                session.select(home)
            } catch (error: CancellationException) {
                throw error
            } catch (_: MailFailure) {
            }
        }
    }
    return SearchCount(total, skipped)
}

data class AppliedFilter(
    val label: String,
    val argument: String,
)

data class CollapsedThread(
    val rootUid: Long,
    val hiddenUids: List<Long>,
    val depthByUid: Map<Long, Int> = emptyMap(),
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

data class SwipeVisual(
    val container: String,
    val icon: String,
)

fun swipeBindingForOffset(
    offsetPx: Float,
    leftToRight: Boolean,
    trailing: SwipeBinding,
    leading: SwipeBinding,
): SwipeBinding? {
    if (offsetPx == 0f) return null
    val swipeLeft = offsetPx < 0f
    val trailingSwipe = if (leftToRight) swipeLeft else !swipeLeft
    return if (trailingSwipe) trailing else leading
}

fun swipeVisual(action: SwipeAction): SwipeVisual = when (action) {
    SwipeAction.Delete -> SwipeVisual("errorContainer", "delete")
    SwipeAction.Move -> SwipeVisual("tertiaryContainer", "drive_file_move")
    SwipeAction.Reply -> SwipeVisual("primaryContainer", "reply")
    SwipeAction.ReplyAll -> SwipeVisual("primaryContainer", "reply_all")
    SwipeAction.SetFlag -> SwipeVisual("secondaryContainer", "flag")
    SwipeAction.ClearFlag -> SwipeVisual("secondaryContainer", "outlined_flag")
    SwipeAction.FlagScreen -> SwipeVisual("secondaryContainer", "flag")
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
    return swipeBindingForOffset(offsetPx, leftToRight, trailing, leading)
}

fun barMoveMailbox(settings: AccountSettings): String {
    return listOf(settings.swipeTrailing, settings.swipeLeading)
        .filter { it.action == SwipeAction.Move }
        .map { it.moveMailbox }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
}

fun selectionTitle(allMailbox: Boolean, count: Int, exists: Int): String {
    val format = NumberFormat.getIntegerInstance()
    if (allMailbox && exists == 0) return "All selected"
    if (allMailbox && exists > 0) return "All ${format.format(exists)} selected"
    if (exists > 0 && count == exists) return "All ${format.format(count)} selected"
    return "${format.format(count)} selected"
}

fun effectiveDeletePolicy(
    policy: DeletePolicy,
    inTrash: Boolean,
    uidPlus: Boolean,
    trashKnown: Boolean,
): DeletePolicy {
    if (inTrash) return if (uidPlus) DeletePolicy.DeletePermanently else DeletePolicy.MarkDeleted
    if (policy == DeletePolicy.DeletePermanently && !uidPlus) return DeletePolicy.MarkDeleted
    if (policy == DeletePolicy.MoveToTrash && !trashKnown) return DeletePolicy.MarkDeleted
    return policy
}

fun alternateDeletePolicies(
    effective: DeletePolicy,
    uidPlus: Boolean,
    trashKnown: Boolean,
): List<DeletePolicy> =
    DeletePolicy.entries.filter { candidate ->
        candidate != effective && when (candidate) {
            DeletePolicy.MarkDeleted -> true
            DeletePolicy.MoveToTrash -> trashKnown
            DeletePolicy.DeletePermanently -> uidPlus
        }
    }

sealed class SelectAllTarget {
    data class Uids(val uids: List<Long>) : SelectAllTarget()
    data object EntireMailbox : SelectAllTarget()
}

fun selectAllTarget(filterActive: Boolean, order: List<Long>): SelectAllTarget {
    return if (filterActive) SelectAllTarget.Uids(order) else SelectAllTarget.EntireMailbox
}

data class MailUndo(
    val delete: Boolean,
    val uids: List<Long>,
    val allMailbox: Boolean = false,
    val targetMailbox: String = "",
    val destUids: List<Long> = emptyList(),
    val usedMove: Boolean = false,
)

fun mailUndoText(undo: MailUndo): String {
    if (undo.delete) return "Marked deleted"
    return "Moved to ${mailboxLeaf(undo.targetMailbox)}"
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

private fun threadDepths(root: ThreadNode): Map<Long, Int> {
    val out = LinkedHashMap<Long, Int>()
    var seenRoot = false
    fun walk(current: ThreadNode, depth: Int) {
        val uid = current.uid
        val next = if (uid != null) {
            if (seenRoot) out[uid] = depth else seenRoot = true
            depth + 1
        } else {
            depth
        }
        for (child in current.children) walk(child, next)
    }
    walk(root, 0)
    return out
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
    val children = flattenNullParents(node.children).sortedWith { left, right ->
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
        out.add(CollapsedThread(uids.first(), uids.drop(1), threadDepths(child)))
    }
    return out
}

private fun flattenNullParents(nodes: List<ThreadNode>): List<ThreadNode> {
    val out = ArrayList<ThreadNode>()
    for (item in nodes) {
        if (item.uid == null) {
            out.addAll(flattenNullParents(item.children))
        } else {
            out.add(item.copy(children = flattenNullParents(item.children)))
        }
    }
    return out
}

fun threadCountMark(messageCount: Int, unread: Int): String = "$messageCount · $unread unread"

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

fun threadMessageOrder(
    roots: List<Long>,
    hidden: Map<Long, List<Long>>,
    expanded: Set<Long>,
): List<Long> {
    val out = ArrayList<Long>(roots.size)
    for (root in roots) {
        out.add(root)
        if (root in expanded) out.addAll(hidden[root].orEmpty())
    }
    return out
}

sealed class IndexCommand {
    data object None : IndexCommand()
    data class Compose(val seed: ComposeSeed) : IndexCommand()
    data class ShowFlags(val uid: Long) : IndexCommand()
}

fun followingUid(uids: List<Long>, current: Long): Long? {
    val index = uids.indexOf(current)
    if (index < 0 || index + 1 >= uids.size) return null
    return uids[index + 1]
}

fun newestAtEnd(view: FolderView): Boolean = !view.newestFirst

internal object OpenMessageOrder {
    var mailbox: String = ""
        private set
    var uids: List<Long> = emptyList()
        private set
    private var sequences = emptyMap<Long, Int>()

    fun publish(mailbox: String, uids: List<Long>, rows: List<IndexRow>) {
        this.mailbox = mailbox
        this.uids = uids.toList()
        sequences = rows.associate { it.uid to it.sequence }
    }

    fun sequence(uid: Long): Int = sequences[uid] ?: 0

    fun clear() {
        mailbox = ""
        uids = emptyList()
        sequences = emptyMap()
    }
}

class IndexModel(
    private val session: MailSession,
    private val store: SettingsStore,
    private val mailbox: String,
) {
    private var heldRows: List<IndexRow> = emptyList()
    var order: List<Long> = emptyList()
        private set
    var orderMailboxes: List<String> = emptyList()
        private set
    private var loadedWindow = false
    private var pageAnchor: Int = 0
    private var includePreview: Boolean = false
    private var activeSearch: String? = null
    private var activeAdvanced: String? = null
    private var activeScope: SearchScope = SearchScope.Current
    private var scopeCancel: () -> Boolean = { false }
    private var scopeProgress: (Int, Int) -> Unit = { _, _ -> }
    private var activeSearchField: SimpleSearchField = SimpleSearchField.Subject
    private var filterUids: Set<Long>? = null
    private val filterStack = ArrayDeque<Set<Long>>()
    private val appliedFilters = ArrayList<AppliedFilter>()
    private var lastVisibleIndex: Int = 0
    private var startInHeld: Int = 0
    private var pendingNotice: String? = null
    private var honourKeep: Boolean = false
    private var keepSnapshot: Long? = null
    private var forceNewest: Boolean = false
    private var windowFailed: Boolean = false
    private var placedStart: Boolean = false
    private var notedTop: Long? = null
    private var threading = false
    private var threadPlan: List<CollapsedThread> = emptyList()
    private var knownExists: Int = 0
    private var arrivalTotal: Int = 0
    var pendingNew: Int = 0
        private set

    var newMailUnnumbered: Boolean = false
        private set

    var notice: String? = null
        private set

    fun reportNotice(text: String) {
        notice = text
    }

    var view: FolderView = FolderView(SortKey.Arrival, newestFirst = true)
    private var arrivalInstead: FolderView? = null
        private set

    val filterActive: Boolean
        get() = filterUids != null

    val searchActive: Boolean
        get() = activeSearch != null

    val canWiden: Boolean
        get() = filterStack.isNotEmpty()

    val filters: List<AppliedFilter>
        get() = appliedFilters.toList()

    var summaries: Map<Long, ThreadSummary> = emptyMap()
        private set

    var threadMembers: Map<Long, IndexRow> = emptyMap()
        private set

    private var openExpanded: Set<Long> = emptySet()

    val threadHidden: Map<Long, List<Long>>
        get() = threadPlan.associate { it.rootUid to it.hiddenUids }

    val threadDepth: Map<Long, Int>
        get() {
            val out = LinkedHashMap<Long, Int>()
            for (part in threadPlan) out.putAll(part.depthByUid)
            return out
        }

    fun noteExpanded(expanded: Set<Long>) {
        openExpanded = expanded
    }

    fun messageOrder(expanded: Set<Long>): List<Long> {
        if (!threading) return order
        return threadMessageOrder(order, threadHidden, expanded)
    }

    suspend fun cacheThreadMembers(rootUid: Long): Boolean {
        val missing = threadHidden[rootUid].orEmpty().filter { it !in threadMembers }
        if (missing.isEmpty()) return true
        val fetched = try {
            fetchByUid(missing, preview = false)
        } catch (failure: MailFailure) {
            notice = failure.text
            return false
        }
        val merged = LinkedHashMap(threadMembers)
        for (row in fetched) merged[row.uid] = row
        threadMembers = merged
        notice = null
        return true
    }

    fun publishMessageOrder() {
        val byUid = LinkedHashMap<Long, IndexRow>()
        for (row in threadMembers.values) byUid[row.uid] = row
        for (row in heldRows) byUid[row.uid] = row
        OpenMessageOrder.publish(mailbox, messageOrder(openExpanded), byUid.values.toList())
    }

    var account: AccountSettings = AccountSettings()
        private set

    var allowLargeClientFallback: Boolean = false

    suspend fun setFolderStart(rule: StartRule?) {
        val saved = withFolderStart(store.load(), mailbox, rule)
        store.save(saved)
        account = saved
    }

    val rows: List<IndexRow>
        get() = heldRows

    var mailUndo: MailUndo? = null
        private set

    fun clearMailUndo() {
        mailUndo = null
    }

    val anchorPage: Int
        get() = pageAnchor

    val startIndex: Int
        get() = if (heldRows.isEmpty()) 0 else startInHeld.coerceIn(0, heldRows.lastIndex)

    val newestHeldIndex: Int
        get() {
            if (heldRows.isEmpty()) return 0
            val best = if (view.key == SortKey.Arrival && filterUids == null && activeSearch == null) {
                heldRows.maxBy { it.sequence }
            } else {
                heldRows.maxBy { it.uid }
            }
            return heldRows.indexOf(best).coerceAtLeast(0)
        }

    fun noteTopUid(uid: Long?) {
        notedTop = uid
    }

    val showsNewestEnd: Boolean
        get() = includesNewest(if (arrivalTotal > 0) arrivalTotal else order.size)

    val menuKeys: List<SortKey>
        get() = SortKey.entries

    internal fun loadUnadvertisedArrival(newestFirst: Boolean) {
        arrivalInstead = FolderView(SortKey.Arrival, newestFirst)
    }

    suspend fun loadWindow(): List<IndexRow> {
        activeSearch = null
        activeAdvanced = null
        clearSearchScope()
        honourKeep = false
        forceNewest = false
        keepSnapshot = null
        return replaceWindow { fetchCurrent() }
    }

    suspend fun jumpToNewest(): List<IndexRow> {
        forceNewest = true
        honourKeep = false
        keepSnapshot = null
        return replaceWindow { fetchCurrent() }
    }

    fun acknowledgeNewMail() {
        pendingNew = 0
        newMailUnnumbered = false
    }

    suspend fun applyView(next: FolderView): List<IndexRow> {
        arrivalInstead = null
        val loaded = store.load()
        account = loaded
        val advanced = activeAdvanced
        if (advanced == null) activeSearch = null
        filterUids = null
        filterStack.clear()
        appliedFilters.clear()
        includePreview = loaded.density != Density.Compact
        view = next
        armKeep()
        return if (advanced != null) {
            replaceWindow { fetchSearch(advanced) }
        } else {
            replaceWindow { fetchView(next) }
        }
    }

    suspend fun applySearch(
        query: String,
        field: SimpleSearchField = SimpleSearchField.Subject,
    ): List<IndexRow> {
        val hadFilter = filterUids != null
        filterUids = null
        filterStack.clear()
        appliedFilters.clear()
        activeAdvanced = null
        clearSearchScope()
        if (query.isEmpty()) {
            if (activeSearch == null && !hadFilter) return heldRows
            activeSearch = null
            activeSearchField = field
            armKeep()
            return replaceWindow { fetchCurrent() }
        }
        account = store.load()
        includePreview = account.density != Density.Compact
        activeSearch = query
        activeSearchField = field
        armKeep()
        return replaceWindow { fetchSearch(query) }
    }

    suspend fun applyAdvanced(
        text: String,
        scope: SearchScope = SearchScope.Current,
        cancelled: () -> Boolean = { false },
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<IndexRow> {
        filterUids = null
        filterStack.clear()
        appliedFilters.clear()
        account = store.load()
        includePreview = account.density != Density.Compact
        activeAdvanced = text
        activeSearch = text
        activeScope = scope
        scopeCancel = cancelled
        scopeProgress = onProgress
        armKeep()
        return replaceWindow { fetchSearch(text) }
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
        val savedAdvanced = activeAdvanced
        val savedScope = activeScope
        val savedMailboxes = orderMailboxes
        val savedSearchField = activeSearchField
        val savedFilters = appliedFilters.toList()
        account = loaded
        includePreview = loaded.density != Density.Compact
        activeSearch = null
        activeAdvanced = null
        clearSearchScope()
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
        armKeep()
        val rows = replaceWindow { fetchCurrent() }
        if (windowFailed) {
            filterUids = savedUids
            filterStack.clear()
            filterStack.addAll(savedStack)
            activeSearch = savedSearch
            activeAdvanced = savedAdvanced
            activeScope = savedScope
            orderMailboxes = savedMailboxes
            activeSearchField = savedSearchField
            appliedFilters.clear()
            appliedFilters.addAll(savedFilters)
        }
        return rows
    }

    suspend fun showAll(): List<IndexRow> {
        val savedUids = filterUids
        val savedStack = filterStack.toList()
        val savedSearch = activeSearch
        val savedAdvanced = activeAdvanced
        val savedScope = activeScope
        val savedMailboxes = orderMailboxes
        val savedSearchField = activeSearchField
        val savedFilters = appliedFilters.toList()
        if (savedUids == null && savedStack.isEmpty() && savedSearch == null && savedFilters.isEmpty()) return heldRows
        filterUids = null
        filterStack.clear()
        activeSearch = null
        activeAdvanced = null
        clearSearchScope()
        appliedFilters.clear()
        armKeep()
        val rows = replaceWindow { fetchCurrent() }
        if (windowFailed) {
            filterUids = savedUids
            filterStack.clear()
            filterStack.addAll(savedStack)
            activeSearch = savedSearch
            activeAdvanced = savedAdvanced
            activeScope = savedScope
            orderMailboxes = savedMailboxes
            activeSearchField = savedSearchField
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
        val savedAdvanced = activeAdvanced
        val savedScope = activeScope
        val savedMailboxes = orderMailboxes
        val savedSearchField = activeSearchField
        val savedFilters = appliedFilters.toList()
        filterUids = filterStack.removeLast()
        if (appliedFilters.isNotEmpty()) appliedFilters.removeAt(appliedFilters.lastIndex)
        activeSearch = null
        activeAdvanced = null
        clearSearchScope()
        armKeep()
        val rows = replaceWindow { fetchCurrent() }
        if (windowFailed) {
            filterUids = savedUids
            filterStack.clear()
            filterStack.addAll(savedStack)
            activeSearch = savedSearch
            activeAdvanced = savedAdvanced
            activeScope = savedScope
            orderMailboxes = savedMailboxes
            activeSearchField = savedSearchField
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
        val savedAdvanced = activeAdvanced
        val savedScope = activeScope
        val savedMailboxes = orderMailboxes
        val savedSearchField = activeSearchField
        val savedFilters = appliedFilters.toList()
        repeat(appliedFilters.size - index) {
            if (filterStack.isNotEmpty()) filterUids = filterStack.removeLast()
        }
        appliedFilters.subList(index, appliedFilters.size).clear()
        activeSearch = null
        activeAdvanced = null
        clearSearchScope()
        armKeep()
        val rows = replaceWindow { fetchCurrent() }
        if (windowFailed) {
            filterUids = savedUids
            filterStack.clear()
            filterStack.addAll(savedStack)
            activeSearch = savedSearch
            activeAdvanced = savedAdvanced
            activeScope = savedScope
            orderMailboxes = savedMailboxes
            activeSearchField = savedSearchField
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
        val bound = if (arrivalTotal > 0) arrivalTotal else order.size
        val next = (pageAnchor + 1) * IndexPageSize
        if (next >= bound) return heldRows
        val previous = heldRows
        val previousAnchor = pageAnchor
        val previousSummaries = summaries
        val previousMembers = threadMembers
        pageAnchor += 1
        try {
            heldRows = when {
                threading -> loadThreadPage()
                arrivalTotal > 0 -> fetchArrivalWindow()
                else -> pagesOf(order)
            }
            notice = null
            publishMessageOrder()
        } catch (failure: MailFailure) {
            pageAnchor = previousAnchor
            lastVisibleIndex = previousIndex
            heldRows = previous
            summaries = previousSummaries
            threadMembers = previousMembers
            notice = failure.text
        }
        return heldRows
    }

    suspend fun revealNewer(): List<IndexRow> {
        if (pageAnchor == 0) return heldRows
        if (arrivalTotal <= 0 && order.isEmpty()) return heldRows
        val previous = heldRows
        val previousAnchor = pageAnchor
        val previousIndex = lastVisibleIndex
        val previousSummaries = summaries
        val previousMembers = threadMembers
        pageAnchor -= 1
        lastVisibleIndex = 0
        try {
            heldRows = when {
                threading -> loadThreadPage()
                arrivalTotal > 0 -> fetchArrivalWindow()
                else -> pagesOf(order)
            }
            notice = null
            if (threading) publishMessageOrder()
        } catch (failure: MailFailure) {
            pageAnchor = previousAnchor
            lastVisibleIndex = previousIndex
            heldRows = previous
            summaries = previousSummaries
            threadMembers = previousMembers
            notice = failure.text
        }
        return heldRows
    }

    suspend fun refreshShown(shownUids: List<Long>, reportedExists: Int): List<IndexRow> {
        if (!loadedWindow) return heldRows
        val previousRows = heldRows
        val previousOrder = order
        val previousMembers = threadMembers
        val previousSummaries = summaries
        val existsNow = maxOf(reportedExists, session.selectedExists())
        try {
            val shown = shownUids.distinct()
            if (shown.isNotEmpty()) {
                val flagsByUid = fetchByUid(shown, preview = false).associate { it.uid to it.flags }
                heldRows = heldRows.map { row ->
                    val flags = flagsByUid[row.uid]
                    if (flags == null) row else row.copy(flags = flags)
                }
                if (threadMembers.isNotEmpty()) {
                    val merged = LinkedHashMap(threadMembers)
                    for ((uid, row) in threadMembers) {
                        val flags = flagsByUid[uid] ?: continue
                        merged[uid] = row.copy(flags = flags)
                    }
                    threadMembers = merged
                }
                refreshSummaryUnread()
            }
            val baseline = knownExists
            if (baseline > 0 && existsNow > baseline) {
                addNewTail(existsNow - baseline)
            }
            if (existsNow > 0) knownExists = existsNow
            notice = null
            publishMessageOrder()
        } catch (failure: MailFailure) {
            heldRows = previousRows
            order = previousOrder
            threadMembers = previousMembers
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
            is MailboxChange.Exists -> {
                if (activeSearch != null || filterUids != null || view.key != SortKey.Arrival) {
                    if (change.exists > 0) knownExists = change.exists
                    pendingNew = 0
                    newMailUnnumbered = true
                } else {
                    applyArrivalGrowth(change.exists)
                }
            }
            is MailboxChange.Expunge -> {
                if (loadedWindow) reloadKeepingAnchor()
            }
            MailboxChange.UidValidityReset -> {
                replaceWindow { fetchCurrent() }
            }
            MailboxChange.WatchLost,
            MailboxChange.Reconnected,
            -> Unit
        }
        return heldRows
    }

    suspend fun deleteMessages(uids: List<Long>, allMailbox: Boolean = false): List<IndexRow> {
        if (!allMailbox && uids.isEmpty()) return heldRows
        try {
            if (allMailbox) session.storeFlagsAll(setOf("\\Deleted"), emptySet())
            else session.storeFlags(uids, setOf("\\Deleted"), emptySet())
            val idSet = uids.toSet()
            heldRows = heldRows.map { row ->
                if (allMailbox || row.uid in idSet) row.copy(flags = row.flags + "\\Deleted") else row
            }
            notice = null
            mailUndo = MailUndo(delete = true, uids = uids, allMailbox = allMailbox)
        } catch (failure: MailFailure) {
            notice = failure.text
            mailUndo = null
        }
        return heldRows
    }

    suspend fun moveMessages(uids: List<Long>, moveMailbox: String, allMailbox: Boolean = false): List<IndexRow> {
        if (moveMailbox.isEmpty()) {
            notice = "Move folder is not set"
            mailUndo = null
            return heldRows
        }
        if (!allMailbox && uids.isEmpty()) return heldRows
        val settings = store.load()
        account = settings
        try {
            if (allMailbox) session.copyAllThenDelete(moveMailbox)
            else session.copyThenDelete(uids, moveMailbox)
            val dest = session.takeCopiedUids()
            val usedMove = moveCommandKind(settings.moveMethod, session.featureCaps.move) == "Move"
            if (allMailbox) {
                order = emptyList()
                heldRows = emptyList()
            } else {
                val gone = uids.toSet()
                order = order.filter { it !in gone }
                heldRows = heldRows.filter { it.uid !in gone }
            }
            notice = null
            mailUndo = MailUndo(
                delete = false,
                uids = uids,
                allMailbox = allMailbox,
                targetMailbox = moveMailbox,
                destUids = dest,
                usedMove = usedMove,
            )
            OpenMessageOrder.publish(mailbox, order, heldRows)
        } catch (failure: MailFailure) {
            notice = failure.text
            mailUndo = null
        }
        return heldRows
    }

    suspend fun deletePermanently(uids: List<Long>, allMailbox: Boolean = false): List<IndexRow> {
        if (!session.featureCaps.uidPlus) return heldRows
        if (!allMailbox && uids.isEmpty()) return heldRows
        try {
            if (allMailbox) {
                session.storeFlagsAll(setOf("\\Deleted"), emptySet())
                session.uidExpungeDeleted()
                order = emptyList()
                heldRows = emptyList()
            } else {
                session.storeFlags(uids, setOf("\\Deleted"), emptySet())
                session.uidExpunge(uids)
                val gone = uids.toSet()
                order = order.filter { it !in gone }
                heldRows = heldRows.filter { it.uid !in gone }
            }
            notice = null
            mailUndo = null
            OpenMessageOrder.publish(mailbox, order, heldRows)
        } catch (failure: MailFailure) {
            notice = failure.text
            mailUndo = null
        }
        return heldRows
    }

    suspend fun undoDelete(uids: List<Long>, allMailbox: Boolean = false): List<IndexRow> {
        try {
            if (allMailbox) session.storeFlagsAll(emptySet(), setOf("\\Deleted"))
            else if (uids.isNotEmpty()) session.storeFlags(uids, emptySet(), setOf("\\Deleted"))
            val idSet = uids.toSet()
            heldRows = heldRows.map { row ->
                if (allMailbox || row.uid in idSet) row.copy(flags = row.flags - "\\Deleted") else row
            }
            notice = null
            mailUndo = null
        } catch (failure: MailFailure) {
            notice = failure.text
        }
        return heldRows
    }

    suspend fun undoMove(
        sourceUids: List<Long>,
        targetMailbox: String,
        destUids: List<Long>,
        usedMove: Boolean,
        allMailbox: Boolean = false,
    ): List<IndexRow> {
        try {
            if (usedMove) {
                session.select(targetMailbox)
                session.copyThenDelete(destUids, mailbox)
                session.select(mailbox)
            } else {
                if (allMailbox) session.storeFlagsAll(emptySet(), setOf("\\Deleted"))
                else if (sourceUids.isNotEmpty()) session.storeFlags(sourceUids, emptySet(), setOf("\\Deleted"))
                if (destUids.isNotEmpty()) {
                    session.select(targetMailbox)
                    session.storeFlags(destUids, setOf("\\Deleted"), emptySet())
                    session.select(mailbox)
                }
            }
            notice = null
            mailUndo = null
            if (loadedWindow) return reloadKeepingAnchor()
        } catch (failure: MailFailure) {
            notice = failure.text
        }
        return heldRows
    }

    suspend fun changeFlags(
        uids: List<Long>,
        add: Set<String>,
        remove: Set<String>,
        allMailbox: Boolean = false,
    ): List<IndexRow> {
        if (allMailbox) {
            if (add.isEmpty() && remove.isEmpty()) return heldRows
        } else if (uids.isEmpty() || (add.isEmpty() && remove.isEmpty())) {
            return heldRows
        }
        try {
            if (allMailbox) session.storeFlagsAll(add, remove)
            else session.storeFlags(uids, add, remove)
            val idSet = uids.toSet()
            heldRows = heldRows.map { row ->
                if (allMailbox || row.uid in idSet) row.copy(flags = (row.flags + add) - remove) else row
            }
            notice = null
        } catch (failure: MailFailure) {
            notice = failure.text
        }
        return heldRows
    }

    suspend fun expunge(): List<IndexRow> {
        if (!session.featureCaps.uidPlus) return heldRows
        try {
            session.uidExpungeDeleted()
        } catch (failure: MailFailure) {
            notice = failure.text
            return heldRows
        }
        if (!loadedWindow) return heldRows
        return reloadKeepingAnchor()
    }

    suspend fun expungeUids(uids: List<Long>): List<IndexRow> {
        if (uids.isEmpty()) return heldRows
        try {
            session.uidExpunge(uids)
        } catch (failure: MailFailure) {
            notice = failure.text
            return heldRows
        }
        mailUndo = null
        if (!loadedWindow) return heldRows
        return reloadKeepingAnchor()
    }

    suspend fun performSwipe(uid: Long, binding: SwipeBinding): IndexCommand {
        when (binding.action) {
            SwipeAction.Delete -> {
                val settings = store.load()
                account = settings
                val trash = session.knownTrash()
                val policy = effectiveDeletePolicy(
                    settings.deletePolicy,
                    trash.isNotEmpty() && mailbox == trash,
                    session.featureCaps.uidPlus,
                    trash.isNotEmpty(),
                )
                when (policy) {
                    DeletePolicy.MarkDeleted -> deleteMessages(listOf(uid))
                    DeletePolicy.MoveToTrash -> moveMessages(listOf(uid), trash)
                    DeletePolicy.DeletePermanently -> deletePermanently(listOf(uid))
                }
            }
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
        val previousMembers = threadMembers
        val previousThreading = threading
        val previousPlan = threadPlan
        pageAnchor = 0
        lastVisibleIndex = 0
        placedStart = false
        windowFailed = false
        try {
            heldRows = load()
            loadedWindow = true
            notice = pendingNotice
            pendingNotice = null
            if (!placedStart) lastVisibleIndex = previousIndex
            rememberExists()
            publishMessageOrder()
        } catch (failure: MailFailure) {
            heldRows = previous
            pageAnchor = previousAnchor
            lastVisibleIndex = previousIndex
            summaries = previousSummaries
            threadMembers = previousMembers
            threading = previousThreading
            threadPlan = previousPlan
            windowFailed = true
            pendingNotice = null
            forceNewest = false
            honourKeep = false
            notice = failure.text
        }
        return heldRows
    }

    private suspend fun fetchCurrent(preserveAnchor: Int? = null): List<IndexRow> {
        val loaded = store.load()
        account = loaded
        includePreview = loaded.density != Density.Compact
        val query = activeSearch
        if (query != null) return fetchSearch(query, preserveAnchor)
        val saved = if (loadedWindow) {
            view
        } else {
            loaded.folderViews[mailbox] ?: loaded.defaultView
        }
        val resolved = arrivalInstead ?: saved
        view = resolved
        return fetchView(resolved, preserveAnchor)
    }

    private suspend fun fetchView(folderView: FolderView, preserveAnchor: Int? = null): List<IndexRow> {
        view = folderView
        pageAnchor = preserveAnchor ?: 0
        return when (folderView.key) {
            SortKey.Arrival -> fetchArrival(folderView.newestFirst, preserveAnchor)
            SortKey.ThreadReferences, SortKey.ThreadOrderedSubject ->
                fetchThread(folderView.key, folderView.newestFirst, preserveAnchor)
            SortKey.Date, SortKey.From, SortKey.Subject, SortKey.To, SortKey.Cc, SortKey.Size ->
                fetchSorted(folderView.key, folderView.newestFirst, preserveAnchor)
        }
    }

    private suspend fun reloadKeepingAnchor(): List<IndexRow> {
        val kept = pageAnchor
        return replaceWindow { fetchCurrent(kept) }
    }

    private suspend fun fetchArrival(newestFirst: Boolean, preserveAnchor: Int? = null): List<IndexRow> {
        clearThreads()
        val keep = filterUids
        if (keep != null) {
            val sorted = if (newestFirst) keep.sortedDescending() else keep.sorted()
            order = sorted
            arrivalTotal = 0
            pendingNew = 0
            val target = resolveTarget(sorted.size, preserveAnchor)
            pageAnchor = clampedAnchor(sorted.size, preserveAnchor, target ?: 0)
            val loaded = pagesOf(sorted)
            if (target != null) rememberStart(target, loaded.size)
            return loaded
        }
        val exists = session.selectedExists()
        if (exists <= 0) {
            session.fetchIndex(
                IndexRequest(
                    mailbox = mailbox,
                    mode = IndexMode.ArrivalRange,
                    firstSequence = 0,
                    lastSequence = 0,
                    limit = IndexPageSize,
                    prefetch = IndexPageSize,
                    includePreview = includePreview,
                ),
            )
            order = emptyList()
            pageAnchor = 0
            arrivalTotal = 0
            pendingNew = 0
            val target = resolveTarget(0, preserveAnchor)
            if (target != null) rememberStart(0, 0)
            return emptyList()
        }
        val previousTotal = arrivalTotal
        arrivalTotal = exists
        val target = resolveTarget(exists, preserveAnchor)
        pageAnchor = clampedAnchor(exists, preserveAnchor, target ?: 0)
        return try {
            val loaded = fetchArrivalWindow()
            pendingNew = 0
            if (target != null) rememberStart(target, loaded.size)
            loaded
        } catch (failure: MailFailure) {
            arrivalTotal = previousTotal
            throw failure
        }
    }

    private suspend fun fetchArrivalWindow(): List<IndexRow> {
        val exists = arrivalTotal
        val span = arrivalSpan(exists, pageAnchor, view.newestFirst) ?: run {
            order = emptyList()
            return emptyList()
        }
        val fetched = session.fetchIndex(
            IndexRequest(
                mailbox = mailbox,
                mode = IndexMode.ArrivalRange,
                firstSequence = span.first,
                lastSequence = span.last,
                limit = IndexPageSize,
                prefetch = IndexPageSize,
                includePreview = includePreview,
            ),
        )
        val rows = if (view.newestFirst) {
            fetched.sortedByDescending { it.sequence }
        } else {
            fetched.sortedBy { it.sequence }
        }
        order = rows.map { it.uid }
        return rows
    }

    private suspend fun applyArrivalGrowth(existsNow: Int) {
        val baseline = knownExists
        val growth = when {
            baseline > 0 -> existsNow - baseline
            existsNow > 0 && loadedWindow -> existsNow
            else -> 0
        }
        val wasAtEnd = includesNewest(if (arrivalTotal > 0) arrivalTotal else order.size)
        if (existsNow > 0) {
            knownExists = existsNow
            if (filterUids == null) arrivalTotal = existsNow
        }
        if (growth <= 0) return
        if (wasAtEnd) addNewTail(growth)
        pendingNew += growth
        newMailUnnumbered = false
    }

    private fun includesNewest(bound: Int): Boolean {
        if (view.newestFirst) return pageAnchor == 0
        if (bound <= 0) return pageAnchor == 0
        val lastPage = (bound - 1) / IndexPageSize
        val start = (lastPage - 1).coerceAtLeast(0)
        return pageAnchor in start..lastPage
    }

    private fun clampedAnchor(count: Int, preserveAnchor: Int?, target: Int): Int {
        if (count <= 0) return 0
        val lastPage = (count - 1) / IndexPageSize
        if (preserveAnchor != null) return preserveAnchor.coerceIn(0, lastPage)
        val maxAnchor = (lastPage - 1).coerceAtLeast(0)
        val raw = ((target - IndexPageSize / 4).coerceAtLeast(0) / IndexPageSize)
        return raw.coerceIn(0, maxAnchor)
    }

    private fun rememberStart(target: Int, loadedCount: Int) {
        placedStart = true
        if (loadedCount <= 0) {
            startInHeld = 0
            lastVisibleIndex = 0
            return
        }
        val index = (target - pageAnchor * IndexPageSize).coerceIn(0, loadedCount - 1)
        startInHeld = index
        lastVisibleIndex = index
    }

    private fun armKeep() {
        val noted = notedTop
        notedTop = null
        if (account.startAfterChange != StartAfterChange.KeepTopVisible) {
            honourKeep = false
            keepSnapshot = null
            return
        }
        honourKeep = true
        keepSnapshot = noted ?: heldRows.getOrNull(lastVisibleIndex)?.uid
    }

    private fun hasEsearch(): Boolean = session.featureCaps.esearch

    private fun usesArrivalSequences(): Boolean =
        view.key == SortKey.Arrival && filterUids == null && activeSearch == null && arrivalTotal > 0

    private suspend fun resolveTarget(count: Int, preserveAnchor: Int?): Int? {
        if (preserveAnchor != null) {
            honourKeep = false
            forceNewest = false
            keepSnapshot = null
            return null
        }
        if (forceNewest) {
            forceNewest = false
            honourKeep = false
            keepSnapshot = null
            if (count <= 0) return 0
            return newestDisplayIndex(count)
        }
        if (honourKeep) {
            honourKeep = false
            val uid = keepSnapshot
            keepSnapshot = null
            if (uid != null && count > 0) {
                val found = findKeptIndex(uid, count)
                if (found != null) return found
            }
        }
        if (count <= 0) return 0
        return locateStart(count)
    }

    private suspend fun locateStart(count: Int): Int {
        val found = locateRule(startRuleFor(mailbox, account), count)
        return found ?: newestDisplayIndex(count)
    }

    private suspend fun locateRule(rule: StartRule, count: Int): Int? {
        if (rule == StartRule.Newest) return newestDisplayIndex(count)
        if (rule == StartRule.First || rule == StartRule.Last) {
            if (usesArrivalSequences() && hasEsearch()) return arrivalSearchedIndex(rule, count)
            return if (rule == StartRule.First) 0 else (count - 1).coerceAtLeast(0)
        }
        return flagIndex(rule, count)
    }

    private fun preferMax(rule: StartRule): Boolean {
        val bottom = rule == StartRule.Last
        return if (bottom) !view.newestFirst else view.newestFirst
    }

    private fun newestDisplayIndex(count: Int): Int {
        if (count <= 0) return 0
        if (usesArrivalSequences()) return if (view.newestFirst) 0 else count - 1
        if (threading) {
            var best = 0
            var bestUid = Long.MIN_VALUE
            for ((index, part) in threadPlan.withIndex()) {
                var top = part.rootUid
                for (uid in part.hiddenUids) if (uid > top) top = uid
                if (top > bestUid) {
                    bestUid = top
                    best = index
                }
            }
            return best
        }
        var best = 0
        var bestUid = Long.MIN_VALUE
        for ((index, uid) in order.withIndex()) {
            if (uid > bestUid) {
                bestUid = uid
                best = index
            }
        }
        return best.coerceIn(0, (count - 1).coerceAtLeast(0))
    }

    private suspend fun arrivalSearchedIndex(rule: StartRule, count: Int): Int? {
        val max = preferMax(rule)
        val edge = if (hasEsearch()) {
            if (max) SearchEdge.Max else SearchEdge.Min
        } else {
            SearchEdge.All
        }
        val matches = try {
            session.searchStart(rule, false, edge)
        } catch (failure: MailFailure) {
            if (pendingNotice == null) pendingNotice = failure.text
            return null
        }
        if (matches.isEmpty()) return null
        val sequence = (if (max) matches.maxOrNull() else matches.minOrNull()) ?: return null
        val seq = sequence.toInt()
        if (seq < 1 || seq > count) return null
        val index = if (view.newestFirst) count - seq else seq - 1
        if (index < 0 || index >= count) return null
        return index
    }

    private suspend fun flagIndex(rule: StartRule, count: Int): Int? {
        if (usesArrivalSequences()) return arrivalSearchedIndex(rule, count)
        val matches = try {
            session.searchStart(rule, true, SearchEdge.All)
        } catch (failure: MailFailure) {
            if (pendingNotice == null) pendingNotice = failure.text
            return null
        }
        if (matches.isEmpty()) return null
        val found = matches.toSet()
        if (threading) {
            for ((index, part) in threadPlan.withIndex()) {
                if (part.rootUid in found || part.hiddenUids.any { it in found }) return index
            }
            return null
        }
        val index = order.indexOfFirst { it in found }
        if (index < 0 || index >= count) return null
        return index
    }

    private suspend fun findKeptIndex(uid: Long, count: Int): Int? {
        if (usesArrivalSequences()) {
            val sequences = try {
                session.locateUid(uid)
            } catch (failure: MailFailure) {
                if (pendingNotice == null) pendingNotice = failure.text
                return null
            }
            val sequence = sequences.minOrNull()?.toInt() ?: return null
            if (sequence < 1 || sequence > count) return null
            val index = if (view.newestFirst) count - sequence else sequence - 1
            if (index < 0 || index >= count) return null
            return index
        }
        if (threading) {
            for ((index, part) in threadPlan.withIndex()) {
                if (part.rootUid == uid || uid in part.hiddenUids) return index
            }
            return null
        }
        val index = order.indexOf(uid)
        if (index < 0 || index >= count) return null
        return index
    }

    private fun arrivalSpan(exists: Int, anchor: Int, fromNewestEnd: Boolean): IntRange? {
        if (exists <= 0 || anchor < 0) return null
        if (fromNewestEnd) {
            val last = exists - anchor * IndexPageSize
            if (last < 1) return null
            val olderLast = exists - (anchor + 1) * IndexPageSize
            val first = if (olderLast < 1) {
                1
            } else {
                (exists - (anchor + 2) * IndexPageSize + 1).coerceAtLeast(1)
            }
            return first..last
        }
        val first = anchor * IndexPageSize + 1
        if (first > exists) return null
        val last = exists.coerceAtMost((anchor + 2) * IndexPageSize)
        return first..last
    }

    private suspend fun fetchSorted(key: SortKey, newestFirst: Boolean, preserveAnchor: Int? = null): List<IndexRow> {
        clearThreads()
        val uids = if (!session.featureCaps.sort && slowerClientSort(account)) {
            session.select(mailbox)
            requireClientFallbackRoom()
            restrict(session.clientOrder(key, newestFirst))
        } else {
            restrict(session.sort(key, newestFirst))
        }
        order = uids
        arrivalTotal = 0
        pendingNew = 0
        val target = resolveTarget(uids.size, preserveAnchor)
        pageAnchor = clampedAnchor(uids.size, preserveAnchor, target ?: 0)
        val loaded = pagesOf(uids)
        if (target != null) rememberStart(target, loaded.size)
        return loaded
    }

    private suspend fun fetchThread(key: SortKey, newestFirst: Boolean, preserveAnchor: Int? = null): List<IndexRow> {
        val serverThread = when (key) {
            SortKey.ThreadReferences -> session.featureCaps.threadReferences
            SortKey.ThreadOrderedSubject -> session.featureCaps.threadOrderedSubject
            else -> true
        }
        val node = if (!serverThread && slowerClientThread(account)) {
            session.select(mailbox)
            requireClientFallbackRoom()
            session.clientThread(key)
        } else {
            session.thread(key)
        }
        val collapsed = collapsedThreads(node, newestFirst)
        val kept = restrictThreads(collapsed)
        threading = true
        threadPlan = kept
        threadMembers = emptyMap()
        order = kept.map { it.rootUid }
        arrivalTotal = 0
        pendingNew = 0
        val target = resolveTarget(order.size, preserveAnchor)
        pageAnchor = clampedAnchor(order.size, preserveAnchor, target ?: 0)
        val loaded = loadThreadPage()
        if (target != null) rememberStart(target, loaded.size)
        return loaded
    }

    private suspend fun requireClientFallbackRoom() {
        if (session.selectedExists() > ClientFallbackWarn && !allowLargeClientFallback) {
            throw MailFailure("folder is large")
        }
    }

    private fun restrict(uids: List<Long>): List<Long> {
        val keep = filterUids ?: return uids
        return uids.filter { it in keep }
    }

    private suspend fun fetchSearch(query: String, preserveAnchor: Int? = null): List<IndexRow> {
        clearThreads()
        val advanced = activeAdvanced
        if (advanced != null && activeScope != SearchScope.Current) {
            return fetchScopedAdvanced(advanced, preserveAnchor)
        }
        orderMailboxes = emptyList()
        val found = if (advanced != null) {
            session.searchCriterion("Advanced", advanced)
        } else {
            session.searchCriterion(activeSearchField.name, query)
        }
        val uids = if (view.newestFirst) found.sortedDescending() else found.sorted()
        order = uids
        arrivalTotal = 0
        pendingNew = 0
        val target = resolveTarget(uids.size, preserveAnchor)
        pageAnchor = clampedAnchor(uids.size, preserveAnchor, target ?: 0)
        val loaded = pagesOf(uids)
        if (target != null) rememberStart(target, loaded.size)
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

    private suspend fun fetchByUid(
        uids: List<Long>,
        preview: Boolean = includePreview,
        folder: String = mailbox,
    ): List<IndexRow> {
        return session.fetchIndex(
            IndexRequest(
                mailbox = folder,
                mode = IndexMode.ByUid,
                uids = uids,
                limit = IndexPageSize,
                prefetch = IndexPageSize,
                includePreview = preview,
            ),
        )
    }

    private fun clearSearchScope() {
        activeScope = SearchScope.Current
        orderMailboxes = emptyList()
    }

    private suspend fun reselectHome() {
        try {
            session.select(mailbox)
        } catch (error: CancellationException) {
            throw error
        } catch (_: MailFailure) {
        }
    }

    private suspend fun fetchScopedAdvanced(text: String, preserveAnchor: Int?): List<IndexRow> {
        val cancelled = scopeCancel
        val progress = scopeProgress
        scopeCancel = { false }
        scopeProgress = { _, _ -> }
        if (usesFastScope(session, activeScope)) {
            if (cancelled()) {
                order = emptyList()
                orderMailboxes = emptyList()
                return emptyList()
            }
            progress(1, 1)
            val groups = session.searchScope(activeScope.name, mailbox, "Advanced", text)
            val hits = ArrayList<Long>()
            val hitBoxes = ArrayList<String>()
            for (group in groups) {
                val ordered = if (view.newestFirst) group.uids.sortedDescending() else group.uids.sorted()
                for (uid in ordered) {
                    hits.add(uid)
                    hitBoxes.add(group.mailbox)
                }
            }
            order = hits
            orderMailboxes = hitBoxes
            arrivalTotal = 0
            pendingNew = 0
            val target = resolveTarget(hits.size, preserveAnchor)
            pageAnchor = clampedAnchor(hits.size, preserveAnchor, target ?: 0)
            val loaded = pagesOfMailboxes(hits, hitBoxes)
            if (target != null) rememberStart(target, loaded.size)
            return loaded
        }
        val boxes = mailboxesFor(activeScope, mailbox, session)
        val hits = ArrayList<Long>()
        val hitBoxes = ArrayList<String>()
        try {
            for ((index, box) in boxes.withIndex()) {
                if (cancelled()) break
                progress(index + 1, boxes.size)
                val opened = try {
                    session.select(box)
                    true
                } catch (error: CancellationException) {
                    throw error
                } catch (_: MailFailure) {
                    false
                }
                if (!opened) continue
                val found = session.searchCriterion("Advanced", text)
                val ordered = if (view.newestFirst) found.sortedDescending() else found.sorted()
                for (uid in ordered) {
                    hits.add(uid)
                    hitBoxes.add(box)
                }
            }
        } finally {
            reselectHome()
        }
        order = hits
        orderMailboxes = hitBoxes
        arrivalTotal = 0
        pendingNew = 0
        val target = resolveTarget(hits.size, preserveAnchor)
        pageAnchor = clampedAnchor(hits.size, preserveAnchor, target ?: 0)
        val loaded = pagesOfMailboxes(hits, hitBoxes)
        if (target != null) rememberStart(target, loaded.size)
        return loaded
    }

    private suspend fun pagesOfMailboxes(uids: List<Long>, mailboxes: List<String>): List<IndexRow> {
        val start = pageAnchor * IndexPageSize
        if (start >= uids.size) return emptyList()
        val end = minOf(uids.size, start + IndexPageSize * 2)
        val loaded = ArrayList<IndexRow>()
        try {
            var index = start
            while (index < end) {
                val box = mailboxes[index]
                var next = index + 1
                while (next < end && mailboxes[next] == box) next += 1
                val slice = uids.subList(index, next)
                val opened = try {
                    session.select(box)
                    true
                } catch (error: CancellationException) {
                    throw error
                } catch (_: MailFailure) {
                    false
                }
                if (opened) {
                    val fetched = align(slice, fetchByUid(slice, folder = box))
                    for (row in fetched) loaded.add(row.copy(mailbox = box))
                }
                index = next
            }
        } finally {
            reselectHome()
        }
        return loaded
    }

    private suspend fun rememberExists() {
        val exists = session.selectedExists()
        if (exists > 0) knownExists = exists
    }

    private suspend fun addNewTail(growth: Int) {
        if (growth <= 0) return
        val fetched = session.fetchIndex(
            IndexRequest(
                mailbox = mailbox,
                mode = IndexMode.ArrivalNewest,
                limit = growth,
                prefetch = 0,
                includePreview = includePreview,
            ),
        )
        val known = HashSet<Long>(order.size + threadMembers.size)
        known.addAll(order)
        known.addAll(threadMembers.keys)
        val fresh = fetched.filter { it.uid !in known }
        if (fresh.isEmpty()) return
        val placed = if (view.newestFirst) {
            fresh.sortedByDescending { it.sequence }
        } else {
            fresh.sortedBy { it.sequence }
        }
        if (view.newestFirst) {
            order = placed.map { it.uid } + order
            heldRows = placed + heldRows
        } else {
            order = order + placed.map { it.uid }
            heldRows = heldRows + placed
        }
    }

    private fun refreshSummaryUnread() {
        if (summaries.isEmpty()) return
        val hiddenByRoot = threadHidden
        val byUid = threadMembers
        summaries = summaries.mapValues { (root, summary) ->
            val hiddenUids = hiddenByRoot[root].orEmpty()
            var unread = 0
            for (uid in hiddenUids) {
                val row = byUid[uid]
                if (row == null || "\\Seen" !in row.flags) unread += 1
            }
            summary.copy(unread = unread)
        }
    }

    private fun clearThreads() {
        threading = false
        threadPlan = emptyList()
        summaries = emptyMap()
        threadMembers = emptyMap()
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
        if (hiddenRows.isNotEmpty()) {
            val merged = LinkedHashMap(threadMembers)
            for (row in hiddenRows) merged[row.uid] = row
            threadMembers = merged
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
}
