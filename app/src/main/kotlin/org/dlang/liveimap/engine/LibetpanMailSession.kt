package org.dlang.liveimap.engine

import android.content.Context
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
import org.dlang.liveimap.session.MessageOrder
import org.dlang.liveimap.session.MessageRef
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
import org.dlang.liveimap.session.localeKeyBytes
import org.dlang.liveimap.session.mailboxMarkedTrash
import org.dlang.liveimap.session.messageOrderThread
import org.dlang.liveimap.session.packKeyBytes
import org.dlang.liveimap.session.plainKeyBytes
import org.dlang.liveimap.ui.compose.decodeHeaderPhrases
import org.dlang.liveimap.ui.compose.decodeHeaderWords
import org.dlang.liveimap.session.usableTrashName
import org.dlang.liveimap.session.sameImapIdentity
import org.dlang.liveimap.session.plusFallback
import org.dlang.liveimap.settings.Settings
import org.dlang.liveimap.settings.SettingsHub
import org.dlang.liveimap.settings.Compression
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.MoveMethod
import org.dlang.liveimap.settings.SmtpNoAuth
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.settings.StartRule
import org.dlang.liveimap.settings.moveCommandKind
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
    val settings: Settings,
    val password: String,
    val smtpPassword: String,
)

/** One open or stage: the handle, the TLS line, and the failure, together. */
@androidx.annotation.Keep
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
@androidx.annotation.Keep
internal class CopyMoveResult(
    @JvmField val copiedUids: LongArray,
    @JvmField val targetEpoch: Long,
)

