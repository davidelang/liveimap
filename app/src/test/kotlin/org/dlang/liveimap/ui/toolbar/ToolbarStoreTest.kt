package org.dlang.liveimap.ui.toolbar

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.settingsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolbarStoreTest {
    @Before
    fun tree() {
        check(settingsRegistry.isNotEmpty())
    }

    @Test
    fun emptyStringAndSavedValue() = runBlocking {
        val previous = ToolbarStore.record
        try {
        ToolbarStore.install("")
        assertEquals(defaultIndexBar(), ToolbarStore.indexBar())
        assertEquals(2, ToolbarStore.indexRows())
        val moved = moveIndexAction(defaultIndexBar(), IndexBarAction.Search, BarSection.Overflow)
        ToolbarStore.replace(ToolbarStore.record.copy(indexBar = moved.copy(rows = 3)))
        val encoded = Settings(indexBar = moved.copy(rows = 3)).encodeUi()
        val dir = File("build/tmp/toolbar-store-test").apply { mkdirs() }
        val file = File(dir, "global.preferences_pb")
        if (file.exists()) file.delete()
        val store = PreferenceDataStoreFactory.create { file }
        val key = stringPreferencesKey("value")
        store.edit { prefs ->
            prefs.clear()
            prefs[key] = encoded
        }
        val prefs = store.data.first()
        assertEquals(setOf("value"), prefs.asMap().keys.map { it.name }.toSet())
        val value = prefs[key] ?: error("value")
        assertTrue(value.trimStart().startsWith("{"))
        ToolbarStore.install(value)
        assertEquals(moved.copy(rows = 3), ToolbarStore.indexBar())
        assertEquals(3, ToolbarStore.indexRows())
        } finally {
            ToolbarStore.replace(previous)
        }
    }
}
