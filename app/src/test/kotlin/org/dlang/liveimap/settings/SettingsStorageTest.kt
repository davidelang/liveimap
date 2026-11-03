package org.dlang.liveimap.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.ui.toolbar.ToolbarStore
import org.dlang.liveimap.ui.toolbar.defaultIndexBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SettingsStorageTest {
    @Before
    fun tree() {
        installGeneratedSettings()
    }

    @Test
    fun emptyUiStringGivesDefaultToolbars() {
        ToolbarStore.install("")
        assertEquals(defaultIndexBar(), ToolbarStore.indexBar())
        assertEquals(2, ToolbarStore.indexRows())
    }

    @Test
    fun savedToolbarIsTheOnlyGlobalKey() = runBlocking {
        val dir = File("build/tmp/settings-storage-test").apply { mkdirs() }
        val file = File(dir, "global.preferences_pb")
        if (file.exists()) file.delete()
        val store = PreferenceDataStoreFactory.create { file }
        val key = stringPreferencesKey("value")
        ToolbarStore.install("")
        val encoded = Settings().encodeUi()
        store.edit { prefs ->
            prefs.clear()
            prefs[key] = encoded
        }
        val prefs = store.data.first()
        assertEquals(setOf("value"), prefs.asMap().keys.map { it.name }.toSet())
        val value = prefs[key] ?: error("missing value")
        assertTrue(value.trimStart().startsWith("{"))
        ToolbarStore.install(value)
        assertEquals(defaultIndexBar(), ToolbarStore.indexBar())
    }

    @Test
    fun accountToolbarLinesDoNotChangeTheUiRecord() {
        ToolbarStore.install(Settings(indexPageSize = 60).encodeUi())
        val before = ToolbarStore.encodeValue()
        try {
            decodeSettings("indexBar=zzz\ntoolbarRows=9\nfriendlyName=A\n")
            error("key=value is not a settings document")
        } catch (_: SettingsDocumentException) {
        }
        assertEquals(before, ToolbarStore.encodeValue())
        assertEquals("A", decodeSettings("{\"friendlyName\":\"A\"}").friendlyName)
        assertFalse(Settings(friendlyName = "A").encode().contains("indexBar"))
    }
}

internal fun settingsTreeText(): String {
    val file = listOf(File("src/main/assets/settings-tree.json"), File("app/src/main/assets/settings-tree.json"))
        .first { it.isFile }
    return file.readText()
}