/** Mailbox and callback from the last watch request. A failed start does not drop this. */
internal class WatchTarget(
    val mailbox: String,
    val onChange: (MailboxChange) -> Unit,
    val generation: Long = 0,
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

class LibetpanMailSession(
    private val context: Context,
    private val accountId: String,
) : MailSession {
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

    private var account: Settings? = null
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

    /** One snapshot for the take coroutine and [SessionLink.emit]. */
    private val activeWatch = AtomicReference<WatchTarget?>(null)

    /** Last watch or rewatch target. Survives a failed start so rewatch can start it. */
    @Volatile
    private var requestedWatch: WatchTarget? = null

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val watchJob = AtomicReference<Job?>(null)
    private val watchThread = AtomicReference<Thread?>(null)

    @Volatile
    private var stoppingWatch = false

    /** Close holds this from stop through free so a late watch cannot take a freed session. */
    @Volatile
    private var closingWatch = false

    private val watchGate = Any()

    @Volatile
    private var certConfirmer: (suspend (CertPrompt) -> Boolean)? = null

    /** F64: true once this session logged in by the no-channel-binding retry after a failed SCRAM -PLUS. */
    @Volatile var channelBindingDropped: Boolean = false
        private set

    @Volatile
    private var plaintextConfirmer: (suspend () -> Boolean)? = null

    // Concurrent: the watch take coroutine prunes it while the lane reads it (F25).
    // One MessageOrder per command for the selected folder. No Any cast.
    private val cache = java.util.concurrent.ConcurrentHashMap<String, MessageOrder>()
    private val threadHeaders = java.util.concurrent.ConcurrentHashMap<String, ClientHeaders>()
    private val link = SessionLink()
    private val keeper = ConnectionKeeper(link)
    private var trafficWriter: TrafficWriter? = null
    private var trafficWriterRoll = Long.MIN_VALUE
    private var trafficRefusedLogged = false

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

    /** Set by openWith: the password typed for this probe, not the stored secret. */
    @Volatile
    private var credentials: org.dlang.liveimap.accounts.AccountCredentials? = null

    /** Test accepts a pin into the draft. A real connect (no credentials) still stores it. */
    @Volatile
    private var draftSettings: ((Settings) -> Unit)? = null

    fun setDraftSettings(onDraft: ((Settings) -> Unit)?) {
        draftSettings = onDraft
    }

    override suspend fun openWith(credentials: org.dlang.liveimap.accounts.AccountCredentials): OpenResult {
        this.credentials = credentials
        return open(credentials.settings)
    }

    /** Next [loginOnce] stores credentials and does not open a command connection. */
    @Volatile
    private var stageCommand = false

    private fun ownStore(): DataStoreSettingsStore = DataStoreSettingsStore(context, accountId)

    private fun trafficDirectory(account: Settings): String {
        if (!account.logImapTraffic) return ""
        val dir = TrafficLog.trafficLogDir(context.cacheDir, accountId)
        if (dir == null) {
            if (!trafficRefusedLogged) {
                trafficRefusedLogged = true
                android.util.Log.w("LiveIMAP", "traffic directory refused for $accountId")
            }
            return ""
        }
        return dir.absolutePath
    }

    private fun accountWriter(acct: Settings?): TrafficWriter {
        val roll = if (acct != null && acct.logImapTraffic && acct.trafficLogMaxMb > 0) {
            acct.trafficLogMaxMb.toLong() * 1024L * 1024L
        } else {
            0L
        }
        val held = trafficWriter
        if (held != null && trafficWriterRoll == roll) return held
        val writer = TrafficLog.writer(context.cacheDir, accountId, roll)
        trafficWriter = writer
        trafficWriterRoll = roll
        return writer
    }

    private fun statusNote(acct: Settings, text: String) {
        accountWriter(acct).note("imap", text)
    }

    private fun applyLimits(handle: Long, account: Settings) {
        val roll = if (account.logImapTraffic && account.trafficLogMaxMb > 0) {
            account.trafficLogMaxMb.toLong() * 1024L * 1024L
        } else {
            0L
        }
        keeper.noopAfterMs = account.keepAliveMinutes.toLong() * 60L * 1000L
        nativeSetSessionLimits(
            handle,
            account.connectTimeoutSec,
            account.readTimeoutSec,
            account.idleRenewMinutes * 60,
            account.idleWakeSeconds * 1000,
            account.maxResultUids,
            trafficDirectory(account),
            roll,
        )
    }

    override suspend fun open(account: Settings): OpenResult {
        val key = account.preservedSecurityKey()
        if (key != null) {
            refusedSecurityKey = key
            return OpenResult.Failed("account settings need attention: $key")
        }
        refusedSecurityKey = null
        val password = if (credentials != null) {
            credentials!!.password ?: return OpenResult.Failed("password missing")
        } else {
            try {
                runBlocking { ownStore().password() } ?: return OpenResult.Failed("password missing")
            } catch (error: Exception) {
                return OpenResult.Failed(error.message ?: "keystore unavailable")
            }
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
                    nativeSetSessionFlags(handle, account.pipelineCommands)
                    applyLimits(handle, account)
                    this.account = account
                    return OpenResult.Connected
                }
            }
        }
        if (handle != 0L) {
            close()
        }
        var opened = login(account, password)
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
                if (confirm(prompt)) {
                    val pinned = account.copy(certPin = prompt.fingerprint)
                    trustedAccount = pinned
                    val text = opened.text
                    val probing = credentials
                    try {
                        if (probing == null) {
                            ownStore().update { it.copy(certPin = prompt.fingerprint) }
                        } else {
                            credentials = org.dlang.liveimap.accounts.AccountCredentials(
                                pinned,
                                probing.password,
                                probing.smtpPassword,
                            )
                            draftSettings?.invoke(pinned)
                        }
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
            val accepted = confirm != null && confirm()
            val stopped = context.getString(R.string.plaintext_auth_stopped)
            if (!accepted) {
                opened = OpenResult.Failed(stopped)
            } else {
                val allowed = trustedAccount.copy(allowPlaintextAuth = true)
                val probing = credentials
                try {
                    if (probing == null) {
                        ownStore().update { it.copy(allowPlaintextAuth = true) }
                    } else {
                        credentials = org.dlang.liveimap.accounts.AccountCredentials(
                            allowed,
                            probing.password,
                            probing.smtpPassword,
                        )
                        draftSettings?.invoke(allowed)
                    }
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
    private fun login(account: Settings, knownPassword: String? = null): OpenResult {
        val first = loginOnce(account, knownPassword)
        if (first !is OpenResult.Failed) return first
        val retry = plusFallback(account, first.text) ?: return first
        TrafficRecorder.event("main", "fallback", retry.authMechanism)
        android.util.Log.w("LiveIMAP", "SCRAM -PLUS failed; logged in with ${retry.authMechanism} (no channel binding)")
        val opened = loginOnce(retry, knownPassword)
        if (opened !is OpenResult.Failed) {
            channelBindingDropped = true
            statusNote(retry, context.getString(R.string.session_no_channel_binding, retry.authMechanism))
        }
        return openResultAfterPlusFallback(first, opened)
    }

    private fun sessionPassword(): String {
        credentials?.let { return it.password ?: throw MailFailure("password missing") }
        return runBlocking { ownStore().password() } ?: throw MailFailure("password missing")
    }

    private fun loginOnce(account: Settings, knownPassword: String? = null): OpenResult {
        namespaceList = null
        val password = if (knownPassword != null) {
            knownPassword
        } else if (credentials != null) {
            credentials!!.password ?: return OpenResult.Failed("password missing")
        } else {
            try {
                runBlocking { ownStore().password() } ?: return OpenResult.Failed("password missing")
            } catch (error: Exception) {
                return OpenResult.Failed(error.message ?: "keystore unavailable")
            }
        }
        val from = if (account.email.isNotEmpty()) account.email else account.username
        if (account.logImapTraffic) {
            statusNote(account, "Connecting")
            statusNote(account, "Authenticating")
        }
        TrafficRecorder.event("main", "connect", "${account.imapHost}:${account.imapPort} ${account.tlsMode.name}")
        val keyChain = KeyChainCerts.load(account.clientCertAlias)
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
                account.tlsMode.name,
                account.smtpTlsMode.name,
                account.certPin,
                account.allowPlaintextAuth,
                account.acceptInvalidCert,
                account.allowOldTls,
                account.authMechanism,
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
                account.tlsMode.name,
                account.smtpTlsMode.name,
                account.certPin,
                account.allowPlaintextAuth,
                account.acceptInvalidCert,
                account.allowOldTls,
                account.authMechanism,
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
        if (opened.handle == 0L) {
            val text = opened.error
            if (account.logImapTraffic) statusNote(account, "Error $text")
            TrafficRecorder.event("main", "error", text)
            return OpenResult.Failed(text)
        }
        applyLimits(opened.handle, account)
        if (stageCommand) {
            handle = opened.handle
            this.account = account
            loggedInPassword = password
            capabilityLine = ""
            clearOrderCache()
            compressed = false
            return OpenResult.Connected
        }
        val connected = nativeConnect(opened.handle)
        connected.tls.takeIf { it.isNotEmpty() }?.let { TrafficRecorder.event("main", "tls", it) }
        if (connected.certFingerprint.isNotEmpty()) {
            pendingCertOffer = arrayOf(
                connected.certSubject,
                connected.certIssuer,
                connected.certNotBefore,
                connected.certNotAfter,
                connected.certFingerprint,
            )
        }
        if (connected.handle == 0L) {
            nativeClose(opened.handle)
            val text = connected.error
            if (account.logImapTraffic) statusNote(account, "Error $text")
            TrafficRecorder.event("main", "error", text)
            if (connected.authRejected) return OpenResult.AuthRejected(text)
            return OpenResult.Failed(text)
        }
        val line = nativeCapabilityLine(connected.handle)
        val gate = capabilityGate(line)
        if (gate is OpenResult.Rejected) {
            nativeClose(connected.handle)
            return gate
        }
        handle = connected.handle
        this.account = account
        loggedInPassword = password
        capabilityLine = line
        clearOrderCache()
        compressed = false
        when (featureCaps.resyncKind()) {
            "Qresync", "Condstore" -> nativeEnable(connected.handle, enableForResync(featureCaps.resyncKind()))
        }
        utf8Names = false
        if (featureCaps.utf8Accept) {
            nativeEnable(connected.handle, "UTF8=ACCEPT")
            utf8Names = true
        }
        if (featureCaps.compressDeflate && !account.logImapTraffic && account.compression == Compression.On) {
            nativeCompress(connected.handle)
            compressed = true
        }
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
        // F101: suspend/reconnect is not a user leave, so no CLOSE (it would expunge behind a pending Undo).
        val job = synchronized(watchGate) {
            stoppingWatch = true
            closingWatch = true
            val h = handle
            if (h != 0L) {
                try {
                    nativeStopWatch(h)
                } catch (_: Exception) {
                }
            }
            watchJob.get()
        }
        joinWatchTake(job)
        synchronized(watchGate) {
            closeSockets()
            activeWatch.set(null)
            requestedWatch = null
            closingWatch = false
        }
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
        val list = namespacesOf(rows)
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
        foldersOf(rows)
    }

    override suspend fun mailboxListed(name: String): Boolean = keeper.read("list") {
        nativeMailboxListed(requireHandle(), wire(name))
    }

    override suspend fun statusMessages(mailboxes: List<String>): Map<String, Int> {
        if (mailboxes.isEmpty()) return emptyMap()
        return keeper.read("status") {
            statusCountsOf(
                nativeStatusMessages(requireHandle(), mailboxes.map(::wire).toTypedArray())
                    ?: throw MailFailure("status failed"),
            )
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
            val settings = account ?: throw MailFailure("not connected")
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
                settings.preferHtml,
                settings.showDeleted,
                useServerPreview,
                settings.email,
                settings.altAddresses.joinToString("\n"),
                settings.unlabeledCharset,
                accountId,
            ) ?: throw MailFailure("fetch failed")
            indexRowsOf(fetched)
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
                ref = MessageRef(accountId, request.mailbox, selected.uidValidity, ids[index]),
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
        mimePartOf(nativeFetchStructure(requireHandle(), uid) ?: throw MailFailure("fetch failed"))
    }

    override suspend fun fetchStructureAt(uid: Long, section: String): MimePart = keeper.read("fetch") {
        mimePartOf(nativeFetchStructureAt(requireHandle(), uid, section) ?: throw MailFailure("fetch failed"))
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

    override suspend fun takeCopiedUids(): LongArray {
        if (handle == 0L) throw MailFailure("not connected")
        val ids = copiedUidStore
        copiedUidStore = LongArray(0)
        return ids
    }

    override suspend fun searchText(query: String): LongArray = keeper.read("search") {
        val h = requireHandle()
        remember("SEARCH $query") {
            val ids = nativeSearchText(h, requireWireText(query), featureCaps.searchKind() == "Esearch", !utf8Names)
                ?: throw MailFailure("search failed")
            orderOf(ids)
        }.uids
    }

    override suspend fun searchCriterion(kind: String, argument: String): LongArray = keeper.read("search") {
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
        }.uids
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
    ): List<MessageRef> = keeper.read("search") {
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
            accountId,
        ) ?: throw MailFailure("search failed")
        messageRefsOf(rows)
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

    override suspend fun searchStart(rule: StartRule, byUid: Boolean, edge: SearchEdge): LongArray {
        if (rule == StartRule.Newest) return LongArray(0)
        return keeper.read("search") {
            nativeSearchStart(
                requireHandle(),
                rule.name,
                byUid,
                edge.name,
                featureCaps.searchKind() == "Esearch",
            ) ?: throw MailFailure("search failed")
        }
    }

    override suspend fun locateUid(uid: Long): LongArray = keeper.read("search") {
        nativeLocateUid(
            requireHandle(),
            uid,
            featureCaps.searchKind() == "Esearch",
        ) ?: throw MailFailure("search failed")
    }

    override suspend fun sort(key: SortKey, newestFirst: Boolean): List<Long> = sortWith(key, newestFirst, null).asList()

    override suspend fun sortMatching(key: SortKey, newestFirst: Boolean, criteria: String): List<Long> =
        sortWith(key, newestFirst, criteria).asList()

    private suspend fun sortWith(key: SortKey, newestFirst: Boolean, criteria: String?): LongArray {
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
            }.uids
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
    fun longSearch(kind: String, argument: String): LongArray {
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
        return ids
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

    override suspend fun thread(key: SortKey): ThreadFlat = threadWith(key, null)

    override suspend fun threadMatching(key: SortKey, criteria: String): ThreadFlat = threadWith(key, criteria)

    private suspend fun threadWith(key: SortKey, criteria: String?): ThreadFlat {
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
                val flat = nativeThread(h, algorithm, criteria?.let(::requireCriteria)) ?: throw MailFailure("thread failed")
                orderOf(flat.uids, flat.depths)
            }
            ThreadFlat(order.uids, order.depths)
        }
    }

    override suspend fun clientOrder(
        key: SortKey,
        newestFirst: Boolean,
        onProgress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
    ): List<Long> = clientOrderCollated(
        key,
        newestFirst,
        onProgress,
        cancelled,
        account?.clientSortCollation ?: ClientSortCollation.Locale,
    )

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

    private class SortColumns(
        val uids: LongArray,
        val numbers: LongArray,
        val blanks: BooleanArray,
        val texts: Array<String>,
    )

    private suspend fun fetchSortPieces(
        uids: LongArray,
        key: SortKey,
        onProgress: (Int, Int) -> Unit,
        cancelled: () -> Boolean,
        total: Int,
        doneBase: Int,
    ): SortColumns {
        val code = sortFieldCode(key)
        val chunk = clientSortChunk()
        val uidAcc = ArrayList<Long>()
        val numberAcc = ArrayList<Long>()
        val blankAcc = ArrayList<Boolean>()
        val textAcc = ArrayList<String>()
        var at = 0
        while (at < uids.size) {
            if (cancelled()) throw MailFailure("sort cancelled")
            val end = minOf(uids.size, at + chunk)
            val slice = uids.copyOfRange(at, end)
            val fetched = keeper.read("client-sort") {
                nativeFetchSortChunk(requireHandle(), slice, code) ?: throw MailFailure("sort failed")
            }
            for (i in fetched.uids.indices) {
                uidAcc.add(fetched.uids[i])
                when (key) {
                    SortKey.Date -> {
                        val epoch = dateHeaderEpoch(fetched.texts[i])
                        numberAcc.add(epoch ?: 0L)
                        blankAcc.add(epoch == null)
                        textAcc.add("")
                    }
                    SortKey.Size -> {
                        numberAcc.add(fetched.numbers[i])
                        blankAcc.add(fetched.blanks[i])
                        textAcc.add("")
                    }
                    SortKey.Subject, SortKey.From, SortKey.To, SortKey.Cc -> {
                        numberAcc.add(0L)
                        blankAcc.add(false)
                        textAcc.add(fetched.texts[i])
                    }
                    else -> throw MailFailure("use an arrival IndexMode")
                }
            }
            at = end
            onProgress(doneBase + at, total)
        }
        return columnsOf(key, uidAcc, numberAcc, blankAcc, textAcc)
    }

    private fun columnsOf(
        key: SortKey,
        uidAcc: List<Long>,
        numberAcc: List<Long>,
        blankAcc: List<Boolean>,
        textAcc: List<String>,
    ): SortColumns {
        val n = uidAcc.size
        val charset = account?.unlabeledCharset ?: throw MailFailure("not connected")
        val raw = textAcc.toTypedArray()
        val texts = when (key) {
            SortKey.Subject -> {
                val decoded = if (n == 0) emptyArray() else decodeHeaderPhrases(raw, charset)
                Array(decoded.size) { baseSubject(decoded[it]) }
            }
            SortKey.From, SortKey.To, SortKey.Cc -> Array(n) {
                firstAddrMailbox(raw[it]) { decodeHeaderWords(it, charset) }
            }
            else -> Array(n) { "" }
        }
        return SortColumns(
            LongArray(n) { uidAcc[it] },
            LongArray(n) { numberAcc[it] },
            BooleanArray(n) { blankAcc[it] },
            texts,
        )
    }

    private fun orderFromPieces(
        pieces: SortColumns,
        key: SortKey,
        newestFirst: Boolean,
        collation: ClientSortCollation,
    ): MessageOrder {
        val numeric = key == SortKey.Date || key == SortKey.Size
        val n = pieces.uids.size
        if (n == 0) return orderOf(LongArray(0))
        val numbers = LongArray(n) { if (pieces.blanks[it]) Long.MIN_VALUE else pieces.numbers[it] }
        val packed = if (numeric) {
            Triple(ByteArray(0), IntArray(n), IntArray(n))
        } else {
            textColumns(pieces.texts, collation)
        }
        val perm = nativeSortClient(
            pieces.uids,
            numbers,
            pieces.blanks,
            packed.first,
            packed.second,
            packed.third,
            numeric,
            newestFirst,
        ) ?: throw MailFailure("sort failed")
        return messageOrderFromPerm(pieces, perm, key, if (numeric) null else packed)
    }

    private fun messageOrderFromPerm(
        pieces: SortColumns,
        perm: IntArray,
        key: SortKey,
        packed: Triple<ByteArray, IntArray, IntArray>?,
    ): MessageOrder {
        val n = perm.size
        val uids = LongArray(n) { pieces.uids[perm[it]] }
        val dateEpoch = if (key == SortKey.Date) {
            LongArray(n) {
                val i = perm[it]
                if (pieces.blanks[i]) Long.MIN_VALUE else pieces.numbers[i]
            }
        } else {
            LongArray(0)
        }
        val sizes = if (key == SortKey.Size) {
            LongArray(n) {
                val i = perm[it]
                if (pieces.blanks[i]) Long.MIN_VALUE else pieces.numbers[i]
            }
        } else {
            LongArray(0)
        }
        val text = if (packed == null) Triple(ByteArray(0), IntArray(0), IntArray(0)) else permuteText(packed, perm)
        return MessageOrder(
            uids,
            ShortArray(0),
            selectedMailbox ?: "",
            accountId,
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
        pieces: SortColumns,
        key: SortKey,
        newestFirst: Boolean,
        collation: ClientSortCollation,
    ): MessageOrder {
        if (pieces.uids.isEmpty()) return order
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
        val chunk = clientSortChunk()
        var acc = ClientHeaders(LongArray(0), emptyArray(), emptyArray(), emptyArray(), emptyArray(), emptyArray())
        var at = 0
        while (at < uids.size) {
            val end = minOf(uids.size, at + chunk)
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
            val charset = account?.unlabeledCharset ?: throw MailFailure("not connected")
            val decoded = if (headers.subject.isEmpty()) emptyArray() else decodeHeaderPhrases(headers.subject, charset)
            val subjects = Array(decoded.size) { baseSubject(decoded[it]) }
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

    /**
     * Extra IDLE: the watch connection is the only login (connect, log in, EXAMINE, IDLE). The command
     * connection is never opened.
     */
    override suspend fun watchOnly(account: Settings, mailbox: String, onChange: (MailboxChange) -> Unit) {
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
                    val started = nativeWatch(requireHandle(), wire(mailbox))
                    if (started == 0L) {
                        keeper.markWatchLost()
                        return
                    }
                    if (!installWatch(mailbox, onChange, started)) return
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
        if (account == null) throw MailFailure("no account")
        if (!featureCaps.idle) return
        // F136: not through the keeper. The watch is its own connection and runs beside the main one (off the
        // session lane); a reconnect of the main connection from here would race the lane's commands.
        // The callback is stored only after nativeWatch returns a generation, not before.
        requestedWatch = WatchTarget(mailbox, onChange)
        val started = nativeWatch(requireHandle(), wire(mailbox))
        if (started == 0L) {
            keeper.markWatchLost()
            clearWatchOffer(mailbox, onChange)
            return
        }
        if (!installWatch(mailbox, onChange, started)) return
        noteTraffic("Idling")
    }

    override suspend fun stopWatch() {
        val job = synchronized(watchGate) {
            stoppingWatch = true
            val h = handle
            if (h != 0L) nativeStopWatch(h)
            activeWatch.set(null)
            requestedWatch = null
            watchJob.get()
        }
        joinWatchTake(job)
    }

    /**
     * Installs only while [generation] is still the session's generation.
     * A lost race leaves stoppingWatch and the installed take alone.
     */
    private fun installWatch(mailbox: String, onChange: (MailboxChange) -> Unit, generation: Long): Boolean {
        val previous = synchronized(watchGate) {
            if (closingWatch) return false
            val h = handle
            if (h == 0L || !nativeWatchIsCurrent(h, generation)) return false
            stoppingWatch = false
            activeWatch.set(WatchTarget(mailbox, onChange, generation))
            val previous = watchJob.get()
            val job = watchScope.launch {
                watchThread.set(Thread.currentThread())
                try {
                    while (!stoppingWatch && h != 0L && nativeWatchIsCurrent(h, generation)) {
                        val bytes = nativeWatchTake(h, generation) ?: break
                        for (note in decodeWatchNotes(bytes)) {
                            if (stoppingWatch) break
                            deliverWatchNote(note, generation)
                        }
                    }
                } finally {
                    watchThread.compareAndSet(Thread.currentThread(), null)
                }
            }
            watchJob.set(job)
            previous
        }
        joinWatchTake(previous)
        return true
    }

    private fun joinWatchTake(job: Job?) {
        if (job == null) return
        if (Thread.currentThread() == watchThread.get()) return
        runBlocking { job.join() }
    }

    /** Drops a published offer that is no longer the current generation. */
    private fun clearWatchOffer(mailbox: String, onChange: (MailboxChange) -> Unit) {
        val cur = activeWatch.get() ?: return
        if (cur.onChange !== onChange || cur.mailbox != mailbox) return
        val h = handle
        if (h != 0L && nativeWatchIsCurrent(h, cur.generation)) return
        activeWatch.compareAndSet(cur, null)
    }

    private fun deliverWatchNote(note: DecodedWatchNote, generation: Long) {
        val target = activeWatch.get() ?: return
        if (target.generation != generation) return
        val change = if (note.kind == 4) {
            keeper.markWatchLost()
            MailboxChange.WatchLost
        } else {
            watchChangeOf(note.kind, note.exists, note.uid, note.flags)
        }
        dropCached(target.mailbox, keepClient = true)
        sequencesStale = true
        target.onChange.invoke(change)
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
        val spilled = spilledMessage(context, rfc822)
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
        val spilled = spilledMessage(context, rfc822)
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
        val spilled = spilledMessage(context, rfc822)
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
        val acct = account ?: throw MailFailure("no account")
        val ownSmtpPassword = if (credentials != null) credentials!!.smtpPassword else runBlocking { ownStore().smtpPassword() }
        // Same-as-IMAP uses the IMAP password. On sends no password. Off sends the resolved password.
        val imapPassword = loggedInPassword ?: credentials?.password
        val same = acct.smtpSameAsImap
        val mode = acct.smtpNoAuth
        if (same && imapPassword == null && mode != SmtpNoAuth.On) throw MailFailure("password missing")
        if (!same && ownSmtpPassword == null && mode != SmtpNoAuth.On) throw MailFailure("password missing")
        val resolvedImap = imapPassword.orEmpty()
        if (same && resolvedImap.isEmpty() && mode != SmtpNoAuth.On) throw MailFailure("needs-password")
        val smtpPassword = smtpPasswordFor(same, ownSmtpPassword.orEmpty(), resolvedImap, mode)
        if (mode == SmtpNoAuth.Off && smtpPassword.isEmpty()) throw MailFailure("needs-password")
        val smtpUser = acct.smtpUsername.ifEmpty { acct.username }
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
        val imapPassword = probe?.password ?: loggedInPassword ?: credentials?.password
        val ownSmtpPassword = if (probe != null) probe.smtpPassword else credentials?.smtpPassword
        if (acct.smtpSameAsImap && imapPassword == null && acct.smtpNoAuth != SmtpNoAuth.On) {
            throw MailFailure("password missing")
        }
        if (!acct.smtpSameAsImap && ownSmtpPassword == null && acct.smtpNoAuth != SmtpNoAuth.On) {
            throw MailFailure("password missing")
        }
        val resolvedImap = imapPassword.orEmpty()
        if (acct.smtpSameAsImap && resolvedImap.isEmpty() && acct.smtpNoAuth != SmtpNoAuth.On) {
            throw MailFailure("needs-password")
        }
        val smtpPassword = smtpPasswordFor(acct.smtpSameAsImap, ownSmtpPassword.orEmpty(), resolvedImap, acct.smtpNoAuth)
        if (acct.smtpNoAuth == SmtpNoAuth.Off && smtpPassword.isEmpty()) throw MailFailure("needs-password")
        val smtpUser = acct.smtpUsername.ifEmpty { acct.username }
        // A staged session has no IMAP handle and does not dial imapHost.
        val staged = stageSmtp(acct, resolvedImap)
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

    private fun stageSmtp(account: Settings, password: String): SessionOpen {
        val from = if (account.email.isNotEmpty()) account.email else account.username
        val keyChain = KeyChainCerts.load(account.clientCertAlias)
        val opened = nativeStage(
            account.imapHost,
            account.imapPort,
            account.username,
            password,
            account.smtpHost,
            account.smtpPort,
            from,
            account.pipelineCommands,
            account.tlsMode.name,
            account.smtpTlsMode.name,
            account.certPin,
            account.allowPlaintextAuth,
            account.acceptInvalidCert,
            account.allowOldTls,
            account.authMechanism,
            keyChain?.chain,
            keyChain?.key,
        )
        if (opened.handle != 0L) applyLimits(opened.handle, account)
        return opened
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
        activeWatch.set(null)
        requestedWatch = null
        clearOrderCache()
        keeper.onSessionClosed()
    }

    private inner class SessionLink : Link {
        override fun dead(): Boolean {
            val h = handle
            if (h == 0L) return true
            return nativeSessionDead(h)
        }

        override fun connect() {
            val saved = account ?: throw MailFailure("not connected")
            val opened = login(saved, sessionPassword())
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
            if (account == null) throw MailFailure("no account")
            var started: WatchTarget? = null
            var installed = false
            val running = runRewatch(
                activeWatch.get(),
                requestedWatch,
                start = { target ->
                    requestedWatch = WatchTarget(target.mailbox, target.onChange)
                    started = target
                    val gen = nativeWatch(requireHandle(), wire(target.mailbox))
                    if (gen != 0L) installed = installWatch(target.mailbox, target.onChange, gen)
                    gen
                },
                markLost = { keeper.markWatchLost() },
                clearOffer = {
                    val offer = started ?: return@runRewatch
                    clearWatchOffer(offer.mailbox, offer.onChange)
                },
            )
            if (!running || !installed) return
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
            // One activeWatch snapshot, same as the take coroutine and rewatch.
            val pending = activeWatch.get()
            if (pending != null && reconnectChangeReachesWatcher(selectedMailbox, pending.mailbox)) {
                pending.onChange.invoke(change)
            }
        }

        override fun note(text: String) {
            val writer = accountWriter(account)
            if (writer != null) writer.note("imap", text) else TrafficLog.status(text)
        }
    }

    private fun noteTraffic(text: String) {
        accountWriter(account)?.note("imap", text)
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
        val app = context
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
        accountId,
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
        tlsMode: String,
        smtpTlsMode: String,
        certPin: String,
        allowPlaintextAuth: Boolean,
        acceptInvalidCert: Boolean,
        allowOldTls: Boolean,
        authMechanism: String,
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
        tlsMode: String,
        smtpTlsMode: String,
        certPin: String,
        allowPlaintextAuth: Boolean,
        acceptInvalidCert: Boolean,
        allowOldTls: Boolean,
        authMechanism: String,
        keyChainChain: Array<ByteArray>?,
        keyChainKey: Any?,
    ): SessionOpen

    private external fun nativeSetSessionFlags(handle: Long, pipeline: Boolean)

    private external fun nativeConnect(handle: Long): SessionOpen

    private external fun nativeSetSessionLimits(
        handle: Long,
        connectSec: Int,
        readSec: Int,
        idleRenewSec: Int,
        watchWakeMs: Int,
        maxResultUids: Int,
        trafficDir: String,
        trafficRollBytes: Long,
    )

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
    // Columns: 0 prefix Array<String?>, 1 delimiter IntArray, 2 kind IntArray (0 Personal, 1 Other, 2 Shared).
    private external fun nativeNamespaces(handle: Long): Array<Any?>?
    private external fun nativeHierarchyDelimiter(handle: Long): Char
    // Columns: 0 mailbox, 1 leaf, 2 specialUse (null if none) Array<String?>; 3 hasChildren, 4 delimiter, 5 messages (-1 if absent), 6 unseen (-1 if absent) IntArray.
    private external fun nativeListLevel(handle: Long, prefix: String, parent: String?, listKind: String): Array<Any?>?
    private external fun nativeMailboxListed(handle: Long, name: String): Boolean
    private external fun nativeSubscribedMailboxes(handle: Long, extended: Boolean): Array<String>?
    // Columns: 0 mailbox Array<String?>, 1 messages IntArray.
    private external fun nativeStatusMessages(handle: Long, mailboxes: Array<String>): Array<Any?>?
    private external fun nativeSelect(handle: Long, mailbox: String, readWrite: Boolean): SelectResult?
    private external fun nativeUnselect(handle: Long)
    private external fun nativeCloseMailbox(handle: Long)
    // Columns: 0 uid, 1 uidValidity, 2 epoch LongArray; 3 sequence, 4 size, 5 toMe, 6 hasAttachment,
    // 7 previewFailed IntArray; 8 accountId, 9 mailbox, 10 from, 11 subject, 12 envelopeDate,
    // 13 preview (null if absent), 14 recipients, 15 headerNote, 16 flags (NUL-separated) Array<String?>.
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
        unlabeledCharset: String,
        accountId: String,
    ): Array<Any?>?

    // One MimePart, big-endian. Each part: u16 length + UTF-8 for section, type, subtype, disposition;
    // filename u16 0xFFFF if null else length + UTF-8; size i32; charset, encoding, format as u16 length + UTF-8;
    // delSp u8; child count u16; then that many children.
    private external fun nativeFetchStructure(handle: Long, uid: Long): ByteArray?
    private external fun nativeFetchStructureAt(handle: Long, uid: Long, section: String): ByteArray?
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

    // Columns: 0 accountId, 1 mailbox Array<String?>, 2 uidValidity LongArray, 3 uid LongArray.
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
        accountId: String,
    ): Array<Any?>?

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
    internal fun clientSortOrderForTest(
        uids: LongArray,
        numbers: LongArray,
        blanks: BooleanArray,
        newestFirst: Boolean,
    ): LongArray {
        val perm = nativeSortClient(
            uids,
            numbers,
            blanks,
            ByteArray(0),
            IntArray(uids.size),
            IntArray(uids.size),
            true,
            newestFirst,
        ) ?: throw MailFailure("sort failed")
        return LongArray(perm.size) { uids[perm[it]] }
    }

    private external fun nativeSortClient(
        uids: LongArray,
        numbers: LongArray,
        blanks: BooleanArray,
        keyBytes: ByteArray,
        keyOff: IntArray,
        keyLen: IntArray,
        numeric: Boolean,
        newestFirst: Boolean,
    ): IntArray?
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
    private external fun nativeThread(handle: Long, algorithm: String, criteria: String?): ThreadFlat?
    private external fun nativeWatchLogin(handle: Long): String?
    private external fun nativeWatch(handle: Long, mailbox: String): Long

    // One blocking take. Null ends the loop (stop or the session closed, and the queue had nothing for this generation).
    // Bytes are the big-endian notes decodeWatchNotes reads, oldest first.
    private external fun nativeWatchTake(handle: Long, generation: Long): ByteArray?
    private external fun nativeWatchIsCurrent(handle: Long, generation: Long): Boolean
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
        fun checkServerSettings(account: Settings): ServerCheck {
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

private fun spilledMessage(context: Context, bytes: ByteArray): File {
    val file = File.createTempFile("liveimap-", ".rfc822", context.cacheDir)
    try {
        file.writeBytes(bytes)
    } catch (error: Exception) {
        file.delete()
        throw error
    }
    return file
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

internal fun clientSortChunk(): Int = SettingsHub.uiNow().clientSortChunk

/** F141: a failed sort chunk is drift (restart the walk) when the folder shrank or sequence numbers went stale. */
internal fun sortChunkDrift(existsAtStart: Int, existsNow: Int, stale: Boolean): Boolean = stale || existsNow < existsAtStart

/** F30: the ENABLE token for a resync kind. QRESYNC data is not processed, so QRESYNC servers get CONDSTORE. */
internal fun enableForResync(kind: String): String = when (kind) {
    "Qresync", "Condstore" -> "CONDSTORE"
    else -> ""
}

/** Resolved SMTP password. On is always "". Off returns the IMAP or SMTP secret. */
internal fun smtpPasswordFor(
    sameAsImap: Boolean,
    smtpPassword: String,
    imapPassword: String,
    smtpNoAuth: SmtpNoAuth,
): String {
    if (smtpNoAuth == SmtpNoAuth.On) return ""
    return if (sameAsImap) imapPassword else smtpPassword
}

/** F144: a reconnect's re-SELECT changes go to the watcher only for the very folder it watches. */
internal fun reconnectChangeReachesWatcher(selected: String?, watched: String?): Boolean =
    selected != null && watched != null && sameWireMailbox(selected, watched)

private fun sameWireMailbox(a: String, b: String): Boolean =
    a == b || (a.equals("INBOX", ignoreCase = true) && b.equals("INBOX", ignoreCase = true))
