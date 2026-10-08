package org.dlang.liveimap.session

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SortKey
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
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

    @Test
    fun storedNamesAndBudgetTwoOpenAAndBOnly() = runBlocking {
        val made = IdleHolder()
        applyStoredIdle(
            made.sessions,
            AccountSettings(watchedFolders = listOf("A", "B", "C", "D"), extraIdleBudget = 2),
        )
        assertEquals(2, made.records.size)
        assertEquals(listOf("open", "select A", "watch A"), made.records[0].calls)
        assertEquals(listOf("open", "select B", "watch B"), made.records[1].calls)
        assertEquals(listOf("A", "B"), made.sessions.openNames())
    }

    @Test
    fun budgetZeroDoesNotCallTheFactory() = runBlocking {
        val made = IdleHolder()
        applyStoredIdle(
            made.sessions,
            AccountSettings(watchedFolders = listOf("A", "B", "C", "D"), extraIdleBudget = 0),
        )
        assertEquals(0, made.records.size)
        assertEquals(emptyList<String>(), made.sessions.openNames())
    }

    @Test
    fun emptyStoredListDoesNotCallTheFactory() = runBlocking {
        val made = IdleHolder()
        applyStoredIdle(
            made.sessions,
            AccountSettings(watchedFolders = emptyList(), extraIdleBudget = 2),
        )
        assertEquals(0, made.records.size)
        assertEquals(emptyList<String>(), made.sessions.openNames())
    }

    @Test
    fun applyOpensOneSessionPerName() = runBlocking {
        val account = AccountSettings(friendlyName = "extra")
        val made = IdleHolder()
        made.sessions.apply(account, listOf("A", "B"))
        assertEquals(2, made.records.size)
        assertEquals(listOf("open", "select A", "watch A"), made.records[0].calls)
        assertEquals(listOf("open", "select B", "watch B"), made.records[1].calls)
        assertSame(account, made.records[0].opened)
        assertSame(account, made.records[1].opened)
        assertEquals(listOf("A", "B"), made.sessions.openNames())
    }

    @Test
    fun secondApplyClosesOnlyTheDroppedSession() = runBlocking {
        val account = AccountSettings(friendlyName = "extra")
        val made = IdleHolder()
        made.sessions.apply(account, listOf("A", "B"))
        made.sessions.apply(account, listOf("A"))
        assertEquals(2, made.records.size)
        assertEquals(listOf("open", "select A", "watch A"), made.records[0].calls)
        assertEquals(listOf("open", "select B", "watch B", "stopWatch", "close"), made.records[1].calls)
        assertEquals(listOf("A"), made.sessions.openNames())
    }

    @Test
    fun stopClosesTheOpenSession() = runBlocking {
        val made = IdleHolder()
        made.sessions.apply(AccountSettings(), listOf("A"))
        made.sessions.stop()
        assertEquals(listOf("open", "select A", "watch A", "stopWatch", "close"), made.records[0].calls)
        assertEquals(emptyList<String>(), made.sessions.openNames())
    }

    @Test
    fun existsAndExpungeReplaceTheCount() {
        assertEquals(9, folderMessageCount(5, MailboxChange.Exists(9)))
        assertEquals(4, folderMessageCount(5, MailboxChange.Expunge(4)))
        assertEquals(5, folderMessageCount(5, MailboxChange.Flags(1L, setOf("\\Seen"))))
        assertEquals(null, folderMessageCount(null, MailboxChange.WatchLost))
    }

    @Test
    fun watchOfBEmitsExistsNine() = runBlocking {
        val record = IdleRecord()
        record.deliverOnWatch = null
        val sessions = ExtraIdleSessions { record }
        sessions.apply(AccountSettings(), listOf("B"))
        val waiting = async { sessions.changes.first() }
        yield()
        val change = MailboxChange.Exists(9)
        val callback = record.callback ?: return@runBlocking fail("expected watch callback")
        callback(change)
        val emitted = waiting.await()
        assertEquals("B", emitted.mailbox)
        assertEquals(change, emitted.change)
    }

    @Test
    fun emptyApplyClosesWithoutANewSession() = runBlocking {
        val made = IdleHolder()
        made.sessions.apply(AccountSettings(), listOf("A"))
        made.sessions.apply(AccountSettings(), emptyList())
        assertEquals(1, made.records.size)
        assertEquals(listOf("open", "select A", "watch A", "stopWatch", "close"), made.records[0].calls)
        assertEquals(emptyList<String>(), made.sessions.openNames())
    }

    @Test
    fun failedOpenClosesWithoutSelect() = runBlocking {
        val record = IdleRecord()
        record.openResult = OpenResult.Failed("down")
        val sessions = ExtraIdleSessions { record }
        try {
            sessions.apply(AccountSettings(), listOf("A"))
            fail("expected MailFailure")
        } catch (failure: MailFailure) {
            assertEquals("down", failure.text)
        }
        assertEquals(listOf("open", "stopWatch", "close"), record.calls)
        assertEquals(emptyList<String>(), sessions.openNames())
    }

    @Test
    fun rejectedOpenClosesWithoutSelect() = runBlocking {
        val record = IdleRecord()
        record.openResult = OpenResult.Rejected("IDLE")
        val sessions = ExtraIdleSessions { record }
        try {
            sessions.apply(AccountSettings(), listOf("A"))
            fail("expected MailFailure")
        } catch (failure: MailFailure) {
            assertEquals("watch rejected", failure.text)
        }
        assertEquals(listOf("open", "stopWatch", "close"), record.calls)
        assertEquals(emptyList<String>(), sessions.openNames())
    }

    @Test
    fun selectFailureClosesThatSessionAndKeepsTheEarlierName() = runBlocking {
        val boom = MailFailure("missing")
        val first = IdleRecord()
        val second = IdleRecord()
        second.selectFailure = boom
        val queued = arrayListOf(first, second)
        val sessions = ExtraIdleSessions { queued.removeAt(0) }
        sessions.apply(AccountSettings(), listOf("A"))
        try {
            sessions.apply(AccountSettings(), listOf("A", "B"))
            fail("expected MailFailure")
        } catch (failure: MailFailure) {
            assertSame(boom, failure)
        }
        assertEquals(listOf("open", "select A", "watch A"), first.calls)
        assertEquals(listOf("open", "select B", "stopWatch", "close"), second.calls)
        assertEquals(listOf("A"), sessions.openNames())
    }

    @Test
    fun watchFailureClosesThatSessionAndKeepsTheEarlierName() = runBlocking {
        val boom = MailFailure("idle down")
        val first = IdleRecord()
        val second = IdleRecord()
        second.watchFailure = boom
        val queued = arrayListOf(first, second)
        val sessions = ExtraIdleSessions { queued.removeAt(0) }
        sessions.apply(AccountSettings(), listOf("A"))
        try {
            sessions.apply(AccountSettings(), listOf("A", "B"))
            fail("expected MailFailure")
        } catch (failure: MailFailure) {
            assertSame(boom, failure)
        }
        assertEquals(listOf("open", "select A", "watch A"), first.calls)
        assertEquals(listOf("open", "select B", "watch B", "stopWatch", "close"), second.calls)
        assertEquals(listOf("A"), sessions.openNames())
    }

    private class IdleHolder {
        val records = ArrayList<IdleRecord>()
        val sessions = ExtraIdleSessions { IdleRecord().also { records.add(it) } }
    }

    private class IdleRecord : MailSession {
        val calls = ArrayList<String>()
        var opened: AccountSettings? = null
        var openResult: OpenResult = OpenResult.Connected
        var selectFailure: MailFailure? = null
        var watchFailure: MailFailure? = null
        var deliverOnWatch: MailboxChange? = MailboxChange.Exists(0)
        var callback: ((MailboxChange) -> Unit)? = null

        override val capabilities: Set<String> = emptySet()

        override suspend fun open(account: AccountSettings): OpenResult {
            calls.add("open")
            opened = account
            return openResult
        }

        override suspend fun namespaces(): List<Namespace> = unused()

        override suspend fun listLevel(
            prefix: String,
            parentMailbox: String?,
            unreadCounts: Boolean,
        ): List<FolderEntry> = unused()

        override suspend fun select(mailbox: String): SelectResult {
            calls.add("select $mailbox")
            selectFailure?.let { throw it }
            return SelectResult(1, 2, 0)
        }

        override suspend fun unselect() = unused()

        override suspend fun fetchIndex(request: IndexRequest): List<IndexRow> = unused()

        override suspend fun fetchStructure(uid: Long): MimePart = unused()

        override suspend fun peekPart(uid: Long, section: String, offset: Int, length: Int): ByteArray = unused()

        override suspend fun fetchRfc822(uid: Long): ByteArray = unused()

        override suspend fun storeFlags(uids: List<Long>, add: Set<String>, remove: Set<String>) = unused()

        override suspend fun uidExpungeDeleted() = unused()

        override suspend fun copyThenDelete(uids: List<Long>, targetMailbox: String) = unused()

        override suspend fun searchText(query: String): List<Long> = unused()

        override suspend fun searchCriterion(kind: String, argument: String): List<Long> = unused()

        override suspend fun sort(key: SortKey, newestFirst: Boolean): List<Long> = unused()

        override suspend fun thread(key: SortKey): ThreadNode = unused()

        override suspend fun watch(mailbox: String, onChange: (MailboxChange) -> Unit) {
            calls.add("watch $mailbox")
            callback = onChange
            deliverOnWatch?.let(onChange)
            watchFailure?.let { throw it }
        }

        override suspend fun stopWatch() {
            calls.add("stopWatch")
        }

        override suspend fun append(mailbox: String, rfc822: ByteArray, flags: Set<String>) = unused()

        override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>) = unused()

        override fun close() {
            calls.add("close")
        }

        private fun unused(): Nothing = throw MailFailure("not used")
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
