package org.dlang.liveimap.lab

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.accounts.AccountStore
import org.junit.Test

/**
 * Adds one lab test account to the app on a test device (screen/layout walks on real hardware). Never edits an
 * existing account: it always creates a new one via addAccount() and fills only that.
 * Args: labHost, labPort, labUser, labPass, labName, labEmail, labSmtpPort (optional).
 */
class LabAccountTest {
    @Test
    fun addLabAccount() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        fun arg(k: String) = args.getString(k) ?: ""
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AccountStore.shared(ctx)
        store.addAccount()
        val fresh = store.load()
        store.save(
            fresh.copy(
                displayName = arg("labName"), imapHost = arg("labHost"), imapPort = arg("labPort").toInt(),
                username = arg("labUser"), tlsMode = org.dlang.liveimap.settings.TlsMode.None,
                allowPlaintextAuth = true, email = arg("labEmail"),
                smtpHost = if (arg("labSmtpPort").isNotEmpty()) arg("labHost") else "",
                smtpPort = arg("labSmtpPort").toIntOrNull() ?: 25,
            ),
        )
        store.setPassword(arg("labPass"))
    }

    /** Selects the existing account whose IMAP host contains labAccountMatch (changes only which one is open). */
    @Test
    fun selectLabAccount() = runBlocking {
        val match = InstrumentationRegistry.getArguments().getString("labAccountMatch").orEmpty()
        val store = AccountStore.shared(InstrumentationRegistry.getInstrumentation().targetContext)
        for (a in store.listAccounts()) {
            store.selectAccount(a.id)
            if (store.load().imapHost.contains(match)) return@runBlocking
        }
        error("no account matching " + match)
    }
}
