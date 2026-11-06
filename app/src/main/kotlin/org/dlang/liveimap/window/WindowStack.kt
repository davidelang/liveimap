package org.dlang.liveimap.window

/** Bottom to top. A message sits on the index it was opened from. The drawer is never removed. */
enum class WindowLayerKind {
    Drawer,
    FolderTree,
    FolderIndex,
    SearchIndex,
    Message,
}

data class WindowLayer(val kind: WindowLayerKind, val key: String)

enum class StackChoice { Account, Favorite, Folder }

/**
 * One window's layers. Not stored. Screen code does not read this yet.
 * Order is fixed: drawer, then an optional folder tree, folder index, search index, and one message.
 */
data class WindowStack(val layers: List<WindowLayer>) {
    init {
        require(layers.isNotEmpty())
        require(layers.first().kind == WindowLayerKind.Drawer)
        var previous = -1
        for (layer in layers) {
            val rank = layer.kind.ordinal
            require(rank > previous)
            previous = rank
        }
        val kinds = layers.map { it.kind }
        if (WindowLayerKind.SearchIndex in kinds) require(WindowLayerKind.FolderIndex in kinds)
        if (WindowLayerKind.Message in kinds) {
            val below = layers[layers.lastIndex - 1].kind
            require(below == WindowLayerKind.FolderIndex || below == WindowLayerKind.SearchIndex)
        }
    }

    /** Showing the drawer does not drop the index or the message. */
    fun revealDrawer(): WindowStack = this

    /** Inserts the folder tree above the drawer when it is absent. Higher layers stay. */
    fun revealFolderTree(key: String): WindowStack = pushFolderTree(key)

    fun pushFolderTree(key: String): WindowStack {
        val existing = layers.indexOfFirst { it.kind == WindowLayerKind.FolderTree }
        if (existing >= 0) {
            if (layers[existing].key == key) return this
            val copy = layers.toMutableList()
            copy[existing] = WindowLayer(WindowLayerKind.FolderTree, key)
            return WindowStack(copy)
        }
        return WindowStack(
            listOf(layers.first(), WindowLayer(WindowLayerKind.FolderTree, key)) + layers.drop(1),
        )
    }

    /** Does not add a folder-tree layer. An existing tree stays, and this index replaces whatever was above it. */
    fun pushMailboxIndex(key: String): WindowStack {
        val treeAt = layers.indexOfFirst { it.kind == WindowLayerKind.FolderTree }
        val cut = if (treeAt >= 0) treeAt else 0
        return WindowStack(layers.take(cut + 1) + WindowLayer(WindowLayerKind.FolderIndex, key))
    }

    /** One search index, directly above the folder index. A message that was above that index is dropped. */
    fun pushSearchIndex(key: String): WindowStack {
        val indexAt = layers.indexOfFirst { it.kind == WindowLayerKind.FolderIndex }
        require(indexAt >= 0)
        return WindowStack(layers.take(indexAt + 1) + WindowLayer(WindowLayerKind.SearchIndex, key))
    }

    /**
     * One message layer, directly above [from]. A second message replaces that layer.
     * [from] is the folder index or the search index.
     */
    fun pushMessage(uid: String, from: WindowLayerKind): WindowStack {
        require(from == WindowLayerKind.FolderIndex || from == WindowLayerKind.SearchIndex)
        val sourceAt = layers.indexOfFirst { it.kind == from }
        require(sourceAt >= 0)
        return WindowStack(layers.take(sourceAt + 1) + WindowLayer(WindowLayerKind.Message, uid))
    }

    /** Drops every layer above the chosen account, favorite, or folder. The choice itself stays. */
    fun choose(choice: StackChoice, key: String): WindowStack = when (choice) {
        StackChoice.Account -> WindowStack(listOf(layers.first().copy(key = key)))
        StackChoice.Favorite -> WindowStack(listOf(layers.first()))
        StackChoice.Folder -> WindowStack(
            listOf(layers.first(), WindowLayer(WindowLayerKind.FolderTree, key)),
        )
    }

    /** Removes only the top layer. The drawer stays when it is the only layer. */
    fun pop(): WindowStack = if (layers.size == 1) this else WindowStack(layers.dropLast(1))

    companion object {
        fun drawer(accountKey: String) = WindowStack(listOf(WindowLayer(WindowLayerKind.Drawer, accountKey)))
    }
}
