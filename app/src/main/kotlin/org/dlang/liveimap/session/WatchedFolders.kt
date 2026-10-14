package org.dlang.liveimap.session

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.dlang.liveimap.settings.AccountSettings
import java.util.concurrent.ConcurrentHashMap

data class WatchPlan(
    val idle: List<String>,
    val status: List<String>,
)

/** Splits extra folders into IDLE slots and STATUS checks. Does not open a socket. */
fun planWatchedFolders(folders: List<String>, idleBudget: Int): WatchPlan {
    val kept = ArrayList<String>()
    val seen = HashSet<String>()
    for (name in folders) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || !seen.add(trimmed)) continue
        kept.add(trimmed)
    }
    val slots = idleBudget.coerceIn(0, MaxExtraIdle) // F81: each slot is a full logged-in connection
    return WatchPlan(kept.take(slots), kept.drop(slots))
}

/** Host, user, and port are enough to tell accounts apart. A mailbox stays here after IDLE was missing. */
private data class NoIdleMailbox(val host: String, val user: String, val port: Int, val mailbox: String)

/** Concurrent set. Not a lock. */
private val noIdleMailboxes = ConcurrentHashMap.newKeySet<NoIdleMailbox>()

private fun noIdleKey(settings: AccountSettings, mailbox: String): NoIdleMailbox =
    NoIdleMailbox(settings.imapHost, settings.username, settings.imapPort, mailbox)

/** Feeds the stored extra folders into the splitter. A recorded no-IDLE mailbox is status, not idle, and only while it is still watched. Does not open a socket. */
fun extraWatchPlan(settings: AccountSettings): WatchPlan {
    val planned = planWatchedFolders(settings.watchedFolders, connectionLimits(settings).idleSlots)
    val idle = ArrayList<String>(planned.idle.size)
    val skipped = ArrayList<String>()
    for (name in planned.idle) {
        if (noIdleMailboxes.contains(noIdleKey(settings, name))) skipped.add(name) else idle.add(name)
    }
    if (skipped.isEmpty()) return planned
    val status = ArrayList<String>(planned.status.size + skipped.size)
    status.addAll(planned.status)
    status.addAll(skipped)
    return WatchPlan(idle, status)
}

/** The main connection plus the index's watch connection: an account cannot work with fewer. */
const val MinConnections = 2
/** Default per-account limit: main, watch, search, and five extra IDLE connections (below Dovecot's
 *  default mail_max_userip_connections of 10). */
const val DefaultMaxConnections = 8
/** Hard ceiling; the limit is never unlimited. */
const val MaxConnectionsCeiling = 20
/** F158: an extra IDLE folder is one watch connection. */
const val ExtraIdleConnectionCost = 1

fun clampMaxConnections(n: Int): Int = n.coerceIn(MinConnections, MaxConnectionsCeiling)

/**
 * How an account's connection limit is spent: main + watch always; then one connection so a body/text search
 * runs without leaving the primary folder; then extra IDLE folders (also capped by the extra IDLE budget).
 * Folders that get no IDLE slot fall back to STATUS polling on an existing connection (WatchPlan.status).
 */
data class ConnectionLimits(val total: Int, val searchConnection: Boolean, val idleSlots: Int)

fun connectionLimits(maxConnections: Int, idleBudget: Int): ConnectionLimits {
    val total = clampMaxConnections(maxConnections)
    var left = total - MinConnections
    val search = left >= 1
    if (search) left -= 1
    val idle = minOf(idleBudget.coerceIn(0, MaxExtraIdle), left / ExtraIdleConnectionCost)
    return ConnectionLimits(total, search, idle)
}

fun connectionLimits(settings: AccountSettings): ConnectionLimits =
    connectionLimits(settings.maxConnections, settings.extraIdleBudget)

/** Digits in MinConnections..MaxConnectionsCeiling, or null (never unlimited). */
fun maxConnectionsFromText(text: String): Int? =
    text.trim().takeIf { it.matches(Regex("\\d{1,3}")) }?.toInt()?.takeIf { it in MinConnections..MaxConnectionsCeiling }

/** Applies only the stored extra IDLE names. STATUS names are not opened. */
suspend fun applyStoredIdle(sessions: ExtraIdleSessions, account: AccountSettings, accountId: String = "") {
    sessions.apply(account, extraWatchPlan(account).idle, accountId)
}

/** One stored name on each line. Does not open a socket. */
fun watchedFolderText(names: List<String>): String = names.joinToString("\n")

/** Trims each line, drops a blank line, and keeps a repeated name. Does not open a socket. */
fun watchedFoldersFromText(text: String): List<String> {
    val names = ArrayList<String>()
    for (line in text.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        names.add(trimmed)
    }
    return names
}

/** Optional minus and digits that fit in an Int, or null. Does not open a socket. */
/** F81: upper bound on extra IDLE connections, so a pasted list cannot open hundreds of logins. */
const val MaxExtraIdle = 8

fun extraIdleBudgetFromText(text: String): Int? {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || !trimmed.matches(Regex("-?\\d+"))) return null
    return trimmed.toIntOrNull()?.takeIf { it in 0..MaxExtraIdle }
}

