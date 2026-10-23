package org.dlang.liveimap.engine

import org.junit.Assert.assertEquals
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
        val loop = src.substringAfter("void watchMain(LiveSession * session) {").substringBefore("\n}\n")
        val renew = loop.substringAfter("gIdleRenews.fetch_add(1);").substringBefore("fd = mailimap_idle_get_fd")
        assertTrue(renew, renew.contains("watchRenewDone(session, static_cast<jlong>(generation))"))
        assertTrue(renew, !renew.contains("mailimap_idle_done("))
        val done = src.substringAfter("int watchRenewDone(LiveSession * session, jlong generation) {").substringBefore("\n}\n")
        assertTrue(done.contains("mailimap_token_send(w->imap_stream, \"DONE\")"))
        assertTrue(done.contains("line[0] != '*' || line[1] != ' '"))
        assertTrue(done.contains("handleUntagged(session, data, generation);"))
        // A parse error is a failed renew (WatchLost), not a silently dropped line.
        assertTrue(done.contains("if (prs != MAILIMAP_NO_ERROR) return prs;"))
        assertTrue(done.contains("rsp_type == MAILIMAP_RESP_DONE_TYPE_TAGGED"))
    }

    /** Watch trace notes only while traffic logging is on, and never under startMu (they call into Kotlin). */
    @Test fun watchTraceIsGatedAndOutsideTheLock() {
        val trace = src.substringAfter("void watchTrace(LiveSession * session, const std::string & text) {").substringBefore("\n}\n")
        assertTrue(trace.contains("if (session == nullptr || !session->logImapTraffic) return;"))
        val queue = src.substringAfter("void queueWatch(LiveSession * session, int kind,").substringBefore("\n}\n")
        assertTrue(queue.indexOf("watchTraceQueued(") in 0 until queue.indexOf("std::lock_guard"))
        assertEquals(1, Regex("""watchTrace\(session, "untagged parse error""").findAll(src).count())
    }
}
