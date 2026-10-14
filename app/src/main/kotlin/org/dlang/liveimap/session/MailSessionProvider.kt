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

/** Account ids dropped under [extraIdleLock]. Not a lock. A later apply reads the stamp it published. */
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
 * [seenDrop] is this account's stamp read before [lock]. A drop that records the id while this apply waits
 * does not open. A later apply that reads the stamp after that drop does.
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
    val seenDrop = drops.stamp(accountId)
    lock.withLock {
        if (!sessions.generationStill(seen)) return
        if (drops.stamp(accountId) != seenDrop) return
        withContext(AccountTarget(accountId)) {
            applyStoredIdle(sessions, account, accountId, seen)
        }
    }
}

/** Bump on this thread, then run [suspendSessions] and [stopExtraIdleOn]. The caller keeps the job. */
internal fun launchExtraIdleStop(
    scope: CoroutineScope,
    sessions: ExtraIdleSessions,
    lock: Mutex,
    suspendSessions: suspend () -> Unit,
): Job {
    sessions.bumpGeneration()
    return scope.launch(NonCancellable) {
        try {
            suspendSessions()
        } finally {
            stopExtraIdleOn(sessions, lock)
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

suspend fun stopExtraIdle() {
    stopExtraIdleOn(extraIdleSessions, extraIdleLock)
}

private suspend fun stopExtraIdleOn(sessions: ExtraIdleSessions, lock: Mutex) {
    sessions.bumpGeneration()
    lock.withLock {
        sessions.stop()
    }
}

/** Extra IDLE changes from the process holder. Does not open a socket. */
fun extraIdleChanges(): kotlinx.coroutines.flow.Flow<IdleFolderChange> = extraIdleSessions.changes

suspend fun dropMailSession(accountId: String) {
    extraIdleLock.withLock {
        recordDroppedExtraIdle(extraIdleSessions, accountId, extraIdleDrops)
    }
    accountSessions.drop(accountId)
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

/** Stamps published under extraIdleLock. A volatile map, not a second lock. */
internal class ExtraIdleDropLog {
    @Volatile
    private var state = State()

    fun stamp(accountId: String): Int = state.at[accountId] ?: 0

    fun record(accountId: String) {
        val cur = state
        val seq = cur.seq + 1
        state = State(cur.at + (accountId to seq), seq)
    }

    private data class State(val at: Map<String, Int> = emptyMap(), val seq: Int = 0)
}
