package org.dlang.liveimap.settings

import com.davidlang.liveimap.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RegTitleTest {
    @Test
    fun regTitleFindsTheLeafResource() {
        assertEquals(R.string.settings_imap_host, SettingsTreeGenerated.regTitle("imapHost"))
        assertEquals(R.string.setting_indexPageSize_title, SettingsTreeGenerated.regTitle("indexPageSize"))
        assertEquals(R.string.settings_group_server_title, SettingsTreeGenerated.titleOf("server"))
        val summary = listOf(
            "label_arrival" to R.string.label_arrival,
            "label_date" to R.string.label_date,
            "label_from" to R.string.label_from,
            "compose_subject" to R.string.compose_subject,
            "compose_to" to R.string.compose_to,
            "compose_cc" to R.string.compose_cc,
            "label_size" to R.string.label_size,
            "label_thread" to R.string.label_thread,
            "label_ordered_subject" to R.string.label_ordered_subject,
            "drawer_delete" to R.string.drawer_delete,
            "label_move" to R.string.label_move,
            "compose_reply" to R.string.compose_reply,
            "compose_reply_all" to R.string.compose_reply_all,
            "label_set_flag" to R.string.label_set_flag,
            "label_clear_flag" to R.string.label_clear_flag,
            "label_flag_screen" to R.string.label_flag_screen,
            "reader_next" to R.string.reader_next,
            "reader_previous" to R.string.reader_previous,
        )
        for ((name, id) in summary) {
            val resolved = SettingsTreeGenerated.resource(name)
            assertTrue(name, resolved != 0)
            assertEquals(name, id, resolved)
        }
    }
}
