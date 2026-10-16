package org.dlang.liveimap.ui.toolbar

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class ToolbarModelTest {
    @Test
    fun readerScreensDoNotNameTheOldBar() {
        val reader = source("kotlin/org/dlang/liveimap/ui/reader/MessageReaderScreen.kt")
        val settings = source("kotlin/org/dlang/liveimap/settings/SettingsScreen.kt")
        val oldType = Regex("\\bReaderAction\\b")
        val oldField = Regex("\\breaderBar\\b")
        assertFalse(oldType.containsMatchIn(reader))
        assertFalse(oldField.containsMatchIn(reader))
        assertFalse(oldType.containsMatchIn(settings))
        assertFalse(oldField.containsMatchIn(settings))
    }

    private fun source(path: String): String =
        listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }.readText()
}
