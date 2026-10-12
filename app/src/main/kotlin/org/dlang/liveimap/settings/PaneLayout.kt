package org.dlang.liveimap.settings

/** What a panel shows. */
enum class PaneContent(val code: String) {
    Folders("folders"),
    MessageList("list"),
    Message("message"),
    AccountDrawer("drawer"),
}

/** Horizontal = side by side (a vertical divider); Vertical = top and bottom. */
enum class SplitAxis(val code: String) { Horizontal("H"), Vertical("V") }

/**
 * A custom layout is a binary split tree. A leaf is one panel; a split divides its area along an axis,
 * [ratio] being the first child's share. See design-decisions.md "Custom panel layout".
 */
sealed class PaneNode {
    data class Leaf(val content: PaneContent) : PaneNode()
    data class Split(val axis: SplitAxis, val ratio: Float, val first: PaneNode, val second: PaneNode) : PaneNode()
}

fun PaneNode.leaves(): List<PaneContent> = when (this) {
    is PaneNode.Leaf -> listOf(content)
    is PaneNode.Split -> first.leaves() + second.leaves()
}

fun PaneNode.panelCount(): Int = leaves().size

/** Text form, e.g. `H0.25(folders,V0.5(list,message))`. */
fun PaneNode.encode(): String = when (this) {
    is PaneNode.Leaf -> content.code
    is PaneNode.Split -> "${axis.code}${"%.3f".format(java.util.Locale.ROOT, ratio).trimEnd('0').trimEnd('.')}(${first.encode()},${second.encode()})"
}

/** Null for empty or malformed text (callers then use Auto). */
fun decodePaneLayout(text: String): PaneNode? {
    if (text.isBlank()) return null
    var at = 0
    fun parse(depth: Int): PaneNode? {
        if (depth > 8) return null
        val rest = text.substring(at)
        PaneContent.entries.firstOrNull { rest.startsWith(it.code) }?.let {
            at += it.code.length
            return PaneNode.Leaf(it)
        }
        val axis = SplitAxis.entries.firstOrNull { rest.startsWith(it.code) } ?: return null
        at += 1
        val open = text.indexOf('(', at)
        if (open < 0) return null
        val ratio = text.substring(at, open).toFloatOrNull()?.takeIf { it > 0f && it < 1f } ?: return null
        at = open + 1
        val first = parse(depth + 1) ?: return null
        if (text.getOrNull(at) != ',') return null
        at += 1
        val second = parse(depth + 1) ?: return null
        if (text.getOrNull(at) != ')') return null
        at += 1
        return PaneNode.Split(axis, ratio, first, second)
    }
    val node = parse(0) ?: return null
    return if (at == text.length) node else null
}

enum class PaneLayoutProblem { NoMessageList, Duplicate, MessageNotBesideList }

/**
 * Rules: exactly one message list; each content at most once; the message panel, when present, shares
 * one split with the message list (it shows the list's selection, and the pair resizes together).
 */
fun paneLayoutProblem(node: PaneNode): PaneLayoutProblem? {
    val leaves = node.leaves()
    if (PaneContent.MessageList !in leaves) return PaneLayoutProblem.NoMessageList
    if (leaves.size != leaves.toSet().size) return PaneLayoutProblem.Duplicate
    if (PaneContent.Message in leaves && mailSplit(node) == null) return PaneLayoutProblem.MessageNotBesideList
    return null
}

/**
 * The mail split: one child is the message panel, the other contains the message list (alone, or with
 * other panels such as folders above it). Null when there is no message panel or it is elsewhere.
 */
fun mailSplit(node: PaneNode): PaneNode.Split? {
    if (node !is PaneNode.Split) return null
    val msg = PaneNode.Leaf(PaneContent.Message)
    if (node.second == msg && PaneContent.MessageList in node.first.leaves()) return node
    if (node.first == msg && PaneContent.MessageList in node.second.leaves()) return node
    return mailSplit(node.first) ?: mailSplit(node.second)
}

/** Whether the message panel is the first child of [split] (above or left of the list side). */
fun PaneNode.Split.messageFirst(): Boolean = first == PaneNode.Leaf(PaneContent.Message)

