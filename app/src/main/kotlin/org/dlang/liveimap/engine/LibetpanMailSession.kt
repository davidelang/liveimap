package org.dlang.liveimap.engine

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.R
import org.dlang.liveimap.session.Capabilities
import org.dlang.liveimap.session.CertPrompt
import org.dlang.liveimap.session.ConnectionState
import org.dlang.liveimap.session.FolderEntry
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.MailboxUids
import org.dlang.liveimap.session.MimePart
import org.dlang.liveimap.session.Namespace
import org.dlang.liveimap.session.NamespaceKind
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.SearchEdge
import org.dlang.liveimap.session.SelectResult
import org.dlang.liveimap.session.SortField
import org.dlang.liveimap.session.ThreadHeader
import org.dlang.liveimap.session.ThreadNode
import org.dlang.liveimap.session.clientThreads
import org.dlang.liveimap.session.mailboxMarkedTrash
import org.dlang.liveimap.session.sameImapIdentity
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.MoveMethod
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.StartRule
import org.dlang.liveimap.settings.moveCommandKind
import org.dlang.liveimap.ui.compose.decodeHeaderWords
import org.dlang.liveimap.ui.index.parseAdvancedQuery

internal fun searchNeedsCharset(text: String): Boolean =
    text.any { it.code > 127 }

// ServerProbe and ServerProbeTest still read this list. The gate does not.
internal val requiredCapabilities = listOf(
    "NAMESPACE",
    "UIDPLUS",
    "LITERAL+",
    "CHILDREN",
    "SORT",
    "THREAD=REFERENCES",
    "IDLE",
)

fun capabilityGate(serverList: String): OpenResult {
    val caps = Capabilities.parse(serverList)
    return if (caps.imap4rev1 || caps.imap4rev2) OpenResult.Connected else OpenResult.Rejected(serverList)
}

class LibetpanMailSession : MailSession {
    private var handle: Long = 0

    @Volatile
    private var capabilityLine: String = ""
    private var account: AccountSettings? = null
    private var loggedInPassword: String? = null
    private var compressed = false
    private var selectedMailbox: String? = null
    private var selectedReadWrite = false
    private var selected = SelectResult(0, 0, 0)
    private var namespaceList: List<Namespace>? = null
    private var trashListed = false
    private var listedTrash = ""

    @Volatile
    private var sequencesStale = false

    @Volatile
    private var watchCallback: ((MailboxChange) -> Unit)? = null

    @Volatile
    private var watchMailbox: String? = null

    @Volatile
    private var certConfirmer: (suspend (CertPrompt) -> Boolean)? = null

    @Volatile
    private var plaintextConfirmer: (suspend () -> Boolean)? = null

    private val cache = mutableMapOf<CacheKey, Any>()
    private val link = SessionLink()
    private val keeper = ConnectionKeeper(
        link,
        clock = { System.nanoTime() / 1_000_000L },
        sleep = { Thread.sleep(it) },
    )

    private data class CacheKey(
        val mailbox: String,
        val uidValidity: Long,
        val uidNext: Long,
        val exists: Int,
        val command: String,
    )

    override val capabilities: Set<String>
        get() = Capabilities.parse(capabilityLine).names

    override val featureCaps: Capabilities
        get() = Capabilities.parse(capabilityLine).without(account?.hiddenCapabilities ?: emptySet())

    override val connectionState: StateFlow<ConnectionState>
        get() = keeper.connectionState

    override suspend fun resume() {
        keeper.resume()
    }

    override suspend fun suspendConnections() {
        keeper.suspendConnections()
    }

