package org.dlang.liveimap.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** David, 2026-10-12: two-pane Settings is list (left) + opened group (right), with no drawer. */
class SettingsSplitTest {
    @Test fun settingsRoutesSplit() {
        assertTrue(settingsSplitRoute("settings"))
        assertTrue(settingsSplitRoute("settings/{group}"))
        assertTrue(settingsSplitRoute("settings/folders/expanded"))
        assertFalse(settingsSplitRoute("folders"))
        assertFalse(settingsSplitRoute("index/{mailbox}"))
        assertFalse(settingsSplitRoute(null))
    }

    @Test fun splitReplacesTheDrawerLayout() {
        val nav = listOf("src/main/kotlin/org/dlang/liveimap/ui/NavHost.kt", "app/src/main/kotlin/org/dlang/liveimap/ui/NavHost.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue(nav.contains("val settingsSplit = split && settingsSplitRoute(route)"))
        val scaffold = nav.substring(nav.indexOf("    LiveImapScaffold {"))
        assertTrue(scaffold.indexOf("if (settingsSplit) {") in 0 until scaffold.indexOf("DrawerScaffold()"))
    }
}
