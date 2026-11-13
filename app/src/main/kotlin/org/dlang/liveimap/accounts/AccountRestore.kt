package org.dlang.liveimap.accounts
import org.dlang.liveimap.i18n.P

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.dlang.liveimap.settings.decodeSettings

internal object AccountRestoreReport {
    var text by mutableStateOf<String?>(null)
        private set

    fun report(text: String) {
        assignRestoreState { this.text = text }
    }

    fun clear(shown: String) {
        if (text == shown) text = null
    }
}

/** True after [restoreAccounts] has returned, including a skipped backup. */
internal object AccountRestoreGate {
    var finished by mutableStateOf(false)
        private set

    fun markFinished() {
        assignRestoreState { finished = true }
    }
}

// A background caller posts when a main looper exists. No main looper: assign here and do not throw.
private fun assignRestoreState(assign: () -> Unit) {
    val mine = runCatching { android.os.Looper.myLooper() }.getOrNull()
    val main = runCatching { android.os.Looper.getMainLooper() }.getOrNull()
    if (main == null || mine === main) {
        assign()
    } else {
        android.os.Handler(main).post(assign)
    }
}

internal suspend fun restoreAccounts(context: Context) {
    try {
        restoreSavedAccounts(context)
    } finally {
        AccountRestoreGate.markFinished()
    }
}

private suspend fun restoreSavedAccounts(context: Context) {
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
        val settings = runCatching { decodeSettings(text) }.getOrNull() ?: continue
        val record = bytes["a:$id"]?.let { decodeBackupRecord(it) }
        store.restoreAccountEntry(
            id,
            settings,
            record?.imapPassword,
            record?.smtpPassword,
            record?.sievePassword,
            record?.oauthClientSecret,
            record?.oauthToken,
        )
        restored += 1
    }
    for ((key, value) in bytes) {
        if (!key.startsWith("a:")) continue
        val id = key.removePrefix("a:")
        if (id in pending) continue
        if (prefs[stringPreferencesKey(id)] != null) continue
        val record = decodeBackupRecord(value) ?: continue
        store.restoreAccountEntry(
            id,
            record.settings,
            record.imapPassword,
            record.smtpPassword,
            record.sievePassword,
            record.oauthClientSecret,
            record.oauthToken,
        )
        restored += 1
    }
    val fresh = context.accountSettingsDataStore.data.first()
    val stillListed = manager.getAccountsByType(liveimapAccountType).mapNotNull {
        manager.getUserData(it, userAccountId)
    }
    for (id in stillListed) {
        if (fresh[stringPreferencesKey(id)] != null) continue
        try {
            store.deleteAccount(id)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w("LiveIMAP", "leftover account not removed $id", error)
        }
    }
    if (restored > 0) {
        AccountRestoreReport.report(P(context, restored, restored, one = "%1\$d account", other = "%1\$d accounts", name = "backup_restored"))
    }
}

