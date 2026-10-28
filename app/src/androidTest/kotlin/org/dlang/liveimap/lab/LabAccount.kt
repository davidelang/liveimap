package org.dlang.liveimap.lab

import android.content.Context
import org.dlang.liveimap.accounts.AccountStore
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.DataStoreSettingsStore

/** A stored account the lab deletes in finally. The device must already have one account of its own. */
internal suspend fun createLabAccount(
    context: Context,
    settings: AccountSettings,
    password: String,
    smtpPassword: String = "",
): String {
    val store = AccountStore.shared(context)
    if (store.listAccounts().isEmpty()) {
        error("the lab device needs one account of its own until R4 allows deleting the last account")
    }
    val id = store.addAccount()
    val bound = DataStoreSettingsStore(context, id)
    bound.save(settings)
    bound.setPassword(password)
    if (settings.smtpHost.isNotEmpty()) bound.setSmtpPassword(smtpPassword)
    return id
}

internal suspend fun deleteLabAccount(context: Context, id: String) {
    if (!AccountStore.shared(context).removeAccount(id)) error("lab account was not deleted")
}
