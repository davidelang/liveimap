package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ScopeRoutingTest {
    @Before
    fun tree() {
        installSettingsTree(settingsTreeText())
    }

    @Test
    fun scopePicksTheBlob() {
        assertEquals(SettingScope.Ui, settingsRegistry.getValue("indexPageSize").scope)
        assertEquals(SettingScope.Ui, settingsRegistry.getValue("toolbars").scope)
        assertEquals(SettingScope.Account, settingsRegistry.getValue("imapHost").scope)
        assertEquals(true, settingsRegistry.getValue("imapHost").server)
        assertEquals(SettingScope.Account, settingsRegistry.getValue("friendlyName").scope)
        val account = AccountSettings(friendlyName = "Ada")
        assertEquals("Ada", account.getSetting("friendlyName"))
        assertEquals("indexPageSize=40", updateGlobalRecord("", "indexPageSize", "40"))
    }
}