    override suspend fun open(account: AccountSettings): OpenResult {
        val context = currentApplication() ?: return OpenResult.Failed("keystore unavailable")
        val password = try {
            DataStoreSettingsStore(context).password()
        } catch (error: Exception) {
            return OpenResult.Failed(error.message ?: "keystore unavailable")
        }
        val held = this.account
        val knownPassword = loggedInPassword
        if (handle != 0L && held != null && knownPassword != null &&
            sameImapIdentity(held, account, knownPassword, password)
        ) {
            if (nativeSessionDead(handle)) {
                close()
            } else {
                val logTurnedOn = account.logImapTraffic && !held.logImapTraffic
                if (logTurnedOn && compressed) {
                    close()
                } else {
                    nativeSetSessionFlags(
                        handle,
                        account.pipelineCommands,
                        account.logImapTraffic,
                        prepareTraffic(account),
                    )
                    finishTraffic(account)
                    this.account = account
                    return OpenResult.Connected
                }
            }
        }
        if (handle != 0L) {
            close()
        }
        var opened = login(account, password)
        if (opened is OpenResult.Failed && promptableCert(opened.text)) {
            val parts = nativeTakeCertOffer()
            val confirm = certConfirmer
            if (parts != null && parts.size == 5 && confirm != null) {
                val prompt = CertPrompt(
                    reason = opened.text,
                    subject = parts[0],
                    issuer = parts[1],
                    notBefore = parts[2],
                    notAfter = parts[3],
                    fingerprint = parts[4],
                    host = account.imapHost,
                    port = account.imapPort,
                )
                if (confirm(prompt)) {
                    val pinned = account.copy(certPin = prompt.fingerprint)
                    val text = opened.text
                    try {
                        DataStoreSettingsStore(context).save(pinned)
                        opened = login(pinned, password)
                    } catch (error: kotlinx.coroutines.CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        opened = OpenResult.Failed(error.message ?: text)
                    }
                }
            }
        }
        if (opened is OpenResult.Failed && opened.text == "plaintext login needs confirmation") {
            val confirm = plaintextConfirmer
            val accepted = confirm != null && confirm()
            val stopped = context.getString(R.string.plaintext_auth_stopped)
            if (!accepted) {
                opened = OpenResult.Failed(stopped)
            } else {
                val allowed = account.copy(allowPlaintextAuth = true)
                try {
                    DataStoreSettingsStore(context).save(allowed)
                    opened = login(allowed, password)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (error: Exception) {
                    opened = OpenResult.Failed(error.message ?: stopped)
                }
                if (opened is OpenResult.Failed && opened.text == "plaintext login needs confirmation") {
                    opened = OpenResult.Failed(stopped)
                }
            }
        }
        if (opened is OpenResult.Connected) {
            selectedMailbox = null
            selectedReadWrite = false
            sequencesStale = false
            keeper.forgetFolder()
            keeper.markUsed()
        }
        return opened
    }

    override fun setCertConfirmer(confirm: (suspend (CertPrompt) -> Boolean)?) {
        certConfirmer = confirm
    }

    override fun setPlaintextConfirmer(confirm: (suspend () -> Boolean)?) {
        plaintextConfirmer = confirm
    }

    private fun login(account: AccountSettings, knownPassword: String? = null): OpenResult {
        namespaceList = null
        val password = if (knownPassword != null) {
            knownPassword
        } else {
            val context = currentApplication() ?: return OpenResult.Failed("keystore unavailable")
            try {
                // Link.connect is not a coroutine. password() hops to Dispatchers.IO.
                runBlocking {
                    DataStoreSettingsStore(context).password()
                }
            } catch (error: Exception) {
                return OpenResult.Failed(error.message ?: "keystore unavailable")
            }
        }
        val from = if (account.email.isNotEmpty()) account.email else account.username
        val trafficPath = prepareTraffic(account)
        if (account.logImapTraffic) {
            TrafficLog.noteStatus("Connecting")
            TrafficLog.noteStatus("Authenticating")
        }
        val opened = nativeOpen(
            account.imapHost,
            account.imapPort,
            account.username,
            password,
            account.smtpHost,
            account.smtpPort,
            from,
            account.pipelineCommands,
            account.logImapTraffic,
            trafficPath,
            account.tlsMode.name,
            account.certPin,
            account.allowPlaintextAuth,
        )
        if (opened == 0L) {
            val text = nativeTakeError()
            if (account.logImapTraffic) TrafficLog.noteStatus("Error $text")
            finishTraffic(account)
            return OpenResult.Failed(text)
        }
        val line = nativeCapabilityLine(opened)
        val gate = capabilityGate(line)
        if (gate is OpenResult.Rejected) {
            nativeClose(opened)
            finishTraffic(account)
            return gate
        }
        if (account.logImapTraffic) TrafficLog.noteCapability(line)
        handle = opened
        this.account = account
        loggedInPassword = password
        capabilityLine = line
        cache.clear()
        compressed = false
        when (featureCaps.resyncKind()) {
            "Qresync" -> nativeEnable(opened, "QRESYNC")
            "Condstore" -> nativeEnable(opened, "CONDSTORE")
        }
        if (featureCaps.compressDeflate && !account.logImapTraffic) {
            nativeCompress(opened)
            compressed = true
        }
        finishTraffic(account)
        return OpenResult.Connected
    }

    private fun closeSockets() {
        val h = handle
        handle = 0
        compressed = false
        if (h != 0L) {
            nativeClose(h)
        }
    }

    private fun closeLiveSession() {
        val h = handle
        if (h != 0L) {
            try {
                nativeStopWatch(h)
            } catch (_: Exception) {
            }
            if (account?.autoExpunge == true && selectedMailbox != null && selectedReadWrite) {
                try {
                    nativeCloseMailbox(h)
                } catch (_: Exception) {
                }
            }
        }
        closeSockets()
        trashListed = false
        listedTrash = ""
    }

    private fun closeSelectedMailbox() {
        nativeCloseMailbox(requireHandle())
        selectedMailbox = null
        selectedReadWrite = false
        selected = SelectResult(0, 0, 0)
    }

    private fun unselectSelectedMailbox() {
        nativeUnselect(requireHandle())
        selectedMailbox = null
        selectedReadWrite = false
        selected = SelectResult(0, 0, 0)
    }

    private fun openMailbox(mailbox: String, write: Boolean): SelectResult {
        val same = selectedMailbox == mailbox
        val wasReadWrite = selectedReadWrite
        val sufficient = same && !sequencesStale && (wasReadWrite || !write)
        if (sufficient) {
            return selected
        }
        val current = selectedMailbox
        if (current != null && current != mailbox) {
            if (account?.autoExpunge == true && wasReadWrite) {
                closeSelectedMailbox()
            } else if (featureCaps.unselect) {
                unselectSelectedMailbox()
            }
        }
        val readWrite = write || (same && sequencesStale && wasReadWrite)
        val result = nativeSelect(requireHandle(), mailbox, readWrite) ?: run {
            noteTraffic("Error select failed")
            throw MailFailure("select failed")
        }
        selectedMailbox = mailbox
        selectedReadWrite = readWrite
        selected = result
        sequencesStale = false
        keeper.noteSelected(result)
        noteTraffic("Selected $mailbox")
        return result
    }

    private fun selectNow(mailbox: String): SelectResult = openMailbox(mailbox, write = false)

    private fun ensureReadWrite() {
        val mailbox = selectedMailbox ?: return
        openMailbox(mailbox, write = true)
    }

    override suspend fun namespaces(): List<Namespace> = keeper.read("namespace") {
        val held = namespaceList
        if (held != null) return@read held
        if (!featureCaps.namespace) {
            val listed = nativeHierarchyDelimiter(requireHandle())
            val delimiter = if (listed == '\u0000') '.' else listed
            val list = listOf(Namespace("", delimiter, NamespaceKind.Personal))
            namespaceList = list
            return@read list
        }
        val rows = nativeNamespaces(requireHandle()) ?: throw MailFailure("namespace failed")
        val list = rows.toList()
        namespaceList = list
        list
    }

    override suspend fun listLevel(
        prefix: String,
        parentMailbox: String?,
        unreadCounts: Boolean,
    ): List<FolderEntry> = keeper.read("list") {
        val rows = nativeListLevel(
            requireHandle(),
            prefix,
            parentMailbox,
            featureCaps.listKind(unreadCounts),
        ) ?: throw MailFailure("list failed")
        rows.toList()
    }

    override suspend fun mailboxListed(name: String): Boolean = keeper.read("list") {
        nativeMailboxListed(requireHandle(), name)
    }

    override suspend fun statusMessages(mailboxes: List<String>): Map<String, Int> {
        if (mailboxes.isEmpty()) return emptyMap()
        return keeper.read("status") {
            nativeStatusMessages(requireHandle(), mailboxes.toTypedArray())
        }
    }

    override suspend fun select(mailbox: String): SelectResult = keeper.read("select") {
        selectNow(mailbox)
    }

    override suspend fun unselect() {
        val mailbox = selectedMailbox
        if (account?.autoExpunge == true && mailbox != null && selectedReadWrite) {
            closeSelectedMailbox()
            return
        }
        if (featureCaps.unselect) {
            unselectSelectedMailbox()
            return
        }
        if (mailbox == null) return
        val result = nativeSelect(requireHandle(), mailbox, false) ?: run {
            noteTraffic("Error select failed")
            throw MailFailure("select failed")
        }
        selectedMailbox = mailbox
        selectedReadWrite = false
        selected = result
        sequencesStale = false
        keeper.noteSelected(result)
        noteTraffic("Selected $mailbox")
    }

    override suspend fun fetchIndex(request: IndexRequest): List<IndexRow> = keeper.read("fetch") {
        if (sequencesStale || selectedMailbox != request.mailbox) {
            selectNow(request.mailbox)
        }
        val h = requireHandle()
        val settings = account
        val useServerPreview = request.includePreview && featureCaps.previewKind() == "Preview"
        val rows = nativeFetchIndex(
            h,
            request.mailbox,
            request.mode.ordinal,
            request.firstSequence,
            request.lastSequence,
            request.uids.toLongArray(),
            request.limit,
            request.prefetch,
            request.includePreview,
            request.previewByteLimit,
            settings?.preferHtml == true,
            settings?.showDeleted != false,
            useServerPreview,
            settings?.email.orEmpty(),
            settings?.altAddresses.orEmpty().joinToString("\n"),
        ) ?: throw MailFailure("fetch failed")
        rows.map { row ->
            row.copy(
                from = decodeHeaderWords(row.from),
                subject = decodeHeaderWords(row.subject),
                recipients = decodeHeaderWords(row.recipients),
            )
        }
    }

    override suspend fun fetchStructure(uid: Long): MimePart = keeper.read("fetch") {
        nativeFetchStructure(requireHandle(), uid) ?: throw MailFailure("fetch failed")
    }

    override suspend fun peekPart(uid: Long, section: String, offset: Int, length: Int): ByteArray =
        keeper.read("fetch") {
            val binary = featureCaps.fetchKind() == "BinaryPeek"
            nativePeekPart(requireHandle(), uid, section, offset, length, binary)
                ?: throw MailFailure("fetch failed")
        }

    override suspend fun fetchRfc822(uid: Long): ByteArray = keeper.read("fetch") {
        nativeFetchRfc822(requireHandle(), uid) ?: throw MailFailure("fetch failed")
    }

    override suspend fun storeFlags(uids: List<Long>, add: Set<String>, remove: Set<String>) {
        keeper.write("store") {
            ensureReadWrite()
            nativeStoreFlags(requireHandle(), uids.toLongArray(), add.toTypedArray(), remove.toTypedArray())
        }
    }

    override suspend fun storeFlagsAll(add: Set<String>, remove: Set<String>) {
        keeper.write("store") {
            ensureReadWrite()
            nativeStoreFlagsAll(requireHandle(), add.toTypedArray(), remove.toTypedArray())
        }
    }

    override suspend fun uidExpungeDeleted() {
        keeper.write("expunge") {
            ensureReadWrite()
            nativeUidExpungeDeleted(requireHandle())
        }
    }

    override suspend fun uidExpunge(uids: List<Long>) {
        if (uids.isEmpty()) return
        keeper.write("expunge") {
            ensureReadWrite()
            nativeUidExpunge(requireHandle(), uids.toLongArray())
        }
    }

    override suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String) {
        if (uids.isEmpty()) return
        keeper.write("copy") {
            ensureReadWrite()
            nativeCopyThenDelete(
                requireHandle(),
                uids.toLongArray(),
                targetMailbox,
                moveCommandKind(account?.moveMethod ?: MoveMethod.CopyThenMarkDeleted, featureCaps.move),
            )
        }
    }

