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
        // labPort (optional) narrows to one lab container when several accounts share the host.
        val port = InstrumentationRegistry.getArguments().getString("labPort")?.toIntOrNull()
        val store = AccountStore.shared(InstrumentationRegistry.getInstrumentation().targetContext)
        for (a in store.listAccounts()) {
            store.selectAccount(a.id)
            val s = store.load()
            if (s.imapHost.contains(match) && (port == null || s.imapPort == port)) return@runBlocking
        }
        error("no account matching " + match)
    }

    /**
     * Removes lab accounts whose username is exactly labUser AND IMAP port is exactly labPort (both required, so a
     * real account can't match by accident). Fails if none or more than one match.
     */
    @Test
    fun removeLabAccount() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val user = args.getString("labUser").orEmpty()
        val port = args.getString("labPort")?.toIntOrNull() ?: error("labPort required")
        check(user.isNotEmpty()) { "labUser required" }
        val store = AccountStore.shared(InstrumentationRegistry.getInstrumentation().targetContext)
        val hits = ArrayList<String>()
        for (a in store.listAccounts()) {
            store.selectAccount(a.id)
            val s = store.load()
            if (s.username == user && s.imapPort == port) hits.add(a.id)
        }
        check(hits.size == 1) { "expected one match, got ${hits.size}" }
        check(store.removeAccount(hits.single())) { "remove refused" }
    }
}
