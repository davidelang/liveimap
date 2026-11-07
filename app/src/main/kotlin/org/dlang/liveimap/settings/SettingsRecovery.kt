package org.dlang.liveimap.settings

import android.content.Context
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

internal const val damagedSettingsNotice = "settings-damaged"

internal data class StoredSettingsLoad(val settings: Settings, val damaged: Boolean)

/** Preserve the exact rejected document privately, never in logs or Android backup. Backup failure aborts recovery. */
internal fun loadStoredSettings(text: String, backupDirectory: File): StoredSettingsLoad {
    try {
        return StoredSettingsLoad(decodeSettings(text), false)
    } catch (_: SettingsDocumentException) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        check(backupDirectory.isDirectory || backupDirectory.mkdirs()) { "settings backup directory unavailable" }
        val backup = File(backupDirectory, "$digest.json.bad")
        try {
            Files.write(backup.toPath(), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (_: FileAlreadyExistsException) {
            check(backup.readBytes().contentEquals(bytes)) { "settings backup incomplete" }
        }
        return StoredSettingsLoad(Settings(), true)
    }
}

internal fun loadStoredSettings(context: Context, text: String): StoredSettingsLoad {
    val loaded = loadStoredSettings(text, File(context.noBackupFilesDir, "damaged-settings"))
    if (loaded.damaged) {
        // Neither parser exceptions nor document text are safe to log: either can contain private values.
        android.util.Log.e("liveimap", "Damaged settings document preserved in private damaged-settings backup")
        SettingsHub.noteLoad(damagedSettingsNotice)
    }
    return loaded
}

/** Repair replaces every represented field; an ordinary save still merges only edits into the latest document. */
internal fun settingsKeysForSave(before: Settings, screen: Settings, tracked: Set<String>,
    uiDamaged: Boolean = false, accountDamaged: Boolean = false,
): Set<String> = tracked + screen.changedKeysAgainst(before) + settingsRegistry.filterValues {
    !it.leaf.derived && if (it.scope == SettingScope.Ui) uiDamaged else accountDamaged
}.keys
