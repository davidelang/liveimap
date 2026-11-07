package org.dlang.liveimap.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupStackTest {
    private val accounts = setOf("a", "b")
    private val phone = LayoutRecord(LayoutNode.Panel(LayoutPanel("main")), UnusedPanel.Collapse, 0, 1)

    @Test fun theDefaultIsTheDefaultFavoriteAndWithoutOneItIsReported() {
        val work = startupStack("", "", "a", accounts, "INBOX", StartupFavorite(false, "Work"))
        assertNull(work.problem)
        assertEquals(WindowStack.drawer("a").pushMailboxIndex("Work"), work.stack)
        // A fresh account has no favorites: the default reports it and shows the drawer; it does not pick a folder.
        val fresh = startupStack("", "", "a", accounts, "INBOX", null)
        assertEquals(StartupProblem.NoFavorite, fresh.problem)
        assertEquals(WindowStack.drawer("a"), fresh.stack)
    }

    @Test fun theFolderListOptionIsTheFolderListOverTheDrawer() {
        val plan = startupStack("folderList", "", "a", accounts, "INBOX")
        assertNull(plan.problem)
        assertEquals(WindowStack.drawer("a").pushFolderTree("root"), plan.stack)
        // Like any stack: the phone shows the folder list, Back pops to the drawer, and the drawer is not in a panel.
        assertEquals(WindowLayerKind.FolderTree, fill(phone, plan.stack).slots.single().layer?.kind)
        assertEquals(listOf(WindowLayerKind.Drawer), back(false, null, plan.stack).stack.layers.map { it.kind })
        assertTrue(!drawerInPanel(fill(phone, plan.stack)))
    }

    @Test fun aFolderInAnotherAccountSitsDirectlyOnThatAccountsDrawer() {
        val plan = startupStack("folder", "b", "a", accounts, "Lists/dev")
        assertEquals("b", plan.accountId)
        assertEquals(listOf(WindowLayer(WindowLayerKind.Drawer, "b"), WindowLayer(WindowLayerKind.FolderIndex, "Lists/dev")), plan.stack.layers)
        // A drawer pick from there replaces the folder, as from any other screen.
        val picked = drawerStep(DrawerView(plan.stack, false), DrawerInput.Pick(listOf(WindowLayer(WindowLayerKind.Screen, "settings"))), false)
        assertEquals(listOf(WindowLayerKind.Drawer, WindowLayerKind.Screen), picked.stack.layers.map { it.kind })
    }

    @Test fun theDrawerAloneShowsTheDrawer() {
        val plan = startupStack("drawer", "", "a", accounts, "INBOX")
        assertEquals(WindowStack.drawer("a"), plan.stack)
        assertTrue(drawerInPanel(fill(phone, plan.stack)))
    }

    @Test fun aDeletedAccountIsReportedAndTheOpenAccountIsKept() {
        val plan = startupStack("folder", "gone", "a", accounts, "INBOX")
        assertEquals(StartupProblem.AccountMissing, plan.problem)
        assertEquals("a", plan.accountId)
        assertEquals(WindowStack.drawer("a"), plan.stack)
    }

    @Test fun aBlankFolderOrUnknownViewIsReported() {
        assertEquals(StartupProblem.FolderMissing, startupStack("folder", "", "a", accounts, " ").problem)
        assertEquals(StartupProblem.UnknownView, startupStack("lastView", "", "a", accounts, "INBOX").problem)
        assertEquals(WindowStack.drawer("a"), startupStack("lastView", "", "a", accounts, "INBOX").stack)
    }

    @Test fun theFirstFavoriteOpensLikeADrawerPickOfIt() {
        val folder = startupStack("favorite", "", "a", accounts, "INBOX", StartupFavorite(node = false, mailbox = "Work"))
        assertNull(folder.problem)
        assertEquals(drawerMailboxLike("a", "Work"), folder.stack)
        val node = startupStack("favorite", "", "a", accounts, "INBOX", StartupFavorite(node = true, mailbox = "Lists"))
        assertEquals(listOf(WindowLayer(WindowLayerKind.Drawer, "a"), WindowLayer(WindowLayerKind.FolderTree, "Lists")), node.stack.layers)
    }

    @Test fun noFavoriteOrABlankOneIsReported() {
        val none = startupStack("favorite", "", "a", accounts, "INBOX", null)
        assertEquals(StartupProblem.NoFavorite, none.problem)
        assertEquals(WindowStack.drawer("a"), none.stack)
        assertEquals(StartupProblem.FavoriteInvalid,
            startupStack("favorite", "", "a", accounts, "INBOX", StartupFavorite(false, "")).problem)
    }

    private fun drawerMailboxLike(account: String, box: String) = WindowStack.drawer(account).pushMailboxIndex(box)
}
