package org.dlang.liveimap.settings

import java.io.File
import java.nio.file.Files
import org.dlang.liveimap.accounts.accountUserData
import org.dlang.liveimap.accounts.overlayAccountFields
import org.dlang.liveimap.accounts.planAccountSave
import org.junit.Assert.*
import org.junit.Test

class SettingsRecoveryTest {
    private fun verdict(before: Settings, screen: Settings, keys: Set<String>, loaded: Settings = screen,
        needPassword: Boolean = false, wrotePassword: Boolean = false, rejected: String? = null) = settingsSaveVerdict(
        rejected = rejected, before = before, screen = screen, keysWritten = keys,
        wrotePassword = wrotePassword, wroteSmtp = false, wroteSieve = false,
        needPassword = needPassword, needSmtp = false, needSieve = false,
        record = accountUserData("account", loaded, null), checkRecord = true, loadedSettings = loaded,
    )

    private fun inDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("settings-recovery-test-").toFile()
        try { test(directory) } finally { check(directory.deleteRecursively()) }
    }

    @Test fun rejectedDocumentsArePreservedExactlyAndCanBeReplacedByTheWholeScreen() = inDirectory { directory ->
        installGeneratedSettings()
        for (bad in listOf("{broken", "[]", "null", "imapHost=legacy.invalid")) {
            val backupDirectory = File(directory, "backups")
            val recovered = loadStoredSettings(bad, backupDirectory)
            assertTrue(recovered.damaged)
            assertTrue(backupDirectory.listFiles()!!.any { it.readText() == bad })
            val count = backupDirectory.listFiles()!!.size
            assertTrue(loadStoredSettings(bad, backupDirectory).damaged)
            assertEquals(count, backupDirectory.listFiles()!!.size)
            val screen = newAccountSettings().copy(imapHost = "imap.example", username = "person",
                email = "person@example", signature = "screen signature", imapPort = 143).withTlsMode(TlsMode.None)
            val record = accountUserData("account", screen, null)
            val before = overlayAccountFields(recovered.settings, record)
            val keys = settingsKeysForSave(before, screen, emptySet(), accountDamaged = true)
            assertTrue(keys.contains("signature"))
            // Same merge and AccountManager write plan as the production commitKeys/stageSave path.
            val merged = applyWrittenKeys(before, screen, keys)
            val plan = planAccountSave("account", before, merged, merged.encode(), "")
            val storedFile = File(directory, "settings.json")
            storedFile.writeText(plan.text)
            val loaded = overlayAccountFields(loadStoredSettings(storedFile.readText(), backupDirectory).settings, plan.userData)
            assertEquals("screen signature", loaded.signature)
            assertEquals(TlsMode.None, loaded.tlsMode)
            assertEquals("imap.example", loaded.imapHost)
            assertTrue(verdict(before, screen, keys, loaded).stored)
            assertEquals(false to false, confirmedSaveDialogs(true, true, true))
        }
    }

    @Test fun confirmedNoChangeClosesCleanlyButMissingEditsAndSecretsReportFailure() {
        installGeneratedSettings()
        val before = newAccountSettings().copy(imapHost = "imap.example")
        val unchanged = verdict(before, before, emptySet())
        assertTrue(unchanged.stored)
        assertNull(unchanged.failure)
        assertEquals(false to false, confirmedSaveDialogs(true, true, unchanged.stored))
        val screen = before.copy(signature = "edited")
        val recoveredKeys = settingsKeysForSave(before, screen, emptySet())
        assertTrue(recoveredKeys.contains("signature"))
        assertTrue(verdict(before, screen, recoveredKeys).stored)
        for (failure in listOf(
            verdict(before, screen, emptySet()),
            verdict(before, screen, recoveredKeys, loaded = before),
            verdict(before, before, emptySet(), needPassword = true),
            verdict(before, before, emptySet(), rejected = "write refused"),
        )) {
            assertFalse(failure.stored)
            assertFalse(failure.failure.isNullOrBlank())
            assertEquals(true to true, confirmedSaveDialogs(true, true, failure.stored))
        }
    }

    @Test fun uiRepairDoesNotOverwriteUneditedAccountFields() {
        installGeneratedSettings()
        val before = newAccountSettings()
        val keys = settingsKeysForSave(before, before, emptySet(), uiDamaged = true)
        assertTrue(keys.isNotEmpty())
        assertTrue(keys.all { settingsRegistry.getValue(it).scope == SettingScope.Ui })
        val accountKeys = settingsKeysForSave(before, before, emptySet(), accountDamaged = true)
        assertTrue(accountKeys.isNotEmpty())
        assertTrue(accountKeys.none { settingsRegistry.getValue(it).scope == SettingScope.Ui })
    }

    @Test fun validDocumentsAreNotBackedUpAndBackupFailureIsNotHidden() = inDirectory { directory ->
        val backupDirectory = File(directory, "backups")
        val valid = loadStoredSettings(newAccountSettings().encode(), backupDirectory)
        assertFalse(valid.damaged)
        assertFalse(backupDirectory.exists())
        val blocked = File(directory, "not-a-directory")
        blocked.writeText("occupied")
        assertThrows(IllegalStateException::class.java) { loadStoredSettings("{broken", blocked) }
        assertEquals("occupied", blocked.readText())
    }
}
