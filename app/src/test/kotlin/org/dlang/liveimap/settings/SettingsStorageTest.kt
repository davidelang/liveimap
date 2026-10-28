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
        installSettingsTree(settingsTreeText())
    }

    @Test
    fun emptyUiStringGivesDefaultToolbars() {
        ToolbarStore.install("")
        assertEquals(defaultIndexBar(), ToolbarStore.indexBar())
        assertEquals(2, ToolbarStore.toolbarRows())
    }

    @Test
    fun savedToolbarIsTheOnlyGlobalKey() = runBlocking {
        val dir = File("build/tmp/settings-storage-test").apply { mkdirs() }
        val file = File(dir, "global.preferences_pb")
        if (file.exists()) file.delete()
        val store = PreferenceDataStoreFactory.create { file }
        val key = stringPreferencesKey("value")
        ToolbarStore.install("")
        val encoded = ToolbarStore.encodeValue()
        store.edit { prefs ->
            val stored = updateGlobalRecord(prefs[key].orEmpty(), "toolbars", encoded)
            prefs.clear()
            prefs[key] = stored
        }
        val prefs = store.data.first()
        assertEquals(setOf("value"), prefs.asMap().keys.map { it.name }.toSet())
        val value = prefs[key] ?: error("missing value")
        assertEquals("toolbars=$encoded", value)
        ToolbarStore.install(value)
        assertEquals(encoded, readUiLine(value, "toolbars"))
    }

    @Test
    fun accountToolbarLinesDoNotChangeTheUiRecord() {
        ToolbarStore.install("indexPageSize=60")
        val before = ToolbarStore.encodeValue()
        decodeAccountSettings("indexBar=zzz\ntoolbarRows=9\nfriendlyName=A\n")
        assertEquals(before, ToolbarStore.encodeValue())
        assertEquals("A", decodeAccountSettings("indexBar=zzz\nfriendlyName=A\n").friendlyName)
        assertFalse(AccountSettings(friendlyName = "A").encode().contains("indexBar="))
    }
}

internal fun settingsTreeText(): String {
    val file = listOf(File("src/main/assets/settings-tree.json"), File("app/src/main/assets/settings-tree.json"))
        .first { it.isFile }
    return file.readText()
}
