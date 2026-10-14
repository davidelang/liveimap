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
import org.dlang.liveimap.session.UidWindow
import org.dlang.liveimap.session.SearchProgress
import org.dlang.liveimap.session.clientThreads
import org.dlang.liveimap.session.mailboxMarkedTrash
import org.dlang.liveimap.session.usableTrashName
import org.dlang.liveimap.session.sameImapIdentity
import org.dlang.liveimap.session.plusFallback
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.compressionWanted
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.MoveMethod
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.StartRule
import org.dlang.liveimap.settings.moveCommandKind
import org.dlang.liveimap.ui.compose.decodeHeaderWords
import org.dlang.liveimap.ui.index.parseAdvancedQuery

/** CHARSET UTF-8 for non-ASCII search text, except once UTF8=ACCEPT is enabled: RFC 6855 section 3 forbids
 * the CHARSET argument then (dovecot: "Cannot set search charset when using UTF8=ACCEPT"); text is UTF-8 anyway. */
internal fun searchNeedsCharset(text: String, utf8Enabled: Boolean = false): Boolean =
    !utf8Enabled && text.any { it.code > 127 }

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

    /** F64: true once this session logged in by the no-channel-binding retry after a failed SCRAM -PLUS. */
    @Volatile var channelBindingDropped: Boolean = false
        private set

    @Volatile
    private var plaintextConfirmer: (suspend () -> Boolean)? = null

    // Concurrent: onNativeWatch prunes it from the native IDLE thread while the lane reads it (F25).
    private val cache = java.util.concurrent.ConcurrentHashMap<CacheKey, Any>()
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

    /** Set by openWith: this session belongs to a non-selected account, so its own secrets are used. */
    @Volatile
    private var credentials: org.dlang.liveimap.accounts.AccountCredentials? = null

    override suspend fun openWith(credentials: org.dlang.liveimap.accounts.AccountCredentials): OpenResult {
        this.credentials = credentials
        return open(credentials.settings)
    }

    /**
     * F75: the account this session belongs to, fixed when it opens (openWith's id, else the caller's
     * AccountTarget, else the account active at that moment). Every later password read, reconnect, SMTP login
     * and pin save uses this id, never "whatever the drawer shows now".
     */
    @Volatile
    private var ownerId: String? = null

    private fun ownStore(context: android.content.Context): DataStoreSettingsStore {
        val id = ownerId ?: throw MailFailure("session has no account")
        return DataStoreSettingsStore(context, id)
    }

    override suspend fun open(account: AccountSettings): OpenResult {
        val context = currentApplication() ?: return OpenResult.Failed("keystore unavailable")
        val owner = credentials?.accountId
            ?: kotlin.coroutines.coroutineContext[org.dlang.liveimap.accounts.AccountTarget]?.accountId
            ?: try { DataStoreSettingsStore(context).chosenAccountId() } catch (error: Exception) { null }
            ?: return OpenResult.Failed("no account")
        if (ownerId != null && ownerId != owner && handle != 0L) close()
        ownerId = owner
        val password = LabTestHooks.passwordFor(account.imapHost) ?: credentials?.password ?: try {
            ownStore(context).password()
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
        // F64: the account as trusted so far in this open (a pin confirmed below carries into later retries).
        var trustedAccount = account
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
                if (credentials == null && confirm(prompt)) {
                    val pinned = account.copy(certPin = prompt.fingerprint)
                    trustedAccount = pinned
                    val text = opened.text
                    try {
                        // F75: field-level update of this session's own account, not a snapshot save.
                        ownStore(context).update { it.copy(certPin = prompt.fingerprint) }
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
            val accepted = credentials == null && confirm != null && confirm()
            val stopped = context.getString(R.string.plaintext_auth_stopped)
            if (!accepted) {
                opened = OpenResult.Failed(stopped)
            } else {
                val allowed = trustedAccount.copy(allowPlaintextAuth = true)
                try {
                    ownStore(context).update { it.copy(allowPlaintextAuth = true) }
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

    /**
     * One login, plus one fresh attempt without channel binding when automatic selection picked a SCRAM -PLUS
     * mechanism that failed (servers whose SASL lacks the binding type, e.g. tls-unique-only on TLS 1.3). The
     * retry keeps the TLS mode, so it never falls back to clear text, and it is not saved.
     */
    private fun login(account: AccountSettings, knownPassword: String? = null): OpenResult {
        val first = loginOnce(account, knownPassword)
        if (first !is OpenResult.Failed) return first
        val retry = plusFallback(account, first.text) ?: return first
        TrafficRecorder.event("main", "fallback", retry.authMechanism)
        // F64: not silent. The downgrade is noted for the status line and kept for the Test account report.
        android.util.Log.w("LiveImap", "SCRAM -PLUS failed; logged in with ${retry.authMechanism} (no channel binding)")
        val opened = loginOnce(retry, knownPassword)
        if (opened !is OpenResult.Failed) {
            channelBindingDropped = true
            noteTraffic("Logged in without channel binding (${retry.authMechanism})")
        }
        return opened
    }

    private fun loginOnce(account: AccountSettings, knownPassword: String? = null): OpenResult {
        namespaceList = null
        val password = if (knownPassword != null) {
            knownPassword
        } else {
            val context = currentApplication() ?: return OpenResult.Failed("keystore unavailable")
            try {
                // Link.connect is not a coroutine. password() hops to Dispatchers.IO.
                credentials?.password ?: runBlocking {
                    ownStore(context).password()
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
        TrafficRecorder.event("main", "connect", "${account.imapHost}:${account.imapPort} ${account.tlsMode.name}")
        val clientCert = ClientCertStore.load(account.clientCert)
        if (account.clientCert.isNotBlank() && clientCert == null) {
            throw MailFailure("client certificate missing; import it again in account settings")
        }
        // The other per-account choice: an Android KeyChain certificate, signed through the keystore.
        val keyChain = if (clientCert == null) KeyChainCerts.load(account.clientCertAlias) else null
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
            account.acceptInvalidCert,
            account.allowOldTls,
            account.authMechanism,
            clientCert?.p12,
            clientCert?.password ?: "",
            keyChain?.chain,
            keyChain?.key,
        )
        nativeTakeTls().takeIf { it.isNotEmpty() }?.let { TrafficRecorder.event("main", "tls", it) }
        if (opened == 0L) {
            val text = nativeTakeError()
            if (account.logImapTraffic) TrafficLog.noteStatus("Error $text")
            TrafficRecorder.event("main", "error", text)
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
        // F30: QRESYNC is not enabled. Its VANISHED/changed-FETCH answers were never processed, and once enabled the
        // server sends VANISHED instead of EXPUNGE on this connection. CONDSTORE keeps HIGHESTMODSEQ and EXPUNGE.
        when (featureCaps.resyncKind()) {
            "Qresync", "Condstore" -> nativeEnable(opened, enableForResync(featureCaps.resyncKind()))
        }
        // RFC 6855: UTF-8 mailbox names (and quoted strings) instead of modified UTF-7.
        utf8Names = false
        if (featureCaps.utf8Accept) {
            nativeEnable(opened, "UTF8=ACCEPT")
            utf8Names = true
        }
        if (featureCaps.compressDeflate && !account.logImapTraffic &&
            compressionWanted(account.compression, onCellular())
        ) {
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
            // F101: suspend/reconnect is not a user leave, so no CLOSE (it would expunge behind a pending Undo).
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
        // F101: switching folders is never an expunge. Only an explicit user leave (expungeOnLeave) sends CLOSE;
        // otherwise UNSELECT, or the SELECT below, which closes the old folder without expunging (RFC 3501 6.3.1).
        if (current != null && current != mailbox && featureCaps.unselect) {
            unselectSelectedMailbox()
        }
        val readWrite = write || (same && sequencesStale && wasReadWrite)
        val result = nativeSelect(requireHandle(), requireWireText(mailbox), readWrite) ?: run {
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
            requireWireText(prefix),
            parentMailbox?.let(::requireWireText),
            featureCaps.listKind(unreadCounts),
        ) ?: throw MailFailure("list failed")
        rows.toList()
    }

    override suspend fun mailboxListed(name: String): Boolean = keeper.read("list") {
        nativeMailboxListed(requireHandle(), wire(name))
    }

    override suspend fun statusMessages(mailboxes: List<String>): Map<String, Int> {
        if (mailboxes.isEmpty()) return emptyMap()
        return keeper.read("status") {
            nativeStatusMessages(requireHandle(), mailboxes.map(::requireWireText).toTypedArray())
        }
    }

    override suspend fun select(mailbox: String): SelectResult = keeper.read("select") {
        selectNow(wire(mailbox))
    }

    override suspend fun unselect() {
        val mailbox = selectedMailbox
        // Nothing selected: UNSELECT would be a BAD ("unselect failed" on opening a folder fresh after login).
        if (mailbox == null) return
        if (featureCaps.unselect) {
            unselectSelectedMailbox()
            return
        }
        val result = nativeSelect(requireHandle(), requireWireText(mailbox), false) ?: run {
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

    override suspend fun fetchStructureAt(uid: Long, section: String): MimePart = keeper.read("fetch") {
        nativeFetchStructureAt(requireHandle(), uid, section) ?: throw MailFailure("fetch failed")
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
            forgetSelected()
            ensureReadWrite()
            nativeStoreFlags(requireHandle(), uids.toLongArray(), requireFlags(add), requireFlags(remove))
        }
    }

    override suspend fun storeFlagsAll(add: Set<String>, remove: Set<String>) {
        keeper.write("store") {
            forgetSelected()
            ensureReadWrite()
            nativeStoreFlagsAll(requireHandle(), requireFlags(add), requireFlags(remove))
        }
    }

    override suspend fun uidExpungeDeleted() {
        keeper.write("expunge") {
            forgetSelected()
            ensureReadWrite()
            nativeUidExpungeDeleted(requireHandle())
        }
    }

    override suspend fun uidExpunge(uids: List<Long>) {
        if (uids.isEmpty()) return
        keeper.write("expunge") {
            forgetSelected()
            ensureReadWrite()
            nativeUidExpunge(requireHandle(), uids.toLongArray())
        }
    }

    override suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String) {
        if (uids.isEmpty()) return
        keeper.write("copy") {
            forgetSelected()
            ensureReadWrite()
            nativeCopyThenDelete(
                requireHandle(),
                uids.toLongArray(),
                wire(targetMailbox),
                moveCommandKind(account?.moveMethod ?: MoveMethod.CopyThenMarkDeleted, featureCaps.move),
            )
        }
    }

    override suspend fun copyUids(uids: List<Long>, targetMailbox: String) {
        if (uids.isEmpty()) return
        keeper.write("copy") {
            forgetSelected()
            ensureReadWrite()
            nativeCopyThenDelete(
                requireHandle(),
                uids.toLongArray(),
                wire(targetMailbox),
                "Copy",
            )
        }
    }

    override suspend fun copyAllThenDelete(targetMailbox: String) {
        keeper.write("copy") {
            forgetSelected()
            ensureReadWrite()
            nativeCopyAllThenDelete(
                requireHandle(),
                wire(targetMailbox),
                moveCommandKind(account?.moveMethod ?: MoveMethod.CopyThenMarkDeleted, featureCaps.move),
            )
        }
    }

    override suspend fun selectedExists(): Int = selected.exists

    override suspend fun copiedEpoch(): Long {
        val h = handle
        if (h == 0L) return 0L
        return nativeCopiedEpoch(h)
    }

    override suspend fun takeCopiedUids(): List<Long> {
        val h = handle
        if (h == 0L) return emptyList()
        return nativeTakeCopiedUids(h)?.asList().orEmpty()
    }

    override suspend fun searchText(query: String): List<Long> = keeper.read("search") {
        val h = requireHandle()
        remember("SEARCH $query") {
            val ids = nativeSearchText(h, requireWireText(query), featureCaps.searchKind() == "Esearch", !utf8Names)
                ?: throw MailFailure("search failed")
            ids.asList()
        }
    }

    override suspend fun searchCriterion(kind: String, argument: String): List<Long> = keeper.read("search") {
        val h = requireHandle()
        val withCharset = searchNeedsCharset(requireSearchArgument(kind, argument), utf8Names)
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
                    parsed.fuzzy,
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
            ids.asList()
        }
    }

    override suspend fun searchCount(kind: String, argument: String): Int {
        if (featureCaps.searchKind() != "Esearch") {
            return searchCriterion(kind, argument).size
        }
        return keeper.read("search") {
            val h = requireHandle()
            val withCharset = searchNeedsCharset(requireSearchArgument(kind, argument), utf8Names)
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
                    parsed.fuzzy,
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
        requireWireText(home)  // F42: the scope home folder goes out as a quoted string too
        val h = requireHandle()
        val parsed = parseAdvancedQuery(argument) ?: throw MailFailure("bad search")
        if (parsed.steps.isEmpty()) throw MailFailure("bad search")
        val withCharset = searchNeedsCharset(requireSearchArgument(kind, argument), utf8Names)
        val rows = nativeSearchScope(
            h,
            scopeName,
            home,
            parsed.combiner.name,
            BooleanArray(parsed.steps.size) { parsed.steps[it].negated },
            Array(parsed.steps.size) { parsed.steps[it].kind },
            Array(parsed.steps.size) { parsed.steps[it].argument },
            withCharset,
            parsed.fuzzy,
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
        requireWireText(home)  // F42: the scope home folder goes out as a quoted string too
        val h = requireHandle()
        val parsed = parseAdvancedQuery(argument) ?: throw MailFailure("bad search")
        if (parsed.steps.isEmpty()) throw MailFailure("bad search")
        val withCharset = searchNeedsCharset(requireSearchArgument(kind, argument), utf8Names)
        val count = nativeSearchScopeCount(
            h,
            scopeName,
            home,
            parsed.combiner.name,
            BooleanArray(parsed.steps.size) { parsed.steps[it].negated },
            Array(parsed.steps.size) { parsed.steps[it].kind },
            Array(parsed.steps.size) { parsed.steps[it].argument },
            withCharset,
            parsed.fuzzy,
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
            ids.asList()
        }
    }

    override suspend fun locateUid(uid: Long): List<Long> = keeper.read("search") {
        val ids = nativeLocateUid(
            requireHandle(),
            uid,
            featureCaps.searchKind() == "Esearch",
        ) ?: throw MailFailure("search failed")
        ids.asList()
    }

    override suspend fun sort(key: SortKey, newestFirst: Boolean): List<Long> = sortWith(key, newestFirst, null)

    override suspend fun sortMatching(key: SortKey, newestFirst: Boolean, criteria: String): List<Long> =
        sortWith(key, newestFirst, criteria)

    private suspend fun sortWith(key: SortKey, newestFirst: Boolean, criteria: String?): List<Long> {
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
            remember("SORT $token $newestFirst ${criteria.orEmpty()}") {
                val ids = nativeSort(h, token, newestFirst, command == "Esort", criteria?.let(::requireCriteria)) ?: throw MailFailure("sort failed")
                ids.asList()
            }
        }
    }

    override suspend fun sortWindow(key: SortKey, newestFirst: Boolean, first: Int, last: Int): UidWindow? =
        sortWindowWith(key, newestFirst, first, last, null)

    override suspend fun sortWindowMatching(
        key: SortKey,
        newestFirst: Boolean,
        first: Int,
        last: Int,
        criteria: String,
    ): UidWindow? = sortWindowWith(key, newestFirst, first, last, criteria)

    private suspend fun sortWindowWith(
        key: SortKey,
        newestFirst: Boolean,
        first: Int,
        last: Int,
        criteria: String?,
    ): UidWindow? {
        if (key == SortKey.Arrival || key == SortKey.ThreadReferences || key == SortKey.ThreadOrderedSubject) return null
        if (!featureCaps.sort || !featureCaps.esort || !featureCaps.partialWindows) return null
        require(first in 1..last) { "window $first..$last" }
        return keeper.read("sort") {
            val token = featureCaps.imapSortKey(sortToken(key))
            if (sortPartialIgnored) return@read null
            val h = requireHandle()
            run {
                val raw = nativeSortWindow(h, token, newestFirst, first, last, criteria?.let(::requireCriteria)) ?: throw MailFailure("sort failed")
                val window = uidWindowOf(raw)
                if (!partialWindowIgnored(window, first)) return@read window
                // Cyrus 3.12/3.13 answer UID SORT RETURN (PARTIAL ..) with COUNT only: report "no windows" so the
                // caller does one full sort instead of a full sort per window.
                sortPartialIgnored = true
            }
            null
        }
    }

    @Volatile private var sortPartialIgnored = false

    override suspend fun searchWindow(kind: String, argument: String, first: Int, last: Int): UidWindow? {
        if (kind == "Advanced" || !featureCaps.partialWindows) return null
        require(first != 0 && last != 0 && (first > 0) == (last > 0)) { "window $first..$last" }
        return keeper.read("search") {
            val h = requireHandle()
            val charset = searchNeedsCharset(requireSearchArgument(kind, argument), utf8Names)
            fun window(lo: Int, hi: Int) = uidWindowOf(
                nativeSearchCriterionWindow(h, kind, argument, charset, lo, hi) ?: throw MailFailure("search failed"),
            )
            if (first > 0) return@read window(first, last)
            // Negative ranges are sent as positive ones: Cyrus 3.13 answers PARTIAL -1:-3 with the OLDEST three.
            val total = window(1, 1).total
            val (lo, hi) = positiveRange(first, last, total) ?: return@read UidWindow(total, emptyList())
            window(lo, hi).copy(total = total)
        }
    }

    /** Turns this session into a long-search side connection (see LongSearch). */
    fun prepareLongSearch(timeoutSec: Int) = nativePrepareLongSearch(requireHandle(), timeoutSec)

    /** One search, without the keeper's reconnect-and-retry: a dropped connection is a cancel or a failure. */
    fun longSearch(kind: String, argument: String): List<Long> {
        val h = requireHandle()
        val withCharset = searchNeedsCharset(requireSearchArgument(kind, argument), utf8Names)
        val ids = if (kind == "Advanced") {
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
                parsed.fuzzy,
            )
        } else {
            nativeSearchCriterion(h, kind, argument, featureCaps.searchKind() == "Esearch", withCharset)
        } ?: throw MailFailure("search failed")
        return ids.asList()
    }

    fun searchProgress(): SearchProgress {
        val h = handle
        if (h == 0L) return SearchProgress(-1, -1, 0)
        val v = nativeProgress(h) ?: return SearchProgress(-1, -1, 0)
        return SearchProgress(v[0], v[1], v[2])
    }

    fun abortConnection() {
        val h = handle
        if (h != 0L) nativeAbort(h)
    }

    override suspend fun thread(key: SortKey): ThreadNode = threadWith(key, null)

    override suspend fun threadMatching(key: SortKey, criteria: String): ThreadNode = threadWith(key, criteria)

    private suspend fun threadWith(key: SortKey, criteria: String?): ThreadNode {
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
            remember("THREAD $algorithm ${criteria.orEmpty()}") {
                nativeThread(h, algorithm, criteria?.let(::requireCriteria)) ?: throw MailFailure("thread failed")
            }
        }
    }

    override suspend fun clientOrder(
        key: SortKey,
        newestFirst: Boolean,
        onProgress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
    ): List<Long> {
        val field = when (key) {
            SortKey.Date -> "DATE"
            SortKey.From -> "FROM"
            SortKey.Subject -> "SUBJECT"
            SortKey.To -> "TO"
            SortKey.Cc -> "CC"
            SortKey.Size -> "SIZE"
            else -> throw MailFailure("use an arrival IndexMode")
        }
        // F108: RFC 5256 keys (Date: header, base subject, first addr-mailbox), and a walk that is restarted when
        // another client's expunge shifts sequence numbers under it (a missing or doubled UID shows that).
        val wireField = if (field == "DATE") "DATEHDR" else field
        var attempt = 0
        while (true) {
            val exists = selectedExists()
            val keys = ClientSortKeys(exists, numeric = key == SortKey.Date || key == SortKey.Size)
            val seen = HashSet<Long>(exists * 2)
            var drift = false
            var first = 1
            while (first <= exists) {
                if (cancelled()) throw MailFailure("sort cancelled")
                val last = minOf(exists, first + ClientSortChunk - 1)
                val rows = keeper.read("client-sort") {
                    nativeFetchSortFieldsRange(requireHandle(), wireField, first, last)
                }
                if (rows == null) {
                    // F141: a chunk past a shrunken EXISTS (another client expunged ahead of the walk) is drift:
                    // re-select and restart instead of failing.
                    if (sortChunkDrift(exists, selectedExists(), sequencesStale)) { drift = true; break }
                    throw MailFailure("sort failed")
                }
                for (row in rows) {
                    if (!seen.add(row.uid)) drift = true
                    keys.add(sortKeyRow(key, row))
                }
                onProgress(last, exists)
                first = last + 1
            }
            if (!drift && seen.size == exists && selectedExists() == exists && !sequencesStale) {
                return keys.order(newestFirst).asList()
            }
            attempt += 1
            if (attempt >= 3) throw MailFailure("the folder kept changing during the sort; try again")
            keeper.read("select") {
                sequencesStale = true
                selectNow(selectedMailbox ?: throw MailFailure("sort failed"))
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
        // F136: not through the keeper. The watch is its own connection and runs beside the main one (off the
        // session lane); a reconnect of the main connection from here would race the lane's commands.
        watchMailbox = mailbox
        watchCallback = onChange
        nativeSetWatchWakeMillis((account?.idleWakeSeconds ?: 5) * 1000)
        nativeWatch(requireHandle(), wire(mailbox))
        noteTraffic("Idling")
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
            // A NOOP can carry EXPUNGE and EXISTS from another client. An expunge plus an arrival leaves EXISTS
            // and the cached UIDNEXT unchanged, so remembered searches must go (the safety suite caught a
            // search answering with a UID another client had expunged).
            forgetSelected()
            if (exists >= 0 && selectedMailbox != null) {
                if (exists != selected.exists) sequencesStale = true
                selected = selected.copy(exists = exists)
            }
        }
    }

    override suspend fun knownTrash(): String {
        val named = usableTrashName(account?.trashMailbox.orEmpty())
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

    /**
     * F14/F115: the user left [mailbox]. CLOSE (expunge) only that folder: reselect it read-write if the shared
     * selection moved meanwhile, through the keeper so a dead link reconnects first.
     */
    override suspend fun expungeOnLeave(mailbox: String) {
        if (account?.autoExpunge != true || mailbox.isEmpty()) return
        keeper.write("close") {
            openMailbox(wire(mailbox), write = true)
            closeSelectedMailbox()
        }
    }

    override suspend fun selectedInfo(): SelectResult = selected

    override suspend fun append(mailbox: String, rfc822: ByteArray, flags: Set<String>) {
        keeper.write("append") {
            forgetSelected()
            nativeAppend(requireHandle(), wire(mailbox), rfc822, requireFlags(flags))
        }
    }

    override suspend fun appendReturningUid(mailbox: String, rfc822: ByteArray, flags: Set<String>): Long =
        keeper.write("append") {
            forgetSelected()
            nativeAppend(requireHandle(), wire(mailbox), rfc822, requireFlags(flags))
        }

    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>, dsnNotify: Int): Int =
        smtpSendFrom(rfc822, recipients, dsnNotify, "")

    override suspend fun smtpSendFrom(rfc822: ByteArray, recipients: List<String>, dsnNotify: Int, envelopeFrom: String): Int {
        val context = currentApplication()
        val ownSmtpPassword = credentials?.smtpPassword
            ?: if (context != null && ownerId != null) ownStore(context).smtpPassword() else ""
        // F52 (David): the IMAP password is used for SMTP only when "Same as IMAP password" is ticked; it is never
        // assumed. No SMTP password = send without AUTH (native adds a prompt if the server then demands AUTH).
        val acct = account
        val smtpPassword = smtpPasswordFor(acct?.smtpSameAsImap == true, ownSmtpPassword, loggedInPassword.orEmpty())
        val smtpUser = (account?.smtpUsername ?: "").ifEmpty { acct?.username.orEmpty() }
        return keeper.write("send") {
            forgetSelected()
            nativeSmtp(
                requireHandle(),
                rfc822,
                recipients.toTypedArray(),
                smtpUser,
                smtpPassword,
                dsnNotify,
                envelopeFrom,
            )
        }
    }

    /**
     * Test account: log in to SMTP without sending anything (connect, TLS, EHLO, AUTH, QUIT). Returns
     * [SmtpLoginCheck]; failures throw [MailFailure] with the server's text.
     */
    override suspend fun smtpLoginCheck(): org.dlang.liveimap.session.SmtpLoginCheck {
        val context = currentApplication()
        val ownSmtpPassword = credentials?.smtpPassword
            ?: if (context != null && ownerId != null) ownStore(context).smtpPassword() else ""
        val acct = account
        val smtpPassword = smtpPasswordFor(acct?.smtpSameAsImap == true, ownSmtpPassword, loggedInPassword.orEmpty())
        val smtpUser = (account?.smtpUsername ?: "").ifEmpty { acct?.username.orEmpty() }
        val code = keeper.write("send") {
            nativeSmtp(requireHandle(), null, emptyArray(), smtpUser, smtpPassword, 0, "")
        }
        return when (code) {
            2 -> org.dlang.liveimap.session.SmtpLoginCheck.LoggedIn
            3 -> org.dlang.liveimap.session.SmtpLoginCheck.NoAuthOffered
            else -> org.dlang.liveimap.session.SmtpLoginCheck.NoPassword
        }
    }

    override fun close() {
        if (handle != 0L) TrafficRecorder.event("main", "close")
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
            val result = nativeSelect(requireHandle(), requireWireText(mailbox), readWrite) ?: throw MailFailure("select failed")
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
            nativeSetWatchWakeMillis((account?.idleWakeSeconds ?: 5) * 1000)
            nativeWatch(requireHandle(), wire(mailbox))
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
            // F144: these changes describe the main connection's selected folder; the index's watcher gets them
            // only when that is the folder it watches, never another folder's counts or epoch.
            if (reconnectChangeReachesWatcher(selectedMailbox, watchMailbox)) watchCallback?.invoke(change)
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

    /** Drops remembered results for the selected mailbox: after any change the app makes, and after NOOP. */
    private fun forgetSelected() {
        val mailbox = selectedMailbox ?: return
        cache.keys.retainAll { it.mailbox != mailbox }
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
        acceptInvalidCert: Boolean,
        allowOldTls: Boolean,
        authMechanism: String,
        clientP12: ByteArray?,
        clientP12Password: String,
        keyChainChain: Array<ByteArray>?,
        keyChainKey: Any?,
    ): Long

    private external fun nativeSetSessionFlags(handle: Long, pipeline: Boolean, log: Boolean, logPath: String)

    private external fun nativeSessionDead(handle: Long): Boolean

    private external fun nativeNoop(handle: Long): Int

    private external fun nativeCapabilityLine(handle: Long): String
    private external fun nativeEnable(handle: Long, capability: String)

    /** True once UTF8=ACCEPT is on: mailbox names are UTF-8 on the wire (RFC 6855). */
    @Volatile private var utf8Names = false

    /** Saved names (Sent, Drafts, favorites…) from before the UTF-8 switch are mUTF-7; send them as UTF-8. */
    private fun wire(name: String): String =
        requireWireText(if (utf8Names) org.dlang.liveimap.ui.compose.Mutf7.savedForUtf8(name) else name)
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
    private external fun nativeCopiedEpoch(handle: Long): Long
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
    private external fun nativeFetchStructureAt(handle: Long, uid: Long, section: String): MimePart?
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
    private external fun nativeSearchText(handle: Long, query: String, useEsearch: Boolean, withCharset: Boolean): LongArray?
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
        fuzzy: Boolean,
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
        fuzzy: Boolean,
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
        fuzzy: Boolean,
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
        fuzzy: Boolean,
    ): Long

    private external fun nativeSearchStart(
        handle: Long,
        rule: String,
        byUid: Boolean,
        edge: String,
        useEsearch: Boolean,
    ): LongArray?

    private external fun nativeLocateUid(handle: Long, uid: Long, useEsearch: Boolean): LongArray?
    private external fun nativeSort(
        handle: Long,
        key: String,
        newestFirst: Boolean,
        useEsort: Boolean,
        criteria: String?,
    ): LongArray?
    private external fun nativePrepareLongSearch(handle: Long, timeoutSec: Int)
    private external fun nativeProgress(handle: Long): LongArray?
    private external fun nativeAbort(handle: Long)
    private external fun nativeSortWindow(
        handle: Long,
        key: String,
        newestFirst: Boolean,
        first: Int,
        last: Int,
        criteria: String?,
    ): LongArray?
    private external fun nativeSearchCriterionWindow(
        handle: Long,
        kind: String,
        argument: String,
        withCharset: Boolean,
        first: Int,
        last: Int,
    ): LongArray?
    private external fun nativeFetchSortFieldsRange(handle: Long, field: String, first: Int, last: Int): Array<SortField>?
    private external fun nativeFetchThreadHeaders(handle: Long): Array<ThreadHeader>?
    private external fun nativeThread(handle: Long, algorithm: String, criteria: String?): ThreadNode?
    private external fun nativeWatch(handle: Long, mailbox: String)
    private external fun nativeStopWatch(handle: Long)
    private external fun nativeSmtp(
        handle: Long,
        message: ByteArray?,
        recipients: Array<String>,
        smtpUsername: String,
        smtpPassword: String,
        dsnNotify: Int,
        envelopeFrom: String,
    ): Int

    companion object {
        init {
            System.loadLibrary("liveimap")
        }

        /** IDLE poll backstop in ms (F38; account setting idleWakeSeconds). */
        @JvmStatic
        external fun nativeSetWatchWakeMillis(millis: Int)

        /** IDLE is renewed (DONE + IDLE) after this many seconds; 0 restores the default (9 min). Lab hook. */
        @JvmStatic
        external fun nativeSetIdleRenewSeconds(seconds: Int)

        /** IDLE renewals since process start (lab check). */
        @JvmStatic
        external fun nativeIdleRenews(): Int

        @JvmStatic
        private external fun nativeTakeError(): String

        /** "TLSv1.3 <cipher> binding=<type>" of the last login on this thread, or "" for plaintext. */
        @JvmStatic
        private external fun nativeTakeTls(): String

        @JvmStatic
        private external fun nativeTakeCertOffer(): Array<String>?

        @JvmStatic
        private external fun nativeProbe(
            host: String,
            port: Int,
            tlsMode: String,
            certPin: String,
            acceptInvalidCert: Boolean,
            allowOldTls: Boolean,
        ): Array<String>?

        /**
         * The settings check: connects in the account's TLS mode, reads CAPABILITY before and after TLS, judges
         * the certificate, and never logs in. A TLS failure with old TLS off is retried once with it on, to tell
         * the user the server needs that setting. Blocking; call off the main thread.
         */
        fun checkServerSettings(account: AccountSettings): ServerCheck {
            fun run(oldTls: Boolean) = ServerProbe.parse(
                nativeProbe(
                    account.imapHost,
                    account.imapPort,
                    account.tlsMode.name,
                    account.certPin,
                    account.acceptInvalidCert,
                    oldTls,
                ),
            )
            val probe = run(account.allowOldTls)
            val oldTlsWorks = if (probe.tls.isNotEmpty() && !account.allowOldTls) run(true).tls.isEmpty() else null
            return checkServer(account, probe, oldTlsWorks)
        }
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

internal fun currentApplication(): Context? {
    return try {
        val thread = Class.forName("android.app.ActivityThread")
        val method = thread.getMethod("currentApplication")
        method.invoke(null) as? Context
    } catch (_: Exception) {
        null
    }
}

/** Native window results are [count, uid...]; a server that sent no COUNT gives -1 (total unknown: window size). */
/** A server that advertised PARTIAL but returned COUNT without the window it covers (seen on Cyrus ESORT). */
internal fun partialWindowIgnored(window: UidWindow, first: Int): Boolean =
    window.uids.isEmpty() && window.total >= first

/** Negative positions (-1 = last) as 1-based ascending positions within [total], or null if none remain. */
internal fun positiveRange(first: Int, last: Int, total: Int): Pair<Int, Int>? {
    val a = (total + 1 + first).coerceAtLeast(1)
    val b = (total + 1 + last).coerceAtLeast(1)
    val lo = minOf(a, b)
    val hi = minOf(maxOf(a, b), total)
    return if (total <= 0 || lo > hi) null else lo to hi
}

internal fun uidWindowOf(raw: LongArray): UidWindow {
    if (raw.isEmpty()) return UidWindow(0, emptyList())
    val uids = raw.copyOfRange(1, raw.size).asList()
    val total = raw[0].takeIf { it >= 0 }?.toInt() ?: uids.size
    return UidWindow(total, uids)
}

internal const val ClientSortChunk = 5000

/** True when the active network is cellular (Auto compression); false if unknown. */
internal fun onCellular(): Boolean {
    val context = currentApplication() ?: return false
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
}

/**
 * Client-side sort keys in primitive arrays: uid and numeric key as longs, text keys lowercased once. Blank keys
 * sort after the rest by uid, as before.
 */
/** F108: turn a fetched header into its RFC 5256 sort key. */
internal fun sortKeyRow(key: SortKey, row: SortField): SortField = when (key) {
    SortKey.Date -> {
        val epoch = org.dlang.liveimap.session.dateHeaderEpoch(row.text)
        SortField(row.uid, "", epoch ?: row.number, epoch == null && row.number == 0L)
    }
    SortKey.Subject -> row.copy(text = org.dlang.liveimap.session.baseSubject(
        org.dlang.liveimap.ui.compose.decodeHeaderWords(row.text)), empty = false)
    SortKey.From, SortKey.To, SortKey.Cc -> row.copy(text = org.dlang.liveimap.session.firstAddrMailbox(
        row.text) { org.dlang.liveimap.ui.compose.decodeHeaderWords(it) }, empty = false) // F137: parse raw, then decode
    else -> row
}

/** F141: a failed sort chunk is drift (restart the walk) when the folder shrank or sequence numbers went stale. */
internal fun sortChunkDrift(existsAtStart: Int, existsNow: Int, stale: Boolean): Boolean = stale || existsNow < existsAtStart

internal class ClientSortKeys(capacity: Int, private val numeric: Boolean) {
    private var uids = LongArray(capacity.coerceAtLeast(16))
    private var numbers = if (numeric) LongArray(uids.size) else LongArray(0)
    private var texts: Array<String?> = if (numeric) emptyArray() else arrayOfNulls(uids.size)
    private var blank = BooleanArray(uids.size)
    var size = 0
        private set

    fun add(row: SortField) {
        if (row.uid <= 0L) return
        if (size == uids.size) grow()
        uids[size] = row.uid
        if (numeric) numbers[size] = row.number else texts[size] = row.text.lowercase()
        blank[size] = row.empty
        size += 1
    }

    private fun grow() {
        val n = uids.size * 2
        uids = uids.copyOf(n)
        blank = blank.copyOf(n)
        if (numeric) numbers = numbers.copyOf(n) else texts = texts.copyOf(n)
    }

    fun order(newestFirst: Boolean): LongArray {
        val filled = (0 until size).filter { !blank[it] }
        val byValue: Comparator<Int> = if (numeric) {
            compareBy<Int> { numbers[it] }.thenBy { uids[it] }
        } else {
            compareBy<Int> { texts[it] }.thenBy { uids[it] }
        }
        val sorted = filled.sortedWith(byValue).let { if (newestFirst) it.asReversed() else it }
        val blanks = (0 until size).filter { blank[it] }.sortedBy { uids[it] }
        val out = LongArray(size)
        var k = 0
        for (i in sorted) out[k++] = uids[i]
        for (i in blanks) out[k++] = uids[i]
        return out
    }
}

/** F30: the ENABLE token for a resync kind. QRESYNC data is not processed, so QRESYNC servers get CONDSTORE. */
internal fun enableForResync(kind: String): String = when (kind) {
    "Qresync", "Condstore" -> "CONDSTORE"
    else -> ""
}

/** F52: SMTP password choice; "" = none configured (sent without AUTH, never the IMAP password). */
internal fun smtpPasswordFor(sameAsImap: Boolean, smtpPassword: String, imapPassword: String): String =
    if (sameAsImap) imapPassword else smtpPassword

/** F144: a reconnect's re-SELECT changes go to the watcher only for the very folder it watches. */
internal fun reconnectChangeReachesWatcher(selected: String?, watched: String?): Boolean =
    selected != null && watched != null && sameWireMailbox(selected, watched)

private fun sameWireMailbox(a: String, b: String): Boolean =
    a == b || (a.equals("INBOX", ignoreCase = true) && b.equals("INBOX", ignoreCase = true))