/** The list side of the mail split. */
fun PaneNode.Split.listSide(): PaneNode = if (messageFirst()) second else first

/**
 * The part of the tree the app's screens (NavHost) fill: the mail split if there is one, else the list
 * leaf. Settings, compose and the rest open in this area too.
 */
fun mailNode(node: PaneNode): PaneNode = mailSplit(node) ?: PaneNode.Leaf(PaneContent.MessageList)

/**
 * The automatic arrangement expressed in this model (the default a custom layout starts from).
 * The drawer is a panel only when no folder column shows; with folders shown it stays a hidden drawer.
 */
fun autoPaneLayout(plan: LayoutPlan, drawerPane: Boolean = false): PaneNode {
    val list = PaneNode.Leaf(PaneContent.MessageList)
    val message = PaneNode.Leaf(PaneContent.Message)
    val folders = PaneNode.Leaf(PaneContent.Folders)
    val mailAxis = if (plan.arrangement == PaneArrangement.Stacked) SplitAxis.Vertical else SplitAxis.Horizontal
    val mail: PaneNode = when {
        plan.panes <= 1 -> list
        plan.folders == FolderPlacement.AboveList ->
            PaneNode.Split(SplitAxis.Horizontal, plan.listShare, PaneNode.Split(SplitAxis.Vertical, 0.35f, folders, list), message)
        else -> PaneNode.Split(mailAxis, plan.listShare, list, message)
    }
    val withFolders = if (plan.folders == FolderPlacement.Beside) PaneNode.Split(SplitAxis.Horizontal, 0.2f, folders, mail) else mail
    val foldersShown = PaneContent.Folders in withFolders.leaves()
    return if (drawerPane && !foldersShown && plan.panes > 1) {
        PaneNode.Split(SplitAxis.Horizontal, 0.2f, PaneNode.Leaf(PaneContent.AccountDrawer), withFolders)
    } else {
        withFolders
    }
}

/** Editing: replace the leaf at [path] (0 = first, 1 = second at each split) by a split of it and [added]. */
fun PaneNode.splitAt(path: List<Int>, axis: SplitAxis, added: PaneContent): PaneNode = when {
    path.isEmpty() -> PaneNode.Split(axis, 0.5f, this, PaneNode.Leaf(added))
    this is PaneNode.Split -> if (path[0] == 0) copy(first = first.splitAt(path.drop(1), axis, added))
        else copy(second = second.splitAt(path.drop(1), axis, added))
    else -> this
}

/** Editing: remove the panel at [path]; its sibling takes the parent's place. The last panel can't go. */
fun PaneNode.removeAt(path: List<Int>): PaneNode {
    if (this !is PaneNode.Split || path.isEmpty()) return this
    if (path.size == 1) return if (path[0] == 0) second else first
    return if (path[0] == 0) copy(first = first.removeAt(path.drop(1))) else copy(second = second.removeAt(path.drop(1)))
}

fun PaneNode.setContent(path: List<Int>, content: PaneContent): PaneNode = when {
    path.isEmpty() -> if (this is PaneNode.Leaf) PaneNode.Leaf(content) else this
    this is PaneNode.Split -> if (path[0] == 0) copy(first = first.setContent(path.drop(1), content))
        else copy(second = second.setContent(path.drop(1), content))
    else -> this
}

fun PaneNode.withRatio(path: List<Int>, ratio: Float): PaneNode {
    if (this !is PaneNode.Split) return this
    if (path.isEmpty()) return copy(ratio = ratio.coerceIn(0.1f, 0.9f))
    return if (path[0] == 0) copy(first = first.withRatio(path.drop(1), ratio)) else copy(second = second.withRatio(path.drop(1), ratio))
}

/** Every leaf with its path, in reading order. */
fun PaneNode.leafPaths(prefix: List<Int> = emptyList()): List<Pair<List<Int>, PaneContent>> = when (this) {
    is PaneNode.Leaf -> listOf(prefix to content)
    is PaneNode.Split -> first.leafPaths(prefix + 0) + second.leafPaths(prefix + 1)
}
