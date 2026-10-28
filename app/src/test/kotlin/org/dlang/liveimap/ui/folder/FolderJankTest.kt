package org.dlang.liveimap.ui.folder

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderJankTest {
    @Test fun oneWidthCandidatePerLength() {
        assertEquals(listOf("7", "12", "1,234"), widthCandidates(listOf("7", "12", "3", "99", "1,234", "5,678")))
        assertEquals(emptyList<String>(), widthCandidates(emptyList()))
    }
}
