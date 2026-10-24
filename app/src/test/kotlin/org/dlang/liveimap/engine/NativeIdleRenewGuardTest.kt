package org.dlang.liveimap.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * IDLE renew: Courier reports a new message only between DONE and the tagged OK. libetpan's mailimap_idle_done()
 * parses that response into selection info, so the EXISTS never reached handleUntagged and the watch missed the
 * message (lab idleRenew "no EXISTS after 28 renewals"). The renew DONE must route each untagged line through
 * handleUntagged.
 */
class NativeIdleRenewGuardTest {
    private val src = File("src/main/cpp/liveimap_jni.cpp").readText()

    @Test fun renewDoneRoutesUntaggedThroughHandleUntagged() {
        assertTrue(src.contains("watchRenewDone(session, generation)"))
        val done = src.substringAfter("int watchRenewDone(LiveSession * session, jlong generation) {").substringBefore("\n}\n")
        assertTrue(done.contains("mailimap_token_send(w->imap_stream, \"DONE\")"))
        assertTrue(done.contains("line[0] != '*' || line[1] != ' '"))
        assertTrue(done.contains("handleUntagged(session, data, generation);"))
        // A parse error is a failed renew (WatchLost), not a silently dropped line.
        assertTrue(done.contains("if (prs != MAILIMAP_NO_ERROR) return prs;"))
        assertTrue(done.contains("rsp_type == MAILIMAP_RESP_DONE_TYPE_TAGGED"))
    }

    /** Watch trace notes only while traffic logging is on, and queueWatch takes no lock. */
    @Test fun watchTraceIsGatedAndOutsideTheLock() {
        val trace = src.substringAfter("void watchTrace(LiveSession * session, const std::string & text) {").substringBefore("\n}\n")
        assertTrue(trace.contains("if (session == nullptr || !session->logImapTraffic) return;"))
        val queue = src.substringAfter("void queueWatch(LiveSession * session, int kind,").substringBefore("\n}\n")
        assertFalse(queue.contains("lock_guard"))
        assertEquals(1, Regex("""watchTrace\(session, "untagged parse error""").findAll(src).count())
    }

    /**
     * Lab StartTls idle -> idleRenew: the idle step's stopWatch freed the TLS watch on the caller thread; its
     * failed LOGOUT/SSL_shutdown left OpenSSL errors in that thread's queue, so the next main EXAMINE read failed
     * at once and the keeper reconnected. The new LiveSession restarted watch generations at 1, below the floor
     * Kotlin kept, so publishWatch dropped the idleRenew watch and every EXISTS was discarded.
     */
    @Test fun sslErrorQueueClearedAndGenerationsProcessWide() {
        assertTrue(src.contains("void ERR_clear_error(void);"))
        val finish = src.substringAfter("void finishIdleStop(LiveSession * session) {").substringBefore("\n}\n")
        assertTrue(finish, finish.indexOf("ERR_clear_error();") > finish.indexOf("mailimap_free(session->watch);"))
        val bump = src.substringAfter("uint64_t bumpWatchGeneration(LiveSession * session) {").substringBefore("\n}\n")
        assertTrue(bump, bump.contains("gWatchGenerationSeq.fetch_add"))
        assertTrue(bump, bump.contains("session->watchGeneration"))
        assertTrue(!src.contains("++session->watchGeneration"))
    }
}
