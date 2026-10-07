package org.dlang.liveimap.ui.folder

import org.dlang.liveimap.session.FolderEntry
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.Namespace
import org.dlang.liveimap.session.NamespaceKind
import org.dlang.liveimap.settings.SettingsStore

internal const val CountFreshMillis = 300_000L

data class FolderRow(
    val mailbox: String,
    val leaf: String,
    val hasChildren: Boolean,
    val depth: Int,
    val parentMailbox: String?,
    val expanded: Boolean,
    val specialUse: String? = null,
    val messages: Int? = null,
    val unseen: Int? = null,
    val namespaceRoot: Boolean = false,
    val delimiter: Char = '\u0000',
)

class FolderListModel(
    private val session: MailSession,
    private val store: SettingsStore,
    private val nowMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private val countedAt = mutableMapOf<String, Long>()
    private var sessionExpandedFolders: MutableSet<String>? = null
    private val levelCache = mutableMapOf<LevelKey, List<FolderEntry>>()

    suspend fun loadLevel(): List<FolderRow> {
        val settings = store.load()
        val expanded = sessionExpanded()
        val namespaces = session.namespaces()
        val personal = namespaces.filter { it.kind == NamespaceKind.Personal }
        val other = namespaces.filter { it.kind == NamespaceKind.Other }
        val shared = namespaces.filter { it.kind == NamespaceKind.Shared }

        val siblings = mutableListOf<LevelNode>()
        val inboxChildren = mutableListOf<LevelNode>()
        var inbox: LevelNode? = null
        var inboxPrefix: String? = null
        var inboxDelimiter: Char = '\u0000'
        var sawInboxPrefixLevel = false
        var inboxFromSiblingLevel = false

        for (ns in personal) {
            if (inboxDelimiter == '\u0000') inboxDelimiter = ns.delimiter
            val level = listLevelCached(ns.prefix, null, settings.showUnreadCounts)
            val mark = inboxChildPrefix(ns.delimiter)
            // Prefix "INBOX." lists that mailbox's children. Do not show them as roots.
            val levelIsInboxChildren = mark != null && ns.prefix == mark
            if (levelIsInboxChildren) {
                sawInboxPrefixLevel = true
                if (inboxPrefix == null) inboxPrefix = ns.prefix
                for (entry in level) {
                    if (entry.mailbox == "INBOX") {
                        if (inbox == null) {
                            inbox = entryNode(entry, ns.prefix)
                            inboxPrefix = ns.prefix
                        }
                    } else {
                        addChild(inboxChildren, entryNode(entry, ns.prefix))
                    }
                }
            } else {
                for (entry in level) {
                    val underInbox = mark != null &&
                        entry.mailbox != "INBOX" &&
                        entry.mailbox.startsWith(mark)
                    if (underInbox) {
                        addChild(inboxChildren, entryNode(entry, ns.prefix))
                    } else if (entry.mailbox == "INBOX") {
                        if (inbox == null) {
                            inbox = entryNode(entry, ns.prefix)
                            inboxPrefix = ns.prefix
                            inboxFromSiblingLevel = true
                        }
                    } else if (siblings.none { it.mailbox == entry.mailbox }) {
                        siblings += entryNode(entry, ns.prefix)
                    }
                }
            }
        }

        val childrenComplete = sawInboxPrefixLevel && !inboxFromSiblingLevel
        val foundInbox = inbox
        val inboxNode = if (foundInbox != null) {
            foundInbox.copy(
                hasChildren = foundInbox.hasChildren || inboxChildren.isNotEmpty(),
                cachedChildren = inboxChildren.toList(),
                childrenComplete = childrenComplete,
            )
        } else if (personal.isNotEmpty()) {
            LevelNode(
                mailbox = "INBOX",
                leaf = "INBOX",
                hasChildren = inboxChildren.isNotEmpty(),
                namespacePrefix = inboxPrefix ?: personal.first().prefix,
                namespaceRoot = false,
                cachedChildren = inboxChildren.toList(),
                childrenComplete = childrenComplete,
                delimiter = inboxDelimiter,
            )
        } else {
            null
        }

        val roots = mutableListOf<LevelNode>()
        if (inboxNode != null) roots += inboxNode
        roots += orderedLevel(siblings)
        roots += orderedLevel(other.map { namespaceNode(it) })
        roots += orderedLevel(shared.map { namespaceNode(it) })

        val rows = mutableListOf<FolderRow>()
        for (root in roots) {
            appendVisible(root, 0, null, expanded, emptySet(), rows)
        }
        return rows
    }

    suspend fun refreshVisibleCounts(visible: List<FolderRow>): List<FolderRow> {
        val settings = store.load()
        if (!session.featureCaps.listStatus && !settings.forceSlowerFallbacks && !settings.statusVisibleCounts) {
            return visible
        }
        val now = nowMillis()
        val due = visible.filter { row ->
            if (row.namespaceRoot) return@filter false
            val at = countedAt[row.mailbox]
            at == null || now - at >= CountFreshMillis
        }
        if (due.isEmpty()) return visible
        val counts = session.statusMessages(due.map { it.mailbox })
        for (row in due) {
            countedAt[row.mailbox] = now
        }
        return visible.map { row ->
            val count = counts[row.mailbox]
            if (count != null) row.copy(messages = count) else row
        }
    }

    fun refreshLevels() {
        levelCache.clear()
    }

    suspend fun saveDefaultView() {
        val expanded = sessionExpanded().toSet()
        val settings = store.load()
        store.save(settings.copy(expandedFolders = expanded))
    }

    suspend fun resetToDefault() {
        val settings = store.load()
        sessionExpandedFolders = settings.expandedFolders.toMutableSet()
    }

    suspend fun alwaysExpand(mailbox: String) {
        val settings = store.load()
        if (mailbox !in settings.expandedFolders) {
            store.save(settings.copy(expandedFolders = settings.expandedFolders + mailbox))
        }
        sessionExpanded().add(mailbox)
    }

    suspend fun dontAlwaysExpand(mailbox: String) {
        val settings = store.load()
        if (mailbox in settings.expandedFolders) {
            store.save(settings.copy(expandedFolders = settings.expandedFolders - mailbox))
        }
    }

    fun collapseAll() {
        val current = sessionExpandedFolders
        if (current == null) {
            sessionExpandedFolders = mutableSetOf()
        } else {
            current.clear()
        }
    }

    suspend fun showCollapsed(mailbox: String) {
        val next = sessionExpanded()
        val ancestors = ancestorMailboxes(mailbox, session.namespaces())
        next.addAll(ancestors)
        next.remove(mailbox)
    }

    suspend fun toggleExpanded(mailbox: String) {
        val next = sessionExpanded()
        if (!next.add(mailbox)) {
            next.remove(mailbox)
        }
    }

    private suspend fun sessionExpanded(): MutableSet<String> {
        val existing = sessionExpandedFolders
        if (existing != null) return existing
        val copied = store.load().expandedFolders.toMutableSet()
        sessionExpandedFolders = copied
        return copied
    }

    private suspend fun listLevelCached(
        prefix: String,
        parentMailbox: String?,
        unreadCounts: Boolean,
    ): List<FolderEntry> {
        val key = LevelKey(prefix, parentMailbox ?: "")
        val hit = levelCache[key]
        if (hit != null) return hit
        val listed = session.listLevel(prefix, parentMailbox, unreadCounts)
        levelCache[key] = listed
        return listed
    }

    private suspend fun appendVisible(
        node: LevelNode,
        depth: Int,
        parentMailbox: String?,
        expanded: Set<String>,
        ancestors: Set<String>,
        rows: MutableList<FolderRow>,
    ) {
        val showChildren = node.hasChildren && node.mailbox in expanded && node.mailbox !in ancestors
        rows += FolderRow(
            mailbox = node.mailbox,
            leaf = node.leaf,
            hasChildren = node.hasChildren,
            depth = depth,
            parentMailbox = parentMailbox,
            expanded = showChildren,
            specialUse = node.specialUse,
            messages = node.messages,
            unseen = node.unseen,
            namespaceRoot = node.namespaceRoot,
            delimiter = node.delimiter,
        )
        if (!showChildren) return
        val nextAncestors = ancestors + node.mailbox
        val seen = mutableSetOf<String>()
        for (child in orderedLevel(childrenOf(node))) {
            if (!seen.add(child.mailbox)) continue
            if (child.mailbox in nextAncestors) continue
            appendVisible(child, depth + 1, node.mailbox, expanded, nextAncestors, rows)
        }
    }

    private suspend fun childrenOf(node: LevelNode): List<LevelNode> {
        val unreadCounts = store.load().showUnreadCounts
        if (node.namespaceRoot) {
            return listLevelCached(node.namespacePrefix, null, unreadCounts)
                .map { entryNode(it, node.namespacePrefix) }
        }
        if (node.childrenComplete) {
            return node.cachedChildren.orEmpty()
        }
        val fetched = listLevelCached(node.namespacePrefix, node.mailbox, unreadCounts)
            .map { entryNode(it, node.namespacePrefix) }
        val seen = fetched.map { it.mailbox }.toSet()
        val extra = node.cachedChildren.orEmpty().filter { it.mailbox !in seen }
        return fetched + extra
    }

    private fun addChild(into: MutableList<LevelNode>, node: LevelNode) {
        if (into.none { it.mailbox == node.mailbox }) into += node
    }

    private fun orderedLevel(nodes: List<LevelNode>): List<LevelNode> =
        nodes.sortedWith(
            compareBy<LevelNode, String>(String.CASE_INSENSITIVE_ORDER) { it.leaf }.thenBy { it.mailbox },
        )

    private fun inboxChildPrefix(delimiter: Char): String? {
        if (delimiter == '\u0000') return null
        return "INBOX$delimiter"
    }

    private fun entryNode(entry: FolderEntry, prefix: String) = LevelNode(
        mailbox = entry.mailbox,
        leaf = entry.leaf,
        hasChildren = entry.hasChildren,
        namespacePrefix = prefix,
        namespaceRoot = false,
        cachedChildren = null,
        childrenComplete = false,
        specialUse = entry.specialUse,
        messages = entry.messages,
        unseen = entry.unseen,
        delimiter = entry.delimiter,
    )

    private fun namespaceNode(ns: Namespace) = LevelNode(
        mailbox = ns.prefix,
        leaf = ns.prefix,
        hasChildren = true,
        namespacePrefix = ns.prefix,
        namespaceRoot = true,
        cachedChildren = null,
        childrenComplete = false,
        delimiter = ns.delimiter,
    )

    private fun ancestorMailboxes(mailbox: String, namespaces: List<Namespace>): List<String> {
        val ns = owningNamespace(mailbox, namespaces) ?: return emptyList()
        if (mailbox.isEmpty() || mailbox == ns.prefix) return emptyList()
        val ancestors = mutableListOf<String>()
        val inboxMark = inboxChildPrefix(ns.delimiter)
        val inboxNamespace = inboxMark != null && ns.prefix == inboxMark
        if (inboxNamespace) {
            if (mailbox != "INBOX") ancestors += "INBOX"
        } else if (ns.prefix.isNotEmpty()) {
            ancestors += ns.prefix
        }
        val delim = ns.delimiter
        if (delim == '\u0000') return ancestors.filter { it != mailbox }
        val floor = when {
            inboxNamespace -> "INBOX"
            ns.prefix.endsWith(delim) -> ns.prefix.dropLast(1)
            else -> null
        }
        val parents = mutableListOf<String>()
        var rest = mailbox
        while (true) {
            val cut = rest.lastIndexOf(delim)
            if (cut <= 0) break
            val parent = rest.substring(0, cut)
            if (floor != null && parent == floor) break
            parents += parent
            rest = parent
        }
        parents.reverse()
        for (parent in parents) {
            if (parent != mailbox && parent !in ancestors) ancestors += parent
        }
        return ancestors
    }

    private fun owningNamespace(mailbox: String, namespaces: List<Namespace>): Namespace? {
        val prefixed = namespaces.filter { ns ->
            ns.prefix.isNotEmpty() && (mailbox == ns.prefix || mailbox.startsWith(ns.prefix))
        }
        if (prefixed.isNotEmpty()) return prefixed.maxBy { it.prefix.length }
        return namespaces.firstOrNull { it.prefix.isEmpty() } ?: namespaces.firstOrNull()
    }

    private data class LevelKey(val prefix: String, val parent: String)

    private data class LevelNode(
        val mailbox: String,
        val leaf: String,
        val hasChildren: Boolean,
        val namespacePrefix: String,
        val namespaceRoot: Boolean,
        val cachedChildren: List<LevelNode>?,
        val childrenComplete: Boolean,
        val specialUse: String? = null,
        val messages: Int? = null,
        val unseen: Int? = null,
        val delimiter: Char = '\u0000',
    )
}
