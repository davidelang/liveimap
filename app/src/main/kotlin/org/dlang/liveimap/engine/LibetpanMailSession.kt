package org.dlang.liveimap.engine

import android.content.Context
import java.io.File
import org.dlang.liveimap.session.FolderEntry
import org.dlang.liveimap.session.IndexRequest
import org.dlang.liveimap.session.IndexRow
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailSession
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.MimePart
import org.dlang.liveimap.session.Namespace
import org.dlang.liveimap.session.OpenResult
import org.dlang.liveimap.session.SelectResult
import org.dlang.liveimap.session.ThreadNode
import org.dlang.liveimap.session.sameImapIdentity
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.SortKey
import org.dlang.liveimap.ui.compose.decodeHeaderWords

internal fun capabilityTokens(serverList: String): List<String> =
    serverList.split(Regex("\\s+")).filter { it.isNotEmpty() }

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
    val have = capabilityTokens(serverList).map { it.uppercase() }.toSet()
    val missing = requiredCapabilities.any { it.uppercase() !in have }
    return if (missing) OpenResult.Rejected(serverList) else OpenResult.Connected
}

private fun hasCap(serverList: String, name: String): Boolean =
    capabilityTokens(serverList).any { it.equals(name, ignoreCase = true) }

fun moveKind(serverList: String): String =
    if (hasCap(serverList, "MOVE")) "Move" else "CopyThenDelete"

fun listKind(serverList: String, unreadCounts: Boolean): String = when {
    !hasCap(serverList, "LIST-EXTENDED") -> "Plain"
    !hasCap(serverList, "LIST-STATUS") -> "Extended"
    unreadCounts -> "ExtendedWithStatus"
    else -> "ExtendedWithMessages"
}

fun resyncKind(serverList: String): String = when {
    hasCap(serverList, "QRESYNC") -> "Qresync"
    hasCap(serverList, "CONDSTORE") -> "Condstore"
    else -> "FullSelect"
}

fun searchKind(serverList: String): String =
    if (hasCap(serverList, "ESEARCH")) "Esearch" else "UidSearch"

fun sortKind(serverList: String): String =
    if (hasCap(serverList, "ESORT")) "Esort" else "UidSort"

fun imapSortKey(serverList: String, token: String): String {
    if (!hasCap(serverList, "SORT=DISPLAY")) return token
    return when (token) {
        "FROM" -> "DISPLAYFROM"
        "TO" -> "DISPLAYTO"
        else -> token
    }
}

fun previewKind(serverList: String): String =
    if (hasCap(serverList, "PREVIEW")) "Preview" else "BodyPeek"

fun fetchKind(serverList: String): String =
    if (hasCap(serverList, "BINARY")) "BinaryPeek" else "BodyPeek"

class LibetpanMailSession : MailSession {
    private var handle: Long = 0

    @Volatile
    private var capSet: Set<String> = emptySet()
    private var account: AccountSettings? = null
    private var compressed = false
    private var selectedMailbox: String? = null
    private var selected = SelectResult(0, 0, 0)

    @Volatile
    private var sequencesStale = false

    @Volatile
    private var watchCallback: ((MailboxChange) -> Unit)? = null

    @Volatile
    private var watchMailbox: String? = null

    private val cache = mutableMapOf<CacheKey, Any>()

    private data class CacheKey(
        val mailbox: String,
        val uidValidity: Long,
        val uidNext: Long,
        val exists: Int,
        val command: String,
    )

    override val capabilities: Set<String>
        get() = capSet

