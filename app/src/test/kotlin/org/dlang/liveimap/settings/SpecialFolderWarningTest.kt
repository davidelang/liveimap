package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SpecialFolderWarningTest {
    @Test
    fun unsetFoldersAreNamedForTheWarning() {
        assertEquals(listOf(SpecialFolder.Sent, SpecialFolder.Postponed), unsetSpecialFolders(Settings()))
        assertEquals(listOf(SpecialFolder.Postponed), unsetSpecialFolders(Settings(sentMailbox = "Sent")))
        assertEquals(emptyList<SpecialFolder>(), unsetSpecialFolders(Settings(sentMailbox = "Sent", postponedMailbox = "Drafts")))
    }
}
