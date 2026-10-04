package org.dlang.liveimap.engine

import java.util.ArrayDeque
import org.dlang.liveimap.session.ConnectionLost
import org.dlang.liveimap.session.ConnectionState
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.SelectResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionKeeperTest {
    @Test
    fun readReconnectsOnceAndRunsTwice() {
        val link = FakeLink()
        val keeper = keeper(link)
        var runs = 0
        val value = keeper.read("fetch") {
            runs += 1
            if (runs == 1) throw ConnectionLost("reset")
            "ok"
        }
        assertEquals("ok", value)
        assertEquals(2, runs)
        assertEquals(1, link.connects)
    }

    @Test
    fun secondReadLossIsNotTriedAgain() {
        val link = FakeLink()
        val keeper = keeper(link)
        var runs = 0
        try {
            keeper.read("fetch") {
                runs += 1
                throw ConnectionLost("reset")
            }
            error("expected connection lost")
        } catch (error: ConnectionLost) {
            assertEquals("reset", error.text)
        }
        assertEquals(2, runs)
        assertEquals(1, link.connects)
    }

    @Test
    fun writeIsNotRepeatedAndNextCallReconnects() {
        val link = FakeLink()
        val keeper = keeper(link)
        var runs = 0
        try {
            keeper.write("store") {
                runs += 1
                throw ConnectionLost("reset")
            }
            error("expected connection lost")
        } catch (error: ConnectionLost) {
            assertEquals(
                "Connection lost while store; it may not have finished. Check the folder, then retry.",
                error.text,
            )
        }
        assertEquals(1, runs)
        assertEquals(0, link.connects)
        var next = 0
        keeper.read("fetch") {
            next += 1
            "ok"
        }
        assertEquals(1, next)
        assertEquals(1, link.connects)
    }

    @Test
    fun noopOnlyAfterTwoMinutes() {
        val early = FakeLink()
        val earlyClock = Clock()
        val earlyKeeper = keeper(early, earlyClock)
        earlyKeeper.read("fetch") { "warm" }
        earlyClock.now = 119_000
        earlyKeeper.read("fetch") { "again" }
        assertEquals(0, early.noops)

        val late = FakeLink()
        val lateClock = Clock()
        val lateKeeper = keeper(late, lateClock)
        lateKeeper.read("fetch") { "warm" }
        lateClock.now = 121_000
        lateKeeper.read("fetch") { "again" }
        assertEquals(1, late.noops)
    }

    @Test
    fun noopFailureReconnectsThenRunsOnce() {
        val link = FakeLink()
        val clock = Clock()
        val keeper = keeper(link, clock)
        keeper.read("fetch") { "warm" }
        clock.now = 121_000
        link.noopFailures.add(ConnectionLost("noop failed"))
        var runs = 0
        keeper.read("fetch") {
            runs += 1
            "ok"
        }
        assertEquals(1, link.noops)
        assertEquals(1, link.connects)
        assertEquals(1, runs)
    }

    @Test
    fun deadLinkReconnectsBeforeTheCommand() {
        val link = FakeLink()
        link.deadFlag = true
        val keeper = keeper(link)
        var runs = 0
        keeper.read("fetch") {
            runs += 1
            "ok"
        }
        assertEquals(1, link.connects)
        assertEquals(1, runs)
        assertEquals(0, link.noops)
    }

    @Test
    fun twoFailedConnectsSleepOnceThenStop() {
        val link = FakeLink()
        link.deadFlag = true
        link.connectFailures.add(MailFailure("first boom"))
        link.connectFailures.add(MailFailure("still down"))
        val sleeps = mutableListOf<Long>()
        val keeper = keeper(link, sleeps = sleeps)
        try {
            keeper.read("fetch") { "no" }
            error("expected connection lost")
        } catch (error: ConnectionLost) {
            assertEquals("still down", error.text)
        }
        assertEquals(listOf(3_000L), sleeps)
        assertEquals(ConnectionState.Lost("still down"), keeper.connectionState.value)
        assertEquals(2, link.connects)
    }

    @Test
    fun stateGoesReconnectingThenConnected() {
        val link = FakeLink()
        link.deadFlag = true
        val keeper = keeper(link)
        val seen = mutableListOf<ConnectionState>()
        link.beforeConnect = { seen.add(keeper.connectionState.value) }
        assertEquals(ConnectionState.Connected, keeper.connectionState.value)
        keeper.read("fetch") { "ok" }
        assertEquals(listOf(ConnectionState.Reconnecting), seen)
        assertEquals(ConnectionState.Connected, keeper.connectionState.value)
    }

    @Test
    fun uidValidityChangeEmitsOnlyReset() {
        val link = FakeLink()
        link.deadFlag = true
        link.selectResult = SelectResult(8, 9, 4)
        val keeper = keeper(link)
        keeper.noteSelected(SelectResult(5, 9, 4))
        keeper.read("fetch") { "ok" }
        assertEquals(listOf<MailboxChange>(MailboxChange.UidValidityReset), link.emissions)
    }

    @Test
    fun sameUidValidityWithMoreMessagesEmitsExistsThenReconnected() {
        val link = FakeLink()
        link.deadFlag = true
        link.selectResult = SelectResult(5, 9, 11)
        val keeper = keeper(link)
        keeper.noteSelected(SelectResult(5, 9, 4))
        keeper.read("fetch") { "ok" }
        assertEquals(
            listOf(MailboxChange.Exists(11), MailboxChange.Reconnected),
            link.emissions,
        )
    }

    @Test
    fun suspendClosesOnceAndNextReadRestores() {
        val link = FakeLink()
        val keeper = keeper(link)
        keeper.suspendConnections()
        assertEquals(1, link.closes)
        assertEquals(ConnectionState.Suspended, keeper.connectionState.value)
        keeper.read("fetch") { "ok" }
        assertEquals(1, link.connects)
        assertEquals(1, link.reselects)
        assertEquals(1, link.rewatches)
    }

    @Test
    fun plainMailFailureDoesNotReconnect() {
        val link = FakeLink()
        val keeper = keeper(link)
        var runs = 0
        try {
            keeper.read("fetch") {
                runs += 1
                throw MailFailure("NO such mailbox")
            }
            error("expected mail failure")
        } catch (error: MailFailure) {
            assertFalse(error is ConnectionLost)
            assertEquals("NO such mailbox", error.text)
        }
        assertEquals(1, runs)
        assertEquals(0, link.connects)
    }
}

