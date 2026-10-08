package org.dlang.liveimap.session

import org.junit.Assert.assertEquals
import org.junit.Test

class WatchedFoldersTest {
    @Test
    fun budgetTwoSplitsIdleAndStatus() {
        val plan = planWatchedFolders(listOf("A", "B", "C", "D"), 2)
        assertEquals(listOf("A", "B"), plan.idle)
        assertEquals(listOf("C", "D"), plan.status)
    }

    @Test
    fun nonPositiveBudgetPutsEveryNameInStatus() {
        val names = listOf("A", "B")
        for (budget in intArrayOf(0, -1)) {
            val plan = planWatchedFolders(names, budget)
            assertEquals(emptyList<String>(), plan.idle)
            assertEquals(names, plan.status)
        }
    }

    @Test
    fun budgetLargerThanListLeavesStatusEmpty() {
        val plan = planWatchedFolders(listOf("A", "B"), 5)
        assertEquals(listOf("A", "B"), plan.idle)
        assertEquals(emptyList<String>(), plan.status)
    }

    @Test
    fun blanksAndLaterDuplicatesDrop() {
        val plan = planWatchedFolders(listOf("", "  ", "INBOX", "INBOX", "Sent"), 1)
        assertEquals(listOf("INBOX"), plan.idle)
        assertEquals(listOf("Sent"), plan.status)
    }

    @Test
    fun emptyInputReturnsTwoEmptyLists() {
        val plan = planWatchedFolders(emptyList(), 1)
        assertEquals(emptyList<String>(), plan.idle)
        assertEquals(emptyList<String>(), plan.status)
    }

    @Test
    fun spacedNameStaysOneName() {
        val plan = planWatchedFolders(listOf("Sent Mail"), 1)
        assertEquals(listOf("Sent Mail"), plan.idle)
        assertEquals(emptyList<String>(), plan.status)
    }
}