    override suspend fun copyUids(uids: List<Long>, targetMailbox: String) {
        if (uids.isEmpty()) return
        keeper.write("copy") {
            ensureReadWrite()
            nativeCopyThenDelete(
                requireHandle(),
                uids.toLongArray(),
                targetMailbox,
                "Copy",
            )
        }
    }

    override suspend fun copyAllThenDelete(targetMailbox: String) {
        keeper.write("copy") {
            ensureReadWrite()
            nativeCopyAllThenDelete(
                requireHandle(),
                targetMailbox,
                moveCommandKind(account?.moveMethod ?: MoveMethod.CopyThenMarkDeleted, featureCaps.move),
            )
        }
    }

    override suspend fun selectedExists(): Int = selected.exists

    override suspend fun takeCopiedUids(): List<Long> {
        val h = handle
        if (h == 0L) return emptyList()
        return nativeTakeCopiedUids(h)?.toList().orEmpty()
    }

    override suspend fun searchText(query: String): List<Long> = keeper.read("search") {
        val h = requireHandle()
        remember("SEARCH $query") {
            val ids = nativeSearchText(h, query, featureCaps.searchKind() == "Esearch")
                ?: throw MailFailure("search failed")
            ids.toList()
        }
    }

    override suspend fun searchCriterion(kind: String, argument: String): List<Long> = keeper.read("search") {
        val h = requireHandle()
        val withCharset = searchNeedsCharset(argument)
        remember("CRITERION $kind $argument") {
            val ids = (if (kind == "Advanced") {
                val parsed = parseAdvancedQuery(argument) ?: throw MailFailure("bad search")
                if (parsed.steps.isEmpty()) throw MailFailure("bad search")
                nativeSearchAdvanced(
                    h,
                    parsed.combiner.name,
                    BooleanArray(parsed.steps.size) { parsed.steps[it].negated },
                    Array(parsed.steps.size) { parsed.steps[it].kind },
                    Array(parsed.steps.size) { parsed.steps[it].argument },
                    featureCaps.searchKind() == "Esearch",
                    withCharset,
                )
            } else {
                nativeSearchCriterion(
                    h,
                    kind,
                    argument,
                    featureCaps.searchKind() == "Esearch",
                    withCharset,
                )
            }) ?: throw MailFailure("search failed")
            ids.toList()
        }
    }

