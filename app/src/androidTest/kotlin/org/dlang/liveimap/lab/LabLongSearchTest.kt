package org.dlang.liveimap.lab

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.accounts.AccountStore
import org.dlang.liveimap.engine.LibetpanLongSearchLink
import org.dlang.liveimap.session.LongSearch
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Read-only long search against a configured account (moon only; never asgard): body search for labLongText in
 * labLongMailbox on a side connection, logging INPROGRESS updates, cancelled after labLongCancelSec (0 = run to
 * the end). The chosen account is switched for the run and restored; the password stays inside the app.
 */
@RunWith(AndroidJUnit4::class)
class LabLongSearchTest {
    private fun arg(name: String) = InstrumentationRegistry.getArguments().getString(name).orEmpty()

    @Test
    fun longSearch() = runBlocking {
        val match = arg("labAccountMatch")
        assumeTrue("labAccountMatch not given", match.isNotEmpty())
        check(!match.contains("asgard", ignoreCase = true) && match != "10.0.0.100") { "never on asgard" }
        val store = AccountStore.shared(InstrumentationRegistry.getInstrumentation().targetContext)
        val accounts = store.listAccounts()
        val original = accounts.firstOrNull { it.chosen }?.id
        try {
            var account: org.dlang.liveimap.settings.AccountSettings? = null
            for (a in accounts) {
                store.selectAccount(a.id)
                val s = store.load()
                if (s.imapHost.contains(match, ignoreCase = true)) { account = s; break }
            }
            val chosen = account ?: error("no account for $match")
            val mailbox = arg("labLongMailbox").ifEmpty { "INBOX" }
            val search = LongSearch(LibetpanLongSearchLink(chosen, mailbox), 250)
            val t0 = System.currentTimeMillis()
            val job = async {
                search.run("Body", arg("labLongText").ifEmpty { "invoice" }) { p ->
                    android.util.Log.i("LiveIMAP", "longsearch progress +${System.currentTimeMillis() - t0}ms ${p.done}/${p.total} lines=${p.lines}")
                }
            }
            val cancelSec = arg("labLongCancelSec").toLongOrNull() ?: 0
            if (cancelSec > 0) {
                delay(cancelSec * 1000)
                search.cancel()
                android.util.Log.i("LiveIMAP", "longsearch cancel at +${System.currentTimeMillis() - t0}ms")
            }
            val r = job.await()
            val shown = if (r is org.dlang.liveimap.session.LongSearchResult.Hits) "Hits(${r.uids.size})" else r.toString()
            android.util.Log.i("LiveIMAP", "longsearch result +${System.currentTimeMillis() - t0}ms $shown")
        } finally {
            if (original != null) store.selectAccount(original)
        }
    }
}
