package org.dlang.liveimap.session

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.dlang.liveimap.settings.AccountSettings

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
    val slots = idleBudget.coerceAtLeast(0)
    return WatchPlan(kept.take(slots), kept.drop(slots))
}

/** Feeds the stored extra folders into the splitter. Does not open a socket. */
fun extraWatchPlan(settings: AccountSettings): WatchPlan =
    planWatchedFolders(settings.watchedFolders, settings.extraIdleBudget)

/** Applies only the stored extra IDLE names. STATUS names are not opened. */
suspend fun applyStoredIdle(sessions: ExtraIdleSessions, account: AccountSettings) {
    sessions.apply(account, extraWatchPlan(account).idle)
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
fun extraIdleBudgetFromText(text: String): Int? {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || !trimmed.matches(Regex("-?\\d+"))) return null
    return trimmed.toIntOrNull()
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

/** One extra IDLE change for a mailbox. Does not open a socket. */
data class IdleFolderChange(
    val mailbox: String,
    val change: MailboxChange,
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
class ExtraIdleSessions(private val factory: () -> MailSession) {
    private val open = ArrayList<Held>()
    private val changeFlow = MutableSharedFlow<IdleFolderChange>(extraBufferCapacity = 16)

    /** A full buffer drops the new change. Older changes stay. */
    val changes: SharedFlow<IdleFolderChange> = changeFlow

    fun openNames(): List<String> = open.map { it.name }

    suspend fun apply(account: AccountSettings, names: List<String>) {
        val keep = names.toHashSet()
        for (held in open.toList()) {
            if (held.name in keep) continue
            closeHeld(held.session)
            open.remove(held)
        }
        val seen = open.mapTo(HashSet()) { it.name }
        for (name in names) {
            if (!seen.add(name)) continue
            val session = factory()
            val result = try {
                session.open(account)
            } catch (failure: Throwable) {
                suppressClose(session, failure)
                throw failure
            }
            when (result) {
                OpenResult.Connected -> {
                    try {
                        session.select(name)
                        session.watch(name) { change ->
                            changeFlow.tryEmit(IdleFolderChange(name, change))
                        }
                    } catch (failure: Throwable) {
                        suppressClose(session, failure)
                        throw failure
                    }
                    open.add(Held(name, session))
                }
                is OpenResult.Failed -> {
                    val failure = MailFailure(result.text)
                    suppressClose(session, failure)
                    throw failure
                }
                is OpenResult.Rejected -> {
                    val failure = MailFailure("watch rejected")
                    suppressClose(session, failure)
                    throw failure
                }
            }
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
            session.close()
        }
    }

    private suspend fun suppressClose(session: MailSession, failure: Throwable) {
        try {
            closeHeld(session)
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
    }

    private class Held(val name: String, val session: MailSession)
}
