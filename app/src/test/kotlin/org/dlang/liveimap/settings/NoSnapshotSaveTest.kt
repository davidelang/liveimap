package org.dlang.liveimap.settings

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** F55/F66: field edits go through update {} so a stale snapshot never reverts other settings. */
class NoSnapshotSaveTest {
    @Test
    fun updateKeepsConcurrentChanges() = runBlocking {
        val store = MemStore(Settings(sieveCertPin = ""))
        store.save(store.load().copy(theme = ThemeMode.Dark)) // another screen, meanwhile
        store.update { it.copy(sieveCertPin = "AB:CD") }
        val now = store.load()
        assertEquals("AB:CD", now.sieveCertPin)
        assertEquals(ThemeMode.Dark, now.theme)
    }
}

private class MemStore(var value: Settings) : SettingsStore {
    override suspend fun load() = value
    override suspend fun save(settings: Settings) { value = settings }
    override suspend fun password() = ""
    override suspend fun setPassword(value: String) {}
}
