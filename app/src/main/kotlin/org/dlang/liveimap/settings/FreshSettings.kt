package org.dlang.liveimap.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first

internal fun globalSettingsFile(context: Context): File =
    File(context.applicationContext.filesDir, "datastore/global_settings.preferences_pb")

internal fun accountSettingsFile(context: Context): File =
    File(context.applicationContext.filesDir, "datastore/account_settings.preferences_pb")

/**
 * Reads the preferences file from disk. The process DataStore cache is a snapshot and is not asked.
 */
internal suspend fun freshPreferenceStrings(file: File): Map<String, String> {
    if (!file.isFile || file.length() == 0L) return emptyMap()
    val copy = File.createTempFile("liveimap-prefs-", ".pb", file.parentFile)
    val job = SupervisorJob()
    try {
        file.inputStream().use { input -> copy.outputStream().use { output -> input.copyTo(output) } }
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO)) { copy }
        val prefs = store.data.first()
        val out = LinkedHashMap<String, String>()
        for ((key, value) in prefs.asMap()) {
            val text = value as? String ?: continue
            out[key.name] = text
        }
        return out
    } finally {
        job.cancel()
        copy.delete()
    }
}
