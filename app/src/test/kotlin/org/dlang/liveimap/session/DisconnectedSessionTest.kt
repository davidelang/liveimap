package org.dlang.liveimap.session

import org.dlang.liveimap.settings.AccountSettings
import org.dlang.liveimap.settings.SortKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class DisconnectedSessionTest {
    @Test
    fun openFailedTextIsNotConnected() {
        val session = DisconnectedMailSession()
        assertTrue(session is DisconnectedMailSession)
        assertTrue(session.capabilities.isEmpty())
        val result = runImmediate { session.open(AccountSettings()) }
        assertTrue(result is OpenResult.Failed)
        assertEquals("not connected", (result as OpenResult.Failed).text)
        session.close()
    }

    @Test
    fun otherMethodsThrowNotConnected() {
        val session = DisconnectedMailSession()
        assertThrowsNotConnected { runImmediate { session.namespaces() } }
        assertThrowsNotConnected { runImmediate { session.listLevel("", null, false) } }
        assertThrowsNotConnected { runImmediate { session.select("INBOX") } }
        assertThrowsNotConnected { runImmediate { session.unselect() } }
        assertThrowsNotConnected {
            runImmediate { session.fetchIndex(IndexRequest("INBOX", IndexMode.ArrivalNewest)) }
        }
        assertThrowsNotConnected { runImmediate { session.fetchStructure(1L) } }
        assertThrowsNotConnected { runImmediate { session.peekPart(1L, "1", 0, 1) } }
        assertThrowsNotConnected { runImmediate { session.fetchRfc822(1L) } }
        assertThrowsNotConnected {
            runImmediate { session.storeFlags(listOf(1L), emptySet(), emptySet()) }
        }
        assertThrowsNotConnected { runImmediate { session.uidExpungeDeleted() } }
        assertThrowsNotConnected { runImmediate { session.copyThenDelete(listOf(1L), "Trash") } }
        assertThrowsNotConnected { runImmediate { session.searchText("hi") } }
        assertThrowsNotConnected { runImmediate { session.sort(SortKey.Arrival, true) } }
        assertThrowsNotConnected { runImmediate { session.thread(SortKey.ThreadReferences) } }
        assertThrowsNotConnected { runImmediate { session.watch("INBOX") { } } }
        assertThrowsNotConnected { runImmediate { session.stopWatch() } }
        assertThrowsNotConnected { runImmediate { session.append("INBOX", byteArrayOf()) } }
        assertThrowsNotConnected { runImmediate { session.smtpSend(byteArrayOf(), emptyList()) } }
        session.close()
    }

    private fun assertThrowsNotConnected(block: () -> Unit) {
        try {
            block()
        } catch (e: MailFailure) {
            assertEquals("not connected", e.text)
            return
        }
        fail("expected MailFailure")
    }
}

private fun <T> runImmediate(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
    return result!!.getOrThrow()
}
