package org.dlang.liveimap.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavHostPaneTest {
    private fun src(path: String) = listOf("src/$path", "app/src/$path").map(::File).first { it.exists() }.readText()

    @Test fun deadHelpersAreGoneAndBooxCommentSitsOnDrawerHidden() {
        val nav = src("main/kotlin/org/dlang/liveimap/ui/NavHost.kt")
        assertFalse(nav.contains("fun InsetPage"))
        assertFalse(nav.contains("fun drawerInboxes"))
        val marker = "flashed on the Boox before the list appeared."
        val at = nav.indexOf(marker)
        assertTrue(at >= 0)
        val tail = nav.substring(at)
        val next = tail.indexOf("\ninternal fun ")
        assertTrue(next >= 0)
        assertTrue(tail.substring(next).startsWith("\ninternal fun drawerHiddenFor"))
        assertFalse(tail.substring(0, next).contains("settingsSplitRoute"))
        assertTrue(nav.contains("SettingsTree("))
        assertFalse(nav.contains("if (settingsSplit) {"))
    }

    @Test fun drawerAccountTestDropsInboxCases() {
        val test = src("test/kotlin/org/dlang/liveimap/ui/DrawerAccountTest.kt")
        assertFalse(test.contains("drawerInboxes"))
        assertTrue(test.contains("drawerBlocks"))
        assertTrue(test.contains("fun oneDrawerAccountIsEmpty"))
        assertTrue(test.contains("fun twoDrawerAccountsKeepOwnFolders"))
    }

    @Test fun settingsTitlesUseResources() {
        val nav = src("main/kotlin/org/dlang/liveimap/ui/NavHost.kt")
        assertFalse(nav.contains("UpPage(\"Settings\""))
        assertFalse(nav.contains("UpPage(\"Expanded folders\""))
        assertFalse(nav.contains("UpPage(\"Folder views\""))
        assertFalse(nav.contains("UpPage(\"Start position per folder\""))
        assertFalse(nav.contains("UpPage(\"Copy contacts\""))
        assertTrue(nav.contains("UpPage(stringResource(R.string.drawer_settings)"))
        assertTrue(nav.contains("UpPage(stringResource(R.string.settings_expanded_folders)"))
        assertTrue(nav.contains("UpPage(stringResource(R.string.settings_folder_views)"))
        assertTrue(nav.contains("UpPage(stringResource(R.string.settings_start_per_folder)"))
        assertTrue(nav.contains("UpPage(stringResource(R.string.settings_copy_contacts)"))
    }

    @Test fun handlesExposeResizeActions() {
        val nav = src("main/kotlin/org/dlang/liveimap/ui/NavHost.kt")
        val drag = nav.substringAfter("private fun DragSplit(").substringBefore("private fun StackSplit(")
        val stack = nav.substringAfter("private fun StackSplit(").substringBefore("private fun AxisSplit(")
        assertHandle(drag, "detectHorizontalDragGestures")
        assertHandle(stack, "detectVerticalDragGestures")
    }

    private fun assertHandle(body: String, gesture: String) {
        assertTrue(body.contains(gesture))
        val sem = body.substringAfter(".semantics {")
        assertTrue(sem.contains("contentDescription"))
        assertTrue(sem.contains("customActions"))
        assertTrue(sem.contains("onDrag(48f, span)"))
        assertTrue(sem.contains("onDrag(-48f, span)"))
    }

    @Test fun panesScrollOnceAndAccountIdAppliesLayout() {
        val nav = src("main/kotlin/org/dlang/liveimap/ui/NavHost.kt")
        for (name in listOf("index", "selection", "folders", "reader", "compose")) {
            val route = nav.substringAfter("composable(\"toolbar/$name\")").substringBefore("\n            composable(")
            assertTrue(name, route.contains("ToolbarEditorPage("))
        }
        val folderPane = nav.substringAfter("val folderListPane").substringBefore("val foldersAboveNow")
        assertFalse(folderPane.contains("verticalScroll"))
        val leaf = nav.substringAfter("is PaneNode.Leaf -> ").substringBefore("\n")
        assertFalse(leaf.contains("verticalScroll"))
        val folders = src("main/kotlin/org/dlang/liveimap/ui/folder/FolderListScreen.kt")
        assertEquals(1, Regex("""\bLazyColumn\(""").findAll(folders).count())
        val setup = slice(src("main/kotlin/org/dlang/liveimap/accounts/AuthenticatorService.kt"), "fun AccountSetupContent")
        assertTrue(setup.contains("SettingsTree("))
        assertFalse(setup.contains("SettingsGroupList"))
        assertTrue(nav.contains("chosenAccountIdFlow()"))
        val flowAt = nav.indexOf("store.chosenAccountIdFlow()")
        val effectStart = nav.lastIndexOf("LaunchedEffect(Unit)", flowAt)
        assertTrue(effectStart >= 0)
        val effect = nav.substring(effectStart, nav.indexOf("\n    }", flowAt) + 6)
        assertTrue(effect.contains("applyRunningLayout"))
        val routeEffect = nav.substringAfter("LaunchedEffect(route, drawerState.currentValue)")
            .substringBefore("LaunchedEffect(")
        assertFalse(routeEffect.contains("applyRunningLayout"))
        for (name in listOf("openIndexRow", "openFoldersRow", "openAccountFavorite")) {
            assertFalse(name, slice(nav, "fun $name").contains("applyRunningLayout"))
        }
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