    override suspend fun searchCount(kind: String, argument: String): Int {
        if (featureCaps.searchKind() != "Esearch") {
            return searchCriterion(kind, argument).size
        }
        return keeper.read("search") {
            val h = requireHandle()
            val withCharset = searchNeedsCharset(argument)
            val count = if (kind == "Advanced") {
                val parsed = parseAdvancedQuery(argument) ?: throw MailFailure("bad search")
                if (parsed.steps.isEmpty()) throw MailFailure("bad search")
                nativeSearchAdvancedCount(
                    h,
                    parsed.combiner.name,
                    BooleanArray(parsed.steps.size) { parsed.steps[it].negated },
                    Array(parsed.steps.size) { parsed.steps[it].kind },
                    Array(parsed.steps.size) { parsed.steps[it].argument },
                    withCharset,
                )
            } else {
                nativeSearchCriterionCount(h, kind, argument, withCharset)
            }
            if (count < 0L || count > Int.MAX_VALUE) throw MailFailure("search failed")
            count.toInt()
        }
    }

    override suspend fun searchScope(
        scopeName: String,
        home: String,
        kind: String,
        argument: String,
    ): List<MailboxUids> = keeper.read("search") {
        if (kind != "Advanced") throw MailFailure("search failed")
        if (scopeName == "Subtree" && home.isEmpty()) throw MailFailure("search failed")
        val h = requireHandle()
        val parsed = parseAdvancedQuery(argument) ?: throw MailFailure("bad search")
        if (parsed.steps.isEmpty()) throw MailFailure("bad search")
        val withCharset = searchNeedsCharset(argument)
        val rows = nativeSearchScope(
            h,
            scopeName,
            home,
            parsed.combiner.name,
            BooleanArray(parsed.steps.size) { parsed.steps[it].negated },
            Array(parsed.steps.size) { parsed.steps[it].kind },
            Array(parsed.steps.size) { parsed.steps[it].argument },
            withCharset,
        ) ?: throw MailFailure("search failed")
        rows.toList()
    }

