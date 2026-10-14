package org.dlang.liveimap.ui.index

import org.dlang.liveimap.session.connectionLimits
import org.dlang.liveimap.session.SelectResult
import org.dlang.liveimap.session.usableTrashName
import org.dlang.liveimap.session.MessageRef
import org.dlang.liveimap.session.onRefs
import org.dlang.liveimap.session.sameMailbox
import org.dlang.liveimap.session.FolderAt
import org.dlang.liveimap.session.EpochChanged
import org.dlang.liveimap.session.at
import org.dlang.liveimap.session.copyFrom
import org.dlang.liveimap.session.folderInfo

import kotlinx.coroutines.CancellationException
import org.dlang.liveimap.session.ComposeKind
import org.dlang.liveimap.session.UidWindow
import org.dlang.liveimap.session.ComposeSeed
import org.dlang.liveimap.session.FolderEntry
import org.dlang.liveimap.session.IndexMode
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.engine.LabTestHooks
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.LongSearch
import org.dlang.liveimap.session.LongSearchLink
import org.dlang.liveimap.session.LongSearchResult
import org.dlang.liveimap.session.SearchProgress
import org.dlang.liveimap.session.isLongSearch
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.NamespaceKind
import org.dlang.liveimap.session.SearchEdge
import org.dlang.liveimap.session.ThreadNode
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.DeletePolicy
import org.dlang.liveimap.settings.overAskThreshold
import org.dlang.liveimap.settings.Density
import org.dlang.liveimap.settings.PreviewChoice
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
    val fuzzy: Boolean = false,
)

fun encodeAdvancedQuery(
    combiner: AdvancedCombiner,
    steps: List<AdvancedStep>,
    fuzzy: Boolean = false,
): String? {
    val lines = ArrayList<String>(steps.size + 1)
    val head = when {
        fuzzy && combiner == AdvancedCombiner.And -> "And Fuzzy"
        fuzzy && combiner == AdvancedCombiner.Or -> "Or Fuzzy"
        else -> combiner.name
    }
    lines.add(head)
    for (step in steps) {
        if (step.kind.contains('\t') || step.kind.contains('\n')) return null
        if (step.argument.contains('\t') || step.argument.contains('\n')) return null
        // F118: a bare CR (or NUL) is never a valid search argument; the native side refuses it as well
        if (step.kind.contains('\r') || step.argument.contains('\r') || step.argument.contains('\u0000')) return null
        val mark = if (step.negated) "Not" else "Yes"
        lines.add(mark + "\t" + step.kind + "\t" + step.argument)
    }
    return lines.joinToString("\n")
}

