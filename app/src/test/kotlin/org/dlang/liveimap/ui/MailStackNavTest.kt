package org.dlang.liveimap.ui

import org.dlang.liveimap.window.WindowLayerKind
import org.dlang.liveimap.window.WindowStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MailStackNavTest {
    @Test
    fun drawerMailboxPushesAnIndexAndNoFolderTree() {
        val stack = drawerMailbox(WindowStack.drawer("account").pushFolderTree("root"), "INBOX")
        assertEquals(listOf(WindowLayerKind.Drawer, WindowLayerKind.FolderIndex), stack.kinds())
        assertEquals("INBOX", stack.layers.last().key)
    }

    @Test
    fun openingAFolderKeepsTheTreeUnderTheIndex() {
        val stack = folderFromTree(WindowStack.drawer("account").pushFolderTree("root"), "Archive")
        assertEquals(
            listOf(WindowLayerKind.Drawer, WindowLayerKind.FolderTree, WindowLayerKind.FolderIndex),
            stack.kinds(),
        )
        assertEquals(listOf(WindowLayerKind.Drawer, WindowLayerKind.FolderTree), stack.pop().kinds())
    }

    @Test
    fun revealingTheTreeLeavesTheIndexAndTheMessage() {
        val open = WindowStack.drawer("account")
            .pushMailboxIndex("INBOX")
            .pushMessage("4", WindowLayerKind.FolderIndex)
        assertTrue(mailCoversTree(open))
        val revealed = revealTree(open, "root")
        assertEquals(
            listOf(
                WindowLayerKind.Drawer,
                WindowLayerKind.FolderTree,
                WindowLayerKind.FolderIndex,
                WindowLayerKind.Message,
            ),
            revealed.kinds(),
        )
        assertEquals("4", revealed.layers.last().key)
    }

    @Test
    fun choosingAccountFavoriteOrFolderDropsWhatIsAbove() {
        val open = WindowStack.drawer("account")
            .pushFolderTree("root")
            .pushMailboxIndex("INBOX")
            .pushMessage("4", WindowLayerKind.FolderIndex)
        assertEquals(listOf(WindowLayerKind.Drawer), chooseAccountMailbox(open, "other", "INBOX").pop().kinds())
        assertEquals("other", chooseAccountMailbox(open, "other", "INBOX").layers.first().key)
        assertEquals(listOf(WindowLayerKind.Drawer, WindowLayerKind.FolderIndex), chooseFavoriteMailbox(open, "Sent").kinds())
        assertEquals(listOf(WindowLayerKind.Drawer, WindowLayerKind.FolderTree), chooseFolderNode(open, "Sent").kinds())
        assertFalse(mailCoversTree(chooseFolderNode(open, "Sent")))
    }

    @Test
    fun searchAndASecondMessageStayOneLayerEach() {
        val folder = WindowStack.drawer("account").pushMailboxIndex("INBOX")
        val search = folder.pushSearchIndex("from:a")
        val message = search.pushMessage("9", messageSource(search))
        assertEquals(WindowLayerKind.SearchIndex, messageSource(search))
        assertEquals(
            listOf(
                WindowLayerKind.Drawer,
                WindowLayerKind.FolderIndex,
                WindowLayerKind.SearchIndex,
                WindowLayerKind.Message,
            ),
            message.kinds(),
        )
        val replaced = message.pushMessage("10", messageSource(message))
        assertEquals("10", replaced.layers.last().key)
        assertEquals(1, replaced.layers.count { it.kind == WindowLayerKind.Message })
        assertEquals(listOf(WindowLayerKind.Drawer, WindowLayerKind.FolderIndex), search.pop().pop().kinds())
    }

    private fun WindowStack.kinds(): List<WindowLayerKind> = layers.map { it.kind }
}
