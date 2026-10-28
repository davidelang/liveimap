package org.dlang.liveimap.ui.toolbar

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.settings.installSettingsTree
import org.dlang.liveimap.settings.readUiLine
import org.dlang.liveimap.settings.settingsTreeText
import org.dlang.liveimap.settings.updateGlobalRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolbarStoreTest {
    @Before
    fun tree() {
        installSettingsTree(settingsTreeText())
    }

    @Test
    fun emptyStringAndSavedValue() = runBlocking {
        val previous = ToolbarStore.record
        try {
        ToolbarStore.install("")
        assertEquals(defaultIndexBar(), ToolbarStore.indexBar())
        assertEquals(2, ToolbarStore.toolbarRows())
        val moved = moveIndexAction(defaultIndexBar(), IndexBarAction.Search, BarSection.Overflow)
        ToolbarStore.replace(ToolbarStore.record.copy(indexBar = moved, toolbarRows = 3))
        val encoded = ToolbarStore.encodeValue()
        val dir = File("build/tmp/toolbar-store-test").apply { mkdirs() }
        val file = File(dir, "global.preferences_pb")
        if (file.exists()) file.delete()
        val store = PreferenceDataStoreFactory.create { file }
        val key = stringPreferencesKey("value")
        store.edit { prefs ->
            prefs.clear()
            prefs[key] = updateGlobalRecord("", "toolbars", encoded)
        }
        val prefs = store.data.first()
        assertEquals(setOf("value"), prefs.asMap().keys.map { it.name }.toSet())
        val value = prefs[key] ?: error("value")
        assertTrue(value.startsWith("toolbars="))
        assertEquals(1, value.lines().count { it.isNotEmpty() })
        ToolbarStore.install(value)
        assertEquals(moved, ToolbarStore.indexBar())
        assertEquals(3, ToolbarStore.toolbarRows())
        assertEquals(encoded, readUiLine(value, "toolbars"))
        } finally {
            ToolbarStore.replace(previous)
        }
    }
}
