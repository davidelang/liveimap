package org.dlang.liveimap.ui

import java.io.File
import org.junit.Assert.assertEquals
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

    @Test fun eachOuterPageScrollsOnce() {
        val settings = src("main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt")
        assertFalse(settings.contains("LocalSettingsInTree"))
        val tree = slice(settings, "fun SettingsTree")
        assertEquals(1, Regex("verticalScroll").findAll(tree).count())
        val groupScreen = slice(settings, "fun SettingsGroupScreen")
        assertEquals(1, Regex("SettingsPage").findAll(groupScreen).count())
        val groups = listOf(
            "AccountGroup",
            "MailboxesGroup",
            "FoldersGroup",
            "ReadingGroup",
            "ComposeGroup",
            "AppearanceGroup",
            "LayoutGroup",
            "SlowerGroup",
            "DebugGroup",
        )
        for (name in groups) {
            assertFalse(name, slice(settings, "fun $name").contains("SettingsPage"))
        }
        for (name in listOf("ExpandedFoldersBody", "FolderViewsBody", "FolderStartsBody")) {
            val body = slice(settings, "fun $name")
            assertFalse(name, body.contains("SettingsPage"))
            assertFalse(name, body.contains("verticalScroll"))
        }
        val expanded = slice(settings, "fun ExpandedFoldersScreen")
        assertTrue(expanded.indexOf("SettingsPage") < expanded.indexOf("ExpandedFoldersBody("))
        val views = slice(settings, "fun FolderViewsScreen")
        assertTrue(views.indexOf("SettingsPage") < views.indexOf("FolderViewsBody("))
        val starts = slice(settings, "fun FolderStartsScreen")
        assertTrue(starts.indexOf("SettingsPage") < starts.indexOf("FolderStartsBody("))
        val toolbar = src("main/kotlin/org/dlang/liveimap/ui/toolbar/ToolbarEditorScreen.kt")
        val editor = slice(toolbar, "fun ToolbarEditorScreen")
        assertFalse(editor.contains("verticalScroll"))
        assertFalse(editor.contains("fillMaxSize"))
        assertTrue(slice(toolbar, "fun ToolbarEditorPage").contains("verticalScroll"))
    }

    @Test fun folderArrowsAndGatedRowsOpen() {
        val settings = src("main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt")
        assertTrue(settings.contains("ChevronRight"))
        assertTrue(settings.contains("ExpandMore"))
        assertTrue(settings.contains("24.dp * depth"))
        assertFalse(settings.contains("detectTapGestures"))
        val choice = slice(settings, "fun <T> ChoiceField")
        assertFalse(choice.contains("expanded && enabled"))
        val layout = slice(settings, "fun LayoutGroup")
        assertTrue(layout.contains("settings_multi_pane"))
        assertTrue(layout.contains("PaneLayoutEditor"))
        assertTrue(layout.contains("settings_change_layout"))
        assertFalse(layout.contains("settings_panes"))
        val editor = src("main/kotlin/org/dlang/liveimap/settings/PaneLayoutEditor.kt")
        val pane = slice(editor, "fun PaneLayoutEditor")
        assertTrue(pane.contains("persist"))
        val commit = slice(editor, "fun commit")
        val gate = commit.indexOf("if (persist)")
        assertTrue(gate >= 0)
        assertFalse(commit.substring(0, gate).contains("onSave"))
        var depth = 0
        var end = -1
        var seenBrace = false
        for (i in gate until commit.length) {
            when (commit[i]) {
                '{' -> {
                    depth += 1
                    seenBrace = true
                }
                '}' -> {
                    depth -= 1
                    if (seenBrace && depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }
        assertTrue(end > gate)
        val block = commit.substring(gate, end)
        assertTrue(block.contains("onSave"))
        assertFalse(commit.substring(end).contains("onSave"))
    }

    @Test fun editorDoesNotLoadOnRevision() {
        val settings = src("main/kotlin/org/dlang/liveimap/settings/SettingsScreen.kt")
        val editor = slice(settings, "private fun rememberSettingsEditor")
        assertFalse(editor.contains("LaunchedEffect(revision)"))
        assertFalse(editor.contains("SettingsRevision"))
        assertTrue(editor.contains("LaunchedEffect(store) { editor.load() }"))
    }

    private fun slice(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue(signature, start >= 0)
        val rest = source.substring(start + signature.length)
        val cut = listOf("\nfun ", "\nprivate fun ", "\ninternal fun ")
            .map { rest.indexOf(it) }
            .filter { it >= 0 }
            .minOrNull() ?: rest.length
        return rest.substring(0, cut)
    }
}