    override suspend fun searchScopeCount(
        scopeName: String,
        home: String,
        kind: String,
        argument: String,
    ): Int = keeper.read("search") {
        if (kind != "Advanced") throw MailFailure("search failed")
        if (scopeName == "Subtree" && home.isEmpty()) throw MailFailure("search failed")
        val h = requireHandle()
        val parsed = parseAdvancedQuery(argument) ?: throw MailFailure("bad search")
        if (parsed.steps.isEmpty()) throw MailFailure("bad search")
        val withCharset = searchNeedsCharset(argument)
        val count = nativeSearchScopeCount(
            h,
            scopeName,
            home,
            parsed.combiner.name,
            BooleanArray(parsed.steps.size) { parsed.steps[it].negated },
            Array(parsed.steps.size) { parsed.steps[it].kind },
            Array(parsed.steps.size) { parsed.steps[it].argument },
            withCharset,
        )
        if (count < 0L || count > Int.MAX_VALUE) throw MailFailure("search failed")
        count.toInt()
    }

    override suspend fun subscribedMailboxes(): List<String> = keeper.read("list") {
        val rows = nativeSubscribedMailboxes(requireHandle(), featureCaps.listExtended)
            ?: throw MailFailure("list failed")
        rows.toList()
    }

    override suspend fun searchStart(rule: StartRule, byUid: Boolean, edge: SearchEdge): List<Long> {
        if (rule == StartRule.Newest) return emptyList()
        return keeper.read("search") {
            val ids = nativeSearchStart(
                requireHandle(),
                rule.name,
                byUid,
                edge.name,
                featureCaps.searchKind() == "Esearch",
            ) ?: throw MailFailure("search failed")
            ids.toList()
        }
    }

    override suspend fun locateUid(uid: Long): List<Long> = keeper.read("search") {
        val ids = nativeLocateUid(
            requireHandle(),
            uid,
            featureCaps.searchKind() == "Esearch",
        ) ?: throw MailFailure("search failed")
        ids.toList()
    }

    override suspend fun sort(key: SortKey, newestFirst: Boolean): List<Long> {
        if (key == SortKey.Arrival) {
            throw MailFailure("use an arrival IndexMode")
        }
        if (key == SortKey.ThreadReferences || key == SortKey.ThreadOrderedSubject) {
            throw MailFailure("use thread")
        }
        if (!featureCaps.sort) {
            throw MailFailure("SORT was not advertised")
        }
        return keeper.read("sort") {
            val h = requireHandle()
            val command = featureCaps.sortKind()
            val token = featureCaps.imapSortKey(sortToken(key))
            remember("SORT $token $newestFirst") {
                val ids = nativeSort(h, token, newestFirst, command == "Esort") ?: throw MailFailure("sort failed")
                ids.toList()
            }
        }
    }

    override suspend fun thread(key: SortKey): ThreadNode {
        val algorithm = when (key) {
            SortKey.ThreadReferences -> {
                if (!featureCaps.threadReferences) {
                    throw MailFailure("THREAD=REFERENCES was not advertised")
                }
                "REFERENCES"
            }
            SortKey.ThreadOrderedSubject -> {
                if (!featureCaps.threadOrderedSubject) {
                    throw MailFailure("ORDEREDSUBJECT was not advertised")
                }
                "ORDEREDSUBJECT"
            }
            else -> throw MailFailure("use thread")
        }
        return keeper.read("thread") {
            val h = requireHandle()
            remember("THREAD $algorithm") {
                nativeThread(h, algorithm) ?: throw MailFailure("thread failed")
            }
        }
    }

