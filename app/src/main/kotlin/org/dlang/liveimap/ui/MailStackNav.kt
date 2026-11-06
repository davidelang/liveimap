package org.dlang.liveimap.ui

import org.dlang.liveimap.ui.index.SearchScope
import org.dlang.liveimap.window.StackChoice
import org.dlang.liveimap.window.WindowLayerKind
import org.dlang.liveimap.window.WindowStack

/** A committed search, read once by the search-index route. Not the folder index's query. */
internal object PendingSearch {
    var query: String = ""
    var scope: SearchScope = SearchScope.Current
}

/** Drawer jump to a mailbox: no folder-tree layer. */
internal fun drawerMailbox(stack: WindowStack, mailbox: String): WindowStack =
    WindowStack(listOf(stack.layers.first())).pushMailboxIndex(mailbox)

/** Opening a folder from the tree keeps the tree and replaces whatever was above it. */
internal fun folderFromTree(stack: WindowStack, mailbox: String): WindowStack =
    stack.pushMailboxIndex(mailbox)

internal fun revealTree(stack: WindowStack, key: String): WindowStack = stack.revealFolderTree(key)

internal fun chooseAccountMailbox(stack: WindowStack, accountKey: String, mailbox: String): WindowStack =
    stack.choose(StackChoice.Account, accountKey).pushMailboxIndex(mailbox)

internal fun chooseAccountTree(stack: WindowStack, accountKey: String, key: String): WindowStack =
    stack.choose(StackChoice.Account, accountKey).pushFolderTree(key)

internal fun chooseFavoriteMailbox(stack: WindowStack, mailbox: String): WindowStack =
    stack.choose(StackChoice.Favorite, mailbox).pushMailboxIndex(mailbox)

internal fun chooseFolderNode(stack: WindowStack, key: String): WindowStack =
    stack.choose(StackChoice.Folder, key)

/** An index or a message is open, so revealing the tree must leave those layers. */
internal fun mailCoversTree(stack: WindowStack): Boolean = stack.layers.any {
    it.kind == WindowLayerKind.FolderIndex ||
        it.kind == WindowLayerKind.SearchIndex ||
        it.kind == WindowLayerKind.Message
}

internal fun withDrawerKey(stack: WindowStack, accountKey: String): WindowStack {
    if (accountKey.isEmpty() || stack.layers.first().key == accountKey) return stack
    val layers = stack.layers.toMutableList()
    layers[0] = layers[0].copy(key = accountKey)
    return WindowStack(layers)
}

/** The index a message replaces. A message already on the stack keeps the index under it. */
internal fun messageSource(stack: WindowStack): WindowLayerKind {
    val layers = stack.layers
    val messageAt = layers.indexOfLast { it.kind == WindowLayerKind.Message }
    if (messageAt > 0) {
        val under = layers[messageAt - 1].kind
        if (under == WindowLayerKind.SearchIndex || under == WindowLayerKind.FolderIndex) return under
    }
    val mailTop = layers.lastOrNull { it.kind != WindowLayerKind.Screen }?.kind
    return if (mailTop == WindowLayerKind.SearchIndex) {
        WindowLayerKind.SearchIndex
    } else {
        WindowLayerKind.FolderIndex
    }
}
