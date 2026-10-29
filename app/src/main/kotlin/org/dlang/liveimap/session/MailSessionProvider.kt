package org.dlang.liveimap.session

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.dlang.liveimap.LiveImapApplication
import org.dlang.liveimap.engine.LibetpanMailSession
import org.dlang.liveimap.settings.Settings

internal interface IdleTurn {
    suspend fun <T> turn(block: suspend () -> T): T
}

/** One extra-IDLE command at a time. Not closed when an account is dropped. */
private class IdleActor : IdleTurn {
    private val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class OnIdle : AbstractCoroutineContextElement(OnIdle) {
        companion object Key : CoroutineContext.Key<OnIdle>
    }

    init {
        scope.launch {
            for (command in commands) {
                try {
                    command()
                } catch (_: Throwable) {
                }
            }
        }
    }

    override suspend fun <T> turn(block: suspend () -> T): T {
        if (coroutineContext[OnIdle] != null) return block()
        val done = CompletableDeferred<T>()
        commands.send {
            try {
                withContext(OnIdle()) { done.complete(block()) }
            } catch (error: Throwable) {
                done.completeExceptionally(error)
            }
        }
        return done.await()
    }
}

// JMAP accounts (jmapFolders on) get a JmapMailSession at open; everything else, and all sending, stays libetpan.
private fun sessionFor(accountId: String): MailSession {
    val context = LiveImapApplication.context ?: error("no application")
    return SerialMailSession(
        RoutedMailSession(
            { LibetpanMailSession(context, accountId) },
            { org.dlang.liveimap.jmap.platformJmapMailSession(context, accountId) },
        ),
    )
}

private val accountSessions = SessionTable { id -> sessionFor(id) }

// Watched folders follow the account's kind too: a JMAP account watches them over its event source.
private val extraIdleSessions = ExtraIdleSessions { id -> sessionFor(id) }

private val extraIdleTurn: IdleTurn = IdleActor()

/** Account ids held under the idle command until that drop returns. Not a lock. */
private val extraIdleDrops = ExtraIdleDropLog()

fun mailSession(accountId: String): MailSession = accountSessions.session(accountId)

/** A session already open for [accountId], or null. Does not create one. */
fun existingMailSession(accountId: String): MailSession? = accountSessions.existing(accountId)

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

suspend fun applyExtraIdle(account: Settings, accountId: String) {
    applyExtraIdleTo(extraIdleSessions, extraIdleTurn, account, accountId, extraIdleDrops)
}

/**
 * The stamp and the dropped set are read before the command. Under the command, a held id does not open,
 * and neither does one whose stamp changed. A later apply that starts after the drop clears may open.
 */
internal suspend fun applyExtraIdleTo(
    sessions: ExtraIdleSessions,
    turn: IdleTurn,
    account: Settings,
    accountId: String,
    drops: ExtraIdleDropLog,
) {
    if (accountId.isBlank()) return
    val seen = sessions.readGeneration()
    val seenDrop = drops.mark(accountId)
    if (seenDrop.held) return
    turn.turn {
        if (!sessions.generationStill(seen)) return@turn
        val now = drops.mark(accountId)
        if (now.held || now.stamp != seenDrop.stamp) return@turn
        applyStoredIdle(sessions, account, accountId, seen)
    }
}

/** Bump on this thread and record that generation. Close later only while it is still current. */
internal fun launchExtraIdleStop(
    scope: CoroutineScope,
    sessions: ExtraIdleSessions,
    turn: IdleTurn,
    suspendSessions: suspend () -> Unit,
): Job {
    sessions.bumpGeneration()
    val generation = sessions.readGeneration()
    return scope.launch(NonCancellable) {
        try {
            suspendSessions()
        } finally {
            closeExtraIdleIfCurrent(sessions, turn, generation)
        }
    }
}

/** onStop calls this. The generation changes before the stop coroutine runs. */
fun launchExtraIdleStop(scope: CoroutineScope): Job =
    launchExtraIdleStop(scope, extraIdleSessions, extraIdleTurn) { suspendMailSessions() }

/** onStart calls this before it walks accounts, so the late stop does not close the start's sessions. */
suspend fun runAfterExtraIdleStop(stop: Job?, block: suspend () -> Unit) {
    stop?.join()
    block()
}

/** Does not bump. An older stop does not call [ExtraIdleSessions.stop] after a newer stop has bumped. */
private suspend fun closeExtraIdleIfCurrent(sessions: ExtraIdleSessions, turn: IdleTurn, generation: Int) {
    turn.turn {
        if (!sessions.generationStill(generation)) return@turn
        sessions.stop()
    }
}

/** Extra IDLE changes from the process holder. Does not open a socket. */
fun extraIdleChanges(): kotlinx.coroutines.flow.Flow<IdleFolderChange> = extraIdleSessions.changes

suspend fun dropMailSession(accountId: String) {
    extraIdleTurn.turn {
        recordDroppedExtraIdle(extraIdleSessions, accountId, extraIdleDrops)
    }
    try {
        accountSessions.drop(accountId)
    } finally {
        extraIdleTurn.turn {
            extraIdleDrops.clear(accountId)
        }
    }
}

/** Under the idle command: remember [accountId], then close that account's extra IDLE. */
internal suspend fun recordDroppedExtraIdle(
    sessions: ExtraIdleSessions,
    accountId: String,
    drops: ExtraIdleDropLog,
) {
    if (accountId.isNotBlank()) drops.record(accountId)
    sessions.closeAccount(accountId)
}

/** Stamps and the in-flight drop set, published under the idle command. A volatile map, not a second lock. */
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
