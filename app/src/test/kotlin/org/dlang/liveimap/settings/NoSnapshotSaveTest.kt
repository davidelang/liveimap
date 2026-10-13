package org.dlang.liveimap.settings

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** F55/F66: field edits go through update {} so a stale snapshot never reverts other settings. */
class NoSnapshotSaveTest {
    @Test
    fun updateKeepsConcurrentChanges() = runBlocking {
        val store = MemStore(AccountSettings(sieveCertPin = ""))
        store.save(store.load().copy(theme = ThemeMode.Dark)) // another screen, meanwhile
        store.update { it.copy(sieveCertPin = "AB:CD") }
        val now = store.load()
        assertEquals("AB:CD", now.sieveCertPin)
        assertEquals(ThemeMode.Dark, now.theme)
    }

    @Test
    fun knownSnapshotSitesUseUpdate() {
        val root = "src/main/kotlin/org/dlang/liveimap/"
        val filter = File(root + "ui/filter/FilterScreen.kt").readText()
        assertFalse(filter.contains("store.save(account.copy(sieveCertPin"))
        val folders = File(root + "ui/folder/FolderListModel.kt").readText()
        assertFalse(folders.contains("store.save(settings.copy(expandedFolders"))
        val toolbar = File(root + "ui/toolbar/ToolbarEditorScreen.kt").readText()
        assertFalse(toolbar.contains("store.save(latest)"))
    }
}

private class MemStore(var value: AccountSettings) : SettingsStore {
    override suspend fun load() = value
    override suspend fun save(settings: AccountSettings) { value = settings }
    override suspend fun password() = ""
    override suspend fun setPassword(value: String) {}
}
