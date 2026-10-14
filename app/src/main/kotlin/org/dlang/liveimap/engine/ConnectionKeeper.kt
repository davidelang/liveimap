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
import org.dlang.liveimap.session.epochAfterReconnect

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
    // A resume older than that owner returns without waiting. A newer resume waits, and reconnects
    // if the owner stopped for a suspend or was superseded by one. The slot is an atomic, not a mutex.
    private val ownerResult = AtomicReference<ReconnectOwner?>(null)
    // One-shot pause after this owner's work and before its result is stored. Tests only.
    internal var beforeOwnerComplete: (() -> Unit)? = null
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

    // Epoch the command had selected, and the epoch after the reconnect that just finished.
    private var driftNamed = 0L
    private var driftNow = 0L

    fun <T> read(@Suppress("UNUSED_PARAMETER") op: String, block: () -> T): T {
        if (prepare()) refuseDriftedEpoch()
        return try {
            val value = block()
            lastUsed = clock()
            value
        } catch (_: ConnectionLost) {
            if (reconnect()) refuseDriftedEpoch()
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
        if (prepare()) refuseDriftedEpoch()
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

    private fun refuseDriftedEpoch(): Nothing {
        val named = driftNamed
        val now = driftNow
        driftNamed = 0L
        driftNow = 0L
        epochAfterReconnect(named, now, "")
        throw EpochUnreachable()
    }

    private fun needsReconnect(): Boolean {
        return broken || link.dead() ||
            state.value is ConnectionState.Suspended ||
            state.value is ConnectionState.Lost
    }

    private fun idleTooLong(): Boolean = clock() - lastUsed >= idleBeforeNoopMs

    /** True when the reconnect's folder epoch is not the one the command had selected. */
    private fun prepare(): Boolean {
        if (needsReconnect()) {
            return reconnect()
        }
        if (idleTooLong()) {
            try {
                link.noop()
                lastUsed = clock()
            } catch (_: ConnectionLost) {
                return reconnect()
            }
        }
        return false
    }

    private class ReconnectOwner(
        val epoch: Long,
        val result: CompletableFuture<kotlin.Result<Unit>>,
    )

    private fun reconnect(fromResume: Boolean = false, epoch: Long? = null, life: LifecycleClock? = lifecycle): Boolean {
        val started = epoch ?: life?.current?.get() ?: 0L
        val owned = CompletableFuture<kotlin.Result<Unit>>()
        val slot = ReconnectOwner(started, owned)
        while (true) {
            val pending = ownerResult.get()
            if (pending != null) {
                if (fromResume && started <= pending.epoch) return false
                val error = pending.result.get().exceptionOrNull()
                if (fromResume && started > pending.epoch) {
                    if (error is LifecycleStop || error is SupersededOwner) continue
                    if (error != null) throw error
                    return false
                }
                if (error == null) return false
                if (error is LifecycleStop) throw ConnectionLost("Closed (background)")
                throw error
            }
            if (ownerResult.compareAndSet(null, slot)) break
        }
        var outcome = kotlin.Result.success(Unit)
        var rethrow: Throwable? = null
        var drifted = false
        try {
            drifted = driveReconnect(life, started)
        } catch (stop: LifecycleStop) {
            outcome = kotlin.Result.failure(stop)
            if (!fromResume) rethrow = ConnectionLost("Closed (background)")
        } catch (stop: SupersededOwner) {
            outcome = kotlin.Result.failure(stop)
            if (!fromResume) rethrow = stop
        } catch (error: Throwable) {
            outcome = kotlin.Result.failure(error)
            rethrow = error
        } finally {
            try {
                val hook = beforeOwnerComplete
                beforeOwnerComplete = null
                hook?.invoke()
                // The last stop check already ran. A suspend or a newer resume may have landed since.
                // The stored result is that outcome, not success and not ConnectionLost.
                if (ownerSuperseded(life, started)) {
                    outcome = kotlin.Result.failure(SupersededOwner())
                    rethrow = if (fromResume) null else SupersededOwner()
                } else if (ownerMustStop(life, started)) {
                    if (!link.dead()) link.close()
                    if (state.value !is ConnectionState.Suspended) {
                        state.value = ConnectionState.Suspended
                        TrafficLog.noteStatus("Closed (background)")
                    }
                    outcome = kotlin.Result.failure(LifecycleStop())
                    rethrow = if (fromResume) null else ConnectionLost("Closed (background)")
                }
            } finally {
                ownerResult.compareAndSet(slot, null)
                if (!owned.isDone) owned.complete(outcome)
            }
        }
        if (rethrow != null) throw rethrow
        return drifted
    }

    private fun driveReconnect(life: LifecycleClock?, epoch: Long): Boolean {
        state.value = ConnectionState.Reconnecting
        TrafficLog.noteStatus("Reconnecting")
        link.close()
        var failure: Exception? = null
        var connected = false
        for (attempt in 0 until 2) {
            if (attempt == 1) {
                stopOrSupersede(life, epoch)
                sleep(3_000)
            }
            stopOrSupersede(life, epoch)
            try {
                link.connect()
                connected = true
                break
            } catch (error: Exception) {
                failure = error
            }
        }
        if (!connected) {
            if (ownerSuperseded(life, epoch)) throw SupersededOwner()
            if (ownerMustStop(life, epoch)) stopForSuspend()
            val text = failureText(failure)
            state.value = ConnectionState.Lost(text)
            TrafficLog.noteStatus("Error $text")
            broken = true
            throw ConnectionLost(text)
        }
        stopOrSupersede(life, epoch)
        val previous = lastSelect
        val selected = try {
            link.reselect()
        } catch (error: Exception) {
            if (ownerSuperseded(life, epoch)) throw SupersededOwner()
            if (ownerMustStop(life, epoch)) stopForSuspend()
            failReconnect(error)
        }
        for (change in reconnectChanges(previous, selected)) link.emit(change)
        if (selected != null) {
            lastSelect = selected
        }
        driftNamed = previous?.uidValidity ?: 0L
        driftNow = selected?.uidValidity ?: 0L
        val drifted = driftNamed != 0L && driftNow != driftNamed
        stopOrSupersede(life, epoch)
        watchLost = false
        broken = false
        lastUsed = clock()
        publishConnected(life, epoch)
        stopOrSupersede(life, epoch)
        scheduleRewatch(life, epoch)
        return drifted
    }

    private class EpochUnreachable : RuntimeException()

    // A winning suspend is not Connected. The update retries if that suspend lands first.
    private fun publishConnected(life: LifecycleClock?, epoch: Long) {
        stopOrSupersede(life, epoch)
        state.update { cur ->
            if (ownerMustStop(life, epoch) || ownerSuperseded(life, epoch)) cur else ConnectionState.Connected
        }
        stopOrSupersede(life, epoch)
    }

    private fun suspendWins(life: LifecycleClock?, epoch: Long): Boolean {
        if (life == null) return false
        val suspendAt = life.latestSuspend.get()
        if (suspendAt <= epoch) return false
        if (suspendAt < life.latestResume.get()) return false
        return true
    }

    // Close only when a suspend is the latest move. A newer resume is not a close.
    private fun ownerMustStop(life: LifecycleClock?, epoch: Long): Boolean {
        return suspendWins(life, epoch)
    }

    // A suspend already ran after this owner, and a newer resume is the latest move.
    private fun ownerSuperseded(life: LifecycleClock?, epoch: Long): Boolean {
        if (life == null) return false
        val suspendAt = life.latestSuspend.get()
        if (suspendAt <= epoch) return false
        return suspendAt < life.latestResume.get()
    }

    private fun stopOrSupersede(life: LifecycleClock?, epoch: Long) {
        if (ownerMustStop(life, epoch)) stopForSuspend()
        if (ownerSuperseded(life, epoch)) throw SupersededOwner()
    }

    private fun stopForSuspend(): Nothing {
        link.close()
        state.value = ConnectionState.Suspended
        TrafficLog.noteStatus("Closed (background)")
        throw LifecycleStop()
    }

    private fun scheduleRewatch(life: LifecycleClock?, epoch: Long) {
        rewatchScope.launch {
            if (ownerSuperseded(life, epoch)) return@launch
            if (ownerMustStop(life, epoch) || state.value is ConnectionState.Suspended) {
                watchLost = true
                if (ownerMustStop(life, epoch)) stopIdle()
                return@launch
            }
            try {
                link.rewatch()
            } catch (_: Exception) {
                watchLost = true
                return@launch
            }
            if (ownerSuperseded(life, epoch)) return@launch
            if (ownerMustStop(life, epoch) || state.value is ConnectionState.Suspended) {
                watchLost = true
                if (ownerMustStop(life, epoch)) stopIdle()
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

private class SupersededOwner : RuntimeException()

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
