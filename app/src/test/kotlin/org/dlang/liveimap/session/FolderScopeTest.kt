package org.dlang.liveimap.session

import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pass 5 (David, 2026-10-12): every folder operation names its folder and runs as one lowest-layer call. */
class FolderScopeTest {
    @Test
    fun anotherCallerWaitsUntilTheFolderCallEnds() = runBlocking {
        val inner = ScopeInner()
        val session = SerialMailSession(inner)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            session.inFolder(FolderAt("Drafts", 1L)) { _, s ->
                entered.complete(Unit)
                release.await()
                s.fetchRfc822(5L)
            }
        }
        entered.await()
        val other = async { session.folderInfo(FolderAt("INBOX")) }
        Thread.sleep(100)
        assertFalse("another folder call ran inside the first", other.isCompleted)
        release.complete(Unit)
        withTimeout(5000) { first.await(); other.await() }
        assertEquals(listOf("select Drafts", "fetch 5 in Drafts", "unselect", "select INBOX"), inner.log)
        session.close()
    }

    @Test
    fun epochMismatchSendsNothing() = runBlocking {
        val inner = ScopeInner()
        val session = SerialMailSession(inner)
        val r = runCatching { session.inFolder(FolderAt("Drafts", 99L)) { _, s -> s.fetchRfc822(5L) } }
        assertTrue(r.exceptionOrNull() is EpochChanged)
        assertEquals(listOf("select Drafts"), inner.log)
        session.close()
    }

    @Test
    fun bareFolderCallsAreRefused() = runBlocking {
        val session = SerialMailSession(ScopeInner())
        assertTrue(runCatching { session.select("INBOX") }.isFailure)
        assertTrue(runCatching { session.fetchRfc822(1L) }.isFailure)
        assertTrue(runCatching { session.storeFlags(listOf(1L), setOf("\\Seen"), emptySet()) }.isFailure)
        session.close()
    }

    @Test
    fun reentryIsRefusedNotDeadlocked() = runBlocking {
        val session = SerialMailSession(ScopeInner())
        val r = withTimeout(5000) {
            runCatching { session.inFolder(FolderAt("A")) { _, _ -> session.folderInfo(FolderAt("B")) } }
        }
        assertTrue(r.isFailure)
        session.close()
    }

    @Test
    fun uidViewNeedsAnEpoch() = runBlocking {
        val session = SerialMailSession(ScopeInner())
        assertTrue(runCatching { session.at(FolderAt("INBOX")).fetchRfc822(1L) }.isFailure)
        assertEquals(0, session.at(FolderAt("INBOX", 1L)).fetchRfc822(1L).size)
        session.close()
    }

    /** Guard: no UI code may select or run folder commands outside a folder scope. */
    @Test
    fun uiHasNoBareSelects() {
        val bare = Regex("""\bsession\.(select|unselect|selectChecked|pinned)\b""")
        val offenders = File("src/main/kotlin/org/dlang/liveimap/ui").walk().filter { it.extension == "kt" }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (bare.containsMatchIn(l)) "${f.name}:${i + 1}" else null } }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }
}

private class ScopeInner : MailSession by DisconnectedMailSession() {
    val log = ArrayList<String>()
    private var selected = ""
    override suspend fun select(mailbox: String): SelectResult {
        selected = mailbox
        log += "select $mailbox"
        return SelectResult(uidValidity = 1L, uidNext = 2L, exists = 1)
    }
    override suspend fun unselect() { log += "unselect" }
    override suspend fun fetchRfc822(uid: Long): ByteArray {
        log += "fetch $uid in $selected"
        return ByteArray(0)
    }
}
