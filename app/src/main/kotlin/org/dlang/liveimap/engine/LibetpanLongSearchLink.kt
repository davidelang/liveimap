package org.dlang.liveimap.engine

import org.dlang.liveimap.session.CertPrompt
import org.dlang.liveimap.session.LongSearchLink
import org.dlang.liveimap.session.confirmMailCertificate
import org.dlang.liveimap.session.confirmMailPlaintext
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.SearchProgress
import org.dlang.liveimap.session.longSearchTimeoutSec
import org.dlang.liveimap.settings.AccountSettings

/** A fresh libetpan connection for one long search in [mailbox]; never shared with the main session. */
class LibetpanLongSearchLink(
    private val account: AccountSettings,
    private val mailbox: String,
    private val confirmCert: suspend (CertPrompt) -> Boolean = { confirmMailCertificate(it) },
    private val confirmPlaintext: suspend () -> Boolean = { confirmMailPlaintext() },
) : LongSearchLink {
    private val session = LibetpanMailSession()

    override suspend fun open() {
        // Same prompts as the main session; an account already confirmed there answers without asking again.
        session.setCertConfirmer(confirmCert)
        session.setPlaintextConfirmer(confirmPlaintext)
        when (val opened = session.open(account)) {
            is OpenResult.Connected -> Unit
            is OpenResult.Rejected -> throw MailFailure("search connection refused")
            is OpenResult.Failed -> throw MailFailure(opened.text)
        }
        session.select(mailbox)
        session.prepareLongSearch(longSearchTimeoutSec("INPROGRESS" in session.capabilities))
    }

    override suspend fun search(kind: String, argument: String): List<Long> = session.longSearch(kind, argument)

    override fun progress(): SearchProgress = session.searchProgress()

    override fun abort() = session.abortConnection()

    override suspend fun close() = session.close()
}
