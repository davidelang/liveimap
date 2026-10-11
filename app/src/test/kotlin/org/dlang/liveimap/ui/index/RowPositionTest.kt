package org.dlang.liveimap.ui.index

import org.junit.Assert.assertEquals
import org.junit.Test

class RowPositionTest {
    @Test
    fun numbersAreThePlaceInTheCurrentOrder() {
        assertEquals(1, rowPosition(anchorPage = 0, indexInWindow = 0))
        assertEquals(60, rowPosition(anchorPage = 0, indexInWindow = 59))
        // Window paged to anchor 2: its first row is the 121st in the order, whatever its IMAP sequence number.
        assertEquals(2 * IndexPageSize + 1, rowPosition(anchorPage = 2, indexInWindow = 0))
    }
}
