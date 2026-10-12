package org.dlang.liveimap.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.dlang.liveimap.session.ConnectionLost
import org.dlang.liveimap.session.ConnectionState
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
                return
            }
        }
        if (watchLost) {
            try {
                link.rewatch()
                watchLost = false
            } catch (_: ConnectionLost) {
                reconnect()
            }
        }
    }

    fun suspendConnections() {
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

    private fun reconnect() {
        state.value = ConnectionState.Reconnecting
        TrafficLog.noteStatus("Reconnecting")
        link.close()
        var failure: Exception? = null
        var connected = false
        for (attempt in 0 until 2) {
            if (attempt == 1) {
                sleep(3_000)
            }
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
        try {
            link.rewatch()
        } catch (error: Exception) {
            failReconnect(error)
        }
        watchLost = false
        broken = false
        lastUsed = clock()
        state.value = ConnectionState.Connected
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
