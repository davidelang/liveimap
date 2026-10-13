package org.dlang.liveimap.ui.folder

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderJankTest {
    @Test fun oneWidthCandidatePerLength() {
        assertEquals(listOf("7", "12", "1,234"), widthCandidates(listOf("7", "12", "3", "99", "1,234", "5,678")))
        assertEquals(emptyList<String>(), widthCandidates(emptyList()))
    }

    @Test fun shownRowsAndWidthsAreRemembered() {
        val s = listOf("src/main/kotlin/org/dlang/liveimap/ui/folder/FolderListScreen.kt", "app/src/main/kotlin/org/dlang/liveimap/ui/folder/FolderListScreen.kt")
            .map(::File).first { it.exists() }.readText()
        assertTrue(s.contains("val shown = remember(rows, folderQuery, emptyPrefixLabel) {"))
        assertTrue(s.contains("val totalWidth = remember(shown, countStyle, density) {"))
    }
}
