package org.dlang.liveimap.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FreshSettingsTest {
    @Test
    fun freshReadSeesTheFileRatherThanAStaleCache() = runBlocking {
        val dir = File("build/tmp/fresh-settings-" + System.nanoTime()).apply { mkdirs() }
        val file = File(dir, "account_settings.preferences_pb")
        val store = PreferenceDataStoreFactory.create { file }
        val key = stringPreferencesKey("account")
        store.edit { prefs -> prefs[key] = "one" }
        assertEquals("one", store.data.first()[key])
        file.writeBytes(file.readBytes())
        val reader = PreferenceDataStoreFactory.create { File(dir, "rewritten.preferences_pb").also { file.copyTo(it, overwrite = true) } }
        reader.edit { prefs -> prefs[key] = "two" }
        val rewritten = File(dir, "rewritten.preferences_pb")
        assertEquals("two", freshPreferenceStrings(rewritten)["account"])
        assertEquals("one", store.data.first()[key])
    }
}