    override suspend fun clientOrder(key: SortKey, newestFirst: Boolean): List<Long> {
        val field = when (key) {
            SortKey.Date -> "DATE"
            SortKey.From -> "FROM"
            SortKey.Subject -> "SUBJECT"
            SortKey.To -> "TO"
            SortKey.Cc -> "CC"
            SortKey.Size -> "SIZE"
            else -> throw MailFailure("use an arrival IndexMode")
        }
        return keeper.read("client-sort") {
            val h = requireHandle()
            remember("CLIENT SORT $field $newestFirst") {
                val rows = nativeFetchSortFields(h, field) ?: throw MailFailure("sort failed")
                orderedClientUids(rows, key, newestFirst)
            }
        }
    }

    override suspend fun clientThread(key: SortKey): ThreadNode {
        if (key != SortKey.ThreadReferences && key != SortKey.ThreadOrderedSubject) {
            throw MailFailure("use thread")
        }
        return keeper.read("client-thread") {
            val h = requireHandle()
            remember("CLIENT THREAD ${key.name}") {
                val rows = nativeFetchThreadHeaders(h) ?: throw MailFailure("thread failed")
                clientThreads(rows.toList(), key)
            }
        }
    }

    override suspend fun watch(mailbox: String, onChange: (MailboxChange) -> Unit) {
        if (!featureCaps.idle) return
        keeper.read("watch") {
            watchMailbox = mailbox
            watchCallback = onChange
            nativeWatch(requireHandle(), mailbox)
            noteTraffic("Idling")
        }
    }

    override suspend fun stopWatch() {
        val h = handle
        if (h != 0L) {
            nativeStopWatch(h)
        }
        watchCallback = null
    }

    override suspend fun noop() {
        keeper.read("noop") {
            val exists = nativeNoop(requireHandle())
            if (exists >= 0 && selectedMailbox != null) {
                selected = selected.copy(exists = exists)
            }
        }
    }

    override suspend fun knownTrash(): String {
        val named = account?.trashMailbox.orEmpty()
        if (named.isNotEmpty()) return named
        if (!featureCaps.listExtended) return ""
        if (trashListed) return listedTrash
        var found = ""
        for (space in namespaces()) {
            if (space.kind != NamespaceKind.Personal) continue
            val marked = mailboxMarkedTrash(listLevel(space.prefix, null, false))
            if (marked.isNotEmpty()) {
                found = marked
                break
            }
        }
        listedTrash = found
        trashListed = true
        return found
    }

    override suspend fun expungeOnLeave() {
        if (account?.autoExpunge != true) return
        if (selectedMailbox == null || !selectedReadWrite) return
        val h = handle
        if (h == 0L) return
        nativeCloseMailbox(h)
        selectedMailbox = null
        selectedReadWrite = false
        selected = SelectResult(0, 0, 0)
    }

    override suspend fun selectedInfo(): SelectResult = selected

    override suspend fun append(mailbox: String, rfc822: ByteArray, flags: Set<String>) {
        keeper.write("append") {
            nativeAppend(requireHandle(), mailbox, rfc822, flags.toTypedArray())
        }
    }

