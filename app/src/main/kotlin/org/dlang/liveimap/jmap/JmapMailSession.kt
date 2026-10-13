package org.dlang.liveimap.jmap

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.dlang.liveimap.engine.PeerTrust
import org.dlang.liveimap.session.FolderEntry
import org.dlang.liveimap.session.IndexMode
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.MimePart
import org.dlang.liveimap.session.Namespace
import org.dlang.liveimap.session.NamespaceKind
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.SelectResult
import org.dlang.liveimap.session.ThreadNode
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SortKey

/**
 * A JMAP account (RFC 8620/8621) behind [MailSession] (roadmap jmap.md). LiveIMAP's own client over the platform
 * HTTPS stack; every exchange goes through [jmapCall], so it keeps the existing guarantees:
 *  - F53: no redirects are followed, and every session URL stays on the discovered host;
 *  - F58: JSON nesting is capped (JsonParser) and response bodies are size-capped (readJmapBody);
 *  - F63: trust uses the account's own JMAP pin ([AccountSettings.jmapCertPin]), never the IMAP one;
 *  - F68: network IOExceptions surface as failures (here [MailFailure]), never as crashes.
 *
 * Folder names are paths of JMAP mailbox names joined by '/'. JMAP email ids are strings; this session numbers
 * them per mailbox as it sees them (UIDs) under an epoch chosen at [open], so a reopened session is a new
 * UIDVALIDITY and every list re-reads. JMAP has no \Deleted: [storeFlags] refuses it and deletes are destroy/move.
 */
