package org.dlang.liveimap.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    fun screensPileAfterTheMailPrefixAndARepeatDropsWhatIsAbove() {
        val mail = WindowStack.drawer("account")
            .pushFolderTree("root")
            .pushMailboxIndex("INBOX")
            .pushMessage("4", WindowLayerKind.FolderIndex)
        val settings = mail.pushScreen("settings")
        val editor = settings.pushScreen("settings/account/a/editor/server")
        assertEquals(
            listOf(
                WindowLayerKind.Drawer,
                WindowLayerKind.FolderTree,
                WindowLayerKind.FolderIndex,
                WindowLayerKind.Message,
                WindowLayerKind.Screen,
                WindowLayerKind.Screen,
            ),
            editor.kinds(),
        )
        assertEquals("settings", editor.layers[4].key)
        assertEquals("settings/account/a/editor/server", editor.layers[5].key)
        assertEquals(listOf("settings"), editor.pushScreen("settings").layers.filter { it.kind == WindowLayerKind.Screen }.map { it.key })
        assertEquals(1, editor.pushScreen("settings/account/a/editor/server").layers.count { it.key == "settings/account/a/editor/server" })
        assertEquals(editor, editor.pushScreen("settings/account/a/editor/server"))
    }

    @Test
    fun popRemovesTheTopScreenBeforeTheMailUnderIt() {
        val stack = WindowStack.drawer("account")
            .pushMailboxIndex("INBOX")
            .pushScreen("settings")
            .pushScreen("help")
        val popped = stack.pop()
        assertEquals(WindowLayer(WindowLayerKind.Screen, "settings"), popped.layers.last())
        assertEquals(WindowLayerKind.FolderIndex, popped.pop().layers.last().kind)
        assertEquals(popped.pop().pop(), popped.pop().pop().pop())
    }

    @Test
    fun choosingDropsScreenLayersAndMailJumpsStillDropWhatIsAbove() {
        val open = WindowStack.drawer("account")
            .pushFolderTree("root")
            .pushMailboxIndex("INBOX")
            .pushMessage("4", WindowLayerKind.FolderIndex)
            .pushScreen("settings")
            .pushScreen("filter")
        assertEquals(listOf(WindowLayerKind.Drawer), open.choose(StackChoice.Account, "other").kinds())
        assertEquals("other", open.choose(StackChoice.Account, "other").layers.single().key)
        assertEquals(listOf(WindowLayerKind.Drawer), open.choose(StackChoice.Favorite, "star").kinds())
        assertEquals(
            listOf(WindowLayerKind.Drawer, WindowLayerKind.FolderTree),
            open.choose(StackChoice.Folder, "Sent").kinds(),
        )
        val jumped = open.pushMailboxIndex("Archive")
        assertEquals(listOf(WindowLayerKind.Drawer, WindowLayerKind.FolderTree, WindowLayerKind.FolderIndex), jumped.kinds())
        assertEquals("Archive", jumped.layers.last().key)
        val replaced = open.pushMessage("9", WindowLayerKind.FolderIndex)
        assertEquals(WindowLayerKind.Message, replaced.layers.last().kind)
        assertEquals("9", replaced.layers.last().key)
        assertFalse(replaced.layers.any { it.kind == WindowLayerKind.Screen })
    }

    @Test
    fun revealingTheTreeKeepsAScreenAndAScreenCannotSitUnderMail() {
        val open = WindowStack.drawer("account").pushMailboxIndex("INBOX").pushScreen("about")
        val revealed = open.revealFolderTree("root")
        assertEquals(
            listOf(
                WindowLayerKind.Drawer,
                WindowLayerKind.FolderTree,
                WindowLayerKind.FolderIndex,
                WindowLayerKind.Screen,
            ),
            revealed.kinds(),
        )
        assertEquals("about", revealed.layers.last().key)
        assertEquals(open.dropMessage(), open)
        assertThrows(IllegalArgumentException::class.java) {
            WindowStack(
                listOf(
                    WindowLayer(WindowLayerKind.Drawer, "account"),
                    WindowLayer(WindowLayerKind.Screen, "settings"),
                    WindowLayer(WindowLayerKind.FolderIndex, "INBOX"),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WindowStack(
                listOf(
                    WindowLayer(WindowLayerKind.Drawer, "account"),
                    WindowLayer(WindowLayerKind.Screen, "settings"),
                    WindowLayer(WindowLayerKind.Screen, "settings"),
                ),
            )
        }
    }

    @Test
    fun removingAnAccountDropsItsScreensAndTheScreensAboveThem() {
        val open = WindowStack.drawer("account")
            .pushMailboxIndex("INBOX")
            .pushScreen("settings")
            .pushScreen("settings/account/a")
            .pushScreen("settings/account/a/editor/server")
            .pushScreen("settings/setup?accountId=a")
            .pushScreen("help")
        val left = open.dropAccountScreens("a")
        assertEquals(listOf("settings"), left.layers.filter { it.kind == WindowLayerKind.Screen }.map { it.key })
        assertEquals(WindowLayerKind.FolderIndex, left.layers[1].kind)
        val other = WindowStack.drawer("account")
            .pushScreen("settings/account/ab")
            .pushScreen("settings/account/a")
            .pushScreen("filters")
        assertEquals(
            listOf("settings/account/ab"),
            other.dropAccountScreens("a").layers.filter { it.kind == WindowLayerKind.Screen }.map { it.key },
        )
        val setup = WindowStack.drawer("account")
            .pushScreen("settings")
            .pushScreen("settings/setup?accountId=a")
            .pushScreen("about")
        assertEquals(
            listOf("settings"),
            setup.dropAccountScreens("a").layers.filter { it.kind == WindowLayerKind.Screen }.map { it.key },
        )
        assertEquals(open, open.dropAccountScreens("missing"))
        assertEquals(open, open.dropAccountScreens(""))
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
