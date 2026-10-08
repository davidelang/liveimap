package org.dlang.liveimap.session

import kotlinx.coroutines.runBlocking
import org.dlang.liveimap.settings.AccountSettings
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

    @Test
    fun freshApplyOpensIdleThenStatus() = runBlocking {
        val link = RecordingLink()
        val apply = ExtraWatchApply()
        apply.apply(WatchPlan(listOf("A", "B"), listOf("C")), link)
        assertEquals(listOf("openIdle A", "openIdle B", "status C"), link.calls)
        assertEquals(listOf("A", "B"), apply.openNames())
    }

    @Test
    fun secondApplyClosesDroppedIdleThenStatus() = runBlocking {
        val link = RecordingLink()
        val apply = ExtraWatchApply()
        apply.apply(WatchPlan(listOf("A", "B"), listOf("C")), link)
        link.calls.clear()
        apply.apply(WatchPlan(listOf("A"), listOf("B", "C")), link)
        assertEquals(listOf("closeIdle B", "status B", "status C"), link.calls)
        assertEquals(listOf("A"), apply.openNames())
    }

    @Test
    fun stopClosesRemainingOpenName() = runBlocking {
        val link = RecordingLink()
        val apply = ExtraWatchApply()
        apply.apply(WatchPlan(listOf("A", "B"), listOf("C")), link)
        apply.apply(WatchPlan(listOf("A"), listOf("B", "C")), link)
        link.calls.clear()
        apply.stop(link)
        assertEquals(listOf("closeIdle A"), link.calls)
        assertEquals(emptyList<String>(), apply.openNames())
    }

    @Test
    fun emptyPlanClosesOpenNamesWithoutStatus() = runBlocking {
        val link = RecordingLink()
        val apply = ExtraWatchApply()
        apply.apply(WatchPlan(listOf("A"), emptyList()), link)
        link.calls.clear()
        apply.apply(WatchPlan(emptyList(), emptyList()), link)
        assertEquals(listOf("closeIdle A"), link.calls)
        assertEquals(emptyList<String>(), apply.openNames())
    }

    @Test
    fun extraWatchPlanUsesStoredListAndBudget() {
        val statusOnly = extraWatchPlan(
            AccountSettings(watchedFolders = listOf("INBOX", "Sent Mail"), extraIdleBudget = 0),
        )
        assertEquals(emptyList<String>(), statusOnly.idle)
        assertEquals(listOf("INBOX", "Sent Mail"), statusOnly.status)

        val split = extraWatchPlan(
            AccountSettings(watchedFolders = listOf("A", "B", "C", "D"), extraIdleBudget = 2),
        )
        assertEquals(listOf("A", "B"), split.idle)
        assertEquals(listOf("C", "D"), split.status)

        val duplicates = extraWatchPlan(
            AccountSettings(watchedFolders = listOf("INBOX", "INBOX", "Sent"), extraIdleBudget = 1),
        )
        assertEquals(listOf("INBOX"), duplicates.idle)
        assertEquals(listOf("Sent"), duplicates.status)
    }

    @Test
    fun folderLinesTrimDropBlanksAndKeepSentMail() {
        assertEquals(
            listOf("A", "B", "Sent Mail"),
            watchedFoldersFromText("A\n B \n\nSent Mail"),
        )
    }

    @Test
    fun repeatedInboxStaysAndEmptyTextIsEmpty() {
        assertEquals(listOf("INBOX", "INBOX"), watchedFoldersFromText("INBOX\nINBOX"))
        assertEquals(emptyList<String>(), watchedFoldersFromText(""))
    }

    @Test
    fun watchedFolderTextUsesOneNamePerLine() {
        assertEquals("A\nSent Mail", watchedFolderText(listOf("A", "Sent Mail")))
    }

    @Test
    fun extraIdleBudgetParsesIntsAndRejectsOtherText() {
        assertEquals(2, extraIdleBudgetFromText("2"))
        assertEquals(0, extraIdleBudgetFromText("0"))
        assertEquals(-1, extraIdleBudgetFromText("-1"))
        assertEquals(null, extraIdleBudgetFromText(""))
        assertEquals(null, extraIdleBudgetFromText("x"))
    }

    @Test
    fun statusOnlyDoesNotOpenIdle() = runBlocking {
        val link = RecordingLink()
        val apply = ExtraWatchApply()
        apply.apply(WatchPlan(emptyList(), listOf("Sent")), link)
        assertEquals(listOf("status Sent"), link.calls)
        assertEquals(emptyList<String>(), apply.openNames())
    }

    private class RecordingLink : ExtraWatchLink {
        val calls = ArrayList<String>()

        override suspend fun openIdle(mailbox: String) {
            calls.add("openIdle $mailbox")
        }

        override suspend fun closeIdle(mailbox: String) {
            calls.add("closeIdle $mailbox")
        }

        override suspend fun status(mailbox: String) {
            calls.add("status $mailbox")
        }
    }
}
