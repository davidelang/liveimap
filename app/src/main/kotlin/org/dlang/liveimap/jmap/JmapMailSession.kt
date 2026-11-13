package org.dlang.liveimap.jmap
import org.dlang.liveimap.i18n.S

import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dlang.liveimap.LiveImapApplication
import org.dlang.liveimap.engine.PeerTrust
import org.dlang.liveimap.engine.TlsPolicy
import org.dlang.liveimap.engine.TrafficWriter
import org.dlang.liveimap.engine.applyTlsPolicy
import org.dlang.liveimap.session.CertPrompt
import org.dlang.liveimap.session.FolderEntry
import org.dlang.liveimap.session.IndexMode
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.session.MessageRef
import org.dlang.liveimap.session.ThreadFlat
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
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.tlsPolicy

/**
 * A JMAP account (RFC 8620/8621) behind [MailSession] (roadmap jmap.md). LiveIMAP's own client over the platform
 * HTTPS stack; every exchange goes through [jmapCall], so it keeps the existing guarantees:
 *  - F53: no redirects are followed, and every session URL stays on the discovered host;
 *  - F58: JSON nesting is capped (JsonParser) and response bodies are size-capped (readJmapBody);
 *  - F63: trust uses the account's own JMAP pin ([Settings.jmapCertPin]), never the IMAP one;
 *  - F68: network IOExceptions surface as failures (here [MailFailure]), never as crashes.
 *
 * Folder names are paths of JMAP mailbox names joined by '/'. JMAP email ids are strings; this session numbers
 * a mailbox's existing mail once, oldest receivedAt first, before it reports uidNext or assigns any other UID.
 * An id that was not in that query takes the next UID. The epoch chosen at [open] is the UIDVALIDITY, so a
 * reopened session is a new UIDVALIDITY and every list re-reads. JMAP has no \Deleted: [storeFlags] refuses it
 * and deletes are destroy/move.
 */
