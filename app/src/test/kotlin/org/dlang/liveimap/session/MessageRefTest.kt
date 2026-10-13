package org.dlang.liveimap.session

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageRefTest {
    /** A MailSession whose select() answers from [validity]; every other call is recorded and fails. */
    private class Recorder(val validity: Map<String, Long>) {
        val selects = ArrayList<String>()
        val session: MailSession = object : MailSession by DisconnectedMailSession() {
            override suspend fun select(mailbox: String): SelectResult {
                selects += mailbox
                val v = validity[mailbox] ?: throw MailFailure("no such folder")
                return SelectResult(v, 1, 0)
            }
            override suspend fun unselect() {}
            override suspend fun <T> inFolder(folder: FolderAt, fresh: Boolean, op: suspend (SelectResult, MailSession) -> T): T =
                op(selectFolderNow(this, folder, fresh), this)
        }
    }

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

    @Test fun crossFolderSearchHitsActInTheirOwnFolders() {
        val r = Recorder(mapOf("INBOX" to 1L, "Archive/2019" to 7L))
        val acted = ArrayList<Pair<String, List<Long>>>()
        val refs = listOf(
            MessageRef("a1", "Archive/2019", 7, 4711),
            MessageRef("a1", "INBOX", 1, 4711),
            MessageRef("a1", "Archive/2019", 7, 12),
        )
        run { r.session.onRefs("a1", refs) { _, mb, uids -> acted += mb to uids } }
        assertEquals(listOf("Archive/2019" to listOf(4711L, 12L), "INBOX" to listOf(4711L)), acted)
    }

    @Test fun wrongAccountIsRefusedBeforeAnyIo() {
        val r = Recorder(mapOf("INBOX" to 1L))
        val failure = runCatching {
            run { r.session.onRefs("a1", listOf(MessageRef("a2", "INBOX", 1, 5))) { _, _, _ -> } }
        }.exceptionOrNull()
        assertTrue(failure is MailFailure)
        assertTrue(r.selects.isEmpty())
    }

    @Test fun uidValidityMismatchFailsSafely() {
        val r = Recorder(mapOf("INBOX" to 1L, "Archive" to 99L))
        var acted = false
        val failure = runCatching {
            run { r.session.onRefs("a1", listOf(MessageRef("a1", "Archive", 7, 5)), ) { _, _, _ -> acted = true } }
        }.exceptionOrNull()
        assertTrue(failure is MailFailure)
        assertTrue(!acted)
    }

    @Test fun unknownEpochIsRefused() {
        val r = Recorder(mapOf("INBOX" to 1L))
        assertTrue(runCatching { run { r.session.onRefs("a1", listOf(MessageRef("a1", "INBOX", 0, 5))) { _, _, _ -> } } }.isFailure)
        assertTrue(r.selects.isEmpty())
    }

    @Test
    fun sameMailboxFoldsOnlyInbox() {
        assertTrue(sameMailbox("INBOX", "inbox"))
        assertTrue(sameMailbox("Spam", "Spam"))
        assertTrue(!sameMailbox("Spam", "spam"))
    }

    @Test
    fun chooserHidesTheCurrentFolder() {
        val rows = listOf("INBOX", "Archive", "Spam")
        org.junit.Assert.assertEquals(listOf("Archive", "Spam"), org.dlang.liveimap.ui.folder.chooserRows(rows, "inbox") { it })
        org.junit.Assert.assertEquals(rows, org.dlang.liveimap.ui.folder.chooserRows(rows, "") { it })
    }

    @Test
    fun composeSeedCarriesRefs() {
        val seed = ComposeSeed(ComposeKind.Reply, "Archive", listOf(4L), uidValidity = 9L)
        org.junit.Assert.assertEquals(listOf(MessageRef("a", "Archive", 9L, 4L)), seed.refs("a"))
        assertTrue(ComposeSeed(ComposeKind.Reply, "Archive", listOf(4L)).refs("a").isEmpty())
    }
}
