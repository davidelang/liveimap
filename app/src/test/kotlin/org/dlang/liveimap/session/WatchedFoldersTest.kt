package org.dlang.liveimap.session

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SortKey
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

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
        assertEquals(null, extraIdleBudgetFromText("-1")) // F81: 0..MaxExtraIdle only
        assertEquals(null, extraIdleBudgetFromText(""))
        assertEquals(null, extraIdleBudgetFromText("x"))
    }








    @Test
    fun existsAndExpungeReplaceTheCount() {
        assertEquals(9, folderMessageCount(5, MailboxChange.Exists(9)))
        assertEquals(4, folderMessageCount(5, MailboxChange.Expunge(4)))
        assertEquals(5, folderMessageCount(5, MailboxChange.Flags(1L, setOf("\\Seen"))))
        assertEquals(null, folderMessageCount(null, MailboxChange.WatchLost))
    }



    @Test
    fun coalescerKeepsLatestCountPerFolderForLargeBursts() {
        val c = IdleChangeCoalescer()
        for (n in 1..5000) c.offer(IdleFolderChange("A", MailboxChange.Exists(n)))
        c.offer(IdleFolderChange("B", MailboxChange.Expunge(7)))
        c.offer(IdleFolderChange("A", MailboxChange.Flags(1, setOf("\\Seen"))))
        c.offer(IdleFolderChange("A", MailboxChange.Reconnected))
        c.offer(IdleFolderChange("A", MailboxChange.Expunge(4999)))
        assertEquals(
            listOf(
                IdleFolderChange("B", MailboxChange.Expunge(7)),
                IdleFolderChange("A", MailboxChange.Reconnected),
                IdleFolderChange("A", MailboxChange.Expunge(4999)),
            ),
            c.drain(),
        )
        assertEquals(emptyList<IdleFolderChange>(), c.drain())
    }

    @Test
    fun coalescerKeepsTheSameFolderOnTwoAccounts() {
        val c = IdleChangeCoalescer()
        c.offer(IdleFolderChange("INBOX", MailboxChange.Exists(1), "a"))
        c.offer(IdleFolderChange("INBOX", MailboxChange.Exists(2), "b"))
        c.offer(IdleFolderChange("INBOX", MailboxChange.Exists(4), "a"))
        assertEquals(
            listOf(
                IdleFolderChange("INBOX", MailboxChange.Exists(2), "b"),
                IdleFolderChange("INBOX", MailboxChange.Exists(4), "a"),
            ),
            c.drain(),
        )
    }

    @Test
    fun coalescerFlowDeliversAfterBurst() = runBlocking {
        val c = IdleChangeCoalescer()
        for (n in 1..100) c.offer(IdleFolderChange("A", MailboxChange.Exists(n)))
        assertEquals(IdleFolderChange("A", MailboxChange.Exists(100)), c.changes.first())
    }
}
