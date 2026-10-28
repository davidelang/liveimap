package org.dlang.liveimap.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoKotlinUiTextTest {
    @Test
    fun settingsRowsUseResourcesForTheNewTitles() {
        val text = listOf(
            File("src/main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt"),
            File("app/src/main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt"),
        ).first { it.isFile }.readText()
        assertFalse(text.contains("fun groupSummary"))
        assertFalse(text.contains("\"All accounts\""))
        assertTrue(text.contains("settings_all_accounts") || text.contains("SettingsTreeGenerated"))
    }
}
