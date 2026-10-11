package org.dlang.liveimap.ui.index

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MoveChunksTest {
    private val uids = (1L..2500L).toList()

    @Test
    fun movesEverythingInChunksWithProgress() = runBlocking {
        val sizes = mutableListOf<Int>()
        val progress = mutableListOf<Int>()
        val r = moveInChunks(uids, MoveChunk, { false }, { d, _ -> progress += d }) { c -> sizes += c.size; c.map { it + 10_000 } }
        assertEquals(listOf(1000, 1000, 500), sizes)
        assertEquals(listOf(1000, 2000, 2500), progress)
        assertEquals(uids, r.moved)
        assertEquals(2500, r.dest.size)
    }

    @Test
    fun stopTakesEffectBetweenChunks() = runBlocking {
        var calls = 0
        val r = moveInChunks(uids, MoveChunk, { calls >= 1 }, { _, _ -> }) { c -> calls++; c }
        assertEquals(1, calls)
        assertEquals((1L..1000L).toList(), r.moved)
    }

    @Test
    fun stopBeforeStartStillMovesFirstChunk() = runBlocking {
        val r = moveInChunks(listOf(1L, 2L), MoveChunk, { true }, { _, _ -> }) { it }
        assertEquals(listOf(1L, 2L), r.moved)
    }

    @Test
    fun failurePartwayKeepsWhatMoved() = runBlocking {
        var calls = 0
        val r = moveInChunks(uids, MoveChunk, { false }, { _, _ -> }) { c ->
            calls++
            if (calls == 2) throw org.dlang.liveimap.session.MailFailure("NO over quota")
            c
        }
        assertEquals((1L..1000L).toList(), r.moved)
        assertEquals("NO over quota", r.failure?.text)
    }
}