fun parseAdvancedQuery(text: String): AdvancedQuery? {
    val lines = text.split('\n')
    if (lines.isEmpty()) return null
    val combiner: AdvancedCombiner
    val fuzzy: Boolean
    when (lines[0]) {
        "And" -> {
            combiner = AdvancedCombiner.And
            fuzzy = false
        }
        "Or" -> {
            combiner = AdvancedCombiner.Or
            fuzzy = false
        }
        "And Fuzzy" -> {
            combiner = AdvancedCombiner.And
            fuzzy = true
        }
        "Or Fuzzy" -> {
            combiner = AdvancedCombiner.Or
            fuzzy = true
        }
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
    return AdvancedQuery(combiner, steps, fuzzy)
}

fun advancedHasBody(text: String): Boolean {
    val parsed = parseAdvancedQuery(text) ?: return false
    return parsed.steps.any { step -> step.kind == "Body" || step.kind == "Text" }
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
        return SearchCount(session.inFolder(FolderAt(home)) { _, s -> s.searchCount("Advanced", text) }, emptyList())
    }
    if (usesFastScope(session, scope)) {
        if (cancelled()) return SearchCount(0, emptyList())
        onProgress(1, 1)
        return SearchCount(session.searchScopeCount(scope.name, home, "Advanced", text), emptyList())
    }
    val boxes = mailboxesFor(scope, home, session)
    var total = 0
    val skipped = ArrayList<String>()
    for ((index, box) in boxes.withIndex()) {
        if (cancelled()) break
        onProgress(index + 1, boxes.size)
        // One call per folder: the session selects it and counts in it (nothing to restore afterwards).
        total += try {
            session.inFolder(FolderAt(box)) { _, s -> s.searchCount("Advanced", text) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: MailFailure) {
            skipped.add(box)
            continue
        }
    }
    return SearchCount(total, skipped)
}

data class AppliedFilter(
    val label: String,
    val argument: String,
    /** Search kind (criterionKey in liveimap_jni.cpp, or "Advanced" for an encoded AdvancedQuery). */
    val kind: String = "",
)

/**
 * The whole filter chain as one search: (kind, argument) for MailSession.searchCriterion, ANDing every filter.
 * An And advanced filter contributes its steps; an Or one goes in as a nested "Group". Null for an empty chain.
 */
fun combinedFilterSearch(filters: List<AppliedFilter>): Pair<String, String>? {
    if (filters.isEmpty()) return null
    if (filters.size == 1) return filters[0].kind to filters[0].argument
    val steps = ArrayList<AdvancedStep>()
    var fuzzy = false
    for (f in filters) {
        if (f.kind != "Advanced") {
            steps.add(AdvancedStep(false, f.kind, f.argument))
            continue
        }
        val q = parseAdvancedQuery(f.argument) ?: continue
        fuzzy = fuzzy || q.fuzzy
        if (q.combiner == AdvancedCombiner.And) steps.addAll(q.steps)
        else steps.add(AdvancedStep(false, "Group", groupArgument(q)))
    }
    return "Advanced" to (encodeAdvancedQuery(AdvancedCombiner.And, steps, fuzzy) ?: return null)
}

/** The filter chain as one And query for SORT/THREAD criteria (encodeAdvancedQuery text). Null for an empty chain. */
fun filterChainGroup(filters: List<AppliedFilter>): String? {
    if (filters.isEmpty()) return null
    val steps = ArrayList<AdvancedStep>()
    for (f in filters) {
        if (f.kind != "Advanced") {
            steps.add(AdvancedStep(false, f.kind, f.argument))
            continue
        }
        val q = parseAdvancedQuery(f.argument) ?: continue
        if (q.combiner == AdvancedCombiner.And) steps.addAll(q.steps)
        else steps.add(AdvancedStep(false, "Group", groupArgument(q)))
    }
    return encodeAdvancedQuery(AdvancedCombiner.And, steps)
}

/** A nested query as one step argument: lines split by RS (0x1e), fields by US (0x1f); parsed by groupKey (JNI). */
fun groupArgument(q: AdvancedQuery): String =
    (listOf(q.combiner.name) + q.steps.map { (if (it.negated) "Not" else "Yes") + "\u001f" + it.kind + "\u001f" + it.argument })
        .joinToString("\u001e")

/** A chip's text: the label, plus the argument for text criteria (a UID set is never shown). */
fun filterChipText(filter: AppliedFilter): String =
    if (filter.argument.isEmpty() || filter.kind == "Uid") filter.label else "${filter.label} ${filter.argument}"

/** "Show this thread" chip: the thread's subject (without Re:/Fwd: prefixes), or just the label. */
fun threadChipLabel(label: String, subject: String?): String {
    var s = subject?.trim().orEmpty()
    while (true) {
        val m = Regex("^(?i)(re|fwd?|aw|sv|wg)(\\[\\d+])?:\\s*").find(s) ?: break
        s = s.substring(m.range.last + 1)
    }
    return if (s.isEmpty()) label else "$label: $s"
}

/** UIDs as an IMAP set, runs collapsed: 1,3:5,9. */
fun uidSetText(uids: Collection<Long>): String {
    val sorted = uids.toSortedSet().toList()
    val out = StringBuilder()
    var i = 0
    while (i < sorted.size) {
        var j = i
        while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
        if (out.isNotEmpty()) out.append(',')
        out.append(sorted[i])
        if (j > i) out.append(':').append(sorted[j])
        i = j + 1
    }
    return out.toString()
}

/** [uid] and the messages below it in its thread (preorder entries deeper than it, up to the next sibling). */
fun threadSubtree(plan: List<CollapsedThread>, uid: Long): List<Long> {
    val thread = plan.firstOrNull { it.rootUid == uid || uid in it.hiddenUids } ?: return listOf(uid)
    val all = listOf(thread.rootUid) + thread.hiddenUids
    val depth = { u: Long -> if (u == thread.rootUid) 0 else thread.depthByUid[u] ?: 1 }
    val at = all.indexOf(uid)
    val base = depth(uid)
    val out = arrayListOf(uid)
    for (k in at + 1 until all.size) {
        if (depth(all[k]) <= base) break
        out.add(all[k])
    }
    return out
}

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

/** Preview lines for a row: the preview setting, else Density; Auto uses the planned size for this window. */
fun previewLines(account: AccountSettings, planned: org.dlang.liveimap.settings.PreviewSize): Int = when (account.previewSize) {
    PreviewChoice.Density -> previewLineCount(account.density)
    PreviewChoice.None -> 0
    PreviewChoice.Small -> 2
    PreviewChoice.Large -> 5
    PreviewChoice.Auto -> when (planned) {
        org.dlang.liveimap.settings.PreviewSize.None -> 0
        org.dlang.liveimap.settings.PreviewSize.Small -> 2
        org.dlang.liveimap.settings.PreviewSize.Large -> 5
    }
}

/** Whether index fetches carry preview text (server PREVIEW when offered, else a capped body peek). */
fun wantsPreview(account: AccountSettings): Boolean = when (account.previewSize) {
    PreviewChoice.Density -> account.density != Density.Compact
    PreviewChoice.None -> false
    else -> true
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
    trashNamedByUser: Boolean,
): DeletePolicy {
    // F90: a folder is "Trash" for permanent deletion only when the user named it; one the server merely flags
    // \Trash gets the user's own policy (never an escalation to an undo-less UID EXPUNGE).
    if (inTrash && !trashNamedByUser) {
        return if (policy == DeletePolicy.DeletePermanently && uidPlus) policy else DeletePolicy.MarkDeleted
    }
    if (inTrash) return if (uidPlus) DeletePolicy.DeletePermanently else DeletePolicy.MarkDeleted
    if (policy == DeletePolicy.DeletePermanently && !uidPlus) return DeletePolicy.MarkDeleted
    if (policy == DeletePolicy.MoveToTrash && !trashKnown) return DeletePolicy.MarkDeleted
    return policy
}

/**
 * [policy] is what the home folder was going to do when the user confirmed. A Trash hit made permanent does not
 * change the home folder. [permanentOnlyInTrash] is that confirm; other folders keep [effectiveDeletePolicy].
 */
fun policyForListedDelete(
    policy: DeletePolicy,
    folder: String,
    home: String,
    trash: String,
    uidPlus: Boolean,
    trashNamedByUser: Boolean,
    permanentOnlyInTrash: Boolean,
): DeletePolicy {
    val inTrash = trash.isNotEmpty() && sameMailbox(folder, trash)
    if (sameMailbox(folder, home)) return policy
    if (permanentOnlyInTrash && inTrash) {
        return if (uidPlus) DeletePolicy.DeletePermanently else DeletePolicy.MarkDeleted
    }
    return effectiveDeletePolicy(policy, inTrash, uidPlus, trash.isNotEmpty(), trashNamedByUser)
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
    val targetMailbox: String = "",
    val usedMove: Boolean = false,
    /** F105: the changed messages with folder and UIDVALIDITY; [IndexModel.undo] acts on exactly these. */
    val source: List<MessageRef> = emptyList(),
    /** Their copies in [targetMailbox] (from COPYUID), same epoch rules. */
    val dest: List<MessageRef> = emptyList(),
) {
    val uids: List<Long> get() = source.map { it.uid }
    val destUids: List<Long> get() = dest.map { it.uid }
    val allMailbox: Boolean get() = false
}

/** The account scope of a [MessageRef] made for [settings]. */
fun refAccountKey(settings: AccountSettings): String = "${settings.username}@${settings.imapHost}"

/**
 * F105: a reader action's undo, as refs: the message in [folder] (UIDVALIDITY [epoch]) and its copy in [target]
 * ([targetEpoch] from COPYUID). Undo refuses when the copy is unknown (F102).
 */
fun refUndo(
    settings: AccountSettings,
    folder: String,
    epoch: Long,
    uid: Long,
    target: String = "",
    dest: List<Long> = emptyList(),
    targetEpoch: Long = 0L,
    usedMove: Boolean = false,
): MailUndo {
    val key = refAccountKey(settings)
    return MailUndo(
        delete = target.isEmpty(),
        targetMailbox = target,
        usedMove = usedMove,
        source = listOf(MessageRef(key, folder, epoch, uid)),
        dest = if (targetEpoch > 0) dest.map { MessageRef(key, target, targetEpoch, it) } else emptyList(),
    )
}

fun mailUndoText(undo: MailUndo): String {
    if (undo.delete) return "Marked deleted"
    return "Moved to ${mailboxLeaf(undo.targetMailbox)}"
}

// Thread trees can be thousands of levels deep (one level per reply), so every walk here uses an explicit stack.

/** Largest uid in each subtree, computed once, post-order, without recursion. */
private fun subtreeMaxUids(root: ThreadNode): java.util.IdentityHashMap<ThreadNode, Long?> {
    val out = java.util.IdentityHashMap<ThreadNode, Long?>()
    val stack = ArrayDeque<Pair<ThreadNode, Boolean>>()
    stack.addLast(root to false)
    while (stack.isNotEmpty()) {
        val (n, done) = stack.removeLast()
        if (!done) {
            stack.addLast(n to true)
            for (c in n.children) stack.addLast(c to false)
        } else {
            var best = n.uid
            for (c in n.children) {
                val m = out[c] ?: continue
                if (best == null || m > best) best = m
            }
            out[n] = best
        }
    }
    return out
}

private fun byNewest(maxOf: Map<ThreadNode, Long?>, newestFirst: Boolean) = Comparator<ThreadNode> { left, right ->
    val leftKey = maxOf[left]
    val rightKey = maxOf[right]
    when {
        leftKey == null && rightKey == null -> 0
        leftKey == null -> 1
        rightKey == null -> -1
        newestFirst -> rightKey.compareTo(leftKey)
        else -> leftKey.compareTo(rightKey)
    }
}

fun orderedThreadUids(node: ThreadNode, newestFirst: Boolean): List<Long> {
    val maxOf = subtreeMaxUids(node)
    val children = if (node.children.isEmpty()) node.children else node.children.sortedWith(byNewest(maxOf, newestFirst))
    val out = ArrayList<Long>()
    node.copy(children = children).forEachPreorder { n -> n.uid?.let { out.add(it) } }
    return out
}

private fun threadDepths(root: ThreadNode): Map<Long, Int> {
    val out = LinkedHashMap<Long, Int>()
    var seenRoot = false
    val stack = ArrayDeque<Pair<ThreadNode, Int>>()
    stack.addLast(root to 0)
    while (stack.isNotEmpty()) {
        val (current, depth) = stack.removeLast()
        val uid = current.uid
        val next = if (uid != null) {
            if (seenRoot) out[uid] = depth else seenRoot = true
            depth + 1
        } else {
            depth
        }
        for (i in current.children.indices.reversed()) stack.addLast(current.children[i] to next)
    }
    return out
}

private fun preorderUids(current: ThreadNode): List<Long> {
    val out = ArrayList<Long>()
    current.forEachPreorder { n -> n.uid?.let { out.add(it) } }
    return out
}

fun collapsedThreads(node: ThreadNode, newestFirst: Boolean): List<CollapsedThread> {
    val flat = node.children.mapNotNull { threadWithoutPlaceholderRoot(it) }
    val maxOf = java.util.IdentityHashMap<ThreadNode, Long?>()
    for (c in flat) maxOf.putAll(subtreeMaxUids(c))
    val children = flat.sortedWith(byNewest(maxOf, newestFirst))
    val out = ArrayList<CollapsedThread>()
    for (child in children) {
        val uids = preorderUids(child)
        if (uids.isEmpty()) continue
        out.add(CollapsedThread(uids.first(), uids.drop(1), threadDepths(child)))
    }
    return out
}

/**
 * One top-level THREAD entry as one thread. A placeholder root (RFC 5256 dummy parent: the replies' parent is not in
 * the folder) keeps its children together, as Pine and mutt do: the first child becomes the root and the other
 * children its replies. Placeholders below the root are replaced by their children.
 */
private fun threadWithoutPlaceholderRoot(top: ThreadNode): ThreadNode? {
    if (top.uid != null) return flattenNullParents(listOf(top)).single()
    val kids = flattenNullParents(top.children)
    if (kids.isEmpty()) return null
    return ThreadNode(kids[0].uid, kids[0].children + kids.drop(1))
}

/** Replaces every placeholder (null-uid) node by its children, at every level, iteratively (post-order rebuild). */
private fun flattenNullParents(nodes: List<ThreadNode>): List<ThreadNode> {
    val holder = ThreadNode(null, nodes)
    val built = java.util.IdentityHashMap<ThreadNode, List<ThreadNode>>()  // node -> its flattened children
    val stack = ArrayDeque<Pair<ThreadNode, Boolean>>()
    stack.addLast(holder to false)
    while (stack.isNotEmpty()) {
        val (n, done) = stack.removeLast()
        if (!done) {
            stack.addLast(n to true)
            for (c in n.children) stack.addLast(c to false)
            continue
        }
        val kids = ArrayList<ThreadNode>()
        for (c in n.children) {
            val flatKids = built.remove(c)!!
            if (c.uid == null) kids.addAll(flatKids) else kids.add(ThreadNode(c.uid, flatKids))
        }
        built[n] = kids
    }
    return built.remove(holder)!!
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

fun followingUndeleted(uids: List<Long>, deleted: Set<Long>, current: Long): Long? {
    val index = uids.indexOf(current)
    if (index < 0) return null
    for (later in index + 1 until uids.size) {
        if (uids[later] !in deleted) return uids[later]
    }
    return null
}

fun newestAtEnd(view: FolderView): Boolean = !view.newestFirst

internal object OpenMessageOrder {
    var mailbox: String = ""
        private set
    /** F93: the order belongs to one account and one UIDVALIDITY epoch, not just a mailbox name. */
    var accountId: String = ""
        private set
    var uidValidity: Long = 0L
        private set

    fun matches(accountId: String, mailbox: String, uidValidity: Long): Boolean =
        this.mailbox == mailbox && this.accountId == accountId && accountId.isNotEmpty() &&
            this.uidValidity != 0L && this.uidValidity == uidValidity
    var uids: List<Long> = emptyList()
        private set
    private var sequences = emptyMap<Long, Int>()
    private var deleted = emptySet<Long>()

    fun publish(accountId: String, uidValidity: Long, mailbox: String, uids: List<Long>, rows: List<IndexRow>) {
        this.accountId = accountId
        this.uidValidity = uidValidity
        this.mailbox = mailbox
        this.uids = uids.toList()
        sequences = rows.associate { it.uid to it.sequence }
        deleted = rows.filter { "\\Deleted" in it.flags }.map { it.uid }.toSet()
    }

    fun sequence(uid: Long): Int = sequences[uid] ?: 0

    fun followingAfterDelete(current: Long): Long? = followingUndeleted(uids, deleted, current)

    fun clear() {
        mailbox = ""
        accountId = ""
        uidValidity = 0L
        uids = emptyList()
        sequences = emptyMap()
        deleted = emptySet()
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

    /** Set when a cross-folder page could not fetch one folder. Callers report it instead of dropping the folder. */
    private var pageFailures: String? = null

    /** Opens a side connection for a long search; set by the screen (null in tests: main session). */
    var longSearchLinks: ((AccountSettings, String) -> LongSearchLink)? = null

    /** Progress of a long search (null when none runs); called from a background thread. */
    var onSearchProgress: ((SearchProgress?) -> Unit)? = null

    @Volatile private var runningSearch: LongSearch? = null
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
        get() = appliedFilters.size > 1

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
        OpenMessageOrder.publish(orderAccountId, epochs[mailbox] ?: 0L, mailbox, messageOrder(openExpanded), byUid.values.toList())
    }

    var account: AccountSettings = AccountSettings()
        private set

    fun adoptAccount(next: AccountSettings) {
        account = next
    }

    var allowLargeClientFallback: Boolean = false

    suspend fun setFolderStart(rule: StartRule?) {
        val saved = store.update { withFolderStart(it, mailbox, rule) }
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

    /** Jump to number: the row numbered [position] (1-based, in the current sort/thread/filter order). */
    suspend fun jumpToPosition(position: Int): Boolean {
        val count = if (usesArrivalSequences()) arrivalTotal else order.size
        if (position !in 1..count) return false
        val target = position - 1
        replaceWindow {
            pageAnchor = clampedAnchor(count, null, target)
            val loaded = when {
                usesArrivalSequences() -> fetchArrivalWindow()
                threading -> loadThreadPage()
                orderMailboxes.isNotEmpty() -> pagesOfMailboxes(order, orderMailboxes)
                else -> pagesOf(order)
            }
            rememberStart(target, loaded.size)
            loaded
        }
        return !windowFailed
    }

    suspend fun jumpToSequence(sequence: Int): Boolean {
        if (usesArrivalSequences()) {
            val total = arrivalTotal
            if (sequence !in 1..total) return false
            val displayIndex = if (view.newestFirst) total - sequence else sequence - 1
            replaceWindow {
                pageAnchor = clampedAnchor(total, null, displayIndex)
                val loaded = fetchArrivalWindow()
                rememberStart(displayIndex, loaded.size)
                loaded
            }
            return !windowFailed
        }
        val index = rows.indexOfFirst { it.sequence == sequence }
        if (index < 0) return false
        startInHeld = index
        lastVisibleIndex = index
        return true
    }

    suspend fun nextUnread(): Boolean {
        val anchor = rows.getOrNull(lastVisibleIndex)
        try {
            if (usesArrivalSequences()) {
                val chosen = nextArrivalSequence(anchor?.sequence ?: 0) ?: return false
                return jumpToSequence(chosen)
            }
            val index = if (threading) {
                nextThreadIndex(anchor?.uid)
            } else {
                nextOrderedIndex(anchor?.uid)
            }
            if (index == null) return false
            return placeUnread(index)
        } catch (failure: MailFailure) {
            if (pendingNotice == null) pendingNotice = failure.text
            notice = pendingNotice
            pendingNotice = null
            return false
        }
    }

    private suspend fun nextArrivalSequence(anchorSequence: Int): Int? {
        val matches = home.searchStart(StartRule.FirstUnseen, false, SearchEdge.All)
        if (matches.isEmpty()) return null
        if (anchorSequence == 0) {
            val chosen = if (view.newestFirst) matches.maxOrNull() else matches.minOrNull()
            return chosen?.toInt()
        }
        val anchorId = anchorSequence.toLong()
        val chosen = if (view.newestFirst) {
            matches.filter { it < anchorId }.maxOrNull()
        } else {
            matches.filter { it > anchorId }.minOrNull()
        }
        return chosen?.toInt()
    }

    private suspend fun nextOrderedIndex(anchorUid: Long?): Int? {
        val found = home.searchStart(StartRule.FirstUnseen, true, SearchEdge.All).toSet()
        if (found.isEmpty()) return null
        val start = if (anchorUid == null) {
            0
        } else {
            val at = order.indexOf(anchorUid)
            if (at < 0) 0 else at + 1
        }
        for (index in start until order.size) {
            if (order[index] in found) return index
        }
        return null
    }

    private suspend fun nextThreadIndex(anchorUid: Long?): Int? {
        val found = home.searchStart(StartRule.FirstUnseen, true, SearchEdge.All).toSet()
        if (found.isEmpty()) return null
        val anchorThread = if (anchorUid == null) {
            -1
        } else {
            threadPlan.indexOfFirst { part ->
                part.rootUid == anchorUid || anchorUid in part.hiddenUids
            }
        }
        val start = if (anchorThread < 0) 0 else anchorThread + 1
        for (index in start until threadPlan.size) {
            val part = threadPlan[index]
            if (part.rootUid in found || part.hiddenUids.any { it in found }) return index
        }
        return null
    }

    private suspend fun placeUnread(index: Int): Boolean {
        val count = order.size
        if (index < 0 || index >= count) return false
        replaceWindow {
            pageAnchor = clampedAnchor(count, null, index)
            val loaded = when {
                threading -> loadThreadPage()
                orderMailboxes.isNotEmpty() -> pagesOfMailboxes(order, orderMailboxes)
                else -> pagesOf(order)
            }
            rememberStart(index, loaded.size)
            loaded
        }
        return !windowFailed
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
        appliedFilters.clear()
        includePreview = wantsPreview(loaded)
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
        includePreview = wantsPreview(account)
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
        appliedFilters.clear()
        account = store.load()
        includePreview = wantsPreview(account)
        activeAdvanced = text
        activeSearch = text
        activeScope = scope
        scopeCancel = cancelled
        scopeProgress = onProgress
        armKeep()
        return replaceWindow { fetchSearch(text) }
    }

    /**
     * Runs the filter chain as ONE server search carrying every criterion (never intersects result sets on the
     * client). Null with [notice] set when the search failed.
     */
    private suspend fun searchFilters(filters: List<AppliedFilter>): Set<Long>? {
        val (kind, argument) = combinedFilterSearch(filters) ?: return emptySet()
        return try {
            home.searchCriterion(kind, argument).toSet()
        } catch (failure: MailFailure) {
            notice = failure.text
            null
        }
    }

    /** Replaces the filter chain with [filters] (empty = show all), re-running the one combined search. */
    private suspend fun setFilters(filters: List<AppliedFilter>): List<IndexRow> {
        val found = if (filters.isEmpty()) null else (searchFilters(filters) ?: return heldRows)
        val savedUids = filterUids
        val savedSearch = activeSearch
        val savedAdvanced = activeAdvanced
        val savedScope = activeScope
        val savedMailboxes = orderMailboxes
        val savedSearchField = activeSearchField
        val savedFilters = appliedFilters.toList()
        val loaded = store.load()
        account = loaded
        includePreview = wantsPreview(loaded)
        activeSearch = null
        activeAdvanced = null
        clearSearchScope()
        filterUids = found
        appliedFilters.clear()
        appliedFilters.addAll(filters)
        armKeep()
        val rows = replaceWindow { fetchCurrent() }
        if (windowFailed) {
            filterUids = savedUids
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

    /** Narrowing adds the criterion to the chain and searches again with all of them; otherwise it starts a chain. */
    suspend fun applyCriterion(kind: String, argument: String, narrow: Boolean, label: String): List<IndexRow> {
        val chip = AppliedFilter(label, argument, kind)
        val chain = if (narrow && filterUids != null) appliedFilters + chip else listOf(chip)
        return setFilters(chain)
    }

    /** Quick filter "show new": narrows the current list to \Recent-and-unseen messages (IMAP NEW). */
    suspend fun showNew(label: String): List<IndexRow> = applyCriterion("New", "", narrow = true, label = label)

    /** Quick filter "show not deleted": narrows the current list to UNDELETED. */
    suspend fun showNotDeleted(label: String): List<IndexRow> =
        applyCriterion("NotDeleted", "", narrow = true, label = label)

    /**
     * "Show this thread": narrows the current list to [uid] and its replies below it in the thread (its subtree),
     * as a UID criterion in the same combined search. Uses the current thread view, or one THREAD REFERENCES.
     */
    suspend fun showThreadOf(uid: Long, label: String): List<IndexRow> {
        val plan = if (threading && threadPlan.isNotEmpty()) {
            threadPlan
        } else {
            try {
                collapsedThreads(home.thread(SortKey.ThreadReferences), newestFirst = false)
            } catch (failure: MailFailure) {
                notice = failure.text
                return heldRows
            }
        }
        val uids = threadSubtree(plan, uid)
        val subject = (heldRows.firstOrNull { it.uid == uid } ?: threadMembers[uid])?.subject
            ?: runCatching { fetchByUid(listOf(uid), preview = false).firstOrNull()?.subject }.getOrNull()
        return applyCriterion("Uid", uidSetText(uids), narrow = true, label = threadChipLabel(label, subject))
    }

    suspend fun showAll(): List<IndexRow> {
        if (filterUids == null && activeSearch == null && appliedFilters.isEmpty()) return heldRows
        return setFilters(emptyList())
    }

    /** Widening drops the last criterion and rebuilds the one search from the rest. */
    suspend fun widenFilter(): List<IndexRow> {
        if (appliedFilters.size < 2) return heldRows
        return setFilters(appliedFilters.dropLast(1))
    }

    suspend fun dropFiltersFrom(index: Int): List<IndexRow> {
        if (index < 0 || index >= appliedFilters.size) return heldRows
        return setFilters(appliedFilters.take(index))
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
            pageFailures = null
            heldRows = when {
                threading -> loadThreadPage()
                arrivalTotal > 0 -> fetchArrivalWindow()
                orderMailboxes.isNotEmpty() -> pagesOfMailboxes(order, orderMailboxes)
                else -> pagesOf(order)
            }
            notice = pageFailures
            pageFailures = null
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
            pageFailures = null
            heldRows = when {
                threading -> loadThreadPage()
                arrivalTotal > 0 -> fetchArrivalWindow()
                orderMailboxes.isNotEmpty() -> pagesOfMailboxes(order, orderMailboxes)
                else -> pagesOf(order)
            }
            notice = pageFailures
            pageFailures = null
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
        val existsNow = maxOf(reportedExists, home.selectedExists())
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
                    if (row.uid == change.uid && inListedFolder(row, mailbox)) row.copy(flags = change.flags) else row
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
                epochs.remove(mailbox)
                allMailboxCeiling = 0L
                mailUndo = null
                replaceWindow { fetchCurrent() }
            }
            MailboxChange.WatchLost,
            MailboxChange.Reconnected,
            -> Unit
        }
        return heldRows
    }

    /**
     * David, 2026-10-12 (F129/F142): every query and UID action names its folder and epoch; the session layer
     * selects or verifies that folder inside the same call that runs the command. Nothing here selects or pins.
     * The folder is the acted-on hit's folder while [actFolder] is set, else this index's folder. Its epoch is the
     * one recorded when its rows were listed (0 for this folder before the first listing: reads only; a hit's
     * folder with no recorded epoch is refused).
     */
    private fun actAt(): FolderAt {
        val folder = actFolder ?: mailbox
        return FolderAt(folder, epochs[folder] ?: if (folder == mailbox) 0L else -1L)
    }

    /** This index's folder (or the acted-on hit's), bound per call. */
    private val home: MailSession get() = session.at(actAt())

    /** F123: the shown folder's message count (never whatever folder the shared session has selected). */
    suspend fun shownExists(): Int = home.selectedExists()

    /** F123: the poll tick (only without IDLE): NOOP and EXISTS of this index's own folder, in one call. */
    suspend fun pollShownExists(): Int = session.inFolder(FolderAt(mailbox)) { _, s ->
        s.noop()
        s.selectedExists()
    }

    /** F123: Folder info for the shown folder. */
    suspend fun shownInfo(): SelectResult = session.folderInfo(FolderAt(mailbox))

    /**
     * F105: UIDVALIDITY of each folder as of when its rows were listed. Every UID action goes through
     * [MessageRef]s built from it, so a UID is only ever used in the folder and epoch it was read from.
     */
    private val epochs = HashMap<String, Long>()
    private val orderAccountId: String
        get() = (store as? org.dlang.liveimap.settings.DataStoreSettingsStore)?.boundAccountId.orEmpty()

    /** Account scope for this model's refs (one model, one account). */
    private val accountKey: String get() = refAccountKey(account)

    private fun epochOf(folder: String): Long = epochs[folder] ?: 0L

    /**
     * F142: the home folder's epoch is recorded only from the select that produced its rows (never from a later
     * query), so a server-side UIDVALIDITY change is caught by the next action instead of masked.
     */
    private fun noteListedEpoch(folder: String, selected: SelectResult) {
        if (selected.uidValidity > 0 && !epochs.containsKey(folder)) epochs[folder] = selected.uidValidity
    }

    /** Rows of a folder (this one or a hit's), fetched in the same call as the select that dates them. */
    private suspend fun fetchListed(request: IndexRequest): List<IndexRow> =
        session.inFolder(FolderAt(request.mailbox, epochs[request.mailbox] ?: 0L)) { selected, s ->
            noteListedEpoch(request.mailbox, selected)
            s.fetchIndex(request)
        }

    /** F105: [folder]'s UIDVALIDITY as recorded when its rows were listed; 0 when not known yet. */
    fun listedEpoch(folder: String): Long = epochs[folder.ifEmpty { mailbox }] ?: 0L

    /** A folder's UIDVALIDITY as seen when its rows were listed (search paths; tests). */
    internal fun noteFolderEpoch(folder: String, epoch: Long) {
        epochs[folder] = epoch
    }

    private fun refsFor(uids: List<Long>, folder: String = actFolder ?: mailbox): List<MessageRef> {
        val epoch = epochOf(folder)
        return uids.map { MessageRef(accountKey, folder, epoch, it) }
    }

    /** F107: UIDs at or above this (the UIDNEXT when "entire folder" was chosen) are never part of it. */
    private var allMailboxCeiling: Long = 0L

    suspend fun freezeAllMailbox() {
        allMailboxCeiling = session.folderInfo(FolderAt(mailbox, epochOf(mailbox).takeIf { it != 0L } ?: -1L)).uidNext
    }

    private suspend fun frozenAll(kind: String = "All"): List<Long> {
        val all = allUidsInWindows(home, kind = kind)
        val ceiling = allMailboxCeiling
        return if (ceiling > 0) all.filter { it < ceiling } else all
    }

    /** F105: while set, UID actions run in this folder (a cross-folder search hit's own folder). */
    private var actFolder: String? = null

    /** A removed hit leaves [order] and [orderMailboxes] at the same positions, so the lists stay one length. */
    private fun dropGoneFromOrder(gone: Set<Long>) {
        val kept = dropOrderedHits(order, orderMailboxes, gone, actFolder)
        order = kept.first
        orderMailboxes = kept.second
    }

    /** True when [row] is one of the rows the current action is about (same folder as the action). */
    private fun inActedFolder(row: IndexRow): Boolean = inListedFolder(row, actFolder ?: mailbox)

    /** [row] was listed in [folder] (an empty row mailbox is this index's folder). */
    private fun inListedFolder(row: IndexRow, folder: String): Boolean {
        val named = if (row.mailbox.isEmpty()) mailbox else row.mailbox
        return sameMailbox(named, folder)
    }

    private fun foreign(rowMailbox: String) = rowMailbox.isNotEmpty() && rowMailbox != mailbox

    /**
     * F105: run [block] against [rowMailbox] (a search hit from another folder), then return the session to this
     * index's folder. Undo is not offered for such actions: MailUndo carries no folder.
     */
    private suspend fun <T> inRowFolder(rowMailbox: String, block: suspend () -> T): T {
        if (!foreign(rowMailbox)) return block()
        actFolder = rowMailbox
        try {
            return block()
        } finally {
            actFolder = null
        }
    }

    /**
     * F105: run [op] on a selection that may span folders: [own] UIDs (or the entire folder) here, and each other
     * folder's [hits] inside that folder through its refs. Notices are joined; undo merges every part's refs.
     */
    suspend fun acrossFolders(
        own: List<Long>,
        hits: List<Pair<String, Long>>,
        entire: Boolean,
        op: suspend (uids: List<Long>, entire: Boolean, folder: String) -> List<IndexRow>,
    ): List<IndexRow> {
        if (hits.isEmpty()) return op(own, entire, mailbox)
        val undos = ArrayList<MailUndo>()
        val notes = ArrayList<String>()
        var parts = 0
        suspend fun part(block: suspend () -> Unit) {
            parts += 1
            mailUndo = null
            notice = null
            block()
            mailUndo?.let(undos::add)
            notice?.let(notes::add)
        }
        if (own.isNotEmpty() || entire) part { op(own, entire, mailbox) }
        for ((folder, uids) in hits.groupBy({ it.first }, { it.second })) {
            part { inRowFolder(folder) { op(uids, false, folder) } }
        }
        notice = notes.distinct().joinToString("; ").ifEmpty { null }
        mailUndo = if (undos.size == parts) mergeUndo(undos) else null
        return heldRows
    }

    private fun mergeUndo(parts: List<MailUndo>): MailUndo? {
        val first = parts.firstOrNull() ?: return null
        if (parts.any { it.source.isEmpty() || it.delete != first.delete || it.targetMailbox != first.targetMailbox ||
                it.usedMove != first.usedMove }) return null
        return first.copy(
            source = parts.flatMap { it.source },
            dest = parts.flatMap { it.dest },
        )
    }

    suspend fun deleteMessages(uids: List<Long>, allMailbox: Boolean = false): List<IndexRow> {
        if (!allMailbox && uids.isEmpty()) return heldRows
        try {
            // F44: "entire folder" works on a UID snapshot (never 1:*), so mail arriving meanwhile is untouched,
            // and undo only clears \Deleted on UIDs this action actually changed.
            val target = if (allMailbox) frozenAll() else uids
            val wanted = target.toSet()
            val before = if (allMailbox) home.searchCriterion("Deleted", "").toSet()
                else heldRows.filter { it.uid in wanted && "\\Deleted" in it.flags && inActedFolder(it) }.map { it.uid }.toSet()
            val done = ArrayList<Long>()
            var failure: MailFailure? = null
            for (part in target.chunked(MoveChunk)) {
                try {
                    home.storeFlags(part, setOf("\\Deleted"), emptySet())
                    done.addAll(part)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    failure = error
                    break
                }
            }
            val changed = done.filter { it !in before }
            val idSet = done.toSet()
            heldRows = heldRows.map { row ->
                if (row.uid in idSet && inActedFolder(row)) row.copy(flags = row.flags + "\\Deleted") else row
            }
            notice = when {
                failure != null && done.isNotEmpty() -> "Deleted ${done.size} of ${target.size}, then: ${failure.text}"
                failure != null -> failure.text
                else -> null
            }
            mailUndo = if (changed.isEmpty()) null else MailUndo(
                delete = true,
                source = refsFor(changed),
            )
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
        if (sameMailbox(moveMailbox, actFolder ?: mailbox)) {
            // F103: copy-then-mark into the same folder would duplicate every message.
            notice = "Already in ${mailboxLeaf(moveMailbox)}"
            mailUndo = null
            return heldRows
        }
        val settings = store.load()
        account = settings
        moveStop = false
        try {
            // "Move entire folder" too goes in batches: first every UID (UID SEARCH ALL, in PARTIAL windows when
            // offered), then chunks. Copy-then-mark only sets \Deleted per chunk; nothing here expunges.
            val all = if (allMailbox) frozenAll(moveAllKind(settings)) else uids
            val chunk = LabTestHooks.moveChunk() ?: MoveChunk
            val big = all.size > chunk
            var targetEpoch = -1L // one destination epoch for all batches, else 0 (no undo)
            val outcome = moveInChunks(all, chunk, { moveStop }, { done, total ->
                if (big) onSearchProgress?.invoke(SearchProgress(done.toLong(), total.toLong(), done.toLong()))
            }) { part ->
                // One call: select/verify the source, copy, and read COPYUID before anything else can run.
                val copied = session.copyFrom(actAt(), part, moveMailbox, delete = true)
                val epoch = copied.targetEpoch
                targetEpoch = if (targetEpoch == -1L || targetEpoch == epoch) epoch else 0L
                copied.copiedUids
            }
            if (big) onSearchProgress?.invoke(null)
            val moved = outcome.moved
            val usedMove = moveCommandKind(settings.moveMethod, session.featureCaps.move) == "Move"
            // Whatever moved is reflected, also when a later batch failed or Stop was pressed.
            if (allMailbox && moved.size == all.size && outcome.failure == null) {
                order = emptyList()
                orderMailboxes = emptyList()
                heldRows = emptyList()
            } else if (moved.isNotEmpty()) {
                val gone = moved.toSet()
                dropGoneFromOrder(gone)
                heldRows = heldRows.filter { it.uid !in gone || !inActedFolder(it) }
            }
            // F102: with copy-then-mark a failed batch may already be copied (COPY ok, STORE failed).
            val maybeCopied = if (outcome.failure != null && !usedMove) {
                "; the failed batch may already be copied to ${mailboxLeaf(moveMailbox)}, check it before retrying"
            } else {
                ""
            }
            notice = when {
                outcome.failure != null && moved.isNotEmpty() ->
                    "Moved ${moved.size} of ${all.size}, then: ${outcome.failure.text}$maybeCopied"
                outcome.failure != null -> outcome.failure.text + maybeCopied
                moved.size < all.size -> "Moved ${moved.size} of ${all.size}; stopped"
                else -> null
            }
            // F45/F102: undo only when the server named every copy (COPYUID for each moved message); otherwise a
            // MOVE could not be reversed and copy-then-mark would leave duplicates.
            mailUndo = if (moved.isEmpty() || outcome.dest.size != moved.size || targetEpoch <= 0L) null else MailUndo(
                delete = false,
                targetMailbox = moveMailbox,
                usedMove = usedMove,
                source = refsFor(moved),
                dest = outcome.dest.map { MessageRef(accountKey, moveMailbox, targetEpoch, it) },
            )
            if (moved.isNotEmpty() && mailUndo == null && outcome.failure == null) {
                notice = "Moved; undo is not available (the server did not report the new message IDs)"
            }
            OpenMessageOrder.publish(orderAccountId, epochs[mailbox] ?: 0L, mailbox, publishableOrder(order), heldRows)
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
            val snapshot = if (allMailbox) frozenAll() else uids
            val done = ArrayList<Long>()
            var failure: MailFailure? = null
            for (part in snapshot.chunked(MoveChunk)) {
                try {
                    home.storeFlags(part, setOf("\\Deleted"), emptySet())
                    home.uidExpunge(part)
                    done.addAll(part)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    failure = error
                    break
                }
            }
            if (done.isNotEmpty()) {
                val gone = done.toSet()
                dropGoneFromOrder(gone)
                heldRows = heldRows.filter { it.uid !in gone || !inActedFolder(it) }
            }
            notice = when {
                failure != null && done.isNotEmpty() -> "Deleted ${done.size} of ${snapshot.size}, then: ${failure.text}"
                failure != null -> failure.text
                else -> null
            }
            mailUndo = null
            if (done.isNotEmpty()) {
                OpenMessageOrder.publish(orderAccountId, epochs[mailbox] ?: 0L, mailbox, publishableOrder(order), heldRows)
            }
        } catch (failure: MailFailure) {
            notice = failure.text
            mailUndo = null
        }
        return heldRows
    }

    /**
     * F105: undo exactly what [u] recorded, in the folders and UIDVALIDITY epochs it recorded (a cross-folder hit's
     * undo works too). An undo without those refs is refused.
     */
    suspend fun undo(u: MailUndo): List<IndexRow> {
        account = store.load()
        if (u.source.isEmpty()) {
            notice = "Undo not possible: the message was not recorded"
            mailUndo = null
            return heldRows
        }
        try {
            if (u.delete) {
                session.onRefs(accountKey, u.source) { s, _, uids ->
                    for (part in uids.chunked(MoveChunk)) s.storeFlags(part, emptySet(), setOf("\\Deleted"))
                }
                val hits = u.source.map { it.mailbox to it.uid }.toSet()
                heldRows = heldRows.map { row ->
                    val named = if (row.mailbox.isEmpty()) mailbox else row.mailbox
                    if ((named to row.uid) in hits || u.source.any { sameMailbox(it.mailbox, named) && it.uid == row.uid }) {
                        row.copy(flags = row.flags - "\\Deleted")
                    } else {
                        row
                    }
                }
                notice = null
                mailUndo = null
                return heldRows
            }
            if (u.dest.size != u.source.size) {
                notice = "Undo not possible: the server did not report where the messages went"
                mailUndo = null
                return heldRows
            }
            if (u.usedMove) {
                // Same move the action used (MOVE when that is what ran), so the target does not keep \Deleted copies.
                val buckets = LinkedHashMap<Triple<String, Long, String>, MutableList<Long>>()
                for (i in u.source.indices) {
                    val destRef = u.dest[i]
                    val key = Triple(destRef.mailbox, destRef.uidValidity, u.source[i].mailbox)
                    buckets.getOrPut(key) { ArrayList() }.add(destRef.uid)
                }
                for ((key, moved) in buckets) {
                    session.copyFrom(FolderAt(key.first, key.second), moved, key.third, delete = true)
                }
            } else {
                session.onRefs(accountKey, u.source) { s, _, uids ->
                    s.storeFlags(uids, emptySet(), setOf("\\Deleted"))
                }
                session.onRefs(accountKey, u.dest) { s, _, uids ->
                    s.storeFlags(uids, setOf("\\Deleted"), emptySet())
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
            // F44: snapshot UIDs, so new mail is not marked (e.g. \Seen) before it was ever shown.
            val target = if (allMailbox) frozenAll() else uids
            val done = ArrayList<Long>()
            var failure: MailFailure? = null
            for (part in target.chunked(MoveChunk)) {
                try {
                    home.storeFlags(part, add, remove)
                    done.addAll(part)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: MailFailure) {
                    failure = error
                    break
                }
            }
            val idSet = done.toSet()
            heldRows = heldRows.map { row ->
                if (row.uid in idSet && inActedFolder(row)) row.copy(flags = (row.flags + add) - remove) else row
            }
            notice = when {
                failure != null && done.isNotEmpty() -> "Changed ${done.size} of ${target.size}, then: ${failure.text}"
                failure != null -> failure.text
                else -> null
            }
        } catch (failure: MailFailure) {
            notice = failure.text
        }
        return heldRows
    }

    suspend fun expunge(): List<IndexRow> {
        if (!session.featureCaps.uidPlus) return heldRows
        try {
            home.uidExpungeDeleted()
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
            home.uidExpunge(uids)
        } catch (failure: MailFailure) {
            notice = failure.text
            return heldRows
        }
        mailUndo = null
        if (!loadedWindow) return heldRows
        return reloadKeepingAnchor()
    }

    suspend fun performSwipe(uid: Long, binding: SwipeBinding, rowMailbox: String = ""): IndexCommand {
        if (foreign(rowMailbox)) {
            when (binding.action) {
                SwipeAction.Reply, SwipeAction.ReplyAll -> {
                    val kind = if (binding.action == SwipeAction.Reply) ComposeKind.Reply else ComposeKind.ReplyAll
                    return IndexCommand.Compose(ComposeSeed(kind, rowMailbox, listOf(uid), listedEpoch(rowMailbox)))
                }
                SwipeAction.FlagScreen -> {
                    notice = "Open the message to change flags on a hit from another folder"
                    return IndexCommand.None
                }
                else -> return inRowFolder(rowMailbox) { performSwipeHere(uid, binding) }
            }
        }
        return performSwipeHere(uid, binding)
    }

    private suspend fun performSwipeHere(uid: Long, binding: SwipeBinding): IndexCommand {
        when (binding.action) {
            SwipeAction.Delete -> {
                val settings = store.load()
                account = settings
                val trash = session.knownTrash()
                val policy = effectiveDeletePolicy(
                    settings.deletePolicy,
                    trash.isNotEmpty() && sameMailbox(actFolder ?: mailbox, trash),
                    session.featureCaps.uidPlus,
                    trash.isNotEmpty(),
                    trashNamedByUser = usableTrashName(settings.trashMailbox).isNotEmpty(),
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

    /** F124: a row resumes as a draft only when its own folder (not the index's) is Postponed. */
    fun openSeed(uid: Long, rowMailbox: String = "", rowFlags: Set<String> = emptySet()): ComposeSeed? {
        val folder = rowMailbox.ifEmpty { mailbox }
        // F147b: a JMAP staging copy (a message being, or already, sent) is never resumed as a draft.
        if (rowFlags.any { it.equals(org.dlang.liveimap.jmap.JMAP_STAGING_KEYWORD, ignoreCase = true) }) return null
        if (account.postponedMailbox.isNotEmpty() && sameMailbox(folder, account.postponedMailbox)) {
            return ComposeSeed(ComposeKind.ResumePostpone, folder, listOf(uid), listedEpoch(folder))
        }
        return null
    }

    fun bounceSeed(uids: List<Long>): ComposeSeed = actionSeed(ComposeKind.Bounce, uids)

    fun actionSeed(kind: ComposeKind, uids: List<Long>): ComposeSeed =
        ComposeSeed(kind, actFolder ?: mailbox, uids, listedEpoch(actFolder ?: mailbox))

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
            heldRows = try {
                load()
            } catch (changed: EpochChanged) {
                // F142: the folder's UIDVALIDITY moved since its rows were listed. Drop everything dated by the old
                // epoch and list again (once); the new listing records the new epoch.
                if (changed.mailbox != mailbox) throw changed
                epochs.remove(mailbox)
                allMailboxCeiling = 0L
                mailUndo = null
                order = emptyList()
                load()
            }
            loadedWindow = true
            notice = pageFailures ?: pendingNotice
            pageFailures = null
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
            pageFailures = null
            forceNewest = false
            honourKeep = false
            notice = failure.text
        }
        return heldRows
    }

    private suspend fun fetchCurrent(preserveAnchor: Int? = null): List<IndexRow> {
        val loaded = store.load()
        account = loaded
        includePreview = wantsPreview(loaded)
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
        var exists = home.selectedExists()
        if (exists <= 0) {
            exists = session.folderInfo(FolderAt(mailbox, epochs[mailbox] ?: 0L)).exists
        }
        if (exists <= 0) {
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
        val fetched = fetchListed(
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
            home.searchStart(rule, false, edge)
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
            home.searchStart(rule, true, SearchEdge.All)
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
                home.locateUid(uid)
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

    /** The active filter chain as SORT/THREAD criteria, or null when no filter is on. */
    private fun chainCriteria(): String? = if (filterUids == null) null else filterChainGroup(appliedFilters)

    private suspend fun fetchSorted(key: SortKey, newestFirst: Boolean, preserveAnchor: Int? = null): List<IndexRow> {
        clearThreads()
        val criteria = chainCriteria()
        run {
            val windowed = windowedSort(key, newestFirst, criteria)
            if (windowed != null) {
                order = windowed
                arrivalTotal = 0
                pendingNew = 0
                pageAnchor = clampedAnchor(windowed.size, preserveAnchor, 0)
                return pagesOf(windowed)
            }
        }
        val uids = if (!session.featureCaps.sort && slowerClientSort(account)) {
            requireClientFallbackRoom()
            // Chunked client sort: progress and Cancel through the same bar as long searches and big moves.
            moveStop = false
            try {
                restrict(
                    home.clientOrder(key, newestFirst, { done, total ->
                        onSearchProgress?.invoke(SearchProgress(done.toLong(), total.toLong(), done.toLong()))
                    }, { moveStop }),
                )
            } finally {
                onSearchProgress?.invoke(null)
            }
        } else {
            // With a filter on, the criteria go with SORT; restricting to the search result is only the fallback.
            criteria?.let { home.sortMatching(key, newestFirst, it) } ?: restrict(home.sort(key, newestFirst))
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
            requireClientFallbackRoom()
            home.clientThread(key)
        } else {
            null
        }
        val criteria = chainCriteria()
        val matched = if (node == null && criteria != null) home.threadMatching(key, criteria) else null
        val collapsed = collapsedThreads(node ?: matched ?: home.thread(key), newestFirst)
        // THREAD with the criteria already holds only matching messages; otherwise restrict to the search result.
        val kept = if (matched != null) collapsed else restrictThreads(collapsed)
        threading = true
        threadPlan = kept
        threadMembers = emptyMap()
        order = compactUids(kept.map { it.rootUid })
        arrivalTotal = 0
        pendingNew = 0
        val target = resolveTarget(order.size, preserveAnchor)
        pageAnchor = clampedAnchor(order.size, preserveAnchor, target ?: 0)
        val loaded = loadThreadPage()
        if (target != null) rememberStart(target, loaded.size)
        return loaded
    }

    private suspend fun requireClientFallbackRoom() {
        if (allowLargeClientFallback || mailbox in account.fallbackAskSkip) return
        if (overAskThreshold(home.selectedExists(), account.askFallback)) {
            throw MailFailure("folder is large")
        }
    }

    private fun restrict(uids: List<Long>): List<Long> {
        val keep = filterUids ?: return uids
        return compactUids(uids) { it in keep }
    }

    private suspend fun fetchSearch(query: String, preserveAnchor: Int? = null): List<IndexRow> {
        clearThreads()
        val advanced = activeAdvanced
        if (advanced != null && activeScope != SearchScope.Current) {
            return fetchScopedAdvanced(advanced, preserveAnchor)
        }
        orderMailboxes = emptyList()
        if (advanced == null) {
            val windowed = windowedSearch(activeSearchField.name, query, view.newestFirst)
            if (windowed != null) {
                order = windowed
                arrivalTotal = 0
                pendingNew = 0
                pageAnchor = clampedAnchor(windowed.size, preserveAnchor, 0)
                return pagesOf(windowed)
            }
        }
        val found = if (advanced != null) {
            searchMaybeLong("Advanced", advanced)
        } else {
            home.searchCriterion(activeSearchField.name, query)
        }
        val uids = sortedUids(found, view.newestFirst)
        order = uids
        arrivalTotal = 0
        pendingNew = 0
        val target = resolveTarget(uids.size, preserveAnchor)
        pageAnchor = clampedAnchor(uids.size, preserveAnchor, target ?: 0)
        val loaded = pagesOf(uids)
        if (target != null) rememberStart(target, loaded.size)
        return loaded
    }

    /**
     * The sorted order as windows (ESORT PARTIAL): one round trip for the first window plus COUNT, instead of the
     * whole UID list. Null when the server cannot do it, or answers COUNT without the window (Cyrus 3.12/3.13).
     */
    private suspend fun windowedSort(key: SortKey, newestFirst: Boolean, criteria: String? = null): WindowedOrder? {
        val size = IndexPageSize * 2
        suspend fun window(lo: Int, hi: Int): UidWindow? = if (criteria == null) {
            home.sortWindow(key, newestFirst, lo, hi)
        } else {
            home.sortWindowMatching(key, newestFirst, lo, hi, criteria)
        }
        val first = window(1, size) ?: return null
        if (first.total <= size) return null
        val order = WindowedOrder(first.total, size) { lo, hi ->
            window(lo, hi)?.uids ?: throw MailFailure("sort failed")
        }
        order.seed(0, first.uids)
        return order
    }

    /**
     * Body and text searches run on a separate connection ([longSearchLinks]) with INPROGRESS progress; Cancel
     * drops it and a late result is discarded. No retry. Without a link factory (tests) they use the main session.
     */
    private suspend fun searchMaybeLong(kind: String, argument: String): List<Long> {
        presetHits?.let { return it }
        // David's connection limit: with no room for a search connection, search on the main one.
        if (longSearchLinks == null || !isLongSearch(kind, argument) || !connectionLimits(account).searchConnection) {
            return home.searchCriterion(kind, argument)
        }
        return runLongSearch(argument)
    }

    /** True when [text] in [scope] would run as a long search on a side connection. */
    fun isLongAdvanced(text: String, scope: SearchScope): Boolean =
        longSearchLinks != null && scope == SearchScope.Current && isLongSearch("Advanced", text) &&
            connectionLimits(account).searchConnection

    /**
     * Runs a long advanced search on its side connection and returns the hits without touching the list: the
     * caller does not hold the list gate, so the list keeps working on the main session meanwhile. Apply the hits
     * with [applyAdvancedHits]. Throws MailFailure ("search cancelled" after Cancel); never retries.
     */
    suspend fun runLongSearch(text: String): List<Long> {
        val links = longSearchLinks ?: throw MailFailure("search failed")
        val search = LongSearch(links(store.load(), mailbox))
        runningSearch = search
        onSearchProgress?.invoke(SearchProgress(-1, -1, 0))
        try {
            return when (val result = search.run("Advanced", text) { onSearchProgress?.invoke(it) }) {
                is LongSearchResult.Hits -> result.uids
                LongSearchResult.Cancelled -> throw MailFailure("search cancelled")
                is LongSearchResult.Failed -> throw MailFailure(result.text)
            }
        } finally {
            runningSearch = null
            onSearchProgress?.invoke(null)
        }
    }

    /** Shows hits a [runLongSearch] already found, as the advanced search [text], without searching again. */
    suspend fun applyAdvancedHits(text: String, uids: List<Long>): List<IndexRow> {
        presetHits = uids
        try {
            return applyAdvanced(text)
        } finally {
            presetHits = null
        }
    }

    private var presetHits: List<Long>? = null

    /** Cancel the running long search (drops its connection), or stop a chunked move before its next chunk. */
    fun cancelSearch() {
        moveStop = true
        runningSearch?.cancel()
    }

    @Volatile private var moveStop = false

    /** Search hits as windows (ESEARCH PARTIAL), in display order; null when unsupported or small. */
    private suspend fun windowedSearch(kind: String, query: String, newestFirst: Boolean): WindowedOrder? {
        val size = IndexPageSize * 2
        val probe = home.searchWindow(kind, query, 1, 1) ?: return null
        val total = probe.total
        if (total <= size) return null
        val order = WindowedOrder(total, size) { lo, hi ->
            val (a, b) = searchWindowRange(lo, hi, total, newestFirst)
            val hits = home.searchWindow(kind, query, a, b)?.uids?.sorted() ?: throw MailFailure("search failed")
            if (newestFirst) hits.reversed() else hits
        }
        order.ensure(0, size)
        return order
    }

    private suspend fun pagesOf(uids: List<Long>): List<IndexRow> {
        if (uids is WindowedOrder) uids.ensure(pageAnchor * IndexPageSize, IndexPageSize * 2)
        val visible = uids.drop(pageAnchor * IndexPageSize).take(IndexPageSize).toMutableList()
        val prefetch = uids.drop((pageAnchor + 1) * IndexPageSize).take(IndexPageSize).toMutableList()
        val loaded = ArrayList<IndexRow>(visible.size + prefetch.size)
        if (uids is WindowedOrder) {
            visible.removeAll { it == WindowedOrder.Unknown }
            prefetch.removeAll { it == WindowedOrder.Unknown }
        }
        if (visible.isNotEmpty()) loaded += align(visible, fetchByUid(visible))
        if (prefetch.isNotEmpty()) loaded += align(prefetch, fetchByUid(prefetch))
        return loaded
    }

    private suspend fun fetchByUid(
        uids: List<Long>,
        preview: Boolean = includePreview,
        folder: String = mailbox,
    ): List<IndexRow> {
        return fetchListed(
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
                // F128: one call selects the box, dates its hits and searches it.
                val found = try {
                    session.inFolder(FolderAt(box)) { selected, s ->
                        // F142: a search must not re-date this index's own listed rows; a moved epoch there is a
                        // reset (the next load re-lists), and its hits are dropped rather than mixed with old rows.
                        if (searchEpochMasks(box == mailbox, epochs[box], selected.uidValidity)) {
                            throw EpochChanged(box)
                        }
                        epochs[box] = selected.uidValidity
                        s.searchCriterion("Advanced", text)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: EpochChanged) {
                    continue
                } catch (_: MailFailure) {
                    continue
                }
                val ordered = if (view.newestFirst) found.sortedDescending() else found.sorted()
                for (uid in ordered) {
                    hits.add(uid)
                    hitBoxes.add(box)
                }
            }
        } finally {
            // Nothing to restore: no folder is ever left selected for anyone else to depend on.
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
        val failed = ArrayList<String>()
        for ((box, slice) in pageMailboxGroups(uids, mailboxes, start, end)) {
            // fetchListed checks the box's recorded epoch (or records it) in the same call as the fetch.
            val fetched = try {
                align(slice, fetchByUid(slice, folder = box))
            } catch (error: CancellationException) {
                throw error
            } catch (failure: MailFailure) {
                failed.add("$box: ${failure.text}")
                emptyList()
            }
            for (row in fetched) loaded.add(row.copy(mailbox = box))
        }
        if (failed.isNotEmpty()) pageFailures = failed.joinToString("; ")
        return loaded
    }

    private suspend fun rememberExists() {
        val exists = home.selectedExists()
        if (exists > 0) knownExists = exists
    }

    private suspend fun addNewTail(growth: Int) {
        if (growth <= 0) return
        val fetched = fetchListed(
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
            order = orderWith(order, placed.map { it.uid }, front = true)
            heldRows = placed + heldRows
        } else {
            order = orderWith(order, placed.map { it.uid }, front = false)
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

/** UIDs per MOVE/COPY command for big moves (review 3.7: 10,000 in one command took 40 s with no feedback). */
const val MoveChunk = 1000

/** Search kind for "Move entire folder": UNDELETED by default, ALL when the account includes \Deleted. */
fun moveAllKind(settings: AccountSettings): String = if (settings.moveAllIncludesDeleted) "All" else "NotDeleted"

data class ChunkedMove(val moved: List<Long>, val dest: List<Long>, val failure: MailFailure? = null)

/**
 * Runs [moveChunk] over [uids] in chunks of [size], reporting progress after each and checking [stop] before
 * every chunk but the first. A failing chunk ends the run; the result still lists what moved before it.
 */
suspend fun moveInChunks(
    uids: List<Long>,
    size: Int,
    stop: () -> Boolean,
    progress: (done: Int, total: Int) -> Unit,
    moveChunk: suspend (List<Long>) -> List<Long>,
): ChunkedMove {
    val moved = LongArray(uids.size)
    var n = 0
    val dest = ArrayList<Long>()
    for ((index, chunk) in uids.chunked(size).withIndex()) {
        if (index > 0 && stop()) break
        try {
            dest += moveChunk(chunk)
        } catch (failure: MailFailure) {
            return ChunkedMove(moved.copyOf(n).asList(), dest, failure)
        }
        for (u in chunk) moved[n++] = u
        progress(n, uids.size)
    }
    return ChunkedMove(moved.copyOf(n).asList(), dest)
}

/** Every UID in the selected folder, ascending: PARTIAL windows of [window] when offered, else one search. */
suspend fun allUidsInWindows(session: MailSession, window: Int = 10_000, kind: String = "All"): List<Long> {
    val probe = session.searchWindow(kind, "", 1, window) ?: return sortedUids(session.searchCriterion(kind, ""), false)
    val out = LongArray(probe.total)
    var n = 0
    fun take(uids: List<Long>) {
        for (u in uids.sorted()) if (n < out.size) out[n++] = u
    }
    take(probe.uids)
    var lo = window + 1
    while (lo <= probe.total) {
        val hi = minOf(probe.total, lo + window - 1)
        take(session.searchWindow(kind, "", lo, hi)?.uids ?: throw MailFailure("search failed"))
        lo = hi + 1
    }
    return out.copyOf(n).asList()
}

/** F142: true when a search's select would overwrite the home folder's listed epoch with a different one. */
internal fun searchEpochMasks(homeFolder: Boolean, listed: Long?, selected: Long): Boolean =
    homeFolder && listed != null && listed > 0 && selected > 0 && listed != selected

/**
 * Drops [gone] from a search order and the mailbox recorded at that same position.
 * [actedFolder] null drops every matching UID (the home-folder action). A named folder drops only its own hits,
 * including while that folder is the acted-on one, so the hit does not stay and show up again.
 */
internal fun dropOrderedHits(
    order: List<Long>,
    mailboxes: List<String>,
    gone: Set<Long>,
    actedFolder: String?,
): Pair<List<Long>, List<String>> {
    if (mailboxes.isEmpty()) {
        val next = if (actedFolder == null) orderWithout(order, gone) else order
        return next to mailboxes
    }
    val uids = ArrayList<Long>(order.size)
    val boxes = ArrayList<String>(mailboxes.size)
    val n = minOf(order.size, mailboxes.size)
    for (i in 0 until n) {
        val uid = order[i]
        val box = mailboxes[i]
        val drop = uid in gone && (actedFolder == null || sameMailbox(box, actedFolder))
        if (!drop) {
            uids.add(uid)
            boxes.add(box)
        }
    }
    return compactUids(uids) to boxes
}

/** UIDs from start until end, each grouped with the mailbox stored at that position. */
internal fun pageMailboxGroups(
    uids: List<Long>,
    mailboxes: List<String>,
    start: Int,
    end: Int,
): List<Pair<String, List<Long>>> {
    val groups = ArrayList<Pair<String, List<Long>>>()
    var index = start
    while (index < end) {
        val box = mailboxes[index]
        var next = index + 1
        while (next < end && mailboxes[next] == box) next += 1
        groups.add(box to uids.subList(index, next).toList())
        index = next
    }
    return groups
}
