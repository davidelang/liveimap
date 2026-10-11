package org.dlang.liveimap.engine

import org.dlang.liveimap.session.LongSearchLink
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.SearchProgress
import org.dlang.liveimap.session.longSearchTimeoutSec
import org.dlang.liveimap.settings.AccountSettings

/** A fresh libetpan connection for one long search in [mailbox]; never shared with the main session. */
class LibetpanLongSearchLink(
    private val account: AccountSettings,
    private val mailbox: String,
) : LongSearchLink {
    private val session = LibetpanMailSession()

    override suspend fun open() {
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

    override suspend fun close() = session.suspendConnections()
}
