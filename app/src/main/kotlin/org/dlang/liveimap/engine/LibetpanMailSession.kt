package org.dlang.liveimap.engine

import android.content.Context
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
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.DataStoreSettingsStore
import org.dlang.liveimap.settings.SortKey

internal fun capabilityTokens(serverList: String): List<String> =
    serverList.split(Regex("\\s+")).filter { it.isNotEmpty() }

internal val requiredCapabilities = listOf(
    "NAMESPACE",
    "UIDPLUS",
    "LITERAL+",
    "CHILDREN",
    "UNSELECT",
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

fun listKind(serverList: String): String = when {
    !hasCap(serverList, "LIST-EXTENDED") -> "Plain"
    hasCap(serverList, "LIST-STATUS") -> "ExtendedWithStatus"
    else -> "Extended"
}

fun resyncKind(serverList: String): String = when {
    hasCap(serverList, "QRESYNC") -> "Qresync"
    hasCap(serverList, "CONDSTORE") -> "Condstore"
    else -> "FullSelect"
}

fun searchKind(serverList: String): String =
    if (hasCap(serverList, "ESEARCH")) "Esearch" else "UidSearch"

fun sortKind(serverList: String): String {
    val command = if (hasCap(serverList, "ESORT")) "Esort" else "UidSort"
    val from = if (hasCap(serverList, "SORT=DISPLAY")) "DISPLAY" else "FROM"
    return "$command $from"
}

fun previewKind(serverList: String): String =
    if (hasCap(serverList, "PREVIEW")) "Preview" else "BodyPeek"

fun fetchKind(serverList: String): String =
    if (hasCap(serverList, "BINARY")) "BinaryPeek" else "BodyPeek"

class LibetpanMailSession : MailSession {
    private var handle: Long = 0
    private var capSet: Set<String> = emptySet()
    private var account: AccountSettings? = null
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
        return OpenResult.Connected
    }

    override suspend fun namespaces(): List<Namespace> {
        val rows = nativeNamespaces(requireHandle()) ?: throw MailFailure("namespace failed")
        return rows.toList()
    }

    override suspend fun listLevel(prefix: String, parentMailbox: String?): List<FolderEntry> {
        val rows = nativeListLevel(requireHandle(), prefix, parentMailbox) ?: throw MailFailure("list failed")
        return rows.toList()
    }

    override suspend fun select(mailbox: String): SelectResult {
        val result = nativeSelect(requireHandle(), mailbox) ?: throw MailFailure("select failed")
        selectedMailbox = mailbox
        selected = result
        sequencesStale = false
        return result
    }

    override suspend fun unselect() {
        nativeUnselect(requireHandle())
    }

    override suspend fun fetchIndex(request: IndexRequest): List<IndexRow> {
        val h = requireHandle()
        if (sequencesStale || selectedMailbox != request.mailbox) {
            select(request.mailbox)
        }
        val settings = account
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
        ) ?: throw MailFailure("fetch failed")
        return rows.toList()
    }

    override suspend fun fetchStructure(uid: Long): MimePart {
        return nativeFetchStructure(requireHandle(), uid) ?: throw MailFailure("fetch failed")
    }

    override suspend fun peekPart(uid: Long, section: String, offset: Int, length: Int): ByteArray {
        return nativePeekPart(requireHandle(), uid, section, offset, length) ?: throw MailFailure("fetch failed")
    }

    override suspend fun fetchRfc822(uid: Long): ByteArray {
        return nativeFetchRfc822(requireHandle(), uid) ?: throw MailFailure("fetch failed")
    }

    override suspend fun storeFlags(uids: List<Long>, add: Set<String>, remove: Set<String>) {
        nativeStoreFlags(requireHandle(), uids.toLongArray(), add.toTypedArray(), remove.toTypedArray())
    }

    override suspend fun uidExpungeDeleted() {
        nativeUidExpungeDeleted(requireHandle())
    }

    override suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String) {
        nativeCopyThenDelete(requireHandle(), uids.toLongArray(), targetMailbox)
    }

    override suspend fun searchText(query: String): List<Long> {
        val h = requireHandle()
        return remember("SEARCH $query") {
            val ids = nativeSearchText(h, query) ?: throw MailFailure("search failed")
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
        val token = sortToken(key)
        return remember("SORT $token $newestFirst") {
            val ids = nativeSort(h, token, newestFirst) ?: throw MailFailure("sort failed")
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

    override suspend fun append(mailbox: String, rfc822: ByteArray) {
        nativeAppend(requireHandle(), mailbox, rfc822)
    }

    override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>) {
        nativeSmtp(requireHandle(), rfc822, recipients.toTypedArray())
    }

    override fun close() {
        val h = handle
        handle = 0
        capSet = emptySet()
        account = null
        selectedMailbox = null
        sequencesStale = false
        watchCallback = null
        cache.clear()
        if (h != 0L) {
            nativeClose(h)
        }
    }

    internal fun onNativeWatch(kind: Int, exists: Int, uid: Long, flags: Array<String>?) {
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
    ): Long

    private external fun nativeCapabilityLine(handle: Long): String
    private external fun nativeClose(handle: Long)
    private external fun nativeNamespaces(handle: Long): Array<Namespace>?
    private external fun nativeListLevel(handle: Long, prefix: String, parent: String?): Array<FolderEntry>?
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
    ): Array<IndexRow>?

    private external fun nativeFetchStructure(handle: Long, uid: Long): MimePart?
    private external fun nativePeekPart(handle: Long, uid: Long, section: String, offset: Int, length: Int): ByteArray?
    private external fun nativeFetchRfc822(handle: Long, uid: Long): ByteArray?
    private external fun nativeStoreFlags(handle: Long, uids: LongArray, add: Array<String>, remove: Array<String>)
    private external fun nativeUidExpungeDeleted(handle: Long)
    private external fun nativeCopyThenDelete(handle: Long, uids: LongArray, target: String)
    private external fun nativeAppend(handle: Long, mailbox: String, message: ByteArray)
    private external fun nativeSearchText(handle: Long, query: String): LongArray?
    private external fun nativeSort(handle: Long, key: String, newestFirst: Boolean): LongArray?
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

private fun currentApplication(): Context? {
    return try {
        val thread = Class.forName("android.app.ActivityThread")
        val method = thread.getMethod("currentApplication")
        method.invoke(null) as? Context
    } catch (_: Exception) {
        null
    }
}
