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
        assertEquals(null, extraIdleBudgetFromText("-1")) // F81: 0..MaxExtraIdle only
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
            "acct",
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
            "acct",
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
            "acct",
        )
        assertEquals(0, made.records.size)
        assertEquals(emptyList<String>(), made.sessions.openNames())
    }

    @Test
    fun applyWithBlankAccountIdOpensNothing() = runBlocking {
        val made = IdleHolder()
        made.sessions.apply(AccountSettings(watchedFolders = listOf("A"), extraIdleBudget = 2), listOf("A"), "")
        assertEquals(0, made.records.size)
        assertEquals(emptyList<String>(), made.sessions.openNames())
    }

    @Test
    fun applyOpensOneSessionPerName() = runBlocking {
        val account = AccountSettings(friendlyName = "extra")
        val made = IdleHolder()
        made.sessions.apply(account, listOf("A", "B"), "acct")
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
        made.sessions.apply(account, listOf("A", "B"), "acct")
        made.sessions.apply(account, listOf("A"), "acct")
        assertEquals(2, made.records.size)
        assertEquals(listOf("open", "select A", "watch A"), made.records[0].calls)
        assertEquals(listOf("open", "select B", "watch B", "stopWatch", "close"), made.records[1].calls)
        assertEquals(listOf("A"), made.sessions.openNames())
    }

    @Test
    fun stopClosesTheOpenSession() = runBlocking {
        val made = IdleHolder()
        made.sessions.apply(AccountSettings(), listOf("A"), "acct")
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
    fun twoAccountsWatchingTheSameFolderAreTwoSessions() = runBlocking {
        val records = ArrayList<IdleRecord>()
        val sessions = ExtraIdleSessions {
            IdleRecord().also {
                it.deliverOnWatch = null
                records.add(it)
            }
        }
        val accountA = AccountSettings(friendlyName = "A")
        val accountB = AccountSettings(friendlyName = "B")
        sessions.apply(accountA, listOf("INBOX"), "acct-a")
        sessions.apply(accountB, listOf("INBOX"), "acct-b")
        assertEquals(2, records.size)
        assertEquals(listOf("INBOX", "INBOX"), sessions.openNames())
        assertSame(accountA, records[0].opened)
        assertSame(accountB, records[1].opened)
        val waiting = async { sessions.changes.first() }
        yield()
        records[0].callback?.invoke(MailboxChange.Exists(3))
        val emitted = waiting.await()
        assertEquals("acct-a", emitted.accountId)
        assertEquals("INBOX", emitted.mailbox)
        assertEquals(MailboxChange.Exists(3), emitted.change)
    }

    @Test
    fun watchOfBEmitsExistsNine() = runBlocking {
        val record = IdleRecord()
        record.deliverOnWatch = null
        val sessions = ExtraIdleSessions { record }
        sessions.apply(AccountSettings(), listOf("B"), "acct")
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
        made.sessions.apply(AccountSettings(), listOf("A"), "acct")
        made.sessions.apply(AccountSettings(), emptyList(), "acct")
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
            sessions.apply(AccountSettings(), listOf("A"), "acct")
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
            sessions.apply(AccountSettings(), listOf("A"), "acct")
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
        sessions.apply(AccountSettings(), listOf("A"), "acct")
        try {
            sessions.apply(AccountSettings(), listOf("A", "B"), "acct")
            fail("expected MailFailure")
        } catch (failure: MailFailure) {
            assertSame(boom, failure)
        }
        assertEquals(listOf("open", "select A", "watch A"), first.calls)
        assertEquals(listOf("open", "select B", "stopWatch", "close"), second.calls)
        assertEquals(listOf("A"), sessions.openNames())
    }

    @Test
    fun firstSessionWithoutIdleMarksTheRestWithoutOpening() = runBlocking {
        val account = AccountSettings(imapHost = "no-idle.example", username = "skip", imapPort = 993)
        val skipFirst = IdleRecord()
        skipFirst.capabilities = setOf("IMAP4REV1")
        val openedNext = IdleRecord()
        val queued = arrayListOf(skipFirst, openedNext)
        val sessions = ExtraIdleSessions { queued.removeAt(0) }
        sessions.apply(account, listOf("A", "B"), "acct")
        assertEquals(listOf("open", "stopWatch", "close"), skipFirst.calls)
        assertEquals(emptyList<String>(), openedNext.calls)
        assertEquals(emptyList<String>(), sessions.openNames())
        val planned = extraWatchPlan(account.copy(watchedFolders = listOf("A", "B", "C"), extraIdleBudget = 2))
        assertEquals(emptyList<String>(), planned.idle)
        assertEquals(listOf("C", "A", "B"), planned.status)
        val dropped = extraWatchPlan(account.copy(watchedFolders = listOf("B", "C"), extraIdleBudget = 2))
        assertEquals(listOf("C"), dropped.idle)
        assertEquals(listOf("B"), dropped.status)
        val otherHost = extraWatchPlan(
            AccountSettings(
                imapHost = "other.example",
                username = "skip",
                imapPort = 993,
                watchedFolders = listOf("A", "B", "C"),
                extraIdleBudget = 2,
            ),
        )
        assertEquals(listOf("A", "B"), otherHost.idle)
        assertEquals(listOf("C"), otherHost.status)

        val keep = IdleRecord()
        val skip = IdleRecord()
        skip.capabilities = setOf("IMAP4REV1")
        val next = IdleRecord()
        val more = arrayListOf(keep, skip, next)
        val again = ExtraIdleSessions { more.removeAt(0) }
        again.apply(account, listOf("Keep", "Skip", "Next"), "acct")
        assertEquals(listOf("open", "select Keep", "watch Keep"), keep.calls)
        assertEquals(listOf("open", "stopWatch", "close"), skip.calls)
        assertEquals(listOf("open", "select Next", "watch Next"), next.calls)
        assertEquals(listOf("Keep", "Next"), again.openNames())
        val after = extraWatchPlan(
            account.copy(watchedFolders = listOf("Keep", "Skip", "Next"), extraIdleBudget = 3),
        )
        assertEquals(listOf("Keep", "Next"), after.idle)
        assertEquals(listOf("Skip"), after.status)
    }

    @Test
    fun jmapFoldersWithoutImapIdleStaysOpenAndIsNotAStatusName() = runBlocking {
        val account = AccountSettings(
            imapHost = "jmap.example",
            username = "jmap",
            imapPort = 993,
            jmapFolders = true,
            watchedFolders = listOf("A", "B"),
            extraIdleBudget = 2,
        )
        val record = IdleRecord()
        record.capabilities = setOf("IMAP4REV1")
        val sessions = ExtraIdleSessions { record }
        sessions.apply(account, listOf("A"), "acct")
        assertEquals(false, record.featureCaps.idle)
        assertEquals(listOf("open"), record.calls)
        assertEquals(listOf("A"), sessions.openNames())
        val planned = extraWatchPlan(account)
        assertEquals(listOf("A", "B"), planned.idle)
        assertEquals(emptyList<String>(), planned.status)
    }

    @Test
    fun unhidingIdleReturnsTheFolderToTheIdlePlan() = runBlocking {
        val hidden = AccountSettings(
            imapHost = "hide.example",
            username = "u",
            imapPort = 993,
            hiddenCapabilities = setOf("IDLE"),
            watchedFolders = listOf("A"),
            extraIdleBudget = 1,
        )
        val first = IdleHolder()
        applyStoredIdle(first.sessions, hidden, "acct")
        assertEquals(0, first.records.size)
        assertEquals(emptyList<String>(), first.sessions.openNames())
        assertEquals(emptyList<String>(), extraWatchPlan(hidden).idle)
        assertEquals(listOf("A"), extraWatchPlan(hidden).status)

        val shown = hidden.copy(hiddenCapabilities = emptySet())
        val second = IdleHolder()
        applyStoredIdle(second.sessions, shown, "acct")
        assertEquals(listOf("A"), second.sessions.openNames())
        assertEquals(listOf("open", "select A", "watch A"), second.records[0].calls)
        assertEquals(listOf("A"), extraWatchPlan(shown).idle)
        assertEquals(emptyList<String>(), extraWatchPlan(shown).status)
    }

    @Test
    fun laterIdleCapabilityReturnsTheFolderToTheIdlePlan() = runBlocking {
        val account = AccountSettings(
            imapHost = "later.example",
            username = "u",
            imapPort = 993,
            watchedFolders = listOf("A"),
            extraIdleBudget = 1,
        )
        val missing = IdleRecord()
        missing.capabilities = setOf("IMAP4REV1")
        val first = ExtraIdleSessions { missing }
        applyStoredIdle(first, account, "acct")
        assertEquals(emptyList<String>(), first.openNames())
        assertEquals(listOf("A"), extraWatchPlan(account).status)

        val gained = IdleRecord()
        val second = ExtraIdleSessions { gained }
        applyStoredIdle(second, account, "acct")
        assertEquals(listOf("open", "select A", "watch A"), gained.calls)
        assertEquals(listOf("A"), second.openNames())
        assertEquals(listOf("A"), extraWatchPlan(account).idle)
        assertEquals(emptyList<String>(), extraWatchPlan(account).status)
    }

    @Test
    fun thrownWatchOnlyKeepsANoIdleStatusNameUntilIdleReturns() = runBlocking {
        val hidden = AccountSettings(
            imapHost = "keep.example",
            username = "u",
            imapPort = 993,
            hiddenCapabilities = setOf("IDLE"),
            watchedFolders = listOf("A"),
            extraIdleBudget = 1,
        )
        val boom = MailFailure("watch down")
        val failing = IdleRecord()
        failing.watchFailure = boom
        val restored = IdleRecord()
        val queued = arrayListOf(failing, restored)
        val sessions = ExtraIdleSessions { queued.removeAt(0) }
        applyStoredIdle(sessions, hidden, "acct")
        assertEquals(2, queued.size)
        assertEquals(emptyList<String>(), sessions.openNames())
        assertEquals(emptyList<String>(), extraWatchPlan(hidden).idle)
        assertEquals(listOf("A"), extraWatchPlan(hidden).status)

        val shown = hidden.copy(hiddenCapabilities = emptySet())
        try {
            applyStoredIdle(sessions, shown, "acct")
            fail("expected MailFailure")
        } catch (failure: MailFailure) {
            assertSame(boom, failure)
        }
        assertEquals(listOf("open", "select A", "watch A", "stopWatch", "close"), failing.calls)
        assertEquals(emptyList<String>(), sessions.openNames())
        assertEquals(emptyList<String>(), extraWatchPlan(shown).idle)
        assertEquals(listOf("A"), extraWatchPlan(shown).status)

        applyStoredIdle(sessions, shown, "acct")
        assertEquals(listOf("open", "select A", "watch A"), restored.calls)
        assertEquals(listOf("A"), sessions.openNames())
        assertEquals(listOf("A"), extraWatchPlan(shown).idle)
        assertEquals(emptyList<String>(), extraWatchPlan(shown).status)
    }

    @Test
    fun watchFailureClosesThatSessionAndKeepsTheEarlierName() = runBlocking {
        val boom = MailFailure("idle down")
        val first = IdleRecord()
        val second = IdleRecord()
        second.watchFailure = boom
        val queued = arrayListOf(first, second)
        val sessions = ExtraIdleSessions { queued.removeAt(0) }
        sessions.apply(AccountSettings(), listOf("A"), "acct")
        try {
            sessions.apply(AccountSettings(), listOf("A", "B"), "acct")
            fail("expected MailFailure")
        } catch (failure: MailFailure) {
            assertSame(boom, failure)
        }
        assertEquals(listOf("open", "select A", "watch A"), first.calls)
        assertEquals(listOf("open", "select B", "watch B", "stopWatch", "close"), second.calls)
        assertEquals(listOf("A"), sessions.openNames())
    }

    @Test
    fun droppingOneAccountClosesOnlyThatAccount() = runBlocking {
        val records = ArrayList<IdleRecord>()
        val sessions = ExtraIdleSessions {
            IdleRecord().also {
                it.deliverOnWatch = null
                records.add(it)
            }
        }
        val accountA = AccountSettings(imapHost = "drop-a.example", username = "a", imapPort = 993)
        val accountB = AccountSettings(imapHost = "drop-b.example", username = "b", imapPort = 993)
        sessions.apply(accountA, listOf("INBOX"), "acct-a")
        sessions.apply(accountB, listOf("INBOX"), "acct-b")
        records[0].callback?.invoke(MailboxChange.Exists(1))
        sessions.closeAccount("acct-a")
        assertEquals(listOf("open", "select INBOX", "watch INBOX", "stopWatch", "close"), records[0].calls)
        assertEquals(listOf("open", "select INBOX", "watch INBOX"), records[1].calls)
        assertEquals(listOf("INBOX"), sessions.openNames())
        val waiting = async { sessions.changes.first() }
        yield()
        records[0].callback?.invoke(MailboxChange.Exists(8))
        records[1].callback?.invoke(MailboxChange.Exists(4))
        val emitted = waiting.await()
        assertEquals("acct-b", emitted.accountId)
        assertEquals(MailboxChange.Exists(4), emitted.change)
    }

    @Test
    fun staleApplyOpensNothing() = runBlocking {
        val made = IdleHolder()
        val seen = made.sessions.readGeneration()
        val gate = Mutex(locked = true)
        val waiting = launch {
            gate.withLock {
                applyStoredIdle(
                    made.sessions,
                    AccountSettings(watchedFolders = listOf("A", "B"), extraIdleBudget = 2),
                    "acct",
                    seen,
                )
            }
        }
        yield()
        made.sessions.bumpGeneration()
        gate.unlock()
        waiting.join()
        assertEquals(0, made.records.size)
        assertEquals(emptyList<String>(), made.sessions.openNames())
    }

    @Test
    fun stopThenStartLeavesTheStartSessionsOpen() = runBlocking {
        val made = IdleHolder()
        val lock = Mutex()
        val gate = Mutex(locked = true)
        val before = made.sessions.readGeneration()
        val account = AccountSettings(watchedFolders = listOf("A", "B"), extraIdleBudget = 2)
        val stop = launchExtraIdleStop(this, made.sessions, lock) {
            gate.withLock { }
        }
        assertFalse(made.sessions.generationStill(before))
        val start = launch {
            runAfterExtraIdleStop(stop) {
                applyExtraIdleTo(made.sessions, lock, account, "acct", ExtraIdleDropLog())
            }
        }
        var held = true
        try {
            yield()
            assertEquals(emptyList<String>(), made.sessions.openNames())
            gate.unlock()
            held = false
            start.join()
            assertEquals(listOf("A", "B"), made.sessions.openNames())
            assertEquals(listOf("open", "select A", "watch A"), made.records[0].calls)
            assertEquals(listOf("open", "select B", "watch B"), made.records[1].calls)
        } finally {
            if (held) gate.unlock()
        }
    }

    @Test
    fun waitingApplyForADroppedAccountOpensNothing() = runBlocking {
        val made = IdleHolder()
        val lock = Mutex(locked = true)
        val drops = ExtraIdleDropLog()
        val account = AccountSettings(
            imapHost = "drop-wait.example",
            username = "a",
            imapPort = 993,
            watchedFolders = listOf("INBOX"),
            extraIdleBudget = 2,
        )
        val waiting = launch {
            applyExtraIdleTo(made.sessions, lock, account, "acct-a", drops)
        }
        repeat(3) { yield() }
        recordDroppedExtraIdle(made.sessions, "acct-a", drops)
        lock.unlock()
        waiting.join()
        assertEquals(0, made.records.size)
        assertEquals(emptyList<String>(), made.sessions.openNames())
        applyExtraIdleTo(made.sessions, lock, account, "acct-a", drops)
        assertEquals(listOf("INBOX"), made.sessions.openNames())
    }

    @Test
    fun hiddenIdleOpensNoSession() = runBlocking {
        val hidden = AccountSettings(
            imapHost = "hide-none.example",
            username = "u",
            imapPort = 993,
            hiddenCapabilities = setOf("IDLE"),
            watchedFolders = listOf("A", "B", "C"),
            extraIdleBudget = 3,
        )
        val made = IdleHolder()
        applyStoredIdle(made.sessions, hidden, "acct")
        assertEquals(0, made.records.size)
        assertEquals(emptyList<String>(), made.sessions.openNames())
        val planned = extraWatchPlan(hidden)
        assertEquals(emptyList<String>(), planned.idle)
        assertEquals(listOf("A", "B", "C"), planned.status)
    }

    @Test
    fun serverWithoutIdleOpensOneSessionForSeveralNames() = runBlocking {
        val account = AccountSettings(
            imapHost = "probe-once.example",
            username = "u",
            imapPort = 993,
            watchedFolders = listOf("A", "B", "C"),
            extraIdleBudget = 3,
        )
        val probe = IdleRecord()
        probe.capabilities = setOf("IMAP4REV1")
        var calls = 0
        val sessions = ExtraIdleSessions {
            calls += 1
            probe
        }
        applyStoredIdle(sessions, account, "acct")
        assertEquals(1, calls)
        assertEquals(listOf("open", "stopWatch", "close"), probe.calls)
        assertEquals(emptyList<String>(), sessions.openNames())
        val planned = extraWatchPlan(account)
        assertEquals(emptyList<String>(), planned.idle)
        assertEquals(listOf("A", "B", "C"), planned.status)

        val opened = ArrayList<IdleRecord>()
        val again = ExtraIdleSessions { IdleRecord().also { opened.add(it) } }
        applyStoredIdle(again, account, "acct")
        assertEquals(3, opened.size)
        assertEquals(listOf("A", "B", "C"), again.openNames())
        assertEquals(listOf("A", "B", "C"), extraWatchPlan(account).idle)
        assertEquals(emptyList<String>(), extraWatchPlan(account).status)
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

        override var capabilities: Set<String> = setOf("IMAP4REV1", "IDLE")

        override suspend fun watchOnly(account: AccountSettings, mailbox: String, onChange: (MailboxChange) -> Unit) {
            if (!featureCaps.idle) {
                calls.add("open")
                opened = account
                capabilities = emptySet()
                return
            }
            super.watchOnly(account, mailbox, onChange)
        }

        override suspend fun statusMessages(mailboxes: List<String>): Map<String, Int> {
            for (name in mailboxes) calls.add("status $name")
            return emptyMap()
        }

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

        override suspend fun smtpSend(rfc822: ByteArray, recipients: List<String>, dsnNotify: Int): Int = unused()

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
