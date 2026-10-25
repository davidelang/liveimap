package org.dlang.liveimap.engine

import android.content.Context
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import com.davidlang.liveimap.R
import org.dlang.liveimap.session.Capabilities
import org.dlang.liveimap.session.CertPrompt
import org.dlang.liveimap.session.ClientFetch
import org.dlang.liveimap.session.ClientHeaders
import org.dlang.liveimap.session.ClientSortCollation
import org.dlang.liveimap.session.ConnectionLost
import org.dlang.liveimap.session.ConnectionState
import org.dlang.liveimap.session.FolderEntry
import org.dlang.liveimap.session.IndexMode
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.session.LifecycleCall
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.MailboxUids
import org.dlang.liveimap.session.MessageOrder
import org.dlang.liveimap.session.MimePart
import org.dlang.liveimap.session.Namespace
import org.dlang.liveimap.session.NamespaceKind
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.SearchEdge
import org.dlang.liveimap.session.SelectResult
import org.dlang.liveimap.session.ThreadFlat
import org.dlang.liveimap.session.ThreadNode
import org.dlang.liveimap.session.UidWindow
import org.dlang.liveimap.session.SearchProgress
import org.dlang.liveimap.session.baseSubject
import org.dlang.liveimap.session.dateHeaderEpoch
import org.dlang.liveimap.session.firstAddrMailbox
import org.dlang.liveimap.session.flattenThread
import org.dlang.liveimap.session.localeKeyBytes
import org.dlang.liveimap.session.mailboxMarkedTrash
import org.dlang.liveimap.session.messageOrderThread
import org.dlang.liveimap.session.packKeyBytes
import org.dlang.liveimap.session.plainKeyBytes
import org.dlang.liveimap.ui.compose.decodeHeaderWords
import org.dlang.liveimap.session.usableTrashName
import org.dlang.liveimap.session.sameImapIdentity
import org.dlang.liveimap.session.plusFallback
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.compressionWanted
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.MoveMethod
import org.dlang.liveimap.settings.SmtpNoAuth
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

/** The server rejected AUTH or LOGIN. A local SASL failure is not this. */
class ImapServerAuthenticationRejected(text: String) : MailFailure(text)

/**
 * A server rejection of the first attempt stays when the SCRAM-PLUS fallback fails locally.
 * A later server rejection, or a fallback that connects, is the result. A local failure is not an auth rejection.
 */
internal fun openResultAfterPlusFallback(first: OpenResult, opened: OpenResult): OpenResult {
    if (first is OpenResult.AuthRejected && opened is OpenResult.Failed && opened !is OpenResult.AuthRejected) {
        return first
    }
    return opened
}

/** Settings for an SMTP probe that has not opened IMAP. */
internal class SmtpProbeMaterial(
    val settings: AccountSettings,
    val password: String,
    val smtpPassword: String,
)

/** One open or stage: the handle, the TLS line, and the failure, together. */
internal class SessionOpen(
    @JvmField val handle: Long,
    @JvmField val tls: String,
    @JvmField val error: String,
    @JvmField val authRejected: Boolean,
    @JvmField val certSubject: String,
    @JvmField val certIssuer: String,
    @JvmField val certNotBefore: String,
    @JvmField val certNotAfter: String,
    @JvmField val certFingerprint: String,
)

/** UIDs created by one copy or move, and the target mailbox epoch. */
internal class CopyMoveResult(
    @JvmField val copiedUids: LongArray,
    @JvmField val targetEpoch: Long,
)

/** Builds a mailbox UID list from a primitive array. */
internal object NativeBoxes {
    @JvmStatic
    fun mailboxUids(name: String, uids: LongArray): MailboxUids =
        MailboxUids(name, uids.asList())
}

/** Mailbox and callback from the last watch request. [clearPublishedWatch] does not drop this. */
internal class WatchTarget(
    val mailbox: String,
    val onChange: (MailboxChange) -> Unit,
)

/**
 * Published offer wins. A cleared publish still starts [requested].
 * A 0 throws [ConnectionLost] after [markLost] and [clearOffer], so resume does not clear watchLost.
 * No idle note. Returns false when no watch was requested (start is not called).
 */
internal fun runRewatch(
    published: WatchTarget?,
    requested: WatchTarget?,
    start: (WatchTarget) -> Long,
    markLost: () -> Unit,
    clearOffer: () -> Unit,
): Boolean {
    val target = published ?: requested ?: return false
    val started = start(target)
    if (started == 0L) {
        markLost()
        clearOffer()
        throw ConnectionLost("watch lost")
    }
    return true
}

class LibetpanMailSession : MailSession {
    private var handle: Long = 0
    private var copiedUidStore = LongArray(0)
    private var copiedEpochStore = 0L
    private var pendingCertOffer: Array<String>? = null

    @Volatile
    private var capabilityLine: String = ""
        set(value) {
            if (field == value) return
            field = value
            val parsed = Capabilities.parse(value)
            parsedCaps = parsed
            storedFeatureCaps = parsed.without(account?.hiddenCapabilities ?: emptySet())
        }

    @Volatile
    private var parsedCaps: Capabilities = Capabilities.parse("")

    @Volatile
    private var storedFeatureCaps: Capabilities = parsedCaps

    private var account: AccountSettings? = null
        set(value) {
            field = value
            storedFeatureCaps = parsedCaps.without(value?.hiddenCapabilities ?: emptySet())
        }

    /** Preserved key from a refused open. That path does not store the account; handle stays 0. */
    private var refusedSecurityKey: String? = null
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

    private val publishedWatch = AtomicReference<PublishedWatch?>(null)

    /** Last watch or rewatch target. Survives a cleared publish so rewatch can start it. */
    @Volatile
    private var requestedWatch: WatchTarget? = null

    private class WatchOffer(
        val mailbox: String,
        val onChange: (MailboxChange) -> Unit,
    )

    private class PublishedWatch(
        val generation: Long,
        val offer: WatchOffer?,
    )

    @Volatile
    private var certConfirmer: (suspend (CertPrompt) -> Boolean)? = null

    /** F64: true once this session logged in by the no-channel-binding retry after a failed SCRAM -PLUS. */
    @Volatile var channelBindingDropped: Boolean = false
        private set

    @Volatile
    private var plaintextConfirmer: (suspend () -> Boolean)? = null

    // Concurrent: onNativeWatch prunes it from the native IDLE thread while the lane reads it (F25).
    // One MessageOrder per command for the selected folder. No Any cast.
    private val cache = java.util.concurrent.ConcurrentHashMap<String, MessageOrder>()
    private val threadHeaders = java.util.concurrent.ConcurrentHashMap<String, ClientHeaders>()
    private val link = SessionLink()
    private val keeper = ConnectionKeeper(
        link,
        clock = { System.nanoTime() / 1_000_000L },
        sleep = { Thread.sleep(it) },
    )

    override val capabilities: Set<String>
        get() = parsedCaps.names

    override val featureCaps: Capabilities
        get() = storedFeatureCaps

    override val connectionState: StateFlow<ConnectionState>
        get() = keeper.connectionState

    override suspend fun resume() {
        val call = coroutineContext[LifecycleCall]
        if (call == null) keeper.resume() else keeper.resume(call.clock, call.epoch)
    }

