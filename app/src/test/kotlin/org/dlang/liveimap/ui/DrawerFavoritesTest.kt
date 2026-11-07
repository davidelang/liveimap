package org.dlang.liveimap.ui

import org.dlang.liveimap.settings.FolderFavorite
import org.dlang.liveimap.settings.startupFavoriteOf
import org.dlang.liveimap.settings.withDefaultView
import org.dlang.liveimap.window.StartupFavorite
import org.dlang.liveimap.window.WindowStack
import org.dlang.liveimap.window.startupStack
import org.junit.Assert.assertEquals
import org.junit.Test

class DrawerFavoritesTest {
    private val work = FolderFavorite(false, "Work", '/', label = "Job")
    private fun keys(list: List<FolderFavorite>) = list.map { (if (it.node) "node:" else "box:") + it.mailbox + (if (it.label.isEmpty()) "" else "=" + it.label) }

    @Test fun aNewAccountGetsTheRowsTheDrawerShowedInTheSameOrder() {
        assertEquals(listOf("node:", "box:INBOX"), keys(withAccountDrawerRows(emptyList(), "", alreadyAdded = false)))
        assertEquals(listOf("node:", "box:INBOX", "box:Drafts"), keys(withAccountDrawerRows(emptyList(), "Drafts", false)))
    }

    @Test fun anExistingAccountGetsOnlyWhatItLacksAtTheEndAndKeepsItsOwnFavoritesAsTheyWere() {
        val mine = listOf(work, FolderFavorite(false, "INBOX", '/', label = "Home mail"))
        val next = withAccountDrawerRows(mine, "Drafts", false)
        assertEquals(listOf("box:Work=Job", "box:INBOX=Home mail", "node:", "box:Drafts"), keys(next))
    }

    @Test fun onceAddedADeletedRowIsNotAddedAgain() {
        val deletedInbox = withAccountDrawerRows(emptyList(), "", false).filterNot { it.mailbox == "INBOX" }
        assertEquals(listOf("node:"), keys(withAccountDrawerRows(deletedInbox, "", alreadyAdded = true)))
    }

    @Test fun anUntouchedAccountStartsOnItsFolderList() {
        val first = startupFavoriteOf(withAccountDrawerRows(emptyList(), "", false))!!
        val plan = startupStack("", "", "a", setOf("a"), "INBOX", StartupFavorite(first.node, first.mailbox))
        assertEquals(WindowStack.drawer("a").pushFolderTree("root"), plan.stack)
        // Tagging INBOX as the default view moves startup there.
        val tagged = withDefaultView(withAccountDrawerRows(emptyList(), "", false), FolderFavorite(false, "INBOX", '.'), true)
        val inbox = startupFavoriteOf(tagged)!!
        assertEquals(WindowStack.drawer("a").pushMailboxIndex("INBOX"),
            startupStack("", "", "a", setOf("a"), "INBOX", StartupFavorite(inbox.node, inbox.mailbox)).stack)
    }

    @Test fun theRowsKeepTheirOldNamesUntilRenamed() {
        val rows = withAccountDrawerRows(emptyList(), "", false)
        assertEquals("Personal", favoriteDrawerLabel(rows[0], "Personal"))
        assertEquals("Mail at home", favoriteDrawerLabel(rows[0].copy(label = "Mail at home"), "Personal"))
        assertEquals("Posteingang", favoriteDrawerLabel(rows[1], "Personal", inboxText = "Posteingang"))
        assertEquals("Ten", favoriteDrawerLabel(rows[1].copy(label = "Ten"), "Personal", inboxText = "Posteingang"))
    }
}
