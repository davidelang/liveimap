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

    private var lastUsed = clock()
    private var lastSelect: SelectResult? = null
    private var broken = false
    private var watchLost = false

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
            throw ConnectionLost(
                "Connection lost while $op; it may not have finished. Check the folder, then retry.",
            )
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
            broken = true
            throw ConnectionLost(text)
        }
        val previous = lastSelect
        val selected = try {
            link.reselect()
        } catch (error: Exception) {
            failReconnect(error)
        }
        if (selected != null && previous != null && selected.uidValidity != previous.uidValidity) {
            link.emit(MailboxChange.UidValidityReset)
        } else {
            if (selected != null && previous != null && selected.exists != previous.exists) {
                if (selected.exists > previous.exists) {
                    link.emit(MailboxChange.Exists(selected.exists))
                } else {
                    link.emit(MailboxChange.Expunge(selected.exists))
                }
            }
            link.emit(MailboxChange.Reconnected)
        }
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
