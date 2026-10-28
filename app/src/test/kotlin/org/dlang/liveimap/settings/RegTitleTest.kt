package org.dlang.liveimap.settings

import com.davidlang.liveimap.R
import org.junit.Assert.assertEquals
import org.junit.Test

class RegTitleTest {
    @Test
    fun regTitleFindsTheLeafResource() {
        assertEquals(R.string.settings_imap_host, SettingsTreeGenerated.regTitle("imapHost"))
        assertEquals(R.string.setting_indexPageSize_title, SettingsTreeGenerated.regTitle("indexPageSize"))
        assertEquals(R.string.settings_group_server_title, SettingsTreeGenerated.titleOf("server"))
    }
}
