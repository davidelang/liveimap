package org.dlang.liveimap.ui

import java.io.File
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
        assertTrue(nav.contains("internal fun settingsSplitRoute"))
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
}