    override suspend fun appendReturningUid(mailbox: String, rfc822: ByteArray, flags: Set<String>): Long =
        keeper.write("append") {
            nativeAppend(requireHandle(), mailbox, rfc822, flags.toTypedArray())
        }

    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>) {
        val context = currentApplication()
        val smtpPassword = if (context != null) DataStoreSettingsStore(context).smtpPassword() else ""
        keeper.write("send") {
            nativeSmtp(
                requireHandle(),
                rfc822,
                recipients.toTypedArray(),
                account?.smtpUsername ?: "",
                smtpPassword,
            )
        }
    }

    override fun close() {
        closeLiveSession()
        capabilityLine = ""
        account = null
        loggedInPassword = null
        namespaceList = null
        selectedMailbox = null
        selectedReadWrite = false
        sequencesStale = false
        watchCallback = null
        cache.clear()
        keeper.onSessionClosed()
    }

    fun onNativeWatch(kind: Int, exists: Int, uid: Long, flags: Array<String>?) {
        val mailbox = watchMailbox
        val change: MailboxChange = when (kind) {
            0 -> MailboxChange.Exists(exists)
            1 -> MailboxChange.Expunge(exists)
            2 -> MailboxChange.Flags(uid, flags?.toSet() ?: emptySet())
            4 -> {
                keeper.markWatchLost()
                MailboxChange.WatchLost
            }
            else -> MailboxChange.UidValidityReset
        }
        if (change !is MailboxChange.Flags && mailbox != null) {
            cache.keys.retainAll { it.mailbox != mailbox }
        }
        sequencesStale = true
        watchCallback?.invoke(change)
    }

    private inner class SessionLink : Link {
        override fun dead(): Boolean {
            val h = handle
            if (h == 0L) return true
            return nativeSessionDead(h)
        }

        override fun connect() {
            val saved = account ?: throw MailFailure("not connected")
            val opened = login(saved)
            if (opened !is OpenResult.Connected) {
                val text = when (opened) {
                    is OpenResult.Failed -> opened.text
                    is OpenResult.Rejected -> opened.capabilities
                    OpenResult.Connected -> "connection failed"
                }
                throw MailFailure(text)
            }
            cache.clear()
            sequencesStale = true
        }

        override fun noop() {
            nativeNoop(requireHandle())
        }

        override fun reselect(): SelectResult? {
            val mailbox = selectedMailbox ?: return null
            val readWrite = selectedReadWrite
            val result = nativeSelect(requireHandle(), mailbox, readWrite) ?: throw MailFailure("select failed")
            selectedMailbox = mailbox
            selectedReadWrite = readWrite
            selected = result
            cache.clear()
            sequencesStale = true
            noteTraffic("Selected $mailbox")
            return result
        }

        override fun rewatch() {
            val mailbox = watchMailbox ?: return
            if (watchCallback == null) return
            nativeWatch(requireHandle(), mailbox)
            noteTraffic("Idling")
        }

        override fun close() {
            closeLiveSession()
        }

        override fun emit(change: MailboxChange) {
            if (change !is MailboxChange.Flags) {
                val mailbox = selectedMailbox
                if (mailbox != null) {
                    cache.keys.retainAll { it.mailbox != mailbox }
                }
                sequencesStale = true
            }
            watchCallback?.invoke(change)
        }
    }

    private fun noteTraffic(text: String) {
        if (account?.logImapTraffic == true) TrafficLog.noteStatus(text)
    }

    private fun requireHandle(): Long {
        val h = handle
        if (h == 0L) throw MailFailure("not connected")
        return h
    }

    private fun <T : Any> remember(command: String, load: () -> T): T {
        val key = CacheKey(
            selectedMailbox ?: "",
            selected.uidValidity,
            selected.uidNext,
            selected.exists,
            command,
        )
        val found = cache[key]
        if (found != null) {
            @Suppress("UNCHECKED_CAST")
            return found as T
        }
        val value = load()
        cache[key] = value
        return value
    }

    private fun orderedClientUids(rows: Array<SortField>, key: SortKey, newestFirst: Boolean): List<Long> {
        val filled = ArrayList<SortField>()
        val blanks = ArrayList<SortField>()
        for (row in rows) {
            if (row.uid <= 0L) continue
            if (row.empty) blanks.add(row) else filled.add(row)
        }
        val numeric = key == SortKey.Date || key == SortKey.Size
        val byValue = if (numeric) {
            compareBy<SortField> { it.number }.thenBy { it.uid }
        } else {
            compareBy<SortField> { it.text.lowercase() }.thenBy { it.uid }
        }
        filled.sortWith(byValue)
        if (newestFirst) filled.reverse()
        blanks.sortBy { it.uid }
        return (filled.asSequence() + blanks.asSequence()).map { it.uid }.toList()
    }

    private fun sortToken(key: SortKey): String = when (key) {
        SortKey.Date -> "DATE"
        SortKey.From -> "FROM"
        SortKey.Subject -> "SUBJECT"
        SortKey.To -> "TO"
        SortKey.Cc -> "CC"
        SortKey.Size -> "SIZE"
        else -> throw MailFailure("use an arrival IndexMode")
    }

    private external fun nativeOpen(
        host: String,
        port: Int,
        user: String,
        password: String,
        smtpHost: String,
        smtpPort: Int,
        from: String,
        pipeline: Boolean,
        log: Boolean,
        logPath: String,
        tlsMode: String,
        certPin: String,
        allowPlaintextAuth: Boolean,
    ): Long

    private external fun nativeSetSessionFlags(handle: Long, pipeline: Boolean, log: Boolean, logPath: String)

    private external fun nativeSessionDead(handle: Long): Boolean

    private external fun nativeNoop(handle: Long): Int

    private external fun nativeCapabilityLine(handle: Long): String
    private external fun nativeEnable(handle: Long, capability: String)
    private external fun nativeCompress(handle: Long)
    private external fun nativeClose(handle: Long)
    private external fun nativeNamespaces(handle: Long): Array<Namespace>?
    private external fun nativeHierarchyDelimiter(handle: Long): Char
    private external fun nativeListLevel(handle: Long, prefix: String, parent: String?, listKind: String): Array<FolderEntry>?
    private external fun nativeMailboxListed(handle: Long, name: String): Boolean
    private external fun nativeSubscribedMailboxes(handle: Long, extended: Boolean): Array<String>?
    private external fun nativeStatusMessages(handle: Long, mailboxes: Array<String>): Map<String, Int>
    private external fun nativeSelect(handle: Long, mailbox: String, readWrite: Boolean): SelectResult?
    private external fun nativeUnselect(handle: Long)
    private external fun nativeCloseMailbox(handle: Long)
    private external fun nativeFetchIndex(
        handle: Long,
        mailbox: String,
        mode: Int,
        firstSequence: Int,
        lastSequence: Int,
        uids: LongArray,
        limit: Int,
        prefetch: Int,
        includePreview: Boolean,
        previewByteLimit: Int,
        preferHtml: Boolean,
        showDeleted: Boolean,
        useServerPreview: Boolean,
        accountEmail: String,
        altAddresses: String,
    ): Array<IndexRow>?

    private external fun nativeFetchStructure(handle: Long, uid: Long): MimePart?
    private external fun nativePeekPart(
        handle: Long,
        uid: Long,
        section: String,
        offset: Int,
        length: Int,
        useBinary: Boolean,
    ): ByteArray?
    private external fun nativeFetchRfc822(handle: Long, uid: Long): ByteArray?
    private external fun nativeStoreFlags(handle: Long, uids: LongArray, add: Array<String>, remove: Array<String>)
    private external fun nativeStoreFlagsAll(handle: Long, add: Array<String>, remove: Array<String>)
    private external fun nativeUidExpungeDeleted(handle: Long)
    private external fun nativeUidExpunge(handle: Long, uids: LongArray)
    private external fun nativeCopyThenDelete(handle: Long, uids: LongArray, target: String, moveKind: String)
    private external fun nativeCopyAllThenDelete(handle: Long, target: String, moveKind: String)
    private external fun nativeTakeCopiedUids(handle: Long): LongArray?
    private external fun nativeAppend(handle: Long, mailbox: String, message: ByteArray, flags: Array<String>): Long
    private external fun nativeSearchText(handle: Long, query: String, useEsearch: Boolean): LongArray?
    private external fun nativeSearchCriterion(
        handle: Long,
        kind: String,
        argument: String,
        useEsearch: Boolean,
        withCharset: Boolean,
    ): LongArray?

    private external fun nativeSearchAdvanced(
        handle: Long,
        combiner: String,
        negated: BooleanArray,
        kinds: Array<String>,
        arguments: Array<String>,
        useEsearch: Boolean,
        withCharset: Boolean,
    ): LongArray?

    private external fun nativeSearchCriterionCount(
        handle: Long,
        kind: String,
        argument: String,
        withCharset: Boolean,
    ): Long

    private external fun nativeSearchAdvancedCount(
        handle: Long,
        combiner: String,
        negated: BooleanArray,
        kinds: Array<String>,
        arguments: Array<String>,
        withCharset: Boolean,
    ): Long

    private external fun nativeSearchScope(
        handle: Long,
        scope: String,
        home: String,
        combiner: String,
        negated: BooleanArray,
        kinds: Array<String>,
        arguments: Array<String>,
        withCharset: Boolean,
    ): Array<MailboxUids>?

    private external fun nativeSearchScopeCount(
        handle: Long,
        scope: String,
        home: String,
        combiner: String,
        negated: BooleanArray,
        kinds: Array<String>,
        arguments: Array<String>,
        withCharset: Boolean,
    ): Long

    private external fun nativeSearchStart(
        handle: Long,
        rule: String,
        byUid: Boolean,
        edge: String,
        useEsearch: Boolean,
    ): LongArray?

    private external fun nativeLocateUid(handle: Long, uid: Long, useEsearch: Boolean): LongArray?
    private external fun nativeSort(handle: Long, key: String, newestFirst: Boolean, useEsort: Boolean): LongArray?
    private external fun nativeFetchSortFields(handle: Long, field: String): Array<SortField>?
    private external fun nativeFetchThreadHeaders(handle: Long): Array<ThreadHeader>?
    private external fun nativeThread(handle: Long, algorithm: String): ThreadNode?
    private external fun nativeWatch(handle: Long, mailbox: String)
    private external fun nativeStopWatch(handle: Long)
    private external fun nativeSmtp(
        handle: Long,
        message: ByteArray,
        recipients: Array<String>,
        smtpUsername: String,
        smtpPassword: String,
    )

    companion object {
        init {
            System.loadLibrary("liveimap")
        }

        @JvmStatic
        private external fun nativeTakeError(): String

        @JvmStatic
        private external fun nativeTakeCertOffer(): Array<String>?
    }
}

private fun promptableCert(text: String): Boolean =
    text == "certificate untrusted" || text == "certificate changed"

private fun imapTrafficLogPath(context: Context?): String {
    if (context == null) return ""
    return File(context.cacheDir, "imap-traffic.log").absolutePath
}

private fun prepareTraffic(account: AccountSettings): String {
    val context = currentApplication()
    val path = imapTrafficLogPath(context)
    if (context != null) {
        TrafficLog.install(File(context.cacheDir, "imap-traffic.log"))
        if (account.logImapTraffic) {
            TrafficLog.setRecording(true)
        }
    }
    return path
}

private fun finishTraffic(account: AccountSettings) {
    if (!account.logImapTraffic) {
        TrafficLog.setRecording(false)
    }
}

private fun currentApplication(): Context? {
    return try {
        val thread = Class.forName("android.app.ActivityThread")
        val method = thread.getMethod("currentApplication")
        method.invoke(null) as? Context
    } catch (_: Exception) {
        null
    }
}