    override suspend fun open(account: AccountSettings): OpenResult {
        val held = this.account
        if (handle != 0L && held != null && sameImapIdentity(held, account)) {
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
                        imapTrafficLogPath(currentApplication()),
                    )
                    this.account = held.copy(
                        pipelineCommands = account.pipelineCommands,
                        logImapTraffic = account.logImapTraffic,
                    )
                    return OpenResult.Connected
                }
            }
        }
        if (handle != 0L) {
            close()
        }
        val context = currentApplication() ?: return OpenResult.Failed("keystore unavailable")
        val password = try {
            DataStoreSettingsStore(context).password()
        } catch (error: Exception) {
            return OpenResult.Failed(error.message ?: "keystore unavailable")
        }
        val from = if (account.email.isNotEmpty()) account.email else account.username
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
            imapTrafficLogPath(context),
        )
        if (opened == 0L) {
            return OpenResult.Failed(nativeTakeError())
        }
        val line = nativeCapabilityLine(opened)
        val gate = capabilityGate(line)
        if (gate is OpenResult.Rejected) {
            nativeClose(opened)
            return gate
        }
        handle = opened
        this.account = account
        capSet = capabilityTokens(line).toSet()
        cache.clear()
        selectedMailbox = null
        sequencesStale = false
        when (resyncKind(line)) {
            "Qresync" -> nativeEnable(opened, "QRESYNC")
            "Condstore" -> nativeEnable(opened, "CONDSTORE")
        }
        if (hasCap(line, "COMPRESS=DEFLATE") && !account.logImapTraffic) {
            nativeCompress(opened)
            compressed = true
        }
        return OpenResult.Connected
    }

    override suspend fun namespaces(): List<Namespace> {
        val rows = nativeNamespaces(requireHandle()) ?: throw MailFailure("namespace failed")
        return rows.toList()
    }

    override suspend fun listLevel(
        prefix: String,
        parentMailbox: String?,
        unreadCounts: Boolean,
    ): List<FolderEntry> {
        val rows = nativeListLevel(
            requireHandle(),
            prefix,
            parentMailbox,
            listKind(advertised(), unreadCounts),
        ) ?: throw MailFailure("list failed")
        return rows.toList()
    }

    override suspend fun statusMessages(mailboxes: List<String>): Map<String, Int> {
        if (mailboxes.isEmpty()) return emptyMap()
        return nativeStatusMessages(requireHandle(), mailboxes.toTypedArray())
    }

    override suspend fun select(mailbox: String): SelectResult {
        val current = selectedMailbox
        if (current != null && current != mailbox && hasCap(advertised(), "UNSELECT")) {
            nativeUnselect(requireHandle())
            selectedMailbox = null
            selected = SelectResult(0, 0, 0)
        }
        val result = nativeSelect(requireHandle(), mailbox) ?: throw MailFailure("select failed")
        selectedMailbox = mailbox
        selected = result
        sequencesStale = false
        return result
    }

    override suspend fun unselect() {
        if (capSet.none { it.equals("UNSELECT", ignoreCase = true) }) return
        nativeUnselect(requireHandle())
    }

    override suspend fun fetchIndex(request: IndexRequest): List<IndexRow> {
        val h = requireHandle()
        if (sequencesStale || selectedMailbox != request.mailbox) {
            select(request.mailbox)
        }
        val settings = account
        val useServerPreview = request.includePreview && previewKind(advertised()) == "Preview"
        val rows = nativeFetchIndex(
            h,
            request.mailbox,
            request.mode.ordinal,
            request.uids.toLongArray(),
            request.limit,
            request.prefetch,
            request.includePreview,
            request.previewByteLimit,
            settings?.preferHtml == true,
            settings?.showDeleted != false,
            useServerPreview,
            settings?.email.orEmpty(),
        ) ?: throw MailFailure("fetch failed")
        return rows.map { row ->
            row.copy(
                from = decodeHeaderWords(row.from),
                subject = decodeHeaderWords(row.subject),
            )
        }
    }

    override suspend fun fetchStructure(uid: Long): MimePart {
        return nativeFetchStructure(requireHandle(), uid) ?: throw MailFailure("fetch failed")
    }

    override suspend fun peekPart(uid: Long, section: String, offset: Int, length: Int): ByteArray {
        val binary = fetchKind(advertised()) == "BinaryPeek"
        return nativePeekPart(requireHandle(), uid, section, offset, length, binary)
            ?: throw MailFailure("fetch failed")
    }

    override suspend fun fetchRfc822(uid: Long): ByteArray {
        return nativeFetchRfc822(requireHandle(), uid) ?: throw MailFailure("fetch failed")
    }

    override suspend fun storeFlags(uids: List<Long>, add: Set<String>, remove: Set<String>) {
        nativeStoreFlags(requireHandle(), uids.toLongArray(), add.toTypedArray(), remove.toTypedArray())
    }

    override suspend fun storeFlagsAll(add: Set<String>, remove: Set<String>) {
        nativeStoreFlagsAll(requireHandle(), add.toTypedArray(), remove.toTypedArray())
    }

    override suspend fun uidExpungeDeleted() {
        nativeUidExpungeDeleted(requireHandle())
    }

    override suspend fun uidExpunge(uids: List<Long>) {
        if (uids.isEmpty()) return
        nativeUidExpunge(requireHandle(), uids.toLongArray())
    }

    override suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String) {
        if (uids.isEmpty()) return
        nativeCopyThenDelete(requireHandle(), uids.toLongArray(), targetMailbox, moveKind(advertised()))
    }

    override suspend fun copyAllThenDelete(targetMailbox: String) {
        nativeCopyAllThenDelete(requireHandle(), targetMailbox, moveKind(advertised()))
    }

    override suspend fun selectedExists(): Int = selected.exists

    override suspend fun takeCopiedUids(): List<Long> {
        val h = handle
        if (h == 0L) return emptyList()
        return nativeTakeCopiedUids(h)?.toList().orEmpty()
    }

    override suspend fun searchText(query: String): List<Long> {
        val h = requireHandle()
        return remember("SEARCH $query") {
            val ids = nativeSearchText(h, query, searchKind(advertised()) == "Esearch")
                ?: throw MailFailure("search failed")
            ids.toList()
        }
    }

    override suspend fun searchCriterion(kind: String, argument: String): List<Long> {
        val h = requireHandle()
        return remember("CRITERION $kind $argument") {
            val ids = nativeSearchCriterion(h, kind, argument, searchKind(advertised()) == "Esearch")
                ?: throw MailFailure("search failed")
            ids.toList()
        }
    }

    override suspend fun sort(key: SortKey, newestFirst: Boolean): List<Long> {
        if (key == SortKey.Arrival) {
            throw MailFailure("use an arrival IndexMode")
        }
        if (key == SortKey.ThreadReferences || key == SortKey.ThreadOrderedSubject) {
            throw MailFailure("use thread")
        }
        val h = requireHandle()
        val command = sortKind(advertised())
        val token = imapSortKey(advertised(), sortToken(key))
        return remember("SORT $token $newestFirst") {
            val ids = nativeSort(h, token, newestFirst, command == "Esort") ?: throw MailFailure("sort failed")
            ids.toList()
        }
    }

    override suspend fun thread(key: SortKey): ThreadNode {
        val algorithm = when (key) {
            SortKey.ThreadReferences -> "REFERENCES"
            SortKey.ThreadOrderedSubject -> {
                val advertised = capSet.any { it.equals("THREAD=ORDEREDSUBJECT", ignoreCase = true) }
                if (!advertised) {
                    throw MailFailure("ORDEREDSUBJECT was not advertised")
                }
                "ORDEREDSUBJECT"
            }
            else -> throw MailFailure("use thread")
        }
        val h = requireHandle()
        return remember("THREAD $algorithm") {
            nativeThread(h, algorithm) ?: throw MailFailure("thread failed")
        }
    }

    override suspend fun watch(mailbox: String, onChange: (MailboxChange) -> Unit) {
        val h = requireHandle()
        watchMailbox = mailbox
        watchCallback = onChange
        nativeWatch(h, mailbox)
    }

    override suspend fun stopWatch() {
        val h = handle
        if (h != 0L) {
            nativeStopWatch(h)
        }
        watchCallback = null
    }

    override suspend fun append(mailbox: String, rfc822: ByteArray, flags: Set<String>) {
        nativeAppend(requireHandle(), mailbox, rfc822, flags.toTypedArray())
    }

    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>) {
        nativeSmtp(requireHandle(), rfc822, recipients.toTypedArray())
    }

    override fun close() {
        val h = handle
        handle = 0
        capSet = emptySet()
        account = null
        compressed = false
        selectedMailbox = null
        sequencesStale = false
        watchCallback = null
        cache.clear()
        if (h != 0L) {
            nativeClose(h)
        }
    }

    fun onNativeWatch(kind: Int, exists: Int, uid: Long, flags: Array<String>?) {
        val mailbox = watchMailbox
        val change: MailboxChange = when (kind) {
            0 -> MailboxChange.Exists(exists)
            1 -> MailboxChange.Expunge(exists)
            2 -> MailboxChange.Flags(uid, flags?.toSet() ?: emptySet())
            else -> MailboxChange.UidValidityReset
        }
        if (change !is MailboxChange.Flags && mailbox != null) {
            cache.keys.retainAll { it.mailbox != mailbox }
        }
        sequencesStale = true
        watchCallback?.invoke(change)
    }

    private fun advertised(): String = capSet.joinToString(" ")

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
    ): Long

    private external fun nativeSetSessionFlags(handle: Long, pipeline: Boolean, log: Boolean, logPath: String)

    private external fun nativeSessionDead(handle: Long): Boolean

    private external fun nativeCapabilityLine(handle: Long): String
    private external fun nativeEnable(handle: Long, capability: String)
    private external fun nativeCompress(handle: Long)
    private external fun nativeClose(handle: Long)
    private external fun nativeNamespaces(handle: Long): Array<Namespace>?
    private external fun nativeListLevel(handle: Long, prefix: String, parent: String?, listKind: String): Array<FolderEntry>?
    private external fun nativeStatusMessages(handle: Long, mailboxes: Array<String>): Map<String, Int>
    private external fun nativeSelect(handle: Long, mailbox: String): SelectResult?
    private external fun nativeUnselect(handle: Long)
    private external fun nativeFetchIndex(
        handle: Long,
        mailbox: String,
        mode: Int,
        uids: LongArray,
        limit: Int,
        prefetch: Int,
        includePreview: Boolean,
        previewByteLimit: Int,
        preferHtml: Boolean,
        showDeleted: Boolean,
        useServerPreview: Boolean,
        accountEmail: String,
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
    private external fun nativeAppend(handle: Long, mailbox: String, message: ByteArray, flags: Array<String>)
    private external fun nativeSearchText(handle: Long, query: String, useEsearch: Boolean): LongArray?
    private external fun nativeSearchCriterion(
        handle: Long,
        kind: String,
        argument: String,
        useEsearch: Boolean,
    ): LongArray?
    private external fun nativeSort(handle: Long, key: String, newestFirst: Boolean, useEsort: Boolean): LongArray?
    private external fun nativeThread(handle: Long, algorithm: String): ThreadNode?
    private external fun nativeWatch(handle: Long, mailbox: String)
    private external fun nativeStopWatch(handle: Long)
    private external fun nativeSmtp(handle: Long, message: ByteArray, recipients: Array<String>)

    companion object {
        init {
            System.loadLibrary("liveimap")
        }

        @JvmStatic
        private external fun nativeTakeError(): String
    }
}

private fun imapTrafficLogPath(context: Context?): String {
    if (context == null) return ""
    return File(context.cacheDir, "imap-traffic.log").absolutePath
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
