package org.dlang.liveimap.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** David, 2026-10-12: the wrap column is shown under Compose, not Sending/SMTP; the stored key is unchanged. */
class WrapColumnPlacementTest {
    private val screen = listOf("src/main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt", "app/src/main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt")
        .map(::File).first { it.exists() }.readText()

    private fun group(name: String): String {
        val start = screen.indexOf("private fun $name(")
        val next = screen.indexOf("\nprivate fun ", start + 1).let { if (it < 0) screen.length else it }
        return screen.substring(start, next)
    }

    @Test fun wrapColumnIsInComposeOnly() {
        assertEquals(1, Regex("settings_wrap_column").findAll(screen).count())
        assertTrue(group("ComposeGroup").contains("settings_wrap_column"))
        assertTrue(!group("AccountGroup").contains("settings_wrap_column"))
        assertTrue(group("ComposeGroup").contains("composerWrapColumn = number"))
    }

    @Test fun storedValueRoundTrips() {
        val s = newAccountSettings().copy(composerWrapColumn = 72)
        assertEquals(72, s.copy().composerWrapColumn)
    }
}
