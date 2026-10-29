package org.dlang.liveimap.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStringsTest {
    @Test
    fun everyGeneratedNameIsInStringsXml() {
        val xml = listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml"))
            .first { it.isFile }
            .readText()
        val summaryNames = listOf(
            "label_arrival", "label_date", "label_from", "compose_subject", "compose_to", "compose_cc",
            "label_size", "label_thread", "label_ordered_subject", "drawer_delete", "label_move",
            "compose_reply", "compose_reply_all", "label_set_flag", "label_clear_flag", "label_flag_screen",
            "reader_next", "reader_previous",
        )
        for (name in summaryNames) {
            assertTrue(name, name in SettingsTreeGenerated.resourceNames)
        }
        for (name in SettingsTreeGenerated.resourceNames) {
            assertTrue(name, xml.contains("name=\"$name\""))
        }
    }
}