/** Exists and expunge replace the count. Every other change keeps it. Does not open a socket. */
fun folderMessageCount(current: Int?, change: MailboxChange): Int? = when (change) {
    is MailboxChange.Exists -> change.exists
    is MailboxChange.Expunge -> change.exists
    is MailboxChange.Flags,
    MailboxChange.UidValidityReset,
    MailboxChange.WatchLost,
    MailboxChange.Reconnected,
    -> current
}

/** One extra IDLE change for a mailbox on one account. Does not open a socket. */
data class IdleFolderChange(
    val mailbox: String,
    val change: MailboxChange,
    val accountId: String = "",
)

/** Opens extra IDLE names and checks STATUS names. Does not open a socket. */
interface ExtraWatchLink {
    suspend fun openIdle(mailbox: String)
    suspend fun closeIdle(mailbox: String)
    suspend fun status(mailbox: String)
}

/** Applies an extra-folder watch plan. Does not open a socket. */
class ExtraWatchApply {
    private val open = ArrayList<String>()

    fun openNames(): List<String> = open.toList()

    suspend fun apply(plan: WatchPlan, link: ExtraWatchLink) {
        val keepIdle = plan.idle.toHashSet()
        val stillOpen = ArrayList<String>()
        for (name in open) {
            if (name in keepIdle) {
                stillOpen.add(name)
            } else {
                link.closeIdle(name)
            }
        }
        open.clear()
        open.addAll(stillOpen)
        val openNow = stillOpen.toHashSet()
        for (name in plan.idle) {
            if (openNow.add(name)) {
                link.openIdle(name)
                open.add(name)
            }
        }
        for (name in plan.status) {
            link.status(name)
        }
    }

    suspend fun stop(link: ExtraWatchLink) {
        val closing = open.toList()
        for (name in closing) {
            link.closeIdle(name)
        }
        open.clear()
    }
}

/** One session per extra IDLE name. Does not call STATUS or the screen. */
/**
 * F28: coalesces extra-IDLE changes instead of buffering or dropping them. Per mailbox only the newest count change
 * (Exists/Expunge) is kept, and each reset marker (UidValidityReset, WatchLost, Reconnected) is kept once, so a
 * burst of any size costs O(folders) memory and the consumer always ends on the current count. Flags are not kept
 * (the only consumer uses counts). One consumer drains it. Does not open a socket.
 */
class IdleChangeCoalescer {
    private val pending = LinkedHashMap<Triple<String, String, String>, IdleFolderChange>()
    private val signal = Channel<Unit>(Channel.CONFLATED)

    fun offer(change: IdleFolderChange) {
        val kind = when (change.change) {
            is MailboxChange.Flags -> return
            is MailboxChange.Exists, is MailboxChange.Expunge -> "count"
            else -> change.change.toString()
        }
        synchronized(pending) {
            val key = Triple(change.accountId, change.mailbox, kind)
            pending.remove(key)
            pending[key] = change
        }
        signal.trySend(Unit)
    }

    fun drain(): List<IdleFolderChange> = synchronized(pending) {
        val out = pending.values.toList()
        pending.clear()
        out
    }

    val changes: Flow<IdleFolderChange> = flow {
        while (true) {
            for (change in drain()) emit(change)
            signal.receive()
        }
    }
}

class ExtraIdleSessions(private val factory: () -> MailSession) {
    private val open = ArrayList<Held>()
    private val coalescer = IdleChangeCoalescer()

    /** Count and reset changes per extra IDLE folder, coalesced per folder (F28); nothing is lost. */
    val changes: Flow<IdleFolderChange> = coalescer.changes

    fun openNames(): List<String> = open.map { it.name }

    /** [names] is this account's extra IDLE set. Another account's sessions stay open. */
    suspend fun apply(account: AccountSettings, names: List<String>, accountId: String = "") {
        if (accountId.isBlank()) return
        val keep = HashSet<Pair<String, String>>()
        for (name in names) keep.add(accountId to name)
        for (held in open.toList()) {
            if (held.accountId != accountId) continue
            if ((held.accountId to held.name) in keep) continue
            closeHeld(held.session)
            open.remove(held)
        }
        val seen = HashSet<Pair<String, String>>()
        for (held in open) {
            if (held.accountId == accountId) seen.add(held.accountId to held.name)
        }
        for (name in names) {
            if (!seen.add(accountId to name)) continue
            val session = factory()
            try {
                session.watchOnly(account, name) { change ->
                    coalescer.offer(IdleFolderChange(name, change, accountId))
                }
            } catch (failure: Throwable) {
                suppressClose(session, failure)
                throw failure
            }
            // No IDLE (or IDLE hidden): watchOnly already returned. Do not STATUS this session or an earlier watch.
            if (!session.featureCaps.idle) {
                noIdleMailboxes.add(noIdleKey(account, name.trim()))
                closeHeld(session)
                continue
            }
            open.add(Held(accountId, name, session))
        }
    }

    suspend fun stop() {
        for (held in open.toList()) {
            closeHeld(held.session)
            open.remove(held)
        }
    }

    private suspend fun closeHeld(session: MailSession) {
        try {
            session.stopWatch()
        } finally {
            // F27: close waits out any native call still in flight on this session; never on the main thread.
            withContext(Dispatchers.IO) { session.close() }
        }
    }

    private suspend fun suppressClose(session: MailSession, failure: Throwable) {
        try {
            closeHeld(session)
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
    }

    private class Held(val accountId: String, val name: String, val session: MailSession)
}
