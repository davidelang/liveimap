package org.dlang.liveimap.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongSearchTest {
    private class FakeLink(val result: () -> List<Long>) : LongSearchLink {
        var searches = 0
        var closed = false
        var aborted = false
        @Volatile var progress = SearchProgress(-1, -1, 0)
        val release = CompletableDeferred<Unit>()
        var block = false
        override suspend fun open() {}
        override suspend fun search(kind: String, argument: String): List<Long> {
            searches += 1
            if (block) runBlocking { release.await() }
            if (aborted) throw ConnectionLost("connection dropped")
            return result()
        }
        override fun progress() = progress
        override fun abort() { aborted = true; release.complete(Unit) }
        override suspend fun close() { closed = true }
    }

    @Test
    fun hitsAndClose() = runBlocking {
        val link = FakeLink { listOf(3L, 4L) }
        assertEquals(LongSearchResult.Hits(listOf(3L, 4L)), LongSearch(link, 5).run("Body", "x") {})
        assertTrue(link.closed)
    }

    @Test
    fun failureIsReportedOnceWithoutRetry() = runBlocking {
        val link = FakeLink { throw ConnectionLost("timed out") }
        assertEquals(LongSearchResult.Failed("timed out"), LongSearch(link, 5).run("Body", "x") {})
        assertEquals(1, link.searches)
        assertTrue(link.closed)
    }

    @Test
    fun cancelDropsConnectionAndDiscards() = runBlocking {
        val link = FakeLink { listOf(1L) }.apply { block = true }
        val search = LongSearch(link, 5)
        val running = async { search.run("Body", "x") {} }
        delay(50)
        search.cancel()
        assertEquals(LongSearchResult.Cancelled, running.await())
        assertTrue(link.aborted)
        assertEquals(1, link.searches)
    }

    @Test
    fun coroutineCancelAbortsBlockedSearchAndCloses() = runBlocking {
        val link = FakeLink { listOf(1L) }.apply { block = true }
        val running = async { LongSearch(link, 5).run("Body", "x") {} }
        delay(50)
        running.cancel()
        kotlinx.coroutines.withTimeout(2000) { running.join() }
        assertTrue(link.aborted)
        assertTrue(link.closed)
    }

    @Test
    fun lateResultAfterCancelIsDiscarded() = runBlocking {
        lateinit var search: LongSearch
        val link = FakeLink { search.cancel(); listOf(9L) }
        search = LongSearch(link, 5)
        assertEquals(LongSearchResult.Cancelled, search.run("Body", "x") {})
    }

    @Test
    fun progressUpdatesAreForwarded() = runBlocking {
        val seen = mutableListOf<SearchProgress>()
        val link = FakeLink { listOf(1L) }.apply { block = true }
        val search = LongSearch(link, 5)
        val running = async { search.run("Text", "x") { synchronized(seen) { seen += it } } }
        link.progress = SearchProgress(500, 1000, 1)
        delay(100)
        link.release.complete(Unit)
        running.await()
        assertTrue(synchronized(seen) { seen.any { it.fraction == 0.5f } })
    }

    @Test
    fun longKindsAndTimeouts() {
        assertTrue(isLongSearch("Body"))
        assertTrue(isLongSearch("Advanced", "And\nYes\tBody\tinvoice"))
        assertFalse(isLongSearch("Advanced", "Or\nYes\tSubject\tLiveIMAP"))
        assertFalse(isLongSearch("Subject"))
        assertEquals(60, longSearchTimeoutSec(true))
        assertEquals(600, longSearchTimeoutSec(false))
        assertEquals(null, SearchProgress(-1, 1000, 1).fraction)
    }
}
