package org.dlang.liveimap.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings is one tree. The scaffold does not put a group list beside the settings detail. */
class SettingsSplitTest {
    private fun src(path: String) = listOf("src/$path", "app/src/$path").map(::File).first { it.exists() }.readText()

    @Test fun settingsRoutesSplit() {
        val nav = src("main/kotlin/org/dlang/liveimap/ui/NavHost.kt")
        assertFalse(nav.contains("settingsSplitRoute"))
        assertFalse(nav.contains("if (settingsSplit) {"))
        assertTrue(nav.contains("SettingsTree("))
        assertFalse(nav.contains("SettingsGroupList"))
        val routeEffect = nav.substringAfter("LaunchedEffect(route, drawerState.currentValue)")
            .substringBefore("LaunchedEffect(LayoutApply.generation)")
        assertFalse(routeEffect.contains("applyRunningLayout"))
        assertTrue(nav.contains("LaunchedEffect(LayoutApply.generation)"))
    }

    @Test fun splitReplacesTheDrawerLayout() {
        val nav = src("main/kotlin/org/dlang/liveimap/ui/NavHost.kt")
        val scaffold = nav.substring(nav.indexOf("    LiveImapScaffold {"))
        assertFalse(scaffold.contains("if (settingsSplit) {"))
        assertFalse(scaffold.contains("SettingsGroupList"))
        assertTrue(scaffold.contains("DrawerScaffold()"))
        assertTrue(scaffold.contains("settingsSplit = false"))
        val settings = src("main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt")
        assertTrue(settings.contains("testTag(\"smoke-settings\")"))
        assertTrue(settings.contains("settings_change_layout"))
        assertTrue(settings.contains("LayoutApply.request()"))
        assertTrue(settings.contains("paneControlsEnabled"))
        assertTrue(settings.contains("multiPaneName(settings.multiPane)"))
        assertFalse(settings.contains("fun layoutOffOn"))
    }
}
