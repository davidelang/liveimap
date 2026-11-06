package org.dlang.liveimap.window

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowStackTest {
    @Test
    fun drawerStaysAtTheBottom() {
        val stack = WindowStack.drawer("account")
        assertEquals(listOf(WindowLayerKind.Drawer), stack.kinds())
        assertEquals(stack, stack.pop())
        assertEquals(stack, stack.revealDrawer())
    }

    @Test
    fun mailboxIndexDoesNotPushAFolderTree() {
        val stack = WindowStack.drawer("account").pushMailboxIndex("INBOX")
        assertEquals(listOf(WindowLayerKind.Drawer, WindowLayerKind.FolderIndex), stack.kinds())
        assertEquals("INBOX", stack.layers.last().key)
    }

    @Test
    fun folderTreeThenIndexKeepsBoth() {
        val stack = WindowStack.drawer("account")
            .pushFolderTree("root")
            .pushMailboxIndex("Archive")
        assertEquals(
            listOf(WindowLayerKind.Drawer, WindowLayerKind.FolderTree, WindowLayerKind.FolderIndex),
            stack.kinds(),
        )
        assertEquals("root", stack.layers[1].key)
        assertEquals("Archive", stack.layers[2].key)
    }

    @Test
    fun searchIndexPushesAboveTheFolderIndex() {
        val stack = WindowStack.drawer("account")
            .pushMailboxIndex("INBOX")
            .pushSearchIndex("query")
        assertEquals(
            listOf(WindowLayerKind.Drawer, WindowLayerKind.FolderIndex, WindowLayerKind.SearchIndex),
            stack.kinds(),
        )
        assertEquals("INBOX", stack.layers[1].key)
        assertEquals("query", stack.layers[2].key)
    }

    @Test
    fun messagePushesAboveItsIndexAndASecondMessageReplacesIt() {
        val fromFolder = WindowStack.drawer("account")
            .pushMailboxIndex("INBOX")
            .pushMessage("1", WindowLayerKind.FolderIndex)
        assertEquals(
            listOf(WindowLayerKind.Drawer, WindowLayerKind.FolderIndex, WindowLayerKind.Message),
            fromFolder.kinds(),
        )
        val replaced = fromFolder.pushMessage("2", WindowLayerKind.FolderIndex)
        assertEquals(fromFolder.kinds(), replaced.kinds())
        assertEquals("2", replaced.layers.last().key)
        assertEquals(1, replaced.layers.count { it.kind == WindowLayerKind.Message })

        val fromSearch = WindowStack.drawer("account")
            .pushMailboxIndex("INBOX")
            .pushSearchIndex("query")
            .pushMessage("9", WindowLayerKind.SearchIndex)
        assertEquals(
            listOf(
                WindowLayerKind.Drawer,
                WindowLayerKind.FolderIndex,
                WindowLayerKind.SearchIndex,
                WindowLayerKind.Message,
            ),
            fromSearch.kinds(),
        )
        assertEquals("9", fromSearch.pushMessage("10", WindowLayerKind.SearchIndex).layers.last().key)
    }

    @Test
    fun revealingDrawerOrFolderTreeLeavesIndexAndMessage() {
        val open = WindowStack.drawer("account")
            .pushMailboxIndex("INBOX")
            .pushMessage("4", WindowLayerKind.FolderIndex)
        assertEquals(open, open.revealDrawer())
        val revealed = open.revealFolderTree("root")
        assertEquals(
            listOf(
                WindowLayerKind.Drawer,
                WindowLayerKind.FolderTree,
                WindowLayerKind.FolderIndex,
                WindowLayerKind.Message,
            ),
            revealed.kinds(),
        )
        assertEquals("INBOX", revealed.layers[2].key)
        assertEquals("4", revealed.layers[3].key)
        assertEquals(revealed, revealed.revealFolderTree("root"))
    }

    @Test
    fun choosingDropsEveryLayerAboveThatChoice() {
        val open = WindowStack.drawer("account")
            .pushFolderTree("root")
            .pushMailboxIndex("INBOX")
            .pushMessage("4", WindowLayerKind.FolderIndex)
        val account = open.choose(StackChoice.Account, "other")
        assertEquals(listOf(WindowLayerKind.Drawer), account.kinds())
        assertEquals("other", account.layers.single().key)

        val favorite = open.choose(StackChoice.Favorite, "star")
        assertEquals(listOf(WindowLayer(WindowLayerKind.Drawer, "account")), favorite.layers)

        val folder = open.choose(StackChoice.Folder, "Sent")
        assertEquals(
            listOf(WindowLayer(WindowLayerKind.Drawer, "account"), WindowLayer(WindowLayerKind.FolderTree, "Sent")),
            folder.layers,
        )
    }

    @Test
    fun popRemovesOnlyTheTopLayer() {
        val stack = WindowStack.drawer("account")
            .pushMailboxIndex("INBOX")
            .pushMessage("4", WindowLayerKind.FolderIndex)
        val popped = stack.pop()
        assertEquals(listOf(WindowLayerKind.Drawer, WindowLayerKind.FolderIndex), popped.kinds())
        assertEquals(listOf(WindowLayerKind.Drawer), popped.pop().kinds())
        assertEquals(popped.pop(), popped.pop().pop())
    }

    private fun WindowStack.kinds(): List<WindowLayerKind> = layers.map { it.kind }
}
