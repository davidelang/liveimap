package org.dlang.liveimap.engine

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.dlang.liveimap.session.ConnectionLost
import org.dlang.liveimap.session.ConnectionState
import org.dlang.liveimap.session.LifecycleClock
import org.dlang.liveimap.session.MailFailure
import org.dlang.liveimap.session.MailboxChange
import org.dlang.liveimap.session.SelectResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
    fun secondReconnectDoesNotSleepWhileOneRuns() {
        val link = FakeLink()
        link.deadFlag = true
        link.connectFailures.add(MailFailure("first boom"))
        val sleeps = mutableListOf<Long>()
        val inSleep = CountDownLatch(1)
        val release = CountDownLatch(1)
        val keeper = ConnectionKeeper(link, clock = { 0L }, sleep = {
            synchronized(sleeps) { sleeps.add(it) }
            inSleep.countDown()
            release.await()
        })
        val ownerError = arrayOfNulls<Throwable>(1)
        val owner = Thread {
            try {
                keeper.read("fetch") { "ok" }
            } catch (error: Throwable) {
                ownerError[0] = error
            }
        }
        owner.start()
        inSleep.await()
        val connectsWhileOwned = link.connects
        val loserValue = arrayOfNulls<String>(1)
        val loserError = arrayOfNulls<Throwable>(1)
        val loserDone = CountDownLatch(1)
        val loser = Thread {
            try {
                loserValue[0] = keeper.read("fetch") { "ok" }
            } catch (error: Throwable) {
                loserError[0] = error
            } finally {
                loserDone.countDown()
            }
        }
        loser.start()
        assertFalse(loserDone.await(300, TimeUnit.MILLISECONDS))
        assertEquals(connectsWhileOwned, link.connects)
        assertEquals(listOf(3_000L), synchronized(sleeps) { sleeps.toList() })
        keeper.resume()
        assertEquals(connectsWhileOwned, link.connects)
        assertEquals(listOf(3_000L), synchronized(sleeps) { sleeps.toList() })
        release.countDown()
        owner.join()
        assertTrue(loserDone.await(5, TimeUnit.SECONDS))
        assertEquals(null, ownerError[0])
        assertEquals(null, loserError[0])
        assertEquals("ok", loserValue[0])
        assertEquals(2, link.connects)
        assertEquals(listOf(3_000L), synchronized(sleeps) { sleeps.toList() })
    }

    @Test
    fun loserWaitsAndThrowsTheOwnersFailure() {
        val link = FakeLink()
        link.deadFlag = true
        link.connectFailures.add(MailFailure("first boom"))
        link.connectFailures.add(MailFailure("still down"))
        val inSleep = CountDownLatch(1)
        val release = CountDownLatch(1)
        val keeper = ConnectionKeeper(link, clock = { 0L }, sleep = {
            inSleep.countDown()
            release.await()
        })
        val ownerError = arrayOfNulls<Throwable>(1)
        val owner = Thread {
            try {
                keeper.read("fetch") { "ok" }
            } catch (error: Throwable) {
                ownerError[0] = error
            }
        }
        owner.start()
        inSleep.await()
        val loserError = arrayOfNulls<Throwable>(1)
        val loserDone = CountDownLatch(1)
        val loser = Thread {
            try {
                keeper.read("fetch") { "no" }
            } catch (error: Throwable) {
                loserError[0] = error
            } finally {
                loserDone.countDown()
            }
        }
        loser.start()
        assertFalse(loserDone.await(300, TimeUnit.MILLISECONDS))
        release.countDown()
        owner.join()
        assertTrue(loserDone.await(5, TimeUnit.SECONDS))
        assertTrue(ownerError[0] is ConnectionLost)
        assertEquals("still down", (ownerError[0] as ConnectionLost).text)
        assertTrue(loserError[0] is ConnectionLost)
        assertEquals("still down", (loserError[0] as ConnectionLost).text)
        assertFalse((loserError[0] as ConnectionLost).text.contains("Reconnecting"))
        assertEquals(2, link.connects)
    }

    @Test
    fun staleSuspendDoesNotClose() {
        val clock = LifecycleClock()
        val link = FakeLink()
        val keeper = ConnectionKeeper(link, clock = { 0L }, sleep = { })
        val suspendEpoch = clock.takeSuspend()
        clock.takeResume()
        keeper.suspendConnections(clock, suspendEpoch)
        assertEquals(0, link.closes)
        assertEquals(ConnectionState.Connected, keeper.connectionState.value)
    }

    @Test
    fun resumeClosesWhenANewerSuspendArrivesBeforeConnect() {
        val clock = LifecycleClock()
        val link = FakeLink()
        link.deadFlag = true
        val epoch = clock.takeResume()
        clock.takeSuspend()
        val keeper = ConnectionKeeper(link, clock = { 0L }, sleep = { })
        keeper.resume(clock, epoch)
        assertEquals(0, link.connects)
        assertEquals(0, link.rewatches)
        assertEquals(ConnectionState.Suspended, keeper.connectionState.value)
    }

    @Test
    fun resumeStopsBeforeRewatchWhenSuspendArrivesDuringConnect() {
        val clock = LifecycleClock()
        val link = FakeLink()
        link.deadFlag = true
        val epoch = clock.takeResume()
        link.beforeConnect = { clock.takeSuspend() }
        val keeper = ConnectionKeeper(link, clock = { 0L }, sleep = { })
        keeper.resume(clock, epoch)
        assertEquals(1, link.connects)
        assertEquals(0, link.reselects)
        assertEquals(0, link.rewatches)
        assertEquals(ConnectionState.Suspended, keeper.connectionState.value)
    }

    @Test
    fun newerResumeAfterSuspendEndsConnected() {
        val clock = LifecycleClock()
        val link = FakeLink()
        link.deadFlag = true
        link.connectFailures.add(MailFailure("first boom"))
        val inSleep = CountDownLatch(1)
        val release = CountDownLatch(1)
        val keeper = ConnectionKeeper(link, clock = { 0L }, sleep = {
            inSleep.countDown()
            release.await()
        })
        val epochA = clock.takeResume()
        val ownerError = arrayOfNulls<Throwable>(1)
        val owner = Thread {
            try {
                keeper.resume(clock, epochA)
            } catch (error: Throwable) {
                ownerError[0] = error
            }
        }
        owner.start()
        assertTrue(inSleep.await(5, TimeUnit.SECONDS))
        val suspendEpoch = clock.takeSuspend()
        keeper.suspendConnections(clock, suspendEpoch)
        val epochB = clock.takeResume()
        val resumeError = arrayOfNulls<Throwable>(1)
        val resumed = CountDownLatch(1)
        val resumeB = Thread {
            try {
                keeper.resume(clock, epochB)
            } catch (error: Throwable) {
                resumeError[0] = error
            } finally {
                resumed.countDown()
            }
        }
        resumeB.start()
        assertFalse(resumed.await(300, TimeUnit.MILLISECONDS))
        release.countDown()
        owner.join()
        assertTrue(resumed.await(5, TimeUnit.SECONDS))
        assertEquals(null, ownerError[0])
        assertEquals(null, resumeError[0])
        assertEquals(ConnectionState.Connected, keeper.connectionState.value)
        assertTrue(link.awaitRewatches(1))
    }

    @Test
    fun suspendAfterConnectLetsTheNewerResumeReconnect() {
        val clock = LifecycleClock()
        val link = FakeLink()
        link.deadFlag = true
        val inReselect = CountDownLatch(1)
        val releaseReselect = CountDownLatch(1)
        link.beforeReselect = {
            inReselect.countDown()
            releaseReselect.await()
            if (link.deadFlag) throw MailFailure("closed under the owner")
        }
        val keeper = ConnectionKeeper(link, clock = { 0L }, sleep = { })
        val epochA = clock.takeResume()
        val ownerError = arrayOfNulls<Throwable>(1)
        val owner = Thread {
            try {
                keeper.resume(clock, epochA)
            } catch (error: Throwable) {
                ownerError[0] = error
            }
        }
        owner.start()
        assertTrue(inReselect.await(5, TimeUnit.SECONDS))
        assertEquals(1, link.connects)
        val suspendEpoch = clock.takeSuspend()
        keeper.suspendConnections(clock, suspendEpoch)
        val epochB = clock.takeResume()
        val resumeError = arrayOfNulls<Throwable>(1)
        val resumed = CountDownLatch(1)
        val resumeB = Thread {
            try {
                keeper.resume(clock, epochB)
            } catch (error: Throwable) {
                resumeError[0] = error
            } finally {
                resumed.countDown()
            }
        }
        resumeB.start()
        assertFalse(resumed.await(300, TimeUnit.MILLISECONDS))
        releaseReselect.countDown()
        owner.join()
        assertTrue(resumed.await(5, TimeUnit.SECONDS))
        assertEquals(null, ownerError[0])
        assertEquals(null, resumeError[0])
        assertEquals(ConnectionState.Connected, keeper.connectionState.value)
        assertTrue(link.connects >= 2)
        assertTrue(link.awaitRewatches(1))
    }

    @Test
    fun suspendThatIsStillLatestClosesWithoutIdle() {
        val clock = LifecycleClock()
        val link = FakeLink()
        link.deadFlag = true
        link.connectFailures.add(MailFailure("first boom"))
        val inSleep = CountDownLatch(1)
        val release = CountDownLatch(1)
        val keeper = ConnectionKeeper(link, clock = { 0L }, sleep = {
            inSleep.countDown()
            release.await()
        })
        val epoch = clock.takeResume()
        val owner = Thread { keeper.resume(clock, epoch) }
        owner.start()
        assertTrue(inSleep.await(5, TimeUnit.SECONDS))
        val suspendEpoch = clock.takeSuspend()
        keeper.suspendConnections(clock, suspendEpoch)
        release.countDown()
        owner.join()
        assertEquals(ConnectionState.Suspended, keeper.connectionState.value)
        assertEquals(0, link.rewatches)
    }

    @Test
    fun rewatchIsNotOnTheReconnectCaller() {
        val link = FakeLink()
        link.deadFlag = true
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val rewatchThread = arrayOfNulls<Thread>(1)
        link.rewatchGate = {
            rewatchThread[0] = Thread.currentThread()
            entered.countDown()
            release.await()
        }
        val keeper = keeper(link)
        val reader = Thread {
            keeper.read("fetch") { "ok" }
            returned.countDown()
        }
        reader.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        assertTrue(returned.await(5, TimeUnit.SECONDS))
        assertEquals(ConnectionState.Connected, keeper.connectionState.value)
        assertFalse(rewatchThread[0] == reader)
        release.countDown()
        reader.join()
        assertEquals(1, link.rewatches)
    }

    @Test
    fun failedRewatchLeavesTheConnectionUp() {
        val link = FakeLink()
        link.deadFlag = true
        link.rewatchFailures.add(MailFailure("idle failed"))
        val keeper = keeper(link)
        val value = keeper.read("fetch") { "ok" }
        assertEquals("ok", value)
        assertTrue(link.awaitRewatches(1))
        assertEquals(ConnectionState.Connected, keeper.connectionState.value)
        val deadline = System.currentTimeMillis() + 2_000
        var restarted = false
        while (System.currentTimeMillis() < deadline) {
            val before = link.rewatches
            keeper.resume()
            if (link.rewatches > before) {
                restarted = true
                break
            }
            Thread.sleep(10)
        }
        assertTrue(restarted)
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
        assertTrue(link.awaitRewatches(1))
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
    @Test
    fun reconnectReportsArrivalsHiddenByExpunges() {
        val before = org.dlang.liveimap.session.SelectResult(7, 100, 50)
        // 3 arrived, 5 expunged: EXISTS fell, but UIDNEXT moved.
        assertEquals(
            listOf(MailboxChange.Expunge(48), MailboxChange.Exists(48), MailboxChange.Reconnected),
            reconnectChanges(before, org.dlang.liveimap.session.SelectResult(7, 103, 48)),
        )
        // equal counts, UIDNEXT moved
        assertEquals(
            listOf(MailboxChange.Expunge(50), MailboxChange.Exists(50), MailboxChange.Reconnected),
            reconnectChanges(before, org.dlang.liveimap.session.SelectResult(7, 102, 50)),
        )
        assertEquals(listOf(MailboxChange.Reconnected), reconnectChanges(before, before))
        // 5 arrived, 2 expunged
        assertEquals(
            listOf(MailboxChange.Expunge(53), MailboxChange.Exists(53), MailboxChange.Reconnected),
            reconnectChanges(before, org.dlang.liveimap.session.SelectResult(7, 105, 53)),
        )
        // 3 arrived only
        assertEquals(
            listOf(MailboxChange.Exists(53), MailboxChange.Reconnected),
            reconnectChanges(before, org.dlang.liveimap.session.SelectResult(7, 103, 53)),
        )
        assertEquals(
            listOf(MailboxChange.Expunge(40), MailboxChange.Reconnected),
            reconnectChanges(before, org.dlang.liveimap.session.SelectResult(7, 100, 40)),
        )
        assertEquals(
            listOf(MailboxChange.UidValidityReset),
            reconnectChanges(before, org.dlang.liveimap.session.SelectResult(8, 1, 0)),
        )
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
    val rewatchFailures = ArrayDeque<Exception>()
    var rewatchGate: (() -> Unit)? = null
    private val rewatchArrived = java.util.concurrent.atomic.AtomicInteger(0)
    val emissions = mutableListOf<MailboxChange>()
    var beforeConnect: () -> Unit = {}
    var beforeReselect: () -> Unit = {}

    fun awaitRewatches(n: Int): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (rewatchArrived.get() < n && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        return rewatchArrived.get() >= n
    }

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
        beforeReselect()
        return selectResult
    }

    override fun rewatch() {
        rewatches += 1
        try {
            rewatchGate?.invoke()
            if (rewatchFailures.isNotEmpty()) throw rewatchFailures.removeFirst()
        } finally {
            rewatchArrived.incrementAndGet()
        }
    }

    override fun close() {
        closes += 1
        deadFlag = true
    }

    override fun emit(change: MailboxChange) {
        emissions.add(change)
    }

}
