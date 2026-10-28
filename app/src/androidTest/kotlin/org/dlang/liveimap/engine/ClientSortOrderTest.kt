package org.dlang.liveimap.engine

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class ClientSortOrderTest {
    @Test fun tenThousandNumericRowsMatchUidTieBreak() {
        val n = 10_000
        val uids = LongArray(n) { (n - it).toLong() }
        val numbers = LongArray(n) { index ->
            when {
                index % 17 == 0 -> 0L
                else -> (index % 500).toLong()
            }
        }
        val blanks = BooleanArray(n) { it % 113 == 0 }
        val session = LibetpanMailSession(
            InstrumentationRegistry.getInstrumentation().targetContext,
            "11111111-1111-1111-1111-111111111111",
        )
        val got = session.clientSortOrderForTest(uids, numbers, blanks, newestFirst = false)
        val filled = ArrayList<Int>()
        val blankIdx = ArrayList<Int>()
        for (i in 0 until n) {
            if (uids[i] <= 0) continue
            if (blanks[i]) blankIdx.add(i) else filled.add(i)
        }
        filled.sortWith(compareBy<Int> { numbers[it] }.thenBy { uids[it] })
        blankIdx.sortWith(compareBy { uids[it] })
        val expect = LongArray(filled.size + blankIdx.size)
        var k = 0
        for (i in filled) expect[k++] = uids[i]
        for (i in blankIdx) expect[k++] = uids[i]
        assertEquals(expect.size, got.size)
        for (i in expect.indices) assertEquals(expect[i], got[i])
    }
}
