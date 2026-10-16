package org.dlang.liveimap.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** F77: raw dialogs and menus drop obscured touches. AlertDialog already does. */
class GuardedPopupTest {
    @Test
    fun guardWindowMarksTheViewAndItsRoot() {
        val src = read("kotlin/org/dlang/liveimap/ui/GuardedDialog.kt")
        val start = src.indexOf("internal fun guardWindow")
        assertTrue(start >= 0)
        val body = src.substring(start, src.indexOf("\n}", start))
        assertTrue(body.contains("view.filterTouchesWhenObscured = true"))
        assertTrue(body.contains("view.rootView.filterTouchesWhenObscured = true"))
    }

    @Test
    fun eachPopupCallsTheFilter() {
        val expected = listOf(
            "kotlin/org/dlang/liveimap/settings/SettingsScreen.kt" to 4,
            "kotlin/org/dlang/liveimap/ui/folder/MailboxChooser.kt" to 1,
            "kotlin/org/dlang/liveimap/ui/debug/DebugReportReview.kt" to 1,
            "kotlin/org/dlang/liveimap/ui/reader/MessageReaderScreen.kt" to 1,
            "kotlin/org/dlang/liveimap/ui/folder/FolderListScreen.kt" to 3,
            "kotlin/org/dlang/liveimap/ui/index/MessageIndexScreen.kt" to 5,
            "kotlin/org/dlang/liveimap/ui/search/AdvancedSearchScreen.kt" to 2,
            "kotlin/org/dlang/liveimap/ui/compose/ComposeScreen.kt" to 2,
        )
        for ((path, windows) in expected) {
            val src = read(path)
            val count = occurrences(src, "Dialog(") +
                occurrences(src, "DropdownMenu(") +
                occurrences(src, "ExposedDropdownMenu(")
            assertEquals(path, windows, count)
            assertTrue(path, occurrences(src, "ObscuredTouchFilter()") >= count)
        }
    }

    private fun occurrences(text: String, token: String): Int {
        var n = 0
        var i = 0
        while (true) {
            val at = text.indexOf(token, i)
            if (at < 0) return n
            val prev = if (at == 0) ' ' else text[at - 1]
            if (!prev.isLetterOrDigit() && prev != '_') n++
            i = at + token.length
        }
    }

    private fun read(path: String) =
        listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }.readText()
}
