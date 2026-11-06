package org.dlang.liveimap.window

/** Bottom to top. A message sits on the index it was opened from. The drawer is never removed. Screens pile after the mail prefix. */
enum class WindowLayerKind {
    Drawer,
    FolderTree,
    FolderIndex,
    SearchIndex,
    Message,
    Screen,
}

data class WindowLayer(val kind: WindowLayerKind, val key: String)

enum class StackChoice { Account, Favorite, Folder }

/**
 * One window's layers. Not stored.
 * Mail order is fixed: drawer, then an optional folder tree, folder index, search index, and one message.
 * Screen layers are a pile after that prefix. Each screen key appears once.
 */
data class WindowStack(val layers: List<WindowLayer>) {
    init {
        require(layers.isNotEmpty())
        require(layers.first().kind == WindowLayerKind.Drawer)
        var previous = -1
        var screens = false
        val screenKeys = HashSet<String>()
        for (layer in layers) {
            if (layer.kind == WindowLayerKind.Screen) {
                screens = true
                require(screenKeys.add(layer.key))
                continue
            }
            require(!screens)
            val rank = layer.kind.ordinal
            require(rank > previous)
            previous = rank
        }
        val kinds = layers.map { it.kind }
        if (WindowLayerKind.SearchIndex in kinds) require(WindowLayerKind.FolderIndex in kinds)
        val messageAt = layers.indexOfFirst { it.kind == WindowLayerKind.Message }
        if (messageAt >= 0) {
            require(layers.indexOfLast { it.kind == WindowLayerKind.Message } == messageAt)
            val below = layers[messageAt - 1].kind
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

    /**
     * Appends a screen after the mail prefix.
     * A key already in the pile drops the screens above it and does not add a second copy.
     */
    fun pushScreen(key: String): WindowStack {
        val existing = layers.indexOfFirst { it.kind == WindowLayerKind.Screen && it.key == key }
        if (existing >= 0) return WindowStack(layers.take(existing + 1))
        return WindowStack(layers + WindowLayer(WindowLayerKind.Screen, key))
    }

    /** Removes the message layer wherever it sits. Screens above it stay. */
    fun dropMessage(): WindowStack {
        if (layers.none { it.kind == WindowLayerKind.Message }) return this
        return WindowStack(layers.filter { it.kind != WindowLayerKind.Message })
    }

    /** Removes only the top layer. The drawer stays when it is the only layer. A screen pops before the mail under it. */
    fun pop(): WindowStack = if (layers.size == 1) this else WindowStack(layers.dropLast(1))

    companion object {
        fun drawer(accountKey: String) = WindowStack(listOf(WindowLayer(WindowLayerKind.Drawer, accountKey)))
    }
}