private class Clock {
    var now = 0L
}

private fun keeper(
    link: FakeLink,
    clock: Clock = Clock(),
    sleeps: MutableList<Long> = mutableListOf(),
): ConnectionKeeper {
    return ConnectionKeeper(link, clock = { clock.now }, sleep = { sleeps.add(it) })
}

private class FakeLink : Link {
    var deadFlag = false
    var selectResult: SelectResult? = SelectResult(1, 2, 3)
    val connectFailures = ArrayDeque<Exception>()
    val noopFailures = ArrayDeque<Exception>()
    var connects = 0
    var noops = 0
    var closes = 0
    var reselects = 0
    var rewatches = 0
    val emissions = mutableListOf<MailboxChange>()
    var beforeConnect: () -> Unit = {}

    override fun dead(): Boolean = deadFlag

    override fun connect() {
        beforeConnect()
        connects += 1
        if (connectFailures.isNotEmpty()) {
            throw connectFailures.removeFirst()
        }
        deadFlag = false
    }

    override fun noop() {
        noops += 1
        if (noopFailures.isNotEmpty()) {
            deadFlag = true
            throw noopFailures.removeFirst()
        }
    }

    override fun reselect(): SelectResult? {
        reselects += 1
        return selectResult
    }

    override fun rewatch() {
        rewatches += 1
    }

    override fun close() {
        closes += 1
        deadFlag = true
    }

    override fun emit(change: MailboxChange) {
        emissions.add(change)
    }
}