class JmapMailSession(
    private val accountKey: String,
    private val traffic: TrafficWriter?,
    /** account, authorization (sent only if the session resource needs it) */
    private val discover: (Settings, String) -> JmapOffer,
    private val passwordOf: suspend (Settings) -> String,
    /** Stores a confirmed JMAP pin on the account (field-level update, F75). */
    private val savePin: suspend (String) -> Unit,
    /** One auth failure refreshes the bearer. Null on a session that has no token store. */
    private val onBearerRejected: ((Settings, String) -> String?)? = null,
    /** Clears the auth-failure streak after the bearer is accepted. */
    private val onBearerAccepted: (() -> Unit)? = null,
) : MailSession {
    @Volatile private var certConfirmer: (suspend (CertPrompt) -> Boolean)? = null
    @Volatile private var useBearer: Boolean = false

    override fun setCertConfirmer(confirm: (suspend (CertPrompt) -> Boolean)?) {
        certConfirmer = confirm
    }

    private class SessionCall(
        val block: suspend () -> Any?,
        val result: CompletableDeferred<Any?>,
    )

    private class SessionSlot(
        val commands: Channel<SessionCall>,
        val scope: CoroutineScope,
    ) {
        val current = AtomicReference<SessionCall?>(null)
    }

    private class OnSession(val owner: JmapMailSession) : AbstractCoroutineContextElement(OnSession) {
        companion object Key : CoroutineContext.Key<OnSession>
    }

    private val commandSlot = AtomicReference<SessionSlot?>(null)
    @Volatile private var commandsClosed = false

    private fun failClosed(call: SessionCall) {
        call.result.completeExceptionally(CancellationException("session closed"))
    }

    private fun drainClosed(slot: SessionSlot) {
        while (true) {
            val extra = slot.commands.tryReceive().getOrNull() ?: break
            failClosed(extra)
        }
    }

    private fun retireSlot(slot: SessionSlot) {
        slot.scope.cancel()
        slot.commands.close()
    }

    // The consumer starts only after this slot is still the one compareAndSet installed.
    private fun ensureSlot(): SessionSlot? {
        while (true) {
            if (commandsClosed) return null
            commandSlot.get()?.let { return it }
            val slot = SessionSlot(
                Channel(Channel.UNLIMITED),
                CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )
            if (!commandSlot.compareAndSet(null, slot)) {
                retireSlot(slot)
                continue
            }
            if (commandSlot.get() !== slot || commandsClosed) {
                if (commandSlot.get() === slot) commandSlot.compareAndSet(slot, null)
                retireSlot(slot)
                if (commandsClosed) return null
                continue
            }
            slot.scope.launch { consumeSlot(slot) }
            return slot
        }
    }

    private suspend fun consumeSlot(slot: SessionSlot) {
        try {
            for (call in slot.commands) {
                if (commandSlot.get() !== slot) {
                    failClosed(call)
                    drainClosed(slot)
                    break
                }
                slot.current.set(call)
                if (commandSlot.get() !== slot) {
                    if (slot.current.compareAndSet(call, null)) failClosed(call)
                    drainClosed(slot)
                    break
                }
                try {
                    val value = withContext(OnSession(this@JmapMailSession)) { call.block() }
                    call.result.complete(value)
                } catch (error: Throwable) {
                    call.result.completeExceptionally(error)
                    if (commandSlot.get() !== slot) {
                        drainClosed(slot)
                        break
                    }
                } finally {
                    slot.current.compareAndSet(call, null)
                }
            }
        } finally {
            drainClosed(slot)
        }
    }

    /** Drops the command slot. Queued and in-flight waiters fail and are not parked. */
    private fun retireCommands() {
        commandsClosed = true
        val slot = commandSlot.getAndSet(null) ?: return
        slot.current.getAndSet(null)?.let { failClosed(it) }
        slot.commands.close()
        drainClosed(slot)
        slot.scope.cancel()
    }

    private suspend fun <T> onSession(block: suspend () -> T): T {
        if (coroutineContext[OnSession]?.owner === this) return block()
        if (commandsClosed) throw CancellationException("session closed")
        val result = CompletableDeferred<Any?>()
        val call = SessionCall({ block() }, result)
        val slot = ensureSlot()
        if (slot == null) {
            failClosed(call)
        } else {
            try {
                slot.commands.send(call)
            } catch (_: ClosedSendChannelException) {
                failClosed(call)
            }
        }
        @Suppress("UNCHECKED_CAST")
        return result.await() as T
    }

    private var account: Settings? = null
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
     * Per mailbox id: email id <-> UID. Existing mail is numbered once, in query order, before [next] is reported.
     * The same id keeps its UID. A new id only uses [allocFromNext], so it stays at or above the [next] already
     * reported. [receivedAt] does not put it below that [next]. [select] reports [next].
     */
    internal class Uids {
        val byId = HashMap<String, Long>()
        val byUid = HashMap<Long, String>()
        var next = 1L
        /** Set after the once-only receivedAt id query for this mailbox. */
        var existingNumbered = false

        /** [receivedAt] is not used. A new id is [allocFromNext]. */
        internal fun uid(id: String, receivedAt: Long? = null): Long {
            byId[id]?.let { return it }
            val assigned = allocFromNext()
            byId[id] = assigned
            byUid[assigned] = id
            return assigned
        }

        private fun allocFromNext(): Long {
            var u = next
            while (byUid.containsKey(u)) u++
            next = u + 1
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
        /** A truncated body value shorter than the account response cap will not grow on another Email/get. */
        var prefixFinal: Boolean = false
    }
    private val parts = LinkedHashMap<String, Map<String, Part>>()
    /** Email id whose structure the open reader last fetched. Select does not clear it. */
    private var openEmailId: String? = null

    override val capabilities: Set<String> get() = setOf("IMAP4rev1", "JMAP")

    override suspend fun open(account: Settings): OpenResult = onSession {
        val key = account.preservedSecurityKey()
        if (key != null) return@onSession OpenResult.Failed("account settings need attention: $key")
        var trusted = account
        useBearer = account.accountAuth == "oauth2"
        var loginPassword = passwordOf(account)
        var retriedAuth = false
        while (true) {
            val loginAuthorization = jmapAuthorization(account.username, loginPassword, useBearer)
            try {
                val offer = try {
                    io { JmapResponseCap.use(account.maxJmapResponseBytes.toInt()) { discover(account, loginAuthorization) } }
                } catch (pinOffer: JmapPinOffer) {
                    // As IMAP (F64): show the certificate, and on yes pin it as the JMAP pin (F63) and retry once.
                    val shown = PeerTrust.offer(pinOffer.ders)
                    val confirm = certConfirmer
                    if (shown == null || shown.size != 5 || confirm == null) throw pinOffer
                    val prompt = CertPrompt(pinOffer.text, shown[0], shown[1], shown[2], shown[3], shown[4], pinOffer.host, runCatching { jmapServerTarget(account.imapHost).port }.getOrDefault(443))
                    if (!confirm(prompt)) throw pinOffer
                    savePin(prompt.fingerprint)
                    trusted = account.copy(jmapCertPin = prompt.fingerprint)
                    try {
                        io { JmapResponseCap.use(trusted.maxJmapResponseBytes.toInt()) { discover(trusted, loginAuthorization) } }
                    } catch (again: JmapPinOffer) {
                        throw JmapFailure(again.text)
                    }
                }
                val mail = offer as? JmapOffer.Mail ?: return@onSession OpenResult.Failed("server offers no JMAP mail")
                if (mail.session.primaryMailAccountId.isNullOrBlank()) return@onSession OpenResult.Failed("jmap account id is empty")
                this.account = trusted
                session = mail.session
                pin = trusted.jmapCertPin
                password = loginPassword
                authorization = loginAuthorization
                epoch = (System.currentTimeMillis() / 1000).coerceAtLeast(1L) and 0x7fffffffL
                ids.clear(); roles.clear(); uids.clear(); totals.clear(); parts.clear(); selectedPath = null
                openEmailId = null
                if (useBearer) onBearerAccepted?.invoke()
                return@onSession OpenResult.Connected
            } catch (failure: JmapFailure) {
                if (useBearer && org.dlang.liveimap.oauth.oauthAuthenticationFailed(failure.text)) {
                    if (!retriedAuth) {
                        val next = onBearerRejected?.invoke(account, loginPassword)
                        if (next != null) {
                            retriedAuth = true
                            loginPassword = next
                            continue
                        }
                    } else {
                        onBearerRejected?.invoke(account, loginPassword)
                    }
                    return@onSession OpenResult.Failed("Sign in again")
                }
                return@onSession OpenResult.Failed(failure.text)
            }
        }
        return@onSession OpenResult.Failed("Sign in again")
    }

    override suspend fun namespaces(): List<Namespace> = listOf(Namespace("", DELIMITER, NamespaceKind.Personal))

    override suspend fun listLevel(prefix: String, parentMailbox: String?, unreadCounts: Boolean): List<FolderEntry> =
        onSession { levelLocked(parentMailbox, unreadCounts) }

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
            val rows = jmap { jmapFolderLevel(s, parentId, accountName(), password, pin, pinnedJmapPost(pin, accountKey, traffic, policy), PeerTrust::check, policy, accountKey, useBearer) }
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

    override suspend fun select(mailbox: String): SelectResult = onSession {
        ensureListedLocked(mailbox)
        val id = idOfLocked(mailbox)
        val total = call(mailboxGetRequest(accountId(), id)) { root -> mailboxTotal(root, id) }
        totals[id] = total
        numberExistingLocked(id)
        selectedPath = mailbox
        dropClosedPartBytes()
        SelectResult(epoch, (uids[id]?.next ?: 1L), total)
    }

    override suspend fun unselect() {
        onSession { selectedPath = null }
    }

    override suspend fun fetchIndex(request: IndexRequest): List<IndexRow> = onSession {
        val id = idOfLocked(request.mailbox)
        numberExistingLocked(id)
        val table = uids.getOrPut(id) { Uids() }
        val body = when (request.mode) {
            IndexMode.ArrivalNewest -> emailQueryGet(accountId(), id, 0, request.limit, newestFirst = true)
            IndexMode.ArrivalOldest -> emailQueryGet(accountId(), id, 0, request.limit, newestFirst = false)
            IndexMode.ArrivalRange -> {
                val first = request.firstSequence.coerceAtLeast(1)
                val last = request.lastSequence.coerceAtLeast(first)
                emailQueryGet(accountId(), id, first - 1, (last - first + 1).coerceAtMost(jmapWindow()), newestFirst = false)
            }
            IndexMode.ByUid -> {
                val wanted = request.uids.mapNotNull { table.byUid[it] }
                if (wanted.isEmpty()) return@onSession emptyList()
                emailGetRequest(accountId(), wanted)
            }
        }
        call(body) { root -> parseRows(root, table, request.mailbox) }
    }

    override suspend fun storeFlags(uids: List<Long>, add: Set<String>, remove: Set<String>) {
        onSession {
            val (path, table) = selectedTable()
            val patch = keywordPatch(add, remove)
            val emailIds = uids.map { table.byUid[it] ?: throw MailFailure("unknown message in $path") }
            setEmails(emailIds.associateWith { patch })
        }
    }

    override suspend fun uidExpungeDeleted() {
        // JMAP has no \Deleted: nothing is ever marked, so there is nothing to expunge.
    }

    override suspend fun expungeAllDeleted() {
    }

    override suspend fun uidExpunge(uids: List<Long>) {
        onSession {
            val (path, table) = selectedTable()
            val emailIds = uids.map { table.byUid[it] ?: throw MailFailure("unknown message in $path") }
            val body = """{"using":$USING,"methodCalls":[["Email/set",{"accountId":${q(accountId())},"destroy":${arr(emailIds)}},"0"]]}"""
            call(body) { root -> requireSetDone(root, emailIds, destroyed = true) }
            for (e in emailIds) table.byId.remove(e)?.let { table.byUid.remove(it) }
        }
    }

    override suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String) {
        onSession {
            val (path, table) = selectedTable()
            val from = idOfLocked(path)
            ensureListedLocked(targetMailbox)
            val to = idOfLocked(targetMailbox)
            val emailIds = uids.map { table.byUid[it] ?: throw MailFailure("unknown message in $path") }
            setEmails(emailIds.associateWith { mapOf("mailboxIds/$from" to null, "mailboxIds/$to" to true) })
        }
    }

    override suspend fun copyUids(uids: List<Long>, targetMailbox: String) {
        onSession {
            val (path, table) = selectedTable()
            ensureListedLocked(targetMailbox)
            val to = idOfLocked(targetMailbox)
            val emailIds = uids.map { table.byUid[it] ?: throw MailFailure("unknown message in $path") }
            setEmails(emailIds.associateWith { mapOf("mailboxIds/$to" to true) })
        }
    }

    override suspend fun selectedExists(): Int {
        val path = onSession { selectedPath } ?: return 0
        return select(path).exists
    }

    override suspend fun searchText(query: String): LongArray = searchFilter("""{"text":${q(query)}}""")

    override suspend fun searchCriterion(kind: String, argument: String): LongArray {
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

    override suspend fun sort(key: SortKey, newestFirst: Boolean): List<Long> = onSession {
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
    override suspend fun thread(key: SortKey): ThreadFlat = onSession {
        val (path, table) = selectedTable()
        val mailboxId = idOfLocked(path)
        val hits = pagedHits(table, mailboxId, """{"inMailbox":${q(mailboxId)}}""", """[{"property":"receivedAt","isAscending":true}]""", threads = true)
        threadFlat(hits)
    }

    override suspend fun fetchStructure(uid: Long): MimePart = onSession {
        val id = emailIdLocked(uid)
        val body = """{"using":$USING,"methodCalls":[["Email/get",{"accountId":${q(accountId())},"ids":[${q(id)}],"properties":["bodyStructure"],"bodyProperties":["partId","blobId","type","charset","disposition","name","size","subParts"]},"0"]]}"""
        val structure = call(body) { root -> firstEmail(root)["bodyStructure"] as? Json.Obj ?: throw MailFailure("jmap message has no structure") }
        val map = HashMap<String, Part>()
        val tree = mimeOf(structure, "", 0, map)
        openEmailId = id
        rememberParts(id, map)
        dropClosedPartBytes()
        tree
    }

    override suspend fun peekPart(uid: Long, section: String, offset: Int, length: Int): ByteArray = onSession {
        if (offset < 0 || length < 0) throw MailFailure("part range is invalid")
        val id = emailIdLocked(uid)
        if (section.equals("HEADER", ignoreCase = true)) {
            val body = """{"using":$USING,"methodCalls":[["Email/get",{"accountId":${q(accountId())},"ids":[${q(id)}],"properties":["headers"]},"0"]]}"""
            val bytes = call(body) { root -> headerBlock(firstEmail(root)) }
            return@onSession slice(bytes, offset, length)
        }
        val part = parts[id]?.get(section) ?: throw MailFailure("part $section is not known; read the structure first")
        // Length 0 is the rest of the section, so a short cache is not enough.
        val needEnd = if (length == 0) Long.MAX_VALUE else offset.toLong() + length.toLong()
        val cached = part.bytes
        if (cached != null && (part.whole || part.prefixFinal || needEnd <= cached.size)) return@onSession slice(cached, offset, length)
        val have = cached?.size ?: 0
        if (part.partId != null && part.type.startsWith("text/", ignoreCase = true)) {
            // The first text fetch asks for the account cap. isTruncated means this prefix is not the whole part.
            val cap = (account?.maxJmapResponseBytes ?: JmapMaxBody).toLong()
            if (have.toLong() >= cap) return@onSession slice(cached ?: ByteArray(0), offset, length)
            val body = """{"using":$USING,"methodCalls":[["Email/get",{"accountId":${q(accountId())},"ids":[${q(id)}],"properties":["bodyValues"],"bodyProperties":["partId"],"fetchAllBodyValues":true,"maxBodyValueBytes":$cap},"0"]]}"""
            val (text, truncated) = call(body) { root -> bodyValue(firstEmail(root), part.partId) }
            val fetched = text.toByteArray(Charsets.UTF_8)
            val tail = if (fetched.size > have) fetched.copyOfRange(have, fetched.size) else ByteArray(0)
            part.bytes = if (cached == null) fetched else cached + tail
            // A truncated value can be shorter than the cap. It is not the whole part, and another Email/get will not grow it.
            part.whole = !truncated
            part.prefixFinal = truncated && fetched.size < cap
        } else if (cached == null) {
            val blob = part.blobId ?: throw MailFailure("part $section has no content")
            part.bytes = download(blob, part.type)
        }
        slice(part.bytes ?: ByteArray(0), offset, length)
    }

    override suspend fun fetchRfc822(uid: Long): ByteArray = onSession {
        val id = emailIdLocked(uid)
        val body = """{"using":$USING,"methodCalls":[["Email/get",{"accountId":${q(accountId())},"ids":[${q(id)}],"properties":["blobId"]},"0"]]}"""
        val blob = call(body) { root -> (firstEmail(root)["blobId"] as? Json.Str)?.text ?: throw MailFailure("jmap message has no blob") }
        download(blob, "message/rfc822")
    }

    override suspend fun append(mailbox: String, rfc822: ByteArray, flags: Set<String>) {
        appendReturningUid(mailbox, rfc822, flags)
    }

    /** Upload the message as a blob, then Email/import it into [mailbox] with [flags] as keywords. */
    override suspend fun appendReturningUid(mailbox: String, rfc822: ByteArray, flags: Set<String>): Long = onSession {
        if (rfc822.isEmpty()) throw MailFailure("message is empty")
        ensureListedLocked(mailbox)
        val mailboxId = idOfLocked(mailbox)
        val keywords = flags.map { keywordOf(it) }.distinct().joinToString(",", "{", "}") { q(it) + ":true" }
        val s = need()
        val policy = accountPolicy()
        val blob = jmap { jmapUpload(s, rfc822, accountName(), password, pin, pinnedJmapUpload(pin, accountKey, traffic, policy), PeerTrust::check, policy, accountKey, useBearer) }
        val body = """{"using":$USING,"methodCalls":[["Email/import",{"accountId":${q(accountId())},"emails":{"k1":{"blobId":${q(blob)},"mailboxIds":{${q(mailboxId)}:true},"keywords":$keywords}}},"0"]]}"""
        val created = call(body) { root -> importedId(root) }
        uids.getOrPut(mailboxId) { Uids() }.uid(created)
    }

    /** "Send over JMAP" (account.emailSubmission): the message file already written, then blob upload, Email/import,
     *  and EmailSubmission/set. Not Email/set create. Uses the session from [open]; discovery is not called again.
     *  Without the option a JMAP account sends through its SMTP server, which is not this session. Returns -1:
     *  EmailSubmission reports no DSN choice here. */
    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>, dsnNotify: Int): Int {
        val spilled = File.createTempFile("liveimap-jmap-", ".rfc822")
        try {
            spilled.writeBytes(rfc822)
            return smtpSendFile(spilled, recipients, dsnNotify)
        } finally {
            spilled.delete()
        }
    }

    override suspend fun appendFile(mailbox: String, file: File, flags: Set<String>) {
        appendReturningUidFile(mailbox, file, flags)
    }

    override suspend fun appendReturningUidFile(mailbox: String, file: File, flags: Set<String>): Long = onSession {
        if (!file.isFile || file.length() == 0L) throw MailFailure("message is empty")
        ensureListedLocked(mailbox)
        val mailboxId = idOfLocked(mailbox)
        val keywords = flags.map { keywordOf(it) }.distinct().joinToString(",", "{", "}") { q(it) + ":true" }
        val s = need()
        val policy = accountPolicy()
        val blob = jmap {
            jmapUpload(
                s,
                file,
                accountName(),
                password,
                pin,
                pinnedJmapUploadFile(pin, accountKey, traffic, policy),
                PeerTrust::check,
                policy,
                accountKey,
                useBearer,
            )
        }
        val body = """{"using":$USING,"methodCalls":[["Email/import",{"accountId":${q(accountId())},"emails":{"k1":{"blobId":${q(blob)},"mailboxIds":{${q(mailboxId)}:true},"keywords":$keywords}}},"0"]]}"""
        val created = call(body) { root -> importedId(root) }
        uids.getOrPut(mailboxId) { Uids() }.uid(created)
    }

    override suspend fun smtpSendFile(file: File, recipients: List<String>, dsnNotify: Int): Int = onSession {
        sendOpenSession(file, recipients)
    }

    override suspend fun smtpSendFromFile(
        file: File,
        recipients: List<String>,
        dsnNotify: Int,
        envelopeFrom: String,
    ): Int = smtpSendFile(file, recipients, dsnNotify)

    private suspend fun sendOpenSession(file: File, recipients: List<String>): Int {
        val saved = account ?: throw MailFailure("not connected")
        if (!saved.emailSubmission) throw MailFailure("a JMAP account sends through its SMTP server or EmailSubmission")
        if (!file.isFile || file.length() == 0L) throw MailFailure("message is empty")
        val discovered = need()
        val policy = accountPolicy()
        jmap {
            jmapSend(
                JmapOffer.Mail(discovered),
                file,
                saved.email,
                recipients,
                accountName(),
                password,
                pin,
                pinnedJmapUploadFile(pin, accountKey, traffic, policy),
                pinnedJmapPost(pin, accountKey, traffic, policy),
                PeerTrust::check,
                saved.sentMailbox,
                draftsName = saved.postponedMailbox,
                onNotice = { copyId ->
                    val app = LiveImapApplication.context
                    if (app != null) {
                        val text = S(app, "The staging copy %1\$s could not be removed", copyId)
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            android.widget.Toast.makeText(app, text, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                },
                policy = policy,
                accountKey = accountKey,
                bearer = useBearer,
            )
        }
        return -1
    }

    // ---- push (RFC 8620 §7.3 EventSource) ----

    private val watchScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

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

    /** Epoch, the armed watch, and the job are one snapshot so a suspend cannot miss a launch. */
    private data class WatchFields(
        /** Latest [watch] target, published before setup RPCs so a suspend during setup can be restarted. */
        val armedWatch: ArmedWatch? = null,
        /** Bumped by [suspendConnections] so a watch still in setup does not start the event source. */
        val watchEpoch: Int = 0,
        /** Set only by [suspendConnections]. [stopWatch] and [close] clear it so a deliberate stop stays down. */
        val restartWatchOnResume: Boolean = false,
        val watchJob: kotlinx.coroutines.Job? = null,
        /** F151: the current watch's own stream holder; an orphaned watch only ever touches its own. */
        val watchRun: WatchRun? = null,
    )

    private val watchFields = AtomicReference(WatchFields())

    /**
     * Watch [mailbox] through the server's event source: one request at a time with closeafter=state, so each
     * StateChange ends the response. A new Email state is turned into the IMAP-style changes the index expects:
     * Flags for known messages whose keywords changed, then Exists/Expunge from the folder's new total. An
     * authentication failure refreshes once. A second one marks the tokens unusable and asks the user to sign in
     * again. Any other failure ends the watch with WatchLost; the caller restarts it.
     */
    override suspend fun watch(mailbox: String, onChange: (MailboxChange) -> Unit) {
        startWatch(mailbox, onChange, fromResume = false)
    }

    private suspend fun startWatch(mailbox: String, onChange: (MailboxChange) -> Unit, fromResume: Boolean) {
        val armed = ArmedWatch(mailbox, onChange)
        var epoch = 0
        while (true) {
            val cur = watchFields.get()
            // A stop or close that landed after resume sampled the flag must not be re-armed.
            if (fromResume && !cur.restartWatchOnResume) return
            if (watchFields.compareAndSet(cur, cur.copy(armedWatch = armed))) {
                epoch = cur.watchEpoch
                break
            }
        }
        haltWatchJob()
        val (mailboxId, startState, startTotal) = onSession {
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
        var auth = authorization
        val streamPin = pin
        val accountId = accountId()
        var pushRetried = false
        val run = WatchRun()
        // Lazy so the snapshot publishes this job before the body runs. start() is after that publish.
        val job = watchScope.launch(start = CoroutineStart.LAZY) {
            if (epoch != watchFields.get().watchEpoch) return@launch
            var state = startState
            var total = startTotal
            try {
                while (isActive && !run.stopped) {
                    val events = try {
                        readStream(run, url, host, auth, streamPin)
                    } catch (failure: JmapFailure) {
                        if (!(useBearer && org.dlang.liveimap.oauth.oauthAuthenticationFailed(failure.text))) {
                            throw failure
                        }
                        val acct = account
                        if (pushRetried || acct == null) {
                            account?.let { onBearerRejected?.invoke(it, password) }
                            throw MailFailure("Sign in again")
                        }
                        val next = onBearerRejected?.invoke(acct, password)
                        if (next == null) throw MailFailure("Sign in again")
                        password = next
                        authorization = jmapAuthorization(acct.username, next, true)
                        auth = authorization
                        pushRetried = true
                        try {
                            readStream(run, url, host, auth, streamPin)
                        } catch (again: JmapFailure) {
                            if (org.dlang.liveimap.oauth.oauthAuthenticationFailed(again.text)) {
                                onBearerRejected?.invoke(acct, password)
                                throw MailFailure("Sign in again")
                            }
                            throw again
                        }
                    }
                    if (pushRetried && useBearer) onBearerAccepted?.invoke()
                    pushRetried = false
                    if (!isActive) break
                    val next = events.firstNotNullOfOrNull { jmapPushEmailState(it.data, accountId) } ?: continue
                    if (next == state) continue
                    val (flags, newTotal, newState) = onSession { changesSince(state, mailboxId) }
                    state = newState
                    for (change in flags) onChange(change)
                    if (newTotal > total) onChange(MailboxChange.Exists(newTotal))
                    if (newTotal < total) onChange(MailboxChange.Expunge(newTotal))
                    total = newTotal
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (failure: MailFailure) {
                if (failure.text == "Sign in again") {
                    if (isActive) onChange(MailboxChange.SignInAgain(failure.text))
                } else if (isActive) {
                    onChange(MailboxChange.WatchLost)
                }
            } catch (_: Exception) {
                if (isActive) onChange(MailboxChange.WatchLost)
            }
        }
        while (true) {
            val cur = watchFields.get()
            if (epoch != cur.watchEpoch || cur.armedWatch !== armed) {
                job.cancel()
                return
            }
            if (watchFields.compareAndSet(cur, cur.copy(watchRun = run, restartWatchOnResume = false, watchJob = job))) break
        }
        job.start()
    }

    /** Cancels the running source and joins it. Does not forget [WatchFields.armedWatch]; [stopWatch] does that. */
    private suspend fun haltWatchJob() {
        val job: kotlinx.coroutines.Job
        val run: WatchRun?
        while (true) {
            val cur = watchFields.get()
            val held = cur.watchJob ?: return
            if (watchFields.compareAndSet(cur, cur.copy(watchJob = null, watchRun = null))) {
                job = held
                run = cur.watchRun
                break
            }
        }
        job.cancel()
        run?.stop() // unblocks a read that is waiting on the server
        // A TLS read blocked in another thread (or a server that holds the response headers until the first
        // event, as Stalwart's eventsource did in the lab) is not always freed by close(); the read times out on
        // its own (readTimeout), so do not hold the caller hostage to it.
        kotlinx.coroutines.withTimeoutOrNull(stopWatchWaitMs()) { job.join() }
    }

    override suspend fun stopWatch() {
        while (true) {
            val cur = watchFields.get()
            if (watchFields.compareAndSet(cur, cur.copy(armedWatch = null, restartWatchOnResume = false))) break
        }
        haltWatchJob()
    }

    override suspend fun suspendConnections() {
        var job: kotlinx.coroutines.Job? = null
        var run: WatchRun? = null
        while (true) {
            val cur = watchFields.get()
            val restart = if (cur.armedWatch != null) true else cur.restartWatchOnResume
            val next = cur.copy(
                watchEpoch = cur.watchEpoch + 1,
                restartWatchOnResume = restart,
                watchJob = null,
                watchRun = null,
            )
            if (watchFields.compareAndSet(cur, next)) {
                job = cur.watchJob
                run = cur.watchRun
                break
            }
        }
        job?.cancel()
        run?.stop()
    }

    override suspend fun resume() {
        while (true) {
            val cur = watchFields.get()
            if (!cur.restartWatchOnResume) return
            val armed = cur.armedWatch
            if (armed == null) {
                if (watchFields.compareAndSet(cur, cur.copy(restartWatchOnResume = false))) return
                continue
            }
            startWatch(armed.mailbox, armed.onChange, fromResume = true)
            return
        }
    }

    private fun readStream(run: WatchRun, url: String, host: String, auth: String, streamPin: String): List<JmapPushEvent> {
        if (run.stopped) return emptyList()
        val policy = accountPolicy()
        val exchange = try {
            platformJmapEventSource(url, auth, streamPin, accountKey, traffic, policy)
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
            val verdict = applyTlsPolicy(jmapVerdict(accountKey, url) { jmapNet { PeerTrust.check(host, exchange.peerDer(), streamPin) } }, policy)
            if (verdict.isNotEmpty()) throw JmapFailure(verdict)
            val status = jmapNet { exchange.status }
            if (status != 200) throw JmapFailure("jmap push status $status")
            val body = jmapNet { JmapResponseCap.use(account?.maxJmapResponseBytes?.toInt() ?: JmapMaxBody) { exchange.body() } }
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
            if (next != state) parts.clear()
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

    override suspend fun knownTrash(): String = onSession { roles.entries.firstOrNull { it.value == "trash" }?.key.orEmpty() }

    override fun close() {
        retireCommands()
        var job: kotlinx.coroutines.Job? = null
        var run: WatchRun? = null
        while (true) {
            val cur = watchFields.get()
            val next = cur.copy(armedWatch = null, restartWatchOnResume = false, watchJob = null, watchRun = null)
            if (watchFields.compareAndSet(cur, next)) {
                job = cur.watchJob
                run = cur.watchRun
                break
            }
        }
        job?.cancel()
        run?.stop()
        session = null
        authorization = ""
        password = ""
        ids.clear(); roles.clear(); uids.clear(); totals.clear(); parts.clear(); selectedPath = null
        openEmailId = null
    }

    // ---- internals (call on the session command) ----

    private fun refreshStoredBearer(): String? {
        if (!useBearer) return null
        val acct = account ?: return null
        val next = onBearerRejected?.invoke(acct, password) ?: return null
        password = next
        authorization = jmapAuthorization(acct.username, next, true)
        return next
    }

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
        return jmap { jmapDownload(need(), blobId, type, authorization, pin, pinnedJmapGet(pin, accountKey, traffic, policy), PeerTrust::check, policy, accountKey) }
    }

    /** Newest structures stay. The cap is the account's jmapPartCacheMessages. */
    private fun rememberParts(id: String, map: Map<String, Part>) {
        parts.remove(id)
        parts[id] = map
        val cap = account?.jmapPartCacheMessages ?: 200
        while (parts.size > cap) {
            val eldest = parts.keys.firstOrNull() ?: break
            parts.remove(eldest)
        }
    }

    /** Part bodies for every email except the open reader. The maps themselves stay. */
    private fun dropClosedPartBytes() {
        val open = openEmailId
        for ((id, cached) in parts) {
            if (id == open) continue
            for (part in cached.values) {
                part.bytes = null
                part.whole = false
                part.prefixFinal = false
            }
        }
    }

    private fun selectedTable(): Pair<String, Uids> {
        val path = selectedPath ?: throw MailFailure("no folder selected")
        return path to uids.getOrPut(idOfLocked(path)) { Uids() }
    }

    private suspend fun searchFilter(filter: String): LongArray = onSession {
        val (path, table) = selectedTable()
        val mailboxId = idOfLocked(path)
        val inMailbox = """{"operator":"AND","conditions":[{"inMailbox":${q(mailboxId)}},$filter]}"""
        val ids = pagedHits(table, mailboxId, inMailbox, sortJson = null, threads = false).map { it.uid }
        val out = ids.toLongArray()
        out.sort()
        out
    }

    private class Hit(val id: String, val uid: Long, val threadId: String)

    /**
     * Once per mailbox: [Email/query] by receivedAt ascending, [MAX_WINDOW], calculateTotal, position on later pages,
     * and no [Email/get]. Ids are assigned in the order returned. A missing total stops on a short page. A stored
     * mailbox total of 0 does not skip the query. Call on the session command, before [Uids.next] is reported.
     */
    private suspend fun numberExistingLocked(mailboxId: String) {
        val table = uids.getOrPut(mailboxId) { Uids() }
        if (table.existingNumbered) return
        var position = 0
        var reported: Int? = null
        val seen = HashSet<String>()
        var counted = 0
        while (true) {
            val positionJson = if (position == 0) "" else ""","position":$position"""
            val body = """{"using":$USING,"methodCalls":[["Email/query",{"accountId":${q(accountId())},"filter":{"inMailbox":${q(mailboxId)}},"sort":[{"property":"receivedAt","isAscending":true}]$positionJson,"limit":${jmapWindow()},"calculateTotal":true},"0"]]}"""
            val (ids, total) = call(body) { root -> queryIds(root) to queryTotal(root) }
            if (total != null) reported = total
            var fresh = 0
            for (id in ids) {
                if (!seen.add(id)) continue
                table.uid(id)
                fresh++
                counted++
            }
            if (fresh == 0) break
            val known = reported
            if (known != null && counted >= known) break
            if (known == null && ids.size < jmapWindow()) break
            position += ids.size
        }
        table.existingNumbered = true
    }

    /**
     * Pages [Email/query] by [MAX_WINDOW]. Position is omitted on the first page. Each request sets calculateTotal.
     * The query total stops the walk. A missing total stops when a page returns fewer ids than the limit.
     * The mailbox total from [select] is not the stop. [numberExistingLocked] has already numbered this mailbox.
     * Within a page, an id that was not in that query is assigned in receivedAt order, each at least the
     * [Uids.next] the page started with. Hits stay in query order.
     */
    private suspend fun pagedHits(table: Uids, mailboxId: String, filter: String, sortJson: String?, threads: Boolean): List<Hit> {
        numberExistingLocked(mailboxId)
        var position = 0
        var reported: Int? = null
        val seen = HashSet<String>()
        val hits = ArrayList<Hit>()
        val properties = if (threads) """["id","threadId","receivedAt"]""" else """["id","receivedAt"]"""
        while (true) {
            val positionJson = if (position == 0) "" else ""","position":$position"""
            val sortPart = if (sortJson == null) "" else ""","sort":$sortJson"""
            val body = """{"using":$USING,"methodCalls":[["Email/query",{"accountId":${q(accountId())},"filter":$filter$sortPart$positionJson,"limit":${jmapWindow()},"calculateTotal":true},"0"],["Email/get",{"accountId":${q(accountId())},"#ids":{"resultOf":"0","name":"Email/query","path":"/ids"},"properties":$properties},"1"]]}"""
            val (ids, fields, total) = call(body) { root -> Triple(queryIds(root), emailsById(root), queryTotal(root)) }
            if (total != null) reported = total
            val fresh = ArrayList<String>()
            for (id in ids) if (seen.add(id)) fresh += id
            val byAge = fresh.sortedWith(compareBy { receivedAtEpoch(fields[it]?.get("receivedAt")) ?: Long.MAX_VALUE })
            for (id in byAge) table.uid(id, receivedAtEpoch(fields[id]?.get("receivedAt")))
            for (id in fresh) {
                val f = fields[id]
                val uid = table.byId[id] ?: table.uid(id, receivedAtEpoch(f?.get("receivedAt")))
                val threadId = (f?.get("threadId") as? Json.Str)?.text.orEmpty()
                hits += Hit(id, uid, threadId)
            }
            if (fresh.isEmpty()) break
            val known = reported
            if (known != null && hits.size >= known) break
            if (known == null && ids.size < jmapWindow()) break
            position += ids.size
        }
        return hits
    }

    private fun threadFlat(hits: List<Hit>): ThreadFlat {
        val groups = LinkedHashMap<String, MutableList<Long>>()
        for (hit in hits) groups.getOrPut(hit.threadId.ifEmpty { "solo:${hit.id}" }) { ArrayList() } += hit.uid
        var count = 0
        for (uidsInThread in groups.values) if (uidsInThread.isNotEmpty()) count += uidsInThread.size
        val uids = LongArray(count)
        val depths = ShortArray(count)
        var at = 0
        for (uidsInThread in groups.values) {
            if (uidsInThread.isEmpty()) continue
            uids[at] = uidsInThread.first()
            depths[at] = 0
            at++
            for (uid in uidsInThread.drop(1)) {
                uids[at] = uid
                depths[at] = 1
                at++
            }
        }
        return ThreadFlat(uids, depths)
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
        var retriedAuth = false
        while (true) {
        val result = jmap { jmapCall(s.apiUrl, body, authorization, pin, pinnedJmapPost(pin, accountKey, traffic, policy), PeerTrust::check, policy, accountKey) }
        if ((result.status == 401 || result.status == 403) && useBearer) {
            if (!retriedAuth) {
                retriedAuth = true
                if (refreshStoredBearer() != null) continue
            } else {
                refreshStoredBearer()
            }
            throw MailFailure("Sign in again")
        }
        if (result.status != 200) throw MailFailure("jmap api status ${result.status}")
        val root = try {
            JsonParser(result.body).parseDocument()
        } catch (_: JsonBroken) {
            throw MailFailure("jmap response is not JSON")
        }
        if (root !is Json.Obj) throw MailFailure("jmap response is not an object")
        methodError(root)?.let { throw MailFailure("jmap error: $it") }
        if (retriedAuth && useBearer) onBearerAccepted?.invoke()
        return parse(root)
        }
    }

    private suspend fun <T> jmap(block: () -> T): T = io {
        try {
            JmapResponseCap.use(account?.maxJmapResponseBytes?.toInt() ?: JmapMaxBody, block)
        } catch (failure: JmapFailure) {
            if (!(useBearer && org.dlang.liveimap.oauth.oauthAuthenticationFailed(failure.text))) {
                throw MailFailure(failure.text)
            }
            if (refreshStoredBearer() == null) throw MailFailure("Sign in again")
            try {
                val value = JmapResponseCap.use(account?.maxJmapResponseBytes?.toInt() ?: JmapMaxBody, block)
                onBearerAccepted?.invoke()
                value
            } catch (again: JmapFailure) {
                if (org.dlang.liveimap.oauth.oauthAuthenticationFailed(again.text)) {
                    refreshStoredBearer()
                    throw MailFailure("Sign in again")
                }
                throw MailFailure(again.text)
            }
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun parseRows(root: Json.Obj, table: Uids, mailbox: String): List<IndexRow> {
        val list = args(root, "Email/get")?.get("list") as? Json.Arr ?: throw MailFailure("jmap response lacks Email/get")
        return list.values.mapIndexedNotNull { index, item ->
            val f = (item as? Json.Obj)?.fields ?: return@mapIndexedNotNull null
            val id = (f["id"] as? Json.Str)?.text ?: return@mapIndexedNotNull null
            IndexRow(
                ref = MessageRef(accountKey, mailbox, epoch.coerceAtLeast(1L), table.uid(id, receivedAtEpoch(f["receivedAt"]))),
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
            )
        }
    }

    private fun jmapWindow(): Int = account?.jmapMaxWindow ?: 120

    private fun stopWatchWaitMs(): Long = account?.jmapStopWatchWaitMs?.toLong() ?: 5_000L

    companion object {
        const val DELIMITER = '/'
        private const val MAX_WINDOW = 10000
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

        private fun queryTotal(root: Json.Obj): Int? {
            val num = args(root, "Email/query")?.get("total") as? Json.Num ?: return null
            return num.text.toLongOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
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

        internal fun headerBlock(email: Map<String, Json>): ByteArray {
            val headers = email["headers"] as? Json.Arr ?: throw MailFailure("jmap message has no headers")
            val out = StringBuilder()
            for (item in headers.values) {
                val fields = (item as? Json.Obj)?.fields ?: continue
                val name = (fields["name"] as? Json.Str)?.text ?: continue
                val value = (fields["value"] as? Json.Str)?.text.orEmpty()
                out.append(name).append(": ").append(value).append("\r\n")
            }
            return out.toString().toByteArray(Charsets.UTF_8)
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
            // Length 0 is from the offset through the end. A positive length stays a window.
            val end = if (length == 0) bytes.size else (offset.toLong() + length).coerceAtMost(bytes.size.toLong()).toInt()
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
fun platformJmapMailSession(context: android.content.Context, accountId: String): JmapMailSession {
    val traffic = org.dlang.liveimap.engine.TrafficLog.writer(context.cacheDir, accountId)
    return JmapMailSession(
        accountKey = accountId,
        traffic = traffic,
        discover = { account, auth ->
            offerForAccount(
                account,
                platformJmapExchange(account.jmapCertPin, account.tlsPolicy(), accountId, traffic),
                accountKey = accountId,
                traffic = traffic,
                authorization = auth,
            )
        },
        passwordOf = { account ->
            if (account.accountAuth == "oauth2") {
                org.dlang.liveimap.oauth.OauthBridge.accessOrThrow(context, accountId, account)
            } else {
                org.dlang.liveimap.settings.DataStoreSettingsStore(context, accountId).password()
                    ?: throw org.dlang.liveimap.session.MailFailure("password missing")
            }
        },
        savePin = { pin ->
            org.dlang.liveimap.settings.DataStoreSettingsStore(context, accountId).update { it.copy(jmapCertPin = pin) }
        },
        onBearerRejected = { account, rejected ->
            if (account.accountAuth != "oauth2") null
            else try {
                org.dlang.liveimap.oauth.OauthBridge.onAuthRejected(context, accountId, account, rejected)
            } catch (error: Exception) {
                if (error is java.util.concurrent.CancellationException) throw error
                null
            }
        },
        onBearerAccepted = {
            org.dlang.liveimap.oauth.OauthBridge.noteAuthSuccess(context, accountId)
        },
    )
}
