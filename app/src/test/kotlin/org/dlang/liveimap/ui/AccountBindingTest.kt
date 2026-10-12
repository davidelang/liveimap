package org.dlang.liveimap.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F121: only the drawer chooses the active account; every screen stays bound to its own account. */
class AccountBindingTest {
    private fun src(path: String) = File("src/main/kotlin/org/dlang/liveimap/$path").readText()

    @Test
    fun settingsNeverSwitchesTheActiveAccount() {
        val settings = src("settings/SettingsScreen.kt")
        assertFalse(settings.contains("store.selectAccount("))
        assertTrue(settings.contains("editingId = id"))
        val store = src("accounts/AccountStore.kt")
        assertTrue("a new account is made active only when it is the first", store.contains("if (accounts.isEmpty()) writeSelectedId(accountId)"))
    }

    @Test
    fun boundStoreActsOnlyOnItsAccount() {
        val store = src("settings/SettingsStore.kt")
        assertTrue(store.contains("if (boundAccountId != null) return // F121"))
        assertTrue(src("accounts/AccountStore.kt").contains("?: throw IllegalStateException(\"account \$target no longer exists\")"))
    }

    @Test
    fun screensKeepTheirAccountAcrossTheBackStack() {
        for (f in listOf(
            "ui/index/MessageIndexScreen.kt", "ui/reader/MessageReaderScreen.kt", "ui/folder/FolderListScreen.kt",
            "ui/compose/ComposeScreen.kt", "ui/compose/UnsentScreen.kt", "ui/contacts/AddressBookPicker.kt",
            "ui/contacts/ContactCopyScreen.kt", "ui/search/AdvancedSearchScreen.kt",
        )) {
            val text = src(f)
            assertTrue(f, text.contains("var accountId by rememberSaveable"))
            assertTrue(f, text.contains("if (accountId == null) accountId = store.chosenAccountId()"))
        }
    }

    @Test
    fun longPressManagesTheFavoriteInItsOwnAccountAndShowsWhere() {
        val nav = src("ui/NavHost.kt")
        val edit = nav.substringAfter("fun editAccountFavorite(").substringBefore("val drawerSheet")
        assertFalse(edit.contains("selectAccount"))
        assertEquals("dlang@lang.hm on mail.lang.hm\nINBOX.lists.debian", favoriteWhereText(FavoriteWhere("dlang@lang.hm", "mail.lang.hm", "INBOX.lists.debian")))
        assertEquals("(top level)", favoriteWhereText(FavoriteWhere("", "", "")))
    }
}