class JmapMailSession(
    private val discover: (AccountSettings) -> JmapOffer,
    private val passwordOf: suspend (AccountSettings) -> String,
    private val postFor: (pin: String) -> (String, String, String) -> JmapHttpExchange = ::pinnedJmapPost,
    private val trust: (String, List<ByteArray>, String) -> String = PeerTrust::check,
    private val epochClock: () -> Long = { System.currentTimeMillis() / 1000 },
) : MailSession {
    private val lock = Mutex()
    private var account: AccountSettings? = null
    private var session: JmapSession? = null
    private var authorization = ""
    private var pin = ""
    private var password = ""
    private var epoch = 0L

    /** Folder path -> mailbox id, filled as levels are listed. */
    private val ids = HashMap<String, String>()
    private val roles = HashMap<String, String>()
    private var selectedPath: String? = null

    /** Per mailbox id: email id <-> UID. */
    private class Uids {
        val byId = HashMap<String, Long>()
        val byUid = HashMap<Long, String>()
        var next = 1L
        fun uid(id: String): Long = byId.getOrPut(id) { (next++).also { byUid[it] = id } }
    }
    private val uids = HashMap<String, Uids>()

    override val capabilities: Set<String> get() = setOf("IMAP4rev1", "JMAP")

    override suspend fun open(account: AccountSettings): OpenResult = lock.withLock {
        try {
            val offer = io { discover(account) }
            val mail = offer as? JmapOffer.Mail ?: return@withLock OpenResult.Failed("server offers no JMAP mail")
            if (mail.session.primaryMailAccountId.isNullOrBlank()) return@withLock OpenResult.Failed("jmap account id is empty")
            this.account = account
            session = mail.session
            pin = account.jmapCertPin
            password = passwordOf(account)
            authorization = jmapBasicAuthorization(account.username, password)
            epoch = epochClock().coerceAtLeast(1L) and 0x7fffffffL
            ids.clear(); roles.clear(); uids.clear(); selectedPath = null
            OpenResult.Connected
        } catch (failure: JmapFailure) {
            OpenResult.Failed(failure.text)
        }
    }

    override suspend fun namespaces(): List<Namespace> = listOf(Namespace("", DELIMITER, NamespaceKind.Personal))

    override suspend fun listLevel(prefix: String, parentMailbox: String?, unreadCounts: Boolean): List<FolderEntry> =
        lock.withLock {
            val s = need()
            val parentId = if (parentMailbox == null) null else idOfLocked(parentMailbox)
            val rows = jmap { jmapFolderLevel(s, parentId, accountName(), password, pin, postFor(pin), trust) }
            rows.map { row ->
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
        val id = idOfLocked(mailbox)
        val total = call(mailboxGetRequest(accountId(), id)) { root -> mailboxTotal(root, id) }
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
            val to = idOfLocked(targetMailbox)
            val emailIds = uids.map { table.byUid[it] ?: throw MailFailure("unknown message in $path") }
            setEmails(emailIds.associateWith { mapOf("mailboxIds/$from" to null, "mailboxIds/$to" to true) })
        }
    }

    override suspend fun copyUids(uids: List<Long>, targetMailbox: String) {
        lock.withLock {
            val (path, table) = selectedTable()
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
        val body = """{"using":$USING,"methodCalls":[["Email/query",{"accountId":${q(accountId())},"filter":{"inMailbox":${q(idOfLocked(path))}},"sort":[{"property":"$property","isAscending":${!newestFirst}}],"limit":$MAX_IDS},"0"]]}"""
        call(body) { root -> queryIds(root).map { table.uid(it) } }
    }

    override suspend fun thread(key: SortKey): ThreadNode = throw MailFailure("threads are not available on JMAP yet")
    override suspend fun fetchStructure(uid: Long): MimePart = throw MailFailure("message structure is not available on JMAP yet")
    override suspend fun peekPart(uid: Long, section: String, offset: Int, length: Int): ByteArray =
        throw MailFailure("message parts are not available on JMAP yet")
    override suspend fun fetchRfc822(uid: Long): ByteArray = throw MailFailure("raw messages are not available on JMAP yet")
    override suspend fun append(mailbox: String, rfc822: ByteArray, flags: Set<String>) {
        throw MailFailure("saving a message is not available on JMAP yet")
    }
    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>, dsnNotify: Int): Int =
        throw MailFailure("a JMAP account sends through its SMTP server or EmailSubmission")

    /** New mail comes from Email/changes with EventSource push (JmapPush), not from an IMAP-style watch. */
    override suspend fun watch(mailbox: String, onChange: (MailboxChange) -> Unit) {}
    override suspend fun stopWatch() {}

    override suspend fun knownTrash(): String = lock.withLock { roles.entries.firstOrNull { it.value == "trash" }?.key.orEmpty() }

    override fun close() {
        session = null
        authorization = ""
        password = ""
        ids.clear(); roles.clear(); uids.clear(); selectedPath = null
    }

    // ---- internals (call with [lock] held) ----

    private fun need(): JmapSession = session ?: throw MailFailure("not connected")
    private fun accountId(): String = need().primaryMailAccountId ?: throw MailFailure("jmap account id is empty")
    private fun accountName(): String = account?.username ?: throw MailFailure("not connected")

    private fun idOfLocked(path: String): String =
        ids[path] ?: if (path.equals("INBOX", ignoreCase = true)) {
            roles.entries.firstOrNull { it.value == "inbox" }?.key?.let { ids[it] }
                ?: throw MailFailure("folder $path is not listed yet")
        } else {
            throw MailFailure("folder $path is not listed yet")
        }

    private fun selectedTable(): Pair<String, Uids> {
        val path = selectedPath ?: throw MailFailure("no folder selected")
        return path to uids.getOrPut(idOfLocked(path)) { Uids() }
    }

    private suspend fun searchFilter(filter: String): List<Long> = lock.withLock {
        val (path, table) = selectedTable()
        val inMailbox = """{"operator":"AND","conditions":[{"inMailbox":${q(idOfLocked(path))}},$filter]}"""
        val body = """{"using":$USING,"methodCalls":[["Email/query",{"accountId":${q(accountId())},"filter":$inMailbox,"limit":$MAX_IDS},"0"]]}"""
        call(body) { root -> queryIds(root).map { table.uid(it) } }.sorted()
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
        val result = jmap { jmapCall(s.apiUrl, body, authorization, pin, postFor(pin), trust) }
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
                uid = table.uid(id),
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
