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

/** F116/F124/F126/F127: a select + UID command pair can't be split by another caller. */
class SelectionPinTest {
    @Test
    fun anotherCallerWaitsUntilThePinnedBlockEnds() = runBlocking {
        val inner = PinInner()
        val session = SerialMailSession(inner)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pinned = async {
            session.pinned {
                session.select("Drafts")
                entered.complete(Unit)
                release.await()
                session.fetchRfc822(5L)
            }
        }
        entered.await()
        val other = async { session.select("INBOX") }
        Thread.sleep(100)
        assertFalse("other select ran inside the pinned block", other.isCompleted)
        release.complete(Unit)
        withTimeout(5000) { pinned.await(); other.await() }
        assertEquals(listOf("select Drafts", "fetch 5 in Drafts", "select INBOX"), inner.log)
        session.close()
    }

    @Test
    fun pinIsReentrant() = runBlocking {
        val inner = PinInner()
        val session = SerialMailSession(inner)
        withTimeout(5000) {
            session.pinned { session.pinned { session.select("A") } }
        }
        assertEquals(listOf("select A"), inner.log)
        session.close()
    }

    @Test
    fun composeAndReaderPinTheirSourceFolder() {
        val compose = File("src/main/kotlin/org/dlang/liveimap/ui/compose/ComposeScreen.kt").readText()
        assertTrue(compose.contains("inBox(box) { session.fetchRfc822(uid) }"))
        assertTrue(compose.contains("inBox(source) { session.peekPart("))
        val reader = File("src/main/kotlin/org/dlang/liveimap/ui/reader/MessageReaderScreen.kt").readText()
        assertTrue(reader.contains("Triple(e, session.copiedEpoch(), session.takeCopiedUids())"))
    }
}

private class PinInner : MailSession by DisconnectedMailSession() {
    val log = ArrayList<String>()
    private var selected = ""
    override suspend fun select(mailbox: String): SelectResult {
        selected = mailbox
        log += "select $mailbox"
        return SelectResult(uidValidity = 1L, uidNext = 2L, exists = 1)
    }
    override suspend fun fetchRfc822(uid: Long): ByteArray {
        log += "fetch $uid in $selected"
        return ByteArray(0)
    }
}
