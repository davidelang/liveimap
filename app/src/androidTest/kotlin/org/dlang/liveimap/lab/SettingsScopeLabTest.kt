package org.dlang.liveimap.lab

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.accounts.AccountStore
import org.dlang.liveimap.accounts.accountSettingsStoredKeys
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.editors.openingAccount
import org.dlang.liveimap.settings.globalSettingsStoredKeys
import org.dlang.liveimap.settings.decodeSettings
import org.dlang.liveimap.settings.readGlobalSettings
import org.dlang.liveimap.settings.saveGlobal
import org.dlang.liveimap.ui.toolbar.ToolbarStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsScopeLabTest {
    @Test
    fun uiIsSharedAndAccountValuesStayApart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AccountStore.shared(context)
        val created = ArrayList<String>()
        try {
            val first = createLabAccount(context, Settings(), "")
            val second = createLabAccount(context, Settings(), "")
            created += first
            created += second
            store.selectAccount(first)
            val onFirst = store.load().copy(friendlyName = "first-name")
            store.update { onFirst }
            ToolbarStore.replace(ToolbarStore.record.copy(indexBar = ToolbarStore.record.indexBar.copy(rows = 3)))
            ToolbarStore.save(context)
            saveGlobal(context, "indexPageSize", "77")
            store.selectAccount(second)
            val onSecond = store.load().copy(friendlyName = "second-name")
            store.update { onSecond }
            assertEquals(3, ToolbarStore.indexRows())
            assertEquals(77L, decodeSettings(readGlobalSettings(context)).indexPageSize)
            assertEquals("second-name", store.load().friendlyName)
            store.selectAccount(first)
            assertEquals("first-name", store.load().friendlyName)
            assertEquals(77L, decodeSettings(readGlobalSettings(context)).indexPageSize)
            assertEquals(setOf("value"), globalSettingsStoredKeys(context))
            val keys = accountSettingsStoredKeys(context)
            assertTrue(keys.contains(first))
            assertTrue(keys.contains(second))
            assertEquals(setOf(first, second), keys)
            val listed = store.listAccounts()
            val one = listed.filter { it.id == first }
            assertEquals(first, openingAccount(one, "drawer", second)?.id)
            val drawerSecond = listed.map { it.copy(drawer = it.id == second) }
            assertEquals(second, openingAccount(drawerSecond, "drawer", first)?.id)
            assertEquals(first, openingAccount(drawerSecond, "lastEdited", first)?.id)
        } finally {
            for (id in created) store.removeAccount(id)
        }
    }
}
