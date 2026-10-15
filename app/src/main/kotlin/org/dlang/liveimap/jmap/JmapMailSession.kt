package org.dlang.liveimap.jmap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dlang.liveimap.engine.PeerTrust
import org.dlang.liveimap.engine.TlsPolicy
import org.dlang.liveimap.engine.applyTlsPolicy
import org.dlang.liveimap.session.CertPrompt
import org.dlang.liveimap.session.FolderEntry
import org.dlang.liveimap.session.IndexMode
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.MaxMimeDepth
import org.dlang.liveimap.session.MimePart
import org.dlang.liveimap.session.TooDeepSubtype
import org.dlang.liveimap.session.Namespace
import org.dlang.liveimap.session.NamespaceKind
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.SelectResult
import org.dlang.liveimap.session.ThreadNode
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.tlsPolicy

/**
 * A JMAP account (RFC 8620/8621) behind [MailSession] (roadmap jmap.md). LiveIMAP's own client over the platform
 * HTTPS stack; every exchange goes through [jmapCall], so it keeps the existing guarantees:
 *  - F53: no redirects are followed, and every session URL stays on the discovered host;
 *  - F58: JSON nesting is capped (JsonParser) and response bodies are size-capped (readJmapBody);
 *  - F63: trust uses the account's own JMAP pin ([AccountSettings.jmapCertPin]), never the IMAP one;
 *  - F68: network IOExceptions surface as failures (here [MailFailure]), never as crashes.
 *
 * Folder names are paths of JMAP mailbox names joined by '/'. JMAP email ids are strings; this session numbers
 * them per mailbox from receivedAt the first time it sees each id (UIDs) under an epoch chosen at [open], so a
 * reopened session is a new UIDVALIDITY and every list re-reads. JMAP has no \Deleted: [storeFlags] refuses it
 * and deletes are destroy/move.
 */