    override suspend fun suspendConnections() {
        val call = coroutineContext[LifecycleCall]
        if (call == null) keeper.suspendConnections() else keeper.suspendConnections(call.clock, call.epoch)
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

    /** Next [loginOnce] stores credentials and does not open a command connection. */
    @Volatile
    private var stageCommand = false

    private fun ownStore(context: android.content.Context): DataStoreSettingsStore {
        val id = ownerId ?: throw MailFailure("session has no account")
        return DataStoreSettingsStore(context, id)
    }

    override suspend fun open(account: AccountSettings): OpenResult {
        val key = account.preservedSecurityKey()
        if (key != null) {
            refusedSecurityKey = key
            return OpenResult.Failed("account settings need attention: $key")
        }
        refusedSecurityKey = null
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
                    nativeSetIdleRenewSeconds(account.idleRenewMinutes * 60)
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
        if (opened is OpenResult.Failed && opened !is OpenResult.AuthRejected && promptableCert(opened.text)) {
            val parts = pendingCertOffer
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
        if (opened is OpenResult.Failed && opened !is OpenResult.AuthRejected && opened.text == "plaintext login needs confirmation") {
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
            nativeSetIdleRenewSeconds(account.idleRenewMinutes * 60)
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
        return openResultAfterPlusFallback(first, opened)
    }

    /**
     * Password for this session: lab hook when set, else what logged in here, else this account's stored secret.
     * [Link.connect] reads this before a reconnect close; a full [close] may clear [loggedInPassword] afterward.
     */
    private fun sessionPassword(account: AccountSettings): String {
        LabTestHooks.passwordFor(account.imapHost)?.let { return it }
        loggedInPassword?.let { return it }
        val context = currentApplication() ?: throw MailFailure("keystore unavailable")
        return credentials?.password ?: runBlocking {
            ownStore(context).password()
        }
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
        val opened = if (stageCommand) {
            nativeStage(
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
                account.smtpTlsMode.name,
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
        } else {
            nativeOpen(
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
                account.smtpTlsMode.name,
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
        }
        pendingCertOffer = if (opened.certFingerprint.isNotEmpty()) {
            arrayOf(
                opened.certSubject,
                opened.certIssuer,
                opened.certNotBefore,
                opened.certNotAfter,
                opened.certFingerprint,
            )
        } else {
            null
        }
        opened.tls.takeIf { it.isNotEmpty() }?.let { TrafficRecorder.event("main", "tls", it) }
        if (opened.handle == 0L) {
            val text = opened.error
            val serverRejected = !stageCommand && opened.authRejected
            if (account.logImapTraffic) TrafficLog.noteStatus("Error $text")
            TrafficRecorder.event("main", "error", text)
            finishTraffic(account)
            if (serverRejected) return OpenResult.AuthRejected(text)
            return OpenResult.Failed(text)
        }
        if (stageCommand) {
            handle = opened.handle
            this.account = account
            loggedInPassword = password
            capabilityLine = ""
            clearOrderCache()
            compressed = false
            finishTraffic(account)
            return OpenResult.Connected
        }
        val line = nativeCapabilityLine(opened.handle)
        val gate = capabilityGate(line)
        if (gate is OpenResult.Rejected) {
            nativeClose(opened.handle)
            finishTraffic(account)
            return gate
        }
        if (account.logImapTraffic) TrafficLog.noteCapability(line)
        handle = opened.handle
        this.account = account
        loggedInPassword = password
        capabilityLine = line
        clearOrderCache()
        compressed = false
        // F30: QRESYNC is not enabled. Its VANISHED/changed-FETCH answers were never processed, and once enabled the
        // server sends VANISHED instead of EXPUNGE on this connection. CONDSTORE keeps HIGHESTMODSEQ and EXPUNGE.
        when (featureCaps.resyncKind()) {
            "Qresync", "Condstore" -> nativeEnable(opened.handle, enableForResync(featureCaps.resyncKind()))
        }
        // RFC 6855: UTF-8 mailbox names (and quoted strings) instead of modified UTF-7.
        utf8Names = false
        if (featureCaps.utf8Accept) {
            nativeEnable(opened.handle, "UTF8=ACCEPT")
            utf8Names = true
        }
        if (featureCaps.compressDeflate && !account.logImapTraffic &&
            compressionWanted(account.compression, onCellular())
        ) {
            nativeCompress(opened.handle)
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
        val name = wire(mailbox)
        val same = selectedMailbox == name
        val wasReadWrite = selectedReadWrite
        val sufficient = same && !sequencesStale && (wasReadWrite || !write)
        if (sufficient) {
            return selected
        }
        val current = selectedMailbox
        if (current != null && !sameWireMailbox(current, name)) clearOrderCache()
        // F101: switching folders is never an expunge. Only an explicit user leave (expungeOnLeave) sends CLOSE;
        // otherwise UNSELECT, or the SELECT below, which closes the old folder without expunging (RFC 3501 6.3.1).
        if (current != null && current != name && featureCaps.unselect) {
            unselectSelectedMailbox()
        }
        val readWrite = write || (same && sequencesStale && wasReadWrite)
        val result = nativeSelect(requireHandle(), name, readWrite) ?: run {
            noteTraffic("Error select failed")
            throw MailFailure("select failed")
        }
        noteValidity(name, result.uidValidity)
        selectedMailbox = name
        selectedReadWrite = readWrite
        selected = result
        sequencesStale = false
        keeper.noteSelected(result)
        noteTraffic("Selected $name")
        return result
    }

    private fun selectNow(mailbox: String): SelectResult = openMailbox(mailbox, write = false)

    /**
     * Courier refuses APPEND into the mailbox that is selected read-only ("NO Current box is selected READ-ONLY"),
     * e.g. right after a SEARCH ran on it via EXAMINE. Re-SELECT it read-write first. Never CLOSE (F101: that
     * would expunge); a re-SELECT of the same folder expunges nothing.
     */
    private fun writableForAppend(name: String) {
        if (appendNeedsReadWrite(selectedMailbox, selectedReadWrite, name)) openMailbox(name, write = true)
    }

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
            wire(prefix),
            parentMailbox?.let(::wire),
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
            nativeStatusMessages(requireHandle(), mailboxes.map(::wire).toTypedArray())
        }
    }

    override suspend fun select(mailbox: String): SelectResult = keeper.read("select", reportNewEpoch = true) {
        selectNow(mailbox)
    }

    override suspend fun unselect() {
        val mailbox = selectedMailbox
        // Nothing selected: UNSELECT would be a BAD ("unselect failed" on opening a folder fresh after login).
        if (mailbox == null) return
        if (featureCaps.unselect) {
            unselectSelectedMailbox()
            clearOrderCache()
            return
        }
        val name = wire(mailbox)
        val result = nativeSelect(requireHandle(), name, false) ?: run {
            noteTraffic("Error select failed")
            throw MailFailure("select failed")
        }
        noteValidity(name, result.uidValidity)
        selectedMailbox = name
        selectedReadWrite = false
        selected = result
        sequencesStale = false
        keeper.noteSelected(result)
        noteTraffic("Selected $name")
    }

    override suspend fun fetchIndex(request: IndexRequest): List<IndexRow> {
        val rows = keeper.read("fetch") {
            val name = wire(request.mailbox)
            if (sequencesStale || selectedMailbox != name) {
                selectNow(name)
            }
            val h = requireHandle()
            if (request.uidsOnly) {
                val ids = nativeSearchCriterion(h, "All", "", featureCaps.searchKind() == "Esearch", false)
                    ?: throw MailFailure("fetch failed")
                return@read uidOnlyRows(request, ids)
            }
            val settings = account
            val useServerPreview = request.includePreview && featureCaps.previewKind() == "Preview"
            val fetched = nativeFetchIndex(
                h,
                name,
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
            fetched.toList()
        }
        return rows
    }

    /** Sequences follow the UID SEARCH ALL reply. Envelope fields stay empty. */
    private fun uidOnlyRows(request: IndexRequest, ids: LongArray): List<IndexRow> {
        if (ids.isEmpty()) return emptyList()
        val indexes = when (request.mode) {
            IndexMode.ArrivalOldest -> listOf(0)
            IndexMode.ArrivalNewest -> listOf(ids.lastIndex)
            IndexMode.ArrivalRange -> {
                val first = request.firstSequence.coerceAtLeast(1)
                val last = request.lastSequence.coerceAtMost(ids.size)
                if (first > last) emptyList() else (first - 1 until last).toList()
            }
            else -> emptyList()
        }
        return indexes.map { index ->
            IndexRow(
                uid = ids[index],
                sequence = index + 1,
                flags = emptySet(),
                internalDateEpoch = 0L,
                size = 0,
                from = "",
                subject = "",
                envelopeDate = "",
                preview = null,
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
            copiedUidStore = LongArray(0)
            copiedEpochStore = 0L
            val moved = nativeCopyThenDelete(
                requireHandle(),
                uids.toLongArray(),
                wire(targetMailbox),
                moveCommandKind(account?.moveMethod ?: MoveMethod.CopyThenMarkDeleted, featureCaps.move),
            ) ?: return@write
            copiedUidStore = moved.copiedUids
            copiedEpochStore = moved.targetEpoch
        }
    }

    override suspend fun copyUids(uids: List<Long>, targetMailbox: String) {
        if (uids.isEmpty()) return
        keeper.write("copy") {
            forgetSelected()
            ensureReadWrite()
            copiedUidStore = LongArray(0)
            copiedEpochStore = 0L
            val moved = nativeCopyThenDelete(
                requireHandle(),
                uids.toLongArray(),
                wire(targetMailbox),
                "Copy",
            ) ?: return@write
            copiedUidStore = moved.copiedUids
            copiedEpochStore = moved.targetEpoch
        }
    }

    override suspend fun copyAllThenDelete(targetMailbox: String) {
        keeper.write("copy") {
            forgetSelected()
            ensureReadWrite()
            copiedUidStore = LongArray(0)
            copiedEpochStore = 0L
            val moved = nativeCopyAllThenDelete(
                requireHandle(),
                wire(targetMailbox),
                moveCommandKind(account?.moveMethod ?: MoveMethod.CopyThenMarkDeleted, featureCaps.move),
            ) ?: return@write
            copiedUidStore = moved.copiedUids
            copiedEpochStore = moved.targetEpoch
        }
    }

    override suspend fun selectedExists(): Int = selected.exists

    override suspend fun copiedEpoch(): Long {
        if (handle == 0L) throw MailFailure("not connected")
        return copiedEpochStore
    }

    override suspend fun takeCopiedUids(): List<Long> {
        if (handle == 0L) throw MailFailure("not connected")
        val ids = copiedUidStore
        copiedUidStore = LongArray(0)
        return ids.asList()
    }

    override suspend fun searchText(query: String): List<Long> = keeper.read("search") {
        val h = requireHandle()
        remember("SEARCH $query") {
            val ids = nativeSearchText(h, requireWireText(query), featureCaps.searchKind() == "Esearch", !utf8Names)
                ?: throw MailFailure("search failed")
            orderOf(ids)
        }.uids.asList()
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
            orderOf(ids)
        }.uids.asList()
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
        val homeWire = wire(home) // F42: the scope home folder goes out as a quoted string too
        val h = requireHandle()
        val parsed = parseAdvancedQuery(argument) ?: throw MailFailure("bad search")
        if (parsed.steps.isEmpty()) throw MailFailure("bad search")
        val withCharset = searchNeedsCharset(requireSearchArgument(kind, argument), utf8Names)
        val rows = nativeSearchScope(
            h,
            scopeName,
            homeWire,
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
        val homeWire = wire(home) // F42: the scope home folder goes out as a quoted string too
        val h = requireHandle()
        val parsed = parseAdvancedQuery(argument) ?: throw MailFailure("bad search")
        if (parsed.steps.isEmpty()) throw MailFailure("bad search")
        val withCharset = searchNeedsCharset(requireSearchArgument(kind, argument), utf8Names)
        val count = nativeSearchScopeCount(
            h,
            scopeName,
            homeWire,
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
                val ids = nativeSort(h, token, newestFirst, command == "Esort", criteria?.let(::requireCriteria))
                    ?: throw MailFailure("sort failed")
                orderOf(ids)
            }.uids.asList()
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
            val order = remember("THREAD $algorithm ${criteria.orEmpty()}") {
                val node = nativeThread(h, algorithm, criteria?.let(::requireCriteria)) ?: throw MailFailure("thread failed")
                val flat = flattenThread(node)
                orderOf(flat.uids, flat.depths)
            }
            messageOrderThread(order)
        }
    }

    override suspend fun clientOrder(
        key: SortKey,
        newestFirst: Boolean,
        onProgress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
    ): List<Long> = clientOrderCollated(key, newestFirst, onProgress, cancelled)

    /** [collation] defaults to [ClientSortCollation.Locale]. A different value is a different command, so a full rebuild. */
    internal suspend fun clientOrderCollated(
        key: SortKey,
        newestFirst: Boolean,
        onProgress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
        collation: ClientSortCollation = ClientSortCollation.Locale,
    ): List<Long> {
        val localeTag = if (collation == ClientSortCollation.Locale) " ${Locale.getDefault().toLanguageTag()}" else ""
        val command = "CLIENT SORT ${sortToken(key)} $newestFirst ${collation.name}$localeTag"
        val mailbox = selectedMailbox ?: throw MailFailure("sort failed")
        val cached = cache[command]
        if (cached != null && sameWireMailbox(cached.mailbox, mailbox) && cached.uidValidity != selected.uidValidity) {
            toastRebuilt(mailbox)
            cache.remove(command)
        }
        val hit = cache[command]
        val order = if (
            hit != null && sameWireMailbox(hit.mailbox, mailbox) && hit.uidValidity == selected.uidValidity
        ) {
            updateClientSort(command, hit, key, newestFirst, collation, onProgress, cancelled)
        } else {
            rebuildClientSort(command, key, newestFirst, collation, onProgress, cancelled)
        }
        return order.uids.asList()
    }

    override suspend fun clientThread(key: SortKey): ThreadNode {
        if (key != SortKey.ThreadReferences && key != SortKey.ThreadOrderedSubject) {
            throw MailFailure("use thread")
        }
        val command = "CLIENT THREAD ${key.name}"
        val mailbox = selectedMailbox ?: throw MailFailure("thread failed")
        val cached = cache[command]
        if (cached != null && sameWireMailbox(cached.mailbox, mailbox) && cached.uidValidity != selected.uidValidity) {
            toastRebuilt(mailbox)
            cache.remove(command)
            threadHeaders.remove(command)
        }
        val hit = cache[command]
        val headers = threadHeaders[command]
        val order = if (
            hit != null && headers != null && sameWireMailbox(hit.mailbox, mailbox) &&
            hit.uidValidity == selected.uidValidity
        ) {
            updateClientThread(command, headers, key)
        } else {
            rebuildClientThread(command, key)
        }
        return messageOrderThread(order)
    }

    private suspend fun rebuildClientSort(
        command: String,
        key: SortKey,
        newestFirst: Boolean,
        collation: ClientSortCollation,
        onProgress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
    ): MessageOrder {
        val before = selected.uidValidity
        val built = walkClientSort(key, newestFirst, collation, onProgress, cancelled)
        return finishClientWalk(command, before, built) {
            walkClientSort(key, newestFirst, collation, onProgress, cancelled)
        }
    }

    private suspend fun updateClientSort(
        command: String,
        cached: MessageOrder,
        key: SortKey,
        newestFirst: Boolean,
        collation: ClientSortCollation,
        onProgress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
    ): MessageOrder {
        if (cancelled()) throw MailFailure("sort cancelled")
        val before = selected.uidValidity
        val all = searchCapturedUids()
        val now = all.toHashSet()
        var order = dropMissing(cached, now, key)
        val have = order.uids.toHashSet()
        val fresh = LongArray(all.count { it !in have })
        var n = 0
        for (uid in all) if (uid !in have) fresh[n++] = uid
        if (fresh.isNotEmpty()) {
            val pieces = fetchSortPieces(fresh, key, onProgress, cancelled, all.size, order.uids.size)
            order = insertPieces(order, pieces, key, newestFirst, collation)
        }
        onProgress(all.size, all.size)
        return finishClientWalk(command, before, order) {
            walkClientSort(key, newestFirst, collation, onProgress, cancelled)
        }
    }

    private suspend fun finishClientWalk(command: String, before: Long, built: MessageOrder, again: suspend () -> MessageOrder): MessageOrder {
        storeClient(command, built)
        if (!sequencesStale) return built
        val mailbox = selectedMailbox ?: return built
        keeper.read("select") { selectNow(mailbox) }
        if (selected.uidValidity == before && cache[command] != null) return built
        val next = again()
        storeClient(command, next)
        return next
    }

    private suspend fun walkClientSort(
        key: SortKey,
        newestFirst: Boolean,
        collation: ClientSortCollation,
        onProgress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
    ): MessageOrder {
        if (cancelled()) throw MailFailure("sort cancelled")
        val all = searchCapturedUids()
        val pieces = fetchSortPieces(all, key, onProgress, cancelled, all.size, 0)
        return orderFromPieces(pieces, key, newestFirst, collation)
    }

    private suspend fun searchCapturedUids(): LongArray = keeper.read("client-sort") {
        nativeSearchUids(requireHandle()) ?: throw MailFailure("sort failed")
    }

    private suspend fun fetchSortPieces(
        uids: LongArray,
        key: SortKey,
        onProgress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
        total: Int,
        doneBase: Int,
    ): List<SortPiece> {
        val code = sortFieldCode(key)
        val out = ArrayList<SortPiece>()
        var at = 0
        while (at < uids.size) {
            if (cancelled()) throw MailFailure("sort cancelled")
            val end = minOf(uids.size, at + ClientSortChunk)
            val slice = uids.copyOfRange(at, end)
            val fetched = keeper.read("client-sort") {
                nativeFetchSortChunk(requireHandle(), slice, code) ?: throw MailFailure("sort failed")
            }
            for (i in fetched.uids.indices) out.add(sortPiece(key, fetched, i))
            at = end
            onProgress(doneBase + at, total)
        }
        return out
    }

    private fun sortPiece(key: SortKey, row: ClientFetch, i: Int): SortPiece {
        val uid = row.uids[i]
        return when (key) {
            SortKey.Date -> {
                val epoch = dateHeaderEpoch(row.texts[i])
                SortPiece(uid, epoch ?: 0L, epoch == null, "")
            }
            SortKey.Size -> SortPiece(uid, row.numbers[i], row.blanks[i], "")
            SortKey.Subject -> SortPiece(uid, 0L, false, baseSubject(decodeHeaderWords(row.texts[i])))
            SortKey.From, SortKey.To, SortKey.Cc -> SortPiece(
                uid,
                0L,
                false,
                firstAddrMailbox(row.texts[i]) { decodeHeaderWords(it) },
            )
            else -> throw MailFailure("use an arrival IndexMode")
        }
    }

    private fun orderFromPieces(
        pieces: List<SortPiece>,
        key: SortKey,
        newestFirst: Boolean,
        collation: ClientSortCollation,
    ): MessageOrder {
        val numeric = key == SortKey.Date || key == SortKey.Size
        val n = pieces.size
        if (n == 0) return orderOf(LongArray(0))
        val uids = LongArray(n) { pieces[it].uid }
        val numbers = LongArray(n) { if (pieces[it].blank) Long.MIN_VALUE else pieces[it].number }
        val blanks = BooleanArray(n) { pieces[it].blank }
        val packed = if (numeric) {
            Triple(ByteArray(0), IntArray(n), IntArray(n))
        } else {
            textColumns(Array(n) { pieces[it].text }, collation)
        }
        val ordered = nativeSortClient(uids, numbers, blanks, packed.first, packed.second, packed.third, numeric, newestFirst)
            ?: throw MailFailure("sort failed")
        val at = HashMap<Long, Int>(n * 2)
        for (i in pieces.indices) at[pieces[i].uid] = i
        val perm = IntArray(ordered.size) { at.getValue(ordered[it]) }
        return messageOrderFromPerm(pieces, perm, key, if (numeric) null else packed)
    }

    private fun messageOrderFromPerm(
        pieces: List<SortPiece>,
        perm: IntArray,
        key: SortKey,
        packed: Triple<ByteArray, IntArray, IntArray>?,
    ): MessageOrder {
        val n = perm.size
        val uids = LongArray(n) { pieces[perm[it]].uid }
        val dateEpoch = if (key == SortKey.Date) {
            LongArray(n) { val p = pieces[perm[it]]; if (p.blank) Long.MIN_VALUE else p.number }
        } else {
            LongArray(0)
        }
        val sizes = if (key == SortKey.Size) {
            LongArray(n) { val p = pieces[perm[it]]; if (p.blank) Long.MIN_VALUE else p.number }
        } else {
            LongArray(0)
        }
        val text = if (packed == null) Triple(ByteArray(0), IntArray(0), IntArray(0)) else permuteText(packed, perm)
        return MessageOrder(
            uids,
            ShortArray(0),
            selectedMailbox ?: "",
            ownerId.orEmpty(),
            selected.uidValidity,
            dateEpoch,
            sizes,
            text.first,
            text.second,
            text.third,
        )
    }

    private fun insertPieces(
        order: MessageOrder,
        pieces: List<SortPiece>,
        key: SortKey,
        newestFirst: Boolean,
        collation: ClientSortCollation,
    ): MessageOrder {
        if (pieces.isEmpty()) return order
        val fresh = orderFromPieces(pieces, key, newestFirst, collation)
        return mergeSortedOrders(order, fresh, key, newestFirst)
    }

    /** One native sort of [fresh], then a merge with [existing]. Same order as sorting both together. */
    private fun mergeSortedOrders(
        existing: MessageOrder,
        fresh: MessageOrder,
        key: SortKey,
        newestFirst: Boolean,
    ): MessageOrder {
        val numeric = key == SortKey.Date || key == SortKey.Size
        val existN = existing.uids.size
        fun blankAt(pos: Int): Boolean {
            if (!numeric) return false
            val src = if (pos < existN) existing else fresh
            val i = if (pos < existN) pos else pos - existN
            val values = if (key == SortKey.Date) src.dateEpoch else src.size
            return i < values.size && values[i] == Long.MIN_VALUE
        }
        fun numberAt(pos: Int): Long {
            val src = if (pos < existN) existing else fresh
            val i = if (pos < existN) pos else pos - existN
            val values = if (key == SortKey.Date) src.dateEpoch else src.size
            return if (i < values.size) values[i] else Long.MIN_VALUE
        }
        fun uidAt(pos: Int): Long {
            val src = if (pos < existN) existing else fresh
            val i = if (pos < existN) pos else pos - existN
            return src.uids[i]
        }
        fun cmpAsc(a: Int, b: Int): Int {
            if (numeric) {
                val av = numberAt(a)
                val bv = numberAt(b)
                if (av != bv) return if (av < bv) -1 else 1
            } else {
                val c = cmpKeyBytes(existing, fresh, existN, a, b)
                if (c != 0) return c
            }
            val ua = uidAt(a)
            val ub = uidAt(b)
            if (ua != ub) return if (ua < ub) -1 else 1
            return 0
        }
        fun split(src: MessageOrder, base: Int): Pair<IntArray, IntArray> {
            var filledN = 0
            var blankN = 0
            for (i in src.uids.indices) if (blankAt(base + i)) blankN++ else filledN++
            val filled = IntArray(filledN)
            val blanks = IntArray(blankN)
            var f = 0
            var b = 0
            for (i in src.uids.indices) {
                val pos = base + i
                if (blankAt(pos)) blanks[b++] = pos else filled[f++] = pos
            }
            return filled to blanks
        }
        fun mergeFilled(a: IntArray, b: IntArray): IntArray {
            val out = IntArray(a.size + b.size)
            var i = 0
            var j = 0
            var k = 0
            while (i < a.size && j < b.size) {
                val c = cmpAsc(a[i], b[j])
                val takeA = if (newestFirst) c > 0 else c <= 0
                if (takeA) out[k++] = a[i++] else out[k++] = b[j++]
            }
            while (i < a.size) out[k++] = a[i++]
            while (j < b.size) out[k++] = b[j++]
            return out
        }
        fun mergeBlanks(a: IntArray, b: IntArray): IntArray {
            val out = IntArray(a.size + b.size)
            var i = 0
            var j = 0
            var k = 0
            while (i < a.size && j < b.size) {
                if (uidAt(a[i]) <= uidAt(b[j])) out[k++] = a[i++] else out[k++] = b[j++]
            }
            while (i < a.size) out[k++] = a[i++]
            while (j < b.size) out[k++] = b[j++]
            return out
        }
        val (filledA, blankA) = split(existing, 0)
        val (filledB, blankB) = split(fresh, existN)
        val filled = mergeFilled(filledA, filledB)
        val blanks = mergeBlanks(blankA, blankB)
        val merged = IntArray(filled.size + blanks.size)
        filled.copyInto(merged, 0)
        blanks.copyInto(merged, filled.size)
        return messageOrderFromPositions(existing, fresh, existN, merged, key)
    }

    private fun cmpKeyBytes(existing: MessageOrder, fresh: MessageOrder, existN: Int, a: Int, b: Int): Int {
        fun span(pos: Int): Triple<ByteArray, Int, Int> {
            val src = if (pos < existN) existing else fresh
            val i = if (pos < existN) pos else pos - existN
            if (i >= src.textOff.size || i >= src.textLen.size) return Triple(ByteArray(0), 0, 0)
            return Triple(src.textKey, src.textOff[i], src.textLen[i])
        }
        val (ab, ao, al) = span(a)
        val (bb, bo, bl) = span(b)
        val n = minOf(al, bl)
        for (k in 0 until n) {
            val av = ab[ao + k].toInt() and 0xFF
            val bv = bb[bo + k].toInt() and 0xFF
            if (av != bv) return av - bv
        }
        return al - bl
    }

    private fun messageOrderFromPositions(
        existing: MessageOrder,
        fresh: MessageOrder,
        existN: Int,
        positions: IntArray,
        key: SortKey,
    ): MessageOrder {
        fun srcAt(pos: Int): Pair<MessageOrder, Int> =
            if (pos < existN) existing to pos else fresh to (pos - existN)
        val n = positions.size
        val uids = LongArray(n) { srcAt(positions[it]).let { (src, i) -> src.uids[i] } }
        val dateEpoch = if (key == SortKey.Date) {
            LongArray(n) {
                val (src, i) = srcAt(positions[it])
                if (i < src.dateEpoch.size) src.dateEpoch[i] else Long.MIN_VALUE
            }
        } else {
            LongArray(0)
        }
        val sizes = if (key == SortKey.Size) {
            LongArray(n) {
                val (src, i) = srcAt(positions[it])
                if (i < src.size.size) src.size[i] else Long.MIN_VALUE
            }
        } else {
            LongArray(0)
        }
        val text = if (key == SortKey.Date || key == SortKey.Size) {
            Triple(ByteArray(0), IntArray(0), IntArray(0))
        } else {
            packKeyBytes(Array(n) { at ->
                val (src, i) = srcAt(positions[at])
                if (i >= src.textOff.size || i >= src.textLen.size) {
                    ByteArray(0)
                } else {
                    src.textKey.copyOfRange(src.textOff[i], src.textOff[i] + src.textLen[i])
                }
            })
        }
        return MessageOrder(
            uids, ShortArray(0), existing.mailbox, existing.accountId, existing.uidValidity,
            dateEpoch, sizes, text.first, text.second, text.third,
        )
    }

    private fun dropMissing(order: MessageOrder, keep: Set<Long>, key: SortKey): MessageOrder {
        if (order.uids.all { it in keep }) return order
        val idx = ArrayList<Int>()
        for (i in order.uids.indices) if (order.uids[i] in keep) idx.add(i)
        return takeIndexes(order, idx, key)
    }

    private fun takeIndexes(order: MessageOrder, idx: List<Int>, key: SortKey): MessageOrder {
        val uids = LongArray(idx.size) { order.uids[idx[it]] }
        val dateEpoch = if (key == SortKey.Date && order.dateEpoch.size == order.uids.size) {
            LongArray(idx.size) { order.dateEpoch[idx[it]] }
        } else {
            LongArray(0)
        }
        val sizes = if (key == SortKey.Size && order.size.size == order.uids.size) {
            LongArray(idx.size) { order.size[idx[it]] }
        } else {
            LongArray(0)
        }
        val text = if (key != SortKey.Date && key != SortKey.Size && order.textOff.size == order.uids.size) {
            packKeyBytes(Array(idx.size) { i ->
                val at = idx[i]
                order.textKey.copyOfRange(order.textOff[at], order.textOff[at] + order.textLen[at])
            })
        } else {
            Triple(ByteArray(0), IntArray(0), IntArray(0))
        }
        return MessageOrder(
            uids, ShortArray(0), order.mailbox, order.accountId, order.uidValidity, dateEpoch, sizes,
            text.first, text.second, text.third,
        )
    }

    private fun textColumns(texts: Array<String>, collation: ClientSortCollation): Triple<ByteArray, IntArray, IntArray> =
        if (collation == ClientSortCollation.Plain) packKeyBytes(Array(texts.size) { plainKeyBytes(texts[it]) })
        else localeKeyBytes(texts)

    private fun permuteText(packed: Triple<ByteArray, IntArray, IntArray>, perm: IntArray): Triple<ByteArray, IntArray, IntArray> {
        val (bytes, off, len) = packed
        return packKeyBytes(Array(perm.size) { i ->
            bytes.copyOfRange(off[perm[i]], off[perm[i]] + len[perm[i]])
        })
    }

    private fun sortFieldCode(key: SortKey): Int = when (key) {
        SortKey.Date -> 0
        SortKey.Size -> 1
        SortKey.Subject -> 2
        SortKey.From -> 3
        SortKey.To -> 4
        SortKey.Cc -> 5
        else -> throw MailFailure("use an arrival IndexMode")
    }

    private suspend fun rebuildClientThread(command: String, key: SortKey): MessageOrder {
        val before = selected.uidValidity
        val headers = fetchAllThreadHeaders()
        val built = threadOrder(key, headers)
        val order = finishClientWalk(command, before, built) {
            val again = fetchAllThreadHeaders()
            threadHeaders[command] = again
            threadOrder(key, again)
        }
        threadHeaders.putIfAbsent(command, headers)
        return order
    }

    private suspend fun updateClientThread(command: String, headers: ClientHeaders, key: SortKey): MessageOrder {
        val before = selected.uidValidity
        val all = searchCapturedUids()
        val now = all.toHashSet()
        val kept = filterHeaders(headers, now)
        val have = kept.uids.toHashSet()
        if (kept.uids.size == headers.uids.size && all.all { it in have }) {
            val hit = cache[command]
            if (hit != null) return hit
        }
        val fresh = LongArray(all.count { it !in have })
        var n = 0
        for (uid in all) if (uid !in have) fresh[n++] = uid
        val merged = if (fresh.isEmpty()) kept else mergeHeaders(kept, fetchThreadHeaders(fresh))
        val built = threadOrder(key, merged)
        threadHeaders[command] = merged
        return finishClientWalk(command, before, built) {
            val again = fetchAllThreadHeaders()
            threadHeaders[command] = again
            threadOrder(key, again)
        }
    }

    private suspend fun fetchAllThreadHeaders(): ClientHeaders {
        val all = searchCapturedUids()
        return fetchThreadHeaders(all)
    }

    private suspend fun fetchThreadHeaders(uids: LongArray): ClientHeaders {
        var acc = ClientHeaders(LongArray(0), emptyArray(), emptyArray(), emptyArray(), emptyArray(), emptyArray())
        var at = 0
        while (at < uids.size) {
            val end = minOf(uids.size, at + ClientSortChunk)
            val slice = uids.copyOfRange(at, end)
            val rows = keeper.read("client-thread") {
                nativeFetchThreadChunk(requireHandle(), slice) ?: throw MailFailure("thread failed")
            }
            acc = mergeHeaders(acc, rows)
            at = end
        }
        return acc
    }

    private fun threadOrder(key: SortKey, headers: ClientHeaders): MessageOrder {
        val flat = if (key == SortKey.ThreadOrderedSubject) {
            val subjects = Array(headers.uids.size) { baseSubject(decodeHeaderWords(headers.subject[it])) }
            val epochs = LongArray(headers.uids.size) { dateHeaderEpoch(headers.date[it]) ?: Long.MIN_VALUE }
            nativeThreadSubject(headers.uids, subjects, epochs) ?: throw MailFailure("thread failed")
        } else {
            nativeThreadReferences(headers.uids, headers.messageId, headers.references, headers.inReplyTo)
                ?: throw MailFailure("thread failed")
        }
        return orderOf(flat.uids, flat.depths)
    }

    private fun filterHeaders(rows: ClientHeaders, keep: Set<Long>): ClientHeaders {
        val idx = rows.uids.indices.filter { rows.uids[it] in keep }
        return ClientHeaders(
            LongArray(idx.size) { rows.uids[idx[it]] },
            Array(idx.size) { rows.messageId[idx[it]] },
            Array(idx.size) { rows.references[idx[it]] },
            Array(idx.size) { rows.inReplyTo[idx[it]] },
            Array(idx.size) { rows.subject[idx[it]] },
            Array(idx.size) { rows.date[idx[it]] },
        )
    }

    private fun mergeHeaders(a: ClientHeaders, b: ClientHeaders): ClientHeaders {
        val n = a.uids.size + b.uids.size
        fun pick(i: Int, left: Array<String>, right: Array<String>) = if (i < a.uids.size) left[i] else right[i - a.uids.size]
        return ClientHeaders(
            LongArray(n) { if (it < a.uids.size) a.uids[it] else b.uids[it - a.uids.size] },
            Array(n) { pick(it, a.messageId, b.messageId) },
            Array(n) { pick(it, a.references, b.references) },
            Array(n) { pick(it, a.inReplyTo, b.inReplyTo) },
            Array(n) { pick(it, a.subject, b.subject) },
            Array(n) { pick(it, a.date, b.date) },
        )
    }

    private data class SortPiece(val uid: Long, val number: Long, val blank: Boolean, val text: String)

    /**
     * Extra IDLE: the watch connection is the only login (connect, log in, EXAMINE, IDLE). The command
     * connection is never opened.
     */
    override suspend fun watchOnly(account: AccountSettings, mailbox: String, onChange: (MailboxChange) -> Unit) {
        stageCommand = true
        val opened = try {
            open(account)
        } finally {
            stageCommand = false
        }
        when (opened) {
            OpenResult.Connected -> {
                try {
                    val line = nativeWatchLogin(requireHandle()) ?: throw MailFailure("")
                    capabilityLine = line
                    if (capabilityGate(line) is OpenResult.Rejected) {
                        close()
                        throw MailFailure("watch rejected")
                    }
                    if (!featureCaps.idle) {
                        close()
                        return
                    }
                    nativeSetWatchWakeMillis((account.idleWakeSeconds) * 1000)
                    nativeWatch(requireHandle(), wire(mailbox), WatchOffer(mailbox, onChange))
                    noteTraffic("Idling")
                } catch (failure: Throwable) {
                    close()
                    throw failure
                }
            }
            is OpenResult.Failed -> throw MailFailure(opened.text)
            is OpenResult.Rejected -> throw MailFailure("watch rejected")
        }
    }

    override suspend fun watch(mailbox: String, onChange: (MailboxChange) -> Unit) {
        if (!featureCaps.idle) return
        // F136: not through the keeper. The watch is its own connection and runs beside the main one (off the
        // session lane); a reconnect of the main connection from here would race the lane's commands.
        // The callback is stored only for the generation nativeWatch publishes, not before it returns.
        nativeSetWatchWakeMillis((account?.idleWakeSeconds ?: 5) * 1000)
        val offer = WatchOffer(mailbox, onChange)
        requestedWatch = WatchTarget(mailbox, onChange)
        val started = nativeWatch(requireHandle(), wire(mailbox), offer)
        if (started == 0L) {
            keeper.markWatchLost()
            val cur = publishedWatch.get()
            if (cur != null && cur.offer === offer) clearPublishedWatch(cur.generation)
            return
        }
        noteTraffic("Idling")
    }

    fun publishWatch(generation: Long, offer: Any?) {
        val pending = offer as? WatchOffer ?: return
        if (generation == 0L) return
        val next = PublishedWatch(generation, pending)
        while (true) {
            val cur = publishedWatch.get()
            if (cur != null && generation < cur.generation) return
            if (!publishedWatch.compareAndSet(cur, next)) continue
            watchMailbox = pending.mailbox
            watchCallback = pending.onChange
            val now = publishedWatch.get()
            if (now !== next) {
                val kept = now?.offer
                if (kept == null) {
                    watchCallback = null
                    watchMailbox = null
                } else {
                    watchMailbox = kept.mailbox
                    watchCallback = kept.onChange
                }
            }
            return
        }
    }

    override suspend fun stopWatch() {
        val h = handle
        val stopped = if (h != 0L) nativeStopWatch(h) else 0L
        clearPublishedWatch(stopped)
        requestedWatch = null
    }

    private fun clearPublishedWatch(stoppedGeneration: Long) {
        if (stoppedGeneration == 0L) return
        val floor = PublishedWatch(stoppedGeneration, null)
        while (true) {
            val cur = publishedWatch.get()
            if (cur != null && cur.generation > stoppedGeneration) return
            if (!publishedWatch.compareAndSet(cur, floor)) continue
            watchCallback = null
            watchMailbox = null
            val kept = publishedWatch.get()?.offer
            if (kept != null) {
                watchMailbox = kept.mailbox
                watchCallback = kept.onChange
            }
            return
        }
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
        val spilled = spilledMessage(rfc822)
        try {
            keeper.write("append") {
                forgetSelected()
                writableForAppend(wire(mailbox))
                nativeAppend(requireHandle(), wire(mailbox), spilled.absolutePath, requireFlags(flags))
            }
        } finally {
            spilled.delete()
        }
    }

    override suspend fun appendReturningUid(mailbox: String, rfc822: ByteArray, flags: Set<String>): Long {
        val spilled = spilledMessage(rfc822)
        try {
            return keeper.write("append") {
                forgetSelected()
                writableForAppend(wire(mailbox))
                nativeAppend(requireHandle(), wire(mailbox), spilled.absolutePath, requireFlags(flags))
            }
        } finally {
            spilled.delete()
        }
    }

    override suspend fun appendFile(mailbox: String, file: File, flags: Set<String>) {
        keeper.write("append") {
            forgetSelected()
            val name = wire(mailbox)
            writableForAppend(name)
            val handle = requireHandle()
            nativeAppend(handle, name, file.absolutePath, requireFlags(flags))
        }
    }

    override suspend fun appendReturningUidFile(mailbox: String, file: File, flags: Set<String>): Long =
        keeper.write("append") {
            forgetSelected()
            val name = wire(mailbox)
            writableForAppend(name)
            val handle = requireHandle()
            nativeAppend(handle, name, file.absolutePath, requireFlags(flags))
        }

    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>, dsnNotify: Int): Int =
        smtpSendFrom(rfc822, recipients, dsnNotify, "")

    override suspend fun smtpSendFrom(rfc822: ByteArray, recipients: List<String>, dsnNotify: Int, envelopeFrom: String): Int {
        val spilled = spilledMessage(rfc822)
        try {
            return sendMessageFile(spilled, recipients, dsnNotify, envelopeFrom)
        } finally {
            spilled.delete()
        }
    }

    override suspend fun smtpSendFile(file: File, recipients: List<String>, dsnNotify: Int): Int =
        smtpSendFromFile(file, recipients, dsnNotify, "")

    override suspend fun smtpSendFromFile(file: File, recipients: List<String>, dsnNotify: Int, envelopeFrom: String): Int =
        sendMessageFile(file, recipients, dsnNotify, envelopeFrom)

    private suspend fun sendMessageFile(file: File, recipients: List<String>, dsnNotify: Int, envelopeFrom: String): Int {
        val key = refusedSecurityKey ?: account?.preservedSecurityKey()
        if (key != null) throw MailFailure("account settings need attention: $key")
        val context = currentApplication()
        val ownSmtpPassword = credentials?.smtpPassword
            ?: if (context != null && ownerId != null) ownStore(context).smtpPassword() else ""
        // Same-as-IMAP uses the IMAP password. Auto sends that, or no AUTH when it is empty.
        // On sends no password. Off sends the resolved password. A missing IMAP password is needs-password.
        val acct = account
        val imapPassword = loggedInPassword.orEmpty()
        val same = acct?.smtpSameAsImap == true
        val mode = acct?.smtpNoAuth ?: SmtpNoAuth.Auto
        if (same && imapPassword.isEmpty() && mode != SmtpNoAuth.On) throw MailFailure("needs-password")
        val smtpPassword = smtpPasswordFor(same, ownSmtpPassword, imapPassword, mode)
        if (mode == SmtpNoAuth.Off && smtpPassword.isEmpty()) throw MailFailure("needs-password")
        val smtpUser = (account?.smtpUsername ?: "").ifEmpty { acct?.username.orEmpty() }
        return keeper.write("send") {
            forgetSelected()
            nativeSmtp(
                requireHandle(),
                file.absolutePath,
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
        val key = refusedSecurityKey ?: account?.preservedSecurityKey()
        if (key != null) throw MailFailure("account settings need attention: $key")
        val probe = smtpProbe
        val acct = account ?: probe?.settings ?: credentials?.settings
            ?: throw MailFailure("not connected")
        val preserved = acct.preservedSecurityKey()
        if (preserved != null) throw MailFailure("FAIL account settings need attention: $preserved")
        val imapPassword = probe?.password ?: loggedInPassword ?: credentials?.password.orEmpty()
        val ownSmtpPassword = probe?.smtpPassword ?: credentials?.smtpPassword ?: ""
        if (acct.smtpSameAsImap && imapPassword.isEmpty() && acct.smtpNoAuth != SmtpNoAuth.On) {
            throw MailFailure("needs-password")
        }
        val smtpPassword = smtpPasswordFor(acct.smtpSameAsImap, ownSmtpPassword, imapPassword, acct.smtpNoAuth)
        if (acct.smtpNoAuth == SmtpNoAuth.Off && smtpPassword.isEmpty()) throw MailFailure("needs-password")
        val smtpUser = acct.smtpUsername.ifEmpty { acct.username }
        // A staged session has no IMAP handle and does not dial imapHost.
        val staged = stageSmtp(acct, imapPassword)
        if (staged.handle == 0L) throw MailFailure(staged.error)
        try {
            val code = nativeSmtp(staged.handle, null, emptyArray(), smtpUser, smtpPassword, 0, "")
            return when (code) {
                2 -> org.dlang.liveimap.session.SmtpLoginCheck.LoggedIn
                3 -> org.dlang.liveimap.session.SmtpLoginCheck.NoAuthOffered
                else -> org.dlang.liveimap.session.SmtpLoginCheck.NoPassword
            }
        } finally {
            nativeClose(staged.handle)
        }
    }

    private fun stageSmtp(account: AccountSettings, password: String): SessionOpen {
        val from = if (account.email.isNotEmpty()) account.email else account.username
        val trafficPath = prepareTraffic(account)
        val clientCert = ClientCertStore.load(account.clientCert)
        val keyChain = if (clientCert == null) KeyChainCerts.load(account.clientCertAlias) else null
        return nativeStage(
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
            account.smtpTlsMode.name,
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
        publishedWatch.set(null)
        watchCallback = null
        watchMailbox = null
        requestedWatch = null
        clearOrderCache()
        keeper.onSessionClosed()
    }

    fun onNativeWatch(generation: Long, kind: Int, exists: Int, uid: Long, flags: Array<String>?) {
        val target = publishedWatch.get() ?: return
        val pending = target.offer ?: return
        if (target.generation != generation) return
        val mailbox = pending.mailbox
        val callback = pending.onChange
        val change: MailboxChange = when (kind) {
            0 -> MailboxChange.Exists(exists)
            1 -> MailboxChange.Expunge(exists)
            2 -> MailboxChange.Flags(uid, flags?.toSet() ?: emptySet())
            5 -> MailboxChange.ShownFlags
            4 -> {
                keeper.markWatchLost()
                MailboxChange.WatchLost
            }
            else -> MailboxChange.UidValidityReset
        }
        dropCached(mailbox, keepClient = true)
        sequencesStale = true
        callback.invoke(change)
    }

    private inner class SessionLink : Link {
        override fun dead(): Boolean {
            val h = handle
            if (h == 0L) return true
            return nativeSessionDead(h)
        }

        override fun connect() {
            val saved = account ?: throw MailFailure("not connected")
            val opened = login(saved, sessionPassword(saved))
            if (opened !is OpenResult.Connected) {
                val text = when (opened) {
                    is OpenResult.Failed -> opened.text
                    is OpenResult.Rejected -> opened.capabilities
                    OpenResult.Connected -> "connection failed"
                }
                throw MailFailure(text)
            }
            clearOrderCache()
            sequencesStale = true
        }

        override fun noop() {
            nativeNoop(requireHandle())
        }

        override fun reselect(): SelectResult? {
            val mailbox = selectedMailbox ?: return null
            val name = wire(mailbox)
            val readWrite = selectedReadWrite
            val result = nativeSelect(requireHandle(), name, readWrite) ?: throw MailFailure("select failed")
            noteValidity(name, result.uidValidity)
            selectedMailbox = name
            selectedReadWrite = readWrite
            selected = result
            clearOrderCache()
            sequencesStale = true
            noteTraffic("Selected $name")
            return result
        }

        override fun rewatch() {
            val publishedOffer = publishedWatch.get()?.offer
            val published = publishedOffer?.let { WatchTarget(it.mailbox, it.onChange) }
            var offer: WatchOffer? = null
            val running = runRewatch(
                published,
                requestedWatch,
                start = { target ->
                    requestedWatch = WatchTarget(target.mailbox, target.onChange)
                    val next = WatchOffer(target.mailbox, target.onChange)
                    offer = next
                    nativeSetWatchWakeMillis((account?.idleWakeSeconds ?: 5) * 1000)
                    nativeWatch(requireHandle(), wire(next.mailbox), next)
                },
                markLost = { keeper.markWatchLost() },
                clearOffer = {
                    val cur = publishedWatch.get()
                    if (cur != null && cur.offer === offer) clearPublishedWatch(cur.generation)
                },
            )
            if (!running) return
            noteTraffic("Idling")
        }

        override fun close() {
            closeLiveSession()
        }

        override fun emit(change: MailboxChange) {
            val mailbox = selectedMailbox
            if (mailbox != null) dropCached(mailbox, keepClient = true)
            if (change !is MailboxChange.Flags && change !is MailboxChange.ShownFlags) {
                sequencesStale = true
            }
            // F144: these changes describe the main connection's selected folder; the index's watcher gets them
            // only when that is the folder it watches, never another folder's counts or epoch.
            // One publishedWatch snapshot, same as onNativeWatch and rewatch. The two volatiles can cross.
            val pending = publishedWatch.get()?.offer
            if (pending != null && reconnectChangeReachesWatcher(selectedMailbox, pending.mailbox)) {
                pending.onChange.invoke(change)
            }
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

    /** Drops remembered server results for the selected mailbox. A client order stays so the next walk can update it. */
    private fun forgetSelected() {
        val mailbox = selectedMailbox ?: return
        dropCached(mailbox, keepClient = true)
    }

    private fun clearOrderCache() {
        cache.clear()
        threadHeaders.clear()
    }

    /** A folder change, or a server reply that must be fetched again. Client orders stay when [keepClient] is set. */
    private fun dropCached(mailbox: String, keepClient: Boolean) {
        val drop = ArrayList<String>()
        for ((command, order) in cache) {
            if (!sameWireMailbox(order.mailbox, mailbox)) continue
            if (keepClient && command.startsWith("CLIENT ")) continue
            drop.add(command)
        }
        for (command in drop) {
            cache.remove(command)
            threadHeaders.remove(command)
        }
    }

    /** UIDVALIDITY moved: the client order is rebuilt, and the folder name is toasted. */
    private fun noteValidity(mailbox: String, validity: Long) {
        var stale = false
        for ((command, order) in cache) {
            if (!command.startsWith("CLIENT ")) continue
            if (sameWireMailbox(order.mailbox, mailbox) && order.uidValidity != validity) stale = true
        }
        if (!stale) return
        toastRebuilt(mailbox)
        clearOrderCache()
    }

    private fun toastRebuilt(mailbox: String) {
        val app = currentApplication() ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(app, "$mailbox order was rebuilt", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun storeClient(command: String, order: MessageOrder) {
        cache.compute(command) { _, _ -> order }
    }

    private fun orderOf(uids: LongArray, depths: ShortArray = ShortArray(0)): MessageOrder = MessageOrder(
        uids,
        depths,
        selectedMailbox ?: "",
        ownerId.orEmpty(),
        selected.uidValidity,
        LongArray(0),
        LongArray(0),
        ByteArray(0),
        IntArray(0),
        IntArray(0),
    )

    private fun remember(command: String, load: () -> MessageOrder): MessageOrder {
        val mailbox = selectedMailbox ?: ""
        val validity = selected.uidValidity
        val found = cache[command]
        if (found != null && sameWireMailbox(found.mailbox, mailbox) && found.uidValidity == validity) return found
        val value = load()
        cache.compute(command) { _, _ -> value }
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

    private external fun nativeStage(
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
        smtpTlsMode: String,
        certPin: String,
        allowPlaintextAuth: Boolean,
        acceptInvalidCert: Boolean,
        allowOldTls: Boolean,
        authMechanism: String,
        clientP12: ByteArray?,
        clientP12Password: String,
        keyChainChain: Array<ByteArray>?,
        keyChainKey: Any?,
    ): SessionOpen

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
        smtpTlsMode: String,
        certPin: String,
        allowPlaintextAuth: Boolean,
        acceptInvalidCert: Boolean,
        allowOldTls: Boolean,
        authMechanism: String,
        clientP12: ByteArray?,
        clientP12Password: String,
        keyChainChain: Array<ByteArray>?,
        keyChainKey: Any?,
    ): SessionOpen

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
    private external fun nativeCopyThenDelete(handle: Long, uids: LongArray, target: String, moveKind: String): CopyMoveResult?
    private external fun nativeCopyAllThenDelete(handle: Long, target: String, moveKind: String): CopyMoveResult?
    private external fun nativeAppend(handle: Long, mailbox: String, message: String, flags: Array<String>): Long
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
    private external fun nativeSearchUids(handle: Long): LongArray?
    private external fun nativeFetchSortChunk(handle: Long, uids: LongArray, field: Int): ClientFetch?
    private external fun nativeFetchThreadChunk(handle: Long, uids: LongArray): ClientHeaders?
    private external fun nativeSortClient(
        uids: LongArray,
        numbers: LongArray,
        blanks: BooleanArray,
        keyBytes: ByteArray,
        keyOff: IntArray,
        keyLen: IntArray,
        numeric: Boolean,
        newestFirst: Boolean,
    ): LongArray?
    private external fun nativeInsertAt(
        uids: LongArray,
        numbers: LongArray,
        blanks: BooleanArray,
        keyBytes: ByteArray,
        keyOff: IntArray,
        keyLen: IntArray,
        numeric: Boolean,
        newestFirst: Boolean,
        newUid: Long,
        newNumber: Long,
        newBlank: Boolean,
        newBytes: ByteArray,
    ): Int
    private external fun nativeThreadSubject(uids: LongArray, subjects: Array<String>, epochs: LongArray): ThreadFlat?
    private external fun nativeThreadReferences(
        uids: LongArray,
        messageId: Array<String>,
        references: Array<String>,
        inReplyTo: Array<String>,
    ): ThreadFlat?
    private external fun nativeThread(handle: Long, algorithm: String, criteria: String?): ThreadNode?
    private external fun nativeWatchLogin(handle: Long): String?
    private external fun nativeWatch(handle: Long, mailbox: String, offer: Any): Long
    private external fun nativeStopWatch(handle: Long): Long
    private external fun nativeSmtp(
        handle: Long,
        message: String?,
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

        @Volatile
        internal var smtpProbe: SmtpProbeMaterial? = null

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
    // One account finishing must not turn another account's log off.
}

private fun spilledMessage(bytes: ByteArray): File {
    val dir = currentApplication()?.cacheDir
    val file = if (dir != null) File.createTempFile("liveimap-", ".rfc822", dir)
        else File.createTempFile("liveimap-", ".rfc822")
    try {
        file.writeBytes(bytes)
    } catch (error: Exception) {
        file.delete()
        throw error
    }
    return file
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
/** True when APPEND targets the mailbox that is currently selected read-only (EXAMINE). */
internal fun appendNeedsReadWrite(selectedMailbox: String?, selectedReadWrite: Boolean, target: String): Boolean =
    selectedMailbox == target && !selectedReadWrite

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

/** F141: a failed sort chunk is drift (restart the walk) when the folder shrank or sequence numbers went stale. */
internal fun sortChunkDrift(existsAtStart: Int, existsNow: Int, stale: Boolean): Boolean = stale || existsNow < existsAtStart

/** F30: the ENABLE token for a resync kind. QRESYNC data is not processed, so QRESYNC servers get CONDSTORE. */
internal fun enableForResync(kind: String): String = when (kind) {
    "Qresync", "Condstore" -> "CONDSTORE"
    else -> ""
}

/** Resolved SMTP password. On is always "". Auto is "" when the resolved password is empty. */
internal fun smtpPasswordFor(
    sameAsImap: Boolean,
    smtpPassword: String,
    imapPassword: String,
    smtpNoAuth: SmtpNoAuth = SmtpNoAuth.Auto,
): String {
    if (smtpNoAuth == SmtpNoAuth.On) return ""
    val resolved = if (sameAsImap) imapPassword else smtpPassword
    if (smtpNoAuth == SmtpNoAuth.Auto && resolved.isEmpty()) return ""
    return resolved
}

/** F144: a reconnect's re-SELECT changes go to the watcher only for the very folder it watches. */
internal fun reconnectChangeReachesWatcher(selected: String?, watched: String?): Boolean =
    selected != null && watched != null && sameWireMailbox(selected, watched)

private fun sameWireMailbox(a: String, b: String): Boolean =
    a == b || (a.equals("INBOX", ignoreCase = true) && b.equals("INBOX", ignoreCase = true))
