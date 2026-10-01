package org.dlang.liveimap.ui.folder

import org.dlang.liveimap.session.FolderEntry
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.Namespace
import org.dlang.liveimap.session.NamespaceKind
import org.dlang.liveimap.settings.SettingsStore

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
)

class FolderListModel(
    private val session: MailSession,
    private val store: SettingsStore,
) {
    suspend fun loadLevel(): List<FolderRow> {
        val expanded = store.load().expandedFolders
        val namespaces = session.namespaces()
        val personal = namespaces.filter { it.kind == NamespaceKind.Personal }
        val other = namespaces.filter { it.kind == NamespaceKind.Other }
        val shared = namespaces.filter { it.kind == NamespaceKind.Shared }

        val siblings = mutableListOf<LevelNode>()
        val inboxChildren = mutableListOf<LevelNode>()
        var inbox: LevelNode? = null
        var inboxPrefix: String? = null
        var sawInboxPrefixLevel = false
        var inboxFromSiblingLevel = false

        for (ns in personal) {
            val level = session.listLevel(ns.prefix, null)
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
            )
        } else {
            null
        }

        val roots = mutableListOf<LevelNode>()
        if (inboxNode != null) roots += inboxNode
        roots += siblings
        for (ns in other) roots += namespaceNode(ns)
        for (ns in shared) roots += namespaceNode(ns)

        val rows = mutableListOf<FolderRow>()
        for (root in roots) {
            appendVisible(root, 0, null, expanded, emptySet(), rows)
        }
        return rows
    }

    suspend fun toggleExpanded(mailbox: String) {
        val settings = store.load()
        val next = settings.expandedFolders.toMutableSet()
        if (!next.add(mailbox)) {
            next.remove(mailbox)
        }
        store.save(settings.copy(expandedFolders = next))
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
        )
        if (!showChildren) return
        val nextAncestors = ancestors + node.mailbox
        val seen = mutableSetOf<String>()
        for (child in childrenOf(node)) {
            if (!seen.add(child.mailbox)) continue
            if (child.mailbox in nextAncestors) continue
            appendVisible(child, depth + 1, node.mailbox, expanded, nextAncestors, rows)
        }
    }

    private suspend fun childrenOf(node: LevelNode): List<LevelNode> {
        if (node.namespaceRoot) {
            return session.listLevel(node.namespacePrefix, null).map { entryNode(it, node.namespacePrefix) }
        }
        if (node.childrenComplete) {
            return node.cachedChildren.orEmpty()
        }
        val fetched = session.listLevel(node.namespacePrefix, node.mailbox)
            .map { entryNode(it, node.namespacePrefix) }
        val seen = fetched.map { it.mailbox }.toSet()
        val extra = node.cachedChildren.orEmpty().filter { it.mailbox !in seen }
        return fetched + extra
    }

    private fun addChild(into: MutableList<LevelNode>, node: LevelNode) {
        if (into.none { it.mailbox == node.mailbox }) into += node
    }

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
    )

    private fun namespaceNode(ns: Namespace) = LevelNode(
        mailbox = ns.prefix,
        leaf = ns.prefix,
        hasChildren = true,
        namespacePrefix = ns.prefix,
        namespaceRoot = true,
        cachedChildren = null,
        childrenComplete = false,
    )

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
    )
}
