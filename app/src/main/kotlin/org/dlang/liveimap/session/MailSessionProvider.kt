package org.dlang.liveimap.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.dlang.liveimap.accounts.AccountTarget
import org.dlang.liveimap.engine.LibetpanMailSession
import org.dlang.liveimap.settings.AccountSettings

// JMAP accounts (jmapFolders on) get a JmapMailSession at open; everything else, and all sending, stays libetpan.
private val accountSessions = SessionTable {
    SerialMailSession(RoutedMailSession({ LibetpanMailSession() }, { org.dlang.liveimap.jmap.platformJmapMailSession() }))
}

// Watched folders follow the account's kind too: a JMAP account watches them over its event source.
private val extraIdleSessions = ExtraIdleSessions {
    SerialMailSession(RoutedMailSession({ LibetpanMailSession() }, { org.dlang.liveimap.jmap.platformJmapMailSession() }))
}

private val extraIdleLock = Mutex()

/** Account ids held under [extraIdleLock] until that drop returns. Not a lock. */
private val extraIdleDrops = ExtraIdleDropLog()

fun mailSession(accountId: String): MailSession = accountSessions.session(accountId)

fun setMailCertConfirmer(confirm: (suspend (CertPrompt) -> Boolean)?) {
    accountSessions.setCertConfirmer(confirm)
}

suspend fun confirmMailCertificate(prompt: CertPrompt): Boolean {
    return accountSessions.confirmCertificate(prompt)
}

suspend fun confirmMailPlaintext(): Boolean = accountSessions.confirmPlaintext()

fun setMailPlaintextConfirmer(confirm: (suspend () -> Boolean)?) {
    accountSessions.setPlaintextConfirmer(confirm)
}

suspend fun suspendMailSessions() {
    accountSessions.suspendConnections()
}

suspend fun applyExtraIdle(account: AccountSettings, accountId: String) {
    applyExtraIdleTo(extraIdleSessions, extraIdleLock, account, accountId, extraIdleDrops)
}

/**
 * The stamp and the dropped set are read before [lock]. Under the lock, a held id does not open,
 * and neither does one whose stamp changed. A later apply that starts after the drop clears may open.
 */
internal suspend fun applyExtraIdleTo(
    sessions: ExtraIdleSessions,
    lock: Mutex,
    account: AccountSettings,
    accountId: String,
    drops: ExtraIdleDropLog,
) {
    if (accountId.isBlank()) return
    val seen = sessions.readGeneration()
    val seenDrop = drops.mark(accountId)
    if (seenDrop.held) return
    lock.withLock {
        if (!sessions.generationStill(seen)) return
        val now = drops.mark(accountId)
        if (now.held || now.stamp != seenDrop.stamp) return
        withContext(AccountTarget(accountId)) {
            applyStoredIdle(sessions, account, accountId, seen)
        }
    }
}

/** Bump on this thread and record that generation. Close later only while it is still current. */
internal fun launchExtraIdleStop(
    scope: CoroutineScope,
    sessions: ExtraIdleSessions,
    lock: Mutex,
    suspendSessions: suspend () -> Unit,
): Job {
    sessions.bumpGeneration()
    val generation = sessions.readGeneration()
    return scope.launch(NonCancellable) {
        try {
            suspendSessions()
        } finally {
            closeExtraIdleIfCurrent(sessions, lock, generation)
        }
    }
}

/** onStop calls this. The generation changes before the stop coroutine runs. */
fun launchExtraIdleStop(scope: CoroutineScope): Job =
    launchExtraIdleStop(scope, extraIdleSessions, extraIdleLock) { suspendMailSessions() }

/** onStart calls this before it walks accounts, so the late stop does not close the start's sessions. */
suspend fun runAfterExtraIdleStop(stop: Job?, block: suspend () -> Unit) {
    stop?.join()
    block()
}

/** Does not bump. An older stop does not call [ExtraIdleSessions.stop] after a newer stop has bumped. */
private suspend fun closeExtraIdleIfCurrent(sessions: ExtraIdleSessions, lock: Mutex, generation: Int) {
    lock.withLock {
        if (!sessions.generationStill(generation)) return
        sessions.stop()
    }
}

/** Extra IDLE changes from the process holder. Does not open a socket. */
fun extraIdleChanges(): kotlinx.coroutines.flow.Flow<IdleFolderChange> = extraIdleSessions.changes

suspend fun dropMailSession(accountId: String) {
    extraIdleLock.withLock {
        recordDroppedExtraIdle(extraIdleSessions, accountId, extraIdleDrops)
    }
    try {
        accountSessions.drop(accountId)
    } finally {
        extraIdleLock.withLock {
            extraIdleDrops.clear(accountId)
        }
    }
}

/** Under [extraIdleLock]: remember [accountId], then close that account's extra IDLE. */
internal suspend fun recordDroppedExtraIdle(
    sessions: ExtraIdleSessions,
    accountId: String,
    drops: ExtraIdleDropLog,
) {
    if (accountId.isNotBlank()) drops.record(accountId)
    sessions.closeAccount(accountId)
}

/** Stamps and the in-flight drop set, published under extraIdleLock. A volatile map, not a second lock. */
internal class ExtraIdleDropLog {
    @Volatile
    private var state = State()

    fun mark(accountId: String): ExtraIdleDropMark {
        val at = state.at[accountId] ?: return ExtraIdleDropMark(0, false)
        return ExtraIdleDropMark(at.seq, at.held)
    }

    fun record(accountId: String) {
        val cur = state
        val seq = cur.seq + 1
        state = State(cur.at + (accountId to Mark(seq, true)), seq)
    }

    /** After closeAccount and accountSessions.drop. The stamp moves so a sample from the drop still misses. */
    fun clear(accountId: String) {
        val cur = state
        if (cur.at[accountId] == null) return
        val seq = cur.seq + 1
        state = State(cur.at + (accountId to Mark(seq, false)), seq)
    }

    private data class Mark(val seq: Int, val held: Boolean)
    private data class State(val at: Map<String, Mark> = emptyMap(), val seq: Int = 0)
}

internal data class ExtraIdleDropMark(val stamp: Int, val held: Boolean)
