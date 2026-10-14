package org.dlang.liveimap.engine

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.dlang.liveimap.session.ConnectionLost
import org.dlang.liveimap.session.ConnectionState
import org.dlang.liveimap.session.LifecycleClock
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.SelectResult

internal const val idleBeforeNoopMs = 120_000L

interface Link {
    fun dead(): Boolean
    fun connect()
    fun noop()
    fun reselect(): SelectResult?
    fun rewatch()
    fun close()
    fun emit(change: MailboxChange)
}

class ConnectionKeeper(
    private val link: Link,
    private val clock: () -> Long,
    private val sleep: (Long) -> Unit,
) {
    private val state = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    val connectionState: StateFlow<ConnectionState> = state

    // Written from the native IDLE thread too (markWatchLost via onNativeWatch, F25).
    @Volatile private var lastUsed = clock()
    @Volatile private var lastSelect: SelectResult? = null
    @Volatile private var broken = false
    @Volatile private var watchLost = false
    // One reconnect owner. A loser does not connect or sleep; it waits for the owner's result.
    // A resume that loses returns without waiting. The future is published with an atomic, not a mutex.
    private val ownerResult = AtomicReference<CompletableFuture<kotlin.Result<Unit>>?>(null)
    private var lifecycle: LifecycleClock? = null
    private val rewatchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun noteSelected(result: SelectResult) {
        lastSelect = result
    }

    fun forgetFolder() {
        lastSelect = null
    }

    fun markWatchLost() {
        watchLost = true
    }

    fun markUsed() {
        lastUsed = clock()
        broken = false
        state.value = ConnectionState.Connected
    }

    fun onSessionClosed() {
        lastSelect = null
        broken = false
        watchLost = false
        lastUsed = clock()
        state.value = ConnectionState.Connected
    }

    fun resume() {
        resume(null, null)
    }

    internal fun resume(clock: LifecycleClock?, epoch: Long?) {
        if (clock != null) lifecycle = clock
        val life = clock ?: lifecycle
        if (needsReconnect()) {
            reconnect(fromResume = true, epoch = epoch, life = life)
            return
        }
        if (idleTooLong()) {
            try {
                link.noop()
                lastUsed = this.clock()
            } catch (_: ConnectionLost) {
                reconnect(fromResume = true, epoch = epoch, life = life)
                return
            }
        }
        if (watchLost) {
            try {
                link.rewatch()
                watchLost = false
            } catch (_: ConnectionLost) {
                reconnect(fromResume = true, epoch = epoch, life = life)
            }
        }
    }

    fun suspendConnections() {
        closeSuspended()
    }

    internal fun suspendConnections(clock: LifecycleClock, epoch: Long) {
        lifecycle = clock
        if (epoch < clock.latestResume.get()) return
        closeSuspended()
    }

    private fun closeSuspended() {
        link.close()
        state.value = ConnectionState.Suspended
        TrafficLog.noteStatus("Closed (background)")
    }

    fun <T> read(@Suppress("UNUSED_PARAMETER") op: String, block: () -> T): T {
        prepare()
        return try {
            val value = block()
            lastUsed = clock()
            value
        } catch (_: ConnectionLost) {
            reconnect()
            try {
                val value = block()
                lastUsed = clock()
                value
            } catch (again: ConnectionLost) {
                broken = true
                TrafficLog.noteStatus("Error ${again.text}")
                throw again
            }
        }
    }

    fun <T> write(op: String, block: () -> T): T {
        prepare()
        return try {
            val value = block()
            lastUsed = clock()
            value
        } catch (_: ConnectionLost) {
            broken = true
            val text = "Connection lost while $op; it may not have finished. Check the folder, then retry."
            TrafficLog.noteStatus("Error $text")
            throw ConnectionLost(text)
        }
    }

    private fun needsReconnect(): Boolean {
        return broken || link.dead() ||
            state.value is ConnectionState.Suspended ||
            state.value is ConnectionState.Lost
    }

    private fun idleTooLong(): Boolean = clock() - lastUsed >= idleBeforeNoopMs

    private fun prepare() {
        if (needsReconnect()) {
            reconnect()
            return
        }
        if (idleTooLong()) {
            try {
                link.noop()
                lastUsed = clock()
            } catch (_: ConnectionLost) {
                reconnect()
            }
        }
    }

    private fun reconnect(fromResume: Boolean = false, epoch: Long? = null, life: LifecycleClock? = lifecycle) {
        val started = epoch ?: life?.current?.get() ?: 0L
        val owned = CompletableFuture<kotlin.Result<Unit>>()
        while (true) {
            val pending = ownerResult.get()
            if (pending != null) {
                if (fromResume) return
                val error = pending.get().exceptionOrNull() ?: return
                if (error is LifecycleStop) throw ConnectionLost("Closed (background)")
                throw error
            }
            if (ownerResult.compareAndSet(null, owned)) break
        }
        try {
            driveReconnect(life, started)
            owned.complete(kotlin.Result.success(Unit))
        } catch (stop: LifecycleStop) {
            if (!owned.isDone) owned.complete(kotlin.Result.failure(stop))
            if (!fromResume) throw ConnectionLost("Closed (background)")
        } catch (error: Throwable) {
            if (!owned.isDone) owned.complete(kotlin.Result.failure(error))
            throw error
        } finally {
            if (!owned.isDone) owned.complete(kotlin.Result.success(Unit))
            ownerResult.compareAndSet(owned, null)
        }
    }

    private fun driveReconnect(life: LifecycleClock?, epoch: Long) {
        state.value = ConnectionState.Reconnecting
        TrafficLog.noteStatus("Reconnecting")
        link.close()
        var failure: Exception? = null
        var connected = false
        for (attempt in 0 until 2) {
            if (attempt == 1) {
                if (suspendWins(life, epoch)) stopForSuspend()
                sleep(3_000)
            }
            if (suspendWins(life, epoch)) stopForSuspend()
            try {
                link.connect()
                connected = true
                break
            } catch (error: Exception) {
                failure = error
            }
        }
        if (!connected) {
            val text = failureText(failure)
            state.value = ConnectionState.Lost(text)
            TrafficLog.noteStatus("Error $text")
            broken = true
            throw ConnectionLost(text)
        }
        if (suspendWins(life, epoch)) stopForSuspend()
        val previous = lastSelect
        val selected = try {
            link.reselect()
        } catch (error: Exception) {
            failReconnect(error)
        }
        for (change in reconnectChanges(previous, selected)) link.emit(change)
        if (selected != null) {
            lastSelect = selected
        }
        if (suspendWins(life, epoch)) stopForSuspend()
        watchLost = false
        broken = false
        lastUsed = clock()
        publishConnected(life, epoch)
        // This owner's epoch is no longer current: do not start IDLE. A newer resume already returned.
        if (!epochCurrent(life, epoch)) {
            watchLost = true
            return
        }
        scheduleRewatch(life, epoch)
    }

    // Do not publish Connected over a suspend that already closed. update retries if that write lands first.
    private fun publishConnected(life: LifecycleClock?, epoch: Long) {
        state.update { cur ->
            if (cur is ConnectionState.Suspended || suspendWins(life, epoch)) cur else ConnectionState.Connected
        }
        if (state.value is ConnectionState.Suspended || suspendWins(life, epoch)) stopForSuspend()
    }

    private fun epochCurrent(life: LifecycleClock?, epoch: Long): Boolean {
        if (life == null) return true
        return life.current.get() == epoch
    }

    private fun suspendWins(life: LifecycleClock?, epoch: Long): Boolean {
        if (life == null) return false
        val suspendAt = life.latestSuspend.get()
        if (suspendAt <= epoch) return false
        if (suspendAt < life.latestResume.get()) return false
        return true
    }

    private fun stopForSuspend(): Nothing {
        link.close()
        state.value = ConnectionState.Suspended
        TrafficLog.noteStatus("Closed (background)")
        throw LifecycleStop()
    }

    private fun scheduleRewatch(life: LifecycleClock?, epoch: Long) {
        rewatchScope.launch {
            if (!epochCurrent(life, epoch) || state.value is ConnectionState.Suspended || suspendWins(life, epoch)) {
                watchLost = true
                if (suspendWins(life, epoch)) stopIdle()
                return@launch
            }
            try {
                link.rewatch()
            } catch (_: Exception) {
                watchLost = true
                return@launch
            }
            if (state.value is ConnectionState.Suspended || suspendWins(life, epoch)) {
                watchLost = true
                stopIdle()
                return@launch
            }
            watchLost = false
        }
    }

    private fun stopIdle() {
        link.close()
        state.value = ConnectionState.Suspended
        TrafficLog.noteStatus("Closed (background)")
    }

    private fun failReconnect(error: Exception): Nothing {
        val text = failureText(error)
        state.value = ConnectionState.Lost(text)
        TrafficLog.noteStatus("Error $text")
        broken = true
        if (error is ConnectionLost) {
            throw error
        }
        throw ConnectionLost(text)
    }

    private fun failureText(error: Exception?): String {
        return when (error) {
            is MailFailure -> error.text
            null -> "connection failed"
            else -> error.message ?: "connection failed"
        }
    }
}

