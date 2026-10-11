package org.dlang.liveimap.ui.index

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WindowedOrderTest {
    private val all = (1L..1000L).toList()
    private var calls = 0
    private fun order() = WindowedOrder(all.size, 100) { lo, hi -> calls += 1; all.subList(lo - 1, hi) }

    @Test
    fun unknownUntilLoadedThenExact() = runBlocking {
        val o = order()
        assertEquals(1000, o.size)
        assertEquals(WindowedOrder.Unknown, o[550])
        o.ensure(540, 30)
        assertEquals(551L, o[550])
        assertEquals(1, calls)
        o.ensure(590, 20) // spans windows 5 and 6; 5 is cached
        assertEquals(2, calls)
        assertEquals(610L, o[609])
    }

    @Test
    fun loadsEachWindowOnce() = runBlocking {
        val o = order()
        repeat(3) { o.ensure(0, 200) }
        assertEquals(2, calls)
        assertEquals((1L..200L).toList(), o.known())
    }

    @Test
    fun lastWindowIsShort() = runBlocking {
        val o = WindowedOrder(250, 100) { lo, hi -> (lo.toLong()..hi.toLong()).toList() }
        o.ensure(240, 50)
        assertEquals(250L, o[249])
    }

    @Test
    fun removalShrinksAndForgets() = runBlocking {
        val o = order()
        o.ensure(0, 100)
        val after = orderWithout(o, setOf(5L, 6L)) as WindowedOrder
        assertEquals(998, after.size)
        assertEquals(0, after.loadedWindows())
    }

    @Test
    fun arrivalGrowsAndForgets() {
        val after = orderWith(order(), listOf(1001L), front = true) as WindowedOrder
        assertEquals(1001, after.size)
        assertEquals(listOf(9L, 1L, 2L), orderWith(listOf(1L, 2L), listOf(9L), front = true))
    }

    @Test
    fun newestFirstSearchPositions() {
        assertEquals(901 to 1000, searchWindowRange(1, 100, 1000, true))
        assertEquals(1 to 100, searchWindowRange(901, 1000, 1000, true))
        assertEquals(1 to 100, searchWindowRange(1, 100, 1000, false))
    }
}
