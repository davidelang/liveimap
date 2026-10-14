package org.dlang.liveimap.session

import kotlinx.coroutines.flow.StateFlow
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.StartRule

/**
 * One account's session, IMAP or JMAP, chosen at [open] by the account's own setting (F68: JMAP only when the user
 * turned "jmapFolders" on; discovery alone never switches). Switching kind closes the other session. Sending stays
 * on IMAP/SMTP (an IMAP session is opened for it on demand, as before JMAP sessions existed); [openWith] is the
 * sending-rule path and is always IMAP. Wrapped by [SerialMailSession], so calls here are already serialized.
 */
class RoutedMailSession(
    private val imapFactory: () -> MailSession,
    private val jmapFactory: () -> MailSession,
) : MailSession {
    @Volatile private var imap: MailSession? = null
    @Volatile private var jmap: MailSession? = null
    @Volatile private var useJmap = false
    @Volatile private var lastAccount: AccountSettings? = null
    @Volatile private var imapOpenFor: AccountSettings? = null
    private var certConfirmer: (suspend (CertPrompt) -> Boolean)? = null
    private var plaintextConfirmer: (suspend () -> Boolean)? = null

    private fun imapSession(): MailSession = imap ?: imapFactory().also {
        it.setCertConfirmer(certConfirmer); it.setPlaintextConfirmer(plaintextConfirmer); imap = it
    }

    private fun jmapSession(): MailSession = jmap ?: jmapFactory().also {
        it.setCertConfirmer(certConfirmer); it.setPlaintextConfirmer(plaintextConfirmer); jmap = it
    }

    private fun current(): MailSession = if (useJmap) jmapSession() else imapSession()

    /** Which kind is active (for tests and the Debug screen). */
    val routedToJmap: Boolean get() = useJmap

    override val capabilities: Set<String> get() = current().capabilities
    override val featureCaps: Capabilities get() = current().featureCaps
    override val connectionState: StateFlow<ConnectionState> get() = current().connectionState

    override suspend fun open(account: AccountSettings): OpenResult {
        val wantJmap = account.jmapFolders
        if (wantJmap != useJmap) {
            if (wantJmap) {
                imap?.close(); imap = null; imapOpenFor = null
            } else {
                jmap?.close(); jmap = null
            }
            useJmap = wantJmap
        }
        lastAccount = account
        val result = current().open(account)
        if (!useJmap && result == OpenResult.Connected) imapOpenFor = account
        return result
    }

    override suspend fun openWith(credentials: org.dlang.liveimap.accounts.AccountCredentials): OpenResult {
        imapOpenFor = null
        return imapSession().openWith(credentials)
    }

    override fun setCertConfirmer(confirm: (suspend (CertPrompt) -> Boolean)?) {
        certConfirmer = confirm
        imap?.setCertConfirmer(confirm); jmap?.setCertConfirmer(confirm)
    }

    override fun setPlaintextConfirmer(confirm: (suspend () -> Boolean)?) {
        plaintextConfirmer = confirm
        imap?.setPlaintextConfirmer(confirm); jmap?.setPlaintextConfirmer(confirm)
    }

    /** SMTP rides on the IMAP session; for a JMAP account open it with the same settings first. */
    private suspend fun sender(): MailSession {
        val s = imapSession()
        val account = lastAccount
        if (useJmap && account != null && imapOpenFor != account) {
            when (val r = s.open(account)) {
                OpenResult.Connected -> imapOpenFor = account
                is OpenResult.Failed -> throw MailFailure(r.text)
                is OpenResult.Rejected -> throw MailFailure("server rejected: ${r.capabilities}")
            }
        }
        return s
    }

    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>, dsnNotify: Int): Int =
        sender().smtpSend(rfc822, recipients, dsnNotify)

    override suspend fun smtpSendFrom(rfc822: ByteArray, recipients: List<String>, dsnNotify: Int, envelopeFrom: String): Int =
        sender().smtpSendFrom(rfc822, recipients, dsnNotify, envelopeFrom)

    override suspend fun smtpLoginCheck(): SmtpLoginCheck = sender().smtpLoginCheck()

    override fun close() {
        imap?.close(); jmap?.close()
        imap = null; jmap = null; imapOpenFor = null
    }

    override suspend fun resume() {
        imap?.resume()
        jmap?.resume()
    }

    override suspend fun suspendConnections() {
        imap?.suspendConnections()
        jmap?.suspendConnections()
    }

    override suspend fun namespaces(): List<Namespace> = current().namespaces()

    override suspend fun listLevel(prefix: String, parentMailbox: String?, unreadCounts: Boolean): List<FolderEntry> = current().listLevel(prefix, parentMailbox, unreadCounts)

    override suspend fun mailboxListed(name: String): Boolean = current().mailboxListed(name)

    override suspend fun statusMessages(mailboxes: List<String>): Map<String, Int> = current().statusMessages(mailboxes)

    override suspend fun select(mailbox: String): SelectResult = current().select(mailbox)

    override suspend fun unselect(): Unit = current().unselect()

    override suspend fun fetchIndex(request: IndexRequest): List<IndexRow> = current().fetchIndex(request)

    override suspend fun fetchStructure(uid: Long): MimePart = current().fetchStructure(uid)

    override suspend fun fetchStructureAt(uid: Long, section: String): MimePart = current().fetchStructureAt(uid, section)

    override suspend fun peekPart(uid: Long, section: String, offset: Int, length: Int): ByteArray = current().peekPart(uid, section, offset, length)

    override suspend fun fetchRfc822(uid: Long): ByteArray = current().fetchRfc822(uid)

    override suspend fun storeFlags(uids: List<Long>, add: Set<String>, remove: Set<String>): Unit = current().storeFlags(uids, add, remove)

    override suspend fun storeFlagsAll(add: Set<String>, remove: Set<String>): Unit = current().storeFlagsAll(add, remove)

    override suspend fun uidExpungeDeleted(): Unit = current().uidExpungeDeleted()

    override suspend fun uidExpunge(uids: List<Long>): Unit = current().uidExpunge(uids)

    override suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String): Unit = current().copyThenDelete(uids, targetMailbox)

    override suspend fun copyUids(uids: List<Long>, targetMailbox: String): Unit = current().copyUids(uids, targetMailbox)

    override suspend fun copyAllThenDelete(targetMailbox: String): Unit = current().copyAllThenDelete(targetMailbox)

    override suspend fun selectedExists(): Int = current().selectedExists()

    override suspend fun takeCopiedUids(): List<Long> = current().takeCopiedUids()

    override suspend fun copiedEpoch(): Long = current().copiedEpoch()

    override suspend fun searchText(query: String): List<Long> = current().searchText(query)

    override suspend fun searchCriterion(kind: String, argument: String): List<Long> = current().searchCriterion(kind, argument)

    override suspend fun searchCount(kind: String, argument: String): Int = current().searchCount(kind, argument)

    override suspend fun searchScope(scopeName: String, home: String, kind: String, argument: String): List<MailboxUids> = current().searchScope(scopeName, home, kind, argument)

    override suspend fun searchScopeCount(scopeName: String, home: String, kind: String, argument: String): Int = current().searchScopeCount(scopeName, home, kind, argument)

    override suspend fun subscribedMailboxes(): List<String> = current().subscribedMailboxes()

    override suspend fun searchStart(rule: StartRule, byUid: Boolean, edge: SearchEdge): List<Long> = current().searchStart(rule, byUid, edge)

    override suspend fun locateUid(uid: Long): List<Long> = current().locateUid(uid)

    override suspend fun sort(key: SortKey, newestFirst: Boolean): List<Long> = current().sort(key, newestFirst)

    override suspend fun sortWindow(key: SortKey, newestFirst: Boolean, first: Int, last: Int): UidWindow? = current().sortWindow(key, newestFirst, first, last)

    override suspend fun searchWindow(kind: String, argument: String, first: Int, last: Int): UidWindow? = current().searchWindow(kind, argument, first, last)

    override suspend fun thread(key: SortKey): ThreadNode = current().thread(key)

    override suspend fun sortMatching(key: SortKey, newestFirst: Boolean, criteria: String): List<Long>? = current().sortMatching(key, newestFirst, criteria)

    override suspend fun sortWindowMatching(key: SortKey, newestFirst: Boolean, first: Int, last: Int, criteria: String): UidWindow? = current().sortWindowMatching(key, newestFirst, first, last, criteria)

    override suspend fun threadMatching(key: SortKey, criteria: String): ThreadNode? = current().threadMatching(key, criteria)

    override suspend fun clientOrder(key: SortKey, newestFirst: Boolean, onProgress: (done: Int, total: Int) -> Unit, cancelled: () -> Boolean): List<Long> = current().clientOrder(key, newestFirst, onProgress, cancelled)

    override suspend fun clientThread(key: SortKey): ThreadNode = current().clientThread(key)

    override suspend fun watch(mailbox: String, onChange: (MailboxChange) -> Unit): Unit = current().watch(mailbox, onChange)

    override suspend fun stopWatch(): Unit = current().stopWatch()

    override suspend fun noop(): Unit = current().noop()

    override suspend fun knownTrash(): String = current().knownTrash()

    override suspend fun expungeOnLeave(mailbox: String): Unit = current().expungeOnLeave(mailbox)

    override suspend fun selectedInfo(): SelectResult = current().selectedInfo()

    override suspend fun append(mailbox: String, rfc822: ByteArray, flags: Set<String>): Unit = current().append(mailbox, rfc822, flags)

    override suspend fun appendReturningUid(mailbox: String, rfc822: ByteArray, flags: Set<String>): Long = current().appendReturningUid(mailbox, rfc822, flags)
}
