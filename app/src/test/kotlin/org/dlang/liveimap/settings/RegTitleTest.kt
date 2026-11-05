package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegTitleTest {
    @Test
    fun regTitleFindsTheLeafResource() {
        assertEquals(SettingsTreeGenerated.resource("settings_imap_host"), SettingsTreeGenerated.regTitle("imapHost"))
        assertEquals(SettingsTreeGenerated.resource("setting_indexPageSize_title"), SettingsTreeGenerated.regTitle("indexPageSize"))
        assertTrue(SettingsTreeGenerated.resource("settings_group_server_title") != 0)
        assertEquals(SettingsTreeGenerated.resource("settings_group_server_title"), SettingsTreeGenerated.titleOf("server"))
        val summary = listOf(
            "label_arrival",
            "label_date",
            "label_from",
            "compose_subject",
            "compose_to",
            "compose_cc",
            "label_size",
            "label_thread",
            "label_ordered_subject",
            "drawer_delete",
            "label_move",
            "compose_reply",
            "compose_reply_all",
            "label_set_flag",
            "label_clear_flag",
            "label_flag_screen",
            "reader_next",
            "reader_previous",
        )
        for (name in summary) {
            assertTrue(name, SettingsTreeGenerated.resource(name) != 0)
        }
    }
}
