package org.dlang.liveimap.engine

import org.dlang.liveimap.session.SortField
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class ClientSortKeysTest {
    @Test
    fun numericOrderTiesByUidBlanksLast() {
        val k = ClientSortKeys(2, numeric = true)
        listOf(SortField(5, "", 300, false), SortField(2, "", 100, false), SortField(9, "", 100, false),
            SortField(7, "", 0, true), SortField(0, "", 1, false)).forEach(k::add)
        assertEquals(4, k.size)
        assertArrayEquals(longArrayOf(2, 9, 5, 7), k.order(false))
        assertArrayEquals(longArrayOf(5, 9, 2, 7), k.order(true))
    }

    @Test
    fun textOrderIsCaseInsensitiveAndGrows() {
        val k = ClientSortKeys(1, numeric = false)
        listOf(SortField(1, "beta", 0, false), SortField(2, "Alpha", 0, false), SortField(3, "", 0, true))
            .forEach(k::add)
        assertArrayEquals(longArrayOf(2, 1, 3), k.order(false))
    }

    @Test
    fun manyRowsStayPrimitive() {
        val k = ClientSortKeys(10, numeric = true)
        for (i in 1..20_000) k.add(SortField(i.toLong(), "", (20_000 - i).toLong(), false))
        val o = k.order(false)
        assertEquals(20_000L, o.first())
        assertEquals(1L, o.last())
    }
}