/**
 * What changed across a reconnect (F28). Arrivals are judged by UIDNEXT, not by EXISTS, so arrivals hidden by an
 * equal or larger number of expunges in the gap are still reported. Expunge comes first so the last count wins.
 * Does not open a socket.
 */
private class LifecycleStop : RuntimeException()

internal fun reconnectChanges(
    previous: org.dlang.liveimap.session.SelectResult?,
    selected: org.dlang.liveimap.session.SelectResult?,
): List<MailboxChange> {
    if (selected != null && previous != null && selected.uidValidity != previous.uidValidity) {
        return listOf(MailboxChange.UidValidityReset)
    }
    val out = ArrayList<MailboxChange>()
    if (selected != null && previous != null) {
        val arrived = selected.uidNext != previous.uidNext || selected.exists > previous.exists
        // More UIDs handed out than the count grew by: something was expunged too (a skipped UIDNEXT only
        // over-reports, which costs a reload, never a missed change).
        val expunged = selected.exists < previous.exists ||
            (selected.uidNext - previous.uidNext) > (selected.exists - previous.exists).toLong()
        if (expunged) {
            out.add(MailboxChange.Expunge(selected.exists))
        }
        if (arrived) out.add(MailboxChange.Exists(selected.exists))
    }
    out.add(MailboxChange.Reconnected)
    return out
}
