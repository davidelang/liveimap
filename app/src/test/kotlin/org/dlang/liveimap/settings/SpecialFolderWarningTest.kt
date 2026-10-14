package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SpecialFolderWarningTest {
    @Test
    fun unsetFoldersAreNamedForTheWarning() {
        assertEquals(listOf(SpecialFolder.Sent, SpecialFolder.Postponed), unsetSpecialFolders(AccountSettings()))
        assertEquals(listOf(SpecialFolder.Postponed), unsetSpecialFolders(AccountSettings(sentMailbox = "Sent")))
        assertEquals(emptyList<SpecialFolder>(), unsetSpecialFolders(AccountSettings(sentMailbox = "Sent", postponedMailbox = "Drafts")))
    }
}
