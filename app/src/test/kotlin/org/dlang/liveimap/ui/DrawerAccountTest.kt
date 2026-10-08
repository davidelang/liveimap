package org.dlang.liveimap.ui

import org.dlang.liveimap.settings.AccountChoice
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.DrawerAccount
import org.dlang.liveimap.settings.FolderFavorite
import org.dlang.liveimap.settings.accountDrawerFolders
import org.dlang.liveimap.settings.encode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerAccountTest {
    @Test
    fun oneChoiceIsEmpty() {
        val rows = drawerInboxes(listOf(AccountChoice(id = "a", name = "Ada", chosen = true)))
        assertTrue(rows.isEmpty())
    }

    @Test
    fun twoChoicesKeepIdOrder() {
        val rows = drawerInboxes(
            listOf(
                AccountChoice(id = "a", name = "Ada", chosen = true),
                AccountChoice(id = "b", name = "Bea", chosen = false),
            ),
        )
        assertEquals(listOf("a", "b"), rows.map { it.id })
    }

    @Test
    fun oneDrawerAccountIsEmpty() {
        val rows = drawerBlocks(
            listOf(
                DrawerAccount(
                    id = "a",
                    name = "Ada",
                    chosen = true,
                    postponedMailbox = "drafts",
                    favorites = listOf(FolderFavorite(false, "INBOX.a", '/')),
                ),
            ),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun twoDrawerAccountsKeepOwnFolders() {
        val ada = FolderFavorite(false, "INBOX.a", '/')
        val bea = FolderFavorite(true, "INBOX.b", '/')
        val rows = drawerBlocks(
            listOf(
                DrawerAccount(
                    id = "a",
                    name = "Ada",
                    chosen = true,
                    postponedMailbox = "drafts",
                    favorites = listOf(ada),
                ),
                DrawerAccount(
                    id = "b",
                    name = "Bea",
                    chosen = false,
                    postponedMailbox = "later",
                    favorites = listOf(bea),
                ),
            ),
        )
        assertEquals(listOf("a", "b"), rows.map { it.id })
        assertEquals("drafts", rows[0].postponedMailbox)
        assertEquals(listOf(ada), rows[0].favorites)
        assertEquals("later", rows[1].postponedMailbox)
        assertEquals(listOf(bea), rows[1].favorites)
    }

    @Test
    fun missingPreferenceHasNoFolders() {
        val folders = accountDrawerFolders(null)
        assertEquals("", folders.postponedMailbox)
        assertTrue(folders.favorites.isEmpty())
    }

    @Test
    fun encodedPreferenceKeepsFolders() {
        val favorite = FolderFavorite(false, "INBOX.a", '/')
        val encoded = AccountSettings(
            postponedMailbox = "drafts",
            favorites = listOf(favorite),
        ).encode()
        val folders = accountDrawerFolders(encoded)
        assertEquals("drafts", folders.postponedMailbox)
        assertEquals(listOf(favorite), folders.favorites)
    }
}
