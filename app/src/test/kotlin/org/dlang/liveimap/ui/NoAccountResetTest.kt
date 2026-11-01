package org.dlang.liveimap.ui

import org.dlang.liveimap.settings.AccountChoice
import org.dlang.liveimap.ui.folder.FolderAccountBody
import org.dlang.liveimap.ui.folder.folderAccountBody
import org.dlang.liveimap.ui.folder.folderScreenAccountId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoAccountResetTest {
    @Test
    fun emptyListOnly() {
        assertTrue(resetToNoAccount(emptyList()))
        assertFalse(resetToNoAccount(listOf(AccountChoice("a", "A", drawer = true))))
    }

    @Test
    fun drawerStaysOffSettingsHelpAndAbout() {
        val accounts = listOf(AccountChoice("a", "A", drawer = true))
        val hidden = listOf(
            "settings",
            "settings/setup",
            "settings/global",
            "settings/accounts",
            "help",
            "about",
            "about/licenses",
        )
        for (route in hidden) {
            assertFalse(openDrawerOnNoAccount(route, accounts))
            assertFalse(openDrawerOnNoAccount(route, emptyList()))
        }
    }

    @Test
    fun folderListOpensOnlyWhenAnAccountExists() {
        val accounts = listOf(AccountChoice("a", "A", drawer = true))
        assertTrue(openDrawerOnNoAccount("folders", accounts))
        assertFalse(openDrawerOnNoAccount("folders", emptyList()))
        assertTrue(openDrawerOnNoAccount("index/{mailbox}", accounts))
        assertFalse(openDrawerOnNoAccount("index/{mailbox}", emptyList()))
    }

    @Test
    fun savedAccountIdAbsentFromTheListIsDropped() {
        assertEquals("a", folderScreenAccountId("a", setOf("a", "b"), "b"))
        assertEquals("b", folderScreenAccountId("gone", setOf("b"), "b"))
        assertNull(folderScreenAccountId("gone", emptySet(), null))
        assertEquals("b", folderScreenAccountId(null, setOf("b"), "b"))
        assertNull(folderScreenAccountId(null, emptySet(), null))
    }

    @Test
    fun createAccountOnlyAfterRestoreFinishesEmpty() {
        assertEquals(FolderAccountBody.Pending, folderAccountBody(false, emptySet()))
        assertEquals(FolderAccountBody.Account, folderAccountBody(false, setOf("a")))
        assertEquals(FolderAccountBody.Account, folderAccountBody(true, setOf("a")))
        assertEquals(FolderAccountBody.Create, folderAccountBody(true, emptySet()))
    }
}
