package org.dlang.liveimap.lab

import android.content.Context
import org.dlang.liveimap.accounts.AccountStore
import org.dlang.liveimap.settings.AccountSettings

/** A stored account the lab deletes in finally. */
internal suspend fun createLabAccount(
    context: Context,
    settings: AccountSettings,
    password: String,
    smtpPassword: String = "",
): String {
    val store = AccountStore.shared(context)
    val smtp = if (settings.smtpHost.isNotEmpty()) smtpPassword else ""
    val id = store.createAccount(settings, password, smtp)
    store.selectAccount(id)
    return id
}

internal suspend fun deleteLabAccount(context: Context, id: String) {
    if (!AccountStore.shared(context).removeAccount(id)) error("lab account was not deleted")
}