class JmapMailSession(
    /** account, authorization (sent only if the session resource needs it) */
    private val discover: (AccountSettings, String) -> JmapOffer,
    private val passwordOf: suspend (AccountSettings) -> String,
    private val postFor: (pin: String, policy: TlsPolicy) -> (String, String, String) -> JmapHttpExchange = ::pinnedJmapPost,
    private val trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
    private val epochClock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val getFor: (pin: String, policy: TlsPolicy) -> (String, String) -> JmapBlobExchange = ::pinnedJmapGet,
    private val uploadFor: (pin: String, policy: TlsPolicy) -> (String, ByteArray, String, String) -> JmapHttpExchange = ::pinnedJmapUpload,
    private val streamFor: (pin: String, policy: TlsPolicy) -> (String, String) -> JmapHttpExchange =
        { p, policy -> { url, auth -> platformJmapEventSource(url, auth, p, policy) } },
    /** Stores a confirmed JMAP pin on the account (field-level update, F75). */
    private val savePin: suspend (String) -> Unit = {},
    private val describe: (List<ByteArray>) -> Array<String>? = PeerTrust::offer,
) : MailSession {
    @Volatile private var certConfirmer: (suspend (CertPrompt) -> Boolean)? = null

    override fun setCertConfirmer(confirm: (suspend (CertPrompt) -> Boolean)?) {
        certConfirmer = confirm
    }

    private val lock = Mutex()
    private var account: AccountSettings? = null
    /** Android account id from [org.dlang.liveimap.accounts.AccountTarget] at [open]; blank allows https only. */
    internal var accountKey = ""
    private var session: JmapSession? = null
    private var authorization = ""
    private var pin = ""
    private var password = ""
    private var epoch = 0L

    /** Folder path -> mailbox id, filled as levels are listed. */
    private val ids = HashMap<String, String>()
    private val roles = HashMap<String, String>()
    private var selectedPath: String? = null

    /**
     * Per mailbox id: email id <-> UID. The first sight of an id keeps its UID: receivedAt epoch seconds when
     * that number is free. A newer id is at least [next], which then moves one past it. An older id is a positive
     * UID below the current minimum, and [next] stays. The same epoch takes the next free integer on that side.
     * A missing receivedAt uses [next] and increases it. [select] reports [next].
     */
    internal class Uids {
        val byId = HashMap<String, Long>()
        val byUid = HashMap<Long, String>()
        var next = 1L
        private val anchor = HashMap<Long, Long>()
        private val highEpoch = HashSet<Long>()

        internal fun uid(id: String, receivedAt: Long? = null): Long {
            byId[id]?.let { return it }
            val epoch = receivedAt?.takeIf { it > 0 }
            val assigned = when {
                epoch == null -> allocMissing()
                anchor.containsKey(epoch) -> allocSame(epoch)
                newerThanAssigned(epoch) -> allocNewer(epoch)
                else -> allocOlder(epoch)
            }
            byId[id] = assigned
            byUid[assigned] = id
            return assigned
        }

        private fun newerThanAssigned(epoch: Long): Boolean {
            var max = Long.MIN_VALUE
            var any = false
            for (e in anchor.keys) {
                any = true
                if (e > max) max = e
            }
            return !any || epoch > max
        }

        private fun allocMissing(): Long {
            var u = next
            while (byUid.containsKey(u)) u++
            next = u + 1
            return u
        }

        private fun allocNewer(epoch: Long): Long {
            var u = if (epoch > next) epoch else next
            while (byUid.containsKey(u)) u++
            next = u + 1
            anchor[epoch] = u
            highEpoch.add(epoch)
            return u
        }

        private fun allocOlder(epoch: Long): Long {
            val min = byUid.keys.minOrNull() ?: next
            var u = epoch
            if (u < 1 || u >= min || byUid.containsKey(u)) {
                u = min - 1
                while (u >= 1 && byUid.containsKey(u)) u--
            }
            if (u < 1) throw MailFailure("no free uid")
            anchor[epoch] = u
            return u
        }

        /** Next free integer beside the first UID of [epoch], on that epoch's side. [next] stays. */
        private fun allocSame(epoch: Long): Long {
            var u = anchor[epoch]!! - 1
            if (epoch !in highEpoch) {
                val min = byUid.keys.minOrNull()
                if (min != null && u > min - 1) u = min - 1
            }
            while (u >= 1 && byUid.containsKey(u)) u--
            if (u < 1) throw MailFailure("no free uid")
            return u
        }
    }
    private val uids = HashMap<String, Uids>()
    /** Mailbox id -> totalEmails from the last Mailbox/get in [select]. */
    private val totals = HashMap<String, Int>()

    /** Per email id: IMAP-style section -> JMAP body part, from the last [fetchStructure]. [bytes] is the cached part. */
    private data class Part(val partId: String?, val blobId: String?, val type: String) {
        var bytes: ByteArray? = null
        var whole: Boolean = false
    }
    private val parts = HashMap<String, Map<String, Part>>()

    override val capabilities: Set<String> get() = setOf("IMAP4rev1", "JMAP")

    override suspend fun open(account: AccountSettings): OpenResult = lock.withLock {
        val previousKey = accountKey
        var connected = false
        try {
            accountKey = kotlin.coroutines.coroutineContext[org.dlang.liveimap.accounts.AccountTarget]?.accountId ?: ""
            var trusted = account
            val loginPassword = passwordOf(account)
            val loginAuthorization = jmapBasicAuthorization(account.username, loginPassword)
            val offer = try {
                io { discover(account, loginAuthorization) }
            } catch (pinOffer: JmapPinOffer) {
                // As IMAP (F64): show the certificate, and on yes pin it as the JMAP pin (F63) and retry once.
                val shown = describe(pinOffer.ders)
                val confirm = certConfirmer
                if (shown == null || shown.size != 5 || confirm == null) throw pinOffer
                val prompt = CertPrompt(pinOffer.text, shown[0], shown[1], shown[2], shown[3], shown[4], pinOffer.host, runCatching { jmapServerTarget(account.imapHost).port }.getOrDefault(443))
                if (!confirm(prompt)) throw pinOffer
                savePin(prompt.fingerprint)
                trusted = account.copy(jmapCertPin = prompt.fingerprint)
                try {
                    io { discover(trusted, loginAuthorization) }
                } catch (again: JmapPinOffer) {
                    throw JmapFailure(again.text)
                }
            }
            val mail = offer as? JmapOffer.Mail ?: return@withLock OpenResult.Failed("server offers no JMAP mail")
            if (mail.session.primaryMailAccountId.isNullOrBlank()) return@withLock OpenResult.Failed("jmap account id is empty")
            this.account = trusted
            session = mail.session
            pin = trusted.jmapCertPin
            password = loginPassword
            authorization = loginAuthorization
            epoch = epochClock().coerceAtLeast(1L) and 0x7fffffffL
            ids.clear(); roles.clear(); uids.clear(); totals.clear(); parts.clear(); selectedPath = null
            connected = true
            OpenResult.Connected
        } catch (failure: JmapFailure) {
            OpenResult.Failed(failure.text)
        } finally {
            if (!connected) accountKey = previousKey
        }
    }

    override suspend fun namespaces(): List<Namespace> = listOf(Namespace("", DELIMITER, NamespaceKind.Personal))

    override suspend fun listLevel(prefix: String, parentMailbox: String?, unreadCounts: Boolean): List<FolderEntry> =
        lock.withLock { levelLocked(parentMailbox, unreadCounts) }

    /**
     * A session that never listed (a watched-folder session, a sending rule) learns a path's id by listing the
     * levels down to it, so "Work/2026" works without the folder screen having run first.
     */
    private suspend fun ensureListedLocked(path: String) {
        if (ids.containsKey(path)) return
        if (path.equals("INBOX", ignoreCase = true) && roles.containsValue("inbox")) return
        val segments = path.split(DELIMITER)
        var parent: String? = null
        for (segment in segments) {
            val here = if (parent == null) segment else parent + DELIMITER + segment
            if (!ids.containsKey(here)) {
                if (parent != null && !ids.containsKey(parent)) break
                levelLocked(parent, unreadCounts = false)
                if (!ids.containsKey(here)) break
            }
            parent = here
        }
    }

    private suspend fun levelLocked(parentMailbox: String?, unreadCounts: Boolean): List<FolderEntry> {
            val s = need()
            val parentId = if (parentMailbox == null) null else idOfLocked(parentMailbox)
            val policy = accountPolicy()
            val rows = jmap { jmapFolderLevel(s, parentId, accountName(), password, pin, postFor(pin, policy), trust, policy, accountKey) }
            return rows.map { row ->
                val path = if (parentMailbox == null) row.name else parentMailbox + DELIMITER + row.name
                ids[path] = row.id
                row.role?.let { roles[path] = it }
                FolderEntry(
                    mailbox = path,
                    leaf = row.name,
                    hasChildren = row.hasChildren,
                    delimiter = DELIMITER,
                    specialUse = row.role?.let { specialUseOf(it) },
                    messages = row.total.toInt(),
                    unseen = if (unreadCounts) row.unread.toInt() else null,
                )
            }
    }

    override suspend fun select(mailbox: String): SelectResult = lock.withLock {
        ensureListedLocked(mailbox)
        val id = idOfLocked(mailbox)
        val total = call(mailboxGetRequest(accountId(), id)) { root -> mailboxTotal(root, id) }
        totals[id] = total
        selectedPath = mailbox
        SelectResult(epoch, (uids[id]?.next ?: 1L), total)
    }

    override suspend fun unselect() {
        lock.withLock { selectedPath = null }
    }

    override suspend fun fetchIndex(request: IndexRequest): List<IndexRow> = lock.withLock {
        val id = idOfLocked(request.mailbox)
        val table = uids.getOrPut(id) { Uids() }
        val body = when (request.mode) {
            IndexMode.ArrivalNewest -> emailQueryGet(accountId(), id, 0, request.limit, newestFirst = true)
            IndexMode.ArrivalOldest -> emailQueryGet(accountId(), id, 0, request.limit, newestFirst = false)
            IndexMode.ArrivalRange -> {
                val first = request.firstSequence.coerceAtLeast(1)
                val last = request.lastSequence.coerceAtLeast(first)
                emailQueryGet(accountId(), id, first - 1, (last - first + 1).coerceAtMost(MAX_WINDOW), newestFirst = false)
            }
            IndexMode.ByUid -> {
                val wanted = request.uids.mapNotNull { table.byUid[it] }
                if (wanted.isEmpty()) return@withLock emptyList()
                emailGetRequest(accountId(), wanted)
            }
        }
        call(body) { root -> parseRows(root, table, request.mailbox) }
    }

    override suspend fun storeFlags(uids: List<Long>, add: Set<String>, remove: Set<String>) {
        lock.withLock {
            val (path, table) = selectedTable()
            val patch = keywordPatch(add, remove)
            val emailIds = uids.map { table.byUid[it] ?: throw MailFailure("unknown message in $path") }
            setEmails(emailIds.associateWith { patch })
        }
    }

    override suspend fun uidExpungeDeleted() {
        // JMAP has no \Deleted: nothing is ever marked, so there is nothing to expunge.
    }

    override suspend fun uidExpunge(uids: List<Long>) {
        lock.withLock {
            val (path, table) = selectedTable()
            val emailIds = uids.map { table.byUid[it] ?: throw MailFailure("unknown message in $path") }
            val body = """{"using":$USING,"methodCalls":[["Email/set",{"accountId":${q(accountId())},"destroy":${arr(emailIds)}},"0"]]}"""
            call(body) { root -> requireSetDone(root, emailIds, destroyed = true) }
            for (e in emailIds) table.byId.remove(e)?.let { table.byUid.remove(it) }
        }
    }

    override suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String) {
        lock.withLock {
            val (path, table) = selectedTable()
            val from = idOfLocked(path)
            ensureListedLocked(targetMailbox)
            val to = idOfLocked(targetMailbox)
            val emailIds = uids.map { table.byUid[it] ?: throw MailFailure("unknown message in $path") }
            setEmails(emailIds.associateWith { mapOf("mailboxIds/$from" to null, "mailboxIds/$to" to true) })
        }
    }

    override suspend fun copyUids(uids: List<Long>, targetMailbox: String) {
        lock.withLock {
            val (path, table) = selectedTable()
            ensureListedLocked(targetMailbox)
            val to = idOfLocked(targetMailbox)
            val emailIds = uids.map { table.byUid[it] ?: throw MailFailure("unknown message in $path") }
            setEmails(emailIds.associateWith { mapOf("mailboxIds/$to" to true) })
        }
    }

    override suspend fun selectedExists(): Int {
        val path = lock.withLock { selectedPath } ?: return 0
        return select(path).exists
    }

    override suspend fun searchText(query: String): List<Long> = searchFilter("""{"text":${q(query)}}""")

    override suspend fun searchCriterion(kind: String, argument: String): List<Long> {
        val filter = when (kind.uppercase()) {
            "TEXT", "ADVANCED" -> """{"text":${q(argument)}}"""
            "FROM" -> """{"from":${q(argument)}}"""
            "TO" -> """{"to":${q(argument)}}"""
            "SUBJECT" -> """{"subject":${q(argument)}}"""
            "BODY" -> """{"body":${q(argument)}}"""
            "UNSEEN" -> """{"notKeyword":"${'$'}seen"}"""
            "SEEN" -> """{"hasKeyword":"${'$'}seen"}"""
            "FLAGGED" -> """{"hasKeyword":"${'$'}flagged"}"""
            else -> throw MailFailure("search \"$kind\" is not available on JMAP")
        }
        return searchFilter(filter)
    }

    override suspend fun sort(key: SortKey, newestFirst: Boolean): List<Long> = lock.withLock {
        val (path, table) = selectedTable()
        val property = when (key) {
            SortKey.Arrival -> "receivedAt"
            SortKey.Date -> "sentAt"
            SortKey.From -> "from"
            SortKey.To -> "to"
            SortKey.Subject -> "subject"
            SortKey.Size -> "size"
            else -> throw MailFailure("sort by ${key.name} is not available on JMAP")
        }
        val mailboxId = idOfLocked(path)
        val sortJson = """[{"property":"$property","isAscending":${!newestFirst}}]"""
        pagedHits(table, mailboxId, """{"inMailbox":${q(mailboxId)}}""", sortJson, threads = false).map { it.uid }
    }

    /** Threads by JMAP threadId: one node per thread, oldest message as its head, the rest as its replies. */
    override suspend fun thread(key: SortKey): ThreadNode = lock.withLock {
        val (path, table) = selectedTable()
        val mailboxId = idOfLocked(path)
        val hits = pagedHits(table, mailboxId, """{"inMailbox":${q(mailboxId)}}""", """[{"property":"receivedAt","isAscending":true}]""", threads = true)
        threadNodes(hits)
    }

    override suspend fun fetchStructure(uid: Long): MimePart = lock.withLock {
        val id = emailIdLocked(uid)
        val body = """{"using":$USING,"methodCalls":[["Email/get",{"accountId":${q(accountId())},"ids":[${q(id)}],"properties":["bodyStructure"],"bodyProperties":["partId","blobId","type","charset","disposition","name","size","subParts"]},"0"]]}"""
        val structure = call(body) { root -> firstEmail(root)["bodyStructure"] as? Json.Obj ?: throw MailFailure("jmap message has no structure") }
        val map = HashMap<String, Part>()
        val tree = mimeOf(structure, "", 0, map)
        parts[id] = map
        tree
    }

    override suspend fun peekPart(uid: Long, section: String, offset: Int, length: Int): ByteArray = lock.withLock {
        if (offset < 0 || length < 0) throw MailFailure("part range is invalid")
        val id = emailIdLocked(uid)
        val part = parts[id]?.get(section) ?: throw MailFailure("part $section is not known; read the structure first")
        val needEnd = offset.toLong() + length.toLong()
        val cached = part.bytes
        if (cached != null && (part.whole || needEnd <= cached.size)) return@withLock slice(cached, offset, length)
        val have = cached?.size ?: 0
        if (part.partId != null && part.type.startsWith("text/", ignoreCase = true)) {
            // A body value is a prefix of maxBodyValueBytes, so the cap reaches the missing bytes and only that tail is stored.
            val cap = needEnd.coerceIn(1, JmapMaxBody.toLong())
            if (have.toLong() >= cap) return@withLock slice(cached ?: ByteArray(0), offset, length)
            val body = """{"using":$USING,"methodCalls":[["Email/get",{"accountId":${q(accountId())},"ids":[${q(id)}],"properties":["bodyValues"],"bodyProperties":["partId"],"fetchAllBodyValues":true,"maxBodyValueBytes":$cap},"0"]]}"""
            val (text, truncated) = call(body) { root -> bodyValue(firstEmail(root), part.partId) }
            val fetched = text.toByteArray(Charsets.UTF_8)
            val tail = if (fetched.size > have) fetched.copyOfRange(have, fetched.size) else ByteArray(0)
            part.bytes = if (cached == null) fetched else cached + tail
            // A truncated value can be shorter than the cap when UTF-8 stops on a character. That prefix is not the whole part.
            part.whole = !truncated
        } else if (cached == null) {
            val blob = part.blobId ?: throw MailFailure("part $section has no content")
            part.bytes = download(blob, part.type)
        }
        slice(part.bytes ?: ByteArray(0), offset, length)
    }

    override suspend fun fetchRfc822(uid: Long): ByteArray = lock.withLock {
        val id = emailIdLocked(uid)
        val body = """{"using":$USING,"methodCalls":[["Email/get",{"accountId":${q(accountId())},"ids":[${q(id)}],"properties":["blobId"]},"0"]]}"""
        val blob = call(body) { root -> (firstEmail(root)["blobId"] as? Json.Str)?.text ?: throw MailFailure("jmap message has no blob") }
        download(blob, "message/rfc822")
    }

    override suspend fun append(mailbox: String, rfc822: ByteArray, flags: Set<String>) {
        appendReturningUid(mailbox, rfc822, flags)
    }

    /** Upload the message as a blob, then Email/import it into [mailbox] with [flags] as keywords. */
    override suspend fun appendReturningUid(mailbox: String, rfc822: ByteArray, flags: Set<String>): Long = lock.withLock {
        if (rfc822.isEmpty()) throw MailFailure("message is empty")
        ensureListedLocked(mailbox)
        val mailboxId = idOfLocked(mailbox)
        val keywords = flags.map { keywordOf(it) }.distinct().joinToString(",", "{", "}") { q(it) + ":true" }
        val s = need()
        val policy = accountPolicy()
        val blob = jmap { jmapUpload(s, rfc822, accountName(), password, pin, uploadFor(pin, policy), trust, policy, accountKey) }
        val body = """{"using":$USING,"methodCalls":[["Email/import",{"accountId":${q(accountId())},"emails":{"k1":{"blobId":${q(blob)},"mailboxIds":{${q(mailboxId)}:true},"keywords":$keywords}}},"0"]]}"""
        val created = call(body) { root -> importedId(root) }
        uids.getOrPut(mailboxId) { Uids() }.uid(created)
    }

    /** "Send over JMAP" (account.emailSubmission): Email/import into Sent, then EmailSubmission/set (jmapSend), as
     *  compose does. Without it a JMAP account sends through its SMTP server, which is not this session. Returns -1:
     *  EmailSubmission reports no DSN choice here. */
    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>, dsnNotify: Int): Int = lock.withLock {
        val a = account ?: throw MailFailure("not connected")
        if (!a.emailSubmission) throw MailFailure("a JMAP account sends through its SMTP server or EmailSubmission")
        if (rfc822.isEmpty()) throw MailFailure("message is empty")
        val s = need()
        val policy = accountPolicy()
        jmap { jmapSend(JmapOffer.Mail(s), rfc822, a.email, recipients, accountName(), password, pin, uploadFor(pin, policy), postFor(pin, policy), trust, a.sentMailbox, draftsName = a.postponedMailbox, policy = policy, accountKey = accountKey) }
        -1
    }

    // ---- push (RFC 8620 §7.3 EventSource) ----

    private val watchScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    @Volatile private var watchJob: kotlinx.coroutines.Job? = null
    /** F151: the current watch's own stream holder; an orphaned watch only ever touches its own. */
    @Volatile private var watchRun: WatchRun? = null
    /** Bumped by [suspendConnections] so a watch still in setup does not start the event source. */
    @Volatile private var watchEpoch = 0
    /** Latest [watch] target, published before setup RPCs so a suspend during setup can be restarted. */
    @Volatile private var armedWatch: ArmedWatch? = null
    /** Set only by [suspendConnections]. [stopWatch] and [close] clear it so a deliberate stop stays down. */
    @Volatile private var restartWatchOnResume = false
    /** Epoch, the armed watch, and the job are published together so a suspend cannot miss a launch. */
    private val watchGate = Any()

    private class ArmedWatch(
        val mailbox: String,
        val onChange: (MailboxChange) -> Unit,
    )

    private class WatchRun {
        @Volatile var stream: JmapHttpExchange? = null
        @Volatile var stopped = false

        /** Ends this watch: its stream is closed from a thread that is not the (possibly blocked) reader. */
        fun stop() {
            stopped = true
            val open = stream ?: return
            stream = null
            Thread({ runCatching { open.close() } }, "jmap-push-close").apply { isDaemon = true }.start()
        }
    }

    /**
     * Watch [mailbox] through the server's event source: one request at a time with closeafter=state, so each
     * StateChange ends the response. A new Email state is turned into the IMAP-style changes the index expects:
     * Flags for known messages whose keywords changed, then Exists/Expunge from the folder's new total. Any failure
     * (network, trust, redirect, off-host URL, bad data) ends the watch with WatchLost; the caller restarts it.
     */
    override suspend fun watch(mailbox: String, onChange: (MailboxChange) -> Unit) {
        startWatch(mailbox, onChange, fromResume = false)
    }

    private suspend fun startWatch(mailbox: String, onChange: (MailboxChange) -> Unit, fromResume: Boolean) {
        val armed = ArmedWatch(mailbox, onChange)
        val epoch = synchronized(watchGate) {
            // A stop or close that landed after resume sampled the flag must not be re-armed.
            if (fromResume && !restartWatchOnResume) return
            armedWatch = armed
            watchEpoch
        }
        haltWatchJob()
        val (mailboxId, startState, startTotal) = lock.withLock {
            ensureListedLocked(mailbox)
            val id = idOfLocked(mailbox)
            val state = call(emailStateRequest(accountId())) { root -> emailState(root) }
            val total = call(mailboxGetRequest(accountId(), id)) { root -> mailboxTotal(root, id) }
            Triple(id, state, total)
        }
        val s = need()
        val url = try { jmapEventSourceUrl(s.eventSourceUrl, accountKey) } catch (e: JmapFailure) { throw MailFailure(e.text) }
        val host = hostOf(url, accountKey) ?: throw MailFailure("jmap event source url is not https")
        if (!host.equals(hostOf(s.apiUrl, accountKey), ignoreCase = true)) throw MailFailure("jmap event source is on another host")
        val auth = authorization
        val streamPin = pin
        val accountId = accountId()
        synchronized(watchGate) {
            if (epoch != watchEpoch || armedWatch !== armed) return
            val run = WatchRun()
            watchRun = run
            restartWatchOnResume = false
            watchJob = watchScope.launch {
                if (epoch != watchEpoch) return@launch
                var state = startState
                var total = startTotal
                try {
                    while (isActive && !run.stopped) {
                        val events = readStream(run, url, host, auth, streamPin)
                        if (!isActive) break
                        val next = events.firstNotNullOfOrNull { jmapPushEmailState(it.data, accountId) } ?: continue
                        if (next == state) continue
                        val (flags, newTotal, newState) = lock.withLock { changesSince(state, mailboxId) }
                        state = newState
                        for (change in flags) onChange(change)
                        if (newTotal > total) onChange(MailboxChange.Exists(newTotal))
                        if (newTotal < total) onChange(MailboxChange.Expunge(newTotal))
                        total = newTotal
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (isActive) onChange(MailboxChange.WatchLost)
                }
            }
        }
    }

    /** Cancels the running source and joins it. Does not forget [armedWatch]; [stopWatch] does that. */
    private suspend fun haltWatchJob() {
        val taken = synchronized(watchGate) {
            val job = watchJob ?: return
            watchJob = null
            val run = watchRun
            watchRun = null
            job to run
        }
        taken.first.cancel()
        taken.second?.stop() // unblocks a read that is waiting on the server
        // A TLS read blocked in another thread (or a server that holds the response headers until the first
        // event, as Stalwart's eventsource did in the lab) is not always freed by close(); the read times out on
        // its own (readTimeout), so do not hold the caller hostage to it.
        kotlinx.coroutines.withTimeoutOrNull(STOP_WATCH_WAIT_MS) { taken.first.join() }
    }

    override suspend fun stopWatch() {
        synchronized(watchGate) {
            armedWatch = null
            restartWatchOnResume = false
        }
        haltWatchJob()
    }

    override suspend fun suspendConnections() {
        var job: kotlinx.coroutines.Job? = null
        var run: WatchRun? = null
        synchronized(watchGate) {
            watchEpoch++
            if (armedWatch != null) restartWatchOnResume = true
            job = watchJob
            watchJob = null
            run = watchRun
            watchRun = null
        }
        job?.cancel()
        run?.stop()
    }

    override suspend fun resume() {
        val armed = synchronized(watchGate) {
            if (!restartWatchOnResume) return
            armedWatch ?: run {
                restartWatchOnResume = false
                return
            }
        }
        startWatch(armed.mailbox, armed.onChange, fromResume = true)
    }

    private fun readStream(run: WatchRun, url: String, host: String, auth: String, streamPin: String): List<JmapPushEvent> {
        if (run.stopped) return emptyList()
        val policy = accountPolicy()
        val exchange = try {
            streamFor(streamPin, policy)(url, auth)
        } catch (e: java.io.IOException) {
            throw JmapFailure(e.message ?: "jmap push failed")
        }
        run.stream = exchange
        // Stopped while connecting: this stream is nobody's; drop it now rather than read on it.
        if (run.stopped) {
            run.stream = null
            exchange.close()
            return emptyList()
        }
        try {
            val verdict = applyTlsPolicy(jmapVerdict(accountKey, url) { jmapNet { trust(host, exchange.peerDer(), streamPin) } }, policy)
            if (verdict.isNotEmpty()) throw JmapFailure(verdict)
            val status = jmapNet { exchange.status }
            if (status != 200) throw JmapFailure("jmap push status $status")
            val body = jmapNet { exchange.body() }
            return if (body.isBlank()) emptyList() else parseJmapPushEvents(body)
        } finally {
            if (run.stream === exchange) run.stream = null
            exchange.close()
        }
    }

    /** Email/changes since [since] (following hasMoreChanges), keywords of known messages, and the new total. */
    private suspend fun changesSince(since: String, mailboxId: String): Triple<List<MailboxChange>, Int, String> {
        var state = since
        val touched = LinkedHashSet<String>()
        var rounds = 0
        while (true) {
            val body = """{"using":$USING,"methodCalls":[["Email/changes",{"accountId":${q(accountId())},"sinceState":${q(state)},"maxChanges":500},"0"]]}"""
            val (next, ids, more) = call(body) { root -> parseChanges(root) }
            touched += ids
            state = next
            if (!more || ++rounds >= 20) break
        }
        val table = uids[mailboxId]
        val known = touched.filter { table?.byId?.containsKey(it) == true }
        val flags = if (known.isEmpty() || table == null) emptyList() else {
            val get = """{"using":$USING,"methodCalls":[["Email/get",{"accountId":${q(accountId())},"ids":${arr(known)},"properties":["id","keywords","mailboxIds"]},"0"]]}"""
            call(get) { root ->
                val list = args(root, "Email/get")?.get("list") as? Json.Arr ?: throw MailFailure("jmap response lacks Email/get")
                list.values.mapNotNull { (it as? Json.Obj)?.fields }.mapNotNull { f ->
                    val id = (f["id"] as? Json.Str)?.text ?: return@mapNotNull null
                    val inBox = ((f["mailboxIds"] as? Json.Obj)?.fields?.get(mailboxId) as? Json.Bool)?.value == true
                    if (!inBox) return@mapNotNull null
                    MailboxChange.Flags(table.uid(id), flagsOf(f["keywords"]))
                }
            }
        }
        val total = call(mailboxGetRequest(accountId(), mailboxId)) { root -> mailboxTotal(root, mailboxId) }
        return Triple(flags, total, state)
    }

    override suspend fun knownTrash(): String = lock.withLock { roles.entries.firstOrNull { it.value == "trash" }?.key.orEmpty() }

    override fun close() {
        var job: kotlinx.coroutines.Job? = null
        var run: WatchRun? = null
        synchronized(watchGate) {
            armedWatch = null
            restartWatchOnResume = false
            job = watchJob
            watchJob = null
            run = watchRun
            watchRun = null
        }
        job?.cancel()
        run?.stop()
        session = null
        authorization = ""
        password = ""
        ids.clear(); roles.clear(); uids.clear(); totals.clear(); parts.clear(); selectedPath = null
    }

    // ---- internals (call with [lock] held) ----

    private fun need(): JmapSession = session ?: throw MailFailure("not connected")
    private fun accountId(): String = need().primaryMailAccountId ?: throw MailFailure("jmap account id is empty")
    private fun accountName(): String = account?.username ?: throw MailFailure("not connected")
    private fun accountPolicy(): TlsPolicy = account?.tlsPolicy() ?: TlsPolicy()

    private fun idOfLocked(path: String): String =
        ids[path] ?: if (path.equals("INBOX", ignoreCase = true)) {
            roles.entries.firstOrNull { it.value == "inbox" }?.key?.let { ids[it] }
                ?: throw MailFailure("folder $path is not listed yet")
        } else {
            throw MailFailure("folder $path is not listed yet")
        }

    private fun emailIdLocked(uid: Long): String {
        val (path, table) = selectedTable()
        return table.byUid[uid] ?: throw MailFailure("unknown message in $path")
    }

    private suspend fun download(blobId: String, type: String): ByteArray {
        val policy = accountPolicy()
        return jmap { jmapDownload(need(), blobId, type, authorization, pin, getFor(pin, policy), trust, policy, accountKey) }
    }

    private fun selectedTable(): Pair<String, Uids> {
        val path = selectedPath ?: throw MailFailure("no folder selected")
        return path to uids.getOrPut(idOfLocked(path)) { Uids() }
    }

    private suspend fun searchFilter(filter: String): List<Long> = lock.withLock {
        val (path, table) = selectedTable()
        val mailboxId = idOfLocked(path)
        val inMailbox = """{"operator":"AND","conditions":[{"inMailbox":${q(mailboxId)}},$filter]}"""
        pagedHits(table, mailboxId, inMailbox, sortJson = null, threads = false).map { it.uid }.sorted()
    }

    private class Hit(val id: String, val uid: Long, val threadId: String)

    /**
     * Pages [Email/query] by [MAX_IDS]. Position is omitted on the first page. Each page also fetches receivedAt
     * and assigns UIDs. The next page is requested when this page added fewer ids than the mailbox total still
     * missing. A page that adds nothing stops.
     */
    private suspend fun pagedHits(table: Uids, mailboxId: String, filter: String, sortJson: String?, threads: Boolean): List<Hit> {
        val total = totals[mailboxId] ?: 0
        if (total <= 0) return emptyList()
        var position = 0
        var missing = total
        val seen = HashSet<String>()
        val hits = ArrayList<Hit>()
        val properties = if (threads) """["id","threadId","receivedAt"]""" else """["id","receivedAt"]"""
        while (true) {
            val positionJson = if (position == 0) "" else ""","position":$position"""
            val sortPart = if (sortJson == null) "" else ""","sort":$sortJson"""
            val body = """{"using":$USING,"methodCalls":[["Email/query",{"accountId":${q(accountId())},"filter":$filter$sortPart$positionJson,"limit":$MAX_IDS},"0"],["Email/get",{"accountId":${q(accountId())},"#ids":{"resultOf":"0","name":"Email/query","path":"/ids"},"properties":$properties},"1"]]}"""
            val (ids, fields) = call(body) { root -> queryIds(root) to emailsById(root) }
            var added = 0
            for (id in ids) {
                if (!seen.add(id)) continue
                val f = fields[id]
                val uid = table.uid(id, receivedAtEpoch(f?.get("receivedAt")))
                val threadId = (f?.get("threadId") as? Json.Str)?.text.orEmpty()
                hits += Hit(id, uid, threadId)
                added++
            }
            if (added == 0) break
            if (added >= missing) break
            missing -= added
            position += ids.size
        }
        return hits
    }

    private fun threadNodes(hits: List<Hit>): ThreadNode {
        val groups = LinkedHashMap<String, MutableList<Long>>()
        for (hit in hits) groups.getOrPut(hit.threadId.ifEmpty { "solo:${hit.id}" }) { ArrayList() } += hit.uid
        return ThreadNode(null, groups.values.map { uidsInThread ->
            ThreadNode(uidsInThread.first(), uidsInThread.drop(1).map { ThreadNode(it, emptyList()) })
        })
    }

    private suspend fun setEmails(updates: Map<String, Map<String, Any?>>) {
        val update = updates.entries.joinToString(",", "{", "}") { (id, patch) ->
            q(id) + ":" + patch.entries.joinToString(",", "{", "}") { (k, v) -> q(k) + ":" + if (v == null) "null" else "true" }
        }
        val body = """{"using":$USING,"methodCalls":[["Email/set",{"accountId":${q(accountId())},"update":$update},"0"]]}"""
        call(body) { root -> requireSetDone(root, updates.keys.toList(), destroyed = false) }
    }

    private suspend fun <T> call(body: String, parse: (Json.Obj) -> T): T {
        val s = need()
        val policy = accountPolicy()
        val result = jmap { jmapCall(s.apiUrl, body, authorization, pin, postFor(pin, policy), trust, policy, accountKey) }
        if (result.status != 200) throw MailFailure("jmap api status ${result.status}")
        val root = try {
            JsonParser(result.body).parseDocument()
        } catch (_: JsonBroken) {
            throw MailFailure("jmap response is not JSON")
        }
        if (root !is Json.Obj) throw MailFailure("jmap response is not an object")
        methodError(root)?.let { throw MailFailure("jmap error: $it") }
        return parse(root)
    }

    private suspend fun <T> jmap(block: () -> T): T = io {
        try {
            block()
        } catch (failure: JmapFailure) {
            throw MailFailure(failure.text)
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun parseRows(root: Json.Obj, table: Uids, mailbox: String): List<IndexRow> {
        val list = args(root, "Email/get")?.get("list") as? Json.Arr ?: throw MailFailure("jmap response lacks Email/get")
        return list.values.mapIndexedNotNull { index, item ->
            val f = (item as? Json.Obj)?.fields ?: return@mapIndexedNotNull null
            val id = (f["id"] as? Json.Str)?.text ?: return@mapIndexedNotNull null
            IndexRow(
                uid = table.uid(id, receivedAtEpoch(f["receivedAt"])),
                sequence = index + 1,
                flags = flagsOf(f["keywords"]),
                internalDateEpoch = epochSeconds((f["receivedAt"] as? Json.Str)?.text.orEmpty()),
                size = (f["size"] as? Json.Num)?.text?.toLongOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0,
                from = addressText(f["from"]),
                subject = (f["subject"] as? Json.Str)?.text.orEmpty(),
                envelopeDate = (f["sentAt"] as? Json.Str)?.text ?: (f["receivedAt"] as? Json.Str)?.text.orEmpty(),
                preview = (f["preview"] as? Json.Str)?.text,
                hasAttachment = (f["hasAttachment"] as? Json.Bool)?.value == true,
                recipients = addressText(f["to"]),
                mailbox = mailbox,
            )
        }
    }

    companion object {
        const val DELIMITER = '/'
        private const val MAX_WINDOW = 120
        private const val MAX_IDS = 50_000
        private const val USING = """["urn:ietf:params:jmap:core","urn:ietf:params:jmap:mail"]"""
        private val PROPERTIES = """["id","keywords","size","receivedAt","sentAt","subject","from","to","preview","hasAttachment"]"""

        internal fun q(text: String): String {
            val out = StringBuilder("\"")
            for (c in text) when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c < ' ' -> out.append(String.format("\\u%04x", c.code))
                else -> out.append(c)
            }
            return out.append('"').toString()
        }

        private fun arr(items: List<String>) = items.joinToString(",", "[", "]") { q(it) }

        internal fun mailboxGetRequest(accountId: String, id: String): String =
            """{"using":$USING,"methodCalls":[["Mailbox/get",{"accountId":${q(accountId)},"ids":[${q(id)}],"properties":["id","totalEmails"]},"0"]]}"""

        internal fun emailQueryGet(accountId: String, mailboxId: String, position: Int, limit: Int, newestFirst: Boolean): String {
            if (limit !in 1..MAX_WINDOW) throw MailFailure("jmap window is invalid")
            return """{"using":$USING,"methodCalls":[["Email/query",{"accountId":${q(accountId)},"filter":{"inMailbox":${q(mailboxId)}},"sort":[{"property":"receivedAt","isAscending":${!newestFirst}}],"position":$position,"limit":$limit},"0"],["Email/get",{"accountId":${q(accountId)},"#ids":{"resultOf":"0","name":"Email/query","path":"/ids"},"properties":$PROPERTIES},"1"]]}"""
        }

        internal fun emailGetRequest(accountId: String, ids: List<String>): String =
            """{"using":$USING,"methodCalls":[["Email/get",{"accountId":${q(accountId)},"ids":${arr(ids)},"properties":$PROPERTIES},"0"]]}"""

        /** IMAP system flags <-> JMAP keywords. \Deleted and \Recent have no JMAP form. */
        internal fun keywordOf(flag: String): String = when (flag.lowercase()) {
            "\\seen" -> "\$seen"
            "\\flagged" -> "\$flagged"
            "\\answered" -> "\$answered"
            "\\draft" -> "\$draft"
            "\\deleted" -> throw MailFailure("JMAP has no \\Deleted; delete moves to Trash or destroys")
            "\\recent" -> throw MailFailure("\\Recent cannot be set")
            else -> if (flag.startsWith("\\")) throw MailFailure("unknown system flag $flag") else flag
        }

        internal fun flagsOf(keywords: Json?): Set<String> {
            val fields = (keywords as? Json.Obj)?.fields ?: return emptySet()
            return fields.filterValues { it is Json.Bool && it.value }.keys.map { k ->
                when (k.lowercase()) {
                    "\$seen" -> "\\Seen"
                    "\$flagged" -> "\\Flagged"
                    "\$answered" -> "\\Answered"
                    "\$draft" -> "\\Draft"
                    else -> k
                }
            }.toSet()
        }

        internal fun keywordPatch(add: Set<String>, remove: Set<String>): Map<String, Any?> {
            val patch = LinkedHashMap<String, Any?>()
            for (f in add) patch["keywords/" + keywordOf(f)] = true
            for (f in remove) patch["keywords/" + keywordOf(f)] = null
            if (patch.isEmpty()) throw MailFailure("no flags to change")
            return patch
        }

        internal const val STOP_WATCH_WAIT_MS = 5_000L
        internal fun specialUseOf(role: String): String = "\\" + role.replaceFirstChar { it.uppercase() }

        internal fun args(root: Json.Obj, name: String): Map<String, Json>? {
            val responses = root.fields["methodResponses"] as? Json.Arr ?: return null
            for (item in responses.values) {
                val call = item as? Json.Arr ?: continue
                if (call.values.size < 2 || (call.values[0] as? Json.Str)?.text != name) continue
                return (call.values[1] as? Json.Obj)?.fields
            }
            return null
        }

        /** The type of the first method-level "error" response, if any. */
        internal fun methodError(root: Json.Obj): String? {
            val responses = root.fields["methodResponses"] as? Json.Arr ?: return null
            for (item in responses.values) {
                val call = item as? Json.Arr ?: continue
                if ((call.values.getOrNull(0) as? Json.Str)?.text == "error") {
                    val type = ((call.values.getOrNull(1) as? Json.Obj)?.fields?.get("type") as? Json.Str)?.text
                    return type ?: "unknown"
                }
            }
            return null
        }

        internal fun queryIds(root: Json.Obj): List<String> {
            val list = args(root, "Email/query")?.get("ids") as? Json.Arr ?: throw MailFailure("jmap response lacks Email/query")
            return list.values.map { (it as? Json.Str)?.text ?: throw MailFailure("jmap id is not text") }
        }

        private fun emailsById(root: Json.Obj): Map<String, Map<String, Json>> {
            val list = args(root, "Email/get")?.get("list") as? Json.Arr ?: throw MailFailure("jmap response lacks Email/get")
            val out = LinkedHashMap<String, Map<String, Json>>()
            for (item in list.values) {
                val f = (item as? Json.Obj)?.fields ?: continue
                val id = (f["id"] as? Json.Str)?.text ?: continue
                out[id] = f
            }
            return out
        }

        private fun receivedAtEpoch(value: Json?): Long? {
            val text = (value as? Json.Str)?.text ?: return null
            if (text.isBlank()) return null
            return epochSeconds(text).takeIf { it > 0 }
        }

        internal fun mailboxTotal(root: Json.Obj, id: String): Int {
            val list = args(root, "Mailbox/get")?.get("list") as? Json.Arr ?: throw MailFailure("jmap response lacks Mailbox/get")
            val box = list.values.mapNotNull { (it as? Json.Obj)?.fields }.firstOrNull { (it["id"] as? Json.Str)?.text == id }
                ?: throw MailFailure("folder is gone on the server")
            return (box["totalEmails"] as? Json.Num)?.text?.toLongOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
        }

        /** Every id was updated (or destroyed); otherwise the server's notUpdated/notDestroyed reason. */
        internal fun requireSetDone(root: Json.Obj, ids: List<String>, destroyed: Boolean) {
            val a = args(root, "Email/set") ?: throw MailFailure("jmap response lacks Email/set")
            val done: Set<String> = if (destroyed) {
                (a["destroyed"] as? Json.Arr)?.values?.mapNotNull { (it as? Json.Str)?.text }?.toSet() ?: emptySet()
            } else {
                (a["updated"] as? Json.Obj)?.fields?.keys ?: emptySet()
            }
            val missing = ids.filter { it !in done }
            if (missing.isEmpty()) return
            val not = (a[if (destroyed) "notDestroyed" else "notUpdated"] as? Json.Obj)?.fields
            val why = not?.get(missing.first())?.let { ((it as? Json.Obj)?.fields?.get("type") as? Json.Str)?.text }
            throw MailFailure("jmap did not change ${missing.size} message(s)" + (why?.let { ": $it" } ?: ""))
        }

        internal fun emailStateRequest(accountId: String): String =
            """{"using":$USING,"methodCalls":[["Email/get",{"accountId":${q(accountId)},"ids":[],"properties":["id"]},"0"]]}"""

        internal fun emailState(root: Json.Obj): String =
            (args(root, "Email/get")?.get("state") as? Json.Str)?.text ?: throw MailFailure("jmap response lacks a state")

        /** newState, created+updated+destroyed ids, hasMoreChanges. */
        internal fun parseChanges(root: Json.Obj): Triple<String, List<String>, Boolean> {
            val a = args(root, "Email/changes") ?: throw MailFailure("jmap response lacks Email/changes")
            val next = (a["newState"] as? Json.Str)?.text ?: throw MailFailure("jmap changes lack newState")
            val ids = listOf("created", "updated", "destroyed").flatMap { key ->
                (a[key] as? Json.Arr)?.values?.mapNotNull { (it as? Json.Str)?.text }.orEmpty()
            }
            return Triple(next, ids, (a["hasMoreChanges"] as? Json.Bool)?.value == true)
        }

        internal fun hostOf(url: String, accountKey: String): String? = try {
            val uri = java.net.URI(url)
            if (jmapUrlAllowed(accountKey, url)) uri.host?.removePrefix("[")?.removeSuffix("]") else null
        } catch (_: Exception) {
            null
        }

        internal fun firstEmail(root: Json.Obj): Map<String, Json> {
            val list = args(root, "Email/get")?.get("list") as? Json.Arr ?: throw MailFailure("jmap response lacks Email/get")
            return (list.values.firstOrNull() as? Json.Obj)?.fields ?: throw MailFailure("message is gone on the server")
        }

        internal fun bodyValue(email: Map<String, Json>, partId: String): Pair<String, Boolean> {
            val values = (email["bodyValues"] as? Json.Obj)?.fields ?: throw MailFailure("jmap message has no body values")
            val value = (values[partId] as? Json.Obj)?.fields ?: throw MailFailure("jmap part $partId has no value")
            val text = (value["value"] as? Json.Str)?.text ?: throw MailFailure("jmap part $partId has no value")
            val truncated = (value["isTruncated"] as? Json.Bool)?.value != false
            return text to truncated
        }

        internal fun slice(bytes: ByteArray, offset: Int, length: Int): ByteArray {
            if (offset >= bytes.size) return ByteArray(0)
            val end = (offset.toLong() + length).coerceAtMost(bytes.size.toLong()).toInt()
            return bytes.copyOfRange(offset, end)
        }

        /**
         * JMAP bodyStructure -> MimePart with IMAP section numbers (a single-part message is "1"; children of a
         * multipart are "n" / "parent.n"). Decoded content is served, so encoding is always identity; text parts
         * come back as UTF-8 bodyValues. Deeper than [MaxMimeDepth] becomes one opaque part.
         */
        private fun mimeOf(node: Json.Obj, parent: String, depth: Int, out: MutableMap<String, Part>): MimePart {
            val f = node.fields
            val full = (f["type"] as? Json.Str)?.text?.lowercase() ?: "text/plain"
            val type = full.substringBefore('/')
            val subtype = full.substringAfter('/', "plain")
            val kids = (f["subParts"] as? Json.Arr)?.values?.mapNotNull { it as? Json.Obj }.orEmpty()
            val section = if (parent.isEmpty() && type != "multipart") "1" else parent
            if (type == "multipart" && depth >= MaxMimeDepth) {
                return MimePart(section, "application", TooDeepSubtype, "", null, 0, emptyList())
            }
            val children = if (type == "multipart") {
                kids.mapIndexed { i, kid -> mimeOf(kid, if (parent.isEmpty()) "${i + 1}" else "$parent.${i + 1}", depth + 1, out) }
            } else {
                emptyList()
            }
            if (type != "multipart") {
                out[section] = Part((f["partId"] as? Json.Str)?.text, (f["blobId"] as? Json.Str)?.text, full)
            }
            return MimePart(
                section = section,
                type = type,
                subtype = subtype,
                disposition = (f["disposition"] as? Json.Str)?.text.orEmpty(),
                filename = (f["name"] as? Json.Str)?.text,
                size = (f["size"] as? Json.Num)?.text?.toLongOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0,
                children = children,
                charset = if (type == "text") "utf-8" else "",
                encoding = "",
            )
        }

        internal fun importedId(root: Json.Obj): String {
            val a = args(root, "Email/import") ?: throw MailFailure("jmap response lacks Email/import")
            val id = (((a["created"] as? Json.Obj)?.fields?.get("k1") as? Json.Obj)?.fields?.get("id") as? Json.Str)?.text
            if (!id.isNullOrBlank()) return id
            val why = (((a["notCreated"] as? Json.Obj)?.fields?.get("k1") as? Json.Obj)?.fields?.get("type") as? Json.Str)?.text
            throw MailFailure("jmap did not save the message" + (why?.let { ": $it" } ?: ""))
        }

        internal fun addressText(value: Json?): String {
            val list = (value as? Json.Arr)?.values ?: return ""
            return list.mapNotNull { (it as? Json.Obj)?.fields }.joinToString(", ") { f ->
                val name = (f["name"] as? Json.Str)?.text.orEmpty()
                val email = (f["email"] as? Json.Str)?.text.orEmpty()
                if (name.isNotBlank()) name else email
            }
        }

        internal fun epochSeconds(utc: String): Long = try {
            java.time.Instant.parse(utc).epochSecond
        } catch (_: Exception) {
            0L
        }
    }
}

/** The app's JMAP session: platform discovery and HTTPS, the account's password from its own store. */
fun platformJmapMailSession(): JmapMailSession {
    lateinit var session: JmapMailSession
    session = JmapMailSession(
    discover = { account, auth -> offerForAccount(account, platformJmapExchange(account.jmapCertPin, account.tlsPolicy()), authorization = auth, accountKey = session.accountKey) },
    passwordOf = { _ ->
        val context = org.dlang.liveimap.engine.currentApplication() ?: throw JmapFailure("keystore unavailable")
        val owner = kotlin.coroutines.coroutineContext[org.dlang.liveimap.accounts.AccountTarget]?.accountId
            ?: org.dlang.liveimap.settings.DataStoreSettingsStore(context).chosenAccountId()
            ?: throw JmapFailure("no account")
        org.dlang.liveimap.settings.DataStoreSettingsStore(context, owner).password()
    },
    savePin = { pin ->
        val context = org.dlang.liveimap.engine.currentApplication() ?: throw JmapFailure("keystore unavailable")
        val owner = kotlin.coroutines.coroutineContext[org.dlang.liveimap.accounts.AccountTarget]?.accountId
            ?: org.dlang.liveimap.settings.DataStoreSettingsStore(context).chosenAccountId()
            ?: throw JmapFailure("no account")
        org.dlang.liveimap.settings.DataStoreSettingsStore(context, owner).update { it.copy(jmapCertPin = pin) }
    },
    )
    return session
}
