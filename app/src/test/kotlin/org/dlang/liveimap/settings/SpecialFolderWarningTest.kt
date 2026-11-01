package org.dlang.liveimap.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecialFolderWarningTest {
    @Test
    fun unsetFoldersAreNamedForTheWarning() {
        assertEquals(listOf(SpecialFolder.Sent, SpecialFolder.Postponed), unsetSpecialFolders(Settings()))
        assertEquals(listOf(SpecialFolder.Postponed), unsetSpecialFolders(Settings(sentMailbox = "Sent")))
        assertEquals(emptyList<SpecialFolder>(), unsetSpecialFolders(Settings(sentMailbox = "Sent", postponedMailbox = "Drafts")))
    }

    @Test
    fun saveGateFollowsAPendingLineAndTheServerCheck() {
        assertFalse(settingsSaveEnabled(false, false, false, false, false, false))
        assertTrue(settingsSaveEnabled(false, false, false, false, true, false))
        assertFalse(settingsSaveEnabled(false, false, false, false, true, true))
        assertFalse(settingsSaveShown(true, true, true, true, true))
    }

    @Test
    fun folderListUsesTheEditedAccountOrTheDraftNotTheDrawer() {
        val draft = Settings(imapHost = "draft.example", username = "ada")
        val saved = settingsFolderAccount("B", draft, "A")
        assertEquals(SettingsFolderAccount.Saved("B"), saved)
        assertEquals("B", settingsFolderProbeId(saved))
        val fresh = settingsFolderAccount(null, draft, "A")
        assertEquals(SettingsFolderAccount.Draft(draft), fresh)
        assertEquals("probe", settingsFolderProbeId(fresh))
        assertNotEquals("A", settingsFolderProbeId(fresh))
    }
}
