package org.dlang.liveimap.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongSearchTest {
    private class RecordingLink(val result: () -> List<Long>) : LongSearchLink {
        var closed = false
        override suspend fun open() {}
        override suspend fun search(kind: String, argument: String): List<Long> = result()
        override fun progress() = SearchProgress(-1, -1, 0)
        override fun abort() {}
        override suspend fun close() { closed = true }
    }

    @Test
    fun hitsAndClose() = runBlocking {
        val link = RecordingLink { listOf(3L, 4L) }
        assertEquals(LongSearchResult.Hits(listOf(3L, 4L)), LongSearch(link, 5).run("Body", "x") {})
        assertTrue(link.closed)
    }

    @Test
    fun lateResultAfterCancelIsDiscarded() = runBlocking {
        lateinit var search: LongSearch
        val link = RecordingLink { search.cancel(); listOf(9L) }
        search = LongSearch(link, 5)
        assertEquals(LongSearchResult.Cancelled, search.run("Body", "x") {})
    }

    @Test
    fun longKindsAndTimeouts() {
        assertTrue(isLongSearch("Body"))
        assertTrue(isLongSearch("Advanced", "And\nYes\tBody\tinvoice"))
        assertFalse(isLongSearch("Advanced", "Or\nYes\tSubject\tLiveIMAP"))
        assertFalse(isLongSearch("Subject"))
        assertEquals(null, SearchProgress(-1, 1000, 1).fraction)
    }
}
