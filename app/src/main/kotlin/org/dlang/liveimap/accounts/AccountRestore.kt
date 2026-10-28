package org.dlang.liveimap.accounts

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.stringPreferencesKey
import com.davidlang.liveimap.R
import kotlinx.coroutines.flow.first
import org.dlang.liveimap.settings.decodeAccountSettings

internal object AccountRestoreReport {
    var text by mutableStateOf<String?>(null)
        private set

    fun report(text: String) {
        this.text = text
    }

    fun clear(shown: String) {
        if (text == shown) text = null
    }
}

internal suspend fun restoreAccounts(context: Context) {
    val availability = BlockStorePasswords.available()
    if (availability is BackupAvailability.Unavailable) {
        Log.w("LiveIMAP", "backup restore skipped ${availability.reason}")
        return
    }
    val store = AccountStore.shared(context)
    val pending = readPendingDeletes(context).toSet()
    val manager = android.accounts.AccountManager.get(context)
    val listed = manager.getAccountsByType(liveimapAccountType).mapNotNull {
        manager.getUserData(it, userAccountId)
    }.toSet()
    val prefs = context.accountSettingsDataStore.data.first()
    var restored = 0
    val bytes = BlockStorePasswords.restoreAll(context)
    for ((key, value) in prefs.asMap()) {
        val id = key.name
        if (id == selectedAccountIdKey || id in listed || id in pending) continue
        val text = value as? String ?: continue
        val settings = runCatching { decodeAccountSettings(text) }.getOrNull() ?: continue
        val record = bytes["a:$id"]?.let { decodeBackupRecord(it) }
        store.restoreAccountEntry(
            id,
            settings,
            record?.imapPassword,
            record?.smtpPassword,
            record?.sievePassword,
        )
        restored += 1
    }
    for ((key, value) in bytes) {
        if (!key.startsWith("a:")) continue
        val id = key.removePrefix("a:")
        if (id in listed || id in pending) continue
        if (prefs[stringPreferencesKey(id)] != null) continue
        val record = decodeBackupRecord(value) ?: continue
        store.restoreAccountEntry(id, record.settings, record.imapPassword, record.smtpPassword, record.sievePassword)
        restored += 1
    }
    if (restored > 0) {
        AccountRestoreReport.report(context.resources.getQuantityString(R.plurals.backup_restored, restored, restored))
    }
}

