package org.dlang.liveimap.lab

import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.accounts.AccountStore
import org.dlang.liveimap.settings.decodeAccountSettings
import org.dlang.liveimap.settings.encode
import org.junit.Test

/**
 * Copies one account between test devices without the secret ever being printed: exportAccount (source device)
 * returns settings and password base64 in the instrumentation status (the host captures it to a 0600 file and
 * pushes it), importAccount (target device) reads /data/local/tmp/lab-acct, adds a NEW account (never edits an
 * existing one) pointed at labHost, and makes it browse-only: no SMTP host, mark-seen-on-open off, auto-expunge
 * off, ask before expunge on.
 */
class LabAccountCopyTest {
    private fun arg(k: String) = InstrumentationRegistry.getArguments().getString(k).orEmpty()

    @Test
    fun exportAccount() = runBlocking {
        val store = AccountStore.shared(InstrumentationRegistry.getInstrumentation().targetContext)
        val accounts = store.listAccounts()
        val original = accounts.firstOrNull { it.chosen }?.id
        var out: Pair<String, String>? = null
        try {
            for (a in accounts) {
                store.selectAccount(a.id)
                val s = store.load()
                if (s.imapHost.contains(arg("labAccountMatch"), ignoreCase = true)) {
                    out = s.encode() to store.password()
                    break
                }
            }
        } finally {
            if (original != null) store.selectAccount(original)
        }
        val (settings, pw) = out ?: error("no matching account")
        val b = android.os.Bundle()
        b.putString("labAcct", Base64.encodeToString(settings.toByteArray(), Base64.NO_WRAP) + ":" +
            Base64.encodeToString(pw.toByteArray(), Base64.NO_WRAP))
        InstrumentationRegistry.getInstrumentation().sendStatus(0, b)
    }

    @Test
    fun importAccount() = runBlocking {
        val (s64, p64) = java.io.File("/data/local/tmp/lab-acct").readText().trim().split(":")
        val src = decodeAccountSettings(String(Base64.decode(s64, Base64.NO_WRAP)))
        val store = AccountStore.shared(InstrumentationRegistry.getInstrumentation().targetContext)
        store.addAccount()
        store.load()
        store.save(
            src.copy(
                imapHost = arg("labHost"), displayName = arg("labName"),
                smtpHost = "", markSeenOnOpen = false, autoExpunge = false, askBeforeExpunge = true,
                logImapTraffic = false,
            ),
        )
        store.setPassword(String(Base64.decode(p64, Base64.NO_WRAP)))
    }
}
